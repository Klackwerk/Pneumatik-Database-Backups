package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.credentials.CredentialService
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.DatabaseType
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.storage.StorageProvider
import grails.config.Config
import grails.core.GrailsApplication
import grails.testing.gorm.DataTest
import spock.lang.Specification

class BackupMySqlServiceSpec extends Specification implements DataTest {

    BackupMySqlService service
    Host host
    Database database
    Backup backup

    Class[] getDomainClassesToMock() {
        [Backup, Database, Host] as Class[]
    }

    void setup() {
        host = new Host(hostname: 'db.example.com', port: 3307, useSSL: false)
        database = new Database(databaseName: 'shop', host: host, user: 'root', password: 'ENCRYPTED',
                storageProvider: StorageProvider.DIRECT, trigger: Trigger.TRIGGER_DAILY, databaseType: DatabaseType.MYSQL)
        backup = new Backup(database: database, success: false, storageProvider: StorageProvider.DIRECT)

        service = new BackupMySqlService()
        service.credentialService = Stub(CredentialService) {
            decryptString('ENCRYPTED') >> 's3cret'
        }
        service.grailsApplication = Stub(GrailsApplication) {
            getConfig() >> Stub(Config) {
                getProperty('pneumatik.storage.temp.path') >> '/tmp/pneumatik/temp'
            }
        }
        service.backupService = new BackupService()
    }

    void 'connection flags carry user, host and port — but never the password'() {
        when:
        List<String> flags = service.createBasicConnectionFlags(backup)

        then: 'the password travels via MYSQL_PWD, not the command line'
        flags == ['-u', 'root', '-h', 'db.example.com', '-P', '3307']
        !flags.any { it.contains('s3cret') }
    }

    void 'the user flag is omitted entirely when the database has no user'() {
        given:
        database.user = null

        expect: 'no literal "null" argument reaches mysqldump'
        service.createBasicConnectionFlags(backup) == ['-h', 'db.example.com', '-P', '3307']
    }

    void 'connection flags add --ssl when the host requires it'() {
        given:
        host.useSSL = true

        expect:
        service.createBasicConnectionFlags(backup).contains('--ssl')
    }

    void 'backup parameters always include hex-blob, routines and triggers'() {
        given: 'version detection unavailable (falls back to defaults)'
        BackupMySqlService spied = Spy(service)
        spied.determineRemoteVersion(_, _) >> null

        when:
        List<String> params = spied.createBackupParameters(backup, ['flags'])

        then:
        params.containsAll(['--hex-blob', '--routines', '--triggers'])
        !params.contains('--column-statistics=0')
    }

    void 'MySQL 8 gets column-statistics and gtid-purged flags, MariaDB does not'() {
        given:
        BackupMySqlService spied = Spy(service)
        spied.determineRemoteVersion(_, _) >> remoteVersion

        expect:
        spied.createBackupParameters(backup, ['flags'])
                .containsAll(['--column-statistics=0', '--set-gtid-purged=OFF']) == expected

        where:
        remoteVersion | expected
        'MySQL 8.x'   | true
        'MariaDB'     | false
    }

    void 'backup command dumps to the temp path with a timestamped filename'() {
        given:
        BackupMySqlService spied = Spy(service)
        spied.determineRemoteVersion(_, _) >> 'MariaDB'

        when:
        List<String> command = spied.createBackupCommand(backup)

        then: 'the dump goes to stdout — the runner streams it to the temp file'
        command.first() == 'mysqldump'
        command.last() == 'shop'
        !command.contains('>')
        backup.filename ==~ /shop_\d{8}_\d{6}\.sql/
        backup.fullPath.startsWith('/tmp/pneumatik/temp/')
        backup.fullPath.endsWith(backup.filename)
    }

    void 'a hostile database name lands in one argv element and a sanitised filename'() {
        given:
        database.databaseName = 'shop; rm -rf /'
        BackupMySqlService spied = Spy(service)
        spied.determineRemoteVersion(_, _) >> 'MariaDB'

        when:
        List<String> command = spied.createBackupCommand(backup)

        then: 'the name is passed verbatim as one argument, never split or expanded'
        command.last() == 'shop; rm -rf /'

        and: 'but the filename it derives is stripped of everything unsafe'
        backup.filename ==~ /shop__rm_-rf___\d{8}_\d{6}\.sql/
        backup.fullPath.startsWith('/tmp/pneumatik/temp/')
    }

    void 'backup command stays bare even for SSH hosts — wrapping happens in BackupService'() {
        given:
        host.sshHostname = 'jump.example.com'
        host.sshUser = 'backup'
        host.sshPort = 22
        host.sshKey = 'ENCRYPTED-KEY'
        BackupMySqlService spied = Spy(service)
        spied.determineRemoteVersion(_, _) >> 'MariaDB'

        when:
        List<String> command = spied.createBackupCommand(backup)

        then: 'no ssh, no identity file, no secret — BackupService.buildExecution adds transport'
        command.first() == 'mysqldump'
        !command.any { it.contains('ssh') }
        !command.any { it.contains('s3cret') }
    }
}
