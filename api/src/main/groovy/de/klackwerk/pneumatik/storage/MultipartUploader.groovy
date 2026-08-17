package de.klackwerk.pneumatik.storage

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest

/**
 * Uploads a file to S3 in parts.
 *
 */
@Slf4j
@CompileStatic
class MultipartUploader {

    /** files at or below this go up as one PUT */
    static final long DEFAULT_MULTIPART_THRESHOLD = 16L * 1024 * 1024
    /** S3 requires at least 5 MB per part except the last */
    static final long DEFAULT_PART_SIZE = 16L * 1024 * 1024
    static final int MAX_PARTS = 10_000

    long multipartThreshold = DEFAULT_MULTIPART_THRESHOLD
    long partSize = DEFAULT_PART_SIZE

    /** called after each part with (bytesUploaded, totalBytes) */
    interface ProgressListener {
        void onProgress(long uploaded, long total)
    }

    void upload(S3Client client, String bucket, String key, File file, String contentType,
                ProgressListener progress = null) {
        long length = file.length()
        if (length <= multipartThreshold) {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket).key(key).contentType(contentType).contentLength(length)
                    .build() as PutObjectRequest, RequestBody.fromFile(file))
            progress?.onProgress(length, length)
            return
        }

        uploadInParts(client, bucket, key, file, contentType, length, progress)
    }

    private void uploadInParts(S3Client client, String bucket, String key, File file, String contentType,
                               long length, ProgressListener progress) {
        // S3 allows at most 10 000 parts; grow the part size rather than fail
        long effectivePartSize = Math.max(partSize, (long) Math.ceil(length / (double) MAX_PARTS))

        CreateMultipartUploadResponse created = client.createMultipartUpload(
                CreateMultipartUploadRequest.builder()
                        .bucket(bucket).key(key).contentType(contentType)
                        .build() as CreateMultipartUploadRequest)
        String uploadId = created.uploadId()
        log.debug "MULTIPARTUPLOADER - Started multipart upload ${uploadId} for ${key} (${length} bytes)"

        try {
            List<CompletedPart> parts = []
            long offset = 0
            int partNumber = 1

            while (offset < length) {
                long thisPart = Math.min(effectivePartSize, length - offset)
                long partOffset = offset

                RequestBody body = RequestBody.fromContentProvider(
                        { -> new BoundedFileInputStream(file, partOffset, thisPart) },
                        thisPart, contentType)

                String etag = client.uploadPart(UploadPartRequest.builder()
                        .bucket(bucket).key(key).uploadId(uploadId)
                        .partNumber(partNumber).contentLength(thisPart)
                        .build() as UploadPartRequest, body).eTag()

                parts << (CompletedPart.builder().partNumber(partNumber).eTag(etag).build() as CompletedPart)
                offset += thisPart
                partNumber++
                progress?.onProgress(offset, length)
                log.debug "MULTIPARTUPLOADER - Uploaded ${offset}/${length} bytes of ${key}"
            }

            client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(key).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build() as CompletedMultipartUpload)
                    .build() as CompleteMultipartUploadRequest)
            log.debug "MULTIPARTUPLOADER - Completed multipart upload of ${key}"
        } catch (Exception e) {
            try {
                client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(bucket).key(key).uploadId(uploadId)
                        .build() as AbortMultipartUploadRequest)
                log.warn "MULTIPARTUPLOADER - Aborted multipart upload ${uploadId} of ${key}"
            } catch (Exception abortFailure) {
                log.error "MULTIPARTUPLOADER - Could not abort multipart upload ${uploadId}", abortFailure
            }
            throw e
        }
    }
}
