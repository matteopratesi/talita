package talita.teacher

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.account.EmailAddress
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.mail.Mailbox
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val NOT_SENT = "Non siamo riusciti a mandare la mail. Riprova tra qualche minuto."

private const val DAILY_LIMIT = "Oggi Talità ha già mandato tante mail e non può mandarne altre. Riprova domani. Se è urgente, avvisa chi organizza la convivenza."

/**
 * An invitation is saved only if its mail leaves, in one transaction (criterion 41): with the mail server down, with no public
 * address and with the day over, the invitation is not there, the one that was there keeps its expiry, and the page says so in the words of
 * the design. Each of these is an application with its own settings.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["spring.mail.port=1"])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class MaestriMailDownTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val invitations: Invitations,
    @Autowired private val clock: MutableClock,
) {

    private val support = MaestriSupport(mockMvc, tokens, dsl)

    @Test
    fun `t3 c41 with the mail server down no invitation is saved, the field stays filled in with the notice, and the log has neither the address nor the person`(output: CapturedOutput) {
        val me = Addresses.unique("guasto-invita")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val guest = Addresses.unique("guasto-ospite")
        val browser = support.browser(me)

        repeat(3) {
            val result = browser.act("/maestri/invita", mapOf("email" to guest))

            assertEquals(422, result.response.status)
            assertTrue(result.html().contains(NOT_SENT))
            assertTrue(result.html().contains("""role="alert""""))
            assertTrue(result.html().contains("""value="$guest""""), "the field was emptied")
            assertFalse(result.html().contains("Invito mandato."))
        }

        assertEquals(0, dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(guest)), "an invitation was saved without a mail")
        assertTrue(Mailbox.sentTo(guest).isEmpty())
        assertTrue(output.all.contains("Mail not sent: "), "the log does not say that the mail did not leave")
        assertFalse(output.all.contains(guest), "the log has the address")
    }

    @Test
    fun `t3 c41 sending again with the mail server down leaves the invitation as it was, with its expiry`() {
        val me = Addresses.unique("guasto-rimanda")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val guest = Addresses.unique("guasto-in-attesa")
        invitations.invite(EmailAddress.parse(guest)!!)
        val before = invitations.pendingFor(guest)!!.expiresAt
        clock.advance(Duration.ofHours(1))
        val browser = support.browser(me)

        val result = browser.act("/maestri/inviti/rimanda", mapOf("email" to guest))

        assertEquals(422, result.response.status)
        assertTrue(result.html().contains(NOT_SENT))
        assertEquals(before, invitations.pendingFor(guest)!!.expiresAt, "the expiry changed with no mail")
        assertTrue(result.html().contains(guest), "the invitation is not in the list any more")
        invitations.withdraw(guest)
    }
}

/** The address of the site is missing: the mail of the invitation could not have its link. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["talita.public-url="])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class MaestriWithoutPublicUrlTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
) {

    @Test
    fun `t3 c41 without talita public-url no invitation is saved and no mail leaves, the page says so, and the log says why without the address`(output: CapturedOutput) {
        val support = MaestriSupport(mockMvc, tokens, dsl)
        val me = Addresses.unique("senza-url")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val guest = Addresses.unique("senza-url-ospite")

        val result = support.browser(me).act("/maestri/invita", mapOf("email" to guest))

        assertEquals(422, result.response.status)
        assertTrue(result.html().contains(NOT_SENT))
        assertEquals(0, dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(guest)))
        assertTrue(Mailbox.sentTo(guest).isEmpty(), "a mail left without a public address")
        assertTrue(output.all.contains("talita.public-url is not set"))
        assertFalse(output.all.contains(guest))
    }
}

/** The day is over: Talità has sent all the mails it can (here, one). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["talita.mail.daily-limit=1"])
@AutoConfigureMockMvc
class MaestriDailyLimitTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val invitations: Invitations,
) {

    @Test
    fun `t3 c41 when the mails of the day are over the invitation is refused with the notice, nothing is saved, and an invitation that was there keeps its expiry`() {
        val support = MaestriSupport(mockMvc, tokens, dsl)
        val me = Addresses.unique("limite-inviti")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val first = Addresses.unique("limite-primo")
        val second = Addresses.unique("limite-secondo")
        val browser = support.browser(me)

        assertEquals(200, browser.act("/maestri/invita", mapOf("email" to first)).response.status)
        val expiry = invitations.pendingFor(first)!!.expiresAt
        assertNotNull(Mailbox.waitForMail(first))

        val refused = browser.act("/maestri/invita", mapOf("email" to second))
        assertEquals(429, refused.response.status)
        assertTrue(refused.html().contains(DAILY_LIMIT))
        assertTrue(refused.html().contains("""role="alert""""))
        assertTrue(refused.html().contains("""value="$second""""), "the field was emptied")
        assertEquals(0, dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(second)), "the invitation was saved but the mail did not leave")
        assertTrue(Mailbox.sentTo(second).isEmpty())

        val again = browser.act("/maestri/inviti/rimanda", mapOf("email" to first))
        assertEquals(429, again.response.status)
        assertTrue(again.html().contains(DAILY_LIMIT))
        assertEquals(expiry, invitations.pendingFor(first)!!.expiresAt)
        assertEquals(1, Mailbox.sentTo(first).size)
    }
}
