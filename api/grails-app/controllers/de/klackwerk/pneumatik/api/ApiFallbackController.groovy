package de.klackwerk.pneumatik.api

import de.klackwerk.pneumatik.web.SpaController
import grails.plugin.springsecurity.annotation.Secured

/**
 * Renders the standard error envelope for framework-level 404s and 500s
 * (unmatched routes, exceptions that escaped controllers). Unmatched
 * non-API GETs fall through to the SPA so client-side routes deep-link.
 */
@Secured('permitAll')
class ApiFallbackController extends BaseApiController {

    def notFound() {
        String originalUri = (request.getAttribute('jakarta.servlet.forward.request_uri') ?: request.requestURI) as String
        boolean apiRequest = originalUri?.startsWith('/api/') || originalUri?.startsWith('/webjars/')
        if (!apiRequest && request.method == 'GET' && !looksLikeStaticAsset(originalUri)) {
            String html = SpaController.indexHtml()
            if (html != null) {
                // client-side route: the SPA shell is the right answer, not a 404
                response.status = 200
                render(contentType: 'text/html;charset=UTF-8', text: html)
                return
            }
        }
        respondError(404, 'not_found', 'The requested resource does not exist')
    }

    /**
     * Client-side routes look like /backups or /settings/profile — no file
     * extension. A request for something that looks like a file is a missing
     * asset, and answering it with the SPA shell (status 200, text/html) hides
     * the failure: the browser silently refuses the response and renders a
     * blank page. Those must 404.
     */
    private static boolean looksLikeStaticAsset(String uri) {
        String lastSegment = uri?.substring(uri.lastIndexOf('/') + 1)
        return lastSegment ? lastSegment.contains('.') : false
    }

    def serverError() {
        Throwable exception = request.getAttribute('exception')?.cause ?: request.getAttribute('exception')
        log.error 'Unhandled server error', exception as Throwable
        respondError(500, 'internal_error', 'An unexpected error occurred')
    }
}
