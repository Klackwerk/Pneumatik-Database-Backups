package de.klackwerk.pneumatik.inventory

import de.klackwerk.pneumatik.backup.Trigger
import de.klackwerk.pneumatik.storage.StorageProvider
import grails.validation.Validateable

class DatabaseCommand implements Validateable {

    String friendlyName
    String databaseName

    String hostId

    String user
    String password

    StorageProvider storageProvider
    Trigger trigger
    DatabaseType databaseType

    static constraints = {
        friendlyName    nullable: true, blank: false
        databaseName    nullable: false, blank: false, matches: InventoryPatterns.IDENTIFIER
        hostId          nullable: false, validator: { String val, DatabaseCommand obj ->
            if (val != null && !Host.exists(val)) {
                return ['notFound']
            }
        }
        user            nullable: true, blank: false, matches: InventoryPatterns.LOGIN
        password        nullable: true, blank: false
        storageProvider nullable: false
        trigger         nullable: false
        databaseType    nullable: true
    }
}
