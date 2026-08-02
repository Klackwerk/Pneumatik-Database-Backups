package de.klackwerk.pneumatik.security

import grails.testing.gorm.DataTest
import spock.lang.Specification

class RefreshTokenServiceSpec extends Specification implements DataTest {

    RefreshTokenService service
    User user
    User other

    Class[] getDomainClassesToMock() {
        [User, RefreshToken] as Class[]
    }

    void setup() {
        user = new User(username: 'admin', password: 'secret', email: 'admin@example.org')
                .save(failOnError: true, validate: false)
        other = new User(username: 'someone', password: 'secret', email: 'someone@example.org')
                .save(failOnError: true, validate: false)
        service = new RefreshTokenService()
    }

    void 'an issued token is opaque and stored only as a hash'() {
        when:
        String plain = service.issue(user)

        then:
        plain.length() == RefreshTokenService.TOKEN_LENGTH
        plain ==~ /[A-Za-z0-9]+/

        and: 'a database dump hands out no sessions'
        RefreshToken stored = RefreshToken.first()
        stored.token == ApiKeyService.hashKey(plain)
        stored.token != plain
        stored.user == user
        stored.expiresAt > new Date()
    }

    void 'rotating returns a new token and retires the presented one'() {
        given:
        String first = service.issue(user)

        when:
        Map rotated = service.rotate(first)

        then:
        rotated.user == user
        rotated.token != first

        and: 'the presented token is spent'
        service.rotate(first) == null

        and: 'the new one works'
        service.rotate(rotated.token as String) != null
    }

    void 'a replayed token is refused even before it expires'() {
        given: 'a token used once — a second appearance is a stale retry or a steal'
        String plain = service.issue(user)
        service.rotate(plain)

        expect:
        service.rotate(plain) == null
    }

    void 'an expired token cannot be rotated'() {
        given:
        String plain = service.issue(user)
        RefreshToken stored = RefreshToken.findByToken(ApiKeyService.hashKey(plain))
        stored.expiresAt = new Date(System.currentTimeMillis() - 1000)
        stored.save(failOnError: true, flush: true)

        expect:
        service.rotate(plain) == null
    }

    void 'an unknown token is refused'() {
        expect:
        service.rotate('not-a-real-token') == null
        service.rotate(null) == null
        service.rotate('') == null
    }

    void 'revoking stops a token being rotated'() {
        given:
        String plain = service.issue(user)

        expect:
        service.revoke(plain)
        service.rotate(plain) == null

        and: 'revoking twice reports nothing was live'
        !service.revoke(plain)
    }

    void 'revoking a user ends all of their sessions and nobody else s'() {
        given:
        String first = service.issue(user)
        String second = service.issue(user)
        String foreign = service.issue(other)

        when:
        int revoked = service.revokeAllForUser(user)

        then:
        revoked == 2
        service.rotate(first) == null
        service.rotate(second) == null

        and:
        service.rotate(foreign) != null
    }

    void 'purging drops tokens that are long past their expiry'() {
        given:
        String live = service.issue(user)
        RefreshToken stale = RefreshToken.findByToken(ApiKeyService.hashKey(service.issue(user)))
        stale.expiresAt = new Date(System.currentTimeMillis() - 400L * 24 * 60 * 60 * 1000)
        stale.save(failOnError: true, flush: true)

        when:
        int purged = service.purgeExpired()

        then:
        purged == 1
        service.rotate(live) != null
    }

    void 'issuing for a username finds the user, and reports an unknown one'() {
        expect:
        service.issueForUsername('admin') != null
        service.issueForUsername('nobody') == null
    }
}
