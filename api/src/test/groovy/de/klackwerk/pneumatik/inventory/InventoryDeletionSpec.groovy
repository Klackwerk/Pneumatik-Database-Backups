package de.klackwerk.pneumatik.inventory

import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.BackupState
import de.klackwerk.pneumatik.backup.RetentionPolicy
import de.klackwerk.pneumatik.backup.Trigger
import de.klackwerk.pneumatik.credentials.CredentialService
import de.klackwerk.pneumatik.storage.StorageProvider
import de.klackwerk.pneumatik.storage.StorageService
import grails.testing.gorm.DataTest
import spock.lang.Specification

class InventoryDeletionSpec extends Specification implements DataTest {

    DatabaseService databaseService
    HostService hostService
    StorageService storageService
    Host host
    Database shop

    Class[] getDomainClassesToMock() {
        [Backup, Database, Host, RetentionPolicy] as Class[]
    }

    void setup() {
        host = new Host(hostname: 'db.example.com', port: 3306).save(failOnError: true, validate: false)
        shop = database('shop')

        storageService = Mock(StorageService)
        // mirror the real contract: deleteBackup removes the stored file and the row
        storageService.deleteBackup(_) >> { Backup b -> b.delete(flush: true); true }

        databaseService = new DatabaseService(credentialService: Stub(CredentialService),
                storageService: storageService)
        hostService = new HostService(credentialService: Stub(CredentialService),
                databaseService: databaseService)
    }

    private Database database(String name) {
        return new Database(databaseName: name, host: host, storageProvider: StorageProvider.DIRECT,
                trigger: Trigger.TRIGGER_DAILY).save(failOnError: true, validate: false)
    }

    private Backup backup(Database database, String filename = 'shop.sql.zip') {
        return new Backup(database: database, success: true, state: BackupState.FINISHED,
                storageProvider: StorageProvider.DIRECT, filename: filename, createdAt: new Date())
                .save(failOnError: true, validate: false)
    }

    void 'the deletion impact counts what would actually be destroyed'() {
        given:
        backup(shop)
        backup(shop)
        backup(shop, null) // a failed run keeps no file

        expect:
        databaseService.deletionImpact(shop) == [backupCount: 3L, storedFileCount: 2L]
    }

    void 'deleting a database takes its backups, archives and retention policy'() {
        given:
        backup(shop)
        backup(shop)
        new RetentionPolicy(database: shop, keepCount: 5).save(failOnError: true, validate: false)

        when:
        databaseService.deleteDatabase(shop)

        then: 'each stored archive is removed, not just the rows'
        2 * storageService.deleteBackup(_) >> { Backup b -> b.delete(flush: true); true }
        Database.count() == 0
        Backup.count() == 0
        RetentionPolicy.count() == 0

        and: 'the host it was on stays'
        Host.count() == 1
    }

    void 'a backup whose archive cannot be deleted still loses its record'() {
        given: 'storage is unavailable for one archive'
        backup(shop)
        databaseService.storageService = Mock(StorageService) {
            deleteBackup(_) >> { throw new IllegalStateException('storage offline') }
        }

        when:
        databaseService.deleteDatabase(shop)

        then: 'the delete completes rather than leaving a half-deleted database'
        Database.count() == 0
        Backup.count() == 0
    }

    void 'the host deletion impact names every database that would go with it'() {
        given:
        Database analytics = database('analytics')
        backup(shop)
        backup(analytics)

        when:
        Map impact = hostService.deletionImpact(host)

        then:
        impact.databaseCount == 2
        impact.databaseNames == ['analytics', 'shop']
        impact.backupCount == 2L
    }

    void 'deleting a host cascades through every database on it'() {
        given:
        Database analytics = database('analytics')
        backup(shop)
        backup(analytics)

        when:
        hostService.deleteHost(host)

        then:
        Host.count() == 0
        Database.count() == 0
        Backup.count() == 0
    }

    void 'deleting a host with no databases removes just the host'() {
        given:
        Host spare = new Host(hostname: 'unused.example.com', port: 3306).save(failOnError: true, validate: false)

        when:
        hostService.deleteHost(spare)

        then:
        hostService.deletionImpact(host).databaseCount == 1
        Host.count() == 1
        Database.count() == 1
    }
}
