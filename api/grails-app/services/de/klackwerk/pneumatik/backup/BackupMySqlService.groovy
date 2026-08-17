package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.credentials.CredentialService
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional

@Transactional
class BackupMySqlService {

    GrailsApplication grailsApplication
    CredentialService credentialService
    BackupService backupService

    /**
     * Starts a Backup directly, sets state depending on success
     * @param backup
     * @return Backup
     */
    Backup executeBackup(Backup backup) {
        log.debug 'BACKUPMYSQLSERVICE - Create backupCommand'
        List<String> backupCommand = createBackupCommand(backup)
        return backupService.runDumpCommand(backup, backupCommand, 'MYSQL_PWD',
                credentialService.decryptString(backup.database.password))
    }

    /**
     * Builds the mysqldump argv — secret-free and without SSH wrapping:
     * the password travels via MYSQL_PWD / stdin and BackupService wraps
     * the command for SSH hosts (see BackupService.buildExecution). The
     * dump goes to stdout — the runner streams it into the temp file
     * without touching the JVM heap.
     * @param backup Backup
     * @return List&lt;String&gt;
     */
    protected List<String> createBackupCommand(Backup backup) {
        // Initialize needed Parameters
        log.debug 'BACKUPMYSQLSERVICE - Creating Backup Command'
        Date createdAt = new Date()
        backup.createdAt = createdAt
        backup.filename = BackupService.createDumpFilename(backup.database.databaseName, createdAt)

        // Update backup path
        final String tempLocation = grailsApplication.config.getProperty('pneumatik.storage.temp.path')
        backup.fullPath = BackupService.resolveStoragePath(tempLocation, backup.filename)

        // Build connection Flags and Parameters
        final List<String> connectionFlags = createBasicConnectionFlags(backup)
        final List<String> backupParameters = createBackupParameters(backup, connectionFlags)

        List<String> backupCommand = ['mysqldump'] + connectionFlags + backupParameters +
                [backup.database.databaseName]
        log.debug "BACKUPMYSQLSERVICE - Will store Backup to: ${backup.fullPath}"

        return backupCommand
    }

    /**
     * Sets the flags needed for MySQL Authentication.
     * @param backup Backup
     * @return List&lt;String&gt;
     */
    protected List<String> createBasicConnectionFlags(Backup backup) {
        // Get all needed Flags
        log.debug 'BACKUPMYSQLSERVICE - Create Basic Connection Flags'
        List<String> basicConnectionFlags = []
        if (backup.database.user) {
            basicConnectionFlags += ['-u', backup.database.user]
        }
        basicConnectionFlags += ['-h', backup.database.host.hostname]
        basicConnectionFlags += ['-P', backup.database.host.port as String]

        // add ssl parameter if needed
        if (backup.database.host.useSSL) {
            basicConnectionFlags << '--ssl'
            log.debug "BACKUPMYSQLSERVICE - Will connect with --ssl flag to host ${backup.database.host.name}"
        }

        return basicConnectionFlags
    }

    /**
     * Sets the backup Parameters depending on the detected MySQL Version on the remote Host
     * @param backup Backup
     * @param connectionFlags List&lt;String&gt;
     * @return List&lt;String&gt;
     */
    protected List<String> createBackupParameters(Backup backup, List<String> connectionFlags) {
        log.debug 'BACKUPMYSQLSERVICE - Create Backup Connection Parameters'
        List<String> backupParameters = ['--hex-blob', '--routines', '--triggers']

        try {
            String remoteVersion = determineRemoteVersion(backup, connectionFlags).toLowerCase()
            if (remoteVersion == 'mariadb') {
                log.debug 'BACKUPMYSQLSERVICE - Setting Backup Parameters for MariaDB'
            } else if (remoteVersion == 'mysql 8.x') {
                log.debug 'BACKUPMYSQLSERVICE - Setting Backup Parameters for MySQL 8.x'
                // This is mostly valid for Managed Digitalocean MySQL Instances
                backupParameters += ['--column-statistics=0', '--set-gtid-purged=OFF']
            } else {
                log.info 'Unsupported MySQL / MariaDB Version found, trying with default parameters'
            }
        } catch (NullPointerException ignored) {
            log.warn 'Unsupported MySQL / MariaDB Version found, trying with default parameters'
        }
        log.debug "BACKUPMYSQLSERVICE - Backup Parameters are ${backupParameters}"

        return backupParameters
    }

    /**
     * Determine MySQL Version of Remote Host, Returns the output of 'SELECT VERSION();'
     * @param backup Backup
     * @param connectionFlags List&lt;String&gt;
     * @return String
     */
    protected String determineRemoteVersion(Backup backup, List<String> connectionFlags) {
        log.debug 'BACKUPMYSQLSERVICE - Determining Remote Version'
        String remoteVersion = null

        try {
            List<String> mysqlCommand = ['mysql'] + connectionFlags + ['-e', 'SELECT VERSION();']

            log.debug 'BACKUPMYSQLSERVICE - Start determine remote version process'
            BackupCommandRunner.CommandResult result = backupService.runEngineCommand(backup, mysqlCommand,
                    'MYSQL_PWD', credentialService.decryptString(backup.database.password), null, 1)
            log.debug 'BACKUPMYSQLSERVICE - Finished determine remote version process'

            String versionString = null
            if (result.exitCode == 0) {
                versionString = result.output?.readLines()?.findAll { it }?.last()
                log.debug "BACKUPMYSQLSERVICE - Remote MySQL Version is: ${versionString}"
            } else {
                log.error "Determine Version process finished with exit code: ${result.exitCode}"
            }

            // Compare version String to known / compatible versions
            if (versionString) {
                if (versionString.toLowerCase().contains('mariadb')) {
                    remoteVersion = 'MariaDB'
                    log.debug 'Detected MariaDB'
                } else if (versionString.toLowerCase().contains('8.')) {
                    remoteVersion = 'MySQL 8.x'
                    log.debug 'Detected MySQL 8.x'
                }
            }
        } catch (Exception e) {
            log.error 'Could not determine remote MySQL / MariaDB version', e
        }

        return remoteVersion
    }
}
