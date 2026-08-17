// CORS — explicit origin allowlist, no wildcard in production.
// PNEUMATIK_CORS_ORIGINS takes a comma-separated list of origins.
grails.cors.enabled = true
grails.cors.allowedOrigins = (System.getenv('PNEUMATIK_CORS_ORIGINS') ?: 'http://localhost:5173')
        .split(',')*.trim().findAll()
grails.cors.allowedHeaders = ['Content-Type', 'Authorization', 'X-API-Key']
grails.cors.allowCredentials = false

// Spring Security core
grails.plugin.springsecurity.userLookup.userDomainClassName = 'de.klackwerk.pneumatik.security.User'
grails.plugin.springsecurity.userLookup.authorityJoinClassName = 'de.klackwerk.pneumatik.security.UserRole'
grails.plugin.springsecurity.authority.className = 'de.klackwerk.pneumatik.security.Role'

grails.plugin.springsecurity.roleHierarchy = '''
   ROLE_ADMIN > ROLE_ANONYMOUS
'''

// Deny anything that has no explicit rule
grails.plugin.springsecurity.rejectIfNoRule = true
grails.plugin.springsecurity.fii.rejectPublicInvocations = false

// Spring Security REST (stateless JWT)
grails.plugin.springsecurity.rest.login.endpointUrl = '/api/v1/auth/login'
grails.plugin.springsecurity.rest.login.failureStatusCode = 401
grails.plugin.springsecurity.rest.login.useJsonCredentials = true
grails.plugin.springsecurity.rest.token.validation.enableAnonymousAccess = true
// Production must supply its own signing secret — see JwtSecretPolicy.
grails.plugin.springsecurity.rest.token.storage.jwt.secret =
        de.klackwerk.pneumatik.security.JwtSecretPolicy.resolve(
                System.getenv('PNEUMATIK_JWT_SECRET'),
                grails.util.Environment.current == grails.util.Environment.PRODUCTION)
grails.plugin.springsecurity.rest.token.storage.jwt.expiration = 3600

grails.plugin.springsecurity.controllerAnnotations.staticRules = [
        [pattern: '/api/v1/auth/**',   access: ['permitAll']],
        [pattern: '/api/v1/openapi.yaml', access: ['permitAll']],
        [pattern: '/api/v1/docs',      access: ['permitAll']],
        [pattern: '/api/v1/docs/**',   access: ['permitAll']],
        [pattern: '/actuator/health',  access: ['permitAll']],
        [pattern: '/error',            access: ['permitAll']],
        // single-page app: shell, client-side routes, and static assets.
        // Auth happens client-side against /api — serving the shell is public.
        [pattern: '/',                 access: ['permitAll']],
        [pattern: '/index.html',       access: ['permitAll']],
        [pattern: '/login',            access: ['permitAll']],
        [pattern: '/backups',          access: ['permitAll']],
        [pattern: '/databases',        access: ['permitAll']],
        [pattern: '/hosts',            access: ['permitAll']],
        [pattern: '/api-keys',         access: ['permitAll']],
        [pattern: '/users',            access: ['permitAll']],
        [pattern: '/settings/**',      access: ['permitAll']],
        [pattern: '/assets/**',        access: ['permitAll']],
        [pattern: '/webjars/**',       access: ['permitAll']],
        [pattern: '/*.svg',            access: ['permitAll']],
        [pattern: '/*.ico',            access: ['permitAll']],
        [pattern: '/*.png',            access: ['permitAll']],
        [pattern: '/*.txt',            access: ['permitAll']],
]

grails.plugin.springsecurity.filterChain.chainMap = [
        // Stateless chain for the whole API
        [
                pattern: '/api/**',
                filters: 'JOINED_FILTERS,-exceptionTranslationFilter,-authenticationProcessingFilter,-securityContextPersistenceFilter,-rememberMeAuthenticationFilter'
        ],
        // Everything else (actuator, error pages) — no REST token filters
        [
                pattern: '/**',
                filters: 'JOINED_FILTERS,-restTokenValidationFilter,-restExceptionTranslationFilter'
        ]
]
