# Verification record

Validated locally on 2026-11-18.

| Check | Result |
|---|---|
| Spring Boot integration suite against disposable PostgreSQL/pgvector | 10 tests passed |
| Operator safeguards and per-customer Terraform/secret isolation | 3 tests passed |
| Terraform runtime mocked plan | Passed |
| Terraform retained-storage mocked plan | Passed |
| Angular production build | Passed |
| Pinned llama frontend production build | Passed |
| Backend and web container builds | Passed |
| Browser: Keycloak sign-in, collection creation, file upload, indexing, streamed llama chat and citations | Passed with a mock hosted-model provider |
| Browser: desktop/mobile layout, no horizontal overflow, unauthenticated API rejection | Passed |
| Verified backup and restore of both databases and documents | Passed with an offline S3 stand-in |
| Caddy and Alloy configuration validation | Passed |

The backend suite includes role-filtered retrieval before generation, forbidden downloads, ignored client assistant/system context, collection access changes, deletion, pause behavior, CSRF, token expiration, S3 update/deletion/partial-failure handling and embedding-profile change protection.

No billable AWS infrastructure was provisioned. Live AWS bootstrap/decommission, real model-provider calls and Grafana Cloud delivery require customer credentials and a target account/domain. A mocked plan or local S3 stand-in does not replace those customer-environment checks. The local preview uses synthetic documents and deterministic mock inference.

Repeat with `ops/check`, then the browser tests described in the README. The local backup/restore harness is `tests/backup-roundtrip --confirm-local-test`; it briefly stops and restores the development stack and never contacts AWS.
