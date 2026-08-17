package de.klackwerk.pneumatik.backup

import groovy.transform.CompileStatic

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * A throwaway {@code known_hosts} file for one ssh invocation.
 *
 * ssh will only verify a host key against a file, so the pinned key is
 * written out for the duration of the run and removed afterwards. Host keys
 * are public, but the file still gets owner-only permissions: ssh refuses to
 * use a known_hosts file that others can write.
 */
@CompileStatic
class KnownHostsFile implements Closeable {

    final Path path

    private KnownHostsFile(Path path) {
        this.path = path
    }

    /**
     * @param directory where to put the file — the app's temp storage, which
     *        is swept at startup, so a killed container leaves nothing behind
     * @param pinnedKey the known_hosts line to verify against, or null to
     *        start empty and let ssh record what it finds
     */
    static KnownHostsFile create(String directory, String pinnedKey) {
        Path parent = Paths.get(directory)
        Files.createDirectories(parent)
        Path file = Files.createTempFile(parent, 'known_hosts', '')

        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString('rw-------')
            Files.setPosixFilePermissions(file, ownerOnly)
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem; ssh only enforces this on POSIX anyway
        }

        Files.writeString(file, pinnedKey ? pinnedKey.trim() + '\n' : '')
        return new KnownHostsFile(file)
    }

    /**
     * The host key ssh recorded, once a connection to an unpinned host
     * succeeded. Comment and empty lines are skipped.
     *
     * @return the key line, or null when nothing was recorded
     */
    String recordedKey() {
        try {
            return Files.readAllLines(path)
                    .collect { it.trim() }
                    .find { it && !it.startsWith('#') }
        } catch (IOException ignored) {
            return null
        }
    }

    @Override
    void close() {
        try {
            Files.deleteIfExists(path)
        } catch (IOException ignored) {
            // the startup sweep of the temp directory will get it
        }
    }
}
