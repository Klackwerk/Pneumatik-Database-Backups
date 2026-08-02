package de.klackwerk.pneumatik.web

import groovy.transform.CompileStatic
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Serves the bundled React build (app/dist, copied into
 * classpath:/META-INF/resources by the Docker image).
 *
 * Nothing does this for us:
 *
 *  - the servlet container only serves {@code META-INF/resources} out of JARs
 *    in {@code WEB-INF/lib}, and the frontend lands in
 *    {@code WEB-INF/classes/META-INF/resources}. (This is why the swagger-ui
 *    webjar resolves and the bundled assets did not.)
 *  - Spring Boot's WebMvcAutoConfiguration, which would otherwise map
 *    {@code /**} onto {@code classpath:/META-INF/resources/}, is
 *    {@code @ConditionalOnMissingBean(WebMvcConfigurationSupport)} and backs
 *    off because Grails contributes its own MVC configuration.
 *
 * Without this configurer every {@code /assets/*} request missed all handlers,
 * fell through to the 404 mapping and came back as the SPA shell with content
 * type text/html — which the browser refuses to execute as a module script,
 * leaving a blank page.
 */
@CompileStatic
class SpaResourceConfig implements WebMvcConfigurer {

    private static final String BUNDLE_ROOT = 'classpath:/META-INF/resources/'

    /** Vite emits content-hashed filenames under /assets, so these are immutable. */
    private static final int ASSET_CACHE_SECONDS = 31_536_000

    @Override
    void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler('/assets/**')
                .addResourceLocations(BUNDLE_ROOT + 'assets/')
                .setCachePeriod(ASSET_CACHE_SECONDS)

        // Root-level files the shell references (favicon.svg, icons.svg, ...).
        // Not content-hashed, so they keep the default (no-cache) headers.
        // These patterns mirror the permitAll staticRules in application.groovy:
        // rejectIfNoRule is on, so anything not listed there is denied anyway.
        registry.addResourceHandler('/*.svg', '/*.png', '/*.ico', '/*.txt')
                .addResourceLocations(BUNDLE_ROOT)
    }
}
