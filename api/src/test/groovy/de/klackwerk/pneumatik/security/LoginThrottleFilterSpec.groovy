package de.klackwerk.pneumatik.security

import groovy.json.JsonSlurper
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification

class LoginThrottleFilterSpec extends Specification {

    LoginThrottleFilter filter

    void setup() {
        filter = new LoginThrottleFilter(maxAttempts: 3, windowMinutes: 15, lockMinutes: 15)
    }

    private MockHttpServletRequest loginRequest(String username, String address = '10.0.0.1') {
        MockHttpServletRequest request = new MockHttpServletRequest('POST', '/api/v1/auth/login')
        request.contentType = 'application/json'
        request.content = /{"username":"${username}","password":"guess"}/.bytes
        request.remoteAddr = address
        return request
    }

    /** a chain that answers with the given status, as the auth filter would */
    private FilterChain chainReturning(int status, List<String> bodySink = []) {
        return new FilterChain() {
            @Override
            void doFilter(ServletRequest req, ServletResponse res) {
                bodySink << (req as HttpServletRequest).inputStream.text
                (res as HttpServletResponse).status = status
            }
        }
    }

    void 'failed attempts below the limit pass through to the authentication filter'() {
        given:
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        2.times {
            filter.doFilter(loginRequest('admin'), response = new MockHttpServletResponse(),
                    chainReturning(401))
        }

        then:
        response.status == 401
    }

    void 'the request body survives the filter so credentials still reach the chain'() {
        given:
        List<String> seen = []

        when:
        filter.doFilter(loginRequest('admin'), new MockHttpServletResponse(), chainReturning(200, seen))

        then: 'the buffered body is replayed verbatim'
        seen == ['{"username":"admin","password":"guess"}']
    }

    void 'the account is refused after too many failures, with a Retry-After hint'() {
        given:
        3.times {
            filter.doFilter(loginRequest('admin'), new MockHttpServletResponse(), chainReturning(401))
        }
        MockHttpServletResponse response = new MockHttpServletResponse()
        List<String> reachedChain = []

        when:
        filter.doFilter(loginRequest('admin'), response, chainReturning(401, reachedChain))

        then: 'no password is checked at all — the chain is never entered'
        reachedChain.isEmpty()
        response.status == 429
        response.getHeader('Retry-After') as int > 0
        new JsonSlurper().parseText(response.contentAsString).error.code == 'too_many_requests'
    }

    void 'a username is throttled even when the attempts come from different addresses'() {
        given: 'each attempt from its own address, so only the username accumulates'
        3.times { int i ->
            filter.doFilter(loginRequest('admin', "10.0.0.${i + 1}"), new MockHttpServletResponse(),
                    chainReturning(401))
        }
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        filter.doFilter(loginRequest('admin', '10.0.0.99'), response, chainReturning(401))

        then:
        response.status == 429
    }

    void 'an address is throttled even when the attempts walk through usernames'() {
        given:
        3.times { int i ->
            filter.doFilter(loginRequest("user${i}"), new MockHttpServletResponse(), chainReturning(401))
        }
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        filter.doFilter(loginRequest('someone-else'), response, chainReturning(401))

        then:
        response.status == 429
    }

    void 'a successful sign-in clears the failure counters'() {
        given:
        2.times {
            filter.doFilter(loginRequest('admin'), new MockHttpServletResponse(), chainReturning(401))
        }

        when: 'the third attempt succeeds, then two more fail'
        filter.doFilter(loginRequest('admin'), new MockHttpServletResponse(), chainReturning(200))
        MockHttpServletResponse response = new MockHttpServletResponse()
        2.times {
            filter.doFilter(loginRequest('admin'), response = new MockHttpServletResponse(),
                    chainReturning(401))
        }

        then: 'the counter restarted, so these are still below the limit'
        response.status == 401
    }

    void 'requests that are not POST are never throttled'() {
        given:
        List<String> reachedChain = []

        when:
        4.times {
            MockHttpServletRequest request = new MockHttpServletRequest('GET', '/api/v1/auth/login')
            request.remoteAddr = '10.0.0.1'
            filter.doFilter(request, new MockHttpServletResponse(), chainReturning(401, reachedChain))
        }

        then:
        reachedChain.size() == 4
    }

    void 'X-Forwarded-For is ignored unless proxies are trusted'() {
        given: 'four attempts from one address, each claiming a different forwarded client'
        4.times { int i ->
            MockHttpServletRequest request = loginRequest('admin')
            request.addHeader('X-Forwarded-For', "203.0.113.${i}")
            filter.doFilter(request, new MockHttpServletResponse(), chainReturning(401))
        }
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        filter.doFilter(loginRequest('admin'), response, chainReturning(401))

        then: 'the real remote address still accumulated them'
        response.status == 429
    }
}
