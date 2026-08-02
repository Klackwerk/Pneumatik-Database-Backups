package de.klackwerk.pneumatik.api

import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.RetentionPolicy
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import de.klackwerk.pneumatik.security.ApiKey
import de.klackwerk.pneumatik.security.User
import groovy.transform.CompileStatic

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Domain → JSON-shape mappers. Domains are never rendered directly.
 * these mappers are the single place that decides what leaves the server,
 * secrets (passwords, SSH keys, key hashes) never do.
 */
@CompileStatic
class ApiMappers {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT

    static String iso(Date date) {
        date == null ? null : ISO.format(date.toInstant().atOffset(ZoneOffset.UTC))
    }

    static Map hostToMap(Host host) {
        [
                id          : host.id,
                friendlyName: host.friendlyName,
                name        : host.name,
                hostname    : host.hostname,
                port        : host.port,
                sshHostname : host.sshHostname,
                sshUser     : host.sshUser,
                sshPort     : host.sshPort,
                hasSshKey   : host.sshKey != null,
                useSSL      : host.useSSL ?: false,
                // the host key is public and worth showing: it is what the
                // operator compares against `ssh-keyscan` output
                verifyHostKey: host.verifyHostKey ?: false,
                hostKey     : host.hostKey,
        ]
    }

    static Map databaseToMap(Database database) {
        [
                id             : database.id,
                friendlyName   : database.friendlyName,
                name           : database.name,
                databaseName   : database.databaseName,
                hostId         : database.host?.id,
                hostName       : database.host?.name,
                user           : database.user,
                hasPassword    : database.password != null,
                storageProvider: database.storageProvider?.name(),
                trigger        : database.trigger?.name(),
                databaseType   : database.databaseType?.name(),
        ]
    }

    static Map backupToMap(Backup backup) {
        [
                id               : backup.id,
                databaseId       : backup.database?.id,
                databaseName     : backup.database?.name,
                createdAt        : iso(backup.createdAt),
                executedAt       : iso(backup.executedAt),
                finishedAt       : iso(backup.finishedAt),
                durationMs       : backup.executedAt && backup.finishedAt
                        ? backup.finishedAt.time - backup.executedAt.time : null,
                filename         : backup.filename,
                size             : backup.size,
                rawSizeBytes     : backup.rawSizeBytes,
                archivedSizeBytes: backup.archivedSizeBytes,
                encrypted        : backup.encrypted ?: false,
                state            : backup.state?.name(),
                exitCode         : backup.exitCode,
                success          : backup.success,
                storageProvider  : backup.storageProvider?.name(),
                createdBy        : backup.createdBy?.username,
                trigger          : backup.createdBy ? null : backup.database?.trigger?.name(),
        ]
    }

    /** Detail view: the listing shape plus the captured command output. */
    static Map backupDetailToMap(Backup backup) {
        backupToMap(backup) + [output: backup.output]
    }

    static Map apiKeyToMap(ApiKey apiKey) {
        List<Database> scope = (apiKey.databases ?: []) as List<Database>
        [
                id             : apiKey.id,
                keyHint        : apiKey.keyHint,
                comment        : apiKey.comment,
                createdAt      : iso(apiKey.createdAt),
                validUntil     : iso(apiKey.validUntil),
                lastConnectedAt: iso(apiKey.lastConnectedAt),
                isValid        : apiKey.isValid,
                createdBy      : apiKey.createdBy?.username,
                databaseIds    : scope*.id.sort(),
                databaseNames  : scope*.name.sort(),
        ]
    }

    static Map userToMap(User user) {
        [
                id      : user.id,
                username: user.username,
                email   : user.email,
                enabled : user.enabled,
                roles   : user.authorities*.authority.sort(),
        ]
    }

    static Map retentionPolicyToMap(RetentionPolicy policy) {
        [
                id        : policy.id,
                databaseId: policy.database?.id,
                keepCount : policy.keepCount,
                keepDays  : policy.keepDays,
                enabled   : policy.enabled,
        ]
    }
}
