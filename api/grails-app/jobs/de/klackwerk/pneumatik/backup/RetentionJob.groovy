package de.klackwerk.pneumatik.backup

/**
 * Applies retention policies nightly at 03:30, after the 02:00 daily
 * backups have had time to finish.
 */
class RetentionJob {

    static triggers = {
        cron name: 'retention', cronExpression: '0 30 3 * * ?'
    }

    def concurrent = false

    RetentionService retentionService

    def execute() {
        retentionService.applyRetentionPolicies()
    }
}
