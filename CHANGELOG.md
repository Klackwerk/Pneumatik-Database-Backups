# Changelog

## Pneumatik 3.2.0

### Added

* Stored archives can be encrypted with AES-256-GCM
  (`PNEUMATIK_ENCRYPT_ARCHIVES`), keyed from the existing keyfile
* `tools/pneumatik-decrypt.py` decrypts an archive without Pneumatik

### Upgrading from 3.1

Encryption is off by default; existing archives stay plaintext and readable.

> With `PNEUMATIK_ENCRYPT_ARCHIVES=true`, losing `pneumatik.key` means losing
> every backup taken while it was on. Read [docs/restore.md](docs/restore.md)
> first.

## Pneumatik 3.1.0

### Added

* Hosts and databases can now be deleted.
* `POST /api/v1/auth/refresh` implements refresh token support
* Backups can be filtered by status, database and host
* The dashboard lists databases whose last successful backup is older than
  twice their schedule interval, and those that never had one.
* SSH host keys can be verified per host (Hosts → *Verify the host key*). The
  first verified connection pins the key it sees;
  A key can also be pasted from `ssh-keyscan`. Off by default; the Hosts list
  flags unverified SSH hosts. `Default behavior will change in v4`
* API keys can be scoped to specific databases. A key with no scope reaches
  every database; `Default behavior will change in v4`
* The dump timeout is configurable via `PNEUMATIK_BACKUP_TIMEOUT_MINUTES`
* Licensed under AGPL-3.0-or-later. The sidebar links to the source
* Guides for getting started, restoring a backup, and running behind a reverse
  proxy
* `SECURITY.md` with a disclosure process and a supported-versions table
* Renovate runs from a scheduled pipeline
* A logo, used as the favicon and on the login screen

### Changed

* Downloads are no longer buffered in the browser
* Copying an API key falls back to a manual-copy path when the browser has no
  Clipboard API.
* A list that fails to load offers a retry instead of asking for a page
  reload.
* Failed backups show in the activity chart
* Backup Datatable improvements
* Minified JS improvements
* Removed unused dependencies
* Bumped minimum password length to 12
* Schedules missed while the application was down are caught up at startup.
* Scheduled triggers skip a database that already has a backup queued or
  running
* Failure mails are retried three times
* S3 uploads are multipart, streamed off disk, and aborted on failure
* The S3 client fails with a message instead of a null client on a wrong endpoint

### Security

* Dump commands are no longer assembled as shell strings
* Dump filenames are sanitised, and a path resolving outside its storage
  directory is refused
* Production fails to start without `PNEUMATIK_JWT_SECRET` of at least 32
  characters
* The initial admin account gets a generated password, printed once to the
  log, or one from `PNEUMATIK_ADMIN_PASSWORD`
* Failed sign-ins are throttled per client address and per username
  (`PNEUMATIK_LOGIN_*`).
* A content security policy and the usual hardening headers are sent on every
  response
* The login response no longer returns the spring-security-rest refresh without expiration
* `Content-Disposition` on downloads is RFC 6266 encoded.

### Fixed

* PostgreSQL backups over SSL always failed
* Retention could delete the last good backup. Age-based deletion is now
  suspended while the latest attempt is failing
* Empty and truncated dumps counted as successful backups. Dumps are now rejected
  when empty
* A timed-out dump left `mysqldump` or `ssh` running
* Backups could be executed concurrently
* Dumps check for free disk space before starting.
* `FlywayMigrator` mapped plain MySQL to a migration directory that has never
  existed, so nothing was applied. MySQL now uses the MariaDB migrations.
* Deleting a queued backup is now possible
* `mysqldump`/`pg_dump` no longer receive a literal `null` user argument when
  a database has no user configured.

## Pneumatik 3.0.0

### Highlights

* **Dashboard statistics** — new `GET /api/v1/stats/dashboard` endpoint and a
  reworked dashboard: backup activity over the last 14 days (finished vs
  failed), storage share per database as a donut chart, total storage used,
  failures of the last 7 days, and a per-database table with backup count,
  storage and last run.
* **Backup run details** — every run now records its duration
  (`executedAt`/`finishedAt`), the exact size of the raw dump and of the
  stored archive, and the captured output of the dump command. Clicking a
  backup opens a detail view; failed backups show the failure log. The
  failure notification mail includes the log as well.
* **Large databases no longer exhaust memory** — dump output is streamed to
  disk by the OS and downloads are streamed from storage to the client;
  backup data no longer passes through the JVM heap at any point. S3
  downloads stream directly instead of spooling to the temp directory.
* **SSH keys are never written to disk anymore** — SSH backups authenticate
  against an ephemeral `ssh-agent` that receives the key via stdin. A killed
  container can no longer leave a decrypted key behind; the temp directory is
  swept at startup to remove artifacts of older versions.
* **Database passwords are never placed on a command line** — locally they
  travel via `MYSQL_PWD`/`PGPASSWORD` in the process environment, over SSH
  they are piped through stdin into the remote shell's environment. No
  process list on either side ever shows a secret.
* **Recovery notifications** — when a backup succeeds after the previous run
  of the same database failed, an all-clear mail is sent. All notification
  mails are now in English.
* **UUID primary keys** — all numeric ids are replaced by UUIDs across the
  schema and the API.
* **Theme** — refreshed color scheme, higher dark-mode contrast, and a
  light/dark toggle in the sidebar (persists, defaults to the OS scheme).

### Breaking changes

* All resource ids are UUIDs. Machine API clients calling
  `POST /api/v1/backup/create/{databaseId}` must switch to the new database
  ids — fetch them from `GET /api/v1/databases` after upgrading.
* Backup listings sort by `createdAt` by default; `sort=id` was removed.
* Mail notifications changed wording and language (German → English).

### Upgrading from 2.x

1. Back up your database.
2. Pull the new image and restart; Flyway migrates the schema in place
   (new backup detail columns, then the UUID key migration).
3. Update stored database ids in any scripts using the `X-API-Key` machine
   endpoint.

## Pneumatik 2.0.0

Complete rewrite. The Grails 4 monolith with server-rendered views is replaced
by a Grails 7.2.1 REST API (`api/`) and a React frontend (`app/`), shipped
together as **one integrated container image**. The full migration log with
every behavioural note lives in [MIGRATION.md](MIGRATION.md).

### Highlights

* **New web UI** — React + TypeScript + shadcn/ui single-page app, served by the
  app itself; responsive down to mobile, light/dark theme following the OS.
* **Versioned JSON API** under `/api/v1` with a consistent response envelope,
  field-level validation errors, pagination and sorting; interactive docs
  (Swagger UI) at `/api/v1/docs`, contract at `/api/v1/openapi.yaml`.
* **Retention policies** (new feature): per database, keep at most *N* recent
  successful backups and/or delete successful backups older than *N* days.
  Applied nightly at 03:30; failed runs are always kept as history.
* **Stateless JWT authentication** for the UI/API (`POST /api/v1/auth/login`);
  the `X-API-Key` machine endpoint is unchanged (see compatibility below).
* **Hardened credential encryption** — stored database passwords and SSH keys
  now use AES-256-GCM with a random IV per value (the 1.x scheme used a fixed
  IV and salt, making ciphertexts deterministic). The encryption key moves out
  of the config file into a mounted keyfile.
* **API keys are stored hashed** (SHA-256); the plaintext is shown exactly once
  at creation. Existing keys keep working — holders are unaffected.
* **PostgreSQL supported as the application database** (in addition to
  MariaDB/MySQL), with vendor-specific Flyway migrations.
* Platform: Grails 7.2.1 / Groovy 4 / Spring Boot 3.5 (jakarta), JDK 21
  runtime, AWS SDK v2 for S3 storage.

### Compatibility

* `POST /api/v1/backup/create/{databaseId}` with `X-API-Key` is kept verbatim
  (same path, same status codes) — existing integrations keep working.
* The database schema upgrades **in place** via Flyway; all 1.x data (hosts,
  databases, backups, users, API keys) is preserved.
* Backup behaviour is unchanged: same `mysqldump`/`pg_dump` invocations, same
  schedules, same storage layout (one fix: the S3 day folder now uses
  day-of-month instead of day-of-year).

### Breaking changes

* Server-rendered GSP UI is gone; the SPA talks to `/api/v1` with JWT auth.
  Session/form login no longer exists.
* Configuration moved from a mounted `application.yml` (j2-templated) to
  **environment variables** plus a mounted encryption keyfile — see
  `.env.example`. The old `GRAILS_*` variables are replaced by `PNEUMATIK_*`.
* The image now contains frontend + API in one; deploy a single container
  (plus the database) instead of the old stack.
* Login failures on the auth endpoint return `401` (was `403`).

### Upgrading from 1.x

1. **Back up your database** (ironic, but do it).
2. Generate the encryption keyfile:
   `openssl rand -base64 32 > pneumatik.key`
3. Create your `.env` from `.env.example`. Map your old settings:
   * `DB_*` values carry over as-is.
   * `GRAILS_ENCRYPTION_SECRET` → `PNEUMATIK_LEGACY_SECRET`,
     `GRAILS_ENCRYPTION_SALT` → `PNEUMATIK_LEGACY_SALT` (needed **once**, see
     step 5).
   * `GRAILS_JWT_SECRET` → `PNEUMATIK_JWT_SECRET`,
     mail settings → `PNEUMATIK_MAIL_*`, S3 settings → `PNEUMATIK_S3_*`,
     storage path → `PNEUMATIK_STORAGE_DIR`.
4. Start the new stack: `docker compose up -d`. Flyway migrates the schema
   automatically.
5. On first start the app re-encrypts all stored credentials from the legacy
   cipher to AES-GCM and hashes plaintext API keys (idempotent; a summary is
   logged). After one successful start, **remove**
   `PNEUMATIK_LEGACY_SECRET`/`PNEUMATIK_LEGACY_SALT` from your environment.
6. Rotate any secrets that lived in your 1.x configuration repository —
   the old repo tracked them in plain text.

## Pneumatik 1.3.1
* Fix decrypted ssh key files having "invalid format"
  * Keys will now be written to disk through bash and not java/groovy, to ensure the correct line endings and encodings
* Refactor timestamp and sshConnectionString generation
  * Moved back to BackupService
* Fix incorrect "could not delete file" log message on ssh key deletion from filesystem
* Store Backups on Object Storage structured as YYYY/MM/DD instead of YYYY/MM
* 2025: Rebump this release due to problems with 1.4.0

## Pneumatik 1.3.0
* Add PostgreSQL Support
* Upgrade Grails to 5.2.4
* Parallelize Backup Execution with parallelStreams
* Directly execute backups manually triggered by users
* Show no warning for MariaDB > 10.3 and set default MariaDB parameter
* Use --hex-blob as default for MariaDB
* Use --single-transaction as default for MariaDB / MySQL
* Minor performance and consistency improvements

## Pneumatik 1.2.1
* Template Storage and temp path to the same location

## Pneumatik 1.2.0
* Always use --hex-blob as mysqldump parameter
* Always dump triggers and routines

## Pneumatik 1.1.1
* Fix Translation for Storage Provider in Database Form
* Add ID column to database overview
* Refactor REST request for creating backups via curl
  * curl -i -H "Content-Type: application/json" -H "X-Api-key: API_KEY" HOST/api/v1/backup/create/$databaseId --request POST

## Pneumatik 1.1.0
* Add Spring Security Rest for API Interactions
* Add basic API Key functionality
* Add capability for creating backups via API Key Authentication
* Create API Keys with or without Expiration in Frontend
* Configuration for custom ssh ports is possible now
* fix bug which broke direct storage functionality

#### Create Backups via API Key with Curl
curl -i -H "Content-Type: application/json" -H "X-Api-key: API_KEY" HOST/api/v1/backup/create --request POST --data '{ "databaseId": "DATABASE_ID" }'

## Pneumatik 1.0.2
* Reduce log output when running with prometheus-blackbox-exporter Health Checks

## Pneumatik 1.0.1
* Add manual Trigger for Backups
* Log improvements

## Pneumatik 1.0.0
* First Production-ready release
* Create Hosts and Databases
* Backup Existing Databases
* Trigger them by time Hourly, 4-Hourly, 12-Hourly or Daily
* Store backups locally or on S3-Compatible Storage
