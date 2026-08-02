package de.klackwerk.pneumatik.storage

import de.klackwerk.pneumatik.backup.Backup
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

@Transactional
class DirectStorageService implements StorageActions {

    GrailsApplication grailsApplication
    StorageService storageService

    /**
     * Stores backups to local Filesystem or mounted drives
     * @param backup Backup
     * @return Boolean
     */
    @Override
    Boolean storeBackup(Backup backup) {
        log.debug "STORAGESERVICE - Storing Backup with id: ${backup.id}"

        // Zip file and delete uncompressed File from temp
        String uncompressedFile = backup.fullPath
        backup = storageService.zipBackup(backup)
        storageService.deleteFile(uncompressedFile)

        // Set target path
        String targetFile = "${grailsApplication.config.getProperty('pneumatik.storage.direct.path')}/${backup.filename}"
        log.debug "STORAGESERVICE - Location will be: ${targetFile}"

        // Move file to target location (replaces the legacy shell `mv`;
        // Files.move copies across filesystems the same way mv does)
        try {
            Files.move(Paths.get(backup.fullPath), Paths.get(targetFile), StandardCopyOption.REPLACE_EXISTING)
        } catch (IOException e) {
            log.error "Could not move Backup to direct Storage: ${e.message}", e
            return false
        }

        // update fullPath
        backup.fullPath = targetFile
        log.debug "STORAGESERVICE - Stored Backup with id: ${backup.id}"
        return true
    }

    @Override
    InputStream openBackup(Backup backup) {
        return Files.newInputStream(Paths.get(backup.fullPath))
    }

    @Override
    Boolean deleteBackup(Backup backup) {
        return storageService.deleteFile(backup.fullPath)
    }
}
