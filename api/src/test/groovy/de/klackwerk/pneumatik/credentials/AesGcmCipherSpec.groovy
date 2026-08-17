package de.klackwerk.pneumatik.credentials

import spock.lang.Specification

class AesGcmCipherSpec extends Specification {

    static final byte[] KEY = new byte[32].tap { new Random(42).nextBytes(it) }

    void 'encrypt/decrypt roundtrips arbitrary strings'() {
        expect:
        AesGcmCipher.decrypt(AesGcmCipher.encrypt(plaintext, KEY), KEY) == plaintext

        where:
        plaintext << ['secret123', '', 'ümläute & 中文', '-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END-----']
    }

    void 'ciphertexts carry the v1 prefix'() {
        expect:
        AesGcmCipher.encrypt('x', KEY).startsWith('v1:')
    }

    void 'encrypting the same plaintext twice yields different ciphertexts (random IV)'() {
        expect:
        AesGcmCipher.encrypt('same', KEY) != AesGcmCipher.encrypt('same', KEY)
    }

    void 'tampered ciphertext fails authentication'() {
        given:
        String encrypted = AesGcmCipher.encrypt('secret', KEY)
        byte[] raw = Base64.decoder.decode(encrypted.substring(3))
        raw[raw.length - 1] = (byte) (raw[raw.length - 1] ^ 0x01)
        String tampered = 'v1:' + Base64.encoder.encodeToString(raw)

        when:
        AesGcmCipher.decrypt(tampered, KEY)

        then:
        thrown(Exception)
    }

    void 'wrong key fails'() {
        given:
        byte[] otherKey = new byte[32].tap { new Random(7).nextBytes(it) }

        when:
        AesGcmCipher.decrypt(AesGcmCipher.encrypt('secret', KEY), otherKey)

        then:
        thrown(Exception)
    }

    void 'isEncrypted recognises only v1 values'() {
        expect:
        AesGcmCipher.isEncrypted('v1:abc')
        !AesGcmCipher.isEncrypted('legacy-base64==')
        !AesGcmCipher.isEncrypted(null)
    }
}
