package de.klackwerk.pneumatik.stats

import de.klackwerk.pneumatik.backup.Trigger
import spock.lang.Specification

class StatsServiceSpec extends Specification {

    private static final Date NOW = new Date(1_800_000_000_000L)

    private static Date hoursAgo(double hours) {
        return new Date(NOW.time - (long) (hours * 60 * 60 * 1000))
    }

    void 'a database is stale once it misses twice its schedule interval'() {
        expect:
        StatsService.isStale(trigger, lastSuccess, NOW) == stale

        where:
        trigger                   | lastSuccess    || stale
        // one missed run is not yet an alarm
        Trigger.TRIGGER_HOURLY    | hoursAgo(1.5)  || false
        Trigger.TRIGGER_HOURLY    | hoursAgo(3)    || true
        Trigger.TRIGGER_4HOURLY   | hoursAgo(7)    || false
        Trigger.TRIGGER_4HOURLY   | hoursAgo(9)    || true
        Trigger.TRIGGER_12HOURLY  | hoursAgo(23)   || false
        Trigger.TRIGGER_12HOURLY  | hoursAgo(25)   || true
        Trigger.TRIGGER_DAILY     | hoursAgo(47)   || false
        Trigger.TRIGGER_DAILY     | hoursAgo(49)   || true
    }

    void 'a scheduled database that never succeeded is stale'() {
        expect:
        StatsService.isStale(Trigger.TRIGGER_DAILY, null, NOW)
    }

    void 'a manually triggered database is never stale — nothing is expected of it'() {
        expect:
        !StatsService.isStale(Trigger.TRIGGER_MANUAL, null, NOW)
        !StatsService.isStale(Trigger.TRIGGER_MANUAL, hoursAgo(10_000), NOW)
        !StatsService.isStale(null, null, NOW)
    }

    void 'the stale age is reported in whole days'() {
        expect:
        StatsService.daysSince(hoursAgo(hours), NOW) == days

        where:
        hours || days
        0     || 0
        23    || 0
        24    || 1
        49    || 2
        24 * 30 || 30
    }

    void 'there is no age when a backup never succeeded'() {
        expect:
        StatsService.daysSince(null, NOW) == null
    }

    void 'parses "#input" to #expected bytes'() {
        expect:
        StatsService.parseSizeToBytes(input) == expected

        where:
        input        | expected
        '12.345 MB'  | (long) (12.345 * 1024 * 1024)
        '0.001 MB'   | 1048L
        '512 KB'     | 512L * 1024
        '1.5 GB'     | (long) (1.5 * 1024 * 1024 * 1024)
        '2 TB'       | 2L * 1024 * 1024 * 1024 * 1024
        '100 B'      | 100L
        '7mb'        | 7L * 1024 * 1024
        null         | null
        ''           | null
        'garbage'    | null
        '12,3 MB'    | null
        'MB'         | null
    }
}
