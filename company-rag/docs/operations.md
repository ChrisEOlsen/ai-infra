# AWS operations

## Ownership and prerequisites

One customer deployment per AWS account/environment is the simplest isolation boundary. The operator needs AWS CLI v2 with federated credentials, Terraform >=1.10, Docker with Buildx, and Python 3.9+. No inbound SSH is opened. SSM Session Manager is the administration path.

Terraform uses two independent local backends selected by the operator CLI: durable **storage** (encrypted EBS, private versioned artifact/backup bucket, runtime-secret metadata and image repositories) and disposable **runtime** (VPC, EC2, IAM role, Elastic IP and release object). State and Terraform working data are isolated per customer under `.runtime/CUSTOMER/` (override the base with `COMPANY_RAG_STATE_DIR`). Each operation reinitializes the matching backend and checks its customer identity. Protect/back up this directory. Do not run deployment from two computers with divergent local state. For team use, migrate each backend to separately keyed, encrypted S3 state with native locking before sharing operations. Remote-state infrastructure is not automatically added by this kit.

The EC2 instance uses IMDSv2 and an instance role scoped to the runtime secret, owned release/backup prefixes, customer image repositories and explicitly approved S3 source prefixes. ECR token retrieval and SSM baseline permissions require service-defined wildcard resources. Customer source buckets are never Terraform-managed resources. KMS-encrypted sources require adding the specific key permission and customer-side key policy. HTTPS is publicly reachable by default; configure ingress CIDRs in Terraform for customers requiring restricted access. HTTP 80 is used for certificate validation/redirect. No NAT Gateway, load balancer or managed database is provisioned.

## Provision

1. Copy `examples/customer.json` into a private configuration location. Set customer slug, region/AZ, real DNS hostname, SMTP settings and approved S3 sources. Region/AZ must agree. The chosen x86-64 instance must be available in that AZ; the example is `t3.large` with standard CPU credit mode to avoid unlimited-credit surprise charges.
2. Run `./ops/rag storage /private/customer.json`. Review and approve the Terraform plan. This creates **billable resources**.
3. Prepare a private JSON credential file with strong generated values for POSTGRES_PASSWORD, RAG_DB_PASSWORD, KEYCLOAK_DB_PASSWORD, OIDC_CLIENT_SECRET, KEYCLOAK_ADMIN_PASSWORD, COMPANY_ADMIN_PASSWORD, PROVISIONER_SECRET, MODEL_API_KEY, and SMTP credentials as needed. Use mode 0600. Never commit it. `ops/local-init` shows the equivalent local settings; do not reuse a development customer's passwords in production.
4. Run `./ops/rag set-secret /private/customer.json --secret-file /private/credentials.json`. It uploads credentials to Secrets Manager without printing them or putting values into Terraform state. Securely dispose of the temporary file according to your credential policy.
5. Run `./ops/rag deploy /private/customer.json`. This builds versioned Linux amd64 application images, pushes them to customer-specific ECR repositories, packages bootstrap files, and applies the runtime Terraform plan. Review/approve the plan.
6. If `zone_id` references an existing Route 53 zone in customer configuration, Terraform creates only the assistant A record. Otherwise point the customer's DNS A record at the printed Elastic IP. Caddy obtains and renews HTTPS certificates. The domain must resolve publicly and reach ports 80/443 for automatic issuance.
7. Run `./ops/readiness https://assistant.example.com`. It checks OIDC discovery and the backend's unauthenticated API response. Complete the initial `admin` account's password change and email verification. Check SMTP delivery, one real hosted embedding/chat request, upload and role-based retrieval before handoff.

Terraform apply alone does not guarantee application readiness. Bootstrap logs: `/var/log/cloud-init-output.log`. Service: `systemctl status company-rag`. Compose logs: `cd /opt/company-rag && docker compose --env-file .env -f deploy/compose.yml logs --tail=100`. Do not print `.env` or secrets in support tickets. AWS CLI/SSM audit records track operator actions. Enable source-bucket CloudTrail data events/access logging where customer audit requirements call for them; these may introduce additional charges.

## Backups and restore

A daily systemd timer runs a verified backup. It briefly stops the backend and Keycloak, dumps both databases, restores each dump into a temporary validation database, packages documents with checksums, and uploads to the private owned bucket. This interrupts login/chat during the backup; choose a quiet maintenance window. A failed verification fails the backup job. Docker logs and the systemd service expose failures for monitoring. Backup objects have no automatic expiry in v1; set a customer-approved retention lifecycle before long-term operation.

Manual backup: `./ops/rag backup /private/customer.json`.

Restore **overwrites current application/identity data**:

```bash
./ops/rag restore /private/customer.json \
  --archive s3://OWNED-BUCKET/backups/TIMESTAMP.tar.gz --confirm CUSTOMER
```

Only backups under the owned bucket's backup prefix are accepted. Archives and checksums are validated before the app stops. Restore retains the previous document directory on disk for inspection; it continues consuming storage until explicitly removed. Restore needs the original runtime credentials (database owners and client secrets); retaining a backup without its corresponding runtime secret is insufficient for a full recovery. Test restore on an isolated clone before a live restore. Schema downgrade compatibility is not assumed.

## Pause and resume

```bash
./ops/rag pause /private/customer.json
./ops/rag resume /private/customer.json
```

Pause verifies instance ownership tags, blocks new work in the database, stops the backup timer and Compose services, then stops EC2. It fails if SSM cannot perform the graceful shutdown; operators may use AWS emergency stop directly during an incident, acknowledging abrupt termination of in-flight work. Resume starts EC2, refreshes runtime secrets, starts the application/timer and re-enables work. Run readiness afterward.

Paused deployments still incur charges for allocated EBS, backups, Elastic IP, secrets, image storage and retained telemetry. Pause is not decommissioning.

## Decommission

Review and revoke dedicated hosted-model, SMTP and customer-side connector credentials first. This generic kit cannot revoke every external provider's API keys; `--credentials-revoked` is an explicit operator acknowledgement that they were revoked or intentionally retained because they are shared. Do not revoke shared credentials indiscriminately.

```bash
./ops/rag decommission /private/customer.json \
  --retain-data --confirm CUSTOMER --credentials-revoked

# Permanently remove owned documents, databases, backups and images instead:
./ops/rag decommission /private/customer.json \
  --delete-data --confirm CUSTOMER --credentials-revoked
```

Both require explicit customer confirmation. If running, a final verified backup is required before destruction. Runtime destruction terminates compute and releases its Elastic IP. The CLI waits for termination, checks for remaining deployment-tagged IPs, and records the result in `.runtime/CUSTOMER/decommission-report.json`.

`--retain-data` leaves the storage state intact, including the data volume, backup bucket, runtime secret and image repositories. These remain chargeable and must be tracked. Redeploy from this state to resume the retained dataset. `--delete-data` additionally checks bucket ownership, purges all object versions/delete markers and customer-specific images, then destroys storage resources. Secrets Manager retains a 7-day recovery window; report and verify its eventual deletion. AWS resources created manually outside these states are outside the CLI's inventory and must be reviewed separately.

Customer source buckets, shared infrastructure, external model-provider data, Grafana retention and local Terraform state are not deleted. Do not promise zero bills until all retained/external resources and the AWS account inventory have been reviewed.

## Updates

Run deployment again to build a new immutable release. Review instance replacement in the plan: durable data is in the independent storage state and reattaches in the same AZ. Make a verified backup first. Database schema upgrades use Flyway. Keycloak realm import is initial bootstrap only and ignores an existing realm; changing roles/clients afterward uses its admin console/API or an explicit migration. Changing environment passwords does not automatically rotate existing PostgreSQL roles or Keycloak credentials—coordinate those changes before updating runtime secrets.

## Changing embedding models

Embedding provider/model/dimensions and mock mode are recorded in the database. Startup refuses a changed profile to prevent silently comparing incompatible vectors. After taking a backup, update model settings and run `ops/reindex --confirm-reindex` from the deployment directory (through SSM on AWS). This discards vectors and queues all original documents for re-embedding. Chat may return no supporting documents until indexing completes. Hosted embedding charges apply again. Chat model changes alone do not require reindexing.
