package talita.mail

import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/**
 * A mail of Talità: always plain text, written by the code that asks for it.
 *
 * @property unsubscribe the address of the page where the person stops receiving this kind of mail (TALITA-13). When there is one, [Mailer] writes it in the
 * header `List-Unsubscribe` (between angle brackets, as RFC 2369 wants). It is not the «one click» of RFC 8058: the page asks for a tap, as the one of the login
 * link does, because the mail programs open the links to look at them.
 */
data class OutgoingMail(val to: String, val subject: String, val text: String, val unsubscribe: String? = null)

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
 * The only way out for the mail of Talità (login links, invitations, reminders). It sends synchronously, so
 * the person knows at once if the mail did not leave; the SMTP timeouts (10 seconds, in
 * `application.yaml`) keep a silent server from holding the request for long.
 *
 * What it guarantees on every mail: sender with the configured name, text only (no HTML, no images,
 * no links to follow), UTF-8, and the headers that switch off the tracking of opens and clicks at
 * Mailjet. The text is written as it is, without HTML escaping.
 *
 * [send] is for one mail on the first count of the day (links and invitations). [sendAll] is for a list, in **one connection** to the server (so that 40 mails
 * stay well under the 20 seconds that htmx waits), with an outcome for each mail, on the count of the kind that the caller says (the reminders have their own).
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
            sender.send(message(sender, mail))
            MailResult.SENT
        } catch (failure: Exception) {
            quota.release()
            log.error("Mail not sent: {}.", failure.javaClass.name)
            MailResult.FAILED
        }
    }

    /**
     * Sends [mails] in one connection and says what became of each, in the same order. All or none on the count of the day: if [kind] has not [mails] left,
     * nothing leaves and every result is [MailResult.DAILY_LIMIT] (the caller asks [MailQuota.remaining] first to say how many it can send). A mail that does not
     * leave gives its place back. One that cannot even be built (an address that is not one) is a failure of that mail only.
     */
    fun sendAll(mails: List<OutgoingMail>, kind: MailKind): List<MailResult> {
        if (mails.isEmpty()) return emptyList()
        if (sender == null || settings.from.isBlank()) {
            log.error("Mails not sent: {}.", if (sender == null) "no mail server configured" else "no sender address configured")
            return mails.map { MailResult.FAILED }
        }
        if (!quota.tryAcquire(kind, mails.size)) {
            log.warn("Mails not sent: the limit of {} mails in 24 hours is reached.", quota.limit(kind))
            return mails.map { MailResult.DAILY_LIMIT }
        }
        val results = MutableList(mails.size) { MailResult.FAILED }
        val reasons = sortedSetOf<String>()
        val built = mutableListOf<Pair<Int, MimeMessage>>()
        mails.forEachIndexed { index, mail ->
            try {
                built += index to message(sender, mail)
            } catch (failure: Exception) {
                reasons += failure.javaClass.name
            }
        }
        if (built.isNotEmpty()) {
            try {
                sender.send(*built.map { it.second }.toTypedArray())
                built.forEach { (index, _) -> results[index] = MailResult.SENT }
            } catch (failure: MailSendException) {
                // The messages that failed are the keys of the map; the others, in the same connection, left.
                val failed = failure.failedMessages.keys
                built.forEach { (index, message) -> if (failed.none { it === message }) results[index] = MailResult.SENT }
                failure.failedMessages.values.forEach { reasons += it.javaClass.name }
                if (failure.failedMessages.isEmpty()) {
                    built.forEach { (index, _) -> results[index] = MailResult.FAILED }
                    reasons += failure.javaClass.name
                }
            } catch (failure: Exception) {
                reasons += failure.javaClass.name
            }
        }
        val notSent = results.count { it != MailResult.SENT }
        if (notSent > 0) {
            quota.release(kind, notSent)
            log.error("Mails not sent: {} of {} ({}).", notSent, mails.size, reasons.joinToString(", "))
        }
        return results
    }

    /** The message of [mail], with what every mail of Talità has. */
    private fun message(sender: JavaMailSender, mail: OutgoingMail): MimeMessage {
        val message = sender.createMimeMessage()
        val helper = MimeMessageHelper(message, false, "UTF-8")
        helper.setFrom(InternetAddress(settings.from, settings.name, "UTF-8"))
        helper.setTo(mail.to)
        helper.setSubject(mail.subject)
        helper.setText(mail.text, false)
        // Mailjet reads these two headers and does not add the tracking pixel and the redirected links.
        message.setHeader("X-Mailjet-TrackOpen", "0")
        message.setHeader("X-Mailjet-TrackClick", "0")
        mail.unsubscribe?.let { message.setHeader("List-Unsubscribe", "<$it>") }
        return message
    }
}
