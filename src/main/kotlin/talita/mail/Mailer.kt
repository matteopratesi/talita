package talita.mail

import jakarta.mail.internet.InternetAddress
import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/** A mail of Talità: always plain text, written by the code that asks for it. */
data class OutgoingMail(val to: String, val subject: String, val text: String)

/** What happened to a mail. The reason for a failure is in the log, never in the result. */
enum class MailResult {
    /** The server accepted the mail. */
    SENT,

    /** Not sent: the limit on the mails of the day is reached. */
    DAILY_LIMIT,

    /** Not sent: no server configured, no sender, server down or too slow. */
    FAILED,
}

/**
 * The only way out for the mail of Talità (login links, invitations). It sends synchronously, so
 * the person knows at once if the mail did not leave; the SMTP timeouts (10 seconds, in
 * `application.yaml`) keep a silent server from holding the request for long.
 *
 * What it guarantees on every mail: sender with the configured name, text only (no HTML, no images,
 * no links to follow), UTF-8, and the headers that switch off the tracking of opens and clicks at
 * Mailjet. The text is written as it is, without HTML escaping.
 *
 * The log never carries the address of the recipient, the text or a link: a failure is logged with
 * the type of the exception only (the message of a mail exception often names the address).
 */
@Component
class Mailer(
    private val sender: JavaMailSender?,
    private val settings: MailSettings,
    private val quota: MailQuota,
) {

    private val log = LoggerFactory.getLogger(Mailer::class.java)

    fun send(mail: OutgoingMail): MailResult {
        if (sender == null || settings.from.isBlank()) {
            log.error("Mail not sent: {}.", if (sender == null) "no mail server configured" else "no sender address configured")
            return MailResult.FAILED
        }
        if (!quota.tryAcquire()) {
            log.warn("Mail not sent: the limit of {} mails in 24 hours is reached.", settings.dailyLimit)
            return MailResult.DAILY_LIMIT
        }
        return try {
            val message = sender.createMimeMessage()
            val helper = MimeMessageHelper(message, false, "UTF-8")
            helper.setFrom(InternetAddress(settings.from, settings.name, "UTF-8"))
            helper.setTo(mail.to)
            helper.setSubject(mail.subject)
            helper.setText(mail.text, false)
            // Mailjet reads these two headers and does not add the tracking pixel and the redirected links.
            message.setHeader("X-Mailjet-TrackOpen", "0")
            message.setHeader("X-Mailjet-TrackClick", "0")
            sender.send(message)
            MailResult.SENT
        } catch (failure: Exception) {
            quota.release()
            log.error("Mail not sent: {}.", failure.javaClass.name)
            MailResult.FAILED
        }
    }
}
