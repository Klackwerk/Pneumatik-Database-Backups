package de.klackwerk.pneumatik

import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.BackupCatchUpService
import de.klackwerk.pneumatik.backup.BackupService
import de.klackwerk.pneumatik.backup.BackupState
import de.klackwerk.pneumatik.backup.Trigger
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.migration.DataMigrationService
import de.klackwerk.pneumatik.security.Role
import de.klackwerk.pneumatik.security.User
import de.klackwerk.pneumatik.security.UserRole
import de.klackwerk.pneumatik.storage.StorageProvider
import de.klackwerk.pneumatik.storage.StorageService
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import grails.util.Environment

import java.security.SecureRandom

class BootStrap {

    GrailsApplication grailsApplication
    DataMigrationService dataMigrationService
    StorageService storageService
    BackupService backupService
    BackupCatchUpService backupCatchUpService

    def init = { servletContext ->
        // No backup is running yet: everything in temp is debris from a
        // previous run (partial dumps, and — before ssh-agent auth —
        // plaintext SSH keys a killed container left behind).
        storageService.cleanTempDirectory()
        // ...which also means any backup still marked RUNNING was abandoned
        backupService.failStaleRunningBackups()
        addDefaultUsers()
        addDevFixtures()
        dataMigrationService.migrate()
        backupCatchUpService.enqueueMissedBackups()
    }

    /** alphabet for the generated password — no look-alike characters */
    private static final String PASSWORD_ALPHABET = 'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
    private static final int GENERATED_PASSWORD_LENGTH = 24

    /**
     * Creates the initial admin account on an empty database.
     *
     * The password comes from PNEUMATIK_ADMIN_PASSWORD, or is generated and
     * printed once to the application log.
     *
     */
    @Transactional
    void addDefaultUsers() {
        if (User.count() == 0) {
            String configured = grailsApplication.config.getProperty('pneumatik.security.admin-password')
            String password = configured ?: generatePassword()

            User u = new User(username: 'admin', password: password, email: 'admin@example.org').save()
            Role r = Role.findByAuthority('ROLE_ADMIN') ?: new Role(authority: 'ROLE_ADMIN').save()

            UserRole.create u, r

            UserRole.withSession {
                it.flush()
                it.clear()
            }

            if (configured) {
                log.info 'BOOTSTRAP - Created admin account with the password from PNEUMATIK_ADMIN_PASSWORD'
            } else {
                log.warn """
                    |
                    |========================================================================
                    | Pneumatik created the initial admin account:
                    |
                    |     username: admin
                    |     password: ${password}
                    |
                    | This password is shown once. Sign in and change it, or set
                    | PNEUMATIK_ADMIN_PASSWORD before the first start.
                    |========================================================================
                    |""".stripMargin()
            }
        }
    }

    private static String generatePassword() {
        Random random = new SecureRandom()
        return (1..GENERATED_PASSWORD_LENGTH).collect {
            PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length())]
        }.join()
    }

    /**
     * Sample host + database so a fresh dev environment has something to
     * show. Development only.
     *
     */
    @Transactional
    void addDevFixtures() {
        if (Environment.current == Environment.DEVELOPMENT && Host.count() == 0) {
            Host h = new Host(friendlyName: 'Pneumatik', hostname: 'localhost', port: 3306, useSSL: false).save()
            Database primary = new Database(friendlyName: 'Pneumatik Database', databaseName: 'pneumatik', host: h,
                    storageProvider: StorageProvider.DIRECT, trigger: Trigger.TRIGGER_MANUAL).save()
            Database shop = new Database(friendlyName: 'Shop', databaseName: 'shop', host: h,
                    storageProvider: StorageProvider.DIRECT, trigger: Trigger.TRIGGER_DAILY).save()
            Database analytics = new Database(friendlyName: 'Analytics', databaseName: 'analytics', host: h,
                    storageProvider: StorageProvider.DIRECT, trigger: Trigger.TRIGGER_12HOURLY).save()

            // two weeks of pseudo-random backup history so the dashboard has data
            Random random = new Random(42)
            [(primary): 180d, (shop): 65d, (analytics): 940d].each { Database db, double baseMb ->
                14.times { int daysAgo ->
                    int perDay = 1 + random.nextInt(3)
                    perDay.times { int run ->
                        boolean failed = random.nextInt(10) == 0
                        Date created = new Date(System.currentTimeMillis()
                                - daysAgo * 86_400_000L - (run * 6 + 2) * 3_600_000L)
                        double sizeMb = baseMb * (0.9 + random.nextDouble() * 0.2)
                        long rawBytes = (long) (sizeMb * 1024 * 1024)
                        long durationMs = 4_000 + random.nextInt(180_000)
                        new Backup(database: db, createdAt: created, executedAt: created,
                                finishedAt: new Date(created.time + durationMs),
                                filename: failed ? null : "${db.databaseName}_${daysAgo}_${run}.zip",
                                size: failed ? null : "${(sizeMb as Double).round(3)} MB",
                                rawSizeBytes: failed ? null : rawBytes,
                                archivedSizeBytes: failed ? null : (long) (rawBytes * 0.17),
                                output: failed
                                        ? "mysqldump: Got error: 2003: \"Can't connect to MySQL server on '${h.hostname}' (110)\" when trying to connect"
                                        : (random.nextInt(3) == 0 ? 'mysqldump: [Warning] Using a password on the command line interface can be insecure.' : null),
                                state: failed ? BackupState.FAILED : BackupState.FINISHED,
                                exitCode: failed ? 2 : 0, success: !failed,
                                storageProvider: StorageProvider.DIRECT).save()
                    }
                }
            }
        }
    }

    def destroy = {
    }
}
