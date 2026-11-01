package talita.reminder

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.gathering.text
import talita.mail.FakeSmtpServer
import talita.mail.Mailer
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The whole list of reminders has a time of its own (TALITA-16, criterion 21): a mail server that accepts the connection and then does not answer, or answers so slowly that the steps
 * of a long list add up, stops the sending after [Mailer.LIST_LIMIT] (15 seconds) at most, under the 20 that htmx waits. The mails that are left fail at once, the page says the true
 * numbers, the lock of the sending and the connection are let go, and the rules of TALITA-13 hold: the row of the register only when one mail left, and the codes to stop written
 * before anything is sent.
 *
 * The timeout of each step of the connection is 3 seconds here and not the 10 of the application (the other tests of the mail prove those): the limit of the list is the real one.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["spring.mail.properties.mail.smtp.connectiontimeout=3000", "spring.mail.properties.mail.smtp.timeout=3000", "spring.mail.properties.mail.smtp.writetimeout=3000"])
@AutoConfigureMockMvc
class ReminderTimeLimitTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = ReminderTestKit(dsl, clock)

    private val browsers = ReminderBrowsers(mockMvc, tokens, dsl)

    @BeforeEach
    fun clean() {
        kit.reset()
        smtp.accepted.clear()
    }

    private fun seconds(from: Long): Double = Duration.ofNanos(System.nanoTime() - from).toMillis() / 1000.0

    /** The advisory lock of the sending is free: another transaction takes it at once (it is let go when the request ends, and not when the mail library gives up). */
    private fun lockIsFree(gathering: Long): Boolean =
        dsl.transactionResult { configuration ->
            DSL.using(configuration).fetchOne("select pg_try_advisory_xact_lock(?, ?)", Reminders.LOCK_NAMESPACE, Reminders.lockKey(gathering, ReminderType.MAYBE))!!.get(0, Boolean::class.javaObjectType)
        }

    /** The sending thread ends by its own timeouts, a few seconds after the page was answered: the connection to the server is closed then. */
    private fun connectionsClosed(): Boolean = smtp.awaitClosed(30)

    @Test
    fun `t16 c21 a server that greets and then says nothing makes the sending fail at the first step that does not answer, with no mail left, no row, the codes written, the lock free`() {
        smtp.mode = FakeSmtpServer.Mode.SILENT_AFTER_GREETING
        val s = kit.scenario()
        val one = kit.inForse(s.gathering, "limite-uno")
        val two = kit.inForse(s.gathering, "limite-due")
        val browser = browsers.enter(s.m.email)

        val began = System.nanoTime()
        val result = browser.send(s.gathering, "maybe")
        val took = seconds(began)

        assertTrue(took < Mailer.LIST_LIMIT.toSeconds(), "the sending took $took seconds: htmx gives up after 20")
        assertEquals(422, result.status())
        assertTrue(text(result.html()).contains("Non siamo riusciti a mandare le mail. Riprova tra qualche minuto."), text(result.html()))
        assertTrue(smtp.accepted.isEmpty())
        // The rules of TALITA-13: no row of the register when no mail left, and the codes to stop were written before the sending.
        assertTrue(kit.batches(s.gathering).isEmpty())
        for (person in listOf(s.a, one, two)) assertNotNull(kit.codeOf(person.id), "the code to stop of ${person.email} was written before the sending")
        assertTrue(lockIsFree(s.gathering), "the lock of the sending is let go when the request ends")
        assertTrue(connectionsClosed(), "the connection to the server is closed")
    }

    @Test
    fun `t16 c21 a server that stops after two mails of six leaves the others failed at once, in the time of one step and not of four, and the page says Mandato a 2 persone su 6`() {
        smtp.mode = FakeSmtpServer.Mode.SILENT_AFTER_TWO_MESSAGES
        val s = kit.scenario()
        val others = (1..5).map { kit.inForse(s.gathering, "limite-resto-$it") }
        val browser = browsers.enter(s.m.email)

        val began = System.nanoTime()
        val result = browser.send(s.gathering, "maybe")
        val took = seconds(began)

        // One step that does not answer takes its 3 seconds, and then nothing more is tried: with a try for each of the four mails left, it would take 12.
        assertTrue(took < 7, "the sending took $took seconds")
        assertEquals(200, result.status())
        assertTrue(text(result.html()).contains("Mandato a 2 persone su 6: per 4 le mail non sono partite."), text(result.html()))
        val batch = kit.batches(s.gathering).single()
        assertEquals(6, batch.recipients?.toInt())
        assertEquals(4, batch.failed?.toInt())
        // The people are in the order of their accounts: the first two went through, and the others never did.
        assertEquals(listOf(s.a.email, others[0].email), smtp.accepted.toList(), "the two mails that went through")
        assertFalse(smtp.accepted.contains(others.last().email))
        assertTrue(lockIsFree(s.gathering))
        assertTrue(connectionsClosed())
    }

    @Test
    fun `t16 c21 a server that answers every step but slowly, so that the steps of the list add up, is stopped at the limit of 15 seconds with the numbers of that moment`() {
        smtp.mode = FakeSmtpServer.Mode.SLOW
        smtp.delayMillis = 2_000
        val s = kit.scenario()
        repeat(5) { kit.inForse(s.gathering, "limite-lento-$it") }
        val browser = browsers.enter(s.m.email)

        val began = System.nanoTime()
        val result = browser.send(s.gathering, "maybe")
        val took = seconds(began)

        // Every step answers in 2 seconds, well inside the 3 of the timeout; six mails of about eight steps each are far more than 15 seconds, and the limit of the list ends them.
        assertTrue(took < Mailer.LIST_LIMIT.toSeconds() + 3, "the sending took $took seconds: htmx gives up after 20")
        assertTrue(took >= Mailer.LIST_LIMIT.toSeconds() - 1, "it was stopped by the limit of the list and not before ($took seconds)")
        assertEquals(200, result.status())
        val html = text(result.html())
        assertTrue(Regex("""Mandato a (1 persona|\d+ persone) su 6: per (una la mail non è partita|\d+ le mail non sono partite)\.""").containsMatchIn(html), html)
        val batch = kit.batches(s.gathering).single()
        assertEquals(6, batch.recipients?.toInt())
        val failed = checkNotNull(batch.failed).toInt()
        assertTrue(failed in 1..5, "some mails left and some did not: $failed failed")
        assertTrue(smtp.accepted.size >= 6 - failed, "the mails that the page counts as sent were taken by the server: ${smtp.accepted}")
        assertTrue(lockIsFree(s.gathering))
        // The sending that was given up starts no new mail (it looks before each one); the one in progress still arrives, and is not counted. The connection is closed when its step ends.
        assertTrue(connectionsClosed())
    }

    companion object {
        private val smtp = FakeSmtpServer()

        @JvmStatic
        @DynamicPropertySource
        fun mailServer(registry: DynamicPropertyRegistry) {
            registry.add("spring.mail.host") { smtp.host }
            registry.add("spring.mail.port") { smtp.port }
        }

        @JvmStatic
        @AfterAll
        fun stop() = smtp.close()
    }
}
