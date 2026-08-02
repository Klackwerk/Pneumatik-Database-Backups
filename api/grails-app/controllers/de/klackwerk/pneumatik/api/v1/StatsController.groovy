package de.klackwerk.pneumatik.api.v1

import de.klackwerk.pneumatik.api.BaseApiController
import de.klackwerk.pneumatik.stats.StatsService
import grails.plugin.springsecurity.annotation.Secured

@Secured('ROLE_ADMIN')
class StatsController extends BaseApiController {

    static namespace = 'v1'

    StatsService statsService

    /**
     * GET /api/v1/stats/dashboard — aggregates for the dashboard charts.
     */
    def dashboard() {
        respondData(statsService.dashboardStats())
    }
}
