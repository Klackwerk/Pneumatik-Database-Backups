package de.klackwerk.pneumatik.credentials

import groovy.transform.CompileStatic

/**
 * Reads the data-encryption key from a file mounted at deploy time
 * (e.g. a Docker secret). The file contains the key base64-encoded.
 *
 * Configured via {@code pneumatik.credentials.key-file}. As a fallback for
 * development, {@code pneumatik.credentials.key} may hold the base64 key
 * directly.
 */
@CompileStatic
class FileKeyProvider implements KeyProvider {

    private final String keyFilePath
    private final String inlineKey

    private volatile byte[] cachedKey

    FileKeyProvider(String keyFilePath, String inlineKey) {
        this.keyFilePath = keyFilePath
        this.inlineKey = inlineKey
    }

    @Override
    byte[] getKey() {
        byte[] key = cachedKey
        if (key == null) {
            key = loadKey()
            cachedKey = key
        }
        return key
    }

    private byte[] loadKey() {
        String encoded
        if (keyFilePath) {
            File file = new File(keyFilePath)
            if (!file.canRead()) {
                throw new IllegalStateException("Encryption key file not readable: ${keyFilePath}")
            }
            encoded = file.text.trim()
        } else if (inlineKey) {
            encoded = inlineKey.trim()
        } else {
            throw new IllegalStateException(
                    'No encryption key configured. Set pneumatik.credentials.key-file (recommended) or pneumatik.credentials.key.')
        }

        byte[] key = Base64.decoder.decode(encoded)
        if (key.length != 32) {
            throw new IllegalStateException("Encryption key must be 32 bytes (256 bit), got ${key.length} bytes")
        }
        return key
    }
}
