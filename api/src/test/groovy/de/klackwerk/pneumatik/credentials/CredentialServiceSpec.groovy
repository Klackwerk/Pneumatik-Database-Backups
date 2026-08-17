package de.klackwerk.pneumatik.credentials

import grails.config.Config
import grails.core.GrailsApplication
import spock.lang.Specification

class CredentialServiceSpec extends Specification {

    static final String KEY_B64 = Base64.encoder.encodeToString('0123456789abcdef0123456789abcdef'.bytes)

    CredentialService service

    void setup() {
        service = new CredentialService()
        service.keyProvider = new FileKeyProvider(null, KEY_B64)
        service.grailsApplication = Stub(GrailsApplication) {
            getConfig() >> Stub(Config) {
                getProperty('pneumatik.credentials.legacy.secret') >> 'legacy-secret'
                getProperty('pneumatik.credentials.legacy.salt') >> 'legacy-salt'
            }
        }
    }

    void 'encryptString produces v1 ciphertext that decryptString roundtrips'() {
        when:
        String encrypted = service.encryptString('secret123')

        then:
        encrypted.startsWith('v1:')
        service.decryptString(encrypted) == 'secret123'
    }

    void 'decryptString falls back to the legacy cipher for unprefixed values'() {
        given: 'a value encrypted with the legacy Grails 4 scheme'
        String legacyValue = legacyEncrypt('old-password', 'legacy-secret', 'legacy-salt')

        expect:
        service.decryptString(legacyValue) == 'old-password'
    }

    void 'decryptString returns null on garbage (legacy contract)'() {
        expect:
        service.decryptString('not-a-ciphertext') == null
    }

    void 'null in, null out'() {
        expect:
        service.encryptString(null) == null
        service.decryptString(null) == null
    }

    void 'isLegacyCiphertext distinguishes formats'() {
        expect:
        service.isLegacyCiphertext('someBase64==')
        !service.isLegacyCiphertext(service.encryptString('x'))
        !service.isLegacyCiphertext(null)
    }

    private static String legacyEncrypt(String plaintext, String secret, String salt) {
        def factory = javax.crypto.SecretKeyFactory.getInstance('PBKDF2WithHmacSHA256')
        def spec = new javax.crypto.spec.PBEKeySpec(secret.toCharArray(), salt.bytes, 65536, 256)
        def keySpec = new javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, 'AES')
        def cipher = javax.crypto.Cipher.getInstance('AES/CBC/PKCS5Padding')
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, new javax.crypto.spec.IvParameterSpec(new byte[16]))
        return Base64.encoder.encodeToString(cipher.doFinal(plaintext.getBytes('UTF-8')))
    }
}
