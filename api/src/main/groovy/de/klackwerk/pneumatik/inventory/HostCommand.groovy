package de.klackwerk.pneumatik.inventory

import grails.validation.Validateable

class HostCommand implements Validateable {

    /** set for edits so uniqueness checks can exclude the record itself */
    String id

    String  friendlyName
    String  hostname
    Integer port
    String  sshHostname
    String  sshUser
    Integer sshPort
    String  sshKey
    Boolean useSSL
    Boolean verifyHostKey
    String  hostKey

    static constraints = {
        id nullable: true
        friendlyName nullable: true, blank: false, validator: { String val, HostCommand obj ->
            if (val) {
                Host existing = Host.findByFriendlyName(val)
                if (existing && existing.id != obj.id) {
                    return ['unique']
                }
            }
        }
        hostname    nullable: false, blank: false, matches: InventoryPatterns.HOSTNAME
        port        nullable: true, blank: false
        sshHostname nullable: true, blank: false, matches: InventoryPatterns.HOSTNAME
        sshUser     nullable: true, blank: false, matches: InventoryPatterns.LOGIN
        sshPort     nullable: true, blank: false
        sshKey        nullable: true, blank: false
        useSSL        nullable: true
        verifyHostKey nullable: true
        hostKey       nullable: true
    }
}
