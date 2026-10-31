package talita.teacher

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.account.EmailAddress
import talita.account.MockBrowser
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.mail.Mailbox
import talita.ui.LONG_DATE
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * «Maestri» (criteria 40-43, 45, 46 and 48): the list by name, the invitations, the mail, the new send, the withdrawal, taking a
 * teacher away (never the last), and who may do all this, against the whole application. The failures of the mail are in
 * `MaestriFailureTests`, the correction of the name in the profile in `ProfileNameTests`.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class MaestriTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val invitations: Invitations,
    @Autowired private val teachers: Teachers,
    @Autowired private val clock: MutableClock,
) {

    private val support = MaestriSupport(mockMvc, tokens, dsl)

    private companion object {
        /** The addresses that only a POST does something at: a GET (a reload) leads to the page. */
        val ACTION_PATHS = listOf(
            "/maestri/invita", "/maestri/inviti/rimanda", "/maestri/inviti/ritira", "/maestri/togli",
            "/maestri/togli/conferma", "/maestri/inviti/ritira/conferma",
        )
    }

    private fun invitationCount(email: String) = dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(email))

    // ---- The page (criterion 40) ---------------------------------------------------------------------------------------

    @Test
    fun `t3 c40 the page Maestri lists the teachers by name and surname, marks the person, and never shows an address of a teacher`() {
        val me = Addresses.unique("io-maestra")
        val other = Addresses.unique("altro-maestro")
        support.onlyTeachers(Person(me, "Elisa", "Conti"), Person(other, "Paolo", "Ferri"))
        val browser = support.browser(me)

        val html = browser.page("/maestri")

        assertEquals("Maestri", heading(html))
        assertTrue(html.contains("<title>Maestri · Talità</title>"))
        assertTrue(html.contains("Chi organizza la convivenza insieme a te. I maestri vedono tutta la convivenza, i bambini e le segnalazioni."))
        assertTrue(html.contains(">I maestri</h2>") && html.contains(">Inviti in attesa</h2>") && html.contains(">Invita un maestro</h2>"))
        assertTrue(html.contains(">Elisa Conti</span>") && html.contains(">Paolo Ferri</span>"))
        // Alphabetical by surname; the person is marked, and only them.
        assertTrue(html.indexOf("Elisa Conti") < html.indexOf("Paolo Ferri"))
        assertEquals(1, Regex(">Sei tu</span>").findAll(html).count())
        assertTrue(html.contains(">EC</span>") && html.contains(">PF</span>"))
        // The addresses of the teachers are not in the page, not even hidden.
        assertFalse(html.contains(me) || html.contains(other), "an address of a teacher is in the page")
        // The current entry of the navigation is Convivenza, and it is the only one.
        assertTrue(Regex("""<a href="/convivenza" aria-current="page"""").containsMatchIn(html))
        assertEquals(1, Regex("""aria-current="page"""").findAll(html.substringAfter("<nav").substringBefore("</nav>")).count())
        assertTrue(html.contains("Nessun invito in attesa."))
        assertTrue(html.contains("""id="maestri-contenuto""""))
    }

    @Test
    fun `t3 c40 the invitations are listed by address with the expiry, or Scaduto with the date it expired`() {
        val me = Addresses.unique("lista-inviti")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val waiting = Addresses.unique("in-attesa")
        val expired = Addresses.unique("scaduto-lista")
        invitations.invite(EmailAddress.parse(waiting)!!)
        invitations.invite(EmailAddress.parse(expired)!!)
        dsl.update(TEACHER_INVITATION).set(TEACHER_INVITATION.EXPIRES_AT, Instant.now().minus(Duration.ofDays(2)).toOffset())
            .where(TEACHER_INVITATION.EMAIL.eq(expired)).execute()

        val html = support.browser(me).page("/maestri")

        assertTrue(html.contains(waiting) && html.contains(expired))
        assertTrue(Regex("""Scade il \d{1,2} [a-zà-ù]+ \d{4}""").containsMatchIn(html))
        assertTrue(Regex("""Scaduto</span>\s*Scadeva il \d{1,2} [a-zà-ù]+ \d{4}""").containsMatchIn(html), html.substringAfter("Inviti in attesa"))
        // For each one: sending again and withdrawing.
        assertEquals(2, Regex(">Manda di nuovo<").findAll(html).count())
        assertEquals(2, Regex("""<span class="busy:hidden">Ritira</span>""").findAll(html).count())
        invitations.withdraw(waiting)
        invitations.withdraw(expired)
    }

    @Test
    fun `t3 c40 the page Convivenza has the way to Maestri, for a teacher only`() {
        val me = Addresses.unique("link-maestri")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))

        val html = support.browser(me).page("/convivenza")

        assertTrue(html.contains(">Maestri</h2>"))
        assertTrue(Regex("""<a href="/maestri"[^>]*>[\s\S]*?Chi organizza la convivenza e chi puoi invitare\.[\s\S]*?</a>""").containsMatchIn(html))
    }

    // ---- Inviting (criteria 41 and 42) -----------------------------------------------------------------------------------

    @Test
    fun `t3 c41 inviting an address saves the invitation for 7 days, sends the mail with a link to the access page and no token, and says Invito mandato`() {
        val me = Addresses.unique("invita")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val guest = Addresses.unique("ospite")
        val browser = support.browser(me)

        val result = browser.act("/maestri/invita", mapOf("email" to guest))

        // htmx asks for the block and gets the block, with the notice and the new row; the field is empty again.
        assertEquals(200, result.response.status)
        val html = result.html()
        assertTrue(html.trimStart().startsWith("""<div id="maestri-contenuto">"""), html.take(120))
        assertFalse(html.contains("<html"), "a whole page came for a piece")
        assertTrue(html.contains("Invito mandato."))
        assertTrue(html.contains("""role="status""""))
        assertTrue(html.contains(guest))
        assertTrue(Regex("""<input id="email"[^>]*value=""[^>]*>""").containsMatchIn(html), "the field is not empty again")

        val invitation = invitations.pendingFor(guest)
        assertNotNull(invitation)
        val hours = Duration.between(clock.instant(), invitation.expiresAt).toHours()
        assertTrue(hours in 167..168, "the invitation lasts $hours hours, not 7 days")

        val mail = Mailbox.waitForMail(guest)
        assertEquals("Ti hanno invitato come maestro su Talità", mail.subject)
        assertEquals(listOf(TestcontainersConfiguration.PUBLIC_URL + "/accedi"), mail.links())
        assertFalse(mail.text.contains("token"), "the mail of an invitation has no token")
        assertTrue(mail.text.contains("accedi con questo stesso indirizzo email, quello a cui è arrivata questa mail"))
        assertTrue(mail.text.contains("L’invito vale fino al ${LONG_DATE.format(invitation.expiresAt)}."))
        assertEquals("Talità", mail.fromName)
        assertEquals(listOf("0"), mail.headers["x-mailjet-trackopen"])
        assertFalse(mail.text.contains(me) || mail.text.contains("Elisa") || mail.text.contains("Conti"), "the mail says who invited")
        invitations.withdraw(guest)
    }

    @Test
    fun `t3 c41 without JavaScript the invitation goes back to the page with the notice, once`() {
        val me = Addresses.unique("invita-senza-js")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val guest = Addresses.unique("ospite-senza-js")
        val browser = support.browser(me)

        val result = browser.act("/maestri/invita", mapOf("email" to guest), htmx = false)

        assertEquals(303, result.response.status)
        assertEquals("/maestri", result.location())
        val page = browser.page("/maestri")
        assertTrue(page.contains("Invito mandato.") && page.contains(guest))
        assertFalse(browser.page("/maestri").contains("Invito mandato."), "the notice came back")
        invitations.withdraw(guest)
    }

    @Test
    fun `t3 c41 inviting the same address again, or sending again, renews the expiry and sends the mail again, with one invitation only`() {
        val me = Addresses.unique("rinnova")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val guest = Addresses.unique("rinnovato")
        val browser = support.browser(me)
        browser.act("/maestri/invita", mapOf("email" to guest))
        // Three days go by. The clock is not moved: the session of the teacher, which lasts 12 hours at most (TALITA-15), would be over. The invitation is made older instead, as it would be.
        dsl.execute("update teacher_invitation set invited_at = invited_at - interval '3 days', expires_at = expires_at - interval '3 days' where email = ?", guest)
        val first = invitations.pendingFor(guest)!!.expiresAt

        browser.act("/maestri/invita", mapOf("email" to guest))
        val again = browser.act("/maestri/inviti/rimanda", mapOf("email" to guest))

        assertEquals(1, invitationCount(guest))
        val renewed = invitations.pendingFor(guest)!!.expiresAt
        assertTrue(renewed.isAfter(first.plus(Duration.ofDays(2))), "the expiry was not renewed: $first then $renewed")
        val mails = Mailbox.sentTo(guest)
        assertEquals(3, mails.size)
        assertTrue(mails.last().text.contains(LONG_DATE.format(renewed)))
        assertTrue(again.html().contains("Invito mandato."))
        assertEquals(1, Regex(">Manda di nuovo<").findAll(again.html()).count())
        invitations.withdraw(guest)
    }

    @Test
    fun `t3 c42 inviting an address that is a teacher creates nothing and says È già tra i maestri on the field`() {
        val me = Addresses.unique("gia-maestro-1")
        val colleague = Addresses.unique("gia-maestro-2")
        support.onlyTeachers(Person(me, "Elisa", "Conti"), Person(colleague, "Paolo", "Ferri"))
        val browser = support.browser(me)

        val result = browser.act("/maestri/invita", mapOf("email" to colleague.uppercase()))

        assertEquals(422, result.response.status)
        val html = result.html()
        assertTrue(html.contains("È già tra i maestri."))
        assertTrue(Regex("""<input id="email"[^>]*aria-invalid="true"""").containsMatchIn(html))
        assertTrue(html.contains("""value="${colleague.uppercase()}""""), "the field is not filled in as it was written")
        assertEquals(0, invitationCount(colleague))
        assertTrue(Mailbox.sentTo(colleague).isEmpty())
    }

    @Test
    fun `t3 c42 a malformed address has the same error as the page of access, and nothing is created`() {
        val me = Addresses.unique("indirizzo-sbagliato")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val browser = support.browser(me)
        val before = dsl.fetchCount(TEACHER_INVITATION)

        for (typed in listOf("", "nome", "nome@", "nome@example", Addresses.of("con spazi"))) {
            val result = browser.act("/maestri/invita", mapOf("email" to typed))

            assertEquals(422, result.response.status, "'$typed'")
            assertTrue(result.html().contains("Scrivi un indirizzo email completo, per esempio nome@example.org."), "'$typed'")
            assertTrue(Regex("""<input id="email"[^>]*aria-invalid="true"""").containsMatchIn(result.html()), "'$typed'")
        }
        assertEquals(before, dsl.fetchCount(TEACHER_INVITATION))
    }

    @Test
    fun `t3 c42 an address that has an account as a parent is invited and accepted, and the person is a teacher and a parent with one account`() {
        val me = Addresses.unique("invita-genitore")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val parent = Addresses.unique("era-genitore")
        support.browser(parent)   // enters as a parent: the account exists
        val accountId = support.accountId(parent)

        support.browser(me).act("/maestri/invita", mapOf("email" to parent))
        val browser = MockBrowser(mockMvc)
        val tap = browser.confirm(support.link(parent))
        assertEquals("/invito", tap.location())
        val accepted = browser.submit("/invito", "/invito", mapOf("nome" to "Marta", "cognome" to "Bianchi"))

        assertEquals(303, accepted.response.status)
        assertEquals(1, dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.eq(parent)))
        assertEquals(accountId, support.accountId(parent), "a second account")
        assertTrue(teachers.isTeacher(parent))
        assertEquals(200, browser.get("/maestri").response.status)
    }

    @Test
    fun `t3 c41 asking with a GET for the address of an action, as a reload of a page left there by a form without JavaScript does, leads to Maestri`() {
        val me = Addresses.unique("ricarica")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val browser = support.browser(me)

        for (path in ACTION_PATHS) {
            val result = browser.get(path)

            assertEquals(303, result.response.status, path)
            assertEquals("/maestri", result.location(), path)
        }
    }

    // ---- Withdrawing (criterion 43) ---------------------------------------------------------------------------------------------------

    @Test
    fun `t3 c43 withdrawing an invitation deletes it and says Invito ritirato, and whoever enters with that address enters as a parent`() {
        val me = Addresses.unique("ritira")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val guest = Addresses.unique("ritirata")
        val browser = support.browser(me)
        browser.act("/maestri/invita", mapOf("email" to guest))
        assertNotNull(invitations.pendingFor(guest))

        val result = browser.act("/maestri/inviti/ritira", mapOf("email" to guest))

        assertEquals(200, result.response.status)
        assertTrue(result.html().contains("Invito ritirato."))
        assertFalse(result.html().contains(guest), "the row is still there")
        assertEquals(0, invitationCount(guest))
        // With the mail of the invitation in hand: the link of access works, as for anyone, and leads to the home as a parent.
        val invited = MockBrowser(mockMvc)
        assertEquals("/", invited.confirm(support.link(guest)).location())
        assertEquals(403, invited.get("/maestri").response.status)
        // Said the same if it was gone already (another teacher did it a moment ago).
        assertTrue(browser.act("/maestri/inviti/ritira", mapOf("email" to guest)).html().contains("Invito ritirato."))
    }

    // ---- Taking a teacher away (criterion 45) -----------------------------------------------------------------------------------------

    @Test
    fun `t3 c45 taking another teacher away deletes the name and the role, keeps the account, and the person has the view of a parent at the next request`() {
        val me = Addresses.unique("togli-1")
        val other = Addresses.unique("togli-2")
        support.onlyTeachers(Person(me, "Elisa", "Conti"), Person(other, "Giulia", "Serra"))
        val teacher = support.browser(me)
        val removed = support.browser(other)
        assertEquals(200, removed.get("/convivenza").response.status)
        val id = support.accountId(other)

        val result = teacher.act("/maestri/togli", mapOf("maestro" to id.toString()))

        assertEquals(200, result.response.status)
        assertTrue(result.html().contains("Giulia Serra non è più tra i maestri."))
        assertFalse(result.html().contains(">Giulia Serra</span>"), "the row is still there")
        assertEquals(1, dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.eq(other)), "the account was deleted")
        assertEquals(0, dsl.fetchCount(TEACHER, TEACHER.ACCOUNT_ID.eq(id)), "the name is still there")
        // The person, with the session they already had: the pages of the teachers are closed to them.
        assertEquals(403, removed.get("/convivenza").response.status)
        assertEquals(403, removed.get("/maestri").response.status)
        assertEquals(200, removed.get("/iscrizioni").response.status)
        assertEquals(listOf("Home", "Iscrizioni"), navigation(removed.page("/")))
        // Taken away twice: said the same, nothing breaks.
        assertTrue(teacher.act("/maestri/togli", mapOf("maestro" to id.toString())).html().contains("non è più tra i maestri."))
        assertTrue(teachers.isTeacher(me))
    }

    @Test
    fun `t3 c45 a teacher who takes themself away goes to the home as a parent, with the notice`() {
        val me = Addresses.unique("togli-me")
        val other = Addresses.unique("togli-me-2")
        support.onlyTeachers(Person(me, "Elisa", "Conti"), Person(other, "Giulia", "Serra"))
        val browser = support.browser(me)

        val result = browser.act("/maestri/togli", mapOf("maestro" to support.accountId(me).toString()))

        // htmx loads the home as a whole page: navigation and role change.
        assertEquals(200, result.response.status)
        assertEquals("/", result.response.getHeader("HX-Redirect"))
        val home = browser.page("/")
        assertTrue(home.contains("Non sei più tra i maestri."))
        assertEquals(listOf("Home", "Iscrizioni"), navigation(home))
        assertEquals(403, browser.get("/maestri").response.status)
        assertTrue(teachers.isTeacher(other))
        assertFalse(teachers.isTeacher(me))
        assertEquals(1, dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.eq(me)), "the account was deleted")
    }

    @Test
    fun `t3 c45 without JavaScript taking someone away goes through a page that asks, with the same words as the dialog`() {
        val me = Addresses.unique("senza-js-1")
        val other = Addresses.unique("senza-js-2")
        support.onlyTeachers(Person(me, "Elisa", "Conti"), Person(other, "Paolo", "Ferri"))
        val browser = support.browser(me)
        val otherId = support.accountId(other).toString()

        val ask = browser.act("/maestri/togli/conferma", mapOf("maestro" to otherId), htmx = false)
        val html = ask.html()

        assertEquals(200, ask.response.status)
        assertEquals("Togliere Paolo Ferri dai maestri?", heading(html))
        assertTrue(html.contains("Paolo Ferri non potrà più gestire la convivenza né vedere i bambini e le segnalazioni. Il suo nome viene cancellato dall’elenco dei maestri. Potrà ancora entrare in Talità come genitore."))
        assertTrue(Regex("""<a href="/maestri"[^>]*>Annulla</a>""").containsMatchIn(html))
        assertTrue(html.contains("Togli dai maestri"))
        assertTrue(teachers.isTeacher(other), "asking removed them")

        // The button of that page does the work (a form that is not htmx), and the notice is in the page of «Maestri».
        val done = browser.act("/maestri/togli", mapOf("maestro" to otherId), htmx = false)
        assertEquals(303, done.response.status)
        assertEquals("/maestri", done.location())
        assertTrue(browser.page("/maestri").contains("Paolo Ferri non è più tra i maestri."))
        assertFalse(teachers.isTeacher(other))

        // The same for an invitation.
        val guest = Addresses.unique("senza-js-invito")
        browser.act("/maestri/invita", mapOf("email" to guest))
        val askInvitation = browser.act("/maestri/inviti/ritira/conferma", mapOf("email" to guest), htmx = false).html()
        assertEquals("Ritirare l’invito?", heading(askInvitation))
        assertTrue(askInvitation.contains("L’invito a $guest non varrà più. La persona potrà entrare in Talità, ma non come maestro."))
        assertEquals(1, invitationCount(guest), "asking withdrew it")
        invitations.withdraw(guest)
    }

    @Test
    fun `t3 c45 the dialogs of the page have the words of the design, with the verb of the title on the button, and no address or id in a URL`() {
        val me = Addresses.unique("dialoghi-1")
        val other = Addresses.unique("dialoghi-2")
        support.onlyTeachers(Person(me, "Elisa", "Conti"), Person(other, "Paolo", "Ferri"))
        val guest = Addresses.unique("dialoghi-invito")
        val browser = support.browser(me)
        browser.act("/maestri/invita", mapOf("email" to guest))

        val html = browser.page("/maestri")

        assertTrue(html.contains(">Togliere Paolo Ferri dai maestri?</h2>"))
        assertTrue(html.contains("Paolo Ferri non potrà più gestire la convivenza né vedere i bambini e le segnalazioni. Il suo nome viene cancellato dall’elenco dei maestri. Potrà ancora entrare in Talità come genitore."))
        assertTrue(html.contains(">Toglierti dai maestri?</h2>"))
        assertTrue(html.contains("Non potrai più gestire la convivenza né vedere i bambini e le segnalazioni. Il tuo nome viene cancellato dall’elenco dei maestri. Un altro maestro potrà invitarti di nuovo."))
        assertTrue(html.contains(">Ritirare l’invito?</h2>"))
        assertTrue(html.contains("L’invito a $guest non varrà più. La persona potrà entrare in Talità, ma non come maestro."))
        for (label in listOf("Togli dai maestri", "Toglimi dai maestri", "Ritira l’invito")) assertTrue(html.contains(label), label)
        // A dialog per row, with no name, no address and no id of a person in its own id; the first focus is on «Annulla».
        val ids = Regex("""<dialog id="([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(3, ids.size)
        for (id in ids) assertTrue(Regex("""(togli|ritira)-\d+""").matches(id), id)
        assertEquals(3, Regex("""<button autofocus[^>]*>Annulla</button>""").findAll(html).count())
        // Nothing of a person goes in an address of the page: the forms send it in the body.
        assertFalse(Regex("""(href|action)="[^"]*(maestro=|email=|@)[^"]*"""").containsMatchIn(html))
        invitations.withdraw(guest)
    }

    // ---- The last teacher (criterion 46) ---------------------------------------------------------------------------------------------------------

    @Test
    fun `t3 c46 with a single teacher there is no Togli, the page says why, and a request by hand changes nothing`() {
        val me = Addresses.unique("solo-uno")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val browser = support.browser(me)

        val html = browser.page("/maestri")
        assertTrue(html.contains("Serve almeno un maestro: per toglierti, invita prima un altro maestro."))
        assertFalse(html.contains("""<span class="busy:hidden">Togli</span>""") || html.contains(">Toglierti dai maestri?<"))

        val result = browser.act("/maestri/togli", mapOf("maestro" to support.accountId(me).toString()))

        assertEquals(422, result.response.status)
        assertTrue(result.html().contains("Serve almeno un maestro. Per togliere questa persona, invita prima un altro maestro."))
        assertTrue(result.html().contains("""role="alert""""))
        assertTrue(teachers.isTeacher(me))
        assertEquals(1, teachers.count())
    }

    @Test
    fun `t3 c46 two teachers who take each other away at the same moment leave one`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(25) { round ->
                val first = Addresses.unique("gara-a-$round")
                val second = Addresses.unique("gara-b-$round")
                support.onlyTeachers(Person(first, "Anna", "Rossi"), Person(second, "Bruno", "Verdi"))
                val firstId = support.accountId(first)
                val secondId = support.accountId(second)
                val start = CyclicBarrier(2)

                val results = listOf(secondId, firstId)
                    .map { target -> pool.submit<RemovalResult> { start.await(); teachers.remove(target) } }
                    .map { it.get(30, TimeUnit.SECONDS) }

                assertEquals(1, results.count { it is RemovalResult.Removed }, "round $round: $results")
                assertEquals(1, results.count { it == RemovalResult.LastTeacher }, "round $round: $results")
                assertEquals(1, teachers.count(), "round $round: ${teachers.count()} teachers are left")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- Who may (criterion 48) --------------------------------------------------------------------------------------------------------------------

    @Test
    fun `t3 c48 a parent, or someone with an invitation they have not accepted, is refused with 403 on every action and nothing changes`() {
        val teacher = Addresses.unique("c48-maestra")
        val target = Addresses.unique("c48-collega")
        support.onlyTeachers(Person(teacher, "Elisa", "Conti"), Person(target, "Paolo", "Ferri"))
        val targetId = support.accountId(target)
        val withdrawn = Addresses.unique("c48-da-ritirare")
        invitations.invite(EmailAddress.parse(withdrawn)!!)
        val victim = Addresses.unique("c48-vittima")
        val guest = Addresses.unique("c48-invitato")
        invitations.invite(EmailAddress.parse(guest)!!)

        val outsiders = listOf(
            "a parent" to support.browser(Addresses.unique("c48-genitore")),
            "someone with an invitation" to MockBrowser(mockMvc).also { assertEquals("/invito", it.confirm(support.link(guest)).location()) },
        )
        for ((who, browser) in outsiders) {
            for (htmx in listOf(true, false)) {
                for ((action, fields) in listOf(
                    "/maestri/invita" to mapOf("email" to victim),
                    "/maestri/inviti/rimanda" to mapOf("email" to withdrawn),
                    "/maestri/inviti/ritira" to mapOf("email" to withdrawn),
                    "/maestri/togli" to mapOf("maestro" to targetId.toString()),
                    "/maestri/togli/conferma" to mapOf("maestro" to targetId.toString()),
                    "/maestri/inviti/ritira/conferma" to mapOf("email" to withdrawn),
                    "/profilo/nome" to mapOf("nome" to "Altro", "cognome" to "Nome"),
                )) {
                    val result = browser.submit("/profilo", action, fields) { if (htmx) header("HX-Request", "true") }
                    assertEquals(403, result.response.status, "$who, $action, htmx=$htmx")
                }
            }
            assertEquals(403, browser.get("/maestri").response.status, "$who, GET /maestri")
            for (path in ACTION_PATHS + "/profilo/nome") assertEquals(403, browser.get(path).response.status, "$who, GET $path")
        }
        assertEquals(0, invitationCount(victim))
        assertTrue(Mailbox.sentTo(victim).isEmpty())
        assertEquals(1, invitationCount(withdrawn), "an invitation was withdrawn")
        assertTrue(teachers.isTeacher(target))
        assertEquals(2, teachers.count())
        assertEquals(0, dsl.fetchCount(TEACHER, TEACHER.FIRST_NAME.eq("Altro")))
        invitations.withdraw(withdrawn)
        invitations.withdraw(guest)
    }
}
