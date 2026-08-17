package de.klackwerk.pneumatik.credentials

import grails.core.GrailsApplication

/**
 * Encrypts/decrypts sensitive values (target DB passwords, SSH keys).
 *
 * New values are always written as v1 AES-256-GCM ciphertexts (random IV,
 * key from the KeyProvider). Values without the v1 prefix are legacy
 * zero-IV AES-CBC ciphertexts from the Grails 4 app; they can still be
 * decrypted (for the startup re-encryption migration) as long as
 * pneumatik.credentials.legacy.secret/salt are configured.
 */
class CredentialService {

    KeyProvider keyProvider
    GrailsApplication grailsApplication

    /**
     * Encrypts a given String, returns null on failure (legacy contract).
     */
    String encryptString(String unencryptedString) {
        if (unencryptedString == null) {
            return null
        }
        try {
            return AesGcmCipher.encrypt(unencryptedString, keyProvider.key)
        } catch (Exception e) {
            log.error 'Error while encrypting', e
            return null
        }
    }

    /**
     * Decrypts an encrypted String, returns null on failure (legacy contract).
     */
    String decryptString(String encryptedString) {
        if (encryptedString == null) {
            return null
        }
        try {
            if (AesGcmCipher.isEncrypted(encryptedString)) {
                return AesGcmCipher.decrypt(encryptedString, keyProvider.key)
            }
            return decryptLegacy(encryptedString)
        } catch (Exception e) {
            log.error 'Error while decrypting', e
            return null
        }
    }

    /**
     * True when the value still uses the legacy Grails 4 cipher format and
     * needs re-encryption.
     */
    boolean isLegacyCiphertext(String value) {
        return value != null && !AesGcmCipher.isEncrypted(value)
    }

    private String decryptLegacy(String encryptedString) {
        String secret = grailsApplication.config.getProperty('pneumatik.credentials.legacy.secret')
        String salt = grailsApplication.config.getProperty('pneumatik.credentials.legacy.salt')
        if (!secret || !salt) {
            throw new IllegalStateException(
                    'Found a legacy-encrypted value but pneumatik.credentials.legacy.secret/salt are not configured. ' +
                    'Configure them so existing data can be re-encrypted.')
        }
        return LegacyAesCbcCipher.decrypt(encryptedString, secret, salt)
    }
}
