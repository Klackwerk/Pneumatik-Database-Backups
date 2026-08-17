package de.klackwerk.pneumatik.storage

import de.klackwerk.pneumatik.backup.Backup

/**
 * Contract every storage provider implements. The backup module only ever
 * talks to storage through this interface (or StorageService, which
 * dispatches on the backup's provider).
 */
interface StorageActions {

    /**
     * Store backup on storage
     * @param backup Backup
     * @return Boolean (success = true)
     */
    Boolean storeBackup(Backup backup)

    /**
     * Open the stored archive for reading. Callers stream from it and close
     * it; the archive is never loaded into memory as a whole.
     * @param backup Backup
     * @return InputStream over the zip archive
     */
    InputStream openBackup(Backup backup)

    /**
     * Remove Backup from storage
     * @param backup
     * @return Boolean (success = true)
     */
    Boolean deleteBackup(Backup backup)
}
