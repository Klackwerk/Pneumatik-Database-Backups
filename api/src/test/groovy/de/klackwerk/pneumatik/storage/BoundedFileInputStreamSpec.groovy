package de.klackwerk.pneumatik.storage

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class BoundedFileInputStreamSpec extends Specification {

    @TempDir
    Path tempDir

    File source

    void setup() {
        source = new File(tempDir.toFile(), 'archive.bin')
        source.bytes = (0..<100).collect { (byte) it } as byte[]
    }

    void 'reads exactly the requested window'() {
        when:
        byte[] read = new BoundedFileInputStream(source, 10, 20).withCloseable { it.bytes }

        then:
        read.length == 20
        read[0] == (byte) 10
        read[19] == (byte) 29
    }

    void 'stops at the window even when the file continues'() {
        when:
        BoundedFileInputStream stream = new BoundedFileInputStream(source, 0, 5)
        byte[] buffer = new byte[50]
        int first = stream.read(buffer, 0, 50)
        int second = stream.read(buffer, 0, 50)

        then: 'a part must never bleed into the next one'
        first == 5
        second == -1

        cleanup:
        stream.close()
    }

    void 'single-byte reads respect the window too'() {
        given:
        BoundedFileInputStream stream = new BoundedFileInputStream(source, 3, 2)

        expect:
        stream.read() == 3
        stream.read() == 4
        stream.read() == -1

        cleanup:
        stream.close()
    }

    void 'the final window can run to the end of the file'() {
        when:
        byte[] read = new BoundedFileInputStream(source, 90, 10).withCloseable { it.bytes }

        then:
        read.length == 10
        read[9] == (byte) 99
    }

    void 'available reports what is left in the window'() {
        given:
        BoundedFileInputStream stream = new BoundedFileInputStream(source, 0, 8)

        when:
        stream.read(new byte[3], 0, 3)

        then:
        stream.available() == 5

        cleanup:
        stream.close()
    }
}
