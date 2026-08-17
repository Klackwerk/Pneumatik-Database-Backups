package de.klackwerk.pneumatik.storage

import de.klackwerk.pneumatik.credentials.ArchiveCipher
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StorageServiceSpec extends Specification {

    @TempDir
    Path tempDir

    private File writeArchive(String entryName, byte[] content) {
        File archive = new File(tempDir.toFile(), 'backup.zip')
        new ZipOutputStream(new FileOutputStream(archive)).withCloseable { ZipOutputStream out ->
            out.putNextEntry(new ZipEntry(entryName))
            out.write(content)
            out.closeEntry()
        }
        return archive
    }

    void 'a complete archive containing the dump passes verification'() {
        given:
        File archive = writeArchive('shop.sql', '-- dump\nCREATE TABLE t (id int);\n'.bytes)

        when:
        StorageService.verifyArchive(archive, 'shop.sql')

        then:
        noExceptionThrown()
    }

    void 'a truncated archive is rejected'() {
        given: 'the central directory is written last, so cutting the tail is what a full disk looks like'
        File archive = writeArchive('shop.sql', ('-- dump\n' * 200).bytes)
        byte[] full = archive.bytes
        archive.bytes = full[0..<(full.length / 2 as int)] as byte[]

        when:
        StorageService.verifyArchive(archive, 'shop.sql')

        then:
        IllegalStateException e = thrown()
        e.message.contains('could not be read back')
    }

    void 'an archive that does not contain the dump is rejected'() {
        given:
        File archive = writeArchive('something-else.sql', 'data'.bytes)

        when:
        StorageService.verifyArchive(archive, 'shop.sql')

        then:
        IllegalStateException e = thrown()
        e.message.contains('does not contain')
    }

    void 'an archive whose dump is empty is rejected'() {
        given:
        File archive = writeArchive('shop.sql', new byte[0])

        when:
        StorageService.verifyArchive(archive, 'shop.sql')

        then:
        IllegalStateException e = thrown()
        e.message.contains('empty')
    }

    private static byte[] decryptToBytes(File encrypted, byte[] key) {
        ByteArrayOutputStream out = new ByteArrayOutputStream()
        new FileInputStream(encrypted).withCloseable { InputStream input ->
            ArchiveCipher.decrypt(input, out, key)
        }
        return out.toByteArray()
    }

    private static byte[] key(int seed) {
        byte[] key = new byte[32]
        Arrays.fill(key, (byte) seed)
        return key
    }

    void 'an encrypted archive replaces the plaintext and decrypts back to it'() {
        given:
        File archive = writeArchive('shop.sql', ('-- dump\n' * 500).bytes)
        byte[] original = archive.bytes

        when:
        File encrypted = StorageService.encryptFile(archive, key(7))

        then: 'the plaintext is gone and the archive is ours'
        !archive.exists()
        encrypted.name == 'backup.zip.enc'
        ArchiveCipher.isEncrypted(encrypted)

        and: 'and it decrypts to exactly the zip that went in'
        decryptToBytes(encrypted, key(7)) == original
    }

    void 'an archive that cannot be decrypted with our own key is never kept'() {
        given:
        File archive = writeArchive('shop.sql', '-- dump\n'.bytes)
        File encrypted = new File(archive.path + '.enc')
        new FileOutputStream(encrypted).withCloseable { OutputStream out ->
            ArchiveCipher.encrypt(new ByteArrayInputStream('dump'.bytes), out, key(1))
        }

        when: 'verification runs with a key that does not open it'
        StorageService.verifyEncryptedArchive(encrypted, key(2))

        then:
        thrown(Exception)

        cleanup:
        encrypted.delete()
    }

    void 'a plaintext zip is not treated as an encrypted archive'() {
        given:
        File archive = writeArchive('shop.sql', '-- dump\n'.bytes)

        expect:
        !ArchiveCipher.isEncrypted(archive)
    }
}
