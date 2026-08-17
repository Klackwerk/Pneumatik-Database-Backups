package de.klackwerk.pneumatik.api

import grails.testing.mixin.integration.Integration
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The endpoints added in 3.1: session renewal, inventory deletion, backup
 * filters and download tickets.
 */
@Integration
class SessionAndInventoryApiSpec extends Specification {

    @Shared HttpClient http = HttpClient.newHttpClient()

    String base() { "http://localhost:${serverPort}" }

    private HttpResponse<String> send(String method, String path, Object body = null, Map<String, String> headers = [:]) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header('Content-Type', 'application/json')
        headers.each { k, v -> builder.header(k, v) }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body))
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    private Map json(HttpResponse<String> response) {
        return new JsonSlurper().parseText(response.body()) as Map
    }

    private Map login() {
        return json(send('POST', '/api/v1/auth/login', [username: 'admin', password: 'admin']))
    }

    private Map<String, String> auth(String token) {
        return ['Authorization': "Bearer ${token}".toString()]
    }

    // --- session ---------------------------------------------------------

    void 'login issues an opaque refresh token, not a second JWT'() {
        when:
        Map body = login()

        then:
        body.access_token
        body.expires_in
        body.refresh_token

        and: 'the plugin default would be a three-part JWT that never expires'
        !body.refresh_token.contains('.')
        body.refresh_token ==~ /[A-Za-z0-9]{64}/
    }

    void 'a refresh token buys a new access token and is rotated in the process'() {
        given:
        String refreshToken = login().refresh_token

        when:
        HttpResponse<String> response = send('POST', '/api/v1/auth/refresh', [refresh_token: refreshToken])
        Map body = json(response)

        then:
        response.statusCode() == 200
        body.access_token
        body.refresh_token != refreshToken
        body.roles.contains('ROLE_ADMIN')

        and: 'the new access token is accepted'
        send('GET', '/api/v1/hosts', null, auth(body.access_token as String)).statusCode() == 200

        and: 'the presented refresh token is spent'
        send('POST', '/api/v1/auth/refresh', [refresh_token: refreshToken]).statusCode() == 401
    }

    void 'refreshing without a usable token is refused'() {
        expect:
        send('POST', '/api/v1/auth/refresh', [refresh_token: 'a' * 64]).statusCode() == 401
        send('POST', '/api/v1/auth/refresh', [:]).statusCode() == 400
    }

    void 'logging out stops the session being renewed'() {
        given:
        String refreshToken = login().refresh_token

        when:
        HttpResponse<String> response = send('POST', '/api/v1/auth/logout', [refresh_token: refreshToken])

        then:
        response.statusCode() == 204
        send('POST', '/api/v1/auth/refresh', [refresh_token: refreshToken]).statusCode() == 401
    }

    // --- inventory deletion ----------------------------------------------

    void 'deleting a database takes its backups with it'() {
        given:
        String token = login().access_token
        String hostId = json(send('POST', '/api/v1/hosts',
                [hostname: 'delete-me.example.com', friendlyName: 'Delete me', port: 3306],
                auth(token))).data.id
        String databaseId = json(send('POST', '/api/v1/databases',
                [databaseName: 'doomed', hostId: hostId, storageProvider: 'DIRECT', trigger: 'TRIGGER_MANUAL'],
                auth(token))).data.id
        send('POST', "/api/v1/databases/${databaseId}/backups", null, auth(token))

        expect: 'the listing states what a delete would destroy'
        json(send('GET', "/api/v1/databases/${databaseId}", null, auth(token))).data.backupCount == 1

        when:
        HttpResponse<String> response = send('DELETE', "/api/v1/databases/${databaseId}", null, auth(token))

        then:
        response.statusCode() == 204
        send('GET', "/api/v1/databases/${databaseId}", null, auth(token)).statusCode() == 404

        and: 'its backups are gone from the listing too'
        json(send('GET', "/api/v1/backups?databaseId=${databaseId}", null, auth(token))).meta.filtered == 0

        cleanup:
        send('DELETE', "/api/v1/hosts/${hostId}", null, auth(token))
    }

    void 'deleting a host reports the databases it would take, then takes them'() {
        given:
        String token = login().access_token
        String hostId = json(send('POST', '/api/v1/hosts',
                [hostname: 'cascade.example.com', friendlyName: 'Cascade', port: 3306],
                auth(token))).data.id
        json(send('POST', '/api/v1/databases',
                [databaseName: 'cascaded', hostId: hostId, storageProvider: 'DIRECT', trigger: 'TRIGGER_MANUAL'],
                auth(token))).data.id

        expect: 'the warning the UI shows is server-provided, not guessed'
        Map host = json(send('GET', "/api/v1/hosts/${hostId}", null, auth(token))).data
        host.databaseCount == 1
        host.databaseNames == ['cascaded']

        when:
        HttpResponse<String> response = send('DELETE', "/api/v1/hosts/${hostId}", null, auth(token))

        then:
        response.statusCode() == 204
        send('GET', "/api/v1/hosts/${hostId}", null, auth(token)).statusCode() == 404
    }

    void 'deleting an unknown host or database is a 404, not a silent success'() {
        given:
        String token = login().access_token
        String missing = '00000000-0000-0000-0000-000000000000'

        expect:
        send('DELETE', "/api/v1/hosts/${missing}", null, auth(token)).statusCode() == 404
        send('DELETE', "/api/v1/databases/${missing}", null, auth(token)).statusCode() == 404
    }

    // --- backup filters ---------------------------------------------------

    void 'backups can be filtered by state, database and host'() {
        given:
        String token = login().access_token
        String hostId = json(send('POST', '/api/v1/hosts',
                [hostname: 'filter.example.com', friendlyName: 'Filter host', port: 3306],
                auth(token))).data.id
        String databaseId = json(send('POST', '/api/v1/databases',
                [databaseName: 'filtered', hostId: hostId, storageProvider: 'DIRECT', trigger: 'TRIGGER_MANUAL'],
                auth(token))).data.id
        send('POST', "/api/v1/databases/${databaseId}/backups", null, auth(token))

        expect: 'the queued backup is found by each filter'
        json(send('GET', "/api/v1/backups?databaseId=${databaseId}", null, auth(token))).meta.filtered == 1
        json(send('GET', "/api/v1/backups?hostId=${hostId}", null, auth(token))).meta.filtered == 1
        json(send('GET', "/api/v1/backups?state=CREATED&hostId=${hostId}", null, auth(token))).meta.filtered == 1

        and: 'and excluded by a state it is not in'
        json(send('GET', "/api/v1/backups?state=FINISHED&hostId=${hostId}", null, auth(token))).meta.filtered == 0

        cleanup:
        send('DELETE', "/api/v1/databases/${databaseId}", null, auth(token))
        send('DELETE', "/api/v1/hosts/${hostId}", null, auth(token))
    }

    void 'an unknown backup state is rejected rather than ignored'() {
        given:
        String token = login().access_token

        when:
        HttpResponse<String> response = send('GET', '/api/v1/backups?state=NONSENSE', null, auth(token))

        then: 'silently returning everything would look like "no failures"'
        response.statusCode() == 400
        json(response).error.code == 'invalid_parameter'
    }

    // --- API key scoping ---------------------------------------------------

    void 'a scoped API key can only back up the databases it was given'() {
        given:
        String token = login().access_token
        String hostId = json(send('POST', '/api/v1/hosts',
                [hostname: 'scoped.example.com', friendlyName: 'Scoped host', port: 3306],
                auth(token))).data.id
        String allowedId = json(send('POST', '/api/v1/databases',
                [databaseName: 'allowed', hostId: hostId, storageProvider: 'DIRECT', trigger: 'TRIGGER_MANUAL'],
                auth(token))).data.id
        String forbiddenId = json(send('POST', '/api/v1/databases',
                [databaseName: 'forbidden', hostId: hostId, storageProvider: 'DIRECT', trigger: 'TRIGGER_MANUAL'],
                auth(token))).data.id

        Map created = json(send('POST', '/api/v1/api-keys',
                [comment: 'scoped key', databaseIds: [allowedId]], auth(token))).data
        String key = created.key

        expect: 'the listing shows what the key is limited to'
        created.databaseNames == ['allowed']

        and: 'the database it was given works'
        send('POST', "/api/v1/backup/create/${allowedId}", null, ['X-API-Key': key]).statusCode() == 200

        and: 'the other one is indistinguishable from a database that does not exist'
        send('POST', "/api/v1/backup/create/${forbiddenId}", null, ['X-API-Key': key]).statusCode() == 404

        cleanup:
        send('DELETE', "/api/v1/api-keys/${created.id}", null, auth(token))
        send('DELETE', "/api/v1/hosts/${hostId}", null, auth(token))
    }

    void 'an unscoped API key still reaches every database'() {
        given: 'the behaviour every key had before scoping existed'
        String token = login().access_token
        String hostId = json(send('POST', '/api/v1/hosts',
                [hostname: 'unscoped.example.com', friendlyName: 'Unscoped host', port: 3306],
                auth(token))).data.id
        String databaseId = json(send('POST', '/api/v1/databases',
                [databaseName: 'anything', hostId: hostId, storageProvider: 'DIRECT', trigger: 'TRIGGER_MANUAL'],
                auth(token))).data.id

        Map created = json(send('POST', '/api/v1/api-keys', [comment: 'unscoped key'], auth(token))).data

        expect:
        created.databaseIds == []
        send('POST', "/api/v1/backup/create/${databaseId}", null, ['X-API-Key': created.key]).statusCode() == 200

        cleanup:
        send('DELETE', "/api/v1/api-keys/${created.id}", null, auth(token))
        send('DELETE', "/api/v1/hosts/${hostId}", null, auth(token))
    }

    // --- security headers ---------------------------------------------------

    void 'responses carry a content security policy and the docs page gets its own'() {
        when:
        HttpResponse<String> spa = send('GET', '/login')

        then: 'the SPA loads only its own bundle — no inline scripts allowed'
        spa.headers().firstValue('Content-Security-Policy').get().contains("script-src 'self'")
        spa.headers().firstValue('X-Content-Type-Options').get() == 'nosniff'
        spa.headers().firstValue('X-Frame-Options').get() == 'DENY'

        when: 'Swagger UI, which needs one inline script to boot'
        HttpResponse<String> docs = send('GET', '/api/v1/docs')
        String policy = docs.headers().firstValue('Content-Security-Policy').get()

        then: 'it carries a nonce for exactly that script instead of relaxing the policy everywhere'
        policy.contains("script-src 'self' 'nonce-")
        docs.body().contains('<script nonce="')
    }

    // --- download tickets --------------------------------------------------

    void 'the download endpoint refuses a request with neither ticket nor token'() {
        given:
        String missing = '00000000-0000-0000-0000-000000000000'

        expect:
        send('GET', "/api/v1/backups/${missing}/download").statusCode() == 401
        send('GET', "/api/v1/backups/${missing}/download?token=made-up").statusCode() == 401
    }

    void 'a ticket cannot be minted for a backup that has no stored file'() {
        given:
        String token = login().access_token
        String hostId = json(send('POST', '/api/v1/hosts',
                [hostname: 'ticket.example.com', friendlyName: 'Ticket host', port: 3306],
                auth(token))).data.id
        String databaseId = json(send('POST', '/api/v1/databases',
                [databaseName: 'unticketed', hostId: hostId, storageProvider: 'DIRECT', trigger: 'TRIGGER_MANUAL'],
                auth(token))).data.id
        send('POST', "/api/v1/databases/${databaseId}/backups", null, auth(token))
        String backupId = json(send('GET', "/api/v1/backups?databaseId=${databaseId}", null, auth(token))).data[0].id

        when: 'the backup is only queued, so there is nothing to download yet'
        HttpResponse<String> response = send('POST', "/api/v1/backups/${backupId}/download-token", null, auth(token))

        then:
        response.statusCode() == 409
        json(response).error.code == 'not_downloadable'

        cleanup:
        send('DELETE', "/api/v1/databases/${databaseId}", null, auth(token))
        send('DELETE', "/api/v1/hosts/${hostId}", null, auth(token))
    }
}
