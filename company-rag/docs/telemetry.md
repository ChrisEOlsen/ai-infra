# Telemetry

Default path: OpenTelemetry Java agent + Micrometer OTLP → Grafana Alloy → Grafana Cloud. Grafana is the viewer; Grafana Cloud provides metrics/logs/traces storage. No dashboard is publicly exposed by the deployment.

Set the runtime secret values:

```
GRAFANA_OTLP_ENDPOINT=https://<your-stack-otlp-endpoint>/otlp
GRAFANA_INSTANCE_ID=<instance-id>
GRAFANA_TOKEN=<scoped-telemetry-ingestion-token>
```

Add these non-secret environment settings to customer configuration:

```
COMPOSE_PROFILES=telemetry
TELEMETRY_ENABLED=true
OTEL_TRACES_EXPORTER=otlp
OTEL_LOGS_EXPORTER=otlp
```

For local Compose, these may be placed in `.env` and started with `--profile telemetry`. The remote systemd service uses COMPOSE_PROFILES from the generated environment file. The Java agent captures HTTP/JDBC traces; Micrometer exports JVM, connection pool and business metrics. It intentionally does not run a second automatic metrics exporter. Collector ports and the Spring management port are not published.

Import `deploy/grafana/dashboard.json` into your Grafana stack and select the metrics datasource. The template assumes standard OTLP-to-Prometheus naming; inspect the destination's metric explorer if your stack changes suffix translation. Expected application names are `rag.ingestion.backlog`, `rag.ingestion.completed`, `rag.ingestion.failed`, `rag.source.sync.completed`, `rag.source.sync.failed`, `rag.chat.failed`, `rag.chat.duration`, `rag.embedding.duration`, and `rag.model.tokens` (kind: prompt/completion/total). Model tokens are usage reported by the provider; cost estimates require maintaining provider prices and are not invoices.

Suggested alerts: nonzero failed-ingestion increase, a sustained backlog, increased chat failures, low disk space, and missing application metrics. Configure notification destinations in Grafana; deployment does not send alerts to others automatically.

Do not enable HTTP header/body capture or database parameter capture. No document/user IDs are used as metric labels. Do not add prompts, retrieved passages, answers, secrets or employee emails to spans/logs. Set a bounded retention and sampling policy in the destination. Export is batched and bounded so telemetry failure does not block chat. Local Docker logs rotate at 10 MB × 3 files per service.

Optional self-hosting later: replace the destination with Grafana plus Prometheus/Mimir, Loki and Tempo. This requires its own capacity, retention, authentication and backups and is not silently added to the customer server. Remote telemetry remains governed by its provider after decommissioning.
