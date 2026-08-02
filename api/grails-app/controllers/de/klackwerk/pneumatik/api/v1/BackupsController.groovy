package de.klackwerk.pneumatik.api.v1

import de.klackwerk.pneumatik.api.ApiMappers
import de.klackwerk.pneumatik.api.BaseApiController
import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.BackupService
import de.klackwerk.pneumatik.backup.BackupState
import de.klackwerk.pneumatik.backup.DownloadTokenService
import de.klackwerk.pneumatik.storage.StorageService
import grails.plugin.springsecurity.annotation.Secured

@Secured('ROLE_ADMIN')
class BackupsController extends BaseApiController {

    static namespace = 'v1'

    BackupService backupService
    StorageService storageService
    DownloadTokenService downloadTokenService
    def springSecurityService

    /**
     * GET /api/v1/backups?page=&pageSize=&search=&sort=&order=&databaseId=&hostId=&state=
     */
    def index() {
        Map p = pagination(25, 100)
        String sort = params.sort ?: 'createdAt'
        if (!BackupService.SORTABLE_FIELDS.containsKey(sort)) {
            respondError(400, 'invalid_parameter',
                    "Unknown sort field '${sort}'. Valid: ${BackupService.SORTABLE_FIELDS.keySet().join(', ')}")
            return
        }

        BackupState state = null
        if (params.state) {
            try {
                state = BackupState.valueOf(params.state as String)
            } catch (IllegalArgumentException ignored) {
                respondError(400, 'invalid_parameter',
                        "Unknown state '${params.state}'. Valid: ${BackupState.values()*.name().join(', ')}")
                return
            }
        }

        Map result = backupService.listBackups(p.offset as int, p.max as int,
                params.search as String, sort, params.order as String, params.databaseId as String,
                params.hostId as String, state)
        respondData((result.items as List<Backup>).collect { ApiMappers.backupToMap(it) },
                paginationMeta(p, result.total as long, result.filtered as Long))
    }

    def show(String id) {
        Backup backup = Backup.get(id)
        if (!backup) {
            respondNotFound('Backup')
            return
        }
        respondData(ApiMappers.backupDetailToMap(backup))
    }

    /**
     * POST /api/v1/backups/{id}/download-token — mint a single-use ticket so
     * the browser can download through a plain link. See DownloadTokenService
     * for why the download itself cannot require an Authorization header.
     */
    def downloadToken(String id) {
        Backup backup = Backup.get(id)
        if (!backup) {
            respondNotFound('Backup')
            return
        }
        if (!backup.filename) {
            respondError(409, 'not_downloadable', 'Backup has no stored file')
            return
        }
        String username = springSecurityService.principal?.username
        respondData([
                token    : downloadTokenService.issue(backup.id, username),
                expiresIn: downloadTokenService.ttlSeconds,
                filename : backup.filename,
        ])
    }

    /**
     * GET /api/v1/backups/{id}/download — streams the stored zip archive
     * from storage to the client without buffering it in memory.
     *
     * Public in the security config so a browser navigation can reach it;
     * authorization is either a single-use ticket in the query string or a
     * normal bearer token, and one of the two is required here.
     */
    @Secured('permitAll')
    def download(String id) {
        boolean ticketed = downloadTokenService.consume(params.token as String, id) != null
        if (!ticketed && !springSecurityService.isLoggedIn()) {
            respondError(401, 'unauthorized', 'A download ticket or a bearer token is required')
            return
        }

        Backup backup = Backup.get(id)
        if (!backup) {
            respondNotFound('Backup')
            return
        }
        if (!backup.filename) {
            respondError(409, 'not_downloadable', 'Backup has no stored file')
            return
        }
        InputStream archive
        try {
            archive = storageService.openBackup(backup)
        } catch (Exception e) {
            log.error "Could not read backup file for backup ${backup.id}", e
            respondError(502, 'storage_error', 'Backup file could not be read from storage')
            return
        }
        response.setHeader('Content-Disposition', contentDisposition(backup.filename))
        response.contentType = 'application/octet-stream'
        Long size = storageService.storedBackupSize(backup)
        if (size != null) {
            response.setContentLengthLong(size)
        }
        archive.withCloseable { InputStream stream ->
            stream.transferTo(response.outputStream)
        }
        response.outputStream.flush()
        return null
    }

    /**
     * RFC 6266 Content-Disposition. Filenames are sanitised on the way in,
     * but rows created before that are not, and a quote in the plain
     * parameter would end the header value early.
     */
    protected static String contentDisposition(String filename) {
        String ascii = filename.replaceAll(/[^A-Za-z0-9_.\-]/, '_')
        String encoded = URLEncoder.encode(filename, 'UTF-8').replace('+', '%20')
        return "attachment; filename=\"${ascii}\"; filename*=UTF-8''${encoded}"
    }

    def delete(String id) {
        Backup backup = Backup.get(id)
        if (!backup) {
            respondNotFound('Backup')
            return
        }
        storageService.deleteBackup(backup)
        respondNoContent()
    }
}
