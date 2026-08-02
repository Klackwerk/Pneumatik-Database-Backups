package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.storage.StorageProvider
import de.klackwerk.pneumatik.storage.StorageService
import grails.testing.gorm.DataTest
import spock.lang.Specification

class RetentionServiceSpec extends Specification implements DataTest {

    RetentionService service
    StorageService storageService
    Database database

    Class[] getDomainClassesToMock() {
        [Backup, Database, Host, RetentionPolicy] as Class[]
    }

    void setup() {
        Host host = new Host(hostname: 'localhost', port: 3306, useSSL: false).save(failOnError: true, validate: false)
        database = new Database(databaseName: 'shop', host: host, storageProvider: StorageProvider.DIRECT,
                trigger: Trigger.TRIGGER_DAILY).save(failOnError: true, validate: false)

        storageService = Mock(StorageService)
        // mirror the real deleteBackup contract: it removes the domain row too
        storageService.deleteBackup(_) >> { Backup b -> b.delete(flush: true); true }

        service = new RetentionService()
        service.storageService = storageService
    }

    private Backup finishedBackup(int daysAgo) {
        new Backup(database: database, success: true, state: BackupState.FINISHED,
                storageProvider: StorageProvider.DIRECT,
                createdAt: new Date(System.currentTimeMillis() - daysAgo * 24L * 3600 * 1000))
                .save(failOnError: true, validate: false)
    }

    private Backup failedBackup(int daysAgo) {
        new Backup(database: database, success: false, state: BackupState.FAILED,
                storageProvider: StorageProvider.DIRECT,
                createdAt: new Date(System.currentTimeMillis() - daysAgo * 24L * 3600 * 1000))
                .save(failOnError: true, validate: false)
    }

    void 'keepCount deletes only the backups beyond the N most recent'() {
        given:
        5.times { finishedBackup(it) } // ages 0..4 days
        RetentionPolicy policy = new RetentionPolicy(database: database, keepCount: 3, enabled: true)
                .save(failOnError: true, validate: false)

        when:
        int deleted = service.applyRetention(policy)

        then:
        deleted == 2
        Backup.count() == 3
        Backup.list()*.createdAt.every { it > new Date(System.currentTimeMillis() - 3 * 24L * 3600 * 1000) }
    }

    void 'keepDays deletes backups older than the cutoff'() {
        given:
        finishedBackup(1)
        finishedBackup(10)
        finishedBackup(40)
        RetentionPolicy policy = new RetentionPolicy(database: database, keepDays: 14, enabled: true)
                .save(failOnError: true, validate: false)

        expect:
        service.applyRetention(policy) == 1
        Backup.count() == 2
    }

    void 'both limits combine as OR'() {
        given: '4 backups: 0, 5, 20, 40 days old'
        [0, 5, 20, 40].each { finishedBackup(it) }
        RetentionPolicy policy = new RetentionPolicy(database: database, keepCount: 3, keepDays: 30, enabled: true)
                .save(failOnError: true, validate: false)

        expect: '40d violates both, 20d only count-limit is fine but age fine too -> only 40d goes'
        service.applyRetention(policy) == 1
        Backup.count() == 3
    }

    void 'failed backups are never touched'() {
        given:
        failedBackup(100)
        finishedBackup(0)
        RetentionPolicy policy = new RetentionPolicy(database: database, keepCount: 1, keepDays: 7, enabled: true)
                .save(failOnError: true, validate: false)

        expect:
        service.applyRetention(policy) == 0
        Backup.count() == 2
    }

    void 'disabled or empty policies do nothing'() {
        given:
        finishedBackup(100)

        expect:
        service.applyRetention(new RetentionPolicy(database: database, keepCount: 1, enabled: false)) == 0
        service.applyRetention(new RetentionPolicy(database: database, enabled: true)) == 0
        Backup.count() == 1
    }

    void 'keepDays never deletes the last remaining backup'() {
        given: 'every stored backup is older than the cutoff — the case that used to empty the shelf'
        [40, 60, 90].each { finishedBackup(it) }
        RetentionPolicy policy = new RetentionPolicy(database: database, keepDays: 14, enabled: true)
                .save(failOnError: true, validate: false)

        when:
        int deleted = service.applyRetention(policy)

        then: 'the newest survives, however old it is'
        deleted == 2
        Backup.count() == 1
        Backup.list().first().createdAt < new Date(System.currentTimeMillis() - 39 * 24L * 3600 * 1000)
    }

    void 'age-based retention is suspended while the latest backup is failing'() {
        given: 'backups stopped succeeding 30 days ago and the newest attempt failed'
        [30, 45, 60].each { finishedBackup(it) }
        failedBackup(0)
        RetentionPolicy policy = new RetentionPolicy(database: database, keepDays: 14, enabled: true)
                .save(failOnError: true, validate: false)

        when:
        int deleted = service.applyRetention(policy)

        then: 'nothing is deleted — these three are the only restorable copies left'
        deleted == 0
        Backup.count() == 4
    }

    void 'keepCount still applies while the latest backup is failing'() {
        given: 'count-based retention is safe: it always leaves N behind'
        5.times { finishedBackup(it + 10) }
        failedBackup(0)
        RetentionPolicy policy = new RetentionPolicy(database: database, keepCount: 2, keepDays: 7, enabled: true)
                .save(failOnError: true, validate: false)

        when:
        int deleted = service.applyRetention(policy)

        then:
        deleted == 3
        Backup.findAllByState(BackupState.FINISHED).size() == 2
    }

    void 'a queued backup does not hide the last real outcome'() {
        given: 'the newest row is a queued backup, the last finished attempt failed'
        finishedBackup(40)
        failedBackup(1)
        new Backup(database: database, success: false, state: BackupState.CREATED,
                storageProvider: StorageProvider.DIRECT, createdAt: new Date())
                .save(failOnError: true, validate: false)
        RetentionPolicy policy = new RetentionPolicy(database: database, keepDays: 14, enabled: true)
                .save(failOnError: true, validate: false)

        expect: 'age-based retention still recognises the failure and holds off'
        service.applyRetention(policy) == 0
    }

    void 'applyRetentionPolicies covers all enabled policies'() {
        given:
        3.times { finishedBackup(it + 10) }
        new RetentionPolicy(database: database, keepCount: 1, enabled: true).save(failOnError: true, validate: false)

        expect:
        service.applyRetentionPolicies() == 2
    }
}
