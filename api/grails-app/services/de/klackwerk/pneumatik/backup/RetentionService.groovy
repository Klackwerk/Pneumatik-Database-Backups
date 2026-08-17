package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.storage.StorageService
import grails.gorm.transactions.Transactional

/**
 * Applies retention policies: deletes stored backups that fall outside a
 * database's keepCount/keepDays limits. New in 2.0 — the legacy app kept
 * every backup forever.
 */
@Transactional
class RetentionService {

    StorageService storageService

    /**
     * Creates or updates the database's retention policy.
     * @return map with the policy and whether it was newly created
     */
    Map upsertPolicy(Database database, RetentionPolicyCommand cmd) {
        RetentionPolicy policy = RetentionPolicy.findByDatabase(database)
        boolean created = policy == null
        if (created) {
            policy = new RetentionPolicy(database: database)
        }
        policy.keepCount = cmd.keepCount
        policy.keepDays = cmd.keepDays
        policy.enabled = cmd.enabled == null ? true : cmd.enabled
        policy.save(flush: true)
        return [policy: policy, created: created]
    }

    /** @return true when a policy existed and was deleted */
    boolean deletePolicy(Database database) {
        RetentionPolicy policy = RetentionPolicy.findByDatabase(database)
        if (!policy) {
            return false
        }
        policy.delete(flush: true)
        return true
    }

    /**
     * Applies every enabled retention policy.
     * @return total number of backups deleted
     */
    int applyRetentionPolicies() {
        List<RetentionPolicy> policies = RetentionPolicy.findAllByEnabled(true)
        log.info "RETENTIONSERVICE - Applying ${policies.size()} retention policies"
        int deleted = 0
        policies.each { RetentionPolicy policy ->
            try {
                deleted += applyRetention(policy)
            } catch (Exception e) {
                log.error "RETENTIONSERVICE - Failed to apply retention for database ${policy.database?.name}", e
            }
        }
        return deleted
    }

    /**
     * How many of the most recent successful backups retention will never
     * delete, whatever the policy says. Age-based rules are unbounded below:
     * once every stored backup is older than keepDays
     */
    static final int MINIMUM_RETAINED = 1

    /**
     * Applies a single policy. Only FINISHED backups are considered — failed
     * backup rows carry no stored file and are kept as history. A backup is
     * deleted when it exceeds keepCount (not among the N most recent) or
     * keepDays (older than N days).
     *
     * Two guards keep a broken database from being emptied out: age-based
     * deletion is suspended while the latest attempt is failing, and the
     * newest {@link #MINIMUM_RETAINED} backups are never deleted.
     *
     * @return number of backups deleted
     */
    int applyRetention(RetentionPolicy policy) {
        if (!policy.enabled || (!policy.keepCount && !policy.keepDays)) {
            return 0
        }

        List<Backup> finished = Backup.createCriteria().list {
            eq 'database', policy.database
            eq 'state', BackupState.FINISHED
            order 'createdAt', 'desc'
        } as List<Backup>

        Set<Backup> toDelete = [] as Set
        if (policy.keepCount && finished.size() > policy.keepCount) {
            toDelete.addAll(finished[policy.keepCount..<finished.size()])
        }
        if (policy.keepDays) {
            if (latestAttemptFailed(policy.database)) {
                log.warn 'RETENTIONSERVICE - Skipping age-based retention for database ' +
                        "${policy.database.name}: its last backup failed, so what is stored is all there is"
            } else {
                Date cutoff = new Date(System.currentTimeMillis() - policy.keepDays * 24L * 60 * 60 * 1000)
                toDelete.addAll(finished.findAll { it.createdAt && it.createdAt < cutoff })
            }
        }

        List<Backup> protectedBackups = finished.take(MINIMUM_RETAINED)
        int spared = toDelete.count { it in protectedBackups } as int
        if (spared) {
            toDelete.removeAll(protectedBackups)
            log.warn "RETENTIONSERVICE - Kept ${spared} backup(s) of database ${policy.database.name} that the " +
                    'retention policy would have deleted — a database is never left without a stored backup'
        }

        toDelete.each { Backup backup ->
            log.info "RETENTIONSERVICE - Deleting backup ${backup.id} (${backup.filename}) of database ${policy.database.name} per retention policy"
            storageService.deleteBackup(backup)
        }

        return toDelete.size()
    }

    /**
     * Whether the most recent finished or failed attempt for this database
     * failed. Queued (CREATED) rows are ignored — they exist from the moment
     * a schedule fires and would otherwise hide the last real outcome.
     */
    private boolean latestAttemptFailed(Database database) {
        List<Backup> latest = Backup.createCriteria().list(max: 1) {
            eq 'database', database
            'in' 'state', [BackupState.FINISHED, BackupState.FAILED]
            order 'createdAt', 'desc'
        } as List<Backup>
        return latest && latest.first().state == BackupState.FAILED
    }
}
