package de.klackwerk.pneumatik.config

import groovy.transform.CompileStatic
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean

import javax.sql.DataSource
import java.sql.Connection

/**
 * Applies the Flyway migrations on startup.
 *
 * Spring Boot's FlywayAutoConfiguration cannot do this for us: it is gated on
 * {@code @ConditionalOnBean(DataSource)}, and Grails registers its
 * {@code dataSource} bean *after* auto-configuration conditions are evaluated.
 * The auto-config therefore never activates, no migration ever runs, and the
 * application dies on the first query ("Table 'user' doesn't exist"). The
 * development and test environments disable Flyway outright, which is why this
 * only ever surfaced in production.
 *
 * Declared explicitly in {@code grails-app/conf/spring/resources.groovy}, so
 * the {@code dataSource} bean is injected by name and definitely exists.
 */
@CompileStatic
class FlywayMigrator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrator)

    /** Placeholder in the configured locations, replaced with the detected vendor. */
    private static final String VENDOR_PLACEHOLDER = '{vendor}'

    DataSource dataSource

    /** Mirrors spring.flyway.enabled. */
    boolean enabled = true

    /** Mirrors spring.flyway.locations; supports Flyway's {vendor} placeholder. */
    String locations = 'classpath:db/migration/{vendor}'

    @Override
    void afterPropertiesSet() {
        if (!enabled) {
            log.info 'Flyway is disabled, skipping schema migration'
            return
        }

        String resolvedLocations = locations.contains(VENDOR_PLACEHOLDER)
                ? locations.replace(VENDOR_PLACEHOLDER, detectVendor())
                : locations

        log.info "Running Flyway migrations from ${resolvedLocations}"

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations(resolvedLocations)
                .load()

        MigrateResult result = flyway.migrate()
        if (result.migrationsExecuted == 0) {
            // targetSchemaVersion is null when nothing was pending, so report
            // where the schema actually stands instead.
            log.info "Flyway: schema already up to date at version ${flyway.info().current()?.version}"
        } else {
            log.info "Flyway: applied ${result.migrationsExecuted} migration(s), " +
                    "schema now at version ${result.targetSchemaVersion}"
        }
    }

    /**
     * Maps the JDBC product name onto the db/migration/<vendor> directory
     * names, matching Spring Boot's DatabaseDriver ids.
     */
    private String detectVendor() {
        String product
        Connection connection = dataSource.connection
        try {
            product = connection.metaData.databaseProductName
        } finally {
            connection.close()
        }

        String normalised = product?.toLowerCase() ?: ''
        if (normalised.contains('mariadb')) {
            return 'mariadb'
        }
        if (normalised.contains('postgresql')) {
            return 'postgresql'
        }
        if (normalised.contains('mysql')) {
            // the MariaDB migrations are plain MySQL-compatible DDL; a
            // 'mysql' directory has never existed, so returning it made
            // Flyway silently apply nothing and the app die later on a
            // missing table — the exact failure this class exists to prevent
            return 'mariadb'
        }
        throw new IllegalStateException(
                "Cannot map database product '${product}' onto a migration directory")
    }
}
