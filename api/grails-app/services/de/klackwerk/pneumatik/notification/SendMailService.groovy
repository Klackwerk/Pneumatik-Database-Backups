package de.klackwerk.pneumatik.notification

import de.klackwerk.pneumatik.backup.Backup
import grails.core.GrailsApplication
import grails.gorm.transactions.Transactional
import grails.plugins.mail.MailService
import grails.util.Environment

import java.util.concurrent.ConcurrentHashMap

@Transactional
class SendMailService {

    static final int DEFAULT_SEND_ATTEMPTS = 3
    static final long RETRY_BASE_DELAY_MILLIS = 2000

    GrailsApplication grailsApplication
    MailService mailService

    /** how long after a failure mail the same database stays quiet */
    static final long DEFAULT_QUIET_HOURS = 6

    /** last failure mail per database — an hourly broken database would send 24 a day */
    private final Map<String, Long> lastFailureMail = new ConcurrentHashMap<>()

    void notifyOnFailedBackup(Backup backup) {
        String databaseId = backup.database?.id
        if (databaseId && isWithinQuietPeriod(databaseId)) {
            log.info "MAILSERVICE - Suppressing failure mail for ${backup.database.name}: one was already sent recently"
            return
        }
        log.debug "MAILSERVICE - Preparing mail for failed backup: ${backup.id}"
        if (send("Backup failed: ${backup.database.name}", failedBackupHtml(backup), backup) && databaseId) {
            lastFailureMail.put(databaseId, System.currentTimeMillis())
        }
    }

    private boolean isWithinQuietPeriod(String databaseId) {
        Long last = lastFailureMail.get(databaseId)
        if (last == null) {
            return false
        }
        long quietHours = grailsApplication.config.getProperty('pneumatik.notification.quiet-hours',
                Integer, DEFAULT_QUIET_HOURS as int) as long
        return System.currentTimeMillis() - last < quietHours * 60 * 60 * 1000
    }

    void clearFailureNotice(Backup backup) {
        if (backup.database?.id) {
            lastFailureMail.remove(backup.database.id)
        }
    }

    /**
     * Sent when a backup succeeds after the database's previous backup had
     * failed
     */
    void notifyOnRecoveredBackup(Backup backup) {
        log.debug "MAILSERVICE - Preparing mail for recovered backup: ${backup.id}"
        clearFailureNotice(backup)
        send("Backup recovered: ${backup.database.name}", recoveredBackupHtml(backup), backup)
    }

    /**
     * Sends a notification, retrying a few times before giving up
     *
     * @return whether the mail was accepted by the server
     */
    private boolean send(String subjectLine, String body, Backup backup) {
        String mailTo = grailsApplication.config.getProperty('grails.mail.adminMail')
        String mailSubject = "[Pneumatik] ${subjectLine}"
        if (Environment.isDevelopmentMode()) {
            mailSubject = "DEVELOPMENT - ${mailSubject}"
        }

        int attempts = grailsApplication.config.getProperty('pneumatik.notification.send-attempts',
                Integer, DEFAULT_SEND_ATTEMPTS)

        log.info "Will send mail to ${mailTo}"
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                mailService.sendMail {
                    to mailTo
                    subject mailSubject
                    html body
                }
                log.debug "MAILSERVICE - Sent mail for backup ${backup.id} to ${mailTo}"
                return true
            } catch (Exception e) {
                if (attempt == attempts) {
                    log.error "SENDING NOTIFICATION MAIL FAILED after ${attempts} attempt(s)", e
                } else {
                    log.warn "MAILSERVICE - Mail attempt ${attempt}/${attempts} failed, retrying: ${e.message}"
                    sleepBetweenAttempts(attempt)
                }
            }
        }
        return false
    }

    private static void sleepBetweenAttempts(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_DELAY_MILLIS * attempt)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
        }
    }

    protected static String failedBackupHtml(Backup backup) {
        String outputBlock = backup.output
                ? "<p><b>Command output:</b></p><pre>${escapeHtml(backup.output)}</pre>"
                : '<p>No command output was captured.</p>'
        return """\
<p>The backup of database <b>${escapeHtml(backup.database.name)}</b> on host \
<b>${escapeHtml(backup.database.host?.name ?: 'unknown')}</b> has failed.</p>
<table cellpadding="2" cellspacing="0">
  <tr><td><b>Executed at</b></td><td>${backup.executedAt ?: '—'}</td></tr>
  <tr><td><b>Finished at</b></td><td>${backup.finishedAt ?: '—'}</td></tr>
  <tr><td><b>Exit code</b></td><td>${backup.exitCode != null ? backup.exitCode : '—'}</td></tr>
</table>
${outputBlock}
<p>Please review the backup details in the Pneumatik dashboard and verify the \
database host is reachable. You will be notified when the next backup of this \
database completes successfully.</p>"""
    }

    protected static String recoveredBackupHtml(Backup backup) {
        return """\
<p>The backup of database <b>${escapeHtml(backup.database.name)}</b> on host \
<b>${escapeHtml(backup.database.host?.name ?: 'unknown')}</b> completed successfully \
after the previous attempt had failed. No further action is required.</p>
<table cellpadding="2" cellspacing="0">
  <tr><td><b>Executed at</b></td><td>${backup.executedAt ?: '—'}</td></tr>
  <tr><td><b>Finished at</b></td><td>${backup.finishedAt ?: '—'}</td></tr>
  <tr><td><b>Backup size</b></td><td>${backup.size ?: '—'}</td></tr>
</table>"""
    }

    private static String escapeHtml(String value) {
        value == null ? '' : value.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    }
}
