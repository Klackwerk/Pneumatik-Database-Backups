package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.security.User
import de.klackwerk.pneumatik.storage.StorageProvider

class Backup {

    String id
    Database database

    Date createdAt = new Date()
    Date executedAt
    Date finishedAt
    String filename
    String fullPath
    String size
    Long rawSizeBytes
    Long archivedSizeBytes
    BackupState state

    /** captured output (stderr) of the dump command */
    String output

    Integer exitCode
    Boolean success

    /** whether the stored archive is AES-256-GCM encrypted */
    Boolean encrypted

    User createdBy

    StorageProvider storageProvider

    static constraints = {
        database    nullable: false, blank: false
        createdAt   nullable: true, blank: false
        executedAt  nullable: true, blank: false
        finishedAt  nullable: true
        filename    nullable: true, blank: false
        fullPath    nullable: true, blank: false
        size        nullable: true, blank: false
        rawSizeBytes nullable: true
        archivedSizeBytes nullable: true
        encrypted   nullable: true
        output      nullable: true
        state       nullable: true
        exitCode    nullable: true, blank: false
        success     nullable: false
        createdBy   nullable: true, blank: false
        storageProvider nullable: false, blank: false
    }

    static mapping = {
        table 'backup'
        id generator: 'uuid2'
        state enumType: 'string'
        output type: 'text'
    }
}
