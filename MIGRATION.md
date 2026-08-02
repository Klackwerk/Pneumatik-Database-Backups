# Migration Guide

Technical reference for upgrading Pneumatik across major versions and for the
platform changes behind them. Release notes live in [CHANGELOG.md](CHANGELOG.md).

## Architecture (since 2.0)

- **`api/`** — Grails 7.2.1 REST API (package `de.klackwerk.pneumatik`), JSON
  only, all endpoints under `/api/v1/...`
- **`app/`** — Vite + React + TypeScript + shadcn/ui frontend; the typed API
  client is generated from `api/src/main/resources/openapi.yaml`
  (`npm run generate:api`)
- Shipped as one container image: the built frontend is bundled into the API
  jar and served by the application itself.

Platform: Grails 7.2.1 · Groovy 4.0.32 · Spring Boot 3.5.16 · Hibernate
5.6.15.Final (jakarta variant; Grails 7 is not on Hibernate 6) · JDK 17
minimum, JDK 21 runtime · AWS SDK v2 for S3.

---

## Upgrading 1.x → 2.0

1. Back up the database.
2. Generate the encryption keyfile and mount it:
   `openssl rand -base64 32 > pneumatik.key`
3. Create `.env` from `.env.example`. Mapping from 1.x configuration:
   - `DB_*` values carry over unchanged
   - `GRAILS_ENCRYPTION_SECRET` → `PNEUMATIK_LEGACY_SECRET`
   - `GRAILS_ENCRYPTION_SALT` → `PNEUMATIK_LEGACY_SALT`
   - `GRAILS_JWT_SECRET` → `PNEUMATIK_JWT_SECRET`
   - mail → `PNEUMATIK_MAIL_*`, S3 → `PNEUMATIK_S3_*`,
     storage path → `PNEUMATIK_STORAGE_DIR`
4. Start the stack. Flyway upgrades the schema in place; all 1.x data is
   preserved.
5. On first start, credentials stored with the legacy cipher are re-encrypted
   to AES-256-GCM and plaintext API keys are hashed (idempotent, summary is
   logged). Afterwards remove `PNEUMATIK_LEGACY_SECRET`/`PNEUMATIK_LEGACY_SALT`
   from the environment.
6. Rotate every secret that was tracked in the 1.x configuration repository
   (DB and mail passwords, S3 credentials, JWT secret, encryption
   secret/salt).

### Configuration model

Production configuration comes from environment variables plus the mounted
encryption keyfile. The 1.x mounted `application.yml` (j2-templated) and the
`external-config` plugin are gone. Session/form login no longer exists; the
SPA and API use stateless JWT (`POST /api/v1/auth/login`). Failed logins
return `401` (1.x returned `403`).

### Encryption format

Stored database passwords and host SSH keys use AES-256-GCM with a random IV,
ciphertext format `v1:base64(iv‖ct‖tag)`. The key is provided by a
`KeyProvider`; `FileKeyProvider` reads the mounted keyfile. Values without the
`v1:` prefix are treated as legacy AES-CBC ciphertexts and are only readable
while the legacy secret/salt are configured. API keys are stored as
`sha256:<hex>` with a `key_hint` column; the plaintext is shown exactly once
at creation.

---

## Upgrading 2.x → 3.0

1. Back up the database.
2. Deploy the new image. Flyway applies, in order:
   - `V2.1.0.001__addBackupDetails.sql` — adds `backup.finished_at`,
     `backup.output`, `backup.raw_size_bytes`, `backup.archived_size_bytes`
   - `V3.0.0.001__uuidPrimaryKeys.sql` — converts every primary key from
     `bigint` to a UUID string (`char(36)`), remaps all foreign keys, and
     recreates the constraints with stable names
3. Update machine API clients: the database ids used in
   `POST /api/v1/backup/create/{databaseId}` are new after the migration.
   Fetch current ids from `GET /api/v1/databases`.

### API changes in 3.0

- Every id field is `type: string, format: uuid`; path parameters validate
  against the UUID pattern (unknown formats resolve to `404`).
- Backup listings default to `sort=createdAt`; `sort=id` was removed from the
  sort whitelist.
- `GET /api/v1/backups/{id}` returns the run's captured command output
  (`output`) in addition to the listing fields; listings additionally carry
  `finishedAt`, `durationMs`, `rawSizeBytes`, `archivedSizeBytes`.
- `GET /api/v1/stats/dashboard` provides the aggregates used by the
  dashboard (totals, per-database storage/backup counts, 14-day activity).

### Backup execution in 3.0

- The dump process' stdout is redirected to the temp file by the operating
  system (`ProcessBuilder.redirectOutput`); stderr is drained concurrently
  into a 64 KB-capped buffer stored as `backup.output`. Backup data does not
  pass through the JVM heap. Timed-out processes are killed
  (`destroyForcibly`) after 30 minutes.
- Downloads stream from storage (`Files.newInputStream` / S3
  `GetObjectResponse` stream) to the HTTP response without buffering.
- SSH authentication uses an ephemeral agent:
  `ssh-agent /bin/bash -c 'ssh-add -q - && ssh …'`. The decrypted private key
  is written to the process' stdin only — never to disk, argv, or the
  environment. The temp directory is emptied at startup; this removes key
  files that pre-3.0 versions may have left behind after a crash.
- Database passwords are not part of any command line. Local runs receive
  `MYSQL_PWD`/`PGPASSWORD` via the process environment. SSH runs write
  `password\n` followed by the key to stdin; the local wrapper reads the
  password into a shell variable, hands the remainder to `ssh-add -`, and
  pipes the password into ssh where the remote shell consumes it via
  `IFS= read -r MYSQL_PWD; export MYSQL_PWD; …`. Passwords containing a
  newline are not supported.
- Custom images must provide `openssh-client` (for `ssh`, `ssh-agent`,
  `ssh-add`); the shipped Dockerfile already does.

---

## Platform and dependency mapping (1.x → 2.0)

| Legacy (Grails 4) | 2.0 |
|---|---|
| `spring-security-core:4.0.3` | `org.apache.grails:grails-spring-security:7.2.1` |
| `spring-security-rest:3.0.0` | `org.apache.grails:grails-spring-security-rest:7.2.1` (JWT) |
| `quartz:2.0.13` | `org.apache.grails:grails-quartz:4.0.1` |
| `mail:3.0.0` | `org.apache.grails:grails-mail:7.2.1` |
| `external-config:2.0.0` | removed — environment variables |
| `cache`, `async`, `events`, `scaffolding`, `gsp`, `ajax-tags`, asset-pipeline, sass | removed — UI moved to React |
| `micronaut-aws-sdk-s3` (AWS SDK v1, EOL) | `software.amazon.awssdk:s3` (v2) |
| `flyway-core` | kept, plus `flyway-mysql` / `flyway-database-postgresql` (required by Flyway 10+) |
| Hibernate 5.4 / `javax.*` | Hibernate 5.6 jakarta / `jakarta.*` |

The mail plugin on the rest-api profile requires two GSP beans
(`GroovyPagesTemplateEngine`, `DefaultGroovyPagesUriService`) declared in
`resources.groovy`.

## Database schema and Flyway

- Vendor-specific migration directories via the `{vendor}` placeholder:
  `db/migration/mariadb/` and `db/migration/postgresql/`.
- The pre-2.0 MariaDB migrations are kept byte-identical — checksums must
  match existing `flyway_schema_history` rows.
- PostgreSQL installs start from the `V2.0.0.001__baseline.sql` baseline.
- Reserved identifiers (`user` table, `db.user` column) are quoted through
  backticked GORM mappings, which Hibernate translates per dialect.

## Behaviour reference

Semantics preserved from 1.x:

- Backup queue model: trigger jobs enqueue `Backup(state: CREATED)`;
  `BackupExecutionJob` drains the queue every 60 s, non-concurrently. Cron
  schedules: hourly / every 4 h / every 12 h / daily 02:00.
- Dump invocations: `mysqldump --hex-blob --routines --triggers` (MySQL 8
  detection adds `--column-statistics=0 --set-gtid-purged=OFF`), `pg_dump`,
  `--ssl` when the host requires it, default port 3306,
  `friendlyName ?: databaseName` naming.
- Storage: DIRECT and S3 behind the `StorageActions` interface; zip-then-store
  flow, date-partitioned S3 keys (`basePath/db/yyyy/MM/dd/`). Deleting a
  backup removes the database row even if the provider reported failure; a
  missing S3 object counts as deleted.
- Machine endpoint `POST /api/v1/backup/create/{databaseId}` with `X-API-Key`:
  same path and status codes as 1.x (JSON body added in 2.0; UUID ids since
  3.0).
- Only the creator of an API key may delete it. Database passwords and host
  SSH keys are only overwritten when a new value is submitted.
- `Database.databaseType == null` is executed as MySQL.
- `BackupState.RUNNING` exists but is not set; states move
  CREATED → FINISHED/FAILED.
- Host and database deletion is not exposed (unchanged from 1.x).

Retention policies (since 2.0): per-database `keepCount` (keep the N most
recent successful backups) and/or `keepDays` (delete successful backups older
than N days), combined as OR. Failed backup rows are never removed. Applied
nightly at 03:30 and manageable via
`/api/v1/databases/{id}/retention-policy`.

## Security notes

- The 1.x configuration repository contained live secrets (DB and mail
  passwords, S3 key/secret, JWT secret, encryption secret and salt). Rotate
  all of them when leaving 1.x.
- Upgrade order for the credential migration: back up the DB → start once
  with `PNEUMATIK_LEGACY_SECRET`/`PNEUMATIK_LEGACY_SALT` set → remove both
  variables.
- Since 3.0 no secret reaches a command line or the filesystem during backup
  runs (see *Backup execution in 3.0*). Backup commands are not logged.
- JWT expiry is 3600 s; the SPA redirects to login on `401`. There is no
  refresh-token flow.

## Testing

- Unit tests (Spock): `./gradlew -p api test` — crypto, credential handling,
  API-key lifecycle, command construction for both engines, the command
  runner (streaming, capping, timeout, stdin/env injection), execution
  assembly, retention, trigger fan-out, statistics parsing, startup data
  migration.
- Integration tests: `./gradlew -p api integrationTest` — response envelopes,
  status codes, pagination, sort whitelist, machine endpoint auth, OpenAPI
  document.
- Frontend: `npm run typecheck && npm run lint && npm run build` in `app/`.
- `docker-compose.dev.yml` provides MariaDB and PostgreSQL instances (plus
  sample target databases); point the API at either via `DB_URL`/`DB_DRIVER`.
