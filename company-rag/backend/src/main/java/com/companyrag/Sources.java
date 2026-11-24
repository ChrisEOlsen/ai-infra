package com.companyrag;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;

@Service
class Sources {
  final Store store;
  final Ingestion ingestion;
  final List<String> allowed;
  final MeterRegistry metrics;
  final long syncMs;

  Sources(
      Store s,
      Ingestion i,
      @Value("${rag.s3-allowlist}") String a,
      MeterRegistry m,
      @Value("${rag.sync-ms}") long syncMs) {
    store = s;
    ingestion = i;
    allowed = Arrays.stream(a.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    metrics = m;
    this.syncMs = syncMs;
  }

  boolean permitted(String bucket, String prefix) {
    String path = bucket + "/" + prefix;
    return allowed.stream().anyMatch(x -> path.startsWith(x.endsWith("/") ? x : x + "/"));
  }

  @Scheduled(fixedDelay = 3000)
  public void scheduled() {
    for (var source :
        store.query(
            "SELECT * FROM sources WHERE status='Pending' OR synced_at IS NULL OR"
                + " synced_at<now()-(? * interval '1 millisecond')",
            syncMs)) if (store.accepting()) sync(source);
  }

  @io.opentelemetry.instrumentation.annotations.WithSpan("rag.source_sync")
  synchronized void sync(Map<String, Object> source) {
    if (!store.accepting()) return;
    UUID id = (UUID) source.get("id");
    String bucket = source.get("bucket").toString(), prefix = source.get("prefix").toString();
    store.db.update("UPDATE sources SET status='Syncing',error=NULL WHERE id=?", id);
    try (var s3 = S3Client.create()) {
      if (!permitted(bucket, prefix))
        throw new IllegalArgumentException("Source is not in operator allowlist");
      var seen = new HashSet<String>();
      for (var page : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix))) {
        for (var object : page.contents()) {
          if (!Ingestion.supported(object.key())) continue;
          seen.add(object.key());
          if (object.size() > Ingestion.MAX_BYTES) throw new IOException("Document exceeds 20 MB");
          String version = object.eTag() + ":" + object.size() + ":" + object.lastModified();
          var existing =
              store.query(
                  "SELECT id FROM documents WHERE source_id=? AND source_key=? AND version=?",
                  id,
                  object.key(),
                  version);
          if (!existing.isEmpty()) continue;
          try (var input = s3.getObject(b -> b.bucket(bucket).key(object.key()))) {
            ingestion.save(
                (UUID) source.get("collection_id"),
                id,
                object.key(),
                version,
                object.key().substring(object.key().lastIndexOf('/') + 1),
                input);
          }
        }
      }
      // Reconcile deletions only after a complete successful listing and download pass.
      for (var doc : store.query("SELECT id,source_key FROM documents WHERE source_id=?", id))
        if (!seen.contains(doc.get("source_key").toString()))
          ingestion.remove((UUID) doc.get("id"));
      store.db.update(
          "UPDATE sources SET status='Ready',synced_at=now(),error=NULL WHERE id=?", id);
      metrics.counter("rag.source.sync.completed").increment();
    } catch (Exception e) {
      store.db.update(
          "UPDATE sources SET status='Failed',error=?,synced_at=now() WHERE id=?",
          "Synchronization failed. Verify source allowlist, AWS permissions and document sizes.",
          id);
      metrics.counter("rag.source.sync.failed").increment();
    }
  }
}
