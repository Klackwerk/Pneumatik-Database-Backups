package de.klackwerk.pneumatik.backup

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

class BackupCommandRunnerSpec extends Specification {

    @TempDir
    Path tempDir

    void 'stdout streams to the file while stderr is captured as output'() {
        given:
        File target = tempDir.resolve('dump.sql').toFile()

        when:
        BackupCommandRunner.CommandResult result = BackupCommandRunner.runToFile(
                ['/bin/bash', '-c', 'echo DUMP-DATA; echo warning >&2'], target, null, 1)

        then:
        result.exitCode == 0
        !result.timedOut
        target.text.trim() == 'DUMP-DATA'
        result.output == 'warning'
    }

    void 'a failing command reports its exit code and stderr'() {
        given:
        File target = tempDir.resolve('dump.sql').toFile()

        when:
        BackupCommandRunner.CommandResult result = BackupCommandRunner.runToFile(
                ['/bin/bash', '-c', 'echo boom >&2; exit 7'], target, null, 1)

        then:
        result.exitCode == 7
        result.output == 'boom'
    }

    void 'stdin content reaches the process without touching the filesystem'() {
        when:
        BackupCommandRunner.CommandResult result = BackupCommandRunner.runCaptured(
                ['/bin/bash', '-c', 'cat'], 'secret-key\n', 1)

        then:
        result.exitCode == 0
        result.output == 'secret-key'
    }

    void 'environment variables reach the process without appearing in the command'() {
        when:
        BackupCommandRunner.CommandResult result = BackupCommandRunner.runCaptured(
                ['/bin/bash', '-c', 'echo "$SECRET_VAR"'], null, 1, [SECRET_VAR: 'shhh'])

        then:
        result.exitCode == 0
        result.output == 'shhh'
    }

    void 'runCaptured merges stdout and stderr'() {
        when:
        BackupCommandRunner.CommandResult result = BackupCommandRunner.runCaptured(
                ['/bin/bash', '-c', 'echo out; echo err >&2'], null, 1)

        then:
        result.exitCode == 0
        result.output.readLines().toSorted() == ['err', 'out']
    }

    void 'a chatty stderr neither fills the heap nor deadlocks the process'() {
        given: 'more stderr than the pipe buffer and the capture cap can hold'
        File target = tempDir.resolve('dump.sql').toFile()

        when:
        BackupCommandRunner.CommandResult result = BackupCommandRunner.runToFile(
                ['/bin/bash', '-c', 'yes error-line | head -c 1000000 >&2; echo done'], target, null, 1)

        then:
        result.exitCode == 0
        result.output.getBytes('UTF-8').length <= BackupCommandRunner.MAX_CAPTURED_BYTES + 100
        result.output.contains('[output truncated')
    }

    void 'a process exceeding the timeout is killed and flagged'() {
        given:
        File target = tempDir.resolve('dump.sql').toFile()

        when: 'timeout of 1 minute is the minimum — use a subshell that sleeps longer via a tiny timeout instead'
        BackupCommandRunner.CommandResult result = BackupCommandRunner.runToFile(
                ['/bin/bash', '-c', 'sleep 120'], target, null, 0)

        then:
        result.timedOut
        result.exitCode == -1
        result.output.contains('timeout')
    }
}
