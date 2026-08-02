package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.storage.StorageProvider
import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import spock.lang.Specification

/**
 * Claiming is a conditional UPDATE against a real database — the point is
 * that only one caller can win it, which an in-memory datastore cannot
 * demonstrate.
 */
@Integration
@Rollback
class BackupClaimSpec extends Specification {

    BackupService backupService

    private Backup queuedBackup() {
        Host host = new Host(hostname: 'claim.example.com', port: 3306, useSSL: false).save(failOnError: true)
        Database database = new Database(databaseName: 'claimed', host: host,
                storageProvider: StorageProvider.DIRECT, trigger: Trigger.TRIGGER_MANUAL).save(failOnError: true)
        return new Backup(database: database, success: false, state: BackupState.CREATED,
                storageProvider: StorageProvider.DIRECT, createdAt: new Date()).save(failOnError: true, flush: true)
    }

    void 'a queued backup can be claimed, and only once'() {
        given:
        Backup backup = queuedBackup()

        expect: 'the first drainer takes it'
        backupService.claimBackup(backup)
        backup.state == BackupState.RUNNING

        and: 'a second instance draining the same queue finds nothing to take'
        !backupService.claimBackup(backup)
    }

    void 'a backup that is no longer queued cannot be claimed'() {
        given:
        Backup backup = queuedBackup()
        backup.state = BackupState.FINISHED
        backup.save(flush: true)

        expect:
        !backupService.claimBackup(backup)
        backup.state == BackupState.FINISHED
    }
}
