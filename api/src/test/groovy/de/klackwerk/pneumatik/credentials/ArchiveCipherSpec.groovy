package de.klackwerk.pneumatik.credentials

import spock.lang.Specification
import spock.lang.Unroll

import javax.crypto.AEADBadTagException
import java.security.SecureRandom

class ArchiveCipherSpec extends Specification {

    private static final byte[] KEY = randomBytes(32)

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length]
        new SecureRandom().nextBytes(bytes)
        return bytes
    }

    private static byte[] encrypt(byte[] plaintext, byte[] key = KEY) {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ArchiveCipher.encrypt(new ByteArrayInputStream(plaintext), out, key)
        return out.toByteArray()
    }

    private static byte[] decrypt(byte[] ciphertext, byte[] key = KEY) {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        ArchiveCipher.decrypt(new ByteArrayInputStream(ciphertext), out, key)
        return out.toByteArray()
    }

    @Unroll
    void 'round-trips #description'() {
        given:
        byte[] plaintext = randomBytes(size)

        when:
        byte[] recovered = decrypt(encrypt(plaintext))

        then:
        recovered == plaintext

        where:
        description                    | size
        'an empty archive'             | 0
        'a single byte'                | 1
        'less than one chunk'          | 1024
        'exactly one chunk'            | ArchiveCipher.CHUNK_SIZE
        'one chunk plus a byte'        | ArchiveCipher.CHUNK_SIZE + 1
        'exactly two chunks'           | ArchiveCipher.CHUNK_SIZE * 2
        'two chunks and a partial one' | ArchiveCipher.CHUNK_SIZE * 2 + 7777
    }

    void 'the ciphertext does not contain the plaintext'() {
        given:
        byte[] plaintext = ('INSERT INTO users VALUES (1, "hunter2");' * 500).bytes

        when:
        byte[] ciphertext = encrypt(plaintext)

        then:
        !new String(ciphertext, 'ISO-8859-1').contains('hunter2')
    }

    void 'a wrong key is rejected rather than producing garbage'() {
        given:
        byte[] ciphertext = encrypt('some dump'.bytes)

        when:
        decrypt(ciphertext, randomBytes(32))

        then:
        thrown(AEADBadTagException)
    }

    void 'the archive key is not the data-encryption key itself'() {
        given: 'a payload that fits one chunk, encrypted with the master key'
        byte[] ciphertext = encrypt('payload'.bytes)

        expect: 'decrypting with the raw key fails — the archive key is derived'
        ArchiveCipher.MAGIC.length + 16 + 7 == ArchiveCipher.headerLength()

        when: 'the salt is changed, so a different subkey is derived'
        ciphertext[ArchiveCipher.MAGIC.length] = (byte) (ciphertext[ArchiveCipher.MAGIC.length] ^ 0xFF)
        decrypt(ciphertext)

        then:
        thrown(AEADBadTagException)
    }

    void 'a truncated archive fails instead of decrypting to a short dump'() {
        given: 'an archive of several chunks'
        byte[] plaintext = randomBytes(ArchiveCipher.CHUNK_SIZE * 3)
        byte[] ciphertext = encrypt(plaintext)

        and: 'with its tail lost, cut exactly on a chunk boundary'
        int frame = ArchiveCipher.CHUNK_SIZE + 16
        byte[] truncated = new byte[ArchiveCipher.headerLength() + frame * 2]
        System.arraycopy(ciphertext, 0, truncated, 0, truncated.length)

        when:
        decrypt(truncated)

        then: 'the final-chunk flag does not match, so the tag fails'
        thrown(AEADBadTagException)
    }

    void 'a modified byte is detected'() {
        given:
        byte[] ciphertext = encrypt(randomBytes(4096))
        int target = ArchiveCipher.headerLength() + 10

        when:
        ciphertext[target] = (byte) (ciphertext[target] ^ 0x01)
        decrypt(ciphertext)

        then:
        thrown(AEADBadTagException)
    }

    void 'reordered chunks are detected'() {
        given: 'an archive of two full chunks and a final one'
        byte[] ciphertext = encrypt(randomBytes(ArchiveCipher.CHUNK_SIZE * 2 + 16))
        int frame = ArchiveCipher.CHUNK_SIZE + 16
        int header = ArchiveCipher.headerLength()

        when: 'the first two chunks swap places'
        byte[] first = ciphertext[header..<(header + frame)] as byte[]
        byte[] second = ciphertext[(header + frame)..<(header + frame * 2)] as byte[]
        System.arraycopy(second, 0, ciphertext, header, frame)
        System.arraycopy(first, 0, ciphertext, header + frame, frame)
        decrypt(ciphertext)

        then: 'the chunk counter is part of the nonce, so neither authenticates'
        thrown(AEADBadTagException)
    }

    void 'each archive uses fresh randomness'() {
        given:
        byte[] plaintext = 'identical input'.bytes

        expect:
        encrypt(plaintext) != encrypt(plaintext)
    }

    void 'a foreign file is not mistaken for an archive'() {
        when:
        decrypt('PK this is a plain zip'.bytes)

        then:
        IllegalStateException e = thrown()
        e.message.contains('Not a Pneumatik encrypted archive')
    }

    void 'isEncrypted distinguishes archives from plaintext'() {
        given:
        File encrypted = File.createTempFile('archive', '.enc')
        File plain = File.createTempFile('archive', '.zip')
        encrypted.bytes = encrypt('dump'.bytes)
        plain.bytes = 'PK not encrypted'.bytes

        expect:
        ArchiveCipher.isEncrypted(encrypted)
        !ArchiveCipher.isEncrypted(plain)
        !ArchiveCipher.isEncrypted(new File('/does/not/exist'))

        cleanup:
        encrypted.delete()
        plain.delete()
    }
}
