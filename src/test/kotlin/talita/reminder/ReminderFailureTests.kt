package talita.reminder

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.account.EmailAddress
import talita.account.LinkOutcome
import talita.account.LoginLinks
import talita.gathering.text
import talita.jooq.tables.references.ACCOUNT
import talita.mail.FakeSmtpServer
import talita.mail.Mailbox
import talita.registration.RegistrationFormat
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOT_SENT = "Non siamo riusciti a mandare le mail. Riprova tra qualche minuto."

private fun card(html: String): String = Regex("""<section id="promemoria".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)?.value ?: error("no card in the answer")

/**
 * The mail does not leave (TALITA-13, criterion 6): with the server down, with no public address, with no sender, the page says so, the register has no row and a teacher can
 * try again at once. Each of these is an application with its own settings.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["spring.mail.port=1"])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class ReminderMailDownTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = ReminderTestKit(dsl, clock)

    private val browsers = ReminderBrowsers(mockMvc, tokens, dsl)

    @Test
    fun `t13 c06 with the mail server down nothing is sent, the page says so with the notice of an error, the register has no row, and one can try again at once`(output: CapturedOutput) {
        val s = kit.scenario()
        kit.inForse(s.gathering, "giu-e")
        val browser = browsers.enter(s.m.email)

        repeat(3) {
            val result = browser.send(s.gathering, "maybe")

            assertEquals(422, result.status())
            assertTrue(text(result.html()).contains(NOT_SENT), text(result.html()))
            assertTrue(result.html().contains("""role="alert""""), "an error is announced at once")
            assertFalse(text(result.html()).contains("Promemoria mandato"))
            // The button is still there: nothing was sent, so nothing waits.
            assertTrue(card(result.html()).contains("""data-apri="manda-maybe""""), "one can try again")
            assertFalse(text(card(result.html())).contains("Potrai rimandarlo"))
        }

        assertTrue(kit.batches(s.gathering).isEmpty(), "no row for a mail that did not leave")
        assertTrue(Mailbox.sentTo(s.a.email).isEmpty())
        assertTrue(output.all.contains("Mails not sent: 2 of 2"), "the log says how many, and the type of the exception")
        for (secret in listOf(s.a.email, "smetti", "codice=", "talita.example.org")) assertFalse(output.all.contains(secret), "the log has «$secret»")
    }

    @Test
    fun `t13 c06 the page of the detail still shows the button and says Mai for the last send`() {
        val s = kit.scenario()
        browsers.enter(s.m.email).send(s.gathering, "maybe")

        val detail = browsers.enter(s.m.email).get("/convivenza/${s.gathering}").response.contentAsString

        assertTrue(text(card(detail)).contains("Ultimo invio Mai"))
        assertTrue(card(detail).contains("""data-apri="manda-maybe""""))
    }
}

/** The address of the site is missing: the mails could not have their links. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["talita.public-url="])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class ReminderWithoutPublicUrlTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    @Test
    fun `t13 c06 without talita public-url no mail leaves, the page says so, nothing is written, and the log says why without an address`(output: CapturedOutput) {
        val kit = ReminderTestKit(dsl, clock)
        val s = kit.scenario()

        val result = ReminderBrowsers(mockMvc, tokens, dsl).enter(s.m.email).send(s.gathering, "maybe")

        assertEquals(422, result.status())
        assertTrue(text(result.html()).contains(NOT_SENT))
        assertTrue(kit.batches(s.gathering).isEmpty())
        assertNull(kit.codeOf(s.a.id), "no code was written for a mail that could not be made")
        assertTrue(Mailbox.sentTo(s.a.email).isEmpty())
        assertTrue(output.all.contains("Reminders not sent: talita.public-url is not set"))
        assertFalse(output.all.contains(s.a.email))
    }
}

/** No sender address: the application starts and says so, and no mail leaves. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["talita.mail.from="])
@AutoConfigureMockMvc
class ReminderWithoutSenderTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    @Test
    fun `t13 c06 without a sender address no mail leaves and the page says so`() {
        val kit = ReminderTestKit(dsl, clock)
        val s = kit.scenario()

        val result = ReminderBrowsers(mockMvc, tokens, dsl).enter(s.m.email).send(s.gathering, "maybe")

        assertEquals(422, result.status())
        assertTrue(text(result.html()).contains(NOT_SENT))
        assertTrue(kit.batches(s.gathering).isEmpty())
    }
}

/**
 * One mail out of three is refused by the server (a 550 on the recipient, as for an address that does not exist): the others leave, the page says it with the numbers, and the
 * register has its row, so the wait of 7 days begins: there is no way to send again only to whoever is missing. If no mail leaves there is no row.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class ReminderPartialFailureTests(
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

    @Test
    fun `t13 c06 when the mail does not leave for one person of three the page says Mandato a 2 persone su 3, the register has the row, and the wait begins`(output: CapturedOutput) {
        val s = kit.scenario()
        val refused = kit.inForse(s.gathering, "rifiuta-uno")
        val fine = kit.inForse(s.gathering, "parziale-f")

        val result = browsers.enter(s.m.email).send(s.gathering, "maybe")

        assertEquals(200, result.status())
        val html = result.html()
        assertTrue(html.contains("""role="alert""""), "something to pay attention to: announced at once")
        assertTrue(text(html).contains("Mandato a 2 persone su 3: per una la mail non è partita."), text(html))
        val batch = kit.batches(s.gathering).single()
        assertEquals(3, batch.recipients?.toInt())
        assertEquals(1, batch.failed?.toInt())
        assertTrue(text(html).contains("Potrai mandare un altro promemoria dal ${RegistrationFormat.moment(LastBatch(batch.sentAt!!, 3, 1).again)}."), text(html))
        assertEquals(setOf(s.a.email, fine.email), smtp.accepted.toSet(), "the mails that left, and not the one that was refused")
        assertFalse(smtp.accepted.contains(refused.email))
        // The card already shows the one that was done: to 2 people, the wait, and no button.
        assertTrue(text(card(html)).contains("Ultimo invio ${RegistrationFormat.moment(batch.sentAt!!)} · a 2 persone"), text(card(html)))
        assertFalse(card(html).contains("""data-apri="manda-maybe""""))
        assertTrue(output.all.contains("Reminders sent: gathering ${s.gathering}, maybe, 2 of 3."))
        assertTrue(output.all.contains("Mails not sent: 1 of 3"))
        for (secret in listOf(s.a.email, refused.email, fine.email, "smetti", "codice=")) assertFalse(output.all.contains(secret), "the log has «$secret»")
    }

    @Test
    fun `t13 c06 when no mail leaves the register has no row and the button is there again`() {
        val s = kit.scenario()
        // Only one person is in forse and the server refuses that address.
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDERS_OFF_AT, OffsetDateTime.now(clock)).setNull(ACCOUNT.NOTICE_CONSENT_AT).where(ACCOUNT.ID.eq(s.a.id)).execute()
        kit.inForse(s.gathering, "rifiuta-solo")
        val browser = browsers.enter(s.m.email)

        val first = browser.send(s.gathering, "maybe")
        val second = browser.send(s.gathering, "maybe")

        for (result in listOf(first, second)) {
            assertEquals(422, result.status())
            assertTrue(text(result.html()).contains(NOT_SENT))
            assertTrue(card(result.html()).contains("""data-apri="manda-maybe""""))
        }
        assertTrue(kit.batches(s.gathering).isEmpty())
        assertTrue(smtp.accepted.isEmpty())
    }

    companion object {
        private val smtp = FakeSmtpServer(refusing = "rifiuta")

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

/**
 * The day has few reminders (3, and 2 for links and invitations, so that the numbers are small): a send that is more than what is left does not leave at all and the page says
 * how many can still go; the two counts do not touch each other.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["talita.mail.daily-limit=2", "talita.mail.reminder-daily-limit=3"])
@AutoConfigureMockMvc
class ReminderQuotaTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: MutableClock,
    @Autowired private val links: LoginLinks,
) {

    private val kit = ReminderTestKit(dsl, clock)

    private val browsers = ReminderBrowsers(mockMvc, tokens, dsl)

    /** A new day for the counts, whatever the tests before did. */
    @BeforeEach
    fun newDay() {
        clock.advance(Duration.ofHours(25))
    }

    @Test
    fun `t13 c07 a send that needs more than what is left does not leave, and the page says how many can still go today`() {
        val s = kit.scenario()
        kit.inForse(s.gathering, "quota-e")
        kit.inForse(s.gathering, "quota-f")
        kit.inForse(s.gathering, "quota-g")
        val browser = browsers.enter(s.m.email)

        val result = browser.send(s.gathering, "maybe")

        // Four people (A, E, F, G) and three places: nothing leaves.
        assertEquals(429, result.status())
        assertTrue(result.html().contains("""role="alert""""))
        assertTrue(text(result.html()).contains("Oggi si possono mandare ancora 3 promemoria, ma questo ne richiede 4. Riprova domani."), text(result.html()))
        assertTrue(Mailbox.sentTo(s.a.email).isEmpty())
        assertTrue(kit.batches(s.gathering).isEmpty())
        assertTrue(card(result.html()).contains("""data-apri="manda-maybe""""), "the card is the same, with its button")
        assertNull(kit.codeOf(s.a.id), "nothing was prepared for a mail that was not sent")
    }

    @Test
    fun `t13 c07 with the places used the page says that Talità has sent all it can, and a link of access still leaves`() {
        val s = kit.scenario()
        kit.inForse(s.gathering, "quota-h")
        val browser = browsers.enter(s.m.email)

        // Two people in forse (A and H): they take two of the three places. The avviso is for B alone: one place, the last.
        assertEquals(200, browser.send(s.gathering, "maybe").status())
        assertEquals(200, browser.send(s.gathering, "notice").status())
        // No place is left: a new gathering of reminders is refused with the words for «finished», not for «not enough».
        dsl.deleteFrom(talita.jooq.tables.references.REMINDER_BATCH).execute()
        val over = browser.send(s.gathering, "maybe")
        assertEquals(429, over.status())
        assertTrue(text(over.html()).contains("Oggi Talità ha già mandato tutti i promemoria che può mandare. Riprova domani."), text(over.html()))

        // A link of access leaves: the count of the reminders is not the one of the links.
        val someone = EmailAddress.parse(Addresses.unique("quota-link"))!!
        assertEquals(LinkOutcome.SENT, links.request(someone))
        assertTrue(Mailbox.waitForMail(someone.value).subject.contains("link per entrare"))
    }

    @Test
    fun `t13 c07 with the places of the links used a reminder still leaves`() {
        val s = kit.scenario()
        // Two links use the two places of the links and invitations.
        for (name in listOf("quota-uno", "quota-due")) assertEquals(LinkOutcome.SENT, links.request(EmailAddress.parse(Addresses.unique(name))!!))
        assertEquals(LinkOutcome.DAILY_LIMIT, links.request(EmailAddress.parse(Addresses.unique("quota-tre"))!!), "the third link waits")

        val result = browsers.enter(s.m.email).send(s.gathering, "maybe")

        assertEquals(200, result.status(), "the reminder does not wait for the count of the links")
        assertTrue(text(result.html()).contains("Promemoria mandato a 1 persona."))
        assertEquals(1, Mailbox.sentTo(s.a.email).size)
    }
}
