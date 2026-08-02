package de.klackwerk.pneumatik.backup

class BackupTrigger12HourlyJob {

    static triggers = {
        cron name: 'backupTrigger12Hourly', cronExpression: '0 0 1,13 * * ?'
    }

    def concurrent = false

    BackupTriggerService backupTriggerService

    def execute() {
        backupTriggerService.triggerBackups(Trigger.TRIGGER_12HOURLY)
    }
}
