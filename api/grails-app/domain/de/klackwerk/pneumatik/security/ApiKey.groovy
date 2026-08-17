package de.klackwerk.pneumatik.security

import de.klackwerk.pneumatik.inventory.Database

/**
 * API access key for machine-to-machine calls (X-API-Key header).
 *
 * The key itself is stored as a SHA-256 hash ("sha256:&lt;hex&gt;"); the
 * plaintext is shown exactly once at creation. keyHint holds the first
 * characters of the plaintext so users can recognise keys in listings.
 */
class ApiKey {

    String id
    String key
    String keyHint

    Date createdAt = new Date()
    User createdBy
    Date validUntil
    String comment
    Date lastConnectedAt

    /**
     * The databases this key may trigger backups for. Empty means every
     * database — existing keys keep working. Behavior will change in v4
     */
    static hasMany = [databases: Database]

    static constraints = {
        key nullable: false, blank: false, minSize: 64
        keyHint nullable: true
        validUntil nullable: true
        comment nullable: true
        lastConnectedAt nullable: true
    }

    static mapping = {
        table 'api_key'
        id generator: 'uuid2'
        key column: '`key`'
        databases joinTable: [name: 'api_key_database', key: 'api_key_id', column: 'database_id']
    }

    Boolean getIsValid() {
        if (this.validUntil) {
            return (this.validUntil > new Date())
        } else {
            return true
        }
    }

    /** Whether this key may trigger a backup of the given database. */
    boolean coversDatabase(Database database) {
        return !databases || databases.contains(database)
    }
}
