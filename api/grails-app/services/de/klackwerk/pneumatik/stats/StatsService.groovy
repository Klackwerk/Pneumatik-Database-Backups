package de.klackwerk.pneumatik.stats

import de.klackwerk.pneumatik.api.ApiMappers
import de.klackwerk.pneumatik.backup.Backup
import de.klackwerk.pneumatik.backup.BackupState
import de.klackwerk.pneumatik.backup.Trigger
import de.klackwerk.pneumatik.inventory.Database
import de.klackwerk.pneumatik.inventory.Host
import grails.gorm.transactions.Transactional

import java.time.LocalDate
import java.time.ZoneId
import java.util.regex.Matcher
import java.util.regex.Pattern

@Transactional(readOnly = true)
class StatsService {

    static final int ACTIVITY_DAYS = 14

    /** Backup.size is stored as a human string ("12.345 MB"), see BackupMySqlService. */
    private static final Pattern SIZE_PATTERN = ~/(?i)\s*([\d.]+)\s*(B|KB|MB|GB|TB)\s*/
    private static final Map<String, Long> UNIT_FACTORS = [
            B : 1L,
            KB: 1024L,
            MB: 1024L * 1024,
            GB: 1024L * 1024 * 1024,
            TB: 1024L * 1024 * 1024 * 1024,
    ].asImmutable()

    /**
     * Aggregates for the dashboard: overall totals, per-database backup count
     * and storage, and a zero-filled daily activity series for the last
     * {@link #ACTIVITY_DAYS} days.
     */
    Map dashboardStats() {
        ZoneId zone = ZoneId.systemDefault()
        LocalDate today = LocalDate.now(zone)

        Map<String, Long> storageByDatabase = [:].withDefault { 0L }
        long totalStorageBytes = 0
        List sizeRows = Backup.executeQuery(
                'select b.database.id, b.size, b.archivedSizeBytes from Backup b where b.size is not null or b.archivedSizeBytes is not null')
        sizeRows.each { row ->
            // prefer the exact on-disk size; fall back to parsing the legacy string
            Long bytes = (row[2] as Long) ?: parseSizeToBytes(row[1] as String)
            if (bytes != null) {
                storageByDatabase[row[0] as String] += bytes
                totalStorageBytes += bytes
            }
        }

        // the newest *successful* backup per database — the only date that
        // says anything about whether the data is actually recoverable
        Map<String, Date> lastSuccessByDatabase = [:]
        Database.executeQuery(
                'select b.database.id, max(b.createdAt) from Backup b where b.state = :state ' +
                        'group by b.database.id', [state: BackupState.FINISHED]).each { row ->
            lastSuccessByDatabase[row[0] as String] = row[1] as Date
        }

        List databaseRows = Database.executeQuery(
                'select d.id, coalesce(d.friendlyName, d.databaseName), count(b.id), max(b.createdAt), d.trigger ' +
                'from Database d left join d.backups b group by d.id, d.friendlyName, d.databaseName, d.trigger')
        Date now = new Date()
        List<Map> databases = databaseRows.collect { row ->
            String databaseId = row[0] as String
            Date lastSuccess = lastSuccessByDatabase[databaseId]
            Trigger trigger = row[4] as Trigger
            [
                    databaseId            : databaseId,
                    databaseName          : row[1] as String,
                    backupCount           : row[2] as Long,
                    storageBytes          : storageByDatabase[databaseId],
                    lastBackupAt          : ApiMappers.iso(row[3] as Date),
                    lastSuccessfulBackupAt: ApiMappers.iso(lastSuccess),
                    trigger               : trigger?.name(),
                    isStale               : isStale(trigger, lastSuccess, now),
                    staleSinceDays        : daysSince(lastSuccess, now),
            ]
        }.sort { -(it.storageBytes as long) }

        // never-succeeded first, then longest-stale — the order an operator
        // would want to work through them
        List<Map> stale = databases.findAll { it.isStale }
                .sort { -((it.staleSinceDays == null ? Integer.MAX_VALUE : it.staleSinceDays) as int) }

        Date activitySince = Date.from(today.minusDays(ACTIVITY_DAYS - 1).atStartOfDay(zone).toInstant())
        List activityRows = Backup.executeQuery(
                'select b.createdAt, b.state from Backup b where b.createdAt >= :since', [since: activitySince])
        Map<LocalDate, Map> byDay = [:]
        activityRows.each { row ->
            LocalDate day = (row[0] as Date).toInstant().atZone(zone).toLocalDate()
            Map bucket = byDay.computeIfAbsent(day) { [finished: 0L, failed: 0L, total: 0L] }
            bucket.total++
            if (row[1] == BackupState.FINISHED) bucket.finished++
            else if (row[1] == BackupState.FAILED) bucket.failed++
        }
        List<Map> activity = (0..<ACTIVITY_DAYS).collect { int offset ->
            LocalDate day = today.minusDays(ACTIVITY_DAYS - 1 - offset)
            Map bucket = byDay[day] ?: [finished: 0L, failed: 0L, total: 0L]
            [date: day.toString()] + bucket
        }

        Date weekAgo = Date.from(today.minusDays(6).atStartOfDay(zone).toInstant())
        long failedLast7Days = (Backup.executeQuery(
                'select count(b) from Backup b where b.state = :state and b.createdAt >= :since',
                [state: BackupState.FAILED, since: weekAgo])[0]) as long

        return [
                totals   : [
                        databases      : Database.count(),
                        hosts          : Host.count(),
                        backups        : Backup.count(),
                        storageBytes   : totalStorageBytes,
                        failedLast7Days: failedLast7Days,
                        staleDatabases : stale.size(),
                ],
                databases: databases,
                stale    : stale,
                activity : activity,
        ]
    }

    /** hours between scheduled runs; manual databases have no expectation */
    private static final Map<Trigger, Integer> SCHEDULE_INTERVAL_HOURS = [
            (Trigger.TRIGGER_HOURLY)  : 1,
            (Trigger.TRIGGER_4HOURLY) : 4,
            (Trigger.TRIGGER_12HOURLY): 12,
            (Trigger.TRIGGER_DAILY)   : 24,
    ].asImmutable()

    /**
     * Whether a database is overdue for a successful backup.
     *
     * A failure count alone does not answer "is my data safe": a database
     * whose backups quietly stopped being scheduled reports zero failures
     * while its last usable copy ages. The allowance is twice the schedule
     * interval, so one missed run is not yet an alarm. Manually-triggered
     * databases are never stale — nothing is expected of them.
     */
    static boolean isStale(Trigger trigger, Date lastSuccessfulBackup, Date now = new Date()) {
        Integer intervalHours = SCHEDULE_INTERVAL_HOURS[trigger]
        if (intervalHours == null) {
            return false
        }
        if (lastSuccessfulBackup == null) {
            return true
        }
        return now.time - lastSuccessfulBackup.time > 2L * intervalHours * 60 * 60 * 1000
    }

    /** Whole days between two instants, for display next to a stale warning. */
    static Integer daysSince(Date date, Date now = new Date()) {
        return date == null ? null : (int) ((now.time - date.time) / (24L * 60 * 60 * 1000))
    }

    /**
     * Parses a human-readable size like "12.345 MB" into bytes.
     * Returns null when the string is not parseable.
     */
    static Long parseSizeToBytes(String size) {
        if (!size) return null
        Matcher matcher = SIZE_PATTERN.matcher(size)
        if (!matcher.matches()) return null
        Long factor = UNIT_FACTORS[matcher.group(2).toUpperCase()]
        if (factor == null) return null
        try {
            return (new BigDecimal(matcher.group(1)) * factor).toLong()
        } catch (NumberFormatException ignored) {
            return null
        }
    }
}
