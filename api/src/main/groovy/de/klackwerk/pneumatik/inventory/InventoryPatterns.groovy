package de.klackwerk.pneumatik.inventory

import groovy.transform.CompileStatic

/**
 * Character allowlists for the inventory values that end up in a dump
 * command or a dump filename.
 *
 * These are not what makes execution safe — commands are assembled as argv
 * lists and remote commands are shell-quoted (see
 * de.klackwerk.pneumatik.backup.ShellCommand). They keep input that could
 * only ever be a mistake or an attack out of the inventory in the first
 * place, and keep derived filenames readable.
 */
@CompileStatic
class InventoryPatterns {

    /** SQL identifier: letters, digits, underscore, dollar, dot, dash */
    static final String IDENTIFIER = '[A-Za-z0-9_][A-Za-z0-9_$.-]*'

    /** login name: an identifier plus '@' for MySQL-style user@host logins */
    static final String LOGIN = '[A-Za-z0-9_][A-Za-z0-9_$.@-]*'

    /** DNS name, IPv4 address or IPv6 literal */
    static final String HOSTNAME = '[A-Za-z0-9_][A-Za-z0-9_.:-]*'
}
