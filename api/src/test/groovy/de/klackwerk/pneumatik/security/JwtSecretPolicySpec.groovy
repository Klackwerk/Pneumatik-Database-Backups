package de.klackwerk.pneumatik.security

import spock.lang.Specification

class JwtSecretPolicySpec extends Specification {

    private static final String STRONG = 'K7yQ2mLp9XvR4tZa8NwE6bHc3JdF5gSu'

    void 'development falls back to the built-in secret'() {
        expect:
        JwtSecretPolicy.resolve(null, false) == JwtSecretPolicy.DEVELOPMENT_SECRET
        JwtSecretPolicy.resolve('', false) == JwtSecretPolicy.DEVELOPMENT_SECRET
    }

    void 'development still prefers a configured secret'() {
        expect:
        JwtSecretPolicy.resolve(STRONG, false) == STRONG
    }

    void 'production accepts a secret of sufficient length'() {
        expect:
        JwtSecretPolicy.resolve(STRONG, true) == STRONG
    }

    void 'production refuses to start without a secret'() {
        when:
        JwtSecretPolicy.resolve(configured, true)

        then:
        IllegalStateException e = thrown()
        e.message.contains('must be set in production')

        where:
        configured << [null, '']
    }

    void 'production refuses the development secret — it is public in the repository'() {
        when:
        JwtSecretPolicy.resolve(JwtSecretPolicy.DEVELOPMENT_SECRET, true)

        then:
        IllegalStateException e = thrown()
        e.message.contains('must be set in production')
    }

    void 'production refuses a secret shorter than the hash it signs with'() {
        when:
        JwtSecretPolicy.resolve('a' * (JwtSecretPolicy.MINIMUM_LENGTH - 1), true)

        then:
        IllegalStateException e = thrown()
        e.message.contains('at least 32 characters')
    }

    void 'the failure tells the operator how to generate one'() {
        when:
        JwtSecretPolicy.resolve(null, true)

        then:
        IllegalStateException e = thrown()
        e.message.contains('openssl rand -base64 48')
    }
}
