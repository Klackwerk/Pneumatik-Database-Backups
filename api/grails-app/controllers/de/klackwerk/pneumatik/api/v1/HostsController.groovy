package de.klackwerk.pneumatik.api.v1

import de.klackwerk.pneumatik.api.ApiMappers
import de.klackwerk.pneumatik.api.BaseApiController
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.inventory.HostCommand
import de.klackwerk.pneumatik.inventory.HostService
import grails.plugin.springsecurity.annotation.Secured

@Secured('ROLE_ADMIN')
class HostsController extends BaseApiController {

    static namespace = 'v1'

    HostService hostService

    def index() {
        Map p = pagination(50, 200)
        List<Host> hosts = Host.list(sort: 'id', max: p.max as int, offset: p.offset as int)

        // one grouped query for the whole page
        Map<String, Long> databaseCounts = [:]
        if (hosts) {
            Database.executeQuery(
                    'select d.host.id, count(d.id) from Database d where d.host in :hosts group by d.host.id',
                    [hosts: hosts]).each { row ->
                databaseCounts[row[0] as String] = row[1] as Long
            }
        }

        respondData(hosts.collect {
            ApiMappers.hostToMap(it) + [databaseCount: databaseCounts[it.id] ?: 0L]
        }, paginationMeta(p, Host.count()))
    }

    def show(String id) {
        Host host = Host.get(id)
        if (!host) {
            respondNotFound('Host')
            return
        }
        respondData(ApiMappers.hostToMap(host) + hostService.deletionImpact(host))
    }

    /**
     * DELETE /api/v1/hosts/{id} — removes the host and every database on it,
     * including their backup history and stored archives. The UI states the
     * counts before asking for confirmation.
     */
    def delete(String id) {
        Host host = Host.get(id)
        if (!host) {
            respondNotFound('Host')
            return
        }
        hostService.deleteHost(host)
        respondNoContent()
    }

    def save(HostCommand cmd) {
        cmd.id = null
        if (cmd.hasErrors()) {
            respondValidationErrors(cmd)
            return
        }
        Host host = hostService.addHost(cmd)
        if (host.hasErrors()) {
            respondValidationErrors(host)
            return
        }
        respondCreated(ApiMappers.hostToMap(host), locationOf("/api/v1/hosts/${host.id}"))
    }

    def update(String id, HostCommand cmd) {
        Host host = Host.get(id)
        if (!host) {
            respondNotFound('Host')
            return
        }
        // validation ran at binding time without the id; re-run with it so
        // uniqueness checks can exclude the record being edited
        cmd.id = id
        cmd.clearErrors()
        if (!cmd.validate()) {
            respondValidationErrors(cmd)
            return
        }
        host = hostService.editHost(cmd, host)
        if (host.hasErrors()) {
            respondValidationErrors(host)
            return
        }
        respondData(ApiMappers.hostToMap(host))
    }
}
