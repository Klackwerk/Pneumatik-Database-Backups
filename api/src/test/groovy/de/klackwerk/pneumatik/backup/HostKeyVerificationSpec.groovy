package de.klackwerk.pneumatik.backup

import de.klackwerk.pneumatik.inventory.Host
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path

class HostKeyVerificationSpec extends Specification {

    private static final String KEY_LINE = 'jump.example.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI0000000000'

    Host host

    void setup() {
        host = new Host(hostname: 'db.example.com', sshHostname: 'jump.example.com', sshUser: 'backup', sshPort: 22)
    }

    void 'verification off keeps the previous behaviour and writes to no key store'() {
        given:
        host.verifyHostKey = false

        when:
        String options = BackupService.hostKeyOptions(host, '/tmp/known_hosts')

        then:
        options.contains('StrictHostKeyChecking=no')

        and: 'an unverified run must not seed a store a verified run would then trust'
        options.contains('UserKnownHostsFile=/dev/null')
    }

    void 'verification on with no pinned key accepts and records the first one it sees'() {
        given:
        host.verifyHostKey = true
        host.hostKey = null

        expect:
        BackupService.hostKeyOptions(host, '/tmp/known_hosts').contains('StrictHostKeyChecking=accept-new')
    }

    void 'verification on with a pinned key demands an exact match'() {
        given:
        host.verifyHostKey = true
        host.hostKey = KEY_LINE

        when:
        String options = BackupService.hostKeyOptions(host, '/tmp/known hosts')

        then:
        options.contains('StrictHostKeyChecking=yes')

        and: 'the path is quoted — it is interpolated into a shell command'
        options.contains("UserKnownHostsFile='/tmp/known hosts'")
    }

    void 'no known_hosts file means no verification, whatever the host says'() {
        given: 'defensive: a caller that forgot to create the file'
        host.verifyHostKey = true
        host.hostKey = KEY_LINE

        expect: 'better to connect unverified than to point ssh at a file that is not there'
        BackupService.hostKeyOptions(host, null).contains('StrictHostKeyChecking=no')
    }
}

class KnownHostsFileSpec extends Specification {

    @TempDir
    Path tempDir

    private static final String KEY_LINE = 'jump.example.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI0000000000'

    void 'a pinned key is written out for ssh to verify against'() {
        when:
        KnownHostsFile file = KnownHostsFile.create(tempDir.toString(), KEY_LINE)

        then:
        Files.readString(file.path).trim() == KEY_LINE

        cleanup:
        file.close()
    }

    void 'the file is owner-only — ssh refuses a known_hosts others can write'() {
        when:
        KnownHostsFile file = KnownHostsFile.create(tempDir.toString(), KEY_LINE)

        then:
        Files.getPosixFilePermissions(file.path)*.toString().sort() == ['OWNER_READ', 'OWNER_WRITE'].sort()

        cleanup:
        file.close()
    }

    void 'an unpinned host starts with an empty file for ssh to fill in'() {
        when:
        KnownHostsFile file = KnownHostsFile.create(tempDir.toString(), null)

        then:
        Files.readString(file.path) == ''
        file.recordedKey() == null

        cleanup:
        file.close()
    }

    void 'the key ssh recorded can be read back for pinning'() {
        given:
        KnownHostsFile file = KnownHostsFile.create(tempDir.toString(), null)

        when: 'ssh appends what it saw, with its usual comment header'
        Files.writeString(file.path, "# comment line\n\n${KEY_LINE}\n")

        then:
        file.recordedKey() == KEY_LINE

        cleanup:
        file.close()
    }

    void 'closing removes the file'() {
        given:
        KnownHostsFile file = KnownHostsFile.create(tempDir.toString(), KEY_LINE)

        when:
        file.close()

        then:
        !Files.exists(file.path)

        and: 'closing twice is harmless'
        file.close()
    }
}
