# Integrated Pneumatik image: the Grails API serves the bundled React
# frontend, so one container is the whole application.

# ---- frontend build ---------------------------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /build
COPY app/package.json app/package-lock.json ./
RUN npm ci
COPY app/ .
RUN npm run build

# ---- backend build ----------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS backend
WORKDIR /build
COPY api/ .
# bundle the frontend into the app's static resources. This lands in the war
# under WEB-INF/classes/META-INF/resources, which the servlet container does
# NOT serve on its own (that only applies to jars in WEB-INF/lib) — the assets
# are mapped explicitly by SpaResourceConfig.
COPY --from=frontend /build/dist/ src/main/resources/META-INF/resources/
RUN ./gradlew --no-daemon bootWar -x test && cp build/libs/*.war app.war

# ---- runtime ----------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

# links the ghcr.io package to the repository
LABEL org.opencontainers.image.source=https://github.com/Klackwerk/Pneumatik-Database-Backups
LABEL org.opencontainers.image.description="Self-hosted MySQL, MariaDB and PostgreSQL backup service"
LABEL org.opencontainers.image.licenses=AGPL-3.0-or-later

# clients used to run the actual dumps (mysqldump / pg_dump, optionally via ssh).
# postgresql-client comes from PGDG, not from jammy: jammy ships pg_dump 14,
# which refuses to dump any server newer than itself ("server version mismatch").
# pg_dump 18 dumps every server version we run.
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        gnupg && \
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
        | gpg --dearmor -o /usr/share/keyrings/postgresql.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/postgresql.gpg] https://apt.postgresql.org/pub/repos/apt jammy-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        mariadb-client \
        postgresql-client-18 \
        openssh-client \
        tzdata && \
    apt-get purge -y gnupg && apt-get autoremove -y && \
    rm -rf /var/lib/apt/lists/*

ENV TZ=Europe/Berlin \
    GRAILS_ENV=production \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

RUN useradd --system --create-home --uid 1000 pneumatik && \
    mkdir -p /opt/storage /tmp/pneumatik && \
    chown -R pneumatik:pneumatik /opt/storage /tmp/pneumatik

USER pneumatik
WORKDIR /app
COPY --from=backend /build/app.war app.war

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s \
    CMD curl -sf http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java -Dgrails.env=$GRAILS_ENV -jar app.war"]
