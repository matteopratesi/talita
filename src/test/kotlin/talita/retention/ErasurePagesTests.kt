package talita.retention

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.GatheringFormat
import talita.gathering.body
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.gathering.text
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.RETENTION_LOG
import talita.registration.Notes
import talita.registration.RegistrationRows
import talita.registration.RegistrationTestKit
import talita.registration.Setup
import talita.ui.Alert
import talita.ui.AlertVariant
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «I tuoi dati» in the profile and the deletions that it leads to (criteria 16-19, 21-26), in the pages: the children of the person, the dialogs with their exact words,
 * what blocks and where it leads, the answers of the requests (a notice that is read once, a second tap that is not an error, a 409 that changes nothing), the account that
 * goes and the session that ends, and that nobody reaches a child that is not theirs. The people have entered with the link; names, dates and numbers are synthetic.
 *
 * A and B are parents, Luca and Anna Rossi are children of A, M is a teacher. The gathering is the one of the test kit, made finished (it ended three days ago): the dates
 * in the sentences are worked out with the policy, as the pages do.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ErasurePagesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val policy: RetentionPolicy,
) {

    private val kit = RegistrationTestKit(mockMvc, tokens, dsl, clock)
    private val rows = RegistrationRows(dsl, clock)

    @org.junit.jupiter.api.BeforeEach
    fun clean() {
        dsl.deleteFrom(RETENTION_LOG).execute()
    }

    private fun words(html: String): String = text(html).replace(' ', ' ')

    private fun flash(browser: MockBrowser): Alert? = (browser.cookie as MockHttpSession?)?.getAttribute("talita.flash") as? Alert

    private fun htmx(): org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = { header("HX-Request", "true") }

    private data class Parent(val browser: MockBrowser, val id: Long, val email: String)

    private fun parent(name: String = "genitore"): Parent {
        val email = Addresses.unique(name)
        val browser = kit.kit.parent(email)
        return Parent(browser, kit.accountId(email), email)
    }

    /** The gathering of the kit, finished three days ago and closed. */
    private fun finished(): Setup {
        val setup = kit.start()
        kit.makeOver(setup.id)
        kit.setState(setup.id, GatheringStatus.closed)
        return setup
    }

    private fun end(setup: Setup): LocalDate = dsl.select(talita.jooq.tables.references.GATHERING.END_DATE).from(talita.jooq.tables.references.GATHERING)
        .where(talita.jooq.tables.references.GATHERING.ID.eq(setup.id)).fetchSingle(talita.jooq.tables.references.GATHERING.END_DATE)!!

    private fun until(setup: Setup): String = GatheringFormat.date(policy.registrationsDeletionDate(end(setup)))

    /** Luca (confirmed, with notes) and Anna (withdrawn) for A, at the finished gathering. */
    private fun twoChildren(setup: Setup, a: Parent): Pair<Long, Long> {
        val luca = rows.register(setup.id, a.id, "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        val anna = rows.register(setup.id, a.id, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.withdrawn)
        return luca to anna
    }

    private fun childId(registration: Long): Long = dsl.select(REGISTRATION.CHILD_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(registration)).fetchSingle(REGISTRATION.CHILD_ID)!!

    private fun profile(browser: MockBrowser): String = browser.get("/profilo").body()

    // ---- The card of the profile (criterion 16) -------------------------------------------------------------------------------------

    @Test
    fun `t7 c16 the profile has I tuoi dati with the children of the person, each with the button that cancels their data, and nobody else's`() {
        val setup = finished()
        val a = parent("genitore-a")
        val b = parent("genitore-b")
        val (luca, anna) = twoChildren(setup, a)
        rows.register(setup.id, b.id, "Sara", "Verdi")
        // A child that was detached from the account is not among «I tuoi figli».
        val detached = rows.register(setup.id, a.id, "Piero", "Neri")
        dsl.update(CHILD).set(CHILD.ACCOUNT_ID, null as Long?).where(CHILD.ID.eq(childId(detached))).execute()

        val html = profile(a.browser)
        val page = words(html)

        assertEquals("Profilo", heading(html))
        assertTrue(page.contains("I tuoi dati Teniamo nome, cognome e data di nascita dei tuoi figli per precompilare l’iscrizione dell’anno dopo. Li cancelli quando vuoi."), page)
        assertTrue(page.contains("Per quanto tempo teniamo i dati"))
        assertTrue(Regex("""<a href="/informativa#per-quanto-tempo" hx-boost="false"""").containsMatchIn(html), "the link to the notice is not boosted")
        assertTrue(page.contains("I tuoi figli"))
        assertTrue(Regex("""LR\s*Luca Rossi""").containsMatchIn(text(html)) && Regex("""AR\s*Anna Rossi""").containsMatchIn(text(html)))
        assertTrue(page.contains("Cancella i dati di Luca") && page.contains("Cancella i dati di Anna"))
        assertFalse(page.contains("Sara") || page.contains("Verdi"), "nothing of the children of B")
        assertFalse(page.contains("Cancella i dati di Piero"), "a child that was detached is not among «I tuoi figli»")
        assertEquals(1, Regex("""Piero""").findAll(page).count(), "he is only in the dialog of the account, where what stays is said")
        assertTrue(page.contains("Il tuo account Cancelliamo il tuo indirizzo email e quello che è a tuo nome. Per rientrare ti basta chiedere un nuovo link: troverai un account vuoto."))
        assertTrue(page.contains("Cancella il mio account"))
        // Not the main action: the buttons are the ones of danger in outline, and none is the button of danger in full outside a dialog.
        assertTrue(Regex("""<a href="/profilo/figli/${childId(luca)}/cancella" data-apri="cancella-figlio-${childId(luca)}" aria-label="Cancella i dati di Luca Rossi" class="[^"]*border-danger[^"]*text-danger""").containsMatchIn(html))
        assertTrue(anna > 0)
    }

    @Test
    fun `t7 c16 the name of two children with the same first name is both, the label is always both, and a parent without children and a teacher have their introduction`() {
        val setup = finished()
        val a = parent("genitore-a")
        rows.register(setup.id, a.id, "Luca", "Rossi")
        rows.register(setup.id, a.id, "Luca", "Bianchi", birth = LocalDate.of(2018, 5, 5))

        val page = words(profile(a.browser))
        assertTrue(page.contains("Cancella i dati di Luca Rossi") && page.contains("Cancella i dati di Luca Bianchi"), page)

        val without = words(profile(parent("genitore-senza-figli").browser))
        assertTrue(without.contains("Non ci sono figli collegati al tuo account. Quando iscrivi un bambino, i suoi dati compaiono qui e li puoi cancellare quando vuoi."), without)
        assertFalse(without.contains("I tuoi figli"))
        assertTrue(without.contains("Cancella il mio account"))

        val teacher = words(setup.marta.get("/profilo").body())
        assertTrue(teacher.contains("I tuoi dati Qui puoi cancellare il tuo account."), teacher)
        assertTrue(teacher.contains("Il tuo nome"), "a teacher has the name, and then the data")
        assertTrue(teacher.contains("Cancelliamo il tuo indirizzo email e il tuo nome. Non sarai più tra i maestri"), teacher)
    }

    // ---- What blocks (criteria 17, 24) ----------------------------------------------------------------------------------------------

    @Test
    fun `t7 c17 with the registrations open the explanation takes the place of the button and leads to the registrations, with them closed it says to ask the teachers`() {
        val setup = kit.start()
        val a = parent("genitore-a")
        val luca = rows.register(setup.id, a.id, "Luca", "Rossi")

        val open = profile(a.browser)
        assertTrue(words(open).contains("Per cancellare i dati di Luca, ritira prima la sua iscrizione alla «Convivenza di settembre 2027»."), words(open))
        assertFalse(open.contains("Cancella i dati di Luca"), "no button, no dialog")
        assertTrue(Regex("""<a href="/iscrizioni"[^>]*aria-label="Vai alle iscrizioni, per ritirare quella di Luca Rossi"[^>]*>[^<]*Vai alle iscrizioni</a>""").containsMatchIn(open), open)
        assertFalse(open.contains("cancella-figlio-"), "no dialog for a child that cannot be deleted")

        kit.setState(setup.id, GatheringStatus.closed)
        val closed = words(profile(a.browser))
        assertTrue(closed.contains("Le iscrizioni sono chiuse. Per cancellare i dati di Luca, chiedi ai maestri di ritirare la sua iscrizione alla «Convivenza di settembre 2027»."), closed)
        assertFalse(closed.contains("Vai alle iscrizioni"), "the parent cannot do it: no link")
        assertTrue(luca > 0)
    }

    @Test
    fun `t7 c17 a request made by hand when the child cannot be deleted changes nothing and is the profile as it is now, with 409`() {
        val setup = kit.start()
        val a = parent("genitore-a")
        val luca = rows.register(setup.id, a.id, "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        val child = childId(luca)

        val answer = a.browser.submit("/profilo", "/profilo/figli/$child/cancella", emptyMap())

        assertEquals(409, answer.status())
        val page = words(answer.body())
        assertTrue(page.contains("Non è stato cancellato niente. Le cose da fare prima sono scritte qui sotto."), page)
        assertTrue(page.contains("Per cancellare i dati di Luca, ritira prima la sua iscrizione"), "and below, what to do first")
        assertTrue(Regex("""role="alert"[^>]*tabindex="-1" data-fuoco""").containsMatchIn(answer.body()))
        assertEquals(1, dsl.fetchCount(HEALTH_NOTE))
        assertEquals(1, dsl.fetchCount(REGISTRATION))
        assertEquals(a.id, dsl.select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.ID.eq(child)).fetchSingle(CHILD.ACCOUNT_ID))
        assertEquals(0, dsl.fetchCount(RETENTION_LOG))
        // The page without JavaScript for a child that cannot be deleted leads back to the profile, which says why.
        val confirm = a.browser.get("/profilo/figli/$child/cancella")
        assertEquals(303, confirm.status())
        assertEquals("/profilo", confirm.location())
    }

    // ---- The dialogs (criterion 18) ---------------------------------------------------------------------------------------------------

    @Test
    fun `t7 c18 the dialog of a child whose registration stays says what goes now, what stays until when and why, the copies of safety, and that it cannot be undone`() {
        val setup = finished()
        val a = parent("genitore-a")
        val (luca, _) = twoChildren(setup, a)
        val child = childId(luca)

        val html = profile(a.browser)
        val dialog = Regex("""<dialog id="cancella-figlio-$child"[\s\S]*?</dialog>""").find(html)?.value
        assertNotNull(dialog)
        val said = words(dialog)

        assertTrue(said.startsWith("Cancellare i dati di Luca?"), said)
        assertTrue(
            said.contains(
                "Cancelliamo subito Luca dalla tua lista di figli: non comparirà più quando iscrivi un bambino. " +
                    "Le segnalazioni su allergie, intolleranze e farmaci di Luca e il consenso che hai dato. " +
                    "Resta L’iscrizione di Luca Rossi alla «Convivenza di settembre 2027», con il suo nome, la data di nascita e l’affidamento che hai accettato: " +
                    "resta fino al ${until(setup)}, come prova dell’affidamento. " +
                    "Anche le copie di sicurezza, che sono cifrate, perdono questi dati entro 30 giorni. Non si può annullare.",
            ),
            said,
        )
        assertTrue(said.contains("Cancella i dati di Luca") && said.contains("Annulla"))
        assertTrue(dialog.contains("autofocus"), "the first focus is on «Annulla»")
        assertTrue(Regex("""<form method="post" action="/profilo/figli/$child/cancella"[^>]*hx-disabled-elt""").containsMatchIn(dialog))
        assertTrue(dialog.contains("Cancellazione…"), "the busy label")
        assertTrue(Regex("""max-h-\[92dvh\][^"]*overflow-y-auto""").containsMatchIn(dialog), "it scrolls inside its panel when it is taller than the screen")
        assertTrue(Regex("""aria-describedby="cancella-figlio-$child-testo"""").containsMatchIn(dialog))
        assertFalse(Regex("""id="[^"]*(luca|rossi|Luca|Rossi)[^"]*"""").containsMatchIn(dialog), "no name in an id")
    }

    @Test
    fun `t7 c18 the dialog of a child that is deleted with everything says that nothing stays`() {
        val setup = finished()
        val a = parent("genitore-a")
        val (_, anna) = twoChildren(setup, a)

        val dialog = Regex("""<dialog id="cancella-figlio-${childId(anna)}"[\s\S]*?</dialog>""").find(profile(a.browser))!!.value
        val said = words(dialog)

        assertTrue(
            said.contains(
                "Cancellare i dati di Anna? Cancelliamo subito Il nome, il cognome e la data di nascita di Anna Rossi. " +
                    "L’iscrizione ritirata di Anna Rossi alla «Convivenza di settembre 2027», con i giorni scelti e il telefono. " +
                    "Non resta niente. Anche le copie di sicurezza, che sono cifrate, perdono questi dati entro 30 giorni. Non si può annullare.",
            ),
            said,
        )
        assertFalse(said.contains("Resta"), "no «Resta» when nothing stays")
    }

    // ---- The answer of the request (criteria 19, 22) -----------------------------------------------------------------------------------

    @Test
    fun `t7 c19 the confirmation deletes, leads to the profile with a notice that says what stays and until when, and Luca is not among the children any more`() {
        val setup = finished()
        val a = parent("genitore-a")
        val (luca, _) = twoChildren(setup, a)
        val child = childId(luca)

        val answer = a.browser.submit("/profilo", "/profilo/figli/$child/cancella", emptyMap())

        assertEquals(303, answer.status())
        assertEquals("/profilo", answer.location())
        val notice = assertNotNull(flash(a.browser))
        assertEquals(AlertVariant.SUCCESS, notice.variant)
        assertEquals("Dati di Luca cancellati.", notice.lead)
        assertEquals("Resta fino al ${until(setup)} l’iscrizione di Luca alla «Convivenza di settembre 2027», come prova dell’affidamento. La trovi in «Iscrizioni».", notice.text)
        assertEquals("/iscrizioni", notice.actionHref)

        val html = profile(a.browser)
        val page = words(html)
        assertTrue(page.contains("Dati di Luca cancellati. Resta fino al ${until(setup)} l’iscrizione di Luca"), page)
        assertTrue(Regex("""role="status"[^>]*tabindex="-1" data-fuoco""").containsMatchIn(html))
        assertFalse(page.contains("Cancella i dati di Luca"), "Luca is not among «I tuoi figli»")
        assertTrue(page.contains("Cancella i dati di Anna"))
        assertNull(flash(a.browser), "the notice is read once")
        assertEquals(0, dsl.fetchCount(HEALTH_NOTE))
    }

    @Test
    fun `t7 c19 when nothing stays the notice says so, and the child and the registration are gone`() {
        val setup = finished()
        val a = parent("genitore-a")
        val (_, anna) = twoChildren(setup, a)

        a.browser.submit("/profilo", "/profilo/figli/${childId(anna)}/cancella", emptyMap())

        val notice = assertNotNull(flash(a.browser))
        assertEquals("Dati di Anna cancellati.", notice.lead)
        assertEquals("Non resta niente.", notice.text)
        assertNull(notice.actionHref)
        assertEquals(0, dsl.fetchCount(CHILD, CHILD.FIRST_NAME.eq("Anna")))
        assertEquals(0, dsl.fetchCount(REGISTRATION, REGISTRATION.ID.eq(anna)))
    }

    @Test
    fun `t7 c22 a second request is the profile with an information notice and no error, and the register has one row`() {
        val setup = finished()
        val a = parent("genitore-a")
        val (_, anna) = twoChildren(setup, a)
        val child = childId(anna)

        assertEquals(303, a.browser.submit("/profilo", "/profilo/figli/$child/cancella", emptyMap()).status())
        val second = a.browser.submit("/profilo", "/profilo/figli/$child/cancella", emptyMap())

        assertEquals(303, second.status())
        assertEquals("/profilo", second.location())
        val notice = assertNotNull(flash(a.browser))
        assertEquals(AlertVariant.INFO, notice.variant)
        assertEquals("Questi dati erano già stati cancellati.", notice.text)
        assertEquals(1, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("child-request")))
        // The page without JavaScript, asked after the deletion, says the same and leads to the profile.
        val confirm = a.browser.get("/profilo/figli/$child/cancella")
        assertEquals(303, confirm.status())
        assertEquals("Questi dati erano già stati cancellati.", flash(a.browser)?.text)
        // A number that never existed, and a child that is not a number, are pages that do not exist.
        assertEquals(404, a.browser.submit("/profilo", "/profilo/figli/${child + 100_000}/cancella", emptyMap()).status())
        assertEquals(404, a.browser.get("/profilo/figli/${child + 100_000}/cancella").status())
    }

    // ---- Who can do it (criterion 21) -------------------------------------------------------------------------------------------------

    @Test
    fun `t7 c21 another parent and a teacher get the page of an id that does not exist for the child of A, on GET and on POST, and nothing changes`() {
        val setup = finished()
        val a = parent("genitore-a")
        val b = parent("genitore-b")
        val (luca, _) = twoChildren(setup, a)
        val child = childId(luca)

        for (other in listOf(b.browser, setup.marta)) {
            assertEquals(404, other.get("/profilo/figli/$child/cancella").status())
            assertEquals(404, other.submit("/profilo", "/profilo/figli/$child/cancella", emptyMap()).status())
        }
        assertEquals(a.id, dsl.select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.ID.eq(child)).fetchSingle(CHILD.ACCOUNT_ID))
        assertEquals(1, dsl.fetchCount(HEALTH_NOTE))
        assertEquals(0, dsl.fetchCount(RETENTION_LOG))

        // Without access: the access page, as for every other address.
        val nobody = MockBrowser(mockMvc)
        assertEquals("/accedi", nobody.get("/profilo/figli/$child/cancella").location())
        assertEquals("/accedi", nobody.post("/profilo/figli/$child/cancella").location())
        assertEquals("/accedi", nobody.get("/profilo/account/cancella").location())
        assertEquals("/accedi", nobody.post("/profilo/account/cancella").location())
    }

    @Test
    fun `t7 c21 the page without JavaScript has the title as its heading, the same words and a way back`() {
        val setup = finished()
        val a = parent("genitore-a")
        val (luca, _) = twoChildren(setup, a)
        val child = childId(luca)

        val page = a.browser.get("/profilo/figli/$child/cancella")
        val html = page.body()

        assertEquals(200, page.status())
        assertEquals("Cancellare i dati di Luca?", heading(html))
        val said = words(html)
        assertTrue(said.contains("Cancelliamo subito Luca dalla tua lista di figli") && said.contains("resta fino al ${until(setup)}, come prova dell’affidamento."), said)
        assertTrue(Regex("""<form method="post" action="/profilo/figli/$child/cancella"""").containsMatchIn(html))
        assertTrue(said.contains("Cancella i dati di Luca") && said.contains("Annulla"))
        assertTrue(Regex("""<a href="/profilo"[^>]*>Annulla</a>""").containsMatchIn(html))
    }

    // ---- The account (criteria 23-26) -----------------------------------------------------------------------------------------------------

    @Test
    fun `t7 c23 the dialog of the account says what goes now and what stays with the address copied into the acceptance`() {
        val setup = finished()
        val a = parent("genitore-a")
        twoChildren(setup, a)

        val dialog = Regex("""<dialog id="cancella-account"[\s\S]*?</dialog>""").find(profile(a.browser))!!.value
        val said = words(dialog)

        assertTrue(
            said.contains(
                "Cancellare il tuo account? Cancelliamo subito Il tuo indirizzo email e il tuo accesso a Talità. " +
                    "I dati di Anna Rossi: il nome, la data di nascita e l’iscrizione ritirata. " +
                    "Le segnalazioni di Luca Rossi e il consenso che hai dato. " +
                    "Resta L’iscrizione di Luca Rossi alla «Convivenza di settembre 2027», con il suo nome, la data di nascita, l’affidamento che hai accettato " +
                    "e il tuo indirizzo email scritto lì dentro: resta fino al ${until(setup)}, come prova dell’affidamento. " +
                    "Anche le copie di sicurezza, che sono cifrate, perdono questi dati entro 30 giorni. Non si può annullare. Cancella il mio account Cancellazione… Annulla",
            ),
            said,
        )

        val without = parent("genitore-vuoto")
        val empty = words(Regex("""<dialog id="cancella-account"[\s\S]*?</dialog>""").find(profile(without.browser))!!.value)
        assertTrue(empty.contains("Cancelliamo subito Il tuo indirizzo email e il tuo accesso a Talità. Non resta niente."), empty)
        assertFalse(empty.contains("Resta"), empty)
    }

    @Test
    fun `t7 c23 the dialog of a teacher who read notes and marked presences says that the address stays, and why`() {
        val setup = finished()
        val a = parent("genitore-a")
        val (luca, _) = twoChildren(setup, a)
        // Paolo is the other teacher: Marta reads Luca's notes and marks a presence.
        dsl.execute("insert into health_note_access (registration_id, teacher_account_id, accessed_at) values (?, ?, now())", luca, setup.martaId)
        dsl.execute("insert into checkin (registration_id, day, recorded_at, recorded_by_account_id) values (?, ?, now(), ?)", luca, end(setup), setup.martaId)

        val said = words(Regex("""<dialog id="cancella-account"[\s\S]*?</dialog>""").find(setup.marta.get("/profilo").body())!!.value)

        assertTrue(said.contains("Cancelliamo subito Il tuo accesso a Talità. Il tuo nome e cognome dall’elenco dei maestri: non gestirai più la convivenza né vedrai i bambini."), said)
        assertTrue(
            said.contains(
                "Resta Il tuo indirizzo email, senza nome: resta fino al ${until(setup)}. Nel registro delle letture c’è scritto che hai letto le segnalazioni di alcuni bambini, " +
                    "e i genitori hanno diritto di sapere chi le ha lette. " +
                    "Le presenze, i pagamenti e le iscrizioni dal modulo cartaceo che hai segnato: restano fino al ${until(setup)}, senza il tuo nome. " +
                    "Si leggerà «un maestro che non è più tra i maestri».",
            ),
            said,
        )
    }

    @Test
    fun `t7 c24 the account has its blocks in a list with the way to do what is first, and a request made by hand is a 409 that changes nothing`() {
        val setup = kit.start()
        val a = parent("genitore-a")
        rows.register(setup.id, a.id, "Luca", "Rossi")

        val parentPage = words(profile(a.browser))
        assertTrue(parentPage.contains("Per cancellare il tuo account, prima: ritira l’iscrizione di Luca alla «Convivenza di settembre 2027». Vai alle iscrizioni"), parentPage)
        assertFalse(parentPage.contains("Cancella il mio account"), "the sentence takes the place of the button")
        val answer = a.browser.submit("/profilo", "/profilo/account/cancella", emptyMap())
        assertEquals(409, answer.status())
        assertTrue(words(answer.body()).contains("Non è stato cancellato niente."))
        assertTrue(dsl.fetchExists(ACCOUNT, ACCOUNT.ID.eq(a.id)))

        // A teacher who is a custodian of a gathering that is not finished: «Togliti dagli affidatari», the same words and the same address as «Togli».
        val marta = words(setup.marta.get("/profilo").body())
        assertTrue(marta.contains("Per cancellare il tuo account, prima: togliti dagli affidatari della «Convivenza di settembre 2027». Togliti dagli affidatari"), marta)
        assertTrue(Regex("""<a href="/convivenza/${setup.id}/affidatari"""").containsMatchIn(setup.marta.get("/profilo").body()))
        assertEquals(409, setup.marta.submit("/profilo", "/profilo/account/cancella", emptyMap()).status())
        assertTrue(kit.accountId(kit.emailOf(setup.martaId)) == setup.martaId)
    }

    @Test
    fun `t7 c24 the last teacher is told to invite another one`() {
        val setup = kit.start()
        // Paolo is taken away: Marta is the only one, and she is not a custodian of anything that is not finished.
        kit.setState(setup.id, GatheringStatus.closed)
        kit.makeOver(setup.id)
        dsl.execute("delete from teacher where account_id = ?", setup.paoloId)

        val page = words(setup.marta.get("/profilo").body())

        assertTrue(page.contains("Per cancellare il tuo account, prima: invita un altro maestro: ora sei l’unico. Vai a Maestri"), page)
        assertEquals(409, setup.marta.submit("/profilo", "/profilo/account/cancella", emptyMap()).status())
    }

    @Test
    fun `t7 c24 a registration that a teacher put back after the child was detached blocks the account, and the profile says it and that only the teachers can withdraw it`() {
        val setup = kit.start()
        val a = parent("genitore-a")
        val luca = rows.register(setup.id, a.id, "Luca", "Rossi", status = RegistrationStatus.withdrawn)
        dsl.execute("insert into checkin (registration_id, day, recorded_at, recorded_by_account_id) values (?, ?, now(), ?)", luca, setup.friday, setup.martaId)
        // During the gathering the parent deletes the data of Luca: the registration is withdrawn and has a presence, so it stays, and the child is detached.
        assertEquals(303, a.browser.submit("/profilo", "/profilo/figli/${childId(luca)}/cancella", emptyMap()).status())
        assertNull(dsl.select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.ID.eq(childId(luca))).fetchSingle(CHILD.ACCOUNT_ID))
        // A teacher puts it back («Rimetti confermata») while the registrations are open.
        kit.setStatus(luca, RegistrationStatus.confirmed)
        // The notice of the deletion of Luca is read once, on the first page that follows (it has its own link to «Iscrizioni»): the page that counts is the second.
        profile(a.browser)

        val html = profile(a.browser)
        val page = words(html)

        val line = "Per cancellare il tuo account, prima: chiedi ai maestri di ritirare l’iscrizione di Luca alla «Convivenza di settembre 2027»: un maestro l’ha rimessa dopo che avevi cancellato i dati di Luca."
        assertTrue(page.contains(line), page)
        assertFalse(page.contains("Cancella il mio account"), "the sentence takes the place of the button, as with any other block")
        assertFalse(html.contains("cancella-account"), "no dialog for an account that cannot be deleted")
        assertFalse(page.contains("Vai alle iscrizioni"), "«Iscrizioni» only shows the registration of a child that was detached: no link to a page where the parent cannot do it")
        // The request by hand finds the same thing, and changes nothing: the page of the 409 has the line too.
        val answer = a.browser.submit("/profilo", "/profilo/account/cancella", emptyMap())
        assertEquals(409, answer.status())
        assertTrue(words(answer.body()).contains("Non è stato cancellato niente."))
        assertTrue(words(answer.body()).contains(line))
        assertTrue(dsl.fetchExists(ACCOUNT, ACCOUNT.ID.eq(a.id)))
        assertEquals(1, dsl.fetchCount(REGISTRATION, REGISTRATION.ID.eq(luca)))
    }

    @Test
    fun `t7 c25 the confirmation deletes the account, ends the session and leads to the access page, once, with Account cancellato`() {
        val setup = finished()
        val a = parent("genitore-a")
        twoChildren(setup, a)

        val answer = a.browser.submit("/profilo", "/profilo/account/cancella", emptyMap())

        assertEquals(303, answer.status())
        assertEquals("/accedi", answer.location())
        assertFalse(dsl.fetchExists(ACCOUNT, ACCOUNT.ID.eq(a.id)))
        val access = a.browser.get("/accedi")
        val said = words(access.body())
        assertTrue(said.contains("Account cancellato. Se ti serve, puoi rientrare con lo stesso indirizzo: troverai un account nuovo e vuoto."), said)
        assertTrue(Regex("""role="status"[^>]*tabindex="-1" data-fuoco""").containsMatchIn(access.body()))
        assertFalse(said.contains(until(setup)), "the access page says nothing of what stays")
        assertFalse(words(a.browser.get("/accedi").body()).contains("Account cancellato."), "once")
        // The session is over: a page of the person leads to the access page.
        assertEquals("/accedi", a.browser.get("/iscrizioni").location())
        // What stays: the registration of Luca, without the account and with the acceptance; Anna is gone.
        assertEquals(1, dsl.fetchCount(REGISTRATION))
        assertNull(dsl.select(REGISTRATION.ACCOUNT_ID).from(REGISTRATION).fetchSingle(REGISTRATION.ACCOUNT_ID))
        assertEquals(1, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("account-request")))
    }

    @Test
    fun `t7 c26 with htmx the answer is HX-Redirect to the access page as a whole page`() {
        val setup = finished()
        val a = parent("genitore-a")
        twoChildren(setup, a)

        val answer = a.browser.submit("/profilo", "/profilo/account/cancella", emptyMap(), htmx())

        assertEquals(200, answer.status())
        assertEquals("/accedi", answer.response.getHeader("HX-Redirect"))
        assertEquals("/accedi", a.browser.get("/iscrizioni", htmx()).response.getHeader("HX-Redirect"), "the old session leads to the access page too")
    }

    @Test
    fun `t7 c26 another phone with the session of the same account is taken to the access page as a whole page, never an error, and entering again gives a new empty account`() {
        val setup = finished()
        val email = Addresses.unique("genitore-doppio")
        val phone = kit.kit.parent(email)
        val tablet = MockBrowser(mockMvc).also { it.confirm(kit.kit.link(email)) }
        val account = kit.accountId(email)
        rows.register(setup.id, account, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.withdrawn)
        // The tablet has a page open, with the code of its forms.
        val code = checkNotNull(MockBrowser.csrfIn(tablet.get("/profilo").body()))

        assertEquals(303, phone.submit("/profilo", "/profilo/account/cancella", emptyMap()).status())

        // The tablet taps «Cancella il mio account» too (a second tap from another phone): the account is gone, and the answer is not an error, nor a piece of the access page
        // inside the old page: it is the order to load the access page whole.
        val piece = tablet.post("/profilo/account/cancella", emptyMap(), code, htmx())
        assertEquals(200, piece.status())
        assertEquals("/accedi", piece.response.getHeader("HX-Redirect"))
        val page = tablet.get("/iscrizioni")
        assertEquals(302, page.status())
        assertEquals("/accedi", page.location())
        assertTrue(words(tablet.get("/accedi").body()).contains("Account cancellato."))

        // The same address again is a new account, empty (the old one had nothing that had to stay).
        val again = kit.kit.parent(email)
        val fresh = kit.accountId(email)
        assertTrue(fresh != account, "a new account, not the old one")
        assertTrue(words(again.get("/iscrizioni").body()).contains("Nessuna iscrizione"))
    }

    // ---- The markup ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun `t7 c34 the profile with the dialogs has no inline style, script or handler, and the pages are not kept`() {
        val setup = finished()
        val a = parent("genitore-a")
        twoChildren(setup, a)

        val page = a.browser.get("/profilo")
        val html = page.body()

        assertFalse(Regex("""\sstyle=""").containsMatchIn(html))
        assertFalse(Regex("""<script(?![^>]*\ssrc=)""").containsMatchIn(html))
        assertFalse(Regex("""\son[a-z]+=""").containsMatchIn(html))
        assertFalse(html.contains("hx-on"))
        assertTrue((page.response.getHeader("Cache-Control") ?: "").contains("no-store"))
        assertEquals(setOf("cancella-account"), Regex("""<dialog id="(cancella-account)"""").findAll(html).map { it.groupValues[1] }.toSet())
        assertEquals(2, Regex("""<dialog id="cancella-figlio-\d+"""").findAll(html).count())
    }
}
