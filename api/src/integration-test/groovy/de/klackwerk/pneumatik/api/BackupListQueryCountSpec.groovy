package de.klackwerk.pneumatik.api

import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.BackupState
import de.klackwerk.pneumatik.backup.Trigger
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.security.User
import de.klackwerk.pneumatik.storage.StorageProvider
import grails.testing.mixin.integration.Integration
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import spock.lang.Shared
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Guards the backup-list hot path against N+1 regressions: the number of
 * SQL statements for a page must not grow with the number of rows on it.
 */
@Integration
class BackupListQueryCountSpec extends Specification {

    @Autowired
    SessionFactory sessionFactory

    @Shared HttpClient http = HttpClient.newHttpClient()
    @Shared String token

    String base() { "http://localhost:${serverPort}" }

    void setup() {
        if (!token) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base() + '/api/v1/auth/login'))
                    .header('Content-Type', 'application/json')
                    .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson([username: 'admin', password: 'admin'])))
                    .build()
            Map body = new JsonSlurper().parseText(
                    http.send(request, HttpResponse.BodyHandlers.ofString()).body()) as Map
            token = body.access_token
        }
    }

    private void seedBackups(int count) {
        Backup.withNewTransaction {
            User admin = User.findByUsername('admin')
            Host host = new Host(hostname: "qc-host-${count}", friendlyName: "QC ${count}", port: 3306, useSSL: false)
                    .save(failOnError: true)
            Database database = new Database(databaseName: "qc_db_${count}", host: host,
                    storageProvider: StorageProvider.DIRECT, trigger: Trigger.TRIGGER_MANUAL)
                    .save(failOnError: true)
            count.times { int i ->
                new Backup(database: database, success: true, state: BackupState.FINISHED,
                        storageProvider: StorageProvider.DIRECT, createdBy: i % 2 == 0 ? admin : null,
                        filename: "qc_${count}_${i}.sql.zip").save(failOnError: true)
            }
        }
    }

    private long statementsForListing(int pageSize) {
        def statistics = sessionFactory.statistics
        statistics.clear()
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(base() + "/api/v1/backups?pageSize=${pageSize}&sort=createdAt&order=desc"))
                .header('Authorization', "Bearer ${token}")
                .GET().build()
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assert response.statusCode() == 200
        assert (new JsonSlurper().parseText(response.body()) as Map).data.size() >= 1
        return statistics.prepareStatementCount
    }

    void 'the backup listing runs a constant number of statements regardless of page size'() {
        given: 'plenty of backups with associations to lazy-load if fetching regressed'
        seedBackups(3)
        seedBackups(25)

        when: 'listing a small and a large page'
        long smallPage = statementsForListing(3)
        long largePage = statementsForListing(25)

        then: 'the statement count does not grow with the rows on the page'
        largePage == smallPage

        and: 'the whole listing needs only the list query, two counts and no per-row loads'
        largePage <= 5
    }
}
