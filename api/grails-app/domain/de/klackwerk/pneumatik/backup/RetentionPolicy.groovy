package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Database

/**
 * Retention rules for one database's backups (new in 2.0; the legacy app
 * kept every backup forever).
 *
 * Both limits are optional and combine as follows for FINISHED backups:
 *  - keepCount: keep at most the N most recent successful backups
 *  - keepDays:  delete successful backups older than N days
 * A backup is deleted when EITHER limit says it should go. Failed backup
 * rows are never touched by retention (they carry no stored file).
 *
 * Neither limit can empty a database's backups: see
 * RetentionService.MINIMUM_RETAINED.
 */
class RetentionPolicy {

    String id
    Database database

    Integer keepCount
    Integer keepDays
    Boolean enabled = true

    static constraints = {
        database  nullable: false, unique: true
        keepCount nullable: true, min: 1
        keepDays  nullable: true, min: 1
        enabled   nullable: false
    }

    static mapping = {
        table 'retention_policy'
        id generator: 'uuid2'
    }
}
