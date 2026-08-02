package de.klackwerk.pneumatik.api.v1

import de.klackwerk.pneumatik.api.ApiMappers
import de.klackwerk.pneumatik.api.BaseApiController
import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.BackupService
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.DatabaseCommand
import de.klackwerk.pneumatik.inventory.DatabaseService
import grails.plugin.springsecurity.annotation.Secured

@Secured('ROLE_ADMIN')
class DatabasesController extends BaseApiController {

    static namespace = 'v1'

    DatabaseService databaseService
    BackupService backupService

    def index() {
        Map p = pagination(50, 200)
        List<Database> databases = Database.createCriteria().list(max: p.max as int, offset: p.offset as int) {
            join 'host'
            order 'id', 'asc'
        } as List<Database>

        // one grouped query for the whole page, so the listing can show what
        // a delete would destroy without an extra round trip per row
        Map<String, Long> backupCounts = [:]
        if (databases) {
            Backup.executeQuery(
                    'select b.database.id, count(b.id) from Backup b where b.database in :databases ' +
                            'group by b.database.id', [databases: databases]).each { row ->
                backupCounts[row[0] as String] = row[1] as Long
            }
        }

        respondData(databases.collect {
            ApiMappers.databaseToMap(it) + [backupCount: backupCounts[it.id] ?: 0L]
        }, paginationMeta(p, Database.count()))
    }

    def show(String id) {
        Database database = Database.get(id)
        if (!database) {
            respondNotFound('Database')
            return
        }
        respondData(ApiMappers.databaseToMap(database) + databaseService.deletionImpact(database))
    }

    def save(DatabaseCommand cmd) {
        if (cmd.hasErrors()) {
            respondValidationErrors(cmd)
            return
        }
        Database database = databaseService.addDatabase(cmd)
        if (database.hasErrors()) {
            respondValidationErrors(database)
            return
        }
        respondCreated(ApiMappers.databaseToMap(database),
                locationOf("/api/v1/databases/${database.id}"))
    }

    def update(String id, DatabaseCommand cmd) {
        Database database = Database.get(id)
        if (!database) {
            respondNotFound('Database')
            return
        }
        if (cmd.hasErrors()) {
            respondValidationErrors(cmd)
            return
        }
        database = databaseService.editDatabase(cmd, database)
        if (database.hasErrors()) {
            respondValidationErrors(database)
            return
        }
        respondData(ApiMappers.databaseToMap(database))
    }

    /**
     * DELETE /api/v1/databases/{id} — removes the database, its backup
     * history and every stored archive. The UI states the counts (from the
     * listing's backupCount) before asking for confirmation.
     */
    def delete(String id) {
        Database database = Database.get(id)
        if (!database) {
            respondNotFound('Database')
            return
        }
        databaseService.deleteDatabase(database)
        respondNoContent()
    }

    /**
     * POST /api/v1/databases/{id}/backups — queue a backup now.
     * The queue drainer job picks it up within a minute.
     */
    def triggerBackup(String id) {
        Database database = Database.get(id)
        if (!database) {
            respondNotFound('Database')
            return
        }
        backupService.createBackup(database)
        respondData([queued: true, databaseId: database.id], null, 202)
    }
}
