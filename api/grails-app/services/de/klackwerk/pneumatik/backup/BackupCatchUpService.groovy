package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Database
import grails.gorm.transactions.Transactional

/**
 * Queues backups that a schedule should have run while the application was
 * down.
 *
 * Quartz keeps its triggers in memory, so a container that is not running at
 * 02:00 does not run the daily backups late — it simply never runs them.
 */
@Transactional
class BackupCatchUpService {

    /**
     * How far past a schedule interval a database may be before a run counts
     * as missed.
     */
    static final double INTERVAL_ALLOWANCE = 2.0

    static final Map<Trigger, Integer> SCHEDULE_INTERVAL_HOURS = [
            (Trigger.TRIGGER_HOURLY)  : 1,
            (Trigger.TRIGGER_4HOURLY) : 4,
            (Trigger.TRIGGER_12HOURLY): 12,
            (Trigger.TRIGGER_DAILY)   : 24,
    ].asImmutable()

    BackupService backupService

    /**
     * Queues one backup per scheduled database that has gone longer than its
     * interval allows without an attempt. Runs at startup.
     *
     * @return how many were queued
     */
    int enqueueMissedBackups() {
        Date now = new Date()
        int queued = 0

        Database.list().each { Database database ->
            Integer intervalHours = SCHEDULE_INTERVAL_HOURS[database.trigger]
            if (intervalHours == null) {
                return // manual only — nothing was missed
            }
            if (!isOverdue(database, intervalHours, now)) {
                return
            }
            if (backupService.createBackup(database, true)) {
                queued++
                log.info "BACKUPCATCHUPSERVICE - Queued a catch-up backup for ${database.name}"
            }
        }

        if (queued) {
            log.info "BACKUPCATCHUPSERVICE - Queued ${queued} backup(s) missed while the application was down"
        }
        return queued
    }

    private boolean isOverdue(Database database, int intervalHours, Date now) {
        List<Backup> latest = Backup.createCriteria().list(max: 1) {
            eq 'database', database
            order 'createdAt', 'desc'
        } as List<Backup>

        Date lastAttempt = latest ? latest.first().createdAt : null
        if (lastAttempt == null) {
            // never attempted: let the normal schedule introduce it rather
            // than dumping every database at once on a fresh install
            return false
        }
        long allowanceMillis = (long) (INTERVAL_ALLOWANCE * intervalHours * 60 * 60 * 1000)
        return now.time - lastAttempt.time > allowanceMillis
    }
}
