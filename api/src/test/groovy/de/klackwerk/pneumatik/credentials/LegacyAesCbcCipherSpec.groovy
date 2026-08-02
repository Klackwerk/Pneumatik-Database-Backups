package de.klackwerk.pneumatik.credentials

import spock.lang.Specification

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.spec.KeySpec

class LegacyAesCbcCipherSpec extends Specification {

    /**
     * Byte-for-byte replica of the legacy Grails 4 encryptString, used to
     * produce fixtures that prove decryption compatibility.
     */
    private static String legacyEncrypt(String plaintext, String secret, String salt) {
        byte[] iv = new byte[16]
        IvParameterSpec ivspec = new IvParameterSpec(iv)
        SecretKeyFactory factory = SecretKeyFactory.getInstance('PBKDF2WithHmacSHA256')
        KeySpec spec = new PBEKeySpec(secret.toCharArray(), salt.getBytes(), 65536, 256)
        SecretKey tmp = factory.generateSecret(spec)
        SecretKeySpec secretKey = new SecretKeySpec(tmp.getEncoded(), 'AES')
        Cipher cipher = Cipher.getInstance('AES/CBC/PKCS5Padding')
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivspec)
        return Base64.encoder.encodeToString(cipher.doFinal(plaintext.getBytes('UTF-8')))
    }

    void 'decrypts values produced by the legacy Grails 4 scheme'() {
        given:
        String secret = 'legacy-secret'
        String salt = 'legacy-salt'

        expect:
        LegacyAesCbcCipher.decrypt(legacyEncrypt(plaintext, secret, salt), secret, salt) == plaintext

        where:
        plaintext << ['db-password-123', 'ssh key\nwith\nnewlines', 'ümläute']
    }

    void 'wrong secret fails'() {
        when:
        LegacyAesCbcCipher.decrypt(legacyEncrypt('x', 's1', 'salt'), 's2', 'salt')

        then:
        thrown(Exception)
    }
}
