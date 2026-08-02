package de.klackwerk.pneumatik.backup

import groovy.transform.CompileStatic

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Runs backup shell commands without ever pulling dump data into the JVM:
 * the process' stdout is redirected to a file by the OS (or captured with a
 * hard cap for small probe commands), stderr is drained concurrently into a
 * capped buffer so a chatty command can neither fill the heap nor deadlock
 * on a full pipe, and secrets can be fed through stdin so they never appear
 * on disk or in the argument list.
 */
@CompileStatic
class BackupCommandRunner {

    /** cap for captured output; enough for any dump tool's diagnostics */
    static final int MAX_CAPTURED_BYTES = 64 * 1024

    @CompileStatic
    static class CommandResult {
        int exitCode
        String output
        boolean timedOut
    }

    /**
     * Runs a command with stdout streamed to {@code stdoutFile} by the OS.
     * Captured output is stderr only.
     *
     * @param stdinContent written to the process' stdin and closed; null closes stdin immediately
     * @param environment extra environment variables — the channel for secrets
     *        that must not appear on the command line
     */
    static CommandResult runToFile(List<String> command, File stdoutFile, String stdinContent, long timeoutMinutes,
                                   Map<String, String> environment = [:]) {
        stdoutFile.parentFile?.mkdirs()
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(stdoutFile)
        return execute(builder, stdinContent, timeoutMinutes, environment)
    }

    /**
     * Runs a command capturing stdout and stderr together (capped). For
     * small probe commands like version detection — never for dumps.
     */
    static CommandResult runCaptured(List<String> command, String stdinContent, long timeoutMinutes,
                                     Map<String, String> environment = [:]) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
        return execute(builder, stdinContent, timeoutMinutes, environment)
    }

    /**
     * Kills the process and everything it started.
     *
     * The process we hold is a wrapper — `ssh-agent`, or bash for a local
     * dump. Destroying only that leaves the actual `mysqldump` or `ssh`
     * running: still reading the source database, still writing to a file
     * nobody will keep. Descendants are taken first so nothing gets a chance
     * to be reparented away as the wrapper dies.
     */
    private static void killTree(Process process) {
        List<ProcessHandle> descendants = process.toHandle().descendants().toList()
        descendants.each { ProcessHandle handle -> handle.destroy() }

        // give them a moment to exit on the polite signal
        descendants.each { ProcessHandle handle ->
            try {
                handle.onExit().get(5, TimeUnit.SECONDS)
            } catch (Exception ignored) {
                handle.destroyForcibly()
            }
        }
        process.destroyForcibly()
    }

    private static CommandResult execute(ProcessBuilder builder, String stdinContent, long timeoutMinutes,
                                         Map<String, String> environment) {
        if (environment) {
            builder.environment().putAll(environment)
        }
        Process process = builder.start()

        Thread stdinWriter = null
        if (stdinContent != null) {
            // written from a thread so a process that never reads stdin
            // cannot block the backup; the stream is closed either way
            stdinWriter = Thread.startDaemon('backup-stdin') {
                try {
                    process.outputStream.withCloseable { OutputStream out ->
                        out.write(stdinContent.getBytes(StandardCharsets.UTF_8))
                    }
                } catch (IOException ignored) {
                    // process exited before reading stdin — its exit code tells the story
                }
            }
        } else {
            process.outputStream.close()
        }

        ByteArrayOutputStream captured = new ByteArrayOutputStream()
        boolean truncated = false
        Thread drainer = Thread.startDaemon('backup-output-drain') {
            try {
                InputStream stream = builder.redirectErrorStream() ? process.inputStream : process.errorStream
                byte[] buffer = new byte[8192]
                int read
                while ((read = stream.read(buffer)) != -1) {
                    if (captured.size() < MAX_CAPTURED_BYTES) {
                        captured.write(buffer, 0, Math.min(read, MAX_CAPTURED_BYTES - captured.size()))
                    } else {
                        truncated = true // keep draining so the pipe never fills up
                    }
                }
            } catch (IOException ignored) {
                // stream closed on process death — nothing left to drain
            }
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            killTree(process)
            process.waitFor(10, TimeUnit.SECONDS)
        }
        drainer.join(TimeUnit.SECONDS.toMillis(5))
        stdinWriter?.join(TimeUnit.SECONDS.toMillis(5))

        String output = captured.toString(StandardCharsets.UTF_8)
        if (truncated) {
            output += "\n[output truncated at ${MAX_CAPTURED_BYTES / 1024} KB]"
        }
        if (!finished) {
            output += "\n[process killed after exceeding the ${timeoutMinutes} minute timeout]"
        }
        return new CommandResult(exitCode: finished ? process.exitValue() : -1, output: output.trim(), timedOut: !finished)
    }
}
