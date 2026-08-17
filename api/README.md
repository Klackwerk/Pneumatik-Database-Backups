# Pneumatik API

Grails 7.2.1 backend of [Pneumatik](../README.md): the versioned JSON API
(`/api/v1`), the Quartz backup scheduler, and — in the integrated image — the
server for the bundled React frontend.

## Running locally

JDK 17+ required. The development profile uses in-memory H2, disables Flyway,
and seeds `admin` / `admin` plus a sample host/database:

```sh
./gradlew bootRun     # → http://localhost:8085
```

Against a real database (see ../docker-compose.dev.yml):

```sh
DB_URL='jdbc:mariadb://127.0.0.1:3308/pneumatik' DB_USER=pneumatik DB_PASSWORD=pneumatik \
  GRAILS_ENV=production ./gradlew bootRun
```

## Tests

```sh
./gradlew test                 # Spock unit tests
./gradlew integrationTest      # REST contract + query-count tests (H2)
PNEUMATIK_TEST_DB=mariadb  ./gradlew integrationTest   # via Testcontainers
PNEUMATIK_TEST_DB=postgres ./gradlew integrationTest
```

## Module layout

The code is organised into modules that only talk through interfaces/services
(`de.klackwerk.pneumatik.*`):

| Package | Responsibility |
|---|---|
| `inventory` | Hosts and databases (the things being backed up) |
| `backup` | Backup lifecycle, dump engines (MySQL/PostgreSQL), triggers, retention |
| `storage` | `StorageActions` interface + DIRECT and S3 providers |
| `credentials` | `KeyProvider` + AES-GCM encryption of secrets at rest |
| `security` | Users, roles, API keys (hashed), password listener |
| `notification` | Failure mails |
| `migration` | Idempotent startup data upgrades from 1.x |
| `api` / `api.v1` | Response envelope, controllers, OpenAPI + Swagger UI |
| `web` | SPA serving for the integrated image |

## API contract

`src/main/resources/openapi.yaml` is the single source of truth. It is served
at `/api/v1/openapi.yaml`, rendered at `/api/v1/docs`, and the frontend
generates its typed client from it (`npm run generate:api` in `../app`) — a CI
job fails when the two drift apart. Change the spec and the implementation
together, and cover new endpoints in
`src/integration-test/groovy/de/klackwerk/pneumatik/api/ApiContractSpec.groovy`.

## Configuration

All runtime configuration is environment-driven; see the table in the
[root README](../README.md#configuration) and `grails-app/conf/application.yml`.
Secrets never live in this repository: the data-encryption key is a mounted
keyfile (`PNEUMATIK_KEY_FILE`), everything else comes from the environment.
