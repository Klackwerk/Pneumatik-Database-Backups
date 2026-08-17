import de.klackwerk.pneumatik.backup.DownloadTokenService
import de.klackwerk.pneumatik.config.FlywayMigrator
import de.klackwerk.pneumatik.credentials.FileKeyProvider
import de.klackwerk.pneumatik.security.LoginThrottleFilter
import de.klackwerk.pneumatik.security.PneumatikAccessTokenJsonRenderer
import de.klackwerk.pneumatik.security.UserPasswordEncoderListener
import de.klackwerk.pneumatik.storage.MultipartUploader
import de.klackwerk.pneumatik.web.SecurityHeadersFilter
import de.klackwerk.pneumatik.web.SpaResourceConfig
import org.grails.gsp.GroovyPagesTemplateEngine
import org.springframework.boot.autoconfigure.security.SecurityProperties
import org.springframework.boot.web.servlet.FilterRegistrationBean

beans = {

    // The swagger-ui webjar is served by the servlet container (it is a jar in
    // WEB-INF/lib). The bundled React build is not — see SpaResourceConfig.
    spaResourceConfig(SpaResourceConfig)

    // The mail plugin's auto-configuration requires this bean even when no
    // view-based mails are sent (rest-api profile has no GSP support).
    groovyPagesTemplateEngine(GroovyPagesTemplateEngine)
    groovyPagesUriService(org.grails.web.pages.DefaultGroovyPagesUriService)

    // Schema migrations. Spring Boot's FlywayAutoConfiguration is gated on
    // @ConditionalOnBean(DataSource) and Grails registers `dataSource` after
    // those conditions are evaluated, so it never activates — see FlywayMigrator.
    flywayMigrator(FlywayMigrator) {
        dataSource = ref('dataSource')
        enabled = '${spring.flyway.enabled:true}'
        locations = '${spring.flyway.locations:classpath:db/migration/{vendor}}'
    }

    keyProvider(FileKeyProvider,
            '${pneumatik.credentials.key-file:}',
            '${pneumatik.credentials.key:}')

    userPasswordEncoderListener(UserPasswordEncoderListener)

    // Single-use tickets so the browser can stream a download through a
    // plain link instead of buffering the archive in memory.
    downloadTokenService(DownloadTokenService) {
        ttlSeconds = '${pneumatik.security.download-ticket-seconds:60}'
    }

    // Overrides the plugin's renderer, whose "refresh token" is a JWT with
    // no expiration — see PneumatikAccessTokenJsonRenderer.
    accessTokenJsonRenderer(PneumatikAccessTokenJsonRenderer) {
        refreshTokenService = ref('refreshTokenService')
    }

    // Login back-off. Registered ahead of the Spring Security chain so a
    // locked-out caller is turned away before any password is checked.
    loginThrottleFilter(LoginThrottleFilter) {
        maxAttempts = '${pneumatik.security.login-throttle.max-attempts:5}'
        windowMinutes = '${pneumatik.security.login-throttle.window-minutes:15}'
        lockMinutes = '${pneumatik.security.login-throttle.lock-minutes:15}'
        trustForwardedFor = '${pneumatik.security.login-throttle.trust-forwarded-for:false}'
    }

    loginThrottleFilterRegistration(FilterRegistrationBean) {
        filter = ref('loginThrottleFilter')
        urlPatterns = ['/api/v1/auth/login']
        order = SecurityProperties.DEFAULT_FILTER_ORDER - 10
    }

    securityHeadersFilter(SecurityHeadersFilter) {
        contentSecurityPolicy = '${pneumatik.security.content-security-policy:' +
                SecurityHeadersFilter.DEFAULT_CONTENT_SECURITY_POLICY + '}'
        hstsMaxAgeSeconds = '${pneumatik.security.hsts-max-age-seconds:31536000}'
        trustForwardedProto = '${pneumatik.security.login-throttle.trust-forwarded-for:false}'
    }

    securityHeadersFilterRegistration(FilterRegistrationBean) {
        filter = ref('securityHeadersFilter')
        urlPatterns = ['/*']
        order = SecurityProperties.DEFAULT_FILTER_ORDER - 20
    }

    // Streams S3 uploads in parts: a single PUT caps out at 5 GB and starts
    // over from zero on a dropped connection.
    multipartUploader(MultipartUploader) {
        multipartThreshold = '${pneumatik.storage.s3.multipart-threshold-bytes:16777216}'
        partSize = '${pneumatik.storage.s3.part-size-bytes:16777216}'
    }
}
