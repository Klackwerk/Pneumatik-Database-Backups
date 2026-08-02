package de.klackwerk.pneumatik.inventory

import de.klackwerk.pneumatik.backup.Trigger
import de.klackwerk.pneumatik.credentials.CredentialService
import de.klackwerk.pneumatik.storage.StorageProvider
import grails.testing.gorm.DataTest
import spock.lang.Specification

class DatabaseServiceSpec extends Specification implements DataTest {

    DatabaseService service
    Host host

    Class[] getDomainClassesToMock() {
        [Database, Host] as Class[]
    }

    void setup() {
        host = new Host(hostname: 'localhost', port: 3306, useSSL: false).save(failOnError: true, validate: false)
        service = new DatabaseService()
        service.credentialService = Stub(CredentialService) {
            encryptString(_) >> { String s -> s == null ? null : "v1:ENC(${s})".toString() }
        }
    }

    private DatabaseCommand command(Map overrides = [:]) {
        new DatabaseCommand([databaseName: 'shop', hostId: host.id, user: 'root', password: 'secret',
                             storageProvider: StorageProvider.DIRECT, trigger: Trigger.TRIGGER_DAILY] + overrides)
    }

    void 'addDatabase encrypts the password'() {
        when:
        Database database = service.addDatabase(command())

        then:
        database.password == 'v1:ENC(secret)'
        database.host == host
    }

    void 'editing without a password keeps the stored one (legacy behaviour)'() {
        given:
        Database database = service.addDatabase(command())

        when:
        service.editDatabase(command(password: null, user: 'other'), database)

        then:
        database.password == 'v1:ENC(secret)'
        database.user == 'other'
    }

    void 'editing with a password replaces it'() {
        given:
        Database database = service.addDatabase(command())

        when:
        service.editDatabase(command(password: 'changed'), database)

        then:
        database.password == 'v1:ENC(changed)'
    }
}
