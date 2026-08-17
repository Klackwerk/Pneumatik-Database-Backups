package de.klackwerk.pneumatik.api

import grails.validation.Validateable
import groovy.json.JsonOutput
import org.springframework.context.MessageSource
import org.springframework.validation.Errors
import org.springframework.validation.FieldError

/**
 * Base class for all API controllers. Defines the one response envelope
 * used everywhere:
 *
 * Success: {"data": ..., "meta": {...}?}
 * Error:   {"error": {"code": "...", "message": "...", "fields": {field: [{code, message}]}?}}
 */
abstract class BaseApiController {

    static responseFormats = ['json']

    MessageSource messageSource

    /** absolute URL for a Location header, e.g. locationOf("/api/v1/hosts/1") */
    protected String locationOf(String uri) {
        int port = request.serverPort
        boolean defaultPort = (request.scheme == 'http' && port == 80) || (request.scheme == 'https' && port == 443)
        return "${request.scheme}://${request.serverName}${defaultPort ? '' : ':' + port}${uri}"
    }

    protected void respondData(Object data, Map meta = null, int status = 200) {
        Map payload = [data: data]
        if (meta != null) {
            payload.meta = meta
        }
        renderJson(payload, status)
    }

    protected void respondCreated(Object data, String location) {
        response.setHeader('Location', location)
        respondData(data, null, 201)
    }

    protected void respondNoContent() {
        render(status: 204)
    }

    protected void respondError(int status, String code, String message, Map fields = null) {
        Map error = [code: code, message: message]
        if (fields) {
            error.fields = fields
        }
        renderJson([error: error], status)
    }

    protected void respondNotFound(String resource = 'Resource') {
        respondError(404, 'not_found', "${resource} not found")
    }

    protected void respondValidationErrors(Object validateable) {
        Errors errors = (validateable instanceof Validateable || validateable?.hasProperty('errors'))
                ? validateable.errors as Errors : validateable as Errors
        Map<String, List<Map>> fields = [:].withDefault { [] }
        errors.fieldErrors.each { FieldError fieldError ->
            String message
            try {
                message = messageSource.getMessage(fieldError, request.locale)
            } catch (Exception ignored) {
                message = fieldError.codes ? fieldError.codes.last() : 'invalid'
            }
            fields[fieldError.field] << [code: shortCode(fieldError), message: message]
        }
        respondError(422, 'validation_failed', 'Validation failed', fields)
    }

    /**
     * Extracts a stable short code from a field error, e.g.
     * "hostCommand.friendlyName.unique" -> "unique".
     */
    private static String shortCode(FieldError fieldError) {
        String code = fieldError.code ?: 'invalid'
        // Grails codes like "nullable", "blank", "minSize.notmet" pass through
        return code
    }

    protected void renderJson(Map payload, int status = 200) {
        response.status = status
        render(contentType: 'application/json;charset=UTF-8', text: JsonOutput.toJson(payload))
    }

    /**
     * Pagination parameters with hard limits. Returns [offset, max, page, pageSize].
     */
    protected Map pagination(int defaultSize = 25, int maxSize = 100) {
        int page = (params.int('page') ?: 1)
        if (page < 1) page = 1
        int pageSize = (params.int('pageSize') ?: defaultSize)
        if (pageSize < 1) pageSize = defaultSize
        if (pageSize > maxSize) pageSize = maxSize
        return [offset: (page - 1) * pageSize, max: pageSize, page: page, pageSize: pageSize]
    }

    protected Map paginationMeta(Map pagination, long total, Long filtered = null) {
        Map meta = [page: pagination.page, pageSize: pagination.pageSize, total: total]
        if (filtered != null) {
            meta.filtered = filtered
        }
        return meta
    }

    def handleException(Exception e) {
        log.error "Unhandled exception in ${controllerName}.${actionName}", e
        respondError(500, 'internal_error', 'An unexpected error occurred')
    }
}
