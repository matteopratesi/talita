package talita.teacher

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.Addresses
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.account.EmailAddress
import talita.account.MockBrowser
import talita.account.MockSignIn
import talita.account.TestAccounts
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.mail.Mailbox
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accepting the invitation to be a teacher, with name and surname (criteria 31, 32 and 44, the part that is here). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AcceptInvitationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val invitations: Invitations,
    @Autowired private val clock: MutableClock,
) {

    private val signIn = MockSignIn(mockMvc, tokens, dsl)

    private fun invite(address: String) = invitations.invite(EmailAddress.parse(address)!!)

    /** A browser of the person that has just entered with [address], after an invitation. */
    private fun enter(address: String): Pair<MockBrowser, MvcResult> {
        val browser = MockBrowser(mockMvc)
        return browser to browser.confirm(signIn.link(address))
    }

    private fun MockBrowser.page(path: String): String = get(path).response.contentAsString

    private fun location(result: MvcResult) = result.response.getHeader("Location")

    private fun heading(html: String) = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()

    private fun teacherNames(address: String) =
        dsl.select(TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(TEACHER).join(ACCOUNT).on(ACCOUNT.ID.eq(TEACHER.ACCOUNT_ID))
            .where(ACCOUNT.EMAIL.eq(address)).fetchOne { it[TEACHER.FIRST_NAME]!! to it[TEACHER.LAST_NAME]!! }

    private fun accept(browser: MockBrowser, first: String, last: String): MvcResult =
        browser.submit("/invito", "/invito", mapOf("nome" to first, "cognome" to last))

    // ---- Entering with an invitation (criterion 31) --------------------------------------------------------------------------

    @Test
    fun `t3 c31 whoever enters with an address that has a valid invitation arrives at Accetta l'invito, and not at the page they asked for`() {
        val address = Addresses.unique("invitato")
        invite(address)
        val browser = MockBrowser(mockMvc)
        browser.get("/iscrizioni")

        val tap = browser.confirm(signIn.link(address))

        assertEquals(303, tap.response.status)
        assertEquals("/invito", location(tap))
    }

    @Test
    fun `t3 c31 the page Accetta l'invito has name and surname, with their keyboards, and the way to say later`() {
        val address = Addresses.unique("pagina-invito")
        invite(address)
        val (browser, _) = enter(address)

        val html = browser.page("/invito")

        assertEquals("Accetta l’invito", heading(html))
        assertTrue(html.contains("<title>Accetta l’invito · Talità</title>"))
        assertTrue(html.contains("Ti hanno invitato a organizzare la convivenza come maestro. Il tuo nome compare nell’elenco dei maestri e nei documenti della convivenza."))
        val nome = Regex("""<input id="nome"[^>]*>""").find(html)?.value
        val cognome = Regex("""<input id="cognome"[^>]*>""").find(html)?.value
        assertNotNull(nome)
        assertNotNull(cognome)
        assertTrue(nome.contains("""autocomplete="given-name"""") && nome.contains("""autocapitalize="words""""), nome)
        assertTrue(cognome.contains("""autocomplete="family-name"""") && cognome.contains("""autocapitalize="words""""), cognome)
        // Accents and apostrophes are not refused by the browser: no required, no maxlength, no pattern; the errors are ours.
        for (field in listOf(nome, cognome)) assertFalse(field.contains("required") || field.contains("maxlength") || field.contains("pattern"), field)
        assertTrue(Regex("""<form [^>]*novalidate""").containsMatchIn(html))
        assertTrue(html.contains(">Nome</label>") && html.contains(">Cognome</label>"))
        assertEquals(1, Regex("""<button type="submit"""").findAll(html).count())
        assertTrue(Regex("""Accetta l’invito\s*</span>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="/"[^>]*>Più tardi</a>""").containsMatchIn(html))
        assertTrue(html.contains("Se non ti aspettavi questo invito, tocca «Più tardi»: non cambia niente."))
        assertTrue(html.contains("""<nav aria-label="Navigazione principale""""))
        assertFalse(Regex("""<nav[\s\S]*?</nav>""").find(html)!!.value.contains("aria-current"), "an entry of the navigation is current")
    }

    // ---- Accepting (criterion 31) --------------------------------------------------------------------------------------------------

    @Test
    fun `t3 c31 name and surname make the person a teacher, without the spaces at the sides, the invitation is used up, and the home has the navigation of the teacher`() {
        val address = Addresses.unique("accetta")
        invite(address)
        val (browser, _) = enter(address)

        val result = accept(browser, "  Niccolò ", " D’Angiò  ")

        assertEquals(303, result.response.status)
        assertEquals("/", location(result))
        assertEquals("Niccolò" to "D’Angiò", teacherNames(address))
        assertNull(invitations.pendingFor(address))
        assertEquals(0, dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(address)), "the invitation is still there")
        val home = browser.page("/")
        assertTrue(home.contains("Invito accettato. Ora sei tra i maestri."))
        assertTrue(home.contains("""role="status""""))
        assertEquals(listOf("Home", "Convivenza", "Iscrizioni", "Arrivi"), Regex("""<span class="max-w-full truncate">([^<]+)</span>""").findAll(home).map { it.groupValues[1] }.toList())
        // Once, and the person does not come back to the invitation: /invito is now a page they have nothing to do in.
        assertFalse(browser.page("/").contains("Invito accettato"))
        assertEquals("/", location(browser.get("/invito")))
        // The initials in the header are those of the name.
        assertTrue(Regex("""class="grid size-8 [^"]*">ND</span>""").containsMatchIn(home))
        // And the next login leads to the home, as for every teacher.
        assertEquals("/", location(MockBrowser(mockMvc).confirm(signIn.link(address))))
    }

    @Test
    fun `t3 c31 accents and apostrophes of every kind are names`() {
        for ((first, last) in listOf("Zoë" to "O'Neil", "Ñandú" to "Müller", "Anne-Marie" to "de la Cruz", "李" to "王", "Ilaria" to "D’Amico")) {
            val address = Addresses.unique("nomi")
            invite(address)
            val (browser, _) = enter(address)

            assertEquals(303, accept(browser, first, last).response.status, "$first $last")
            assertEquals(first to last, teacherNames(address))
        }
    }

    @Test
    fun `t3 c31 a missing name or surname sends the form back, filled in, with the error under the field and the focus on it`() {
        val address = Addresses.unique("errori-nome")
        invite(address)
        val (browser, _) = enter(address)

        val missingFirst = accept(browser, "  ", "Conti")
        val html = missingFirst.response.contentAsString

        assertEquals(422, missingFirst.response.status)
        assertTrue(html.contains("Scrivi il nome."))
        assertFalse(html.contains("Scrivi il cognome."))
        assertTrue(Regex("""<input id="nome"[^>]*aria-invalid="true"""").containsMatchIn(html))
        assertTrue(Regex("""<input id="cognome"[^>]*aria-invalid="true"""").containsMatchIn(html).not())
        assertTrue(html.contains("""value="Conti""""))
        assertTrue(html.contains("""aria-describedby="nome-errore""""))
        assertFalse(html.contains("data-fuoco"), "a single error has no notice above the form")
        assertNull(teacherNames(address))

        val missingLast = accept(browser, "Elisa", "")
        assertEquals(422, missingLast.response.status)
        assertTrue(missingLast.response.contentAsString.contains("Scrivi il cognome."))
        assertTrue(missingLast.response.contentAsString.contains("""value="Elisa""""))
    }

    @Test
    fun `t3 c31 two missing fields have a notice on top that counts them, and each its own error`() {
        val address = Addresses.unique("due-errori")
        invite(address)
        val (browser, _) = enter(address)

        val result = accept(browser, "", " ")
        val html = result.response.contentAsString

        assertEquals(422, result.response.status)
        assertTrue(html.contains("Controlla i 2 campi segnati in rosso."))
        assertTrue(html.contains("""role="alert""""))
        assertTrue(html.contains("Scrivi il nome.") && html.contains("Scrivi il cognome."))
        assertNull(teacherNames(address))
    }

    @Test
    fun `t3 c31 more than 100 characters in a field is an error, 100 is not`() {
        val address = Addresses.unique("cento")
        invite(address)
        val (browser, _) = enter(address)

        val tooLong = accept(browser, "a".repeat(101), "b".repeat(100))
        assertEquals(422, tooLong.response.status)
        assertTrue(tooLong.response.contentAsString.contains("Usa al massimo 100 caratteri."))
        assertEquals(1, Regex("""Usa al massimo 100 caratteri\.""").findAll(tooLong.response.contentAsString).count())

        val both = accept(browser, "a".repeat(101), "b".repeat(101))
        assertEquals(2, Regex("""Usa al massimo 100 caratteri\.""").findAll(both.response.contentAsString).count())
        assertTrue(both.response.contentAsString.contains("Controlla i 2 campi segnati in rosso."))

        assertEquals(303, accept(browser, "a".repeat(100), "b".repeat(100)).response.status)
        assertEquals("a".repeat(100) to "b".repeat(100), teacherNames(address))
    }

    // ---- Later (criterion 32) ----------------------------------------------------------------------------------------------------------

    @Test
    fun `t3 c32 later leads to the home as a parent, which reminds of the invitation until it is accepted, withdrawn or expired`() {
        val address = Addresses.unique("piu-tardi")
        invite(address)
        val (browser, _) = enter(address)

        val home = browser.page("/")

        assertEquals(listOf("Home", "Iscrizioni"), Regex("""<span class="max-w-full truncate">([^<]+)</span>""").findAll(home).map { it.groupValues[1] }.toList())
        val until = Regex("""Ti hanno invitato come maestro\. L’invito vale fino al (\d{1,2} [a-zà-ù]+ \d{4})\.""").find(home)?.groupValues?.get(1)
        assertNotNull(until, home)
        assertTrue(Regex("""<a href="/invito"[^>]*>Accetta l’invito</a>""").containsMatchIn(home))
        assertTrue(home.contains("""role="status""""))
        // The reminder is there at every visit of the home, not once.
        assertTrue(browser.page("/").contains("Ti hanno invitato come maestro."))

        // Withdrawn: gone, and the page of the invitation leads to the home with no message.
        invitations.withdraw(address)
        assertFalse(browser.page("/").contains("Ti hanno invitato come maestro."))
        assertEquals("/", location(browser.get("/invito")))
        assertFalse(browser.page("/").contains("L’invito non vale più"))
    }

    @Test
    fun `t3 c32 the date of the reminder is the one of the expiry, in the format of the guide`() {
        val address = Addresses.unique("data")
        val expiresAt = invite(address)
        val (browser, _) = enter(address)

        val expected = talita.ui.LONG_DATE.format(expiresAt)

        assertTrue(browser.page("/").contains("L’invito vale fino al $expected."), expected)
        assertTrue(Regex("""\d{1,2} (gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre) \d{4}""").matches(expected), expected)
    }

    // ---- An invitation that is not valid (criteria 43 and 44) ------------------------------------------------------------------------

    @Test
    fun `t3 c44 an invitation that expired lets the person in as a parent, and the page of the invitation leads to the home`() {
        val address = Addresses.unique("scaduto")
        invite(address)
        clock.advance(invitations.validity.plusMinutes(1))

        val (browser, tap) = enter(address)

        assertEquals("/", location(tap), "an expired invitation sent the person to the page of the invitation")
        assertFalse(browser.page("/").contains("Ti hanno invitato come maestro."))
        assertEquals("/", location(browser.get("/invito")))
        assertEquals(403, browser.get("/convivenza").response.status)
        assertNull(invitations.pendingFor(address))
    }

    @Test
    fun `t3 c43 a withdrawn invitation lets the person in as a parent, even with the mail of the invitation in hand`() {
        val address = Addresses.unique("ritirato")
        invite(address)
        invitations.withdraw(address)

        val (browser, tap) = enter(address)

        assertEquals("/", location(tap))
        assertEquals("/", location(browser.get("/invito")))
        assertEquals(403, browser.get("/convivenza").response.status)
    }

    @Test
    fun `t3 c44 an invitation that expires or is withdrawn while the page is open leads to the home with a notice, and changes nothing`() {
        for (how in listOf("expired", "withdrawn")) {
            val address = Addresses.unique("durante-$how")
            invite(address)
            val (browser, _) = enter(address)
            val page = browser.get("/invito")
            assertEquals("Accetta l’invito", heading(page.response.contentAsString))
            val csrf = MockBrowser.csrfIn(page.response.contentAsString)

            // The invitation expires while the page is open. The clock is not moved: after the 7 days of an invitation the session of the person, which lasts 12 hours at most (TALITA-15), would be over.
            if (how == "expired") dsl.execute("update teacher_invitation set expires_at = now() - interval '1 minute' where email = ?", address) else invitations.withdraw(address)
            val result = browser.post("/invito", mapOf("nome" to "Elisa", "cognome" to "Conti"), csrf)

            assertEquals(303, result.response.status, how)
            assertEquals("/", location(result), how)
            val home = browser.page("/")
            assertTrue(home.contains("L’invito non vale più. Se serve, chiedi a un maestro di mandartene un altro."), how)
            assertTrue(home.contains("""role="alert""""), how)
            assertNull(teacherNames(address), how)
            assertFalse(browser.page("/").contains("L’invito non vale più"), "once: $how")
        }
    }

    @Test
    fun `t3 c31 a second tap on a form that already did its work leaves the person a teacher with the first name`() {
        val address = Addresses.unique("doppio-tocco")
        invite(address)
        val (browser, _) = enter(address)
        val page = browser.get("/invito")
        val csrf = MockBrowser.csrfIn(page.response.contentAsString)

        val first = browser.post("/invito", mapOf("nome" to "Elisa", "cognome" to "Conti"), csrf)
        val second = browser.post("/invito", mapOf("nome" to "Altro", "cognome" to "Nome"), csrf)

        assertEquals(303, first.response.status)
        assertEquals(303, second.response.status)
        assertEquals("/", location(second))
        assertEquals("Elisa" to "Conti", teacherNames(address))
        assertFalse(browser.page("/").contains("L’invito non vale più"))
    }

    @Test
    fun `t3 c31 a teacher who is invited again, by a row in the table, does not go to the invitation`() {
        val address = Addresses.unique("gia-maestro")
        TestAccounts.makeTeacher(dsl, address)
        invite(address)

        val (browser, tap) = enter(address)

        assertEquals("/", location(tap))
        assertNull(invitations.pendingFor(address))
        assertEquals("/", location(browser.get("/invito")))
        assertTrue(Mailbox.sentTo(address).isEmpty())
    }
}
