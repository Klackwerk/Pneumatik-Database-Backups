package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Database
import grails.gorm.transactions.Transactional

/**
 * Enqueues backups for all databases configured with a given trigger.
 * Centralises the logic the legacy app duplicated across its four
 * BackupTrigger*Job classes.
 */
@Transactional
class BackupTriggerService {

    BackupService backupService

    /**
     * Queue a backup for every database configured with the given trigger.
     * @return the number of backups queued
     */
    int triggerBackups(Trigger trigger) {
        List<Database> databases = Database.findAllByTrigger(trigger)
        log.debug "BACKUPTRIGGERSERVICE - Queueing ${databases.size()} backups for trigger ${trigger}"
        int queued = 0
        databases.each { Database database ->
            // skipIfPending: a database still working through the last run
            // must not accumulate a second one behind it
            if (backupService.createBackup(database, true)) {
                queued++
            }
        }
        return queued
    }
}
