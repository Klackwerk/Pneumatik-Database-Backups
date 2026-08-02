package de.klackwerk.pneumatik.credentials

import groovy.transform.CompileStatic

import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * AES-256-GCM with a random 12-byte IV per encryption. Ciphertexts are
 * self-describing: {@code v1:base64(iv || ciphertext || tag)}. The prefix
 * lets CredentialService tell new-format values apart from legacy
 * zero-IV AES-CBC values during migration, and leaves room for future
 * key/format rotation (v2, ...).
 */
@CompileStatic
class AesGcmCipher {

    static final String PREFIX = 'v1:'

    private static final int IV_LENGTH_BYTES = 12
    private static final int TAG_LENGTH_BITS = 128

    private static final SecureRandom RANDOM = new SecureRandom()

    static String encrypt(String plaintext, byte[] key) {
        byte[] iv = new byte[IV_LENGTH_BYTES]
        RANDOM.nextBytes(iv)

        Cipher cipher = Cipher.getInstance('AES/GCM/NoPadding')
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, 'AES'), new GCMParameterSpec(TAG_LENGTH_BITS, iv))
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes('UTF-8'))

        byte[] combined = new byte[iv.length + ciphertext.length]
        System.arraycopy(iv, 0, combined, 0, iv.length)
        System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length)

        return PREFIX + Base64.encoder.encodeToString(combined)
    }

    static String decrypt(String value, byte[] key) {
        if (!isEncrypted(value)) {
            throw new IllegalArgumentException('Value is not in v1 ciphertext format')
        }
        byte[] combined = Base64.decoder.decode(value.substring(PREFIX.length()))
        byte[] iv = combined[0..<IV_LENGTH_BYTES] as byte[]
        byte[] ciphertext = combined[IV_LENGTH_BYTES..<combined.length] as byte[]

        Cipher cipher = Cipher.getInstance('AES/GCM/NoPadding')
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, 'AES'), new GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return new String(cipher.doFinal(ciphertext), 'UTF-8')
    }

    static boolean isEncrypted(String value) {
        return value?.startsWith(PREFIX)
    }
}
