package de.klackwerk.pneumatik.api.v1

import de.klackwerk.pneumatik.api.ApiMappers
import de.klackwerk.pneumatik.api.BaseApiController
import de.klackwerk.pneumatik.backup.RetentionPolicy
import de.klackwerk.pneumatik.backup.RetentionPolicyCommand
import de.klackwerk.pneumatik.backup.RetentionService
import de.klackwerk.pneumatik.inventory.Database
import grails.plugin.springsecurity.annotation.Secured

/**
 * Retention policy of one database, addressed as a singleton sub-resource:
 * GET/PUT/DELETE /api/v1/databases/{databaseId}/retention-policy
 */
@Secured('ROLE_ADMIN')
class RetentionPoliciesController extends BaseApiController {

    static namespace = 'v1'

    RetentionService retentionService

    def show(String databaseId) {
        Database database = Database.get(databaseId)
        if (!database) {
            respondNotFound('Database')
            return
        }
        RetentionPolicy policy = RetentionPolicy.findByDatabase(database)
        if (!policy) {
            respondNotFound('Retention policy')
            return
        }
        respondData(ApiMappers.retentionPolicyToMap(policy))
    }

    /** PUT — creates the policy if missing, updates it otherwise. */
    def update(String databaseId, RetentionPolicyCommand cmd) {
        Database database = Database.get(databaseId)
        if (!database) {
            respondNotFound('Database')
            return
        }
        if (cmd.hasErrors()) {
            respondValidationErrors(cmd)
            return
        }
        Map result = retentionService.upsertPolicy(database, cmd)
        RetentionPolicy policy = result.policy as RetentionPolicy
        if (policy.hasErrors()) {
            respondValidationErrors(policy)
            return
        }
        if (result.created) {
            respondCreated(ApiMappers.retentionPolicyToMap(policy),
                    locationOf("/api/v1/databases/${databaseId}/retention-policy"))
        } else {
            respondData(ApiMappers.retentionPolicyToMap(policy))
        }
    }

    def delete(String databaseId) {
        Database database = Database.get(databaseId)
        if (!database) {
            respondNotFound('Database')
            return
        }
        if (!retentionService.deletePolicy(database)) {
            respondNotFound('Retention policy')
            return
        }
        respondNoContent()
    }
}
