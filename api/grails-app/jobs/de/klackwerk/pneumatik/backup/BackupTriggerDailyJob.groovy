package de.klackwerk.pneumatik.backup

class BackupTriggerDailyJob {

    static triggers = {
        cron name: 'backupTriggerDaily', cronExpression: '0 0 2 * * ?'
    }

    def concurrent = false

    BackupTriggerService backupTriggerService

    def execute() {
        backupTriggerService.triggerBackups(Trigger.TRIGGER_DAILY)
    }
}
