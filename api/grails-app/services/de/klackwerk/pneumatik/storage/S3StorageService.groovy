package de.klackwerk.pneumatik.storage

import de.klackwerk.pneumatik.backup.Backup
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import jakarta.annotation.PreDestroy
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.S3Exception

import java.text.SimpleDateFormat

/**
 * Stores backups on S3-compatible object storage (AWS S3, DigitalOcean
 * Spaces, MinIO, ...). Ported from the legacy AWS SDK v1 client to SDK v2.
 */
@Transactional
class S3StorageService implements StorageActions {

    GrailsApplication grailsApplication
    StorageService storageService
    MultipartUploader multipartUploader

    /**
     * One client for the life of the application. Building a new one per
     * operation — as this used to — created a fresh connection pool every
     * time and never closed any of them.
     */
    private volatile S3Client cachedClient

    S3Client getClient() {
        if (cachedClient != null) {
            return cachedClient
        }
        synchronized (this) {
            if (cachedClient == null) {
                cachedClient = buildClient()
            }
            return cachedClient
        }
    }

    private S3Client buildClient() {
        final String accessKey = grailsApplication.config.getProperty('pneumatik.storage.s3.key')
        final String secretKey = grailsApplication.config.getProperty('pneumatik.storage.s3.secret')
        final String bucketEndpoint = grailsApplication.config.getProperty('pneumatik.storage.s3.endpoint')
        final String bucketRegion = grailsApplication.config.getProperty('pneumatik.storage.s3.region')

        if (!bucketEndpoint) {
            // failing here beats returning null and surfacing as an NPE later
            throw new IllegalStateException('S3 storage is selected but PNEUMATIK_S3_ENDPOINT is not configured')
        }

        log.debug "S3STORAGESERVICE - Creating S3 client for region ${bucketRegion} and endpoint ${bucketEndpoint}"
        String endpoint = bucketEndpoint.contains('://') ? bucketEndpoint : "https://${bucketEndpoint}"

        return S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(bucketRegion))
                .endpointOverride(URI.create(endpoint))
                .build()
    }

    @PreDestroy
    void closeClient() {
        cachedClient?.close()
    }

    /**
     * Stores backup to S3 Storage
     * @param backup Backup
     * @return Boolean
     */
    @Override
    Boolean storeBackup(Backup backup) {
        String dumpPath = backup.fullPath
        File zippedBackup = null

        try {
            log.debug 'S3STORAGESERVICE - Storing backup to S3'

            String bucketName = grailsApplication.config.getProperty('pneumatik.storage.s3.bucket')
            String s3basePath = grailsApplication.config.getProperty('pneumatik.storage.s3.basePath')

            backup = storageService.zipBackup(backup)
            storageService.deleteFile(dumpPath)
            zippedBackup = new File(backup.fullPath)

            // Date-partitioned key so no folder collects a large number of files.
            // (Legacy used 'DD' — day of year — by mistake; fixed to 'dd'.)
            Date now = new Date()
            String year = new SimpleDateFormat('yyyy').format(now)
            String month = new SimpleDateFormat('MM').format(now)
            String day = new SimpleDateFormat('dd').format(now)
            String key = "${s3basePath}/${backup.database.name.replace(' ', '_')}/${year}/${month}/${day}/${backup.filename}"

            log.debug "S3STORAGESERVICE - Upload to bucket ${bucketName} as ${key}"
            multipartUploader.upload(getClient(), bucketName, key, zippedBackup, 'application/zip',
                    { long uploaded, long total ->
                        log.debug "S3STORAGESERVICE - ${backup.id}: ${uploaded}/${total} bytes uploaded"
                    } as MultipartUploader.ProgressListener)

            // only now does the S3 key describe where the archive really is;
            // setting it earlier left a failed upload pointing at an object
            // that was never written
            backup.fullPath = key
            log.debug "S3STORAGESERVICE - Uploaded to bucket ${bucketName}"
            return true
        } catch (Exception e) {
            log.error "Uploading to S3 for backup with id ${backup.id} FAILED!", e
            return false
        } finally {
            // the staging zip is local scratch either way; leaving it behind
            // filled the storage volume of every S3-configured install
            if (zippedBackup?.exists() && !zippedBackup.delete()) {
                log.error "S3STORAGESERVICE - Could not delete staging archive ${zippedBackup}"
            }
        }
    }

    /**
     * Opens the stored archive as a stream directly from S3 — nothing is
     * spooled to the local filesystem or loaded into memory.
     * @param backup Backup
     * @return InputStream over the object's content
     */
    @Override
    InputStream openBackup(Backup backup) {
        log.debug 'S3STORAGESERVICE - Get File from S3'
        String bucketName = grailsApplication.config.getProperty('pneumatik.storage.s3.bucket')
        S3Client client = getClient()

        log.debug "S3STORAGESERVICE - Stream file from bucket ${bucketName} on path ${backup.fullPath}"
        GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(backup.fullPath)
                .build() as GetObjectRequest
        ResponseInputStream<GetObjectResponse> inputStream = client.getObject(getRequest)
        if (inputStream == null) {
            log.error "Could not download file from bucket ${bucketName} on path ${backup.fullPath}"
            throw new FileNotFoundException("Object not found on bucket ${bucketName}, path ${backup.fullPath}")
        }
        return inputStream
    }

    /**
     * Delete file from S3
     * @param backup Backup
     * @return Boolean (success = true; also true when the file was already gone)
     */
    @Override
    Boolean deleteBackup(Backup backup) {
        log.debug "S3STORAGESERVICE - Deleting backup ${backup.id} from S3"
        try {
            String bucketName = grailsApplication.config.getProperty('pneumatik.storage.s3.bucket')
            S3Client client = getClient()

            // Test if file exists
            log.debug 'S3STORAGESERVICE - Testing if file exists on remote'
            try {
                client.headObject(HeadObjectRequest.builder()
                        .bucket(bucketName)
                        .key(backup.fullPath)
                        .build() as HeadObjectRequest)
            } catch (NoSuchKeyException ignored) {
                // file already absent — treat as deleted (legacy behaviour)
                log.error 'File was not found on S3'
                return true
            }

            log.debug "S3STORAGESERVICE - Deleting file on ${backup.fullPath}"
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(backup.fullPath)
                    .build() as DeleteObjectRequest)
            log.debug "S3STORAGESERVICE - Deleted file on ${backup.fullPath}"
            return true
        } catch (S3Exception e) {
            log.error 'S3 error while deleting backup', e
            return false
        } catch (Exception e) {
            log.error 'Could not delete backup from S3', e
            return false
        }
    }
}
