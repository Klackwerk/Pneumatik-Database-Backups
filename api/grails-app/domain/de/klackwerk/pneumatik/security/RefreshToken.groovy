package de.klackwerk.pneumatik.security

/**
 * A long-lived credential that buys a fresh access token without asking for
 * the password again.
 *
 * Stored as a SHA-256 hash like {@link ApiKey}, so a database dump does not
 * hand out sessions. Tokens rotate on every use: turns a stolen-and-replayed token into
 * a detectable, self-limiting event rather than a permanent key.
 *
 */
class RefreshToken {

    String id
    String token
    User user

    Date createdAt = new Date()
    Date expiresAt
    Date lastUsedAt
    Date revokedAt

    static constraints = {
        token nullable: false, blank: false, unique: true
        user nullable: false
        expiresAt nullable: false
        lastUsedAt nullable: true
        revokedAt nullable: true
    }

    static mapping = {
        table 'refresh_token'
        id generator: 'uuid2'
    }

    boolean isUsable(Date now = new Date()) {
        return revokedAt == null && expiresAt > now
    }
}
