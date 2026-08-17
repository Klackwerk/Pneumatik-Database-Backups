package de.klackwerk.pneumatik.web

import grails.plugin.springsecurity.annotation.Secured

/**
 * Serves the React single-page app when the frontend build is bundled into
 * the jar/war (the integrated Docker image copies app/dist into
 * classpath:/META-INF/resources). This controller returns the shell; the
 * assets it references are served by SpaResourceConfig — the container does
 * NOT serve them natively, because META-INF/resources is only auto-served out
 * of jars in WEB-INF/lib. In development the frontend runs on its own Vite
 * server, so a hint is shown instead.
 */
@Secured('permitAll')
class SpaController {

    private static volatile String cachedIndexHtml

    static String indexHtml() {
        String html = cachedIndexHtml
        if (html == null) {
            InputStream stream = SpaController.getResourceAsStream('/META-INF/resources/index.html')
            html = stream?.getText('UTF-8')
            cachedIndexHtml = html
        }
        return html
    }

    def index() {
        String html = indexHtml()
        if (html == null) {
            render(status: 404, contentType: 'text/plain;charset=UTF-8',
                    text: 'No frontend bundled. In development, open the Vite dev server (http://localhost:5173) instead.')
            return
        }
        render(contentType: 'text/html;charset=UTF-8', text: html)
    }
}
