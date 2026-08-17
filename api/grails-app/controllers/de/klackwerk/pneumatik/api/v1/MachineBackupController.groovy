package de.klackwerk.pneumatik.api.v1

import de.klackwerk.pneumatik.api.BaseApiController
import de.klackwerk.pneumatik.backup.BackupService
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.security.ApiKey
import de.klackwerk.pneumatik.security.ApiKeyService
import grails.plugin.springsecurity.annotation.Secured

/**
 * Machine-to-machine endpoint, authenticated by X-API-Key header.
 *
 * POST /api/v1/backup/create/{databaseId} is kept verbatim from the legacy
 * app so existing integrations keep working; status-code behaviour is
 * preserved (200 queued, 401 bad key, 404 unknown database).
 */
@Secured('ROLE_ANONYMOUS')
class MachineBackupController extends BaseApiController {

    static namespace = 'v1'

    ApiKeyService apiKeyService
    BackupService backupService

    def create(String databaseId) {
        ApiKey apiKey = apiKeyService.authenticate(request)
        if (!apiKey) {
            respondError(401, 'invalid_api_key', 'Missing or invalid X-API-Key header')
            return
        }

        Database database = Database.get(databaseId)
        if (!database) {
            respondNotFound('Database')
            return
        }

        // a key scoped to specific databases must not reach the others; the
        // same 404 as an unknown id, so a key cannot be used to discover
        // which database ids exist
        if (!apiKey.coversDatabase(database)) {
            log.warn "MACHINEBACKUPCONTROLLER - Key ${apiKey.keyHint} is not scoped for database ${database.id}"
            respondNotFound('Database')
            return
        }

        backupService.createBackup(database)
        respondData([queued: true, databaseId: database.id])
    }
}
