<p align="center">
  <img src="docs/assets/logo.svg" width="72" height="72" alt="">
</p>

<h1 align="center">Pneumatik Database Backups</h1>

<p align="center">
  Self-hosted backups for MySQL, MariaDB and PostgreSQL.
</p>

<p align="center">
  <img alt="Backend" src="https://img.shields.io/badge/backend-Grails%207.2-2b6777">
  <img alt="Frontend" src="https://img.shields.io/badge/frontend-React%2019-2b6777">
  <img alt="Build" src="https://img.shields.io/badge/build-JDK%2017%2B-2b6777">
  <img alt="Runtime" src="https://img.shields.io/badge/runtime-JDK%2021-2b6777">
  <img alt="API" src="https://img.shields.io/badge/API-OpenAPI%203.0-2b6777">
  <img alt="License" src="https://img.shields.io/badge/license-AGPL--3.0-d08700">
</p>

Pneumatik schedules `mysqldump` / `pg_dump` runs against your MySQL, MariaDB and
PostgreSQL servers — directly or through an SSH tunnel — compresses the dumps,
and stores them on local disk or any S3-compatible object storage. One
container, a small web UI, an HTTP API, and email alerts when a backup fails.

**New here? Start with the [Getting started guide](docs/getting-started.md).**

---

## Features

- **Scheduled backups** — hourly, every 4 h, every 12 h, daily, or manual only
- **MySQL / MariaDB / PostgreSQL** targets, with `--ssl` and SSH-tunnelled dumps
- **Local or S3-compatible storage** (AWS S3, DigitalOcean Spaces, MinIO, …);
  every dump is zipped, S3 keys are date-partitioned
- **Retention policies** — keep the last *N* backups and/or delete backups
  older than *N* days, applied automatically every night
- **Web UI** — manage hosts, databases, backups, users and API keys; filter
  runs by status, database or host; download or delete any stored backup, and
  see at a glance which databases have gone too long without a successful one
- **HTTP API** — versioned JSON API with JWT auth, plus an `X-API-Key`
  endpoint so scripts and CI jobs can trigger backups
- **Failure notifications** by email
- **Encrypted at rest** — database passwords and SSH keys are stored
  AES-256-GCM encrypted; API keys are stored hashed and can be scoped to
  individual databases
- **Optional archive encryption** — AES-256-GCM over the stored dumps, with a
  standalone decrypt script so a restore never needs Pneumatik itself
- **Verified SSH host keys** — optional per host, pinned on first connect, so
  the database password cannot be handed to an impostor

## How it works

```
┌────────────────────── one container ──────────────────────┐
│  React SPA  ◄──serves──  Grails 7 API  ──schedules──► Quartz│
│                              │                         │    │
│                     MariaDB/PostgreSQL          mysqldump / │
│                     (app database)              pg_dump via │
│                                                 bash + ssh  │
└─────────────────────────────────────────────┬─────────────┘
                                               ▼
                                  local disk or S3-compatible
```

Scheduled triggers enqueue backups; a worker drains the queue once a minute,
runs the dump, zips it, stores it, and records the result. The UI is a
single-page app served by the API itself, so deploying Pneumatik means running
**one application container and one database**.

## Quick start

Requirements: Docker with Compose.

```sh
git clone https://github.com/Klackwerk/Pneumatik-Database-Backups.git pneumatik && cd pneumatik
cp .env.example .env                      # set DB passwords + JWT secret
openssl rand -base64 32 > pneumatik.key   # data-encryption key (keep it safe!)
docker compose up -d
```

The first start creates the `admin` account and prints a generated password to
the container log:

```sh
docker compose logs pneumatik | grep -A4 'initial admin account'
```

Open `http://localhost:8080` and sign in. The
[getting started guide](docs/getting-started.md) walks through the first host,
the first database, retention and notifications; read
[running behind a reverse proxy](docs/reverse-proxy.md) before exposing it
beyond localhost.

> ⚠️ Losing `pneumatik.key` makes the stored database passwords and SSH keys
> undecryptable. Back it up separately from the backups themselves.

## Documentation

| | |
|---|---|
| [Getting started](docs/getting-started.md) | First backup, end to end |
| [Restoring a backup](docs/restore.md) | Getting the data back out |
| [Running behind a reverse proxy](docs/reverse-proxy.md) | TLS, forwarded headers, timeouts |
| [Security policy](SECURITY.md) | Reporting a vulnerability, hardening |
| [Contributing](CONTRIBUTING.md) | Setup, checks and house style |
| [Changelog](CHANGELOG.md) | What changed per release |

## Configuration

Everything is configured through environment variables (see `.env.example`):

| Variable | Purpose | Default |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | Pneumatik's own database | — |
| `DB_DRIVER` / `DB_URL` | override to run on PostgreSQL | MariaDB |
| `PNEUMATIK_KEY_FILE` | path to the base64 32-byte encryption key | — |
| `PNEUMATIK_JWT_SECRET` | signing secret for login tokens; **required in production**, min. 32 chars | — |
| `PNEUMATIK_ADMIN_PASSWORD` | password for the initial admin account | generated |
| `PNEUMATIK_LOGIN_MAX_ATTEMPTS` / `_WINDOW_MINUTES` / `_LOCK_MINUTES` | failed-login back-off | `5` / `15` / `15` |
| `PNEUMATIK_TRUST_FORWARDED_FOR` | read the client address from `X-Forwarded-For` (only behind a proxy that sets it) | `false` |
| `PNEUMATIK_BACKUP_TIMEOUT_MINUTES` | how long a single dump may run | `30` |
| `PNEUMATIK_ENCRYPT_ARCHIVES` | AES-256-GCM over stored archives; needs the keyfile to restore | `false` |
| `PNEUMATIK_NOTIFICATION_QUIET_HOURS` | silence after a failure mail before the same database mails again | `6` |
| `PNEUMATIK_HTTP_PORT` | published HTTP port | `8080` |
| `PNEUMATIK_STORAGE_DIR` | host directory for locally stored backups | `./storage` |
| `PNEUMATIK_S3_*` | endpoint, region, bucket, key, secret, base path | — |
| `PNEUMATIK_MAIL_*` | SMTP host/port/user/password, from & admin address | — |
| `PNEUMATIK_CORS_ORIGINS` | extra origins allowed to call the API | same-origin |
| `PNEUMATIK_LEGACY_SECRET` / `_SALT` | 1.x cipher parameters, only during upgrade | — |

## API

Interactive documentation (Swagger UI) is served at **`/api/v1/docs`**; the
OpenAPI contract at `/api/v1/openapi.yaml` is the source of truth the
frontend's typed client is generated from.

Trigger a backup from a script or CI job with an API key (created in the UI):

```sh
curl -X POST -H "X-API-Key: $KEY" https://backup.example.com/api/v1/backup/create/42
```

Everything else uses JWT bearer tokens:

```sh
TOKEN=$(curl -s -X POST https://backup.example.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"…"}' | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" https://backup.example.com/api/v1/backups
```

Access tokens last an hour. The login response also returns a `refresh_token`
— opaque, stored hashed, and rotated on every use — which
`POST /api/v1/auth/refresh` exchanges for a new access token without asking
for the password again. `POST /api/v1/auth/logout` revokes it.

## Development

| Directory | Stack |
|---|---|
| `api/` | Grails 7.2.1 (Groovy 4, Spring Boot 3.5), GORM, Quartz, Spock |
| `app/` | Vite, React 19, TypeScript, Tailwind 4, shadcn/ui, TanStack Query |

```sh
# backend — JDK 17+, in-memory H2; the generated admin password is logged at startup
cd api && ./gradlew bootRun            # → http://localhost:8085

# frontend — Node 20+, proxies /api to :8085
cd app && npm install && npm run dev   # → http://localhost:5173
```

After changing `api/src/main/resources/openapi.yaml`, regenerate the typed
client — CI fails if they drift apart:

```sh
cd app && npm run generate:api
```

`docker-compose.dev.yml` provides local MariaDB/PostgreSQL instances (as app
database and as sample backup targets).

## Testing

```sh
cd api && ./gradlew test integrationTest       # unit + REST contract + query-count tests
PNEUMATIK_TEST_DB=mariadb  ./gradlew integrationTest   # same suite on MariaDB (Docker)
PNEUMATIK_TEST_DB=postgres ./gradlew integrationTest   # same suite on PostgreSQL (Docker)

cd app && npm run typecheck && npm run lint && npm run build
```

The integration suite includes contract tests (response envelope, status
codes, validation errors) and a query-count assertion that guards the backup
listing against N+1 regressions.

## Upgrading from 1.x

Pneumatik 2.0 upgrades the 1.x database schema in place and re-encrypts stored
credentials on first start. Follow the step-by-step guide in
[CHANGELOG.md](CHANGELOG.md#upgrading-from-1x); the full technical migration
log is in [MIGRATION.md](MIGRATION.md).

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md)
for the setup, the checks CI runs, and the house style. Participation is
covered by the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

Copyright © klackwerk IT & Events GmbH.

Licensed under the **GNU Affero General Public License v3.0 or later**. See
[LICENSE](LICENSE) for the full text.

In short: you may run, study, modify and redistribute Pneumatik. If you
distribute a modified version — or offer one to users over a network — you must
make your changes available under the same license.
