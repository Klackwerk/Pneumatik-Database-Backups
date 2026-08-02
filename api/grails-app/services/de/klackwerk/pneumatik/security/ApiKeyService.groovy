package de.klackwerk.pneumatik.security

import de.klackwerk.pneumatik.inventory.Database
import grails.gorm.transactions.Transactional
import jakarta.servlet.http.HttpServletRequest
import org.apache.commons.lang3.RandomStringUtils

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * API key lifecycle and validation. Keys are stored as SHA-256 hashes
 * ("sha256:&lt;hex&gt;"); the plaintext exists only in the create response.
 */
@Transactional
class ApiKeyService {

    static final String HASH_PREFIX = 'sha256:'
    static final int KEY_HINT_LENGTH = 8

    def springSecurityService

    /**
     * Creates a new API key.
     * @return map with the saved ApiKey and the one-time plaintext key
     */
    Map createApiKey(ApiKeyCommand cmd) {
        String plainKey = generateApiKeyKey()

        ApiKey apiKey = new ApiKey()
        apiKey.key = hashKey(plainKey)
        apiKey.keyHint = plainKey.take(KEY_HINT_LENGTH)
        apiKey.comment = cmd.comment
        apiKey.createdAt = new Date()
        apiKey.validUntil = cmd.validUntil
        apiKey.createdBy = springSecurityService.getCurrentUser() as User
        // no ids means an unscoped key, which is what every key was before
        cmd.databaseIds?.each { String databaseId ->
            Database database = Database.get(databaseId)
            if (database) {
                apiKey.addToDatabases(database)
            }
        }
        apiKey.save()

        return [apiKey: apiKey, plainKey: plainKey]
    }

    /**
     * Resolves the X-API-Key header of a request to its key record.
     * Keys can be invalid if they are expired or unknown.
     *
     * @return the ApiKey, or null when the header is missing or unusable
     */
    ApiKey authenticate(HttpServletRequest request) {
        return authenticate(request.getHeader('X-API-Key'))
    }

    ApiKey authenticate(String plainKey) {
        if (!plainKey) {
            log.error 'Tried to authenticate with empty API Key'
            return null
        }

        ApiKey apiKey = ApiKey.findByKey(hashKey(plainKey))
        if (apiKey && apiKey.isValid) {
            apiKey.lastConnectedAt = new Date()
            apiKey.save(flush: true)
            return apiKey
        }
        return null
    }

    /** @return true if the key is known and unexpired */
    Boolean validateApiKey(HttpServletRequest request) {
        return authenticate(request) != null
    }

    Boolean validateApiKey(String plainKey) {
        return authenticate(plainKey) != null
    }

    /**
     * Deletes an API key; only the user who created it may delete it
     * (legacy behaviour, preserved).
     */
    Boolean deleteApiKey(String apiKeyId) {
        ApiKey apiKey = ApiKey.get(apiKeyId)
        if (!apiKey) {
            return false
        }

        if (apiKey.createdBy == springSecurityService.getCurrentUser()) {
            apiKey.delete()
            return true
        }

        return false
    }

    List<ApiKey> listApiKeysForCurrentUser() {
        User user = springSecurityService.getCurrentUser() as User
        return ApiKey.findAllByCreatedBy(user, [sort: 'createdAt'])
    }

    static String hashKey(String plainKey) {
        MessageDigest digest = MessageDigest.getInstance('SHA-256')
        byte[] hash = digest.digest(plainKey.getBytes('UTF-8'))
        return HASH_PREFIX + hash.encodeHex().toString()
    }

    /**
     * Generates a unique random 64-character key
     * @return String plaintext key
     */
    protected static String generateApiKeyKey() {
        String key = ''
        Boolean keyUnique = false
        SecureRandom sr = new SecureRandom()

        while (!keyUnique) {
            key = RandomStringUtils.random(64, 0, 0, true, true, null, sr)
            if (!ApiKey.findByKey(hashKey(key))) {
                keyUnique = true
            }
        }

        return key
    }
}
