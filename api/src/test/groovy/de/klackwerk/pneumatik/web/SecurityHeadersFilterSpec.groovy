package de.klackwerk.pneumatik.web

import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification

class SecurityHeadersFilterSpec extends Specification {

    SecurityHeadersFilter filter
    MockHttpServletRequest request
    MockHttpServletResponse response
    FilterChain chain

    void setup() {
        filter = new SecurityHeadersFilter()
        request = new MockHttpServletRequest('GET', '/')
        response = new MockHttpServletResponse()
        chain = Mock(FilterChain)
    }

    void 'every response carries a content security policy'() {
        when:
        filter.doFilter(request, response, chain)

        then:
        1 * chain.doFilter(request, response)
        String policy = response.getHeader('Content-Security-Policy')

        and: 'the SPA loads only its own bundled assets'
        policy.contains("default-src 'self'")
        policy.contains("script-src 'self'")
        policy.contains("object-src 'none'")
        policy.contains("frame-ancestors 'none'")

        and: 'inline scripts are not allowed — that is what protects the stored token'
        !policy.contains("script-src 'self' 'unsafe-inline'")
    }

    void 'the usual hardening headers are set'() {
        when:
        filter.doFilter(request, response, chain)

        then:
        response.getHeader('X-Content-Type-Options') == 'nosniff'
        response.getHeader('X-Frame-Options') == 'DENY'
        response.getHeader('Referrer-Policy') == 'strict-origin-when-cross-origin'
        response.getHeader('Permissions-Policy')
    }

    void 'HSTS is only sent over a connection that is actually secure'() {
        when: 'plain http, as on a LAN deployment with no TLS'
        filter.doFilter(request, response, chain)

        then: 'pinning HSTS here would lock the user out of their own tool'
        response.getHeader('Strict-Transport-Security') == null

        when:
        request.secure = true
        response = new MockHttpServletResponse()
        filter.doFilter(request, response, chain)

        then:
        response.getHeader('Strict-Transport-Security').contains('max-age=31536000')
    }

    void 'X-Forwarded-Proto only counts when proxies are trusted'() {
        given:
        request.addHeader('X-Forwarded-Proto', 'https')

        when: 'untrusted: any client could send this header'
        filter.doFilter(request, response, chain)

        then:
        response.getHeader('Strict-Transport-Security') == null

        when:
        filter.trustForwardedProto = true
        response = new MockHttpServletResponse()
        filter.doFilter(request, response, chain)

        then:
        response.getHeader('Strict-Transport-Security')
    }

    void 'HSTS can be turned off entirely'() {
        given:
        filter.hstsMaxAgeSeconds = 0
        request.secure = true

        when:
        filter.doFilter(request, response, chain)

        then:
        response.getHeader('Strict-Transport-Security') == null
    }
}
