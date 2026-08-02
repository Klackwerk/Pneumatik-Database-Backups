package de.klackwerk.pneumatik.api.v1

import grails.plugin.springsecurity.annotation.Secured

/**
 * Serves the hand-maintained OpenAPI contract (src/main/resources/openapi.yaml).
 * That file is the single source of truth the frontend's typed client is
 * generated from.
 */
@Secured('permitAll')
class OpenapiController {

    static namespace = 'v1'

    def spec() {
        InputStream stream = getClass().getResourceAsStream('/openapi.yaml')
        if (stream == null) {
            render(status: 404)
            return
        }
        response.setHeader('Cache-Control', 'no-cache')
        render(contentType: 'application/yaml;charset=UTF-8', text: stream.getText('UTF-8'))
    }
}
