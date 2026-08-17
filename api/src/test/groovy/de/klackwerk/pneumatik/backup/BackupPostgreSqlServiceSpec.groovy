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

class BackupPostgreSqlServiceSpec extends Specification implements DataTest {

    BackupPostgreSqlService service
    Host host
    Database database
    Backup backup

    Class[] getDomainClassesToMock() {
        [Backup, Database, Host] as Class[]
    }

    void setup() {
        host = new Host(hostname: 'pg.example.com', port: 5432, useSSL: false)
        database = new Database(databaseName: 'shop', host: host, user: 'postgres', password: 'ENCRYPTED',
                storageProvider: StorageProvider.DIRECT, trigger: Trigger.TRIGGER_DAILY, databaseType: DatabaseType.POSTGRESQL)
        backup = new Backup(database: database, success: false, storageProvider: StorageProvider.DIRECT)

        service = new BackupPostgreSqlService()
        service.credentialService = Stub(CredentialService) {
            decryptString('ENCRYPTED') >> 'pg-secret'
        }
        service.grailsApplication = Stub(GrailsApplication) {
            getConfig() >> Stub(Config) {
                getProperty('pneumatik.storage.temp.path') >> '/tmp/pneumatik/temp'
            }
        }
        service.backupService = new BackupService()
    }

    void 'connection flags use pg_dump style options'() {
        when:
        List<String> flags = service.createBasicConnectionFlags(backup)

        then:
        flags == ['-U', 'postgres', '-h', 'pg.example.com', '-p', '5432']
        !flags.contains('--password')
    }

    void 'the backup command contains no secret at all'() {
        when:
        List<String> command = service.createBackupCommand(backup)

        then: 'PGPASSWORD is injected via ProcessBuilder / stdin, never the command line'
        command.first() == 'pg_dump'
        command.last() == 'shop'
        !command.any { it.contains('pg-secret') }
        !command.any { it.contains('PGPASSWORD') }
        !command.contains('>')
        backup.fullPath ==~ /\/tmp\/pneumatik\/temp\/shop_\d{8}_\d{6}\.sql/
    }

    void 'SSL is requested through PGSSLMODE — pg_dump has no --ssl option'() {
        given:
        host.useSSL = true

        expect: 'passing --ssl made pg_dump abort before it connected'
        !service.createBasicConnectionFlags(backup).contains('--ssl')
        !service.createBackupCommand(backup).contains('--ssl')
        service.createEngineEnvironment(backup) == [PGSSLMODE: 'require']
    }

    void 'no SSL setting is applied when the host does not require it'() {
        expect:
        service.createEngineEnvironment(backup) == [:]
    }

    void 'a hostile database name lands in one argv element and a sanitised filename'() {
        given:
        database.databaseName = 'shop$(id)'

        when:
        List<String> command = service.createBackupCommand(backup)

        then:
        command.last() == 'shop$(id)'
        backup.filename ==~ /shop__id__\d{8}_\d{6}\.sql/
    }
}
