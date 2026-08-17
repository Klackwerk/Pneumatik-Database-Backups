# Running behind a reverse proxy

Pneumatik serves plain HTTP on port 8080 and does not terminate TLS. Put a
proxy in front of it. The access token is a bearer token in `Authorization`
headers and in `localStorage` — over plain HTTP it is readable by anything on
the path.

## What the application needs from the proxy

| Concern | What to do |
|---|---|
| TLS | Terminate at the proxy. Nothing else is required in Pneumatik. |
| HSTS | Sent automatically once the request is recognised as secure. |
| Client address | Set `PNEUMATIK_TRUST_FORWARDED_FOR=true` **only** if the proxy overwrites `X-Forwarded-For`. |
| Scheme | Forward `X-Forwarded-Proto`; it decides whether HSTS is sent. |
| Downloads | Disable response buffering and raise the read timeout. Dumps are large. |
| WebSockets | Not used. Nothing to configure. |

`PNEUMATIK_TRUST_FORWARDED_FOR` is off by default, and that default is right
for a deployment that publishes port 8080 directly. Turning it on where the
header is not overwritten lets any caller pick its own identity for login
throttling by sending the header itself.

## nginx

```nginx
server {
    listen 443 ssl http2;
    server_name backup.example.com;

    ssl_certificate     /etc/letsencrypt/live/backup.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/backup.example.com/privkey.pem;

    # a dump can be many gigabytes and takes as long as it takes
    proxy_read_timeout 1h;
    proxy_send_timeout 1h;
    proxy_buffering off;
    client_max_body_size 1m;   # nothing is uploaded to Pneumatik

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $remote_addr;   # overwrite, do not append
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}

server {
    listen 80;
    server_name backup.example.com;
    return 301 https://$host$request_uri;
}
```

`X-Forwarded-For $remote_addr` — not `$proxy_add_x_forwarded_for` — is what
makes `PNEUMATIK_TRUST_FORWARDED_FOR=true` safe. The appending form keeps
whatever the client sent, which is exactly the value you must not trust.

## Caddy

```
backup.example.com {
    reverse_proxy 127.0.0.1:8080 {
        header_up X-Forwarded-For {remote_host}
        transport http {
            read_timeout 1h
        }
    }
}
```

Caddy obtains and renews the certificate itself and sets `X-Forwarded-Proto`
without further configuration.

## Traefik (labels on the compose service)

```yaml
labels:
  - traefik.enable=true
  - traefik.http.routers.pneumatik.rule=Host(`backup.example.com`)
  - traefik.http.routers.pneumatik.entrypoints=websecure
  - traefik.http.routers.pneumatik.tls.certresolver=le
  - traefik.http.services.pneumatik.loadbalancer.server.port=8080
  - traefik.http.services.pneumatik.loadbalancer.responseforwarding.flushinterval=1ms
```

Traefik sets the forwarded headers itself. Make sure the entrypoint's
`forwardedHeaders.trustedIPs` is configured, otherwise the client-supplied
header is passed through.

## After wiring it up

Publish the container on localhost only, so the proxy is the sole route in:

```yaml
ports:
  - '127.0.0.1:8080:8080'
```

Then set the matching environment:

```sh
PNEUMATIK_TRUST_FORWARDED_FOR=true
PNEUMATIK_CORS_ORIGINS=          # the bundled UI is same-origin; leave empty
```

Set `PNEUMATIK_CORS_ORIGINS` only for a separate frontend or an external API
consumer running on another origin.

## Checking it

```sh
# HSTS present, and the security headers are there
curl -sI https://backup.example.com/ | grep -iE 'strict-transport|content-security|x-frame'

# plain HTTP redirects rather than serving
curl -sI http://backup.example.com/ | head -1

# the throttle sees the real client, not the proxy: five bad logins from one
# address should end in 429
for i in $(seq 1 6); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST https://backup.example.com/api/v1/auth/login \
    -H 'Content-Type: application/json' -d '{"username":"admin","password":"wrong"}'
done
```

If the last call returns `401` instead of `429`, every request is arriving with
the same proxy address or the header is not being overwritten — recheck the
`X-Forwarded-For` line.
