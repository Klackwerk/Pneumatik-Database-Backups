package de.klackwerk.pneumatik.inventory

import groovy.util.logging.Slf4j

@Slf4j
class Host {

    String  id
    String  friendlyName
    String  hostname
    Integer port = 3306
    String  sshHostname
    String  sshUser
    Integer sshPort
    String  sshKey
    Boolean useSSL

    /**
     * Whether the SSH host key must match {@link #hostKey}.
     *
     * Off by default so upgrades keep working, but it is what stops a
     * machine-in-the-middle collecting the database password — it is piped
     * through ssh's stdin to the far end. With verification on and no key
     * pinned yet, the first connection pins what it sees and every later one
     * must match.
     *
     * Default will change in v4
     */
    Boolean verifyHostKey = false
    /** the pinned key, in known_hosts format; learned on first connect */
    String  hostKey

    static constraints = {
        friendlyName    nullable: true, blank: false, unique: true
        // hostnames and logins reach the dump command
        hostname        nullable: false, blank: false, unique: false, matches: InventoryPatterns.HOSTNAME
        port            nullable: false, blank: false
        sshHostname     nullable: true, blank: false, matches: InventoryPatterns.HOSTNAME
        sshUser         nullable: true, blank: false, matches: InventoryPatterns.LOGIN
        sshPort         nullable: true, blank: false
        sshKey          nullable: true, blank: false
        verifyHostKey   nullable: false
        hostKey         nullable: true, blank: false
    }

    static mapping = {
        table 'host'
        id generator: 'uuid2'
        sshKey type: 'text'
        hostKey type: 'text'
    }

    String getName() {
        if (friendlyName) {
            return friendlyName
        } else {
            return hostname
        }
    }

    Boolean getExecuteViaSSH() {
        if (sshKey && sshUser && sshHostname) {
            log.debug "HOST - All parameters for SSH connection set, id: ${id}"
            return true
        } else {
            log.debug "HOST - Not all parameters for SSH connection set, id: ${id}"
            return false
        }
    }
}
