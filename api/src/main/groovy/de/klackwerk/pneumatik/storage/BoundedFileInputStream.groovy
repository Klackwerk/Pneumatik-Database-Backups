package de.klackwerk.pneumatik.storage

import groovy.transform.CompileStatic

/**
 * Reads exactly {@code length} bytes of a file starting at {@code offset}.
 *
 * Lets one part of a multipart upload be streamed straight off disk: no
 * slice of the archive is ever held in the JVM heap.
 */
@CompileStatic
class BoundedFileInputStream extends InputStream {

    private final RandomAccessFile file
    private long remaining

    BoundedFileInputStream(File source, long offset, long length) {
        this.file = new RandomAccessFile(source, 'r')
        this.file.seek(offset)
        this.remaining = length
    }

    @Override
    int read() {
        if (remaining <= 0) {
            return -1
        }
        int value = file.read()
        if (value >= 0) {
            remaining--
        }
        return value
    }

    @Override
    int read(byte[] buffer, int offset, int length) {
        if (remaining <= 0) {
            return -1
        }
        int toRead = (int) Math.min(length as long, remaining)
        int read = file.read(buffer, offset, toRead)
        if (read > 0) {
            remaining -= read
        }
        return read
    }

    @Override
    int available() {
        return (int) Math.min(remaining, Integer.MAX_VALUE as long)
    }

    @Override
    void close() {
        file.close()
    }
}
