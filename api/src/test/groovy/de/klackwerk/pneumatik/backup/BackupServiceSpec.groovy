package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.credentials.CredentialService
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.DatabaseType
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.notification.SendMailService
import de.klackwerk.pneumatik.storage.StorageProvider
import de.klackwerk.pneumatik.storage.StorageService
import grails.config.Config
import grails.core.GrailsApplication
import grails.testing.gorm.DataTest
import spock.lang.Specification

class BackupServiceSpec extends Specification implements DataTest {

    BackupService service
    Host host
    Database database
    Backup backup

    Class[] getDomainClassesToMock() {
        [Backup, Database, Host] as Class[]
    }

    void setup() {
        host = new Host(hostname: 'db.example.com', port: 3306)
        database = new Database(databaseName: 'shop', host: host, user: 'root', password: 'ENCRYPTED',
                storageProvider: StorageProvider.DIRECT, trigger: Trigger.TRIGGER_DAILY, databaseType: DatabaseType.MYSQL)
        backup = new Backup(database: database, success: false, storageProvider: StorageProvider.DIRECT)

        service = new BackupService()
        service.credentialService = Stub(CredentialService) {
            decryptString('ENCRYPTED-KEY') >> 'PRIVATE-KEY-MATERIAL'
        }
        service.grailsApplication = Stub(GrailsApplication) {
            getConfig() >> Stub(Config) {
                getProperty('pneumatik.backup.timeout-minutes', Integer, _) >> 30
                getProperty('pneumatik.storage.temp.path') >> '/tmp/pneumatik/temp'
            }
        }
    }

    private void makeSshHost() {
        host.sshHostname = 'jump.example.com'
        host.sshUser = 'backup'
        host.sshPort = 22
        host.sshKey = 'ENCRYPTED-KEY'
    }

    void 'local execution runs the argv without a shell and passes the password by environment'() {
        when:
        Map execution = service.buildExecution(backup, ['mysqldump', '-u', 'root', 'shop'], 'MYSQL_PWD', 's3cret')

        then: 'no /bin/bash -c: nothing parses the argv, so nothing can inject into it'
        execution.command == ['mysqldump', '-u', 'root', 'shop']
        execution.environment == [MYSQL_PWD: 's3cret']
        execution.stdin == null
        !(execution.command as List).any { (it as String).contains('s3cret') }
    }

    void 'local execution without a password sets no environment variable'() {
        when:
        Map execution = service.buildExecution(backup, ['mysqldump', '-u', 'root', 'shop'], 'MYSQL_PWD', null)

        then:
        execution.environment == [:]
        execution.stdin == null
    }

    void 'local execution merges extra engine settings into the environment'() {
        when:
        Map execution = service.buildExecution(backup, ['pg_dump', 'shop'], 'PGPASSWORD', 'pg-secret',
                [PGSSLMODE: 'require'])

        then:
        execution.environment == [PGSSLMODE: 'require', PGPASSWORD: 'pg-secret']
    }

    void 'shell metacharacters in inventory values never reach a local shell'() {
        given: 'a database name that would be command substitution if a shell saw it'
        String hostile = 'shop$(id > /tmp/pwned)'

        when:
        Map execution = service.buildExecution(backup, ['mysqldump', hostile], 'MYSQL_PWD', null)

        then: 'it stays one opaque argument — mysqldump reports an unknown database'
        execution.command == ['mysqldump', hostile]
        !(execution.command as List).contains('/bin/bash')
    }

    void 'ssh execution feeds password and key through stdin — no secret in any argv'() {
        given:
        makeSshHost()

        when:
        Map execution = service.buildExecution(backup, ['mysqldump', '-u', 'root', 'shop'], 'MYSQL_PWD', 's3cret')
        String script = (execution.command as List)[3]
        String remote = 'IFS= read -r MYSQL_PWD; export MYSQL_PWD; ' +
                service.buildRemoteCommand(['mysqldump', '-u', 'root', 'shop'], [:])

        then: 'ephemeral agent, password read into a shell variable, remote reads it into MYSQL_PWD'
        (execution.command as List)[0..2] == ['ssh-agent', '/bin/bash', '-c']
        script.contains('IFS= read -r PNEUMATIK_DB_PASSWORD')
        script.contains('ssh-add -q -')
        script.endsWith(ShellCommand.quote(remote))
        !script.contains('s3cret')
        !script.contains('PRIVATE-KEY-MATERIAL')
        !script.contains(' -i ')

        and: 'ssh must consume stdin for the password, so no -n'
        !script.contains(' -n ')

        and: 'stdin carries exactly one password line followed by the key'
        execution.stdin == 's3cret\nPRIVATE-KEY-MATERIAL\n'
        execution.environment == [:]
    }

    void 'ssh execution without a password uses -n and sends only the key'() {
        given:
        makeSshHost()

        when:
        Map execution = service.buildExecution(backup, ['mysqldump', '-u', 'root', 'shop'], 'MYSQL_PWD', null)
        String script = (execution.command as List)[3]

        then:
        script.contains('ssh-add -q -')
        script.contains(' -n ')
        script.endsWith(ShellCommand.quote("'mysqldump' '-u' 'root' 'shop'"))
        execution.stdin == 'PRIVATE-KEY-MATERIAL\n'
        execution.environment == [:]
    }

    void 'the remote command quotes every inventory value it interpolates'() {
        expect: 'each argument survives one round of shell parsing unchanged'
        service.buildRemoteCommand(['mysqldump', 'shop$(id)', 'a"b'], [:]) ==
                "'mysqldump' 'shop\$(id)' 'a\"b'"
    }

    void 'the ssh destination and port are quoted, not interpolated raw'() {
        given:
        makeSshHost()
        host.sshUser = 'back;up'

        expect:
        service.generateSSHConnectionString(backup).contains("'back;up@jump.example.com'")
        service.generateSSHConnectionString(backup).contains("-p '22'")
    }

    void 'ssh execution prefixes extra engine settings on the remote side'() {
        given:
        makeSshHost()

        expect:
        service.buildRemoteCommand(['pg_dump', 'shop'], [PGSSLMODE: 'require']) ==
                "PGSSLMODE='require' 'pg_dump' 'shop'"
    }

    void 'dump filenames keep only characters that are safe on a filesystem'() {
        expect:
        BackupService.createDumpFilename(databaseName, new Date()).startsWith(stem + '_')

        where:
        databaseName       | stem
        'shop'             | 'shop'
        'my-shop_2.prod'   | 'my-shop_2.prod'
        '../../etc/passwd' | '.._.._etc_passwd'
        'shop; rm -rf /'   | 'shop__rm_-rf__'
        'shop$(id)'        | 'shop__id_'
        '..'               | 'database'
        ''                 | 'database'
        null               | 'database'
    }

    void 'dump filenames are capped so a long database name cannot overflow the path'() {
        expect:
        BackupService.createDumpFilename('x' * 500, new Date())
                .startsWith('x' * BackupService.MAX_FILENAME_STEM + '_')
    }

    void 'storage paths that escape their directory are refused'() {
        when:
        BackupService.resolveStoragePath('/tmp/pneumatik/temp', '../../../etc/passwd')

        then:
        thrown(IllegalArgumentException)
    }

    void 'storage paths inside the directory resolve normally'() {
        expect:
        BackupService.resolveStoragePath('/tmp/pneumatik/temp', 'shop_20260801_120000.sql') ==
                '/tmp/pneumatik/temp/shop_20260801_120000.sql'
    }

    void 'a dump that exits cleanly but writes nothing is a failure, not a backup'() {
        given:
        File dump = File.createTempFile('pneumatik-empty', '.sql')
        backup.fullPath = dump.absolutePath
        StorageService storage = Mock(StorageService)
        SendMailService mail = Mock(SendMailService)
        service.storageService = storage
        service.sendMailService = mail

        when: 'the command succeeds without producing output'
        Backup result = service.runDumpCommand(backup, ['true'], 'MYSQL_PWD', null)

        then: 'nothing is stored and the operator is told why'
        result.exitCode == 0
        result.state == BackupState.FAILED
        !result.success
        result.output.contains('wrote no data')
        0 * storage.storeBackup(_)
        1 * mail.notifyOnFailedBackup(_)

        cleanup:
        dump.delete()
    }

    void 'a dump that produces data is stored and recorded as successful'() {
        given:
        File dump = File.createTempFile('pneumatik-dump', '.sql')
        backup.fullPath = dump.absolutePath
        StorageService storage = Mock(StorageService)
        service.storageService = storage
        service.sendMailService = Mock(SendMailService)

        when:
        Backup result = service.runDumpCommand(backup, ['echo', '-- dump'], 'MYSQL_PWD', null)

        then:
        1 * storage.storeBackup(_) >> true
        result.state == BackupState.FINISHED
        result.success
        result.rawSizeBytes > 0

        cleanup:
        dump.delete()
    }

    void 'a dump command that fails is never stored'() {
        given:
        File dump = File.createTempFile('pneumatik-failed', '.sql')
        backup.fullPath = dump.absolutePath
        StorageService storage = Mock(StorageService)
        SendMailService mail = Mock(SendMailService)
        service.storageService = storage
        service.sendMailService = mail

        when:
        Backup result = service.runDumpCommand(backup, ['false'], 'MYSQL_PWD', null)

        then:
        result.exitCode != 0
        result.state == BackupState.FAILED
        0 * storage.storeBackup(_)
        1 * mail.notifyOnFailedBackup(_)

        cleanup:
        dump.delete()
    }
}
