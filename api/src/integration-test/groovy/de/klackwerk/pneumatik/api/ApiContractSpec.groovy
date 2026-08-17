package de.klackwerk.pneumatik.api

import grails.testing.mixin.integration.Integration
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Contract tests: envelope shape, status codes, validation errors,
 * pagination meta and auth behaviour — the things the frontend's typed
 * client relies on.
 */
@Integration
@Stepwise
class ApiContractSpec extends Specification {

    @Shared HttpClient http = HttpClient.newHttpClient()
    @Shared String token
    @Shared String hostId

    String base() { "http://localhost:${serverPort}" }

    private HttpResponse<String> get(String path, Map<String, String> headers = [:]) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
        headers.each { k, v -> builder.header(k, v) }
        http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    private HttpResponse<String> post(String path, Object body, Map<String, String> headers = [:]) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base() + path))
                .header('Content-Type', 'application/json')
        headers.each { k, v -> builder.header(k, v) }
        http.send(builder.POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body))).build(),
                HttpResponse.BodyHandlers.ofString())
    }

    private Map json(HttpResponse<String> response) {
        new JsonSlurper().parseText(response.body()) as Map
    }

    private Map<String, String> auth() {
        ['Authorization': "Bearer ${token}".toString()]
    }

    void 'unauthenticated requests are rejected with 401'() {
        expect:
        get('/api/v1/hosts').statusCode() == 401
    }

    void 'login with bad credentials returns 401'() {
        expect:
        post('/api/v1/auth/login', [username: 'admin', password: 'wrong']).statusCode() == 401
    }

    void 'login with valid credentials returns a JWT'() {
        when:
        HttpResponse<String> response = post('/api/v1/auth/login', [username: 'admin', password: 'admin'])
        Map body = json(response)

        then:
        response.statusCode() == 200
        body.access_token
        body.roles.contains('ROLE_ADMIN')

        cleanup:
        token = body.access_token
    }

    void 'invalid input yields 422 with the error envelope and field errors'() {
        when:
        HttpResponse<String> response = post('/api/v1/hosts', [friendlyName: 'no hostname'], auth())
        Map body = json(response)

        then:
        response.statusCode() == 422
        body.error.code == 'validation_failed'
        body.error.message
        body.error.fields.hostname*.code.contains('nullable')
    }

    void 'creating a resource returns 201, a Location header and the data envelope'() {
        when:
        HttpResponse<String> response = post('/api/v1/hosts',
                [friendlyName: 'Contract Host', hostname: 'contract.example.com', port: 3306], auth())
        Map body = json(response)

        then:
        response.statusCode() == 201
        response.headers().firstValue('Location').get().endsWith("/api/v1/hosts/${body.data.id}")
        body.data.hostname == 'contract.example.com'
        body.data.hasSshKey == false

        cleanup:
        hostId = body.data.id as String
    }

    void 'collections are wrapped in data with pagination meta'() {
        when:
        Map body = json(get('/api/v1/hosts?page=1&pageSize=1', auth()))

        then:
        body.data instanceof List
        body.meta.page == 1
        body.meta.pageSize == 1
        body.meta.total >= 1
    }

    void 'oversized pageSize is capped at the hard limit'() {
        when:
        Map body = json(get('/api/v1/hosts?pageSize=99999', auth()))

        then:
        body.meta.pageSize <= 200
    }

    void 'unknown sort fields are rejected with 400'() {
        when:
        HttpResponse<String> response = get('/api/v1/backups?sort=bogusField', auth())

        then:
        response.statusCode() == 400
        json(response).error.code == 'invalid_parameter'
    }

    void 'unknown resources return 404 with the error envelope'() {
        when:
        HttpResponse<String> response = get('/api/v1/hosts/00000000-0000-0000-0000-000000000000', auth())

        then:
        response.statusCode() == 404
        json(response).error.code == 'not_found'
    }

    void 'machine endpoint rejects a bad api key with 401'() {
        when:
        HttpResponse<String> response = post('/api/v1/backup/create/00000000-0000-0000-0000-000000000000', [:],
                ['X-API-Key': 'bogus'])

        then:
        response.statusCode() == 401
        json(response).error.code == 'invalid_api_key'
    }

    void 'the OpenAPI contract is served'() {
        when:
        HttpResponse<String> response = get('/api/v1/openapi.yaml')

        then:
        response.statusCode() == 200
        response.body().contains('openapi: 3.0.3')
    }
}
