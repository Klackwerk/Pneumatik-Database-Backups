package de.klackwerk.pneumatik.security

import grails.testing.gorm.DataTest
import spock.lang.Specification

class ApiKeyServiceSpec extends Specification implements DataTest {

    ApiKeyService service
    User user

    Class[] getDomainClassesToMock() {
        [ApiKey, User] as Class[]
    }

    void setup() {
        user = new User(username: 'admin', password: 'x', email: 'a@b.c').save(failOnError: true, validate: false)
        service = new ApiKeyService()
        service.springSecurityService = [getCurrentUser: { user }]
    }

    void 'createApiKey stores a sha256 hash and a hint, returns the plaintext once'() {
        when:
        Map result = service.createApiKey(new ApiKeyCommand(comment: 'ci'))
        ApiKey apiKey = result.apiKey as ApiKey
        String plainKey = result.plainKey as String

        then:
        plainKey.length() == 64
        apiKey.key == ApiKeyService.hashKey(plainKey)
        apiKey.key.startsWith('sha256:')
        apiKey.keyHint == plainKey.take(8)
        apiKey.comment == 'ci'
        apiKey.createdBy == user
    }

    void 'validateApiKey accepts the plaintext key and updates lastConnectedAt'() {
        given:
        Map result = service.createApiKey(new ApiKeyCommand())

        when:
        Boolean valid = service.validateApiKey(result.plainKey as String)

        then:
        valid
        (result.apiKey as ApiKey).lastConnectedAt != null
    }

    void 'validateApiKey rejects unknown, empty and expired keys'() {
        given:
        Map result = service.createApiKey(new ApiKeyCommand(validUntil: new Date(System.currentTimeMillis() - 1000)))

        expect:
        !service.validateApiKey('unknown-key')
        !service.validateApiKey('')
        !service.validateApiKey(result.plainKey as String)
    }

    void 'only the creator can delete a key'() {
        given:
        Map result = service.createApiKey(new ApiKeyCommand())
        String id = (result.apiKey as ApiKey).id
        User other = new User(username: 'other', password: 'x', email: 'o@b.c').save(failOnError: true, validate: false)

        when: 'another user tries'
        service.springSecurityService = [getCurrentUser: { other }]
        Boolean deniedResult = service.deleteApiKey(id)

        then:
        !deniedResult
        ApiKey.count() == 1

        when: 'the creator tries'
        service.springSecurityService = [getCurrentUser: { user }]
        Boolean allowed = service.deleteApiKey(id)

        then:
        allowed
        ApiKey.count() == 0
    }

    void 'hashKey is deterministic and prefixed'() {
        expect:
        ApiKeyService.hashKey('abc') == ApiKeyService.hashKey('abc')
        ApiKeyService.hashKey('abc') != ApiKeyService.hashKey('abd')
        ApiKeyService.hashKey('abc').startsWith('sha256:')
    }
}
