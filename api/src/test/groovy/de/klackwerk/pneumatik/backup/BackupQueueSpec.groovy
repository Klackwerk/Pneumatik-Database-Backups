package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.storage.StorageProvider
import grails.config.Config
import grails.core.GrailsApplication
import grails.testing.gorm.DataTest
import spock.lang.Specification

/**
 * Queue mechanics: claiming, releasing abandoned runs, and not stacking
 * scheduled backups behind one that is still going.
 */
class BackupQueueSpec extends Specification implements DataTest {

    BackupService service
    BackupCatchUpService catchUpService
    Host host
    Database shop

    Class[] getDomainClassesToMock() {
        [Backup, Database, Host] as Class[]
    }

    void setup() {
        host = new Host(hostname: 'db.example.com', port: 3306).save(failOnError: true, validate: false)
        shop = new Database(databaseName: 'shop', host: host, storageProvider: StorageProvider.DIRECT,
                trigger: Trigger.TRIGGER_DAILY).save(failOnError: true, validate: false)

        service = new BackupService()
        service.springSecurityService = Stub(Object)
        service.grailsApplication = Stub(GrailsApplication) {
            getConfig() >> Stub(Config) {
                getProperty('pneumatik.storage.temp.path') >> '/tmp/pneumatik/temp'
            }
        }
        catchUpService = new BackupCatchUpService(backupService: service)
    }

    private Backup backup(BackupState state, int hoursAgo = 0) {
        return new Backup(database: shop, success: state == BackupState.FINISHED, state: state,
                storageProvider: StorageProvider.DIRECT,
                createdAt: new Date(System.currentTimeMillis() - hoursAgo * 3600_000L))
                .save(failOnError: true, validate: false, flush: true)
    }

    // claimBackup is a conditional UPDATE — its whole purpose is atomicity
    // against a real database, so it is covered by BackupClaimSpec in the
    // integration tests rather than here.

    void 'backups left RUNNING by a dead process are failed at startup'() {
        given: 'the container died mid-dump'
        backup(BackupState.RUNNING)
        backup(BackupState.RUNNING)
        backup(BackupState.CREATED)
        backup(BackupState.FINISHED)

        when:
        int released = service.failStaleRunningBackups()

        then: 'they would otherwise sit in the queue forever, invisible to the drainer'
        released == 2
        Backup.countByState(BackupState.RUNNING) == 0
        Backup.countByState(BackupState.FAILED) == 2

        and: 'the queued and finished ones are untouched'
        Backup.countByState(BackupState.CREATED) == 1
        Backup.countByState(BackupState.FINISHED) == 1

        and: 'the reason is recorded where the operator will look'
        Backup.findAllByState(BackupState.FAILED).every { it.output.contains('Pneumatik restarted') }
    }

    void 'a database with a queued or running backup counts as pending'() {
        expect:
        !service.hasPendingBackup(shop)

        when:
        Backup queued = backup(BackupState.CREATED)

        then:
        service.hasPendingBackup(shop)

        when:
        queued.state = BackupState.RUNNING
        queued.save(flush: true)

        then:
        service.hasPendingBackup(shop)

        when:
        queued.state = BackupState.FINISHED
        queued.save(flush: true)

        then:
        !service.hasPendingBackup(shop)
    }

    void 'a scheduled run is skipped while one is still in flight'() {
        given: 'a dump that takes longer than the schedule interval'
        backup(BackupState.RUNNING)

        expect: 'otherwise every tick would add another, each making the next later'
        service.createBackup(shop, true) == null
        Backup.countByState(BackupState.CREATED) == 0
    }

    void 'a manual run is queued even when one is in flight'() {
        given:
        backup(BackupState.RUNNING)

        expect: 'the operator asked for it explicitly'
        service.createBackup(shop) != null
        Backup.countByState(BackupState.CREATED) == 1
    }

    void 'catch-up queues a backup for a schedule that was missed while down'() {
        given: 'a daily database last attempted three days ago'
        backup(BackupState.FINISHED, 72)

        when:
        int queued = catchUpService.enqueueMissedBackups()

        then:
        queued == 1
        Backup.countByState(BackupState.CREATED) == 1
    }

    void 'catch-up leaves a database that is within its schedule alone'() {
        given: 'a daily database attempted an hour ago'
        backup(BackupState.FINISHED, 1)

        expect:
        catchUpService.enqueueMissedBackups() == 0
    }

    void 'catch-up ignores manual databases and never-attempted ones'() {
        given:
        shop.trigger = Trigger.TRIGGER_MANUAL
        shop.save(flush: true, validate: false)
        backup(BackupState.FINISHED, 1000)

        expect: 'nothing is expected of a manual database, however old its last run'
        catchUpService.enqueueMissedBackups() == 0

        when: 'a scheduled database that has never run at all'
        Database fresh = new Database(databaseName: 'fresh', host: host, storageProvider: StorageProvider.DIRECT,
                trigger: Trigger.TRIGGER_DAILY).save(failOnError: true, validate: false, flush: true)

        then: 'the schedule introduces it, rather than every database dumping at boot'
        catchUpService.enqueueMissedBackups() == 0
        fresh != null
    }
}
