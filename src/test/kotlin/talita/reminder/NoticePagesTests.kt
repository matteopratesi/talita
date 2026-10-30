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
import talita.account.EmailAddress
import talita.account.LinkOutcome
import talita.account.LoginLinks
import talita.account.MockBrowser
import talita.gathering.text
import talita.jooq.tables.references.ACCOUNT
import talita.mail.Mailbox
import talita.registration.RegistrationFormat
import talita.teacher.InviteResult
import talita.teacher.InviteTeacher
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The avviso and the stop for the parents (TALITA-13, criteria 3, 10 and 11): the box in «Iscrizioni» and in the profile (never ticked before, saved with a button, with the moment), who sees
 * the card and who does not, the stop and taking the reminders back, what a person who stopped still receives, and that every one of these changes **only the account of the session**
 * (a test with two accounts, and with a request that carries the id of the other).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class NoticePagesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val links: LoginLinks,
    @Autowired private val invitations: InviteTeacher,
) {

    private val kit = ReminderTestKit(dsl, clock)

    private val browsers = ReminderBrowsers(mockMvc, tokens, dsl)

    @BeforeEach
    fun clean() = kit.reset()

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    private fun section(html: String, id: String): String = Regex("""<section id="$id".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)?.value ?: error("no section $id in the page")

    private fun ask(browser: MockBrowser, formPath: String, action: String, ticked: Boolean, htmx: Boolean = true) =
        browser.submit(formPath, action, if (ticked) mapOf("notice" to "true") else emptyMap()) { if (htmx) header("HX-Request", "true") }

    // ---- The card in «Iscrizioni» (criterion 3) ---------------------------------------------------------------------------------------------

    @Test
    fun `t13 c03 a parent with children finds the avviso at the bottom of Iscrizioni, not ticked, with the text that says what they get and how to stop`() {
        val s = kit.scenario()
        val html = browsers.enter(s.a.email).get("/iscrizioni").response.contentAsString

        val card = section(html, "avviso-card")
        assertTrue(html.indexOf("""id="avviso-card"""") > html.lastIndexOf("Iscrivi un altro figlio"), "after the registrations and «Iscrivi un altro figlio»")
        assertTrue(text(card).startsWith("Avviso per la prossima convivenza"), text(card))
        assertTrue(text(card).contains("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza"))
        assertTrue(
            text(card).contains(
                "Quando le iscrizioni di una nuova convivenza sono aperte, i maestri possono mandarti una mail con il link per iscrivere i bambini. Nella mail non ci sono i nomi dei bambini. Puoi smettere quando vuoi, da qui o dalla mail.",
            ),
            text(card),
        )
        assertTrue(Regex("""<input type="checkbox"[^>]*name="notice"""").containsMatchIn(card))
        assertFalse(Regex("""<input type="checkbox"[^>]*\bchecked\b""").containsMatchIn(card), "never ticked before the person ticks it")
        assertTrue(text(card).contains("Salva la scelta"))
        assertTrue(card.contains("""href="/profilo#email"""") && text(card).contains("Gestisci le email"))
        assertFalse(text(card).contains("Non mandarmi più promemoria"), "the stop is in the profile, not here")
        assertFalse(text(card).contains("Hai chiesto l’avviso"), "nothing was asked yet")
        assertTrue(card.contains("""hx-target="#avviso-card"""") && card.contains("""action="/iscrizioni/avviso""""), "the answer replaces this card")
    }

    @Test
    fun `t13 c03 ticking the box and saving keeps the moment and says so, and unticking takes the consent away`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.b.email)
        dsl.update(ACCOUNT).setNull(ACCOUNT.NOTICE_CONSENT_AT).where(ACCOUNT.ID.eq(s.b.id)).execute()
        val before = now().minusSeconds(1)

        val saved = ask(browser, "/iscrizioni", "/iscrizioni/avviso", ticked = true)

        assertEquals(200, saved.response.status)
        val html = saved.response.contentAsString
        assertTrue(html.trim().startsWith("<section id=\"avviso-card\""), "the answer is the card")
        assertTrue(html.contains("""role="status"""") && html.contains("data-fuoco"))
        assertTrue(text(html).contains("Scelta salvata. Ti avviseremo quando i maestri apriranno le iscrizioni di una nuova convivenza."), text(html))
        val moment = kit.consentOf(s.b.id)
        assertNotNull(moment, "the date and the hour are saved")
        assertTrue(moment.isAfter(before) && moment.isBefore(now().plusSeconds(1)))
        assertTrue(text(html).contains("Hai chiesto l’avviso il ${RegistrationFormat.moment(moment)}."), text(html))
        assertTrue(Regex("""<input type="checkbox"[^>]*\bchecked\b""").containsMatchIn(html))

        // Saved again as it is: no error, and the moment is the one of the first time.
        val again = ask(browser, "/iscrizioni", "/iscrizioni/avviso", ticked = true)
        assertEquals(200, again.response.status)
        assertEquals(moment, kit.consentOf(s.b.id), "the moment of the consent is not renewed")

        // Unticked: the consent is gone, and the page says so.
        val off = ask(browser, "/iscrizioni", "/iscrizioni/avviso", ticked = false)
        assertTrue(text(off.response.contentAsString).contains("Scelta salvata. Non ti avviseremo."), text(off.response.contentAsString))
        assertNull(kit.consentOf(s.b.id))
        assertFalse(text(off.response.contentAsString).contains("Hai chiesto l’avviso"))
        assertFalse(Regex("""<input type="checkbox"[^>]*\bchecked\b""").containsMatchIn(off.response.contentAsString))
        // Saved unticked again: the same, no error.
        assertEquals(200, ask(browser, "/iscrizioni", "/iscrizioni/avviso", ticked = false).response.status)
    }

    @Test
    fun `t13 c03 without JavaScript the box is saved with a form, back to the page with the notice once`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.a.email)

        val saved = ask(browser, "/iscrizioni", "/iscrizioni/avviso", ticked = true, htmx = false)

        assertEquals(303, saved.response.status)
        assertEquals("/iscrizioni#avviso-card", saved.response.getHeader("Location"))
        assertNotNull(kit.consentOf(s.a.id))
        val page = browser.get("/iscrizioni").response.contentAsString
        assertTrue(text(page).contains("Scelta salvata. Ti avviseremo quando i maestri apriranno le iscrizioni di una nuova convivenza."))
        assertFalse(text(browser.get("/iscrizioni").response.contentAsString).contains("Scelta salvata."), "once")
    }

    @Test
    fun `t13 c03 a parent without children does not see the card, in Iscrizioni or in the profile, and cannot ask for the avviso by hand`() {
        kit.scenario()
        val (browser, email) = browsers.parent("senza-figli")

        assertFalse(browser.get("/iscrizioni").response.contentAsString.contains("avviso-card"))
        val profile = browser.get("/profilo").response.contentAsString
        assertFalse(profile.contains("""id="email""""))
        assertFalse(text(profile).contains("Email da Talità"))
        assertTrue(text(browser.get("/iscrizioni").response.contentAsString).contains("Nessuna iscrizione"), "the empty state is the one of always")

        assertEquals(404, ask(browser, "/profilo", "/profilo/avviso", ticked = true).response.status)
        assertEquals(404, ask(browser, "/profilo", "/iscrizioni/avviso", ticked = true).response.status)
        val id = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!
        assertNull(kit.consentOf(id), "no consent without children")
    }

    // ---- The card in the profile ---------------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c03 the profile has Email da Talità between the access and the data, with the introduction, the box, the moment and the stop`() {
        val s = kit.scenario()
        val html = browsers.enter(s.b.email).get("/profilo").response.contentAsString

        val accesso = html.indexOf("""aria-labelledby="accesso"""")
        val email = html.indexOf("""<section id="email"""")
        val dati = html.indexOf("I tuoi dati")
        assertTrue(accesso in 0 until email && email < dati, "$accesso $email $dati")
        val card = section(html, "email")
        assertTrue(text(card).startsWith("Email da Talità Oltre ai link per entrare, Talità ti scrive solo per ricordarti di confermare un’iscrizione in forse e, se lo chiedi, per avvisarti quando si aprono le iscrizioni di una nuova convivenza. Nelle mail non ci sono i nomi dei bambini."), text(card))
        assertTrue(Regex("""<input type="checkbox"[^>]*\bchecked\b""").containsMatchIn(card), "B asked for it")
        assertTrue(text(card).contains("Hai chiesto l’avviso il ${RegistrationFormat.moment(kit.consentOf(s.b.id)!!)}."))
        assertTrue(text(card).contains("Non voglio più promemoria Non riceverai né i promemoria né l’avviso, e la tua richiesta di avviso si cancella. I link per entrare in Talità ti arrivano sempre."), text(card))
        assertTrue(text(card).contains("Non mandarmi più promemoria"))
        assertTrue(card.contains("""action="/profilo/avviso"""") && card.contains("""action="/profilo/promemoria/stop""""))
        // Without the consent the stop says only what the stop does.
        val a = browsers.enter(s.a.email).get("/profilo").response.contentAsString
        assertTrue(text(section(a, "email")).contains("Non voglio più promemoria Non riceverai né i promemoria né l’avviso. I link per entrare in Talità ti arrivano sempre."))
        assertFalse(text(section(a, "email")).contains("la tua richiesta di avviso si cancella"))
    }

    @Test
    fun `t13 c03 the box of the profile saves the same thing as the one of Iscrizioni`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.a.email)

        val saved = ask(browser, "/profilo", "/profilo/avviso", ticked = true)

        assertEquals(200, saved.response.status)
        assertTrue(saved.response.contentAsString.trim().startsWith("<section id=\"email\""))
        assertTrue(text(saved.response.contentAsString).contains("Scelta salvata. Ti avviseremo"))
        assertNotNull(kit.consentOf(s.a.id))
        // The card of the other place says it too, at the next load.
        assertTrue(Regex("""<input type="checkbox"[^>]*\bchecked\b""").containsMatchIn(section(browser.get("/iscrizioni").response.contentAsString, "avviso-card")))
        assertTrue(ask(browser, "/profilo", "/profilo/avviso", ticked = false).response.contentAsString.contains("Scelta salvata. Non ti avviseremo."))
    }

    // ---- Stop and resume (criterion 10) -----------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c10 the stop from the profile takes the avviso away, says so, and the moment is kept if it is repeated`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.b.email)

        val stopped = browser.submit("/profilo", "/profilo/promemoria/stop", emptyMap()) { header("HX-Request", "true") }

        assertEquals(200, stopped.response.status)
        val html = stopped.response.contentAsString
        assertTrue(html.contains("""role="status""""))
        assertTrue(text(html).contains("Non ti mandiamo più promemoria. La tua richiesta di avviso è stata cancellata."), text(html))
        val moment = kit.stopOf(s.b.id)
        assertNotNull(moment)
        assertNull(kit.consentOf(s.b.id), "the consent goes with the stop")
        assertTrue(text(html).contains("Per riprenderli basta un tocco: poi potrai chiedere di nuovo l’avviso."))
        assertTrue(text(html).contains("Riprendi i promemoria"))
        assertFalse(Regex("""<input type="checkbox"""").containsMatchIn(html), "no box while the reminders are stopped")
        assertFalse(text(html).contains("Non voglio più promemoria"))

        // Again: the same, and the first moment stays. Without a consent the notice has one sentence.
        val again = browser.submit("/profilo", "/profilo/promemoria/stop", emptyMap()) { header("HX-Request", "true") }
        assertTrue(text(again.response.contentAsString).contains("Non ti mandiamo più promemoria."))
        assertFalse(text(again.response.contentAsString).contains("La tua richiesta di avviso è stata cancellata."))
        assertEquals(moment, kit.stopOf(s.b.id), "the first moment is the one that counts")

        // The profile says since when, on the next load, with the day.
        val profile = browser.get("/profilo").response.contentAsString
        assertTrue(text(section(profile, "email")).contains("Hai smesso di ricevere i promemoria il ${RegistrationFormat.day(moment)}. I link per entrare in Talità ti arrivano comunque."), text(section(profile, "email")))
        // And «Iscrizioni» has the same notice with «Vai al profilo» and no box.
        val registrations = section(browser.get("/iscrizioni").response.contentAsString, "avviso-card")
        assertTrue(text(registrations).contains("Hai smesso di ricevere i promemoria il ${RegistrationFormat.day(moment)}."))
        assertTrue(text(registrations).contains("Vai al profilo") && registrations.contains("""href="/profilo#email""""))
        assertFalse(Regex("""<input type="checkbox"""").containsMatchIn(registrations))
    }

    @Test
    fun `t13 c10 taking the reminders back asks for nothing, and the avviso has to be asked for again`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.b.email)
        browser.submit("/profilo", "/profilo/promemoria/stop", emptyMap()) { header("HX-Request", "true") }

        val resumed = browser.submit("/profilo", "/profilo/promemoria/riprendi", emptyMap()) { header("HX-Request", "true") }

        assertEquals(200, resumed.response.status)
        val html = resumed.response.contentAsString
        assertTrue(text(html).contains("Da ora ricevi di nuovo i promemoria."), text(html))
        assertNull(kit.stopOf(s.b.id))
        assertNull(kit.consentOf(s.b.id), "the consent that went with the stop does not come back")
        assertTrue(Regex("""<input type="checkbox"[^>]*name="notice"""").containsMatchIn(html), "the box is there again")
        assertFalse(Regex("""<input type="checkbox"[^>]*\bchecked\b""").containsMatchIn(html), "and it is not ticked")
    }

    @Test
    fun `t13 c10 without JavaScript the stop and the resume are forms, and go back to the profile with the notice once`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.a.email)

        val stop = browser.submit("/profilo", "/profilo/promemoria/stop", emptyMap())
        assertEquals(303, stop.response.status)
        assertEquals("/profilo#email", stop.response.getHeader("Location"))
        assertNotNull(kit.stopOf(s.a.id))
        assertTrue(text(browser.get("/profilo").response.contentAsString).contains("Non ti mandiamo più promemoria."))
        assertFalse(text(browser.get("/profilo").response.contentAsString).contains("Non ti mandiamo più promemoria."), "once")

        val resume = browser.submit("/profilo", "/profilo/promemoria/riprendi", emptyMap())
        assertEquals(303, resume.response.status)
        assertNull(kit.stopOf(s.a.id))
        assertTrue(text(browser.get("/profilo").response.contentAsString).contains("Da ora ricevi di nuovo i promemoria."))
    }

    @Test
    fun `t13 c10 an account without children that stopped finds the card in the profile, to take the reminders back, and not in Iscrizioni`() {
        kit.scenario()
        val person = kit.account("fermo-senza-figli", stopped = now())
        val browser = browsers.enter(person.email)

        val profile = browser.get("/profilo").response.contentAsString
        assertTrue(text(section(profile, "email")).contains("Hai smesso di ricevere i promemoria il ${RegistrationFormat.day(now())}."))
        assertTrue(text(section(profile, "email")).contains("Riprendi i promemoria"))
        assertFalse(browser.get("/iscrizioni").response.contentAsString.contains("avviso-card"), "Iscrizioni has no card for who has no children")

        val resumed = browser.submit("/profilo", "/profilo/promemoria/riprendi", emptyMap()) { header("HX-Request", "true") }
        assertEquals(200, resumed.response.status)
        assertTrue(text(resumed.response.contentAsString).contains("Da ora ricevi di nuovo i promemoria."))
        assertNull(kit.stopOf(person.id))
        // The notice is in the answer; at the next load there is no card for someone with no children.
        assertFalse(browser.get("/profilo").response.contentAsString.contains("""id="email""""))
    }

    @Test
    fun `t13 c10 who stopped cannot ask for the avviso by hand, the answer is the card as it is, and nothing is written`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.b.email)
        browser.submit("/profilo", "/profilo/promemoria/stop", emptyMap()) { header("HX-Request", "true") }

        val refused = ask(browser, "/profilo", "/profilo/avviso", ticked = true)

        assertEquals(409, refused.response.status)
        assertNull(kit.consentOf(s.b.id))
        assertTrue(text(refused.response.contentAsString).contains("Hai smesso di ricevere i promemoria"))
    }

    @Test
    fun `t13 c10 the login links and the invitations still arrive to who stopped the reminders`() {
        val s = kit.scenario()
        browsers.enter(s.b.email).submit("/profilo", "/profilo/promemoria/stop", emptyMap()) { header("HX-Request", "true") }
        assertNotNull(kit.stopOf(s.b.id))

        assertEquals(LinkOutcome.SENT, links.request(EmailAddress.parse(s.b.email)!!))
        val link = Mailbox.waitForMail(s.b.email)
        assertTrue(link.subject.contains("link per entrare"), link.subject)
        // An invitation to be a teacher to the same address (the person is not a teacher): it is not a reminder either.
        val result = invitations.invite(EmailAddress.parse(s.b.email)!!)
        assertTrue(result is InviteResult.Sent, "$result")
        assertEquals(2, Mailbox.sentTo(s.b.email).size)
        assertTrue(Mailbox.sentTo(s.b.email).any { it.subject.contains("invitato") })
        // The invitation stays in the table of the invitations: take it away, the database is shared.
        dsl.deleteFrom(talita.jooq.tables.references.TEACHER_INVITATION).where(talita.jooq.tables.references.TEACHER_INVITATION.EMAIL.eq(s.b.email)).execute()
    }

    // ---- Of every account, and only theirs (criterion 11) -------------------------------------------------------------------------------------

    @Test
    fun `t13 c11 the avviso, the stop and the resume change only the account of the session, even with the id of another account in the request`() {
        val s = kit.scenario()
        val a = browsers.enter(s.a.email)
        val b = browsers.enter(s.b.email)
        val consentOfB = kit.consentOf(s.b.id)
        assertNotNull(consentOfB)

        // A asks for the avviso, with the id of B in the request: only A changes.
        val asked = a.submit("/profilo", "/profilo/avviso", mapOf("notice" to "true", "id" to s.b.id.toString(), "accountId" to s.b.id.toString(), "account" to s.b.id.toString(), "email" to s.b.email)) { header("HX-Request", "true") }
        assertEquals(200, asked.response.status)
        assertNotNull(kit.consentOf(s.a.id))
        assertEquals(consentOfB, kit.consentOf(s.b.id), "B did not change")

        // B stops, A is not touched.
        b.submit("/profilo", "/profilo/promemoria/stop", mapOf("id" to s.a.id.toString(), "account" to s.a.id.toString())) { header("HX-Request", "true") }
        assertNotNull(kit.stopOf(s.b.id))
        assertNull(kit.stopOf(s.a.id), "A did not stop")
        assertNotNull(kit.consentOf(s.a.id), "the consent of A is still there")

        // A unticks, B is not touched; A resumes (nothing to resume), B is still stopped.
        ask(a, "/profilo", "/profilo/avviso", ticked = false)
        a.submit("/profilo", "/profilo/promemoria/riprendi", mapOf("id" to s.b.id.toString())) { header("HX-Request", "true") }
        assertNull(kit.consentOf(s.a.id))
        assertNotNull(kit.stopOf(s.b.id), "B is still stopped: A has no way to take the reminders of B back")
    }

    @Test
    fun `t13 c11 a teacher who is also a parent finds the card in the profile as every account does`() {
        val s = kit.scenario()
        kit.rows.register(s.gathering, s.n.id, "Elia", "Verdi", java.time.LocalDate.of(2017, 7, 7), email = s.n.email)
        val profile = browsers.enter(s.n.email).get("/profilo").response.contentAsString

        assertTrue(text(section(profile, "email")).startsWith("Email da Talità"))
        assertEquals(200, ask(browsers.enter(s.n.email), "/profilo", "/profilo/avviso", ticked = true).response.status)
        assertNotNull(kit.consentOf(s.n.id))
    }

    @Test
    fun `t13 c11 the addresses of the parent are not under the rule of the teachers, and the one that stops is not either`() {
        for (path in listOf("/profilo/avviso", "/iscrizioni/avviso", "/profilo/promemoria/stop", "/profilo/promemoria/riprendi", "/promemoria/smetti")) {
            assertFalse(talita.account.TeacherPaths.matches(path), path)
        }
        assertTrue(talita.account.TeacherPaths.matches("/convivenza/12/promemoria"))
    }
}
