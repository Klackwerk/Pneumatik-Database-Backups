package de.klackwerk.pneumatik.backup

import groovy.transform.CompileStatic

/**
 * POSIX shell quoting for the one code path that cannot avoid a shell.
 *
 * Local dumps run as a plain argv through ProcessBuilder and need no quoting
 * at all — nothing ever parses them. Remote dumps do need it: ssh joins its
 * arguments with spaces and hands the result to the remote user's login
 * shell, so every token that originates from inventory data (database and
 * host names, logins, ports) has to survive exactly one round of shell
 * parsing unchanged.
 */
@CompileStatic
class ShellCommand {

    /**
     * Wraps a value in single quotes, which suppress every form of shell
     * expansion. A literal single quote is the only character that cannot
     * appear inside such a string, so it is closed, escaped and reopened.
     */
    static String quote(String value) {
        if (value == null) {
            return "''"
        }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    /** Renders an argv as one shell-safe command string. */
    static String join(List<String> argv) {
        return argv.collect { quote(it) }.join(' ')
    }

    /**
     * Renders {@code NAME=value} assignments to prefix a remote command
     * with. Names are compile-time constants; only the values are quoted.
     */
    static String environmentPrefix(Map<String, String> environment) {
        return environment.collect { String name, String value -> name + '=' + quote(value) }.join(' ')
    }
}
