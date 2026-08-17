package de.klackwerk.pneumatik.api.v1

import grails.plugin.springsecurity.annotation.Secured

import java.security.SecureRandom

/**
 * Swagger UI for the OpenAPI contract, served from the swagger-ui webjar
 * (no CDN dependency).
 */
@Secured('permitAll')
class DocsController {

    static namespace = 'v1'

    /** must match the org.webjars:swagger-ui version in build.gradle */
    static final String SWAGGER_UI_VERSION = '5.32.8'

    def index() {
        String base = "/webjars/swagger-ui/${SWAGGER_UI_VERSION}"

        // This page needs one inline script to boot Swagger UI, which the
        // application-wide policy forbids. Rather than weaken that policy
        // everywhere, this response carries its own allowing exactly this
        // script — a fresh nonce per response, so nothing injected later can
        // claim it.
        String nonce = Base64.urlEncoder.withoutPadding().encodeToString(nonceBytes())
        response.setHeader('Content-Security-Policy', [
                "default-src 'self'",
                "base-uri 'self'",
                "frame-ancestors 'none'",
                "object-src 'none'",
                "script-src 'self' 'nonce-${nonce}'",
                "style-src 'self' 'unsafe-inline'",
                "img-src 'self' data:",
                "connect-src 'self'",
        ].join('; '))

        render(contentType: 'text/html;charset=UTF-8', text: """\
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Pneumatik API docs</title>
  <link rel="stylesheet" href="${base}/swagger-ui.css">
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="${base}/swagger-ui-bundle.js"></script>
  <script nonce="${nonce}">
    window.ui = SwaggerUIBundle({
      url: '/api/v1/openapi.yaml',
      dom_id: '#swagger-ui',
      deepLinking: true,
      presets: [SwaggerUIBundle.presets.apis],
    });
  </script>
</body>
</html>""")
    }

    private static byte[] nonceBytes() {
        byte[] bytes = new byte[16]
        new SecureRandom().nextBytes(bytes)
        return bytes
    }
}
