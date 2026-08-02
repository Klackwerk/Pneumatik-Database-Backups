package de.klackwerk.pneumatik.inventory

import de.klackwerk.pneumatik.credentials.CredentialService
import grails.testing.gorm.DataTest
import spock.lang.Specification

class HostServiceSpec extends Specification implements DataTest {

    HostService service

    Class[] getDomainClassesToMock() {
        [Host] as Class[]
    }

    void setup() {
        service = new HostService()
        service.credentialService = Stub(CredentialService) {
            encryptString(_) >> { String s -> "v1:ENC(${s})".toString() }
        }
    }

    void 'addHost encrypts the ssh key and defaults the port to 3306'() {
        when:
        Host host = service.addHost(new HostCommand(hostname: 'db.example.com', sshKey: 'PRIVATE'))

        then:
        host.sshKey == 'v1:ENC(PRIVATE)'
        host.port == 3306
        !host.useSSL
    }

    void 'editing without an ssh key keeps the stored one'() {
        given:
        Host host = service.addHost(new HostCommand(hostname: 'db.example.com', sshKey: 'PRIVATE'))

        when:
        service.editHost(new HostCommand(hostname: 'db.example.com', port: 3307), host)

        then:
        host.sshKey == 'v1:ENC(PRIVATE)'
        host.port == 3307
    }
}
