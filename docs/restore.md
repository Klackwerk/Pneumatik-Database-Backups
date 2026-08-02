# Restoring a backup

A backup you have never restored is a guess. This page describes how to get
data back out of Pneumatik, and how to rehearse it before you need it.

## What a backup actually is

Every run produces one plain SQL dump, zipped:

```
orders_prod_20260801_0230.sql.zip     ← the stored archive
└── orders_prod_20260801_0230.sql     ← a single entry, the dump itself
```

The dump is exactly what `mysqldump` or `pg_dump` wrote. Pneumatik adds no
proprietary format — any zip tool and the standard database clients restore it,
with or without Pneumatik running. That is deliberate: the recovery path must
not depend on this application still existing.

With `PNEUMATIK_ENCRYPT_ARCHIVES=true` the archive is sealed with AES-256-GCM
and named `.sql.zip.enc`. One step is then added to every restore below —
[decrypt it first](#decrypting-an-encrypted-archive) — and that step needs
nothing but `pneumatik.key` and a script in this repository.

Where the archive lives depends on the database's storage setting:

| Storage | Location |
|---|---|
| Local disk | `PNEUMATIK_STORAGE_DIR` on the host (`/opt/storage` in the container), flat |
| S3 | `<base path>/<database>/<year>/<month>/<day>/<filename>.sql.zip` |

## Getting the archive

**From the web UI** — Backups, find the run, download. Use the status filter to
show only `Finished` runs; a failed run has no file.

**From the API:**

```sh
TOKEN=$(curl -s -X POST https://backup.example.com/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"…"}' | jq -r .access_token)

# find the backup you want
curl -s -H "Authorization: Bearer $TOKEN" \
  'https://backup.example.com/api/v1/backups?state=FINISHED&search=orders_prod' | jq '.data[0]'

# downloads are handed out against a single-use ticket
TICKET=$(curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  "https://backup.example.com/api/v1/backups/$ID/download-token" | jq -r .data.token)

curl -OJ "https://backup.example.com/api/v1/backups/$ID/download?token=$TICKET"
```

**Straight from storage, without Pneumatik.** If the application is down, the
archives are still ordinary files:

```sh
# local disk
cp /path/to/storage/orders_prod_20260801_0230.sql.zip .

# S3-compatible
aws s3 cp s3://my-bucket/backups/orders_prod/2026/08/01/orders_prod_20260801_0230.sql.zip . \
  --endpoint-url https://fra1.digitaloceanspaces.com
```

## Decrypting an encrypted archive

Encrypted archives end in `.enc`, and the backup's detail view says so. They are
sealed with AES-256-GCM under a key derived from `pneumatik.key` — the same
keyfile that protects stored database passwords.

`tools/pneumatik-decrypt.py` turns one back into a plain zip. It needs Python 3
and the `cryptography` package, and nothing else — not Pneumatik, not its
database:

```sh
pip install cryptography
./tools/pneumatik-decrypt.py -k pneumatik.key orders_prod_20260801_0230.sql.zip.enc
# → orders_prod_20260801_0230.sql.zip
```

Straight into a database, without the plaintext ever hitting disk:

```sh
./tools/pneumatik-decrypt.py -k pneumatik.key -o - orders.sql.zip.enc \
  | funzip | mysql -h db.example.com -u root -p orders_prod
```

If it reports that a chunk failed to authenticate, the key is wrong or the file
is damaged. The archive is authenticated chunk by chunk, so it fails loudly
rather than handing you a dump that is quietly missing its tail.

> **Without `pneumatik.key`, encrypted archives cannot be recovered.** Not by
> you, not by us. This is the trade for encryption at rest: keep the key backed
> up somewhere independent of the backups, and verify a restore works before
> you rely on it.

## Checking it before you rely on it

If the archive is encrypted, decrypt it first — the checks below run on the zip.

```sh
unzip -t orders_prod_20260801_0230.sql.zip     # archive intact?
unzip -p orders_prod_20260801_0230.sql.zip | head -20
unzip -p orders_prod_20260801_0230.sql.zip | tail -5
```

A complete `mysqldump` ends with `-- Dump completed on …`. A complete `pg_dump`
ends with `-- PostgreSQL database dump complete`. If the tail is a truncated
statement, the dump is incomplete — use an older one. (Pneumatik verifies
archives at backup time, so this should not happen; check anyway when it
matters.)

## Restoring MySQL / MariaDB

```sh
unzip -p orders_prod_20260801_0230.sql.zip > orders_prod.sql

# into a fresh database — do this first, and compare, before overwriting anything
mysql -h db.example.com -u root -p -e 'CREATE DATABASE orders_restore'
mysql -h db.example.com -u root -p orders_restore < orders_prod.sql
```

Restoring over the live database, once you are sure:

```sh
mysql -h db.example.com -u root -p orders_prod < orders_prod.sql
```

The dump contains `DROP TABLE IF EXISTS` / `CREATE TABLE` for each table, so it
replaces the tables it contains. It does **not** remove tables that exist in the
target but not in the dump. To guarantee the target matches the dump exactly,
drop and recreate the database first.

Without piping through a file:

```sh
unzip -p orders_prod_20260801_0230.sql.zip | mysql -h db.example.com -u root -p orders_prod
```

## Restoring PostgreSQL

```sh
unzip -p analytics_20260801_0230.sql.zip > analytics.sql

createdb -h db.example.com -U postgres analytics_restore
psql -h db.example.com -U postgres -d analytics_restore -f analytics.sql
```

Add `-v ON_ERROR_STOP=1` so the restore aborts on the first error instead of
running to the end and leaving you with a partial schema:

```sh
psql -h db.example.com -U postgres -d analytics_restore \
     -v ON_ERROR_STOP=1 -f analytics.sql
```

Ownership and roles are not created by the dump. If it references a role that
does not exist on the target, create it first or restore as a superuser.

## Restoring Pneumatik's own database

If you lose the Pneumatik instance itself, you need two things: its application
database and `pneumatik.key`.

1. Restore the application database from whatever backs it up (Pneumatik can
   back itself up — configure it as a target like any other).
2. Put `pneumatik.key` back at `PNEUMATIK_KEY_FILE`.
3. Start the container. Flyway brings the schema up to date.

**Without the key file, stored database passwords and SSH keys cannot be
decrypted.** The hosts and databases will still be listed, but every backup will
fail until you re-enter each credential. Back the key up separately from the
backups themselves — a copy of the key sitting in the same S3 bucket as the
dumps protects you from nothing.

If archive encryption is off, the dumps themselves do not depend on the key and
you can restore data even after losing it. **If it is on, they do**: losing the
keyfile then loses every backup with it.

## Rehearse it

Restore something on a schedule you actually keep — quarterly is a reasonable
floor:

1. Pick a real database, take its newest backup.
2. Restore it into a scratch database on a non-production server.
3. Compare: row counts on the tables you care about, the newest row's timestamp,
   and whether the application starts against it.
4. Write down how long it took. That number is your recovery time; assumptions
   about it are usually wrong by an order of magnitude.

The dashboard warns when a database has no recent *successful* backup, which
catches the common silent failure. It cannot tell you whether the file restores.
Only a restore does that.
