package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.credentials.CredentialService
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional

@Transactional
class BackupPostgreSqlService {

    GrailsApplication grailsApplication
    CredentialService credentialService
    BackupService backupService

    /**
     * Starts a Backup directly, sets state depending on success
     * @param backup
     * @return Backup
     */
    Backup executeBackup(Backup backup) {
        log.debug 'BACKUPPOSTGRESQLSERVICE - Create backupCommand'
        List<String> backupCommand = createBackupCommand(backup)
        return backupService.runDumpCommand(backup, backupCommand, 'PGPASSWORD',
                credentialService.decryptString(backup.database.password), createEngineEnvironment(backup))
    }

    /**
     * Builds the pg_dump argv — secret-free and without SSH wrapping:
     * the password travels via PGPASSWORD / stdin and BackupService wraps
     * the command for SSH hosts (see BackupService.buildExecution). The
     * dump goes to stdout — the runner streams it into the temp file
     * without touching the JVM heap.
     * @param backup Backup
     * @return List&lt;String&gt;
     */
    protected List<String> createBackupCommand(Backup backup) {
        // Initialize needed Parameters
        log.debug 'BACKUPPOSTGRESQLSERVICE - Creating Backup Command'
        Date createdAt = new Date()
        backup.createdAt = createdAt
        backup.filename = BackupService.createDumpFilename(backup.database.databaseName, createdAt)

        // Update backup path
        final String tempLocation = grailsApplication.config.getProperty('pneumatik.storage.temp.path')
        backup.fullPath = BackupService.resolveStoragePath(tempLocation, backup.filename)

        // Build connection Flags
        final List<String> connectionFlags = createBasicConnectionFlags(backup)

        List<String> backupCommand = ['pg_dump'] + connectionFlags + [backup.database.databaseName]
        log.debug "BACKUPPOSTGRESQLSERVICE - Will store Backup to: ${backup.fullPath}"

        return backupCommand
    }

    /**
     * Sets the flags needed for PostgreSQL Authentication
     * @param backup Backup
     * @return List&lt;String&gt;
     */
    protected List<String> createBasicConnectionFlags(Backup backup) {
        // Get all needed Flags
        log.debug 'BACKUPPOSTGRESQLSERVICE - Create Basic Connection Flags'
        List<String> basicConnectionFlags = []
        if (backup.database.user) {
            basicConnectionFlags += ['-U', backup.database.user]
        }
        basicConnectionFlags += ['-h', backup.database.host.hostname]
        basicConnectionFlags += ['-p', backup.database.host.port as String]

        return basicConnectionFlags
    }

    /**
     * SSL is requested through PGSSLMODE
     * @param backup Backup
     * @return Map&lt;String, String&gt;
     */
    protected Map<String, String> createEngineEnvironment(Backup backup) {
        if (!backup.database.host.useSSL) {
            return [:]
        }
        log.debug "BACKUPPOSTGRESQLSERVICE - Will connect with PGSSLMODE=require to host ${backup.database.host.name}"
        return [PGSSLMODE: 'require']
    }
}
