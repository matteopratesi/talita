package talita.mail

import jakarta.mail.SendFailedException
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Date
import kotlin.concurrent.thread

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
 * stay well under the 20 seconds that htmx waits), with an outcome for each mail, on the count of the kind that the caller says (the reminders have their own), and
 * **with a limit for the whole list**: [LIST_LIMIT], 15 seconds (TALITA-16, criterion 21). The timeouts of the connection (10 seconds) are for one step each, and a server that
 * answers nothing makes the steps add up (the greeting, then `EHLO`, then `HELO`; or a pause at every mail of a list of 40): the limit is for all of them together.
 *
 * What happens when the limit passes, or when the server stops answering: the mails that left are SENT, the ones that did not leave are FAILED **at once**, each without
 * waiting for its own 10 seconds, and the caller goes on. The same when the server refuses the whole sending (the sender, `DATA`, the text, or a temporary limit): the list stops at the
 * first refusal. The sending runs in a thread of its own that the caller stops waiting for. **When the 15 seconds are over, no new mail
 * starts (the thread looks before each one); the mail that is in progress at that moment still arrives, if the server takes it, but it is not counted**: it is FAILED for the
 * caller, so the page and the register can say one mail less than the server took (a decision of Matteo, docs/05, «Parte 5»). **Two effects, accepted by Matteo** (CodeRabbit, PR 14,
 * comment 4431895585): the caller goes on, so the caller lets go of its lock and this class gives back the place of every mail that is not SENT, the one in progress too. (1) The day count
 * can say one mail less than the server took. (2) If no mail was counted (nothing in the register, so no wait of 7 days), a teacher who sends again before the thread has closed, at
 * most one timeout later, can send the same reminder a second time to the person whose mail was in progress. The thread closes the connection when the step that it was in ends, at most
 * one timeout later. A server that refuses the **recipient** of a mail (a plain `SendFailedException`: a 550, or a 4xx such as greylisting or a mailbox that is full) is
 * not that: it is a failure of that mail alone, and the others go on in the same connection. The refusals of the whole sending (`SMTPSendFailedException`) stop the list.
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

    /** The most that a list takes ([LIST_LIMIT]); only the tests of the limit make it shorter, so that the boundary can be tried in seconds and not in fifteen. */
    internal var listLimit: Duration = LIST_LIMIT

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
        if (built.isNotEmpty()) deliver(sender, built, results, reasons)
        val notSent = results.count { it != MailResult.SENT }
        if (notSent > 0) {
            quota.release(kind, notSent)
            log.error("Mails not sent: {} of {} ({}).", notSent, mails.size, reasons.joinToString(", "))
        }
        return results
    }

    /**
     * Sends [built] in one connection, in a thread of its own, and waits for it at most [listLimit]. The limit is a **moment** (`deadline`, on the monotonic clock), the same for the
     * caller and for the thread: the thread looks at it, under [gate], before every mail and when a mail ends, so it does not depend on the caller having got there first. [results] and
     * [reasons] are written by that thread only before the deadline (under [gate]): when it passes, what has not been written stays FAILED, and what the thread does after that is not
     * counted (the mail that was in progress may still arrive, and stays FAILED for the caller: the numbers say one mail less, never one more; the place of that mail in the day count is
     * given back, and the lock of the caller is let go: see the class), so the numbers that the caller gives are those of the moment when it gave up. No mail starts after the deadline.
     */
    private fun deliver(sender: JavaMailSender, built: List<Pair<Int, MimeMessage>>, results: MutableList<MailResult>, reasons: MutableSet<String>) {
        val deadline = System.nanoTime() + listLimit.toNanos()
        val gate = Any()
        var gaveUp = false
        // Under [gate]: the time is over, for good. The reason is written once, by whoever finds it first (the thread, at a mail, or the caller, at the join).
        fun giveUp() {
            if (!gaveUp) {
                gaveUp = true
                reasons += "list limit of ${listLimit.toSeconds()} seconds"
            }
        }
        // Under [gate]: whether the deadline has passed (and then it is over for good).
        fun timeIsOver(): Boolean {
            if (System.nanoTime() - deadline >= 0) giveUp()
            return gaveUp
        }
        val worker = thread(isDaemon = true, name = "mail-list") {
            var transport: Transport? = null
            try {
                for ((index, message) in built) {
                    if (synchronized(gate) { timeIsOver() }) return@thread
                    try {
                        if (transport?.isConnected != true) {
                            runCatching { transport?.close() }
                            transport = connect(sender)
                        }
                        if (message.sentDate == null) message.sentDate = Date()
                        message.saveChanges()
                        transport!!.sendMessage(message, message.allRecipients ?: emptyArray())
                        // A mail that ends after the deadline is not counted, even if the caller has not yet looked at the time.
                        synchronized(gate) { if (!timeIsOver()) results[index] = MailResult.SENT }
                    } catch (failure: Exception) {
                        synchronized(gate) { if (!timeIsOver()) reasons += failure.javaClass.name }
                        // Told by the type of the exception, which Angus Mail 2.0.5 sets (checked with the fake server, TALITA-16). A **plain `SendFailedException`** is the refusal of the recipients of
                        // this mail, with a 5xx (an address that does not exist) or a 4xx (greylisting, a mailbox that is full: `validUnsentAddresses`, no `invalidAddresses`): it is about one
                        // person, and the next ones can still go. Everything else stops the list, and the mails that are left fail at once: what is not a `SendFailedException` (a step that did
                        // not answer in time, a socket that broke: the next step would only wait for its own timeout), and its subclasses, which are the refusals of the whole sending
                        // (`SMTPSendFailedException`: `MAIL FROM`, `DATA` or the end of the text refused, a 4xx of the provider for the limit of the day; `SMTPSenderFailedException`, and
                        // `SMTPAddressFailedException`, which the plain one carries inside). They live in the runtime module, so the check is on the exact class and not with `is`. The connection
                        // is still open after all of them, and the next mail would get the same answer.
                        if (failure.javaClass != SendFailedException::class.java) return@thread
                    }
                }
            } finally {
                runCatching { transport?.close() }
            }
        }
        worker.join(listLimit.toMillis())
        // The thread that is still there when the join returns is past the deadline (or about to be, by a millisecond): the caller gives up, with the same reason.
        synchronized(gate) {
            if (worker.isAlive) giveUp()
        }
    }

    /** A connection to the server as Spring makes it for [JavaMailSenderImpl] (the same host, port, user and password), kept open for the whole list. */
    private fun connect(sender: JavaMailSender): Transport {
        val impl = sender as? JavaMailSenderImpl ?: error("the application sends mail with JavaMailSenderImpl")
        val transport = impl.session.getTransport(impl.protocol ?: impl.session.getProperty("mail.transport.protocol") ?: JavaMailSenderImpl.DEFAULT_PROTOCOL)
        val user = impl.username?.takeIf { it.isNotEmpty() }
        val password = if (user == null) impl.password?.takeIf { it.isNotEmpty() } else impl.password
        transport.connect(impl.host, impl.port, user, password)
        return transport
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

    companion object {
        /** The most that a list of mails ([sendAll]) takes, from the first connection to the last mail: under the 20 seconds that htmx waits for the answer of a page. */
        val LIST_LIMIT: Duration = Duration.ofSeconds(15)
    }
}
