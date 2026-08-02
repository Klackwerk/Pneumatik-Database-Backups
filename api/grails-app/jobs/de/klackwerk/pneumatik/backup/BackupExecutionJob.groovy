package de.klackwerk.pneumatik.backup

/**
 * Drains the backup queue: executes every backup in state CREATED.
 * Runs every 60 seconds, never concurrently.
 */
class BackupExecutionJob {

    static triggers = {
        simple startDelay: 5000, repeatInterval: 60000L
    }

    def concurrent = false

    BackupService backupService

    def execute() {
        List<Backup> backups = Backup.findAllByState(BackupState.CREATED)
        backups.each { Backup backup ->
            // claim first: the row moves to RUNNING only if it is still CREATED
            if (!backupService.claimBackup(backup)) {
                log.debug "BACKUPEXECUTIONJOB - Backup ${backup.id} was claimed elsewhere, skipping"
                return
            }
            log.debug "BACKUPEXECUTIONJOB - Executing backup ${backup.id}"
            backupService.executeBackup(backup)
        }
    }
}
