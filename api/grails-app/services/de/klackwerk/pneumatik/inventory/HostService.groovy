package de.klackwerk.pneumatik.inventory

import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.credentials.CredentialService
import grails.gorm.transactions.Transactional

@Transactional
class HostService {

    CredentialService credentialService
    DatabaseService databaseService

    List<Host> listHosts() {
        log.debug 'HOSTSERVICE - List hosts'
        return Host.list(sort: 'id')
    }

    /** How much a delete would destroy — shown to the user before they confirm. */
    Map deletionImpact(Host host) {
        List<Database> databases = databasesOn(host)
        long backupCount = databases.inject(0L) { long total, Database database ->
            total + (databaseService.deletionImpact(database).backupCount as long)
        }

        return [
                databaseCount: databases.size(),
                databaseNames: databases*.name.sort(),
                backupCount  : backupCount,
        ]
    }

    private static List<Database> databasesOn(Host host) {
        return Database.createCriteria().list {
            eq 'host', host
        } as List<Database>
    }

    /**
     * Deletes a host together with every database on it — and therefore
     * those databases' backups and archives. The caller is expected to have
     * shown {@link #deletionImpact} first.
     *
     * @return the deletion impact that was actually carried out
     */
    Map deleteHost(Host host) {
        Map impact = deletionImpact(host)
        log.info "HOSTSERVICE - Deleting host ${host.name} with ${impact.databaseCount} database(s) " +
                "and ${impact.backupCount} backup(s)"

        databasesOn(host).each { Database database ->
            databaseService.deleteDatabase(database)
        }

        host.delete(flush: true)
        return impact
    }

    Host addHost(HostCommand cmd) {
        log.debug 'HOSTSERVICE - Create new Host'
        Host host = new Host()
        return setHostParams(cmd, host)
    }

    Host editHost(HostCommand cmd, Host host) {
        log.debug "HOSTSERVICE - Edit host ${host.id}"
        return setHostParams(cmd, host)
    }

    protected Host setHostParams(HostCommand cmd, Host host) {
        host.hostname = cmd.hostname
        host.friendlyName = cmd.friendlyName
        if (cmd.port) {
            host.port = cmd.port
        } else {
            log.debug 'HOSTSERVICE - Setting port to default MySQL Port 3306'
            host.port = 3306
        }
        host.sshHostname = cmd.sshHostname
        host.sshUser = cmd.sshUser
        if (cmd.sshPort) {
            host.sshPort = cmd.sshPort
        }
        if (cmd.sshKey) {
            log.debug 'HOSTSERVICE - Encrypting and setting SSH Key for Host'
            host.sshKey = credentialService.encryptString(cmd.sshKey)
        }
        if (cmd.useSSL != null) {
            host.useSSL = cmd.useSSL
        } else {
            host.useSSL = false
        }

        host.verifyHostKey = cmd.verifyHostKey ?: false
        // an empty string means "forget the pin and learn it again"; null
        // means the form did not touch it
        if (cmd.hostKey != null) {
            host.hostKey = cmd.hostKey.trim() ?: null
        }
        if (!host.verifyHostKey) {
            // a pin kept while unverified would silently become authoritative
            // the moment verification is switched back on
            host.hostKey = null
        }

        host.save(flush: true)
        return host
    }
}
