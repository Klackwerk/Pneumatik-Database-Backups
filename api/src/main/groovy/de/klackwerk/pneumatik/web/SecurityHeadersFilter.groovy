package de.klackwerk.pneumatik.web

import groovy.transform.CompileStatic
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Response headers that constrain what a browser will do with this origin.
 *
 * The access token lives in localStorage, so any script that runs here can
 * read it. A content security policy is what keeps a stored-XSS bug from
 * turning into silent, persistent admin access — the SPA loads only its own
 * bundled assets, so 'self' is all it ever needs.
 *
 * Inline styles are allowed: React sets style attributes and the charting
 * library relies on them. Inline *scripts* are not — the one page that needs
 * one (Swagger UI) sets its own policy with a nonce.
 */
@CompileStatic
class SecurityHeadersFilter implements Filter {

    static final String DEFAULT_CONTENT_SECURITY_POLICY = [
            "default-src 'self'",
            "base-uri 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "object-src 'none'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "font-src 'self' data:",
            "connect-src 'self'",
    ].join('; ')

    String contentSecurityPolicy = DEFAULT_CONTENT_SECURITY_POLICY
    /** max-age for HSTS in seconds; 0 disables the header */
    long hstsMaxAgeSeconds = 31_536_000
    /**
     * Whether X-Forwarded-Proto may say the connection was HTTPS. Only
     * enable behind a proxy that sets it, or any client could ask for HSTS
     * to be pinned on a plain-HTTP deployment and lock itself out.
     */
    boolean trustForwardedProto = false

    @Override
    void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) {
        HttpServletRequest request = req as HttpServletRequest
        HttpServletResponse response = res as HttpServletResponse

        response.setHeader('Content-Security-Policy', contentSecurityPolicy)
        response.setHeader('X-Content-Type-Options', 'nosniff')
        response.setHeader('X-Frame-Options', 'DENY')
        response.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin')
        response.setHeader('Permissions-Policy', 'geolocation=(), microphone=(), camera=()')

        // sending HSTS over plain http is ignored by browsers, and sending it
        // from a LAN deployment that has no TLS would lock users out
        if (hstsMaxAgeSeconds > 0 && isSecure(request)) {
            response.setHeader('Strict-Transport-Security', "max-age=${hstsMaxAgeSeconds}; includeSubDomains")
        }

        chain.doFilter(req, res)
    }

    private boolean isSecure(HttpServletRequest request) {
        if (request.secure) {
            return true
        }
        return trustForwardedProto && 'https'.equalsIgnoreCase(request.getHeader('X-Forwarded-Proto'))
    }
}
