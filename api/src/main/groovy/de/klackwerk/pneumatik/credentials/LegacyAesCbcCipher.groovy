package de.klackwerk.pneumatik.credentials

import groovy.transform.CompileStatic

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.spec.KeySpec

/**
 * Decrypt-only replica of the legacy Grails 4 CredentialService scheme:
 * AES-256-CBC/PKCS5, PBKDF2WithHmacSHA256 (65536 iterations) over a config
 * secret+salt, and a hardcoded all-zero IV. Kept solely so existing
 * Database.password / Host.sshKey values can be re-encrypted to the v1
 * AES-GCM format at startup. Never use this for new data.
 */
@CompileStatic
class LegacyAesCbcCipher {

    static String decrypt(String encryptedString, String secret, String salt) {
        byte[] iv = new byte[16]
        IvParameterSpec ivspec = new IvParameterSpec(iv)
        SecretKeyFactory factory = SecretKeyFactory.getInstance('PBKDF2WithHmacSHA256')
        KeySpec spec = new PBEKeySpec(secret.toCharArray(), salt.getBytes(), 65536, 256)
        SecretKey tmp = factory.generateSecret(spec)
        SecretKeySpec secretKey = new SecretKeySpec(tmp.getEncoded(), 'AES')
        Cipher cipher = Cipher.getInstance('AES/CBC/PKCS5Padding')
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivspec)
        return new String(cipher.doFinal(Base64.decoder.decode(encryptedString)), 'UTF-8')
    }
}
