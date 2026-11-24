package com.companyrag;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class Ingestion {
  static final long MAX_BYTES = 20L * 1024 * 1024;
  final Store store;
  final Models models;
  final Path directory;
  final TransactionTemplate tx;
  final MeterRegistry metrics;

  Ingestion(
      Store s,
      Models m,
      @Value("${rag.storage}") String path,
      PlatformTransactionManager tm,
      MeterRegistry metrics)
      throws IOException {
    store = s;
    models = m;
    directory = Path.of(path).toAbsolutePath();
    Files.createDirectories(directory);
    tx = new TransactionTemplate(tm);
    this.metrics = metrics;
  }

  @org.springframework.context.event.EventListener(
      org.springframework.boot.context.event.ApplicationReadyEvent.class)
  public void recoverInterruptedJobs() {
    store.db.update("UPDATE documents SET status='Queued',lease_at=NULL WHERE status='Processing'");
    store.db.update("UPDATE sources SET status='Pending' WHERE status='Syncing'");
  }

  static boolean supported(String name) {
    return name != null && name.toLowerCase(Locale.ROOT).matches(".*\\.(pdf|docx|md|txt)$");
  }

  UUID save(
      UUID collection,
      UUID source,
      String sourceKey,
      String version,
      String name,
      InputStream input)
      throws IOException {
    if (!supported(name))
      throw new IllegalArgumentException("Supported formats: PDF, DOCX, Markdown and text");
    UUID id = UUID.randomUUID();
    if (source != null) {
      var rows =
          store.query(
              "SELECT id,version,status FROM documents WHERE source_id=? AND source_key=?",
              source,
              sourceKey);
      if (!rows.isEmpty()) {
        id = (UUID) rows.getFirst().get("id");
        if (Objects.equals(version, rows.getFirst().get("version"))) return id;
      }
    }
    Path temp = Files.createTempFile(directory, "upload-", ".tmp");
    try (var out = Files.newOutputStream(temp)) {
      long copied =
          input.transferTo(
              new FilterOutputStream(out) {
                long size;

                public void write(byte[] b, int off, int len) throws IOException {
                  if ((size += len) > MAX_BYTES) throw new IOException("Document exceeds 20 MB");
                  out.write(b, off, len);
                }

                public void write(int b) throws IOException {
                  if (++size > MAX_BYTES) throw new IOException("Document exceeds 20 MB");
                  out.write(b);
                }
              });
    } catch (Exception e) {
      Files.deleteIfExists(temp);
      throw e;
    }
    Path target = directory.resolve(id.toString());
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    store.db.update(
        "INSERT INTO documents(id,collection_id,source_id,source_key,version,name,path)"
            + " VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
            + " version=excluded.version,name=excluded.name,status='Queued',error=NULL,updated_at=now()",
        id,
        collection,
        source,
        sourceKey,
        version,
        name,
        target.toString());
    return id;
  }

  static List<String> split(String text) {
    var result = new ArrayList<String>();
    for (int start = 0; start < text.length(); start += 1000) {
      int end = Math.min(start + 1200, text.length());
      String part = text.substring(start, end).trim();
      if (!part.isBlank()) result.add(part);
      if (end == text.length()) break;
    }
    return result;
  }

  @Scheduled(fixedDelay = 1500)
  @io.opentelemetry.instrumentation.annotations.WithSpan("rag.ingestion")
  public void process() {
    if (!store.accepting()) return;
    var rows =
        store.query(
            "UPDATE documents SET status='Processing',lease_at=now(),error=NULL WHERE id=(SELECT id"
                + " FROM documents WHERE status='Queued' OR (status='Processing' AND"
                + " lease_at<now()-interval '20 minutes') ORDER BY updated_at LIMIT 1 FOR UPDATE"
                + " SKIP LOCKED) RETURNING *");
    if (rows.isEmpty()) return;
    var doc = rows.getFirst();
    var id = (UUID) doc.get("id");
    long start = System.nanoTime();
    try {
      var tika = new Tika();
      tika.setMaxStringLength(300000);
      String text;
      try (var input = Files.newInputStream(Path.of(doc.get("path").toString()))) {
        text = tika.parseToString(input);
      }
      var chunks = split(text);
      if (chunks.isEmpty()) throw new IOException("No extractable text; scanned PDFs require OCR");
      var vectors = new ArrayList<double[]>();
      for (int i = 0; i < chunks.size(); i += 16)
        vectors.addAll(models.embed(chunks.subList(i, Math.min(i + 16, chunks.size()))));
      tx.executeWithoutResult(
          status -> {
            var current = store.query("SELECT id FROM documents WHERE id=? FOR UPDATE", id);
            if (current.isEmpty()) return;
            store.db.update("DELETE FROM chunks WHERE document_id=?", id);
            for (int i = 0; i < chunks.size(); i++)
              store.db.update(
                  "INSERT INTO chunks(document_id,ordinal,content,embedding,embedding_model)"
                      + " VALUES(?,?,?,?::vector,?)",
                  id,
                  i,
                  chunks.get(i),
                  Models.vector(vectors.get(i)),
                  models.embedding);
            store.db.update(
                "UPDATE documents SET status='Ready',error=NULL,updated_at=now() WHERE id=?", id);
          });
      metrics.counter("rag.ingestion.completed").increment();
    } catch (Exception e) {
      store.db.update(
          "UPDATE documents SET status='Failed',error=?,updated_at=now() WHERE id=?",
          safeError(e),
          id);
      metrics.counter("rag.ingestion.failed").increment();
    } finally {
      metrics
          .timer("rag.ingestion.duration")
          .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  static String safeError(Exception e) {
    return e instanceof IOException
            && e.getMessage() != null
            && (e.getMessage().startsWith("No extractable")
                || e.getMessage().startsWith("Embedding")
                || e.getMessage().startsWith("Document exceeds"))
        ? e.getMessage()
        : "Processing failed. Check provider configuration, file format and service health; retry"
            + " when resolved.";
  }

  void remove(UUID id) throws IOException {
    var rows = store.query("DELETE FROM documents WHERE id=? RETURNING path", id);
    for (var row : rows) Files.deleteIfExists(Path.of(row.get("path").toString()));
  }
}
