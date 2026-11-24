package com.companyrag;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class Store {
  final JdbcTemplate db;

  Store(JdbcTemplate db) {
    this.db = db;
  }

  List<Map<String, Object>> query(String sql, Object... args) {
    return db.queryForList(sql, args).stream()
        .map(
            row -> {
              var out = new LinkedHashMap<String, Object>();
              row.forEach(
                  (k, v) -> {
                    try {
                      out.put(
                          k,
                          v instanceof java.sql.Array a
                              ? Arrays.asList((Object[]) a.getArray())
                              : v);
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  });
              return (Map<String, Object>) out;
            })
        .toList();
  }

  Map<String, Object> one(String sql, Object... args) {
    var rows = query(sql, args);
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    return rows.getFirst();
  }

  String[] roles(Set<String> roles) {
    return roles.toArray(String[]::new);
  }

  boolean allowed(UUID collection, Set<String> roles) {
    return Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM collections WHERE id=? AND roles && ?::text[])",
            Boolean.class,
            collection,
            roles(roles)));
  }

  void require(UUID collection, Set<String> roles) {
    if (!allowed(collection, roles)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  boolean accepting() {
    return Boolean.TRUE.equals(
        db.queryForObject("SELECT accepting FROM app_state WHERE id=1", Boolean.class));
  }

  void running() {
    if (!accepting())
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Deployment paused");
  }
}
