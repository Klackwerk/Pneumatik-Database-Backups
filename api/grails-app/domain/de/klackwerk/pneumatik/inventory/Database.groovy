package de.klackwerk.pneumatik.inventory

import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.Trigger
import de.klackwerk.pneumatik.storage.StorageProvider

class Database {

    String  id
    String  friendlyName
    String  databaseName

    Host    host

    String  user
    String  password

    StorageProvider storageProvider
    Trigger trigger
    DatabaseType databaseType

    static hasMany = [backups: Backup]

    static constraints = {
        friendlyName    nullable: true, blank: false
        // databaseName and user reach the dump command and the dump filename
        databaseName    nullable: false, blank: false, matches: InventoryPatterns.IDENTIFIER
        host            nullable: false, blank: false
        user            nullable: true, blank: false, matches: InventoryPatterns.LOGIN
        password        nullable: true, blank: false
        storageProvider nullable: false, blank: false
        trigger         nullable: false, blank: false
        databaseType    nullable: true, blank: false
    }

    // Don't use restricted table name
    static mapping = {
        table 'db'
        id generator: 'uuid2'
        trigger column: 'backup_trigger'
        // "user" is reserved in PostgreSQL; backticks quote it per dialect
        user column: '`user`'
    }

    String getName() {
        if (friendlyName) {
            return friendlyName
        } else {
            return databaseName
        }
    }
}
