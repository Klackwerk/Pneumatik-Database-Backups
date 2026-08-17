package de.klackwerk.pneumatik.backup

class BackupTrigger4HourlyJob {

    static triggers = {
        cron name: 'backupTrigger4Hourly', cronExpression: '0 0 0,4,8,12,16,20 * * ?'
    }

    def concurrent = false

    BackupTriggerService backupTriggerService

    def execute() {
        backupTriggerService.triggerBackups(Trigger.TRIGGER_4HOURLY)
    }
}
