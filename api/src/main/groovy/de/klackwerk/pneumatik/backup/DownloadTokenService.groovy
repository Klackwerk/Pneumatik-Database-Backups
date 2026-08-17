package de.klackwerk.pneumatik.backup

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.commons.lang3.RandomStringUtils

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived, single-use tickets for downloading a backup archive.
 *
 * A browser navigation cannot carry an Authorization header, so downloading
 * through `fetch` meant holding the whole archive in memory before offering
 * it to the user — impossible for the multi-gigabyte dumps this tool exists
 * to produce. The client asks for a ticket with its bearer token, then
 * points a plain link at the download URL and the browser streams it to disk
 * with its own progress and resume handling.
 *
 * Tickets live in memory: they are valid for seconds, and a restart
 * invalidating them is the correct behaviour, not a limitation.
 */
@Slf4j
@CompileStatic
class DownloadTokenService {

    static final int TOKEN_LENGTH = 43
    /** long enough to start a download, short enough that a leaked URL is worthless */
    static final long DEFAULT_TTL_SECONDS = 60

    long ttlSeconds = DEFAULT_TTL_SECONDS

    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>()

    @CompileStatic
    private static class Ticket {
        String backupId
        String username
        long expiresAt
    }

    /** @return the ticket to put in the download URL */
    String issue(String backupId, String username) {
        purgeExpired()
        String token = RandomStringUtils.random(TOKEN_LENGTH, 0, 0, true, true, null, new SecureRandom())
        tickets.put(token, new Ticket(backupId: backupId, username: username,
                expiresAt: System.currentTimeMillis() + ttlSeconds * 1000))
        return token
    }

    /**
     * Redeems a ticket for exactly one backup. The ticket is removed whether
     * or not it matched, so a guessed or replayed one gets a single attempt.
     *
     * @return the username it was issued to, or null when it is not valid
     */
    String consume(String token, String backupId) {
        if (!token) {
            return null
        }
        Ticket ticket = tickets.remove(token)
        if (!ticket) {
            return null
        }
        if (ticket.expiresAt < System.currentTimeMillis()) {
            log.debug 'DOWNLOADTOKENSERVICE - Rejected an expired download ticket'
            return null
        }
        if (ticket.backupId != backupId) {
            log.warn "DOWNLOADTOKENSERVICE - Download ticket of ${ticket.username} presented for a different backup"
            return null
        }
        return ticket.username
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis()
        tickets.entrySet().removeIf { Map.Entry<String, Ticket> entry -> entry.value.expiresAt < now }
    }
}
