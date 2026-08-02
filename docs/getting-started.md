# Getting started

From nothing to a scheduled, verified backup. Budget about twenty minutes.

You need a machine with Docker and Compose, and network access from it to the
database servers you want to back up.

## 1. Configure

Pneumatik ships as a container image, so there is nothing to clone or build —
you need the compose file and an environment file:

```sh
mkdir pneumatik && cd pneumatik
curl -O https://raw.githubusercontent.com/Klackwerk/Pneumatik-Database-Backups/main/docker-compose.yml
curl -o .env https://raw.githubusercontent.com/Klackwerk/Pneumatik-Database-Backups/main/.env.example
```

Three values in `.env` matter before the first start:

```sh
DB_PASSWORD=…                 # for Pneumatik's own database
DB_ROOT_PASSWORD=…
PNEUMATIK_JWT_SECRET=…        # openssl rand -base64 48 — at least 32 characters
```

Production refuses to start without a JWT secret. Anyone who knows it can mint
an admin token, so treat it as a password.

Then generate the data-encryption key, which protects the database passwords and
SSH keys Pneumatik stores:

```sh
openssl rand -base64 32 > pneumatik.key
```

> **Back this file up somewhere other than your backups.** Without it, stored
> credentials cannot be decrypted. By default it does not affect the dumps
> themselves — those are readable without it — but a Pneumatik instance without
> its key cannot connect to anything.
>
> If you also set `PNEUMATIK_ENCRYPT_ARCHIVES=true`, the stored dumps are
> encrypted with this key too, and losing it then means losing every backup.
> See [Restoring a backup](restore.md) before turning that on.

## 2. Start

```sh
docker compose up -d
docker compose logs -f pneumatik      # ctrl-c once it reports it is running
```

The first start creates the schema and an `admin` account with a generated
password, printed once:

```sh
docker compose logs pneumatik | grep -A4 'initial admin account'
```

Set `PNEUMATIK_ADMIN_PASSWORD` in `.env` beforehand to choose it yourself.

Open <http://localhost:8080> and sign in. Change the password from the sidebar.

Before exposing this to anything beyond localhost, read
[Running behind a reverse proxy](reverse-proxy.md) — the login token travels in
the clear over plain HTTP.

## 3. Add the server the database runs on

**Hosts → Add host.**

| Field | |
|---|---|
| Name / hostname | Where the database server answers. |
| Port | `3306` for MySQL/MariaDB, `5432` for PostgreSQL. |
| Login / password | A database user that may read everything you want dumped. |
| Use SSL | Encrypts the connection from Pneumatik to the database server. |
| Connect via SSH | For servers not reachable directly — see below. |

A dedicated backup user is worth the two minutes:

```sql
-- MySQL / MariaDB
CREATE USER 'pneumatik'@'%' IDENTIFIED BY '…';
GRANT SELECT, SHOW VIEW, EVENT, TRIGGER, LOCK TABLES ON *.* TO 'pneumatik'@'%';

-- PostgreSQL
CREATE ROLE pneumatik LOGIN PASSWORD '…';
GRANT pg_read_all_data TO pneumatik;
```

### If the database is only reachable over SSH

Tick **Connect via SSH** and give the SSH host, user and private key. The key is
stored encrypted and never written to disk — it is handed to an ephemeral
`ssh-agent` at dump time.

Also tick **Verify the host key**. The database password is piped through ssh's
stdin, so without verification anything that can answer for that hostname
receives it. Leave the key field empty and the first connection pins what it
sees; every later run must match. To pin up front:

```sh
ssh-keyscan -t ed25519 db.internal.example.com
```

Paste the output into the host key field.

## 4. Add the database

**Databases → Add database.** Pick the host, enter the database name, then:

* **Schedule** — hourly, every 4 h, every 12 h, daily, or manual only.
* **Storage** — local disk, or S3-compatible if `PNEUMATIK_S3_*` is configured.

Then use **Back up now** on the row. Watch it under **Backups**. A first run that
fails tells you something useful immediately — open the row and read the failure
log; it is the dump command's own output.

Common first failures:

| Failure log says | Cause |
|---|---|
| `Access denied for user` | The host credentials are wrong, or lack rights on that database. |
| `Unknown database` | The name does not match; it is case-sensitive on Linux. |
| `Host key verification failed` | The server's key changed, or the pinned key is wrong. |
| `SSL is required` | The server demands TLS — tick Use SSL on the host. |

## 5. Set a retention policy

**Databases → the ⋯ menu → Retention.** Keep the last *N* backups, delete those
older than *N* days, or both. Applied nightly at 03:30.

The last successful backup is never deleted, and age-based deletion pauses while
a database's runs are failing — otherwise a database that started failing would
quietly have its last good copy expire.

## 6. Turn on failure notifications

Set the mail variables in `.env` and restart:

```sh
PNEUMATIK_MAIL_HOST=smtp.example.com
PNEUMATIK_MAIL_PORT=587
PNEUMATIK_MAIL_USERNAME=…
PNEUMATIK_MAIL_PASSWORD=…
PNEUMATIK_MAIL_FROM=pneumatik@example.com
PNEUMATIK_MAIL_ADMIN=ops@example.com
```

A failed run mails the admin address with the failure log attached, retried
three times, then held quiet for six hours per database so one broken host
cannot flood the inbox. A run that succeeds after a failure sends an all-clear.

## 7. Restore something

Do this now, while nothing is on fire. Take the backup you just made and restore
it into a scratch database — [Restoring a backup](restore.md) walks through it.
Until you have done that once, you do not know that any of this works.

## Backing up from a script or CI

**API keys → Create key.** Scope it to the databases it should reach; a scoped
key gets a 404 for anything else.

```sh
curl -X POST -H "X-API-Key: $KEY" \
  https://backup.example.com/api/v1/backup/create/<database-id>
```

The database id is in the URL when you open a database in the UI, or from
`GET /api/v1/databases`.

## Where to go next

* [Restoring a backup](restore.md)
* [Running behind a reverse proxy](reverse-proxy.md)
* [Security policy](../SECURITY.md)
* Full configuration reference — [README](../README.md#configuration)
* API reference — `/api/v1/docs` on your installation
