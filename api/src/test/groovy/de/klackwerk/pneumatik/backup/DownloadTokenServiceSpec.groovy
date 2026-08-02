package de.klackwerk.pneumatik.backup

import spock.lang.Specification

class DownloadTokenServiceSpec extends Specification {

    DownloadTokenService service

    void setup() {
        service = new DownloadTokenService()
    }

    void 'a ticket redeems once for the backup it was issued for'() {
        given:
        String token = service.issue('backup-1', 'admin')

        expect:
        service.consume(token, 'backup-1') == 'admin'

        and: 'a replay of the same URL gets nothing'
        service.consume(token, 'backup-1') == null
    }

    void 'a ticket is useless for a different backup'() {
        given:
        String token = service.issue('backup-1', 'admin')

        expect:
        service.consume(token, 'backup-2') == null

        and: 'and it is spent by the attempt, not left for a retry'
        service.consume(token, 'backup-1') == null
    }

    void 'an expired ticket is refused'() {
        given: 'a ticket that was already stale when it was minted'
        service.ttlSeconds = -1
        String token = service.issue('backup-1', 'admin')

        expect:
        service.consume(token, 'backup-1') == null
    }

    void 'an unknown or missing ticket is refused'() {
        expect:
        service.consume('made-up', 'backup-1') == null
        service.consume(null, 'backup-1') == null
        service.consume('', 'backup-1') == null
    }

    void 'tickets are unguessable and unique per request'() {
        when:
        List<String> tokens = (1..50).collect { service.issue('backup-1', 'admin') }

        then:
        tokens.unique().size() == 50
        tokens.every { it.length() == DownloadTokenService.TOKEN_LENGTH }
    }
}
