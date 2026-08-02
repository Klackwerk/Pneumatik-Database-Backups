package de.klackwerk.pneumatik.security

import grails.testing.mixin.integration.Integration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.ApplicationContext
import spock.lang.Specification

/**
 * The throttle's behaviour is covered by LoginThrottleFilterSpec. What can
 * only break in a running application is the registration: a wrong URL
 * pattern or an order behind Spring Security would disable it silently.
 */
@Integration
class LoginThrottleWiringSpec extends Specification {

    @Autowired
    ApplicationContext applicationContext

    void 'the throttle is registered on the login endpoint, ahead of the security chain'() {
        given:
        FilterRegistrationBean registration = applicationContext
                .getBean('loginThrottleFilterRegistration', FilterRegistrationBean)

        expect:
        registration.filter instanceof LoginThrottleFilter
        registration.urlPatterns.contains('/api/v1/auth/login')
        registration.order < SecurityProperties.DEFAULT_FILTER_ORDER
    }

    void 'the throttle reads its limits from configuration'() {
        given:
        LoginThrottleFilter filter = applicationContext.getBean('loginThrottleFilter', LoginThrottleFilter)

        expect: 'placeholders resolved to the documented defaults, not to zero'
        filter.maxAttempts == 5
        filter.windowMinutes == 15
        filter.lockMinutes == 15
        !filter.trustForwardedFor
    }
}
