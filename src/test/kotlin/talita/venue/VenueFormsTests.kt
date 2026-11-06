package talita.venue

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import talita.CapturesOutput
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.checkin.htmx
import talita.checkin.words
import talita.gathering.GatheringTestKit
import talita.gathering.body
import talita.gathering.errorOf
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.gathering.text
import talita.gathering.title
import talita.gathering.valueOf
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.VENUE
import talita.jooq.tables.references.VENUE_FORM
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The forms of a venue in the page of the venue (TALITA-20, part 3, criteria 20 and 26): «Nuovo modulo», «Modifica il modulo» and «Togli il modulo», with the words of the design. A form is a **name and four boxes**, never a
 * value: the two boxes of the teachers say only that the sheet has a field. The teacher has entered with the link; a parent is tried on every address. Names, venues and numbers are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class VenueFormsTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
    @Autowired private val forms: VenueForms,
    @Autowired private val venues: Venues,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings

    private fun venue(name: String = "Casa del bosco", place: String = "Casa per ritiri del bosco, Località Esempio"): Long = gatherings.makeVenue(place, name)

    private fun MockBrowser.save(path: String, name: String, vararg boxes: String): MvcResult = submit(path, path, mapOf("name" to name) + boxes.associateWith { "true" })

    private fun rows(venue: Long): List<List<Any>> =
        dsl.select(VENUE_FORM.NAME, VENUE_FORM.WITH_AGE, VENUE_FORM.WITH_DAYS, VENUE_FORM.WITH_TEACHER_DOCUMENT, VENUE_FORM.WITH_TEACHER_TAX_CODE).from(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(venue)).orderBy(VENUE_FORM.ID)
            .fetch().map { it.intoList() as List<Any> }

    private fun formId(venue: Long, name: String): Long = dsl.select(VENUE_FORM.ID).from(VENUE_FORM).where(VENUE_FORM.VENUE_ID.eq(venue)).and(VENUE_FORM.NAME.eq(name)).fetchSingle(VENUE_FORM.ID)!!

    private fun child(first: String, last: String): Long =
        dsl.insertInto(CHILD).set(CHILD.FIRST_NAME, first).set(CHILD.LAST_NAME, last).set(CHILD.BIRTH_DATE, LocalDate.of(2016, 4, 4)).returningResult(CHILD.ID).fetchSingle().value1()!!

    private val registerName = "Registro per la tassa di soggiorno"

    /** An address, written in pieces: no file of the repository has one. */
    private val email = "marta" + "@" + "example.org"

    // ---- The form of a new form (criterion 20) -----------------------------------------------------------------------------------

    @Test
    fun `t20 c20 the page of a new form has the name and the four boxes in two groups, never ticked, with their help, and the venue under the title`() {
        val (browser, _) = kit.start()
        val venue = venue()

        val page = browser.get("/strutture/$venue/moduli/nuovo")
        val html = page.body()
        val words = text(html)

        assertEquals(200, page.status())
        assertEquals("Nuovo modulo", heading(html))
        assertEquals("Nuovo modulo · Talità", title(html))
        assertTrue(words.contains("Torna alla struttura"), words)
        assertTrue(words.contains("Casa del bosco"), "the venue is under the title: $words")
        assertTrue(words.contains("Nome del modulo Come lo chiama la struttura, per esempio «Registro per la tassa di soggiorno». Compare in alto sul foglio che stampi. Scrivi il nome del documento e non di una persona, senza numeri di telefono né email."), words)
        assertTrue(words.contains("Colonne per i bambini Cognome e nome dei bambini ci sono sempre. Aggiungi solo quello che la struttura chiede."), words)
        assertTrue(words.contains("Età al primo giorno Gli anni che ha il bambino il primo giorno della convivenza. Non la data di nascita."), words)
        assertTrue(words.contains("Giorni di presenza Una colonna per ogni giorno: «presente», «solo pranzo» o vuota."), words)
        assertTrue(words.contains("Colonne per i maestri Cognome e nome degli affidatari ci sono sempre. Documento e codice fiscale li scrivi sul foglio quando stampi: Talità non li riceve e non li salva, vanno solo sulla carta."), words)
        assertTrue(words.contains("Documento d’identità Un campo per maestro nella pagina di stampa. Quello che scrivi non si salva."), words)
        assertTrue(words.contains("Codice fiscale Un campo per maestro nella pagina di stampa. Quello che scrivi non si salva."), words)
        assertEquals(2, Regex("""<fieldset""").findAll(html).count())
        for (box in listOf("withAge", "withDays", "withTeacherDocument", "withTeacherTaxCode")) {
            val input = Regex("""<input[^>]*name="$box"[^>]*>""").find(html)!!.value
            assertFalse(input.contains("checked"), "$box is never ticked at the start")
        }
        assertTrue(Regex("""<form[^>]*\snovalidate""").containsMatchIn(html), "the errors are ours")
        assertTrue(words.contains("Salva il modulo") && words.contains("Annulla"), words)
        assertTrue(html.contains("""action="/strutture/$venue/moduli/nuovo"""") && html.contains("""href="/strutture/$venue#moduli""""))
        // A venue that is not there is not found, and so is the form of its forms.
        assertEquals(404, browser.get("/strutture/${venue + 100_000}/moduli/nuovo").status())
    }

    @Test
    fun `t20 c20 a good form is saved with its name without the spaces at the sides and only the choices, the page of the venue says Modulo salvato once and has the row with what the sheet has`() {
        val (browser, _) = kit.start()
        val venue = venue()
        val page = "/strutture/$venue"

        val saved = browser.save("$page/moduli/nuovo", "  $registerName ", "withAge", "withDays", "withTeacherDocument", "withTeacherTaxCode")
        assertEquals(303, saved.status())
        assertEquals("$page#moduli", saved.location())
        assertEquals(listOf(listOf(registerName, true, true, true, true)), rows(venue))
        val html = browser.get(page).body()
        val words = text(html)
        assertTrue(words.contains("Modulo salvato."), words)
        assertTrue(words.contains("Documenti che la struttura chiede. Talità li stampa da Arrivi, con i bambini presenti. Li vedono solo i maestri."), words)
        assertTrue(words.contains("$registerName Nome e cognome, età al primo giorno e giorni di presenza. Maestri: documento e codice fiscale."), words)
        assertFalse(text(browser.get(page).body()).contains("Modulo salvato."), "read once")
        // The boxes that are not ticked are not sent, and are off: every combination says its words.
        browser.save("$page/moduli/nuovo", "Solo nomi")
        browser.save("$page/moduli/nuovo", "Con l'età", "withAge")
        browser.save("$page/moduli/nuovo", "Con i giorni", "withDays")
        browser.save("$page/moduli/nuovo", "Con il documento", "withTeacherDocument")
        browser.save("$page/moduli/nuovo", "Con il codice", "withTeacherTaxCode")
        val list = text(browser.get(page).body())
        for (part in listOf(
            "Solo nomi Solo nome e cognome.", "Con l'età Nome e cognome, età al primo giorno.", "Con i giorni Nome e cognome, giorni di presenza.", "Con il documento Solo nome e cognome. Maestri: documento.",
            "Con il codice Solo nome e cognome. Maestri: codice fiscale.",
        )) {
            assertTrue(list.contains(part), "$part: $list")
        }
        assertEquals(listOf(false, false, false, false), rows(venue)[1].drop(1))
    }

    @Test
    fun `t20 c20 a form with a wrong name comes back with the 422, the error under the field, the name as it was written and the boxes as they were ticked, and nothing is written`() {
        val (browser, _) = kit.start()
        val venue = venue()
        val path = "/strutture/$venue/moduli/nuovo"
        browser.save(path, registerName)
        val before = rows(venue)

        fun check(name: String, error: String, vararg boxes: String) {
            val answer = browser.save(path, name, *boxes)
            val html = answer.body()
            assertEquals(422, answer.status(), "«$name»")
            assertEquals(error, errorOf(html, "nome-modulo"), "«$name»")
            assertEquals(name.trim(), valueOf(html, "nome-modulo"), "the field is as it was written")
            for (box in boxes) assertTrue(Regex("""<input[^>]*name="$box"[^>]*checked""").containsMatchIn(html), "$box stays ticked")
            assertTrue(Regex("""<input[^>]*id="nome-modulo"[^>]*aria-invalid="true"""").containsMatchIn(html), "the invalid field says so")
            assertEquals(before, rows(venue), "nothing was written")
        }
        check("", "Scrivi il nome del modulo.", "withAge")
        check("   ", "Scrivi il nome del modulo.")
        check("a".repeat(61), "Il nome può avere al massimo 60 caratteri: ne hai scritti 61.", "withDays", "withTeacherTaxCode")
        check("  REGISTRO per la tassa di soggiorno ", "C’è già un modulo con questo nome.", "withTeacherDocument")
        check("registro per la tassa di soggiorno", "C’è già un modulo con questo nome.")
        // A name of 60 characters goes in, and a break of line is a space.
        assertEquals(303, browser.save(path, "b".repeat(60)).status())
        assertEquals(303, browser.save(path, "Registro\ndei presenti").status())
        assertTrue(rows(venue).any { it[0] == "Registro dei presenti" })
    }

    @Test
    fun `t20 c20 the name of a form is checked like a public text, with an email, a phone number, an IBAN or the name of a person refused with the sentence of the document`() {
        val (browser, me) = kit.start()
        val venue = venue()
        val path = "/strutture/$venue/moduli/nuovo"
        child("Luca", "Rossi")
        val closing = "Toglili: scrivi solo il nome del documento."
        fun refused(name: String): String? {
            val answer = browser.save(path, name)
            assertEquals(422, answer.status(), name)
            return errorOf(answer.body(), "nome-modulo")
        }

        assertEquals("Qui c’è un indirizzo email. Toglilo: scrivi solo il nome del documento.", refused("Registro di $email"))
        assertEquals("Qui ci sono nome e cognome di una persona. $closing", refused("Registro di Luca Rossi"))
        assertEquals("Qui ci sono nome e cognome di una persona. $closing", refused("Rossi Luca"))
        assertEquals("Qui c’è un numero di telefono. Toglilo: scrivi solo il nome del documento.", refused("Registro 333 123 4567"))
        assertEquals("Qui c’è un IBAN. Toglilo: scrivi solo il nome del documento.", refused("Registro IT60 X054 2811 1010 0000 0123 456"))
        assertEquals("Qui ci sono nome e cognome di una persona, un indirizzo email e un numero di telefono. $closing", refused("Luca Rossi $email 333 123 4567"))
        // The name of a venue keeps its own words: the public ones.
        val public = browser.submit("/strutture/nuova", "/strutture/nuova", mapOf("name" to "Casa di Luca Rossi", "place" to "Un luogo"))
        assertTrue(errorOf(public.body(), "nome")!!.endsWith("la pagina la legge chiunque abbia il link."), errorOf(public.body(), "nome"))
        assertEquals(emptyList(), rows(venue))
        assertTrue(me > 0)
    }

    // ---- A form to change (criterion 20) ----------------------------------------------------------------------------------------

    @Test
    fun `t20 c20 the form of a change has the name and the boxes as they are now, keeps its own name with other capitals, takes none of another form, and a form of another venue is not found`() {
        val (browser, _) = kit.start()
        val venue = venue()
        val other = venue("Casa sul lago", "Casa per ritiri sul lago, Località Esempio")
        browser.save("/strutture/$venue/moduli/nuovo", registerName, "withAge", "withTeacherTaxCode")
        browser.save("/strutture/$venue/moduli/nuovo", "Registro presenze", "withDays")
        browser.save("/strutture/$other/moduli/nuovo", "Modulo del lago")
        val id = formId(venue, registerName)
        val foreign = formId(other, "Modulo del lago")
        val path = "/strutture/$venue/moduli/$id/modifica"

        val page = browser.get(path)
        val html = page.body()
        assertEquals(200, page.status())
        assertEquals("Modifica il modulo", heading(html))
        assertEquals("Modifica il modulo · Talità", title(html))
        assertEquals(registerName, valueOf(html, "nome-modulo"))
        assertTrue(Regex("""<input[^>]*name="withAge"[^>]*checked""").containsMatchIn(html) && Regex("""<input[^>]*name="withTeacherTaxCode"[^>]*checked""").containsMatchIn(html))
        assertFalse(Regex("""<input[^>]*name="withDays"[^>]*checked""").containsMatchIn(html))
        assertFalse(Regex("""<input[^>]*name="withTeacherDocument"[^>]*checked""").containsMatchIn(html))
        assertTrue(html.contains("""action="$path""""))

        // Other capitals are the same form, and the choices change.
        assertEquals(303, browser.save(path, "REGISTRO PER LA TASSA DI SOGGIORNO", "withDays", "withTeacherDocument").status())
        assertEquals(listOf("REGISTRO PER LA TASSA DI SOGGIORNO", false, true, true, false), rows(venue)[0])
        // The name of another form of the same venue is taken; the one of a form of another venue is not.
        val taken = browser.save(path, " registro presenze ")
        assertEquals(422, taken.status())
        assertEquals("C’è già un modulo con questo nome.", errorOf(taken.body(), "nome-modulo"))
        assertEquals(303, browser.save(path, "Modulo del lago").status())
        assertEquals("Modulo del lago", rows(venue)[0][0])
        // A form of another venue is a form that does not exist here.
        assertEquals(404, browser.get("/strutture/$venue/moduli/$foreign/modifica").status())
        assertEquals(404, browser.submit("/strutture/$venue/moduli/nuovo", "/strutture/$venue/moduli/$foreign/modifica", mapOf("name" to "Cambiato")).status())
        assertEquals("Modulo del lago", rows(other)[0][0])
        assertEquals(404, browser.get("/strutture/$venue/moduli/${id + 100_000}/modifica").status())
    }

    // ---- Taking a form away (criterion 20) --------------------------------------------------------------------------------------

    @Test
    fun `t20 c20 taking a form away has a dialog with the words of the design, answers with the block and Modulo tolto, and without JavaScript the page of confirmation and a redirect`() {
        val (browser, _) = kit.start()
        val venue = venue()
        browser.save("/strutture/$venue/moduli/nuovo", registerName, "withAge")
        browser.save("/strutture/$venue/moduli/nuovo", "Registro presenze", "withDays")
        val id = formId(venue, registerName)
        val other = formId(venue, "Registro presenze")
        val page = "/strutture/$venue"

        val html = browser.get(page).body()
        val dialog = Regex("""<dialog id="togli-modulo-$id".*?</dialog>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        assertTrue(
            words(dialog).contains("Togliere «$registerName»? Il modulo sparisce dalla struttura e non si stampa più da Arrivi. I fogli già stampati restano a chi li ha. Potrai riscriverlo quando vuoi. Togli il modulo Tolgo… Annulla"),
            words(dialog),
        )
        assertTrue(dialog.contains("""action="/strutture/$venue/moduli/$id/togli"""") && dialog.contains("""hx-target="#moduli-struttura""""), dialog)
        assertFalse(dialog.contains("bg-danger"), "the button is the main one: a form is written again in a minute")
        assertTrue(Regex("""<a href="/strutture/$venue/moduli/$id/togli" data-apri="togli-modulo-$id" aria-label="Togli $registerName"""").containsMatchIn(html))

        // With htmx: the block whole, with the notice and the sentence for a screen reader.
        val answer = browser.submit(page, "$page/moduli/$id/togli", mapOf("modulo" to id.toString()), htmx())
        assertEquals(200, answer.status())
        val block = answer.body()
        assertTrue(block.trimStart().startsWith("<div id=\"moduli-struttura\">"), block.take(80))
        assertTrue(words(block).contains("Modulo tolto."), words(block))
        assertFalse(words(block).contains(registerName), "the form is gone from the list")
        assertTrue(words(block).contains("Registro presenze Nome e cognome, giorni di presenza."), words(block))
        assertTrue(block.contains("""<p hx-swap-oob="innerHTML:#esito">Modulo tolto.</p>"""))
        assertEquals(listOf("Registro presenze"), rows(venue).map { it[0] })
        // Already taken away by another teacher: the state as it is, with its own words and no error.
        val again = browser.submit(page, "$page/moduli/$id/togli", mapOf("modulo" to id.toString()), htmx())
        assertEquals(200, again.status())
        assertTrue(words(again.body()).contains("Il modulo era già stato tolto."), words(again.body()))
        // Without JavaScript: the page of confirmation (with the words of the dialog), and a redirect with the notice left for the page.
        val confirm = browser.get("$page/moduli/$other/togli")
        assertEquals(200, confirm.status())
        assertEquals("Togliere «Registro presenze»?", heading(confirm.body()))
        assertTrue(words(confirm.body()).contains("Il modulo sparisce dalla struttura e non si stampa più da Arrivi."), words(confirm.body()))
        assertTrue(confirm.body().contains("""href="/strutture/$venue#moduli""""))
        val plain = browser.submit(page, "$page/moduli/$other/togli", emptyMap())
        assertEquals(303, plain.status())
        assertEquals("$page#moduli", plain.location())
        assertEquals(emptyList(), rows(venue))
        assertTrue(text(browser.get(page).body()).contains("Modulo tolto."))
        assertFalse(text(browser.get(page).body()).contains("Modulo tolto."), "read once")
        // The empty state of the card.
        assertTrue(text(browser.get(page).body()).contains("Nessun modulo. Se la struttura chiede un registro dei presenti, scrivilo qui: lo stampi da Arrivi."))
    }

    @Test
    fun `t20 c20 a form that never was in the venue, or is of another venue, is 404 and nothing changes`() {
        val (browser, _) = kit.start()
        val venue = venue()
        val other = venue("Casa sul lago", "Casa per ritiri sul lago, Località Esempio")
        browser.save("/strutture/$other/moduli/nuovo", "Modulo del lago")
        val foreign = formId(other, "Modulo del lago")
        val state = rows(other)

        assertEquals(404, browser.get("/strutture/$venue/moduli/$foreign/togli").status())
        assertEquals(404, browser.submit("/strutture/$venue", "/strutture/$venue/moduli/$foreign/togli", emptyMap()).status())
        assertEquals(404, browser.submit("/strutture/$venue", "/strutture/$venue/moduli/${foreign + 100_000}/togli", emptyMap()).status())
        assertEquals(404, browser.get("/strutture/${venue + 100_000}/moduli/$foreign/togli").status())
        assertEquals(state, rows(other))
    }

    // ---- The list, the venue that goes away, the database (criterion 20) -----------------------------------------------------------

    @Test
    fun `t20 c20 the list of the venues says the forms of each, and the dialog of taking a venue away says that its rooms and its forms go with it`() {
        val (browser, _) = kit.start()
        val venue = venue()
        val empty = venue("Casa sul lago", "Casa per ritiri sul lago, Località Esempio")
        dsl.insertInto(ROOM).set(ROOM.VENUE_ID, venue).set(ROOM.NAME, "Camera azzurra").set(ROOM.BEDS, 4.toShort()).execute()
        dsl.insertInto(ROOM).set(ROOM.VENUE_ID, venue).set(ROOM.NAME, "Camera 2").set(ROOM.BEDS, 2.toShort()).execute()
        browser.save("/strutture/$venue/moduli/nuovo", registerName)

        val list = text(browser.get("/strutture").body())
        assertTrue(list.contains("Casa del bosco Casa per ritiri del bosco, Località Esempio 2 camere, 6 posti · 1 modulo Nessuna convivenza la usa"), list)
        assertTrue(list.contains("Casa sul lago Casa per ritiri sul lago, Località Esempio Nessuna camera · Nessun modulo Nessuna convivenza la usa"), list)
        browser.save("/strutture/$venue/moduli/nuovo", "Registro presenze")
        assertTrue(text(browser.get("/strutture").body()).contains("2 camere, 6 posti · 2 moduli"))
        val dialog = text(browser.get("/strutture/$venue/togli").body())
        assertTrue(dialog.contains("Casa del bosco sparisce dall’elenco delle strutture. Nessuna convivenza la usa. Si cancellano anche le sue 2 camere e i suoi 2 moduli. Potrai scriverla di nuovo quando vuoi."), dialog)
        browser.save("/strutture/$empty/moduli/nuovo", "Solo nomi")
        assertTrue(text(browser.get("/strutture/$empty/togli").body()).contains("Casa sul lago sparisce dall’elenco delle strutture. Nessuna convivenza la usa. Si cancella anche il suo modulo. Potrai scriverla di nuovo quando vuoi."))
        // The venue goes, and its forms with it (the key cascades).
        assertEquals(303, browser.submit("/strutture/$empty", "/strutture/$empty/togli", mapOf("struttura" to empty.toString())).status())
        assertEquals(0, dsl.fetchCount(VENUE_FORM, VENUE_FORM.VENUE_ID.eq(empty)))
        assertEquals(2, dsl.fetchCount(VENUE_FORM, VENUE_FORM.VENUE_ID.eq(venue)))
    }

    @Test
    fun `t20 c20 the table of the forms has only the name and the four choices, never a value, and the database refuses an empty name, a name of 61 characters and the same name twice in a venue`() {
        kit.start()
        val columns = dsl.resultQuery("select column_name from information_schema.columns where table_schema = 'public' and table_name = 'venue_form' order by ordinal_position").fetch(0, String::class.java)
        assertEquals(listOf("id", "venue_id", "name", "with_age", "with_days", "with_teacher_document", "with_teacher_tax_code"), columns)
        val texts = dsl.resultQuery("select column_name from information_schema.columns where table_schema = 'public' and table_name = 'venue_form' and data_type in ('text', 'character varying') order by 1").fetch(0, String::class.java)
        assertEquals(listOf("name"), texts, "the only text is the name of the form: no value of a field")
        val venue = venue()
        fun insert(name: String) = dsl.insertInto(VENUE_FORM).set(VENUE_FORM.VENUE_ID, venue).set(VENUE_FORM.NAME, name).execute()
        insert(registerName)
        assertEquals(listOf(listOf(registerName, false, false, false, false)), rows(venue), "every choice is off by default")
        for (bad in listOf("   ", "x".repeat(61))) assertFailsWithMessage("venue_form_name_length") { insert(bad) }
        assertFailsWithMessage("venue_form_name_in_venue") { insert("  REGISTRO per la tassa di soggiorno") }
        // Another venue may have the same name.
        val other = venue("Casa sul lago", "Casa per ritiri sul lago, Località Esempio")
        dsl.insertInto(VENUE_FORM).set(VENUE_FORM.VENUE_ID, other).set(VENUE_FORM.NAME, registerName).execute()
        assertEquals(2, dsl.fetchCount(VENUE_FORM))
    }

    private fun assertFailsWithMessage(part: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure != null && generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains(part) }, "expected a refusal by $part, got $failure")
    }

    @Test
    fun `t20 c20 two teachers who write the same name at the same moment leave one form and an answer for the second, never an error`() {
        kit.start()
        val venue = venue()
        repeat(6) { round ->
            val name = "Registro $round"
            val pool = Executors.newFixedThreadPool(2)
            try {
                val start = CountDownLatch(1)
                val results = (1..2).map { pool.submit<FormSave> { start.await(10, TimeUnit.SECONDS); forms.create(venue, name, FormColumns(false, false, false, false)) } }
                start.countDown()
                val answers = results.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, answers.count { it is FormSave.Saved }, "round $round: $answers")
                assertEquals(1, answers.count { it == FormSave.NameTaken }, "round $round: $answers")
            } finally {
                pool.shutdownNow()
            }
        }
        assertEquals(6, dsl.fetchCount(VENUE_FORM, VENUE_FORM.VENUE_ID.eq(venue)))
        assertIs<FormSave.NotFound>(forms.create(venue + 100_000, "Nessuna casa", FormColumns(false, false, false, false)))
        assertNull(forms.find(venue, 0))
        assertEquals(6, venues.list().single { it.venue.id == venue }.forms)
    }

    // ---- Who can open them, the markup, the log (criteria 20 and 26) ----------------------------------------------------------------

    @Test
    fun `t20 c26 a parent is told 403 on every address of the forms with a page and with a form, and whoever has not entered goes to the access page, and nothing changes`() {
        val (teacher, _) = kit.start()
        val venue = venue()
        teacher.save("/strutture/$venue/moduli/nuovo", registerName)
        val id = formId(venue, registerName)
        val parent = kit.parent()
        val pages = listOf("/strutture/$venue/moduli/nuovo", "/strutture/$venue/moduli/$id/modifica", "/strutture/$venue/moduli/$id/togli")
        val posts = listOf(
            "/strutture/$venue/moduli/nuovo" to mapOf("name" to "Altro", "withAge" to "true"),
            "/strutture/$venue/moduli/$id/modifica" to mapOf("name" to "Cambiato", "withDays" to "true"),
            "/strutture/$venue/moduli/$id/togli" to mapOf("modulo" to id.toString()),
        )
        val before = rows(venue)

        for (path in pages) {
            assertEquals(403, parent.get(path).status(), "GET $path")
            val anonymous = mockMvc.get(path).andReturn().response
            assertEquals(302, anonymous.status, "GET $path")
            assertEquals("/accedi", anonymous.getHeader("Location"), "GET $path")
        }
        for ((path, fields) in posts) {
            val answer = mockMvc.post(path) { session = parent.cookie; with(csrf()); fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
            assertEquals(403, answer.status, "POST $path")
            val htmx = mockMvc.post(path) { session = parent.cookie; with(csrf()); header("HX-Request", "true"); fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
            assertEquals(403, htmx.status, "POST (htmx) $path")
            val anonymous = mockMvc.post(path) { fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
            assertEquals(302, anonymous.status, "POST $path")
            assertEquals("/accedi", anonymous.getHeader("Location"), "POST $path")
        }
        assertEquals(before, rows(venue), "nothing was changed")
        assertFalse(parent.get("/strutture/$venue/moduli/$id/modifica").body().contains(registerName), "the name of a form is never in the answer to a parent")
    }

    @Test
    fun `t20 c20 the pages of the forms have no style, no script, no handler and no link outside the application, and an address has only numbers`() {
        val (browser, _) = kit.start()
        val venue = venue()
        browser.save("/strutture/$venue/moduli/nuovo", registerName, "withAge", "withTeacherDocument")
        val id = formId(venue, registerName)
        val pages = mapOf(
            "the venue" to browser.get("/strutture/$venue").body(),
            "new" to browser.get("/strutture/$venue/moduli/nuovo").body(),
            "change" to browser.get("/strutture/$venue/moduli/$id/modifica").body(),
            "with errors" to browser.save("/strutture/$venue/moduli/nuovo", "").body(),
            "to remove" to browser.get("/strutture/$venue/moduli/$id/togli").body(),
            "removed" to browser.submit("/strutture/$venue", "/strutture/$venue/moduli/$id/togli", mapOf("modulo" to id.toString()), htmx()).body(),
        )
        for ((name, html) in pages) {
            assertTrue(html.length > 100, "$name is empty")
            assertFalse(Regex("""<script(?![^>]*\ssrc=)""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: inline script")
            assertFalse(Regex("""<[^>]+\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: on* attribute")
            assertFalse(Regex("""<[^>]+\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: style attribute")
            for (link in Regex("""\s(?:src|href|action|hx-post)\s*=\s*["']([^"']*)["']""").findAll(html).map { it.groupValues[1] }) {
                assertFalse(Regex("""^(?:[a-z][a-z0-9+.-]*:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(link) && !link.startsWith("#"), "$name: link outside the app $link")
                assertFalse(Regex("""registro|soggiorno""", RegexOption.IGNORE_CASE).containsMatchIn(link), "$name: a name in an address: $link")
            }
        }
    }

    @Test
    @CapturesOutput
    fun `t20 c26 the log has no name of a form and no name of a venue while the teachers write, change and take away the forms`(output: CapturedOutput) {
        val (browser, _) = kit.start()
        val venue = venue("Casa riservata di prova", "Un luogo di prova")
        browser.get("/strutture/$venue/moduli/nuovo")
        browser.save("/strutture/$venue/moduli/nuovo", "Modulo riservato di prova", "withAge", "withTeacherTaxCode")
        browser.save("/strutture/$venue/moduli/nuovo", "")
        browser.save("/strutture/$venue/moduli/nuovo", "Modulo riservato di prova")
        val id = formId(venue, "Modulo riservato di prova")
        browser.get("/strutture/$venue/moduli/$id/modifica")
        browser.save("/strutture/$venue/moduli/$id/modifica", "Modulo riservato cambiato")
        browser.submit("/strutture/$venue", "/strutture/$venue/moduli/$id/togli", mapOf("modulo" to id.toString()), htmx())
        browser.get("/strutture/$venue/moduli/${id + 100_000}/modifica")

        val log = output.all
        for (secret in listOf("Modulo riservato", "Casa riservata", "Un luogo di prova")) assertFalse(log.contains(secret), "the log has «$secret»")
    }
}
