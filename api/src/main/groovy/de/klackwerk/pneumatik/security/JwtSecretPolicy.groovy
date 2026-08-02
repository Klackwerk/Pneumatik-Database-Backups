package de.klackwerk.pneumatik.security

import groovy.transform.CompileStatic

/**
 * Decides which secret signs the API's JWTs.
 *
 * The secret is what separates an admin from an anonymous caller: anyone who
 * knows it can mint a token carrying any role. A development fallback is
 * convenient, but if it also applied in production it would hand admin
 * access to everyone holding a copy of this repository — so production must
 * supply its own and startup fails when it does not.
 */
@CompileStatic
class JwtSecretPolicy {

    static final String DEVELOPMENT_SECRET = 'dev-only-jwt-secret-change-me-0123456789'
    /** HS256 keys shorter than the hash are pointless; 32 bytes is the floor */
    static final int MINIMUM_LENGTH = 32
    private static final String ADVICE = 'Generate one with: openssl rand -base64 48'

    static String resolve(String configured, boolean production) {
        if (!production) {
            return configured ?: DEVELOPMENT_SECRET
        }
        if (!configured || configured == DEVELOPMENT_SECRET) {
            throw new IllegalStateException("PNEUMATIK_JWT_SECRET must be set in production. ${ADVICE}")
        }
        if (configured.length() < MINIMUM_LENGTH) {
            throw new IllegalStateException(
                    "PNEUMATIK_JWT_SECRET must be at least ${MINIMUM_LENGTH} characters. ${ADVICE}")
        }
        return configured
    }
}
