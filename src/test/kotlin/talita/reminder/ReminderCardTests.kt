package talita.reminder

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.text
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.ReminderKind
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.registration.RegistrationFormat
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The card «Promemoria» of the detail of a gathering (TALITA-13, criterion 2): for each kind how many people would receive it (the zero in words), the last send and the
 * button or the wait; never a name or an address; in a draft, with the registrations closed or over, only the sentence that says that reminders go with the registrations open.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ReminderCardTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = ReminderTestKit(dsl, clock)

    private val browsers = ReminderBrowsers(mockMvc, tokens, dsl)

    @BeforeEach
    fun clean() = kit.reset()

    /** The page of the detail as the teacher M sees it, and the part «Promemoria» of it. */
    private fun detail(browser: MockBrowser, gathering: Long): String = browser.get("/convivenza/$gathering").response.contentAsString

    private fun card(html: String): String = Regex("""<section id="promemoria".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)?.value ?: error("no card «Promemoria» in the page")

    private fun block(html: String, type: String): String =
        Regex("""<div id="promemoria-$type".*?\n</div>""", RegexOption.DOT_MATCHES_ALL).find(card(html))?.value ?: error("no block $type")

    @Test
    fun `t13 c02 with the registrations open the card says, for each kind, how many would receive it and when the last one left, with the button`() {
        val s = kit.scenario()
        kit.inForse(s.gathering, "carta-e")
        kit.inForse(s.gathering, "carta-f")
        val html = detail(browsers.enter(s.m.email), s.gathering)

        val text = text(card(html))
        assertTrue(text.startsWith("Promemoria Due email che mandi tu, con un tocco. Il testo è fisso: non ci sono nomi né dati dei bambini, e in ogni mail c’è il modo per smettere."), text)
        val maybe = text(block(html, "maybe"))
        assertTrue(maybe.contains("Iscrizioni in forse Per chi ha un’iscrizione in forse: riceve un invito a confermarla."), maybe)
        assertTrue(maybe.contains("Lo riceverebbero 3 persone"), "A, E and F: $maybe")
        assertTrue(maybe.contains("Ultimo invio Mai"), maybe)
        assertTrue(card(html).contains("""aria-label="Manda il promemoria a chi ha un’iscrizione in forse""""), "the name of the button says to whom")
        val notice = text(block(html, "notice"))
        assertTrue(notice.contains("Avviso per le nuove iscrizioni Per chi ha già iscritto i figli a una convivenza e ha chiesto un avviso: riceve il link della pagina informativa."), notice)
        assertTrue(notice.contains("Lo riceverebbero 1 persona"), "B alone: $notice")
        assertTrue(card(html).contains("""aria-label="Manda il promemoria a chi ha chiesto l’avviso""""))
        assertTrue(text.contains("Se la connessione cade durante l’invio, riapri la pagina per controllare l’ultimo invio."))
        // The two buttons lead to the page of confirmation without JavaScript and open their own dialog with it.
        assertTrue(card(html).contains("""href="/convivenza/${s.gathering}/promemoria?tipo=maybe""""))
        assertTrue(card(html).contains("""href="/convivenza/${s.gathering}/promemoria?tipo=notice""""))
        assertTrue(card(html).contains("""data-apri="manda-maybe""""))
        assertTrue(card(html).contains("""data-apri="manda-notice""""))
    }

    @Test
    fun `t13 c02 the card is after Iscrizioni and before Quando e dove, and it is a section of its own`() {
        val s = kit.scenario()
        val html = detail(browsers.enter(s.m.email), s.gathering)

        val iscrizioni = html.indexOf("""aria-labelledby="iscrizioni"""")
        val promemoria = html.indexOf("""<section id="promemoria"""")
        val quando = html.indexOf("""aria-labelledby="quando"""")
        assertTrue(iscrizioni in 0 until promemoria && promemoria < quando, "$iscrizioni $promemoria $quando")
        assertEquals(1, Regex("""<h2 id="promemoria-t"""").findAll(html).count())
        assertTrue(Regex("""<h3[^>]*>Iscrizioni in forse</h3>""").containsMatchIn(html) && Regex("""<h3[^>]*>Avviso per le nuove iscrizioni</h3>""").containsMatchIn(html))
    }

    @Test
    fun `t13 c02 the dialog says to how many people, to whom, what they get, and that a mail that has left cannot be taken back`() {
        val s = kit.scenario()
        kit.inForse(s.gathering, "dialogo-e")
        kit.inForse(s.gathering, "dialogo-f")
        val html = detail(browsers.enter(s.m.email), s.gathering)

        val maybe = Regex("""<dialog id="manda-maybe".*?</dialog>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(text(maybe).contains("Mandare il promemoria?"))
        assertTrue(
            text(maybe).contains(
                "Mandiamo una mail a 3 persone: chi ha un’iscrizione in forse. Con la mail riceveranno un invito a confermare l’iscrizione, il titolo e le date della convivenza e il modo per smettere. " +
                    "Non ci sono nomi né dati dei bambini. Una mail partita non si ritira, e non potrai mandare un altro promemoria di questo tipo per 7 giorni.",
            ),
            text(maybe),
        )
        assertTrue(maybe.contains("""action="/convivenza/${s.gathering}/promemoria"""") && maybe.contains("""hx-target="#promemoria""""), "the answer replaces the card")
        assertTrue(maybe.contains("""name="tipo" value="maybe""""), "the kind is in the body")
        assertTrue(maybe.contains("Manda il promemoria") && maybe.contains("Invio…") && maybe.contains("Annulla"))
        assertFalse(maybe.contains("bg-danger"), "a dialog that sends is the main action, not the red one")
        val notice = Regex("""<dialog id="manda-notice".*?</dialog>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(text(notice).contains("Mandare l’avviso?"))
        assertTrue(
            text(notice).contains(
                "Mandiamo una mail a 1 persona: chi ha già iscritto i figli a una convivenza e ha chiesto un avviso. Con la mail riceveranno il titolo e le date della convivenza, " +
                    "il link della pagina informativa e il modo per smettere. Non ci sono nomi né dati dei bambini. Una mail partita non si ritira, e non potrai mandare un altro avviso per 7 giorni.",
            ),
            text(notice),
        )
        assertTrue(notice.contains("Manda l’avviso"))
        // The decision of 24 October: an invitation to confirm, never to withdraw.
        assertFalse(text(maybe).lowercase().contains("confermare o ritirare") || text(maybe).lowercase().contains("ritirarla"))
        assertTrue(text(maybe).contains("riceveranno un invito a confermare l’iscrizione"))
    }

    @Test
    fun `t13 c02 the page of confirmation without JavaScript has the same words, and goes back to the detail when there is nothing to confirm`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.m.email)

        val page = browser.get("/convivenza/${s.gathering}/promemoria?tipo=maybe")
        assertEquals(200, page.response.status)
        assertTrue(text(page.response.contentAsString).contains("Mandare il promemoria?"))
        assertTrue(text(page.response.contentAsString).contains("Mandiamo una mail a 1 persona: chi ha un’iscrizione in forse."))
        assertTrue(page.response.contentAsString.contains("""action="/convivenza/${s.gathering}/promemoria"""") && page.response.contentAsString.contains("""name="tipo" value="maybe""""))
        assertTrue(page.response.contentAsString.contains("""href="/convivenza/${s.gathering}""""), "«Annulla» goes back to the detail")

        // A reminder that cannot go (the last one is recent) has nothing to confirm: the detail says why.
        kit.batch(s.gathering, ReminderKind.maybe, 1)
        val waiting = browser.get("/convivenza/${s.gathering}/promemoria?tipo=maybe")
        assertEquals(303, waiting.response.status)
        assertEquals("/convivenza/${s.gathering}", waiting.response.getHeader("Location"))
        // A kind that does not exist, and a gathering that does not exist, are the page that does not exist.
        assertEquals(404, browser.get("/convivenza/${s.gathering}/promemoria?tipo=sms").response.status)
        assertEquals(404, browser.get("/convivenza/${s.gathering}/promemoria").response.status)
        assertEquals(404, browser.get("/convivenza/987654321/promemoria?tipo=maybe").response.status)
    }

    @Test
    fun `t13 c02 nobody is said in words, with why, and there is no button, for each kind`() {
        val s = kit.scenario()
        dsl.deleteFrom(REGISTRATION).where(REGISTRATION.ID.`in`(s.anna, s.nicolo)).execute()
        dsl.update(ACCOUNT).setNull(ACCOUNT.NOTICE_CONSENT_AT).execute()
        val html = detail(browsers.enter(s.m.email), s.gathering)

        val maybe = text(block(html, "maybe"))
        assertTrue(maybe.contains("Lo riceverebbero Nessuno"), maybe)
        assertTrue(maybe.contains("Nessuno da avvisare: non c’è nessuna iscrizione in forse con un indirizzo email. Le iscrizioni inserite dal cartaceo non hanno un indirizzo: se sono in forse, chiedi tu ai genitori."), maybe)
        val notice = text(block(html, "notice"))
        assertTrue(notice.contains("Lo riceverebbero Nessuno"), notice)
        assertTrue(notice.contains("Nessuno da avvisare: nessun genitore ha ancora chiesto l’avviso. L’avviso serve dalla seconda convivenza: i genitori lo chiedono dopo aver iscritto i figli."), notice)
        assertFalse(card(html).contains("data-apri"), "no button, no dialog")
        assertFalse(card(html).contains("<dialog"))
    }

    @Test
    fun `t13 c02 after a send the card says when and to how many people, and from when the next one can go, 7 days after to the minute, with no button`() {
        val s = kit.scenario()
        kit.inForse(s.gathering, "attesa-e")
        val sentAt = OffsetDateTime.now(clock).minusDays(2).withNano(0)
        kit.batch(s.gathering, ReminderKind.maybe, 2, recipients = 4, failed = 1, at = sentAt)
        val html = detail(browsers.enter(s.m.email), s.gathering)

        val maybe = text(block(html, "maybe"))
        assertTrue(maybe.contains("Ultimo invio ${RegistrationFormat.moment(sentAt)} · a 3 persone"), "4 written to, 1 did not leave: $maybe")
        assertTrue(maybe.contains("Potrai rimandarlo dal ${RegistrationFormat.moment(sentAt.plusDays(7))}."), maybe)
        assertFalse(block(html, "maybe").contains("data-apri"), "no button during the wait")
        // The other kind is untouched: still «Mai», with its button.
        assertTrue(text(block(html, "notice")).contains("Ultimo invio Mai"))
        assertTrue(block(html, "notice").contains("data-apri"))
    }

    @Test
    fun `t13 c02 after 7 days the button is back, and the last send is still said`() {
        val s = kit.scenario()
        val sentAt = OffsetDateTime.now(clock).minusDays(7).minusMinutes(1).withNano(0)
        kit.batch(s.gathering, ReminderKind.maybe, 7, recipients = 1, at = sentAt)
        val html = detail(browsers.enter(s.m.email), s.gathering)

        val maybe = text(block(html, "maybe"))
        assertTrue(maybe.contains("Ultimo invio ${RegistrationFormat.moment(sentAt)} · a 1 persona"), maybe)
        assertFalse(maybe.contains("Potrai rimandarlo"))
        assertTrue(block(html, "maybe").contains("data-apri"))
    }

    @Test
    fun `t13 c02 the card never has a name, an address or a number of children, whoever is in forse`() {
        val s = kit.scenario()
        val html = detail(browsers.enter(s.m.email), s.gathering)

        for (secret in listOf("Luca", "Anna", "Rossi", "Sara", "Neri", "Nicolò", "Ferri", s.a.email, s.b.email, s.c.email, s.d.email, "333 123 4567")) {
            assertFalse(card(html).contains(secret), "the card has «$secret»")
        }
        assertFalse(card(html).contains("data-codice") || card(html).contains("codice="), "no code in the page")
    }

    @Test
    fun `t13 c02 in a draft, with the registrations closed or over, the card has the sentence and no button`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.m.email)
        val sentence = "I promemoria si mandano con le iscrizioni aperte."

        // Closed.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(s.gathering)).execute()
        var html = detail(browser, s.gathering)
        assertEquals("Promemoria $sentence", text(card(html)))
        assertFalse(card(html).contains("data-apri") || card(html).contains("<dialog") || card(html).contains("<h3"), "no blocks, no buttons")

        // Open but over: the last day has passed.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`)
            .set(GATHERING.START_DATE, kit.today().minusDays(5)).set(GATHERING.END_DATE, kit.today().minusDays(3))
            .where(GATHERING.ID.eq(s.gathering)).execute()
        html = detail(browser, s.gathering)
        assertEquals("Promemoria $sentence", text(card(html)))

        // A draft: the other gathering is closed first, because only one is not closed.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(s.gathering)).execute()
        val draft = kit.support.make(title = "Convivenza in bozza", status = GatheringStatus.draft, custodians = listOf(s.m.id))
        html = detail(browser, draft)
        assertEquals("Promemoria $sentence", text(card(html)))
    }
}
