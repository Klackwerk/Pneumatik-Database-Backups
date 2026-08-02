package de.klackwerk.pneumatik.backup

class BackupTriggerHourlyJob {

    static triggers = {
        cron name: 'backupTriggerHourly', cronExpression: '0 0 * * * ?'
    }

    def concurrent = false

    BackupTriggerService backupTriggerService

    def execute() {
        backupTriggerService.triggerBackups(Trigger.TRIGGER_HOURLY)
    }
}
