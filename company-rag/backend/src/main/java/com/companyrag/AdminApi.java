package com.companyrag;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AdminApi {
  final Store store;
  final Access access;
  final Ingestion ingestion;
  final Sources sources;

  AdminApi(Store s, Access a, Ingestion i, Sources sources, MeterRegistry metrics) {
    store = s;
    access = a;
    ingestion = i;
    this.sources = sources;
    metrics.gauge(
        "rag.ingestion.backlog",
        store,
        s2 ->
            s2.db.queryForObject(
                "SELECT count(*) FROM documents WHERE status IN ('Queued','Processing')",
                Long.class));
  }

  record CollectionInput(String name, List<String> roles) {}

  record SourceInput(UUID collection_id, String bucket, String prefix) {}

  @GetMapping("/api/me")
  Map<String, Object> me(Authentication a) {
    return Map.of("subject", access.subject(a), "roles", access.roles(a), "name", a.getName());
  }

  @GetMapping("/api/collections")
  List<Map<String, Object>> collections(Authentication a) {
    return store.query(
        "SELECT * FROM collections WHERE roles && ?::text[] ORDER BY name",
        (Object) store.roles(access.roles(a)));
  }

  @GetMapping("/api/admin/collections")
  List<Map<String, Object>> adminCollections(Authentication a) {
    access.admin(a);
    return store.query("SELECT * FROM collections ORDER BY name");
  }

  @PostMapping("/api/admin/collections")
  Map<String, Object> createCollection(Authentication a, @RequestBody CollectionInput in) {
    access.admin(a);
    validate(in);
    UUID id = UUID.randomUUID();
    store.db.update(
        "INSERT INTO collections VALUES(?,?,?::text[])",
        id,
        in.name().trim(),
        in.roles().toArray(String[]::new));
    return store.one("SELECT * FROM collections WHERE id=?", id);
  }

  @PutMapping("/api/admin/collections/{id}")
  void updateCollection(Authentication a, @PathVariable UUID id, @RequestBody CollectionInput in) {
    access.admin(a);
    validate(in);
    store.one("SELECT id FROM collections WHERE id=?", id);
    store.db.update(
        "UPDATE collections SET name=?,roles=?::text[] WHERE id=?",
        in.name().trim(),
        in.roles().toArray(String[]::new),
        id);
  }

  void validate(CollectionInput i) {
    if (i.name() == null
        || i.name().isBlank()
        || i.name().length() > 100
        || i.roles() == null
        || i.roles().isEmpty()
        || i.roles().stream().anyMatch(r -> !r.matches("[a-zA-Z0-9_-]{1,64}")))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Name and at least one valid role required");
  }

  @GetMapping("/api/admin/documents")
  List<Map<String, Object>> documents(Authentication a) {
    access.admin(a);
    return store.query(
        "SELECT d.id,d.name,d.status,d.error,d.updated_at,d.collection_id,c.name AS collection_name"
            + " FROM documents d JOIN collections c ON c.id=d.collection_id ORDER BY d.updated_at"
            + " DESC");
  }

  @PostMapping("/api/admin/documents")
  Map<String, Object> upload(
      Authentication a, @RequestParam UUID collection, @RequestParam MultipartFile file)
      throws IOException {
    access.admin(a);
    store.running();
    store.one("SELECT id FROM collections WHERE id=?", collection);
    try (var input = file.getInputStream()) {
      return Map.of(
          "id", ingestion.save(collection, null, null, null, file.getOriginalFilename(), input));
    }
  }

  @PostMapping("/api/admin/documents/{id}/retry")
  void retry(Authentication a, @PathVariable UUID id) {
    access.admin(a);
    store.running();
    store.db.update(
        "UPDATE documents SET status='Queued',error=NULL WHERE id=? AND status='Failed'", id);
  }

  @DeleteMapping("/api/admin/documents/{id}")
  void delete(Authentication a, @PathVariable UUID id) throws IOException {
    access.admin(a);
    ingestion.remove(id);
  }

  @GetMapping("/api/documents/{id}")
  ResponseEntity<FileSystemResource> download(Authentication a, @PathVariable UUID id) {
    var d = store.one("SELECT * FROM documents WHERE id=?", id);
    store.require((UUID) d.get("collection_id"), access.roles(a));
    String name = d.get("name").toString();
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(name, java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header("X-Content-Type-Options", "nosniff")
        .body(new FileSystemResource(d.get("path").toString()));
  }

  @GetMapping("/api/admin/sources")
  List<Map<String, Object>> sources(Authentication a) {
    access.admin(a);
    return store.query(
        "SELECT s.*,c.name AS collection_name FROM sources s JOIN collections c ON"
            + " c.id=s.collection_id ORDER BY c.name");
  }

  @PostMapping("/api/admin/sources")
  Map<String, Object> addSource(Authentication a, @RequestBody SourceInput in) {
    access.admin(a);
    store.running();
    if (in.bucket() == null
        || !in.bucket().matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")
        || in.prefix() == null
        || (!in.prefix().isEmpty() && !in.prefix().endsWith("/"))
        || !sources.permitted(in.bucket(), in.prefix()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Use an operator-approved bucket and folder prefix ending in /");
    store.one("SELECT id FROM collections WHERE id=?", in.collection_id());
    UUID id = UUID.randomUUID();
    store.db.update(
        "INSERT INTO sources(id,collection_id,bucket,prefix) VALUES(?,?,?,?)",
        id,
        in.collection_id(),
        in.bucket(),
        in.prefix());
    return Map.of("id", id);
  }

  @PostMapping("/api/admin/sources/{id}/sync")
  void sync(Authentication a, @PathVariable UUID id) {
    access.admin(a);
    store.running();
    store.db.update("UPDATE sources SET status='Pending' WHERE id=?", id);
  }

  @DeleteMapping("/api/admin/sources/{id}")
  void deleteSource(Authentication a, @PathVariable UUID id) throws IOException {
    access.admin(a);
    for (var d : store.query("SELECT id FROM documents WHERE source_id=?", id))
      ingestion.remove((UUID) d.get("id"));
    store.db.update("DELETE FROM sources WHERE id=?", id);
  }

  @PostMapping("/api/admin/state")
  void state(Authentication a, @RequestBody Map<String, Boolean> input) {
    access.admin(a);
    store.db.update(
        "UPDATE app_state SET accepting=? WHERE id=1", Boolean.TRUE.equals(input.get("accepting")));
  }
}
