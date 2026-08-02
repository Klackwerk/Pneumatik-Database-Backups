package de.klackwerk.pneumatik.storage

import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.UploadPartResponse
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class MultipartUploaderSpec extends Specification {

    @TempDir
    Path tempDir

    MultipartUploader uploader
    S3Client client

    void setup() {
        uploader = new MultipartUploader(multipartThreshold: 1024, partSize: 512)
        client = Mock(S3Client)
    }

    private File archiveOf(int bytes) {
        File file = new File(tempDir.toFile(), 'backup.zip')
        file.bytes = new byte[bytes]
        return file
    }

    void 'a small archive goes up as a single PUT'() {
        given:
        File file = archiveOf(500)

        when:
        uploader.upload(client, 'bucket', 'key.zip', file, 'application/zip')

        then: 'multipart has real overhead; it is not worth it below the threshold'
        1 * client.putObject(_ as PutObjectRequest, _ as RequestBody)
        0 * client.createMultipartUpload(_)
    }

    void 'a large archive is split into parts and completed'() {
        given: '1500 bytes at 512 per part'
        File file = archiveOf(1500)

        when:
        uploader.upload(client, 'bucket', 'key.zip', file, 'application/zip')

        then:
        1 * client.createMultipartUpload(_ as CreateMultipartUploadRequest) >>
                (CreateMultipartUploadResponse.builder().uploadId('upload-1').build() as CreateMultipartUploadResponse)
        3 * client.uploadPart(_ as UploadPartRequest, _ as RequestBody) >>
                (UploadPartResponse.builder().eTag('etag').build() as UploadPartResponse)
        1 * client.completeMultipartUpload(_ as CompleteMultipartUploadRequest)
        0 * client.abortMultipartUpload(_)
    }

    void 'progress is reported as parts complete'() {
        given:
        File file = archiveOf(1500)
        List<List<Long>> progress = []

        when:
        uploader.upload(client, 'bucket', 'key.zip', file, 'application/zip',
                { long uploaded, long total -> progress << [uploaded, total] } as MultipartUploader.ProgressListener)

        then:
        1 * client.createMultipartUpload(_) >>
                (CreateMultipartUploadResponse.builder().uploadId('upload-1').build() as CreateMultipartUploadResponse)
        3 * client.uploadPart(_, _ as RequestBody) >>
                (UploadPartResponse.builder().eTag('etag').build() as UploadPartResponse)
        1 * client.completeMultipartUpload(_)

        and: 'monotonically increasing, ending at the full size'
        progress == [[512L, 1500L], [1024L, 1500L], [1500L, 1500L]]
    }

    void 'a failed upload is aborted so its parts stop being billed'() {
        given:
        File file = archiveOf(1500)

        when:
        uploader.upload(client, 'bucket', 'key.zip', file, 'application/zip')

        then:
        1 * client.createMultipartUpload(_) >>
                (CreateMultipartUploadResponse.builder().uploadId('upload-1').build() as CreateMultipartUploadResponse)
        1 * client.uploadPart(_, _ as RequestBody) >> {
            throw SdkClientException.create('connection reset')
        }
        1 * client.abortMultipartUpload(_ as AbortMultipartUploadRequest)
        0 * client.completeMultipartUpload(_)

        and: 'the caller still learns the upload failed'
        thrown(SdkClientException)
    }

    void 'part count stays within the S3 limit however small the configured part size'() {
        given: 'a part size that on its own would need 50 000 parts'
        uploader.partSize = 1
        File file = archiveOf(50_000)
        AtomicInteger parts = new AtomicInteger()

        when:
        uploader.upload(client, 'bucket', 'key.zip', file, 'application/zip')

        then: 'the part size grows instead of the upload failing at 10 000'
        1 * client.createMultipartUpload(_) >>
                (CreateMultipartUploadResponse.builder().uploadId('upload-1').build() as CreateMultipartUploadResponse)
        (1.._) * client.uploadPart(_, _ as RequestBody) >> {
            parts.incrementAndGet()
            (UploadPartResponse.builder().eTag('etag').build() as UploadPartResponse)
        }
        1 * client.completeMultipartUpload(_)
        parts.get() <= MultipartUploader.MAX_PARTS
    }
}
