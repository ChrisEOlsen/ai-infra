package com.companyrag;

import com.fasterxml.jackson.databind.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class Models {
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  final ObjectMapper json;
  final MeterRegistry metrics;
  final String base, key, chat, embedding;
  final int dimensions, maxOutput;
  final boolean mock;

  Models(
      ObjectMapper j,
      MeterRegistry m,
      @Value("${rag.api-base}") String b,
      @Value("${rag.api-key}") String k,
      @Value("${rag.chat-model}") String c,
      @Value("${rag.embedding-model}") String e,
      @Value("${rag.embedding-dimensions}") int d,
      @Value("${rag.max-output-tokens}") int max,
      @Value("${rag.mock-model}") boolean mock,
      Store store) {
    json = j;
    metrics = m;
    base = b.replaceAll("/$", "");
    key = k;
    chat = c;
    embedding = e;
    dimensions = d;
    maxOutput = max;
    this.mock = mock;
    store.db.update(
        "INSERT INTO embedding_profile(id,provider,model,dimensions,mock) VALUES(1,?,?,?,?) ON"
            + " CONFLICT(id) DO NOTHING",
        base,
        embedding,
        dimensions,
        mock);
    var profile = store.one("SELECT * FROM embedding_profile WHERE id=1");
    if (!base.equals(profile.get("provider"))
        || !embedding.equals(profile.get("model"))
        || dimensions != ((Number) profile.get("dimensions")).intValue()
        || mock != (Boolean) profile.get("mock"))
      throw new IllegalStateException(
          "Embedding configuration changed. Reindex the collection before starting with the new"
              + " profile; see docs/operations.md.");

    if (!mock && !base.startsWith("https://"))
      throw new IllegalArgumentException("Model API must use HTTPS");
  }

  HttpRequest request(String route, Object body) throws Exception {
    if (key.isBlank())
      throw new IllegalStateException("Hosted model credentials are not configured");
    return HttpRequest.newBuilder(URI.create(base + route))
        .timeout(Duration.ofSeconds(120))
        .header("Authorization", "Bearer " + key)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
        .build();
  }

  @io.opentelemetry.instrumentation.annotations.WithSpan("rag.embedding")
  List<double[]> embed(List<String> text) throws Exception {
    if (mock) return text.stream().map(this::mockVector).toList();
    long start = System.nanoTime();
    try {
      var response =
          http.send(
              request("/embeddings", Map.of("model", embedding, "input", text)),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200)
        throw new IOException("Embedding provider returned " + response.statusCode());
      var node = json.readTree(response.body());
      var result = new ArrayList<double[]>();
      var data = new ArrayList<JsonNode>();
      node.path("data").forEach(data::add);
      data.sort(Comparator.comparingInt(n -> n.path("index").asInt()));
      for (var n : data) {
        var vector = json.convertValue(n.path("embedding"), double[].class);
        if (vector.length != dimensions)
          throw new IOException(
              "Embedding dimensions mismatch; configuration and reindexing required");
        result.add(vector);
      }
      if (result.size() != text.size())
        throw new IOException("Embedding provider returned incomplete batch");
      usage(node);
      return result;
    } finally {
      metrics
          .timer("rag.embedding.duration")
          .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  double[] mockVector(String text) {
    var v = new double[dimensions];
    for (var w : text.toLowerCase().split("\\W+")) v[Math.floorMod(w.hashCode(), dimensions)] += 1;
    v[0] += .01;
    return v;
  }

  static String vector(double[] v) {
    return Arrays.toString(v);
  }

  void usage(JsonNode n) {
    var u = n.path("usage");
    for (var field : List.of("prompt_tokens", "completion_tokens", "total_tokens"))
      if (u.has(field))
        metrics.counter("rag.model.tokens", "kind", field).increment(u.path(field).asDouble());
  }

  InputStream stream(List<Map<String, String>> messages) throws Exception {
    if (mock) {
      String answer = "Mock response for integration testing. " + messages.getLast().get("content");
      String data =
          json.writeValueAsString(
              Map.of("choices", List.of(Map.of("delta", Map.of("content", answer)))));
      return new ByteArrayInputStream(
          ("data: " + data + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
    }
    var response =
        http.send(
            request(
                "/chat/completions",
                Map.of(
                    "model",
                    chat,
                    "messages",
                    messages,
                    "stream",
                    true,
                    "stream_options",
                    Map.of("include_usage", true),
                    "max_tokens",
                    maxOutput)),
            HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      response.body().close();
      throw new IOException("Chat provider returned " + response.statusCode());
    }
    return response.body();
  }
}
