package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.storage.StorageProvider
import grails.testing.gorm.DataTest
import spock.lang.Specification

class BackupTriggerServiceSpec extends Specification implements DataTest {

    Class[] getDomainClassesToMock() {
        [Backup, Database, Host] as Class[]
    }

    void 'only databases with the matching trigger are queued'() {
        given:
        Host host = new Host(hostname: 'localhost', port: 3306, useSSL: false).save(failOnError: true, validate: false)
        Database hourly1 = database(host, 'a', Trigger.TRIGGER_HOURLY)
        Database hourly2 = database(host, 'b', Trigger.TRIGGER_HOURLY)
        database(host, 'c', Trigger.TRIGGER_DAILY)
        database(host, 'd', Trigger.TRIGGER_MANUAL)

        BackupService backupService = Mock(BackupService)
        BackupTriggerService service = new BackupTriggerService(backupService: backupService)

        when:
        int queued = service.triggerBackups(Trigger.TRIGGER_HOURLY)

        then: 'skipIfPending is set — a scheduled run must not stack behind one already in flight'
        1 * backupService.createBackup(hourly1, true) >> new Backup()
        1 * backupService.createBackup(hourly2, true) >> new Backup()
        0 * backupService.createBackup(_, _)
        queued == 2
    }

    void 'a database that already has a backup in flight is not queued again'() {
        given:
        Host host = new Host(hostname: 'localhost', port: 3306, useSSL: false).save(failOnError: true, validate: false)
        Database busy = database(host, 'busy', Trigger.TRIGGER_HOURLY)

        BackupService backupService = Mock(BackupService)
        BackupTriggerService service = new BackupTriggerService(backupService: backupService)

        when:
        int queued = service.triggerBackups(Trigger.TRIGGER_HOURLY)

        then: 'createBackup declined, so nothing was counted'
        1 * backupService.createBackup(busy, true) >> null
        queued == 0
    }

    private Database database(Host host, String name, Trigger trigger) {
        new Database(databaseName: name, host: host, storageProvider: StorageProvider.DIRECT, trigger: trigger)
                .save(failOnError: true, validate: false)
    }
}
