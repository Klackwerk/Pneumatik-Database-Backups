package de.klackwerk.pneumatik.migration

import de.klackwerk.pneumatik.credentials.CredentialService
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.security.ApiKey
import de.klackwerk.pneumatik.security.ApiKeyService
import grails.gorm.transactions.Transactional

/**
 * One-time (idempotent) data upgrades from the Grails 4 app, run at startup:
 *
 *  1. Re-encrypt Database.password and Host.sshKey from the legacy zero-IV
 *     AES-CBC format to v1 AES-GCM. Requires pneumatik.credentials.legacy.*
 *     to be configured while legacy values exist.
 *  2. Hash plaintext ApiKey.key values (sha256:...) and fill keyHint.
 *
 * Values already in the new formats are skipped, so running this on every
 * startup is safe.
 */
@Transactional
class DataMigrationService {

    CredentialService credentialService

    void migrate() {
        int reencrypted = reencryptLegacyCredentials()
        int hashed = hashPlaintextApiKeys()
        if (reencrypted || hashed) {
            log.info "DATAMIGRATION - Re-encrypted ${reencrypted} credentials, hashed ${hashed} API keys"
        }
    }

    protected int reencryptLegacyCredentials() {
        int count = 0

        Database.list().each { Database database ->
            if (credentialService.isLegacyCiphertext(database.password)) {
                String plaintext = credentialService.decryptString(database.password)
                if (plaintext == null) {
                    throw new IllegalStateException(
                            "Could not decrypt legacy password of database '${database.name}' (id ${database.id}). " +
                            'Check pneumatik.credentials.legacy.secret/salt.')
                }
                database.password = credentialService.encryptString(plaintext)
                database.save(flush: true)
                count++
            }
        }

        Host.list().each { Host host ->
            if (credentialService.isLegacyCiphertext(host.sshKey)) {
                String plaintext = credentialService.decryptString(host.sshKey)
                if (plaintext == null) {
                    throw new IllegalStateException(
                            "Could not decrypt legacy SSH key of host '${host.name}' (id ${host.id}). " +
                            'Check pneumatik.credentials.legacy.secret/salt.')
                }
                host.sshKey = credentialService.encryptString(plaintext)
                host.save(flush: true)
                count++
            }
        }

        return count
    }

    protected int hashPlaintextApiKeys() {
        int count = 0
        ApiKey.list().each { ApiKey apiKey ->
            if (!apiKey.key.startsWith(ApiKeyService.HASH_PREFIX)) {
                apiKey.keyHint = apiKey.key.take(ApiKeyService.KEY_HINT_LENGTH)
                apiKey.key = ApiKeyService.hashKey(apiKey.key)
                apiKey.save(flush: true)
                count++
            }
        }
        return count
    }
}
