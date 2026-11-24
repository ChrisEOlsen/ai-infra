# Company RAG

A customer-scoped knowledge assistant: Angular Material administration, the existing llama chat frontend, Spring Boot, Keycloak, PostgreSQL/pgvector, and hosted OpenAI-compatible chat and embedding APIs. There are no local models or GPU requirements.

```
Browser → HTTPS reverse proxy → Angular admin / llama chat
                           → Spring Boot → hosted embeddings and chat
                           → Keycloak
Spring Boot → PostgreSQL/pgvector and private document storage
            → read-only approved S3 document sources
Telemetry → Alloy → Grafana Cloud
```

## Local start

Requires Docker Compose and Python 3.9+. Building without Docker additionally needs JDK 21+, Maven, Node 24+ and npm.

```bash
cd company-rag
./ops/local-init
# Edit .env: set MODEL_API_KEY and select compatible CHAT_MODEL / EMBEDDING_MODEL.
./ops/dev
```

For a completely offline provider simulation (no paid model calls): `./ops/dev --mock`. The mock is deterministic test plumbing, not a usable language model. Images and dependencies still require internet access when first built. AWS deployment rejects mock mode.

- Admin: http://localhost:8080/admin/
- Chat: http://localhost:8080/chat/
- Keycloak: http://localhost:8080/auth/admin/
- Local email inbox: http://localhost:8025 (never deploy Mailpit in production)

The initial company user is `admin`; its temporary password is `COMPANY_ADMIN_PASSWORD` in `.env`. Complete password change and email verification using the local inbox. The separate Keycloak operator is `operator`, with `KEYCLOAK_ADMIN_PASSWORD`. Replace the temporary master administrator after bootstrapping and restrict administrative access in production. No Keycloak theme modifications are included.

Open Collections, create a collection with role `employee`, then upload a text PDF, DOCX, Markdown or TXT file. Processing appears in the document table. Open chat when the document reaches Ready. Unsupported/scanned PDFs produce a recoverable processing failure; OCR is outside v1. Limits: 20 MB per file, 300,000 extracted characters, six retrieved passages and eight simultaneous model generations. S3 synchronization is eventually consistent, not instantaneous.

`docker compose --env-file .env -f deploy/compose.yml down` stops the local stack without deleting bind-mounted data. Keep `.env` and `.runtime` private and out of Git. Docker Compose's default development project name is `company-rag`; do not start two local copies under the same project name.

## What happens when you upload a document

1. **Authorize and validate.** Spring Boot checks that you are a company administrator, verifies the selected collection exists, and accepts supported files up to 20 MB.
2. **Save and queue.** The original file is saved on persistent server storage. PostgreSQL stores its metadata and collection association, and its status becomes **Queued**.
3. **Extract text.** A background worker picks up queued documents and uses Apache Tika to extract text. Scanned PDFs need OCR, which is not included in v1.
4. **Split into passages.** Extracted text is divided into passages of up to 1,200 characters, with 200 characters of overlap to retain context across boundaries.
5. **Generate embeddings.** The worker sends text passages to the configured hosted embedding API in batches. The provider returns numerical vectors representing the passages. PostgreSQL with pgvector stores the passage text, vectors and embedding model identifier.
6. **Make searchable.** The worker marks the document **Ready** after storing its passages and vectors. Processing errors mark it **Failed**; administrators can retry after resolving the cause. The dashboard refreshes processing status automatically.

Each document belongs to one collection and inherits its allowed roles; there are no separate per-document permissions in v1. Connected S3 sources also feed their selected collection through the same indexing pipeline.

When an employee asks a question, the backend embeds the question and searches for relevant passages across the collections their Keycloak roles allow. Permission filtering happens in PostgreSQL before document content reaches the chat model. The backend sends the retrieved passages and question context to the hosted chat API, streams the answer, and appends links to the retrieved source documents. Opening those links requires the same collection access.

This process indexes documents; it does not train or fine-tune the model. Document text is sent to the configured embedding provider during indexing, and retrieved passages are sent to the chat provider when answering questions. In local `--mock` mode, both embeddings and chat responses are simulated without calling a model provider; this mode is for integration testing rather than useful AI answers.

## Access model

Each document belongs to a collection. Collections list allowed Keycloak realm roles. An employee needs a matching role; `company-admin` grants administration capabilities, not automatic read access to every collection. It is assigned `employee` as well for the initial account.

Every retrieval joins current collection permissions in PostgreSQL before document content reaches the model. Downloads use the same authorization checks. Client-supplied system prompts, model IDs, tools and assistant messages are ignored. Prior user questions can guide follow-up retrieval; previous assistant answers are not replayed as trusted context after permission changes.

The chat UI stores conversations in a browser database isolated by authenticated application session. v1 does not provide cross-device/server-side conversation history. An application session ends when its five-minute Keycloak access token expires; sign-in then uses the existing Keycloak session. Role changes and disabling accounts take effect no later than that token window. Collection access edits apply immediately to new retrieval/download requests. Content already displayed or sent in an in-flight request cannot be recalled.

Local browser storage is not protection against someone controlling the device or its developer tools. Use separate OS/browser profiles on shared devices. Offline service-worker caching is disabled.

## Employees

Use the Keycloak console for individual accounts, required-action emails and role assignments. Or use the operator provisioning tool:

```bash
# Obtain PROVISIONER_SECRET from the runtime secret securely; do not put it in shell history.
./ops/employees --url https://assistant.example.com/auth --email alice@example.com --roles employee,finance
./ops/employees --url https://assistant.example.com/auth --csv examples/employees.csv
```

The provisioner creates accounts without passwords and sends a 24-hour setup email. Existing accounts are updated without duplicate invitations. `employee_id` is the stable identity for database/CSV synchronization. CSV columns: employee_id,email,roles,active. `active=false` disables an account and ends Keycloak sessions. Application sessions expire within the token window described above. The tool reconciles business roles; the operator must review source data and role mappings. Assigning `company-admin` requires `--allow-admin`. The provisioning service account is privileged and must never be exposed to browsers.

## Existing S3 documents

Operators approve bucket/prefix pairs through customer configuration and scoped AWS IAM. Administrators may then connect only those approved locations to a collection. Prefixes must end in `/`; use an empty prefix only when whole-bucket access is intended. The application uses the SDK credential chain (EC2 instance role on AWS); never upload AWS keys through the UI.

S3 source permissions do not automatically become employee permissions. Each source uses its assigned collection. Deleting a connected source removes its indexed documents, not the original S3 objects. Deleting an individual indexed S3 document is temporary: it will be imported again at the next synchronization if it still exists in the source. Failed source scans do not reconcile deletions, avoiding accidental mass deletion after a partial listing failure. Cross-account buckets and KMS-encrypted sources also need customer-side resource/key permissions.

## Deployment and shutdown

See [AWS operations](docs/operations.md). Terraform has separate storage and runtime states so compute can be removed while customer data is retained. Bootstrap fetches runtime secrets; it does not place credentials in user data or Terraform variables. Images are versioned and built before the customer server starts.

The initial deployment is a single server. PostgreSQL, Keycloak and the app need memory; the AWS example starts at 8 GB RAM. It is not highly available. Infrastructure and hosted model API charges are separate.

## Telemetry

See [telemetry](docs/telemetry.md). OpenTelemetry instruments Java operations; Micrometer exports application/JVM metrics. Alloy is the optional Compose telemetry profile and forwards to Grafana Cloud. Telemetry is disabled until a destination is configured. Customer document text, prompts, answers, credentials and emails must not be added to telemetry. Grafana Cloud is an external destination with its own retention/costs.

## Verification

```bash
./ops/check
# Build images and exercise real login/admin/chat in a browser:
./ops/dev --mock
cd admin && npm run e2e
```

The backend integration suite requires Docker and uses a disposable PostgreSQL/pgvector container. It checks authentication, CSRF, collection administration, permission-filtered retrieval, forbidden downloads, prompt isolation, ingestion, deletion, pause behavior, S3 allowlisting and token expiration. Mock inference keeps tests independent of provider credentials and charges. A real provider smoke test requires your API credentials and can incur charges.

See the [verification record](docs/verification.md) for the tested paths and remaining live-environment checks.

The upstream llama frontend is pinned in `chat/upstream.json`, built by `chat/prepare.py`, with the MIT attribution preserved in its output. Changes to upstream require repeating compatibility and browser tests.
