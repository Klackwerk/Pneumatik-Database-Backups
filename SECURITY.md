# Security Policy

Pneumatik holds credentials for every database it backs up and stores the
resulting dumps. A vulnerability here is a vulnerability in everything it can
reach, so security reports are welcome and taken seriously.

## Reporting a vulnerability

Email **security@klackwerk.de**. Do not open a public issue, a merge request or
a discussion thread for a suspected vulnerability.

Include whatever you have:

* the version or commit you tested (`/api/v1/docs` shows the running version)
* how it is deployed — the container image, behind which reverse proxy, which
  application database
* the steps to reproduce, ideally the smallest request sequence that shows it
* what an attacker gains, and what access they need to start
* anything that helps us reproduce: logs, a proof of concept, a screenshot

Reports in English or German are equally fine.

## What to expect

| | |
|---|---|
| Acknowledgement of your report | within 3 working days |
| First assessment (valid / not / need more) | within 10 working days |
| Fix for a confirmed critical issue | targeted within 30 days |
| Credit in the release notes | if you want it — tell us the name to use |

If we go quiet for longer than that, send a reminder to the same address.

There is no bug bounty. This is a small project and we cannot pay for reports.

## Disclosure

We ask for coordinated disclosure: give us the time above to ship a fix before
publishing. When a fix is released we publish the advisory, the affected
versions and the upgrade path in [CHANGELOG.md](CHANGELOG.md), and credit the
reporter unless they prefer otherwise. If a vulnerability is already being
exploited, tell us — we will move faster and say so publicly.

## Supported versions

| Version | Supported |
|---|---|
| 3.2.x | yes |
| 3.1.x | security fixes only, until 3.3.0 |
| 3.0.x | no |
| 2.x | no |
| 1.x | no |

Only the latest minor release receives fixes. There are no backports to older
minor versions.

## Scope

**In scope** — anything in this repository:

* the API, its authentication and authorization
* the web UI
* dump execution, including SSH handling and command construction
* credential storage and encryption
* the container image and the compose files

**Out of scope:**

* vulnerabilities in `mysqldump`, `pg_dump`, `ssh` or the base image — report
  those upstream, though do tell us if Pneumatik uses them unsafely
* deployments that ignore the documented requirements, for example running
  without TLS, publishing the port directly to the internet, or setting
  `PNEUMATIK_TRUST_FORWARDED_FOR=true` without a proxy in front
* missing hardening headers on a response that carries no data
* rate limiting on anything other than the login endpoint
* reports produced only by an automated scanner, with no demonstrated impact
* social engineering, physical access, or denial of service by resource
  exhaustion from an already-authenticated admin

## Safe harbour

We will not pursue or support legal action against anyone who reports in good
faith, tests only against their own installation, avoids privacy violations and
service disruption, and gives us reasonable time to respond before disclosing.

## Hardening your installation

Security-relevant configuration is documented in the
[reverse proxy guide](docs/reverse-proxy.md) and the
[getting started guide](docs/getting-started.md). The essentials:

* Terminate TLS in front of Pneumatik. The access token is a bearer token; over
  plain HTTP it is readable in transit.
* Set `PNEUMATIK_JWT_SECRET` to at least 32 random characters. Production
  refuses to start without it.
* Keep `pneumatik.key` off the machine you are backing up, and back it up
  separately. Without it, stored database passwords and SSH keys cannot be
  decrypted.
* Turn on host key verification for SSH hosts. Without it the database password
  is handed to whatever answers for that hostname.
* Scope API keys to the databases they need.
* Only set `PNEUMATIK_TRUST_FORWARDED_FOR=true` behind a proxy that overwrites
  `X-Forwarded-For`. Otherwise callers choose their own throttling identity.
