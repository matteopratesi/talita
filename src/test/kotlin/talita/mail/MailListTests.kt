package talita.mail

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.context.annotation.Import
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.JavaMailSenderImpl
import talita.Addresses
import talita.CapturesOutput
import talita.MutableClock
import talita.TestcontainersConfiguration
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mail in a list (TALITA-13, criteria 6, 7 and 8): one connection to the server for all the mails, an outcome for each one, the header `List-Unsubscribe`,
 * and a count of the day of its own for the reminders. The server that refuses a recipient is a real SMTP conversation ([FakeSmtpServer]), so the outcome of each
 * mail is the one that the library of the mail reports, not an invention of the test.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class MailListTests(
    @Autowired private val mailer: Mailer,
    @Autowired private val mailSender: JavaMailSender,
    @Autowired private val settings: MailSettings,
    @Autowired private val clock: MutableClock,
) {

    private fun mail(to: String, unsubscribe: String? = STOP_LINK) = OutgoingMail(to, "Promemoria di prova", "Ciao,\n\nquesto è un promemoria di prova.\n\nTalità\n", unsubscribe)

    /** The sender of the application, with its properties (timeouts, no STARTTLS), towards [host] and [port]. */
    private fun senderTo(host: String, port: Int) = JavaMailSenderImpl().apply {
        this.host = host
        this.port = port
        javaMailProperties = (mailSender as JavaMailSenderImpl).javaMailProperties
    }

    private fun mailerTo(server: FakeSmtpServer, reminderLimit: Int = 40) =
        Mailer(senderTo(server.host, server.port), settings, MailQuota(settings.copy(reminderDailyLimit = reminderLimit), clock))

    @Test
    fun `t13 c08 a list leaves in order, each mail to its own address, with the headers of Mailjet and the address to stop`() {
        val first = Addresses.unique("lista-uno")
        val second = Addresses.unique("lista-due")
        val third = Addresses.unique("lista-tre")

        val results = Mailer(mailSender, settings, MailQuota(settings, clock)).sendAll(listOf(mail(first), mail(second), mail(third, unsubscribe = null)), MailKind.REMINDER)

        assertEquals(listOf(MailResult.SENT, MailResult.SENT, MailResult.SENT), results)
        for (address in listOf(first, second)) {
            val received = Mailbox.waitForMail(address)
            assertEquals(listOf(address), received.to)
            assertEquals("Promemoria di prova", received.subject)
            assertEquals(listOf("<$STOP_LINK>"), received.headers["list-unsubscribe"], "the header that lets the mail program offer to stop")
            assertEquals(listOf("0"), received.headers["x-mailjet-trackopen"])
            assertEquals(listOf("0"), received.headers["x-mailjet-trackclick"])
            assertEquals("", received.html, "text only")
        }
        // A mail without an address to stop has no such header: the login link and the invitation are not reminders.
        assertNull(Mailbox.waitForMail(third).headers["list-unsubscribe"])
    }

    @Test
    fun `t13 c08 the mail of a login link has no List-Unsubscribe, as before`() {
        val to = Addresses.unique("lista-accesso")

        assertEquals(MailResult.SENT, mailer.send(OutgoingMail(to, "Oggetto di prova", "Ciao,\n\nTalità\n")))

        assertNull(Mailbox.waitForMail(to).headers["list-unsubscribe"])
    }

    @Test
    fun `t13 c06 a list goes out in one connection to the server, not one for each mail`() {
        FakeSmtpServer().use { server ->
            val addresses = (1..5).map { Addresses.unique("una-connessione-$it") }

            val results = mailerTo(server).sendAll(addresses.map { mail(it) }, MailKind.REMINDER)

            assertEquals(List(5) { MailResult.SENT }, results)
            assertEquals(1, server.connectionCount.get(), "a connection for each mail would make 40 mails slower than htmx waits")
            assertEquals(addresses, server.accepted.toList())
        }
    }

    @Test
    fun `t16 c21 when the server refuses the sender, the text or the whole sending, the list stops at the first refusal, and when it refuses a recipient, for good or for a while, the list goes on`() {
        for (mode in listOf(FakeSmtpServer.Mode.REFUSE_SENDER, FakeSmtpServer.Mode.REFUSE_DATA, FakeSmtpServer.Mode.REFUSE_CONTENT, FakeSmtpServer.Mode.TEMPORARY_LIMIT)) {
            FakeSmtpServer(mode = mode).use { server ->
                val quota = MailQuota(settings.copy(reminderDailyLimit = 10), clock)
                val whole = Mailer(senderTo(server.host, server.port), settings, quota)

                val results = whole.sendAll(List(4) { mail(Addresses.unique("rifiuto-$mode-$it")) }, MailKind.REMINDER)

                assertEquals(List(4) { MailResult.FAILED }, results, "$mode")
                // The answer is the same for every mail (the same sender, the same text, the same limit): one try, and the four fail without the other three.
                assertEquals(1, server.mailFromCount.get(), "$mode: the list went on after a refusal of the whole sending")
                assertEquals(1, server.connectionCount.get(), "$mode")
                assertTrue(server.accepted.isEmpty(), "$mode")
                assertEquals(10, quota.remaining(MailKind.REMINDER), "$mode: the places of the four mails are given back")
            }
        }
        // A refused recipient is about one person: the list goes on, in the same connection. Also when the refusal is temporary (a 4xx: greylisting, a mailbox that is full), which
        // the library reports as a plain `SendFailedException` too, with the address among the valid unsent ones and not among the invalid.
        for (refusal in listOf("550 5.1.1 no such user", "450 4.2.0 try again later", "452 4.2.2 mailbox full")) {
            FakeSmtpServer(refusing = "rifiutato", refusal = refusal).use { server ->
                val results = mailerTo(server).sendAll(
                    listOf(mail(Addresses.unique("avanti-uno")), mail(Addresses.unique("avanti-rifiutato")), mail(Addresses.unique("avanti-due")), mail(Addresses.unique("avanti-tre"))),
                    MailKind.REMINDER,
                )

                assertEquals(listOf(MailResult.SENT, MailResult.FAILED, MailResult.SENT, MailResult.SENT), results, refusal)
                assertEquals(4, server.mailFromCount.get(), "$refusal: the four mails were tried")
                assertEquals(1, server.connectionCount.get(), refusal)
                assertEquals(3, server.accepted.size, refusal)
            }
        }
    }

    @Test
    fun `t16 c21 a mail that is in progress when the limit of the list passes arrives and is not counted, and no other mail starts after it`() {
        // Every answer of the server comes 600 ms late: the connection takes about 1.2 s, the first mail about 2.4 s more. With a limit of 2.5 s the first mail begins before it and ends well
        // after it. The second must not begin, at the moment when that mail ends, whatever the caller has done by then: the thread looks at the deadline itself.
        FakeSmtpServer(mode = FakeSmtpServer.Mode.SLOW, delayMillis = 600).use { server ->
            val quota = MailQuota(settings.copy(reminderDailyLimit = 10), clock)
            val limited = Mailer(senderTo(server.host, server.port), settings, quota).apply { listLimit = Duration.ofMillis(2_500) }

            val results = limited.sendAll(List(3) { mail(Addresses.unique("scadenza-$it")) }, MailKind.REMINDER)

            // For the caller nothing was counted, and the places are given back.
            assertEquals(List(3) { MailResult.FAILED }, results)
            assertEquals(10, quota.remaining(MailKind.REMINDER))
            // The thread ends by itself, after the mail that was in progress; then the server has seen everything that there is to see.
            assertTrue(server.awaitClosed(30), "the connection was closed")
            assertEquals(1, server.mailFromCount.get(), "no mail began after the deadline: only the first, which was in progress")
            assertEquals(1, server.accepted.size, "the mail in progress arrived, and was not counted")
            assertEquals(1, server.connectionCount.get())
        }
    }

    @Test
    fun `t16 c21 a connection that is open after the limit of the list does not send the first mail either`() {
        // Every answer of the server comes 1.2 s late: the greeting and `EHLO` make 2.4 s, and the limit is 1.5 s. At the first look (before the connection) the time is not over; when the
        // connection is open it is, and the mail must not start: the server must not see a single `MAIL FROM`.
        FakeSmtpServer(mode = FakeSmtpServer.Mode.SLOW, delayMillis = 1_200).use { server ->
            val quota = MailQuota(settings.copy(reminderDailyLimit = 10), clock)
            val limited = Mailer(senderTo(server.host, server.port), settings, quota).apply { listLimit = Duration.ofMillis(1_500) }

            val results = limited.sendAll(List(3) { mail(Addresses.unique("connessione-$it")) }, MailKind.REMINDER)

            assertEquals(List(3) { MailResult.FAILED }, results)
            assertEquals(10, quota.remaining(MailKind.REMINDER), "the places are given back")
            // The thread ends by itself once the connection is open and the time is seen to be over; then the server has seen everything that there is to see.
            assertTrue(server.awaitClosed(30), "the connection was closed")
            assertEquals(1, server.connectionCount.get(), "the connection was opened")
            assertEquals(0, server.mailFromCount.get(), "no mail began after the deadline, not even the first")
            assertTrue(server.accepted.isEmpty())
        }
    }

    @Test
    @CapturesOutput
    fun `t13 c06 a mail that the server refuses is a failure of that mail only, the others leave, and the place of the one that failed is given back`(output: CapturedOutput) {
        FakeSmtpServer(refusing = "rifiutato").use { server ->
            val good = Addresses.unique("parziale-uno")
            val refused = Addresses.unique("parziale-rifiutato")
            val other = Addresses.unique("parziale-due")
            val quota = MailQuota(settings.copy(reminderDailyLimit = 10), clock)
            val partial = Mailer(senderTo(server.host, server.port), settings, quota)

            val results = partial.sendAll(listOf(mail(good), mail(refused), mail(other)), MailKind.REMINDER)

            assertEquals(listOf(MailResult.SENT, MailResult.FAILED, MailResult.SENT), results)
            assertEquals(listOf(good, other), server.accepted.toList())
            assertEquals(8, quota.remaining(MailKind.REMINDER), "two mails left, so two places are used; the third is back")
            assertTrue(output.all.contains("Mails not sent: 1 of 3"), "the log says how many, without the address")
            for (address in listOf(good, refused, other)) assertFalse(output.all.contains(address), "the log has an address")
            assertFalse(output.all.contains("promemoria/smetti") || output.all.contains("codice="), "the log has the address to stop")
        }
    }

    @Test
    fun `t13 c06 an address that is not one fails that mail and the others leave`() {
        FakeSmtpServer().use { server ->
            val good = Addresses.unique("indirizzo-buono")

            val results = mailerTo(server).sendAll(listOf(mail(""), mail(good)), MailKind.REMINDER)

            assertEquals(listOf(MailResult.FAILED, MailResult.SENT), results)
            assertEquals(listOf(good), server.accepted.toList())
        }
    }

    @Test
    fun `t13 c06 with the server down every mail of the list fails at once, and with a server that says nothing in less than 15 seconds`() {
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val down = Mailer(senderTo(InetAddress.getLoopbackAddress().hostAddress, closedPort), settings, MailQuota(settings, clock))
        val three = listOf(mail(Addresses.unique("giu-1")), mail(Addresses.unique("giu-2")), mail(Addresses.unique("giu-3")))
        assertEquals(List(3) { MailResult.FAILED }, down.sendAll(three, MailKind.REMINDER))

        ServerSocket(0, 5, InetAddress.getLoopbackAddress()).use { silent ->
            val accepted = CopyOnWriteArrayList<Socket>()
            thread(isDaemon = true) {
                try {
                    while (true) accepted += silent.accept()
                } catch (_: IOException) {
                    // the server socket was closed: the test is over
                }
            }
            val slow = Mailer(senderTo(InetAddress.getLoopbackAddress().hostAddress, silent.localPort), settings, MailQuota(settings, clock))

            val started = System.nanoTime()
            val results = assertTimeoutPreemptively(Duration.ofSeconds(25), ThrowingSupplier { slow.sendAll(three, MailKind.REMINDER) })
            val seconds = Duration.ofNanos(System.nanoTime() - started).toMillis() / 1000.0

            assertEquals(List(3) { MailResult.FAILED }, results)
            assertTrue(seconds < 15, "the list failed after $seconds seconds: htmx gives up after 20")
            accepted.forEach { it.close() }
        }
    }

    @Test
    fun `t13 c07 a list that is more than what is left of the day does not leave at all, and nothing is counted`() {
        FakeSmtpServer().use { server ->
            val quota = MailQuota(settings.copy(reminderDailyLimit = 2), clock)
            val small = Mailer(senderTo(server.host, server.port), settings, quota)
            val three = List(3) { mail(Addresses.unique("troppe-$it")) }

            assertEquals(List(3) { MailResult.DAILY_LIMIT }, small.sendAll(three, MailKind.REMINDER))
            assertEquals(0, server.connectionCount.get(), "the server was not even called")
            assertEquals(2, quota.remaining(MailKind.REMINDER))

            assertEquals(List(2) { MailResult.SENT }, small.sendAll(three.take(2), MailKind.REMINDER))
            assertEquals(0, quota.remaining(MailKind.REMINDER))
            assertEquals(listOf(MailResult.DAILY_LIMIT), small.sendAll(three.take(1), MailKind.REMINDER))
        }
    }

    @Test
    fun `t13 c07 with the count of the reminders finished a login link still leaves, and with the one of the links finished a reminder still leaves`() {
        FakeSmtpServer().use { server ->
            val quota = MailQuota(settings.copy(dailyLimit = 2, reminderDailyLimit = 2), clock)
            val both = Mailer(senderTo(server.host, server.port), settings, quota)

            // The reminders are all used...
            assertEquals(List(2) { MailResult.SENT }, both.sendAll(List(2) { mail(Addresses.unique("rimanda-$it")) }, MailKind.REMINDER))
            assertEquals(MailResult.DAILY_LIMIT, both.sendAll(listOf(mail(Addresses.unique("oltre"))), MailKind.REMINDER).single())
            // ...and the links are not touched.
            assertEquals(MailResult.SENT, both.send(OutgoingMail(Addresses.unique("link-uno"), "Il tuo link", "Ciao,\n\nTalità\n")))
            assertEquals(MailResult.SENT, both.send(OutgoingMail(Addresses.unique("link-due"), "Il tuo link", "Ciao,\n\nTalità\n")))
            assertEquals(MailResult.DAILY_LIMIT, both.send(OutgoingMail(Addresses.unique("link-tre"), "Il tuo link", "Ciao,\n\nTalità\n")))
        }

        // The other way: the links are finished and a reminder leaves.
        FakeSmtpServer().use { server ->
            val quota = MailQuota(settings.copy(dailyLimit = 1, reminderDailyLimit = 3), clock)
            val both = Mailer(senderTo(server.host, server.port), settings, quota)
            assertEquals(MailResult.SENT, both.send(OutgoingMail(Addresses.unique("solo-link"), "Il tuo link", "Ciao,\n\nTalità\n")))
            assertEquals(MailResult.DAILY_LIMIT, both.send(OutgoingMail(Addresses.unique("link-oltre"), "Il tuo link", "Ciao,\n\nTalità\n")))
            assertEquals(List(3) { MailResult.SENT }, both.sendAll(List(3) { mail(Addresses.unique("ancora-$it")) }, MailKind.REMINDER))
        }
    }

    @Test
    @CapturesOutput
    fun `t13 c06 without a server or a sender nothing leaves, nothing is counted, and the log says why`(output: CapturedOutput) {
        val quota = MailQuota(settings, clock)

        assertEquals(List(2) { MailResult.FAILED }, Mailer(null, settings, quota).sendAll(List(2) { mail(Addresses.unique("senza-server-$it")) }, MailKind.REMINDER))
        assertEquals(List(2) { MailResult.FAILED }, Mailer(mailSender, settings.copy(from = ""), quota).sendAll(List(2) { mail(Addresses.unique("senza-mittente-$it")) }, MailKind.REMINDER))

        assertEquals(settings.reminderDailyLimit, quota.remaining(MailKind.REMINDER))
        assertTrue(output.all.contains("Mails not sent: no mail server configured."))
        assertTrue(output.all.contains("Mails not sent: no sender address configured."))
        assertEquals(emptyList(), Mailer(mailSender, settings, quota).sendAll(emptyList(), MailKind.REMINDER))
    }

    private companion object {
        const val STOP_LINK = "https://talita.example.org/promemoria/smetti?codice=Xq3Zp0mV_8aTe-Lw"
    }
}
