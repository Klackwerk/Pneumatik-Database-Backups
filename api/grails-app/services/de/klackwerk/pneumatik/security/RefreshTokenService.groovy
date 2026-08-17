package de.klackwerk.pneumatik.security

import grails.gorm.transactions.Transactional
import org.apache.commons.lang3.RandomStringUtils

import java.security.SecureRandom

/**
 * Issues, rotates and revokes refresh tokens.
 *
 * Access tokens are short-lived so a leaked one expires on its own; that
 * would log people out mid-session, which is what these are for. They are
 * opaque random strings (never JWTs — nothing about them should be
 * self-asserting), stored hashed, and rotated on every use.
 */
@Transactional
class RefreshTokenService {

    static final int TOKEN_LENGTH = 64
    /** how long a refresh token stays valid; renewed on every rotation */
    static final int DEFAULT_LIFETIME_DAYS = 1

    int lifetimeDays = DEFAULT_LIFETIME_DAYS

    /**
     * Issues a token for a username
     *
     * @return the plaintext token, or null when the user is unknown
     */
    String issueForUsername(String username) {
        User user = User.findByUsername(username)
        return user ? issue(user) : null
    }

    /**
     * @return the plaintext token
     */
    String issue(User user) {
        purgeExpired()
        String plainToken = generateToken()
        new RefreshToken(
                token: ApiKeyService.hashKey(plainToken),
                user: user,
                expiresAt: new Date(System.currentTimeMillis() + lifetimeDays * 24L * 60 * 60 * 1000),
        ).save(failOnError: true)
        return plainToken
    }

    /**
     * Exchanges a refresh token for a new one.
     * The presented token is revoked whether or not it was still usable.
     *
     * @return map with user and token, or null when the token is unusable
     */
    Map rotate(String plainToken) {
        RefreshToken existing = find(plainToken)
        if (!existing) {
            return null
        }
        Date now = new Date()
        boolean usable = existing.isUsable(now)
        existing.revokedAt = now
        existing.lastUsedAt = now
        existing.save(failOnError: true)

        if (!usable) {
            log.warn "REFRESHTOKENSERVICE - Rejected an expired or already-used refresh token of user ${existing.user.username}"
            return null
        }
        return [user: existing.user, token: issue(existing.user)]
    }

    /** @return true when a live token was revoked */
    boolean revoke(String plainToken) {
        RefreshToken existing = find(plainToken)
        if (!existing || !existing.isUsable()) {
            return false
        }
        existing.revokedAt = new Date()
        existing.save(failOnError: true)
        return true
    }

    /** Ends every session of a user — for disabling an account or a password change. */
    int revokeAllForUser(User user) {
        List<RefreshToken> live = RefreshToken.createCriteria().list {
            eq 'user', user
            isNull 'revokedAt'
        } as List<RefreshToken>

        Date now = new Date()
        live.each { RefreshToken token ->
            token.revokedAt = now
            token.save(failOnError: true)
        }
        return live.size()
    }

    /**
     * Drops tokens that can no longer be used.
     */
    int purgeExpired() {
        Date cutoff = new Date(System.currentTimeMillis() - lifetimeDays * 24L * 60 * 60 * 1000)
        List<RefreshToken> dead = RefreshToken.createCriteria().list {
            lt 'expiresAt', cutoff
        } as List<RefreshToken>

        dead.each { it.delete() }
        return dead.size()
    }

    private static RefreshToken find(String plainToken) {
        if (!plainToken) {
            return null
        }
        return RefreshToken.findByToken(ApiKeyService.hashKey(plainToken))
    }

    private static String generateToken() {
        return RandomStringUtils.random(TOKEN_LENGTH, 0, 0, true, true, null, new SecureRandom())
    }
}
