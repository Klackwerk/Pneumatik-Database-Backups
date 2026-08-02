package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.credentials.CredentialService
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.DatabaseType
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.notification.SendMailService
import de.klackwerk.pneumatik.security.User
import de.klackwerk.pneumatik.storage.StorageService
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.DateFormat
import java.text.SimpleDateFormat

@Transactional
class BackupService {

    static final long DUMP_TIMEOUT_MINUTES = 30
    /** legacy marker for "process never ran / never delivered an exit code" */
    static final int EXIT_CODE_NOT_RUN = 1312
    /** longest database-name prefix kept in a dump filename */
    static final int MAX_FILENAME_STEM = 100
    /** multiple of the last dump size that must be free before starting */
    static final double DISK_SPACE_HEADROOM = 1.5

    GrailsApplication grailsApplication
    CredentialService credentialService
    SendMailService sendMailService
    StorageService storageService
    def springSecurityService
    BackupMySqlService backupMySqlService
    BackupPostgreSqlService backupPostgreSqlService

    /** whitelist of sortable fields for the backup listing */
    static final Map<String, String> SORTABLE_FIELDS = [
            createdAt : 'b.createdAt',
            executedAt: 'b.executedAt',
            state     : 'b.state',
            size      : 'b.size',
            success   : 'b.success',
            name      : 'coalesce(d.friendlyName, d.databaseName)',
    ].asImmutable()

    /**
     * Creates a backup which is "in queue"
     *
     * @param skipIfPending don't queue when this database already has a
     *        backup waiting or running. Scheduled triggers pass true: a
     *        database whose dump takes longer than its interval would
     *        otherwise pile up runs forever, each one making the next later.
     * @return the queued backup, or null when it was skipped
     */
    Backup createBackup(Database database, boolean skipIfPending = false) {
        log.debug "BACKUPSERVICE - Creating backup for database ${database.name}"

        if (skipIfPending && hasPendingBackup(database)) {
            log.info "BACKUPSERVICE - Skipping backup of ${database.name}: one is already queued or running"
            return null
        }

        User user = null
        try {
            log.debug "BACKUPSERVICE - Backup created by ${springSecurityService.getCurrentUser()}"
            user = springSecurityService.getCurrentUser() as User
        } catch (Exception e) {
            log.debug 'BACKUPSERVICE - Backup not created by any user', e
        }

        Backup backup = new Backup(database: database, success: false, state: BackupState.CREATED,
                storageProvider: database.storageProvider, createdBy: user).save()
        log.debug "BACKUPSERVICE - Created Backup Object with id: ${backup.id}"
        return backup
    }

    /** Whether this database already has a backup queued or in flight. */
    boolean hasPendingBackup(Database database) {
        return Backup.createCriteria().count {
            eq 'database', database
            'in' 'state', [BackupState.CREATED, BackupState.RUNNING]
        } > 0
    }

    /**
     * Claims a queued backup for execution by moving it to RUNNING.
     *
     * @return true when this caller owns the backup
     */
    boolean claimBackup(Backup backup) {
        int claimed = Backup.executeUpdate(
                'update Backup b set b.state = :running where b.id = :id and b.state = :created',
                [running: BackupState.RUNNING, created: BackupState.CREATED, id: backup.id])
        if (claimed) {
            backup.refresh()
        }
        return claimed > 0
    }

    /**
     * Fails backups left RUNNING by a previous process.
     *
     * Nothing is executing them any more — the container that owned them is
     * gone — so without this they would sit in the queue forever, invisible
     * to both the drainer (which only takes CREATED) and the operator.
     *
     * @return how many were released
     */
    int failStaleRunningBackups() {
        List<Backup> stale = Backup.createCriteria().list {
            eq 'state', BackupState.RUNNING
        } as List<Backup>

        stale.each { Backup backup ->
            log.warn "BACKUPSERVICE - Backup ${backup.id} was still RUNNING at startup; marking it failed"
            backup.state = BackupState.FAILED
            backup.success = false
            backup.output = appendDiagnostic(backup.output,
                    'Pneumatik restarted while this backup was running. The run was abandoned; ' +
                            'the next scheduled backup will try again.')
            backup.finishedAt = backup.finishedAt ?: new Date()
            backup.save()
            deletePartialDump(backup)
        }
        return stale.size()
    }

    /**
     * Delegates Backup execution to the respective service
     * @param backup
     * @return Boolean
     */
    Boolean executeBackup(Backup backup) {
        log.debug "BACKUPSERVICE - Executing backup with id: ${backup.id}"
        if (backup.database.databaseType == DatabaseType.MYSQL || !backup.database.databaseType) {
            backup = backupMySqlService.executeBackup(backup)
        } else if (backup.database.databaseType == DatabaseType.POSTGRESQL) {
            backup = backupPostgreSqlService.executeBackup(backup)
        }

        return backup.success
    }

    /**
     * The ssh client invocation for a host, authenticating against an
     * ephemeral ssh-agent (see {@link #buildExecution}).
     *
     * @param nullStdin adds -n (stdin from /dev/null); false when the
     *        database password is piped through ssh's stdin
     */
    String generateSSHConnectionString(Backup backup, boolean nullStdin = true, String knownHostsPath = null) {
        log.debug 'BACKUPSERVICE - Generate SSH connection String'
        Host host = backup.database.host
        String destination = host.sshUser + '@' + host.sshHostname
        return "ssh ${hostKeyOptions(host, knownHostsPath)} -o BatchMode=yes ${nullStdin ? '-n ' : ''}" +
                "-p ${ShellCommand.quote(host.sshPort as String)} " +
                "${ShellCommand.quote(destination)} "
    }

    /**
     * How ssh should treat the far end's host key.
     *
     * Without verification anyone who can answer for the hostname receives
     * the database password, which travels through ssh's stdin. With it on,
     * a pinned key must match; if none is pinned yet the first connection
     * records what it sees ({@code accept-new}) and {@link #learnHostKey}
     * stores it for every run after that.
     */
    protected static String hostKeyOptions(Host host, String knownHostsPath) {
        if (!host.verifyHostKey || !knownHostsPath) {
            // /dev/null keeps an unverified run from seeding a key store that
            // a later verified run would then trust
            return '-o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null'
        }
        String mode = host.hostKey ? 'yes' : 'accept-new'
        return "-o StrictHostKeyChecking=${mode} -o UserKnownHostsFile=${ShellCommand.quote(knownHostsPath)}"
    }

    /** Decrypted SSH key for stdin injection; null when the host doesn't use SSH. */
    String sshKeyStdin(Backup backup) {
        if (!backup.database.host.executeViaSSH) {
            return null
        }
        String key = credentialService.decryptString(backup.database.host.sshKey)
        if (key == null) {
            throw new IllegalStateException("SSH key for host ${backup.database.host.id} could not be decrypted")
        }
        return key.endsWith('\n') ? key : key + '\n'
    }

    /**
     * Assembles process, stdin and environment for an engine command so
     * that NO secret ever appears on a command line (visible in `ps` /
     * /proc/&#42;/cmdline), locally or on the remote host:
     *
     * - local: the password travels as an environment variable
     *   (MYSQL_PWD / PGPASSWORD) set via ProcessBuilder, the command stays
     *   secret-free.
     * - SSH: stdin carries `password\n` followed by the private key. The
     *   local wrapper reads the password into a shell variable (bash `read`
     *   consumes exactly one line from a pipe), hands the remaining stdin
     *   to `ssh-add -`, then pipes the password into ssh where the remote
     *   shell reads it into the engine's password variable. The engine
     *   command is identical on both ends and contains no secret.
     *
     * The engine command is an argv. Locally it is handed
     * to the process unparsed. Remotely it is shell-quoted once for
     * the remote login shell and once more for the local wrapper.
     *
     * @param extraEnvironment engine settings that are not secrets (e.g.
     *        PGSSLMODE); applied on both transports
     * @return [command: List&lt;String&gt;, stdin: String?, environment: Map]
     */
    Map buildExecution(Backup backup, List<String> engineArgv, String passwordEnvVar, String password,
                       Map<String, String> extraEnvironment = [:], String knownHostsPath = null) {
        if (!backup.database.host.executeViaSSH) {
            Map<String, String> environment = [:]
            environment.putAll(extraEnvironment)
            if (password != null) {
                environment.put(passwordEnvVar, password)
            }
            return [command: engineArgv, stdin: null, environment: environment]
        }

        String key = sshKeyStdin(backup)
        String engineCommand = buildRemoteCommand(engineArgv, extraEnvironment)
        if (password == null) {
            String script = 'ssh-add -q - && ' + generateSSHConnectionString(backup, true, knownHostsPath) +
                    ShellCommand.quote(engineCommand)
            return [command: ['ssh-agent', '/bin/bash', '-c', script], stdin: key, environment: [:]]
        }

        String remote = "IFS= read -r ${passwordEnvVar}; export ${passwordEnvVar}; ${engineCommand}"
        String script = 'IFS= read -r PNEUMATIK_DB_PASSWORD && ssh-add -q - && ' +
                'printf \'%s\\n\' "$PNEUMATIK_DB_PASSWORD" | ' +
                generateSSHConnectionString(backup, false, knownHostsPath) + ShellCommand.quote(remote)
        return [command: ['ssh-agent', '/bin/bash', '-c', script], stdin: password + '\n' + key, environment: [:]]
    }

    /** The engine command as the remote login shell will see it. */
    protected static String buildRemoteCommand(List<String> engineArgv, Map<String, String> extraEnvironment) {
        String prefix = ShellCommand.environmentPrefix(extraEnvironment)
        String command = ShellCommand.join(engineArgv)
        return prefix ? prefix + ' ' + command : command
    }

    /**
     * Runs an engine command (dump or probe) with the assembly from
     * {@link #buildExecution}. Streams stdout to {@code stdoutFile} when
     * given, else captures it (probes only).
     */
    BackupCommandRunner.CommandResult runEngineCommand(Backup backup, List<String> engineArgv, String passwordEnvVar,
                                                       String password, File stdoutFile, long timeoutMinutes,
                                                       Map<String, String> extraEnvironment = [:]) {
        Host host = backup.database.host
        boolean verifying = host.executeViaSSH && host.verifyHostKey

        KnownHostsFile knownHosts = verifying
                ? KnownHostsFile.create(grailsApplication.config.getProperty('pneumatik.storage.temp.path'),
                        host.hostKey)
                : null
        try {
            Map execution = buildExecution(backup, engineArgv, passwordEnvVar, password, extraEnvironment,
                    knownHosts?.path?.toString())
            List<String> command = execution.command as List<String>
            String stdin = execution.stdin as String
            Map<String, String> environment = execution.environment as Map<String, String>

            BackupCommandRunner.CommandResult result = stdoutFile != null
                    ? BackupCommandRunner.runToFile(command, stdoutFile, stdin, timeoutMinutes, environment)
                    : BackupCommandRunner.runCaptured(command, stdin, timeoutMinutes, environment)

            if (knownHosts && result.exitCode == 0) {
                learnHostKey(host, knownHosts)
            }
            return result
        } finally {
            knownHosts?.close()
        }
    }

    /**
     * Pins whatever host key a first successful connection saw, so later
     * runs verify against it. Only ever fills an empty pin — an existing one
     * changing means ssh already refused the connection.
     */
    protected void learnHostKey(Host host, KnownHostsFile knownHosts) {
        if (host.hostKey) {
            return
        }
        String recorded = knownHosts.recordedKey()
        if (!recorded) {
            return
        }
        host.hostKey = recorded
        host.save(flush: true)
        log.info "BACKUPSERVICE - Pinned the SSH host key of ${host.name}; later backups verify against it"
    }

    /**
     * Shared execution path for all dump commands: runs the command with the
     * dump streamed to the temp file by the OS (nothing passes through the
     * JVM heap), captures stderr as the backup's output, records duration
     * and sizes, stores the archive and handles failure notification.
     */
    Backup runDumpCommand(Backup backup, List<String> dumpCommand, String passwordEnvVar, String password,
                          Map<String, String> extraEnvironment = [:]) {
        backup.exitCode = EXIT_CODE_NOT_RUN
        try {
            backup.executedAt = new Date()

            String spaceProblem = checkDiskSpace(backup)
            if (spaceProblem) {
                throw new IllegalStateException(spaceProblem)
            }

            log.debug "BACKUPSERVICE - Starting backup process for backup ${backup.id}"
            BackupCommandRunner.CommandResult result = runEngineCommand(backup, dumpCommand,
                    passwordEnvVar, password, new File(backup.fullPath), dumpTimeoutMinutes(), extraEnvironment)
            backup.finishedAt = new Date()
            backup.exitCode = result.exitCode
            backup.output = result.output ?: null
            log.debug "BACKUPSERVICE - Backup process finished with exit code ${result.exitCode}"

            if (result.exitCode == 0) {
                try {
                    backup.rawSizeBytes = Files.size(Paths.get(backup.fullPath))
                    Double fileSizeInMb = backup.rawSizeBytes / 1024 / 1024
                    backup.size = fileSizeInMb.round(3).toString() + ' MB'
                } catch (Exception e) {
                    log.error 'Could not calculate Backup size', e
                }

                // A dump tool can exit 0 having written nothing — a remote
                // shell that swallowed the command, a redirect that never
                // received data. Such a backup restores nothing, so it must
                // not be recorded as one.
                if (backup.rawSizeBytes != null && backup.rawSizeBytes == 0L) {
                    log.error "Backup ${backup.id} produced an empty dump"
                    backup.output = appendDiagnostic(backup.output,
                            'The dump command succeeded but wrote no data. Check that the database exists ' +
                                    'and that the backup user may read it.')
                    backup.state = BackupState.FAILED
                } else if (storageService.storeBackup(backup)) {
                    backup.success = true
                    backup.state = BackupState.FINISHED
                    notifyWhenRecovered(backup)
                } else {
                    log.error "Could not store backup ${backup.id}"
                    backup.state = BackupState.FAILED
                }
            } else {
                log.debug 'BACKUPSERVICE - Backup was not successful'
                backup.state = BackupState.FAILED
            }
        } catch (Exception e) {
            backup.state = BackupState.FAILED
            backup.finishedAt = backup.finishedAt ?: new Date()
            log.error "BACKUPSERVICE - Exception during backup ${backup.id}", e
        } finally {
            if (backup.state == BackupState.FAILED) {
                deletePartialDump(backup)
                try {
                    log.error "Backup failed with exitCode ${backup.exitCode}, will send mail notification to admin"
                    sendMailService.notifyOnFailedBackup(backup)
                } catch (Exception e) {
                    log.error 'Error sending mail', e
                }
            }
            backup.save()
        }
        return backup
    }

    /**
     * Sends the recovery notification when this successful backup follows a
     * failed one.
     */
    private void notifyWhenRecovered(Backup backup) {
        try {
            List previous = Backup.executeQuery(
                    'select b.state from Backup b where b.database = :database and b.id != :id order by b.createdAt desc',
                    [database: backup.database, id: backup.id], [max: 1])
            if (previous && previous[0] == BackupState.FAILED) {
                sendMailService.notifyOnRecoveredBackup(backup)
            }
        } catch (Exception e) {
            log.error 'Error sending recovery mail', e
        }
    }

    /** Dump timeout in minutes; configurable. */
    long dumpTimeoutMinutes() {
        return (grailsApplication.config.getProperty('pneumatik.backup.timeout-minutes', Integer,
                DUMP_TIMEOUT_MINUTES as int)) as long
    }

    /**
     * Refuses to start a dump that the disk cannot hold.
     *
     * @return a message describing the problem, or null when there is room
     */
    protected String checkDiskSpace(Backup backup) {
        Long expected = lastRawSize(backup.database)
        File tempDir = new File(backup.fullPath).parentFile
        if (expected == null || tempDir == null) {
            return null // nothing to compare against yet
        }

        long required = (long) (expected * DISK_SPACE_HEADROOM)
        long usable = tempDir.usableSpace
        if (usable <= 0 || usable >= required) {
            return null
        }
        return "Not enough space in ${tempDir}: the last dump of this database was " +
                "${formatBytes(expected)}, leaving ${formatBytes(usable)} free is too little. " +
                'Free up space or point PNEUMATIK_TEMP_PATH at a larger volume.'
    }

    private static Long lastRawSize(Database database) {
        List<Backup> previous = Backup.createCriteria().list(max: 1) {
            eq 'database', database
            eq 'state', BackupState.FINISHED
            isNotNull 'rawSizeBytes'
            order 'createdAt', 'desc'
        } as List<Backup>
        return previous ? previous.first().rawSizeBytes : null
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return "${bytes} B"
        if (bytes < 1024 * 1024) return "${(bytes / 1024).round(1)} KB"
        if (bytes < 1024L * 1024 * 1024) return "${(bytes / 1024 / 1024).round(1)} MB"
        return "${(bytes / 1024 / 1024 / 1024).round(2)} GB"
    }

    /** Adds a Pneumatik diagnostic below whatever the dump tool reported. */
    private static String appendDiagnostic(String output, String message) {
        return output ? output + '\n\n' + message : message
    }

    /** Removes a partial dump left in temp storage by a failed run. */
    private void deletePartialDump(Backup backup) {
        if (!backup.fullPath) {
            return
        }
        try {
            Files.deleteIfExists(Paths.get(backup.fullPath))
        } catch (Exception e) {
            log.error "Could not delete partial dump ${backup.fullPath}", e
        }
    }

    /**
     * Paginated backup listing for the API. Replaces the legacy DataTables
     * raw-SQL query with HQL; database and creator are join-fetched.
     *
     * @return map with items (List&lt;Backup&gt;), total, filtered counts
     */
    @Transactional(readOnly = true)
    Map listBackups(int offset, int max, String search, String sort, String order, String databaseId = null,
                    String hostId = null, BackupState state = null) {
        String sortExpression = SORTABLE_FIELDS[sort] ?: SORTABLE_FIELDS.createdAt
        String direction = order == 'asc' ? 'asc' : 'desc'

        Map params = [:]
        String where = ' where 1=1'
        if (search) {
            where += ' and lower(coalesce(d.friendlyName, d.databaseName)) like :search'
            params.search = "%${search.toLowerCase()}%".toString()
        }
        if (databaseId != null) {
            where += ' and d.id = :databaseId'
            params.databaseId = databaseId
        }
        if (hostId != null) {
            where += ' and d.host.id = :hostId'
            params.hostId = hostId
        }
        if (state != null) {
            where += ' and b.state = :state'
            params.state = state
        }

        String listQuery = 'select b from Backup b join fetch b.database d join fetch d.host left join fetch b.createdBy ' +
                where + ' order by ' + sortExpression + ' ' + direction
        List<Backup> items = Backup.executeQuery(listQuery, params, [max: max, offset: offset]) as List<Backup>

        String countQuery = 'select count(b) from Backup b join b.database d ' + where
        Long filtered = (Backup.executeQuery(countQuery, params)[0]) as Long
        Long total = (Backup.executeQuery('select count(b) from Backup b')[0]) as Long

        return [items: items, total: total, filtered: filtered]
    }

    /**
     * Formate date to Timestamp
     * @param date Date
     * @return String
     */
    static String createTimestamp(Date date) {
        DateFormat dateFormat = new SimpleDateFormat('yyyyMMdd_HHmmss')
        return dateFormat.format(date)
    }

    /**
     * Builds the dump filename from a database name.
     *
     */
    static String createDumpFilename(String databaseName, Date createdAt) {
        String stem = (databaseName ?: '').replaceAll(/[^A-Za-z0-9_.-]/, '_')
        if (!stem || stem ==~ /\.+/) {
            stem = 'database'
        }
        if (stem.length() > MAX_FILENAME_STEM) {
            stem = stem.substring(0, MAX_FILENAME_STEM)
        }
        return stem + '_' + createTimestamp(createdAt) + '.sql'
    }

    /**
     * Resolves a dump filename inside its directory and refuses anything
     * that escapes it — the backstop behind {@link #createDumpFilename}
     */
    static String resolveStoragePath(String directory, String filename) {
        Path base = Paths.get(directory).toAbsolutePath().normalize()
        Path resolved = base.resolve(filename).normalize()
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Path ${resolved} escapes the storage directory ${base}")
        }
        return resolved.toString()
    }
}
