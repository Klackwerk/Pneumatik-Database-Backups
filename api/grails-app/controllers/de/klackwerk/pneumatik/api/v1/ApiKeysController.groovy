package de.klackwerk.pneumatik.api.v1

import de.klackwerk.pneumatik.api.ApiMappers
import de.klackwerk.pneumatik.api.BaseApiController
import de.klackwerk.pneumatik.security.ApiKey
import de.klackwerk.pneumatik.security.ApiKeyCommand
import de.klackwerk.pneumatik.security.ApiKeyService
import grails.plugin.springsecurity.annotation.Secured

@Secured('ROLE_ADMIN')
class ApiKeysController extends BaseApiController {

    static namespace = 'v1'

    ApiKeyService apiKeyService

    /** GET /api/v1/api-keys — the current user's keys. */
    def index() {
        List<ApiKey> keys = apiKeyService.listApiKeysForCurrentUser()
        respondData(keys.collect { ApiMappers.apiKeyToMap(it) })
    }

    /**
     * POST /api/v1/api-keys — the response contains the plaintext key
     * exactly once; it cannot be retrieved again.
     */
    def save(ApiKeyCommand cmd) {
        if (cmd.hasErrors()) {
            respondValidationErrors(cmd)
            return
        }
        Map result = apiKeyService.createApiKey(cmd)
        ApiKey apiKey = result.apiKey as ApiKey
        if (apiKey.hasErrors()) {
            respondValidationErrors(apiKey)
            return
        }
        Map body = ApiMappers.apiKeyToMap(apiKey)
        body.key = result.plainKey
        respondCreated(body, locationOf("/api/v1/api-keys/${apiKey.id}"))
    }

    /**
     * DELETE /api/v1/api-keys/{id} — only the creator may delete a key
     * (legacy rule, preserved).
     */
    def delete(String id) {
        ApiKey apiKey = ApiKey.get(id)
        if (!apiKey) {
            respondNotFound('API key')
            return
        }
        if (!apiKeyService.deleteApiKey(id)) {
            respondError(403, 'forbidden', 'Only the creator of an API key can delete it')
            return
        }
        respondNoContent()
    }
}
