package de.klackwerk.pneumatik

class UrlMappings {

    static mappings = {

        // --- auth ---------------------------------------------------------
        // POST /api/v1/auth/login is provided by spring-security-rest
        // (configured via grails.plugin.springsecurity.rest.login.endpointUrl)
        post   "/api/v1/auth/refresh"(controller: 'auth', namespace: 'v1', action: 'refresh')
        post   "/api/v1/auth/logout"(controller: 'auth', namespace: 'v1', action: 'logout')

        // --- hosts --------------------------------------------------------
        get    "/api/v1/hosts"(controller: 'hosts', namespace: 'v1', action: 'index')
        post   "/api/v1/hosts"(controller: 'hosts', namespace: 'v1', action: 'save')
        get    "/api/v1/hosts/$id"(controller: 'hosts', namespace: 'v1', action: 'show') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }
        put    "/api/v1/hosts/$id"(controller: 'hosts', namespace: 'v1', action: 'update') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }
        delete "/api/v1/hosts/$id"(controller: 'hosts', namespace: 'v1', action: 'delete') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }

        // --- databases ----------------------------------------------------
        get    "/api/v1/databases"(controller: 'databases', namespace: 'v1', action: 'index')
        post   "/api/v1/databases"(controller: 'databases', namespace: 'v1', action: 'save')
        get    "/api/v1/databases/$id"(controller: 'databases', namespace: 'v1', action: 'show') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }
        put    "/api/v1/databases/$id"(controller: 'databases', namespace: 'v1', action: 'update') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }
        delete "/api/v1/databases/$id"(controller: 'databases', namespace: 'v1', action: 'delete') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }
        post   "/api/v1/databases/$id/backups"(controller: 'databases', namespace: 'v1', action: 'triggerBackup') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }

        // --- retention policy (singleton sub-resource of a database) -------
        get    "/api/v1/databases/$databaseId/retention-policy"(controller: 'retentionPolicies', namespace: 'v1', action: 'show') {
            constraints { databaseId matches: /[0-9a-fA-F-]{36}/ }
        }
        put    "/api/v1/databases/$databaseId/retention-policy"(controller: 'retentionPolicies', namespace: 'v1', action: 'update') {
            constraints { databaseId matches: /[0-9a-fA-F-]{36}/ }
        }
        delete "/api/v1/databases/$databaseId/retention-policy"(controller: 'retentionPolicies', namespace: 'v1', action: 'delete') {
            constraints { databaseId matches: /[0-9a-fA-F-]{36}/ }
        }

        // --- backups ------------------------------------------------------
        get    "/api/v1/backups"(controller: 'backups', namespace: 'v1', action: 'index')
        get    "/api/v1/backups/$id"(controller: 'backups', namespace: 'v1', action: 'show') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }
        get    "/api/v1/backups/$id/download"(controller: 'backups', namespace: 'v1', action: 'download') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }
        post   "/api/v1/backups/$id/download-token"(controller: 'backups', namespace: 'v1', action: 'downloadToken') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }
        delete "/api/v1/backups/$id"(controller: 'backups', namespace: 'v1', action: 'delete') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }

        // --- stats ----------------------------------------------------------
        get    "/api/v1/stats/dashboard"(controller: 'stats', namespace: 'v1', action: 'dashboard')

        // --- api keys -----------------------------------------------------
        get    "/api/v1/api-keys"(controller: 'apiKeys', namespace: 'v1', action: 'index')
        post   "/api/v1/api-keys"(controller: 'apiKeys', namespace: 'v1', action: 'save')
        delete "/api/v1/api-keys/$id"(controller: 'apiKeys', namespace: 'v1', action: 'delete') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }

        // --- users --------------------------------------------------------
        get    "/api/v1/users/me"(controller: 'users', namespace: 'v1', action: 'me')
        put    "/api/v1/users/me/password"(controller: 'users', namespace: 'v1', action: 'changePassword')
        get    "/api/v1/users"(controller: 'users', namespace: 'v1', action: 'index')
        post   "/api/v1/users"(controller: 'users', namespace: 'v1', action: 'save')
        get    "/api/v1/users/$id"(controller: 'users', namespace: 'v1', action: 'show') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }
        put    "/api/v1/users/$id"(controller: 'users', namespace: 'v1', action: 'update') {
            constraints { id matches: /[0-9a-fA-F-]{36}/ }
        }

        // --- machine-to-machine (X-API-Key), legacy path kept verbatim -----
        post   "/api/v1/backup/create/$databaseId"(controller: 'machineBackup', namespace: 'v1', action: 'create') {
            constraints { databaseId matches: /[0-9a-fA-F-]{36}/ }
        }

        // --- contract -----------------------------------------------------
        get    "/api/v1/openapi.yaml"(controller: 'openapi', namespace: 'v1', action: 'spec')
        get    "/api/v1/docs"(controller: 'docs', namespace: 'v1', action: 'index')

        // --- single-page app (bundled frontend) -----------------------------
        get    "/"(controller: 'spa', action: 'index')

        // --- framework error pages ----------------------------------------
        "500"(controller: 'apiFallback', action: 'serverError')
        "404"(controller: 'apiFallback', action: 'notFound')
        "405"(controller: 'apiFallback', action: 'notFound')
    }
}
