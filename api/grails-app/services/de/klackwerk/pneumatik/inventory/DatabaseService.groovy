package de.klackwerk.pneumatik.inventory

import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.RetentionPolicy
import de.klackwerk.pneumatik.credentials.CredentialService
import de.klackwerk.pneumatik.storage.StorageService
import grails.gorm.transactions.Transactional

@Transactional
class DatabaseService {

    CredentialService credentialService
    StorageService storageService

    List<Database> listDatabases() {
        log.debug 'DATABASESERVICE - List databases'
        return Database.list(sort: 'id')
    }

    /** How much a delete would destroy — shown to the user before they confirm. */
    Map deletionImpact(Database database) {
        List<Backup> backups = backupsOf(database)
        return [
                backupCount    : (long) backups.size(),
                storedFileCount: (long) backups.count { it.filename != null },
        ]
    }

    private static List<Backup> backupsOf(Database database) {
        return Backup.createCriteria().list {
            eq 'database', database
        } as List<Backup>
    }

    /**
     * Deletes a database together with its backup history, the stored
     * archives and its retention policy.
     *
     * @return the deletion impact that was actually carried out
     */
    Map deleteDatabase(Database database) {
        Map impact = deletionImpact(database)
        log.info "DATABASESERVICE - Deleting database ${database.name} with ${impact.backupCount} backup(s)"

        RetentionPolicy.createCriteria().list { eq 'database', database }.each { it.delete() }

        // deleteBackup removes the stored file and the row; one at a time so
        // a single unreadable archive cannot abort the whole delete
        backupsOf(database).each { Backup backup ->
            try {
                storageService.deleteBackup(backup)
            } catch (Exception e) {
                log.error "DATABASESERVICE - Could not delete backup ${backup.id}, removing the record anyway", e
                backup.delete()
            }
        }

        database.delete(flush: true)
        return impact
    }

    Database addDatabase(DatabaseCommand cmd) {
        log.debug 'DATABASESERVICE - Create new Database'
        Database database = new Database()
        return setDatabaseParams(cmd, database)
    }

    Database editDatabase(DatabaseCommand cmd, Database database) {
        log.debug "DATABASESERVICE - Edit database ${database.id}"
        return setDatabaseParams(cmd, database)
    }

    protected Database setDatabaseParams(DatabaseCommand cmd, Database database) {
        log.debug 'DATABASESERVICE - Set Database params'
        database.friendlyName = cmd.friendlyName
        database.databaseName = cmd.databaseName
        database.host = Host.get(cmd.hostId)
        database.user = cmd.user

        database.storageProvider = cmd.storageProvider
        database.trigger = cmd.trigger
        database.databaseType = cmd.databaseType

        // only change password if there is input
        if (database.id == null || cmd.password) {
            database.password = credentialService.encryptString(cmd.password)
            log.debug 'DATABASESERVICE - Setting / Changing password to encrypted value'
        }
        database.save(flush: true)
        return database
    }
}
