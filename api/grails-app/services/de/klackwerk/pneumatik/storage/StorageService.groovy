package de.klackwerk.pneumatik.storage

import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.BackupService
import de.klackwerk.pneumatik.credentials.ArchiveCipher
import de.klackwerk.pneumatik.credentials.KeyProvider
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional

import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Facade over the storage providers plus file utilities shared by them.
 */
@Transactional
class StorageService {

    S3StorageService s3StorageService
    DirectStorageService directStorageService
    GrailsApplication grailsApplication
    KeyProvider keyProvider

    /**
     * Stores Backup File
     * @param backup Backup
     * @return Boolean
     */
    Boolean storeBackup(Backup backup) {
        if (backup.storageProvider == StorageProvider.DIRECT || !backup.storageProvider) {
            log.debug 'STORAGESERVICE - Storing Backup on local storage'
            return directStorageService.storeBackup(backup)
        } else if (backup.storageProvider == StorageProvider.S3) {
            log.debug 'STORAGESERVICE - Storing Backup on S3 Storage'
            return s3StorageService.storeBackup(backup)
        } else {
            throw new IllegalStateException('Unsupported Storage provider')
        }
    }

    /**
     * Opens a stored backup archive for streaming. T
     * The caller is responsible for closing the stream.
     * @param backup Backup
     * @return InputStream over the zip archive
     */
    InputStream openBackup(Backup backup) {
        if (backup.storageProvider == StorageProvider.DIRECT) {
            return directStorageService.openBackup(backup)
        } else if (backup.storageProvider == StorageProvider.S3) {
            return s3StorageService.openBackup(backup)
        } else {
            throw new IllegalStateException('Unsupported Storage Provider')
        }
    }

    /**
     * Size of the stored archive in bytes, when known. Falls back to the
     * local file for direct storage; null when undeterminable (legacy rows).
     */
    Long storedBackupSize(Backup backup) {
        if (backup.archivedSizeBytes != null) {
            return backup.archivedSizeBytes
        }
        if (backup.storageProvider == StorageProvider.DIRECT && backup.fullPath) {
            File file = new File(backup.fullPath)
            return file.isFile() ? file.length() : null
        }
        return null
    }

    /**
     * Delete a Stored Backup. Deletes the domain row as well, regardless of
     * whether the provider reported success (legacy behaviour, preserved).
     * @param backup Backup
     * @return Boolean
     */
    Boolean deleteBackup(Backup backup) {
        Boolean success = false
        log.debug "STORAGESERVICE - Delete backup ${backup.id}"

        if (!backup.fullPath) {
            backup.delete(flush: true)
            return true
        }

        if (backup.storageProvider == StorageProvider.DIRECT) {
            log.debug 'STORAGESERVICE - Delete backup from DIRECT storage'
            success = directStorageService.deleteBackup(backup)
        } else if (backup.storageProvider == StorageProvider.S3) {
            log.debug 'STORAGESERVICE - Delete backup from S3 storage'
            success = s3StorageService.deleteBackup(backup)
        } else {
            throw new IllegalStateException('Unsupported Storage Provider')
        }

        backup.delete(flush: true)
        return success
    }

    /**
     * Zips the backup's file, updates filename and fullPath on the backup
     * @param backup Backup
     * @return Backup
     */
    Backup zipBackup(Backup backup) {
        log.debug "STORAGESERVICE - Zip backup with id: ${backup.id}"
        String targetPath = grailsApplication.config.getProperty('pneumatik.storage.direct.path')
        String sourceFilePath = "${backup.fullPath}"
        String targetFilePath = BackupService.resolveStoragePath(targetPath, "${backup.filename}.zip")

        log.debug 'STORAGESERVICE - Creating zip archive'
        new FileOutputStream(new File(targetFilePath)).withCloseable { fos ->
            ZipOutputStream out = new ZipOutputStream(fos)
            ZipEntry zipEntry = new ZipEntry(backup.filename)
            out.putNextEntry(zipEntry)

            log.debug 'STORAGESERVICE - Writing zip archive to temp'
            new FileInputStream(sourceFilePath).withCloseable { fileInputStream ->
                byte[] byteBuffer = new byte[1024]
                int bytesRead
                while ((bytesRead = fileInputStream.read(byteBuffer)) != -1) {
                    out.write(byteBuffer, 0, bytesRead)
                }
            }

            out.flush()
            out.closeEntry()
            out.close()
        }
        log.debug 'STORAGESERVICE - Wrote zip archive to temp'

        try {
            verifyArchive(new File(targetFilePath), backup.filename)
        } catch (IllegalStateException e) {
            // an unreadable archive must not stay in storage
            new File(targetFilePath).delete()
            throw e
        }

        backup.filename = "${backup.filename}.zip"
        backup.fullPath = targetFilePath
        backup.archivedSizeBytes = new File(targetFilePath).length()

        return encryptArchive(backup)
    }

    /**
     * Encrypts the verified archive in place when archive encryption is on.
     *
     * Runs after {@link #verifyArchive}
     *
     * @param backup Backup with a stored plaintext archive
     * @return the same Backup, pointing at the encrypted archive when enabled
     */
    protected Backup encryptArchive(Backup backup) {
        if (!encryptArchives()) {
            return backup
        }

        File encrypted = encryptFile(new File(backup.fullPath), keyProvider.key)

        backup.filename = "${backup.filename}.enc"
        backup.fullPath = encrypted.path
        backup.archivedSizeBytes = encrypted.length()
        backup.encrypted = true

        log.debug "STORAGESERVICE - Encrypted archive ${backup.filename}"
        return backup
    }

    /**
     * Writes {@code plaintext} out encrypted, proves the result decrypts, then
     * removes the plaintext.
     *
     * @param plaintext the verified zip archive
     * @param key the data-encryption key
     * @return the encrypted archive, alongside the original with .enc appended
     */
    protected static File encryptFile(File plaintext, byte[] key) {
        File target = new File(BackupService.resolveStoragePath(plaintext.parent, "${plaintext.name}.enc"))

        try {
            new FileInputStream(plaintext).withCloseable { InputStream input ->
                new FileOutputStream(target).withCloseable { OutputStream output ->
                    ArchiveCipher.encrypt(input, output, key)
                }
            }
            verifyEncryptedArchive(target, key)
        } catch (Exception e) {
            target.delete()
            throw new IllegalStateException("Archive ${plaintext.name} could not be encrypted: ${e.message}", e)
        }

        if (!plaintext.delete()) {
            // the unencrypted dump must not stay in storage beside the encrypted one
            target.delete()
            throw new IllegalStateException("Plaintext archive ${plaintext.name} could not be removed after encryption")
        }

        return target
    }

    /**
     * Decrypts the archive and throws the plaintext away.
     */
    protected static void verifyEncryptedArchive(File archive, byte[] key) {
        new FileInputStream(archive).withCloseable { InputStream input ->
            ArchiveCipher.decrypt(input, OutputStream.nullOutputStream(), key)
        }
    }

    boolean encryptArchives() {
        return grailsApplication.config.getProperty('pneumatik.storage.encrypt-archives', Boolean, false)
    }

    /**
     * Reads the archive back before it counts as stored.
     *
     * @throws IllegalStateException when the archive is unreadable or empty
     */
    protected static void verifyArchive(File archive, String entryName) {
        try {
            new ZipFile(archive).withCloseable { ZipFile zip ->
                ZipEntry entry = zip.getEntry(entryName)
                if (entry == null) {
                    throw new IllegalStateException("Zip archive ${archive.name} does not contain ${entryName}")
                }
                if (entry.size == 0L) {
                    throw new IllegalStateException("Zip archive ${archive.name} contains an empty ${entryName}")
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Zip archive ${archive.name} could not be read back: ${e.message}", e)
        }
    }

    /**
     * Empties the temp directory — partial dumps that a killed container left behind.
     */
    void cleanTempDirectory() {
        String tempLocation = grailsApplication.config.getProperty('pneumatik.storage.temp.path')
        if (!tempLocation) {
            return
        }
        File tempDir = new File(tempLocation)
        if (!tempDir.isDirectory()) {
            tempDir.mkdirs()
            return
        }
        int removed = 0
        tempDir.listFiles()?.each { File file ->
            if (file.isFile() && file.delete()) {
                removed++
            }
        }
        if (removed) {
            log.info "STORAGESERVICE - Removed ${removed} leftover file(s) from temp storage"
        }
    }

    /**
     * Deletes the file at the given path.
     *
     * @return whether the path is gone afterwards — a missing file counts as
     *         deleted, a file that refused to go does not.
     */
    Boolean deleteFile(String path) {
        if (!path) {
            return true
        }
        log.debug "STORAGESERVICE - Delete file ${path}"
        File file = new File(path)
        if (!file.exists()) {
            return true
        }
        boolean deleted = file.delete()
        if (!deleted) {
            log.error "STORAGESERVICE - Could not delete file ${path}"
        }
        return deleted
    }
}
