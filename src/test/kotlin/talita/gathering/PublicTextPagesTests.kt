package talita.gathering

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
import org.springframework.test.web.servlet.post
import talita.Addresses
import talita.CapturesOutput
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.teacher.Person
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The four texts that anyone with the link can read (TALITA-16, criteria 10 and 12-16): the help under each of them, the same in every page where the field is, and the error
 * that stops the save when a text holds the first and last name of a child or a teacher that the application knows. The teacher has entered with the link; names, places and
 * numbers are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PublicTextPagesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings

    /** The sentence of the error that the design writes for a name alone. */
    private val nameError = "Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link."

    private fun child(first: String, last: String): Long =
        dsl.insertInto(CHILD).set(CHILD.FIRST_NAME, first).set(CHILD.LAST_NAME, last).set(CHILD.BIRTH_DATE, LocalDate.of(2016, 4, 4)).returningResult(CHILD.ID).fetchSingle().value1()!!

    /** The teacher of the test, Marta Bianchi as another teacher, and the children Luca Rossi and Nicolò Bianchi. Returns the browser of the teacher. */
    private fun start(): MockBrowser {
        val (browser, _) = kit.start(Person(Addresses.unique("t16-elisa"), "Elisa", "Conti"), Person(Addresses.unique("t16-marta"), "Marta", "Bianchi"))
        child("Luca", "Rossi")
        child("Nicolò", "Bianchi")
        return browser
    }

    private fun MockBrowser.send(formPath: String, action: String, fields: Map<String, String>): MvcResult = submit(formPath, action, fields)

    private fun datesFields(id: Long, title: String, place: String): Map<String, String> {
        val gathering = checkNotNull(dsl.select(GATHERING.START_DATE, GATHERING.END_DATE).from(GATHERING).where(GATHERING.ID.eq(id)).fetchOne())
        return mapOf("title" to title, "startDate" to gathering[GATHERING.START_DATE].toString(), "endDate" to gathering[GATHERING.END_DATE].toString(), "place" to place)
    }

    private fun titleOf(id: Long): String = dsl.select(GATHERING.TITLE).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.TITLE)!!

    private fun placeOf(id: Long): String? = dsl.select(GATHERING.PLACE).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.PLACE)

    private fun infoOf(id: Long): Pair<String?, String?> =
        dsl.select(GATHERING.INFO_TEXT, GATHERING.WHAT_TO_BRING).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle().let { it[GATHERING.INFO_TEXT] to it[GATHERING.WHAT_TO_BRING] }

    // ---- The help (criterion 10) -----------------------------------------------------------------------------------------------

    /** The help under the field with this id, as the page writes it, and whether the field points to it with `aria-describedby`. */
    private fun helpOf(html: String, field: String): String {
        val help = Regex("""<p id="$field-aiuto"[^>]*>([\s\S]*?)</p>""").find(html)?.groupValues?.get(1)
        checkNotNull(help) { "no help under «$field»" }
        val tag = Regex("""<(?:input|textarea) id="$field"[^>]*>""").find(html)?.value
        checkNotNull(tag) { "no field «$field»" }
        assertTrue(Regex("""aria-describedby="$field-aiuto[" ]""").containsMatchIn(tag), "the field «$field» is not tied to its help: $tag")
        return text(help)
    }

    @Test
    fun `t16 c10 the help under title, place, information and what to bring says where the text appears and what not to write, in every page where the field is`() {
        val browser = start()
        val rule = "Non scrivere nome e cognome di bambini o maestri, numeri di telefono né email."

        assertEquals(rule, PublicText.RULE)
        // Title: «Nuova convivenza» (no gathering yet) and «Modifica titolo, date e luogo».
        gatherings.reset()
        assertEquals(
            "Per esempio «Convivenza di settembre 2027». Compare nella pagina per i genitori, nell’anteprima del link e nei promemoria. $rule",
            helpOf(browser.get("/convivenza/nuova").body(), "titolo"),
        )

        for ((label, status, past) in listOf(Triple("a draft", GatheringStatus.draft, false), Triple("with the registrations open", GatheringStatus.`open`, false), Triple("with the registrations closed", GatheringStatus.closed, false), Triple("over", GatheringStatus.closed, true))) {
            gatherings.reset()
            val id = if (past) gatherings.make(status = status, start = gatherings.today().minusDays(5), end = gatherings.today().minusDays(3)) else gatherings.make(status = status)
            val html = browser.get("/convivenza/$id/date").body()
            assertEquals(PublicText.TITLE_HELP, helpOf(html, "titolo"), "title, $label")
            assertEquals(
                "Il nome della casa o della struttura. Compare nella pagina per i genitori e nell’anteprima del link. $rule",
                helpOf(html, "luogo"),
                "place, $label",
            )
            assertFalse(text(html).contains("Serve per aprire le iscrizioni"), "$label: the help of the place does not say what the detail says")
        }

        gatherings.reset()
        val id = gatherings.make(status = GatheringStatus.draft)
        val info = browser.get("/convivenza/$id/informazioni").body()
        assertEquals(
            "Chi può partecipare, gli orari, come si arriva, l’aiuto per i trasporti. Compare nella pagina per i genitori. $rule",
            helpOf(info, "informazioni"),
        )
        assertEquals("Scrivi una cosa per riga: nella pagina per i genitori diventa un elenco. $rule", helpOf(info, "cosa-portare"))
        assertEquals(listOf(PublicText.INFO_HELP, PublicText.BRING_HELP), listOf(helpOf(info, "informazioni"), helpOf(info, "cosa-portare")))
        assertEquals(PublicText.PLACE_HELP, helpOf(browser.get("/convivenza/$id/date").body(), "luogo"))
        assertFalse(text(info).contains("Non scrivere nomi,"), "the old sentence is gone")
    }

    // ---- The error (criteria 12-16) ----------------------------------------------------------------------------------------------

    @Test
    fun `t16 c12 a new gathering with the name of a child in the title is refused with a 422, the field as it was written and nothing saved`() {
        val browser = start()
        val dates = gatherings.today().plusDays(30)

        for (title in listOf("Convivenza con Luca Rossi", "rossi luca", "LUCA ROSSÌ", "Nicolo Bianchi", "Marta Bianchi")) {
            val result = browser.send("/convivenza/nuova", "/convivenza/nuova", mapOf("title" to title, "startDate" to dates.toString(), "endDate" to dates.plusDays(2).toString()))
            val html = result.body()

            assertEquals(422, result.status(), title)
            assertEquals(nameError, errorOf(html, "titolo"), title)
            assertEquals(title, valueOf(html, "titolo"), "the field keeps what was written")
            assertEquals(0, kit.count(), "nothing was saved: $title")
            assertFalse(text(html).contains("Controlla i"), "one error: no notice above, the focus goes to the field")
        }
        // The same with the name of another person who is not known, the name alone and the two parts apart: it is saved.
        for (title in listOf("Convivenza di Lucarossi", "Luca, Rossi", "Convivenza con Luca", "Convivenza con Rossi")) {
            val created = browser.send("/convivenza/nuova", "/convivenza/nuova", mapOf("title" to title, "startDate" to dates.toString(), "endDate" to dates.plusDays(2).toString()))
            assertEquals(303, created.status(), title)
            dsl.deleteFrom(GATHERING).execute()
        }
    }

    @Test
    fun `t16 c12 the four texts of the gathering refuse the name of a child or of a teacher, in a draft and also after the end, with nothing saved`() {
        val browser = start()
        for (over in listOf(false, true)) {
            // `reset` clears the children too: the two of the test are made again for each turn.
            gatherings.reset()
            child("Luca", "Rossi")
            child("Nicolò", "Bianchi")
            val id = if (over) gatherings.make(status = GatheringStatus.closed, start = gatherings.today().minusDays(5), end = gatherings.today().minusDays(3)) else gatherings.make(status = GatheringStatus.draft)
            val before = Triple(titleOf(id), placeOf(id), infoOf(id))

            // Title and place, from «Modifica titolo, date e luogo».
            val dates = browser.send("/convivenza/$id/date", "/convivenza/$id/date", datesFields(id, "Convivenza di Luca Rossi", "Casa di Nicolo Bianchi"))
            assertEquals(422, dates.status(), "dates, over = $over")
            assertEquals(nameError, errorOf(dates.body(), "titolo"))
            assertEquals(nameError, errorOf(dates.body(), "luogo"))
            assertEquals("Convivenza di Luca Rossi", valueOf(dates.body(), "titolo"))
            assertEquals("Casa di Nicolo Bianchi", valueOf(dates.body(), "luogo"))
            assertTrue(text(dates.body()).contains("Controlla i 2 campi segnati in rosso."), "two errors: the notice above")

            // The title alone, then the place alone: one error each.
            val onlyTitle = browser.send("/convivenza/$id/date", "/convivenza/$id/date", datesFields(id, "rossi luca", "Casa per ritiri di esempio"))
            assertEquals(422, onlyTitle.status())
            assertEquals(nameError, errorOf(onlyTitle.body(), "titolo"))
            assertNull(errorOf(onlyTitle.body(), "luogo"))
            val onlyPlace = browser.send("/convivenza/$id/date", "/convivenza/$id/date", datesFields(id, "Convivenza di settembre 2027", "Casa di Marta Bianchi"))
            assertEquals(422, onlyPlace.status())
            assertNull(errorOf(onlyPlace.body(), "titolo"))
            assertEquals(nameError, errorOf(onlyPlace.body(), "luogo"))

            // Information and what to bring.
            val info = browser.send("/convivenza/$id/informazioni", "/convivenza/$id/informazioni", mapOf("infoText" to "Per informazioni chiedete a Marta Bianchi.", "whatToBring" to "Un telo\nIl cappello di LUCA ROSSÌ"))
            assertEquals(422, info.status(), "information, over = $over")
            assertEquals(nameError, errorOf(info.body(), "informazioni"))
            assertEquals(nameError, errorOf(info.body(), "cosa-portare"))
            assertEquals("Per informazioni chiedete a Marta Bianchi.", valueOf(info.body(), "informazioni"))
            assertEquals("Un telo\nIl cappello di LUCA ROSSÌ", valueOf(info.body(), "cosa-portare"))

            assertEquals(before, Triple(titleOf(id), placeOf(id), infoOf(id)), "nothing was saved, over = $over")
        }
    }

    @Test
    fun `t16 c13 the name alone, the two parts apart and the parts glued are saved in the four texts`() {
        val browser = start()
        val id = gatherings.make(status = GatheringStatus.draft)

        assertEquals(303, browser.send("/convivenza/$id/date", "/convivenza/$id/date", datesFields(id, "Il torneo di Luca", "Casa Rossi")).status())
        assertEquals(303, browser.send("/convivenza/$id/informazioni", "/convivenza/$id/informazioni", mapOf("infoText" to "Luca, Rossi e Lucarossi.", "whatToBring" to "Luca e Rossi")).status())
        assertEquals("Il torneo di Luca", titleOf(id))
        assertEquals("Casa Rossi", placeOf(id))
        assertEquals("Luca, Rossi e Lucarossi." to "Luca e Rossi", infoOf(id))
    }

    @Test
    fun `t16 c14 with no child nothing is found, a deleted child does not count, and a text saved before the child came is refused at the next save, also in a field that was not touched`() {
        val browser = start()
        dsl.deleteFrom(CHILD).execute()
        val id = gatherings.make(status = GatheringStatus.draft, info = "Chiedete a Luca Rossi")

        // The first year: no child at all, and the text is saved (the only people that are known are the teachers).
        assertEquals(303, browser.send("/convivenza/$id/informazioni", "/convivenza/$id/informazioni", mapOf("infoText" to "Chiedete a Luca Rossi", "whatToBring" to "")).status())

        // The child arrives. The text that was saved stays as it is until somebody saves the form again.
        child("Luca", "Rossi")
        assertEquals("Chiedete a Luca Rossi", infoOf(id).first)
        val again = browser.send("/convivenza/$id/informazioni", "/convivenza/$id/informazioni", mapOf("infoText" to "Chiedete a Luca Rossi", "whatToBring" to "Un telo"))
        assertEquals(422, again.status(), "the form is checked whole: the error is under the field that was not changed")
        assertEquals(nameError, errorOf(again.body(), "informazioni"))
        assertNull(errorOf(again.body(), "cosa-portare"))
        assertFalse(text(again.body()).contains("Controlla i"), "one error: no notice above")
        assertEquals("Chiedete a Luca Rossi" to null, infoOf(id), "what to bring was not saved either")

        // A child that is deleted does not count any more.
        dsl.deleteFrom(CHILD).execute()
        assertEquals(303, browser.send("/convivenza/$id/informazioni", "/convivenza/$id/informazioni", mapOf("infoText" to "Chiedete a Luca Rossi", "whatToBring" to "Un telo")).status())
    }

    @Test
    fun `t16 c15 a text with a name and also a phone, an email or an IBAN has one error that lists everything, the name first, and the length is checked before`() {
        val browser = start()
        val id = gatherings.make(status = GatheringStatus.draft)
        val tail = "Toglili: la pagina la legge chiunque abbia il link."

        fun errorOfInfo(text: String): String? =
            errorOf(browser.send("/convivenza/$id/informazioni", "/convivenza/$id/informazioni", mapOf("infoText" to text, "whatToBring" to "")).body(), "informazioni")

        assertEquals("Qui ci sono nome e cognome di una persona e un numero di telefono. $tail", errorOfInfo("Luca Rossi 333 123 4567"))
        assertEquals("Qui ci sono nome e cognome di una persona e un indirizzo email. $tail", errorOfInfo("Luca Rossi nome@example.org"))
        assertEquals("Qui ci sono nome e cognome di una persona, un indirizzo email e un numero di telefono. $tail", errorOfInfo("Luca Rossi nome@example.org 333 123 4567"))
        assertEquals("Qui ci sono nome e cognome di una persona e un IBAN. $tail", errorOfInfo("Luca Rossi IT60X0542811101000000123456"))
        assertEquals("Qui ci sono nome e cognome di una persona, un indirizzo email, un numero di telefono e un IBAN. $tail", errorOfInfo("Luca Rossi nome@example.org 333 123 4567 IT60X0542811101000000123456"))
        // Without the name nothing changes: the sentences of today, in the singular when there is one thing.
        assertEquals("Qui c’è un indirizzo email. Toglilo: la pagina la legge chiunque abbia il link.", errorOfInfo("Scrivi a nome@example.org"))
        assertEquals("Qui ci sono un indirizzo email e un numero di telefono. $tail", errorOfInfo("nome@example.org 333 123 4567"))

        // The length first: a text that is too long says only that, and a title that is empty says only that it is missing.
        val tooLong = errorOfTitle(browser, id, "Luca Rossi " + "x".repeat(100))
        assertTrue(tooLong!!.startsWith("Il titolo può avere al massimo 100 caratteri: ne hai scritti 111."), tooLong)
        assertFalse(tooLong.contains("nome e cognome"))
        assertEquals("Scrivi il titolo.", errorOfTitle(browser, id, "   "))
    }

    private fun errorOfTitle(browser: MockBrowser, id: Long, title: String): String? =
        errorOf(browser.send("/convivenza/$id/date", "/convivenza/$id/date", datesFields(id, title, "Casa per ritiri di esempio")).body(), "titolo")

    @Test
    @CapturesOutput
    fun `t16 c16 the log has neither the text nor a name, also when the check finds one`(output: CapturedOutput) {
        val browser = start()
        val id = gatherings.make(status = GatheringStatus.draft)

        val found = browser.send("/convivenza/$id/informazioni", "/convivenza/$id/informazioni", mapOf("infoText" to "Chiedete a Luca Rossi o a Nicolo Bianchi, 333 123 4567", "whatToBring" to "Marta Bianchi"))
        val clean = browser.send("/convivenza/$id/informazioni", "/convivenza/$id/informazioni", mapOf("infoText" to "Ci si ritrova alle 9.", "whatToBring" to "Un telo"))

        assertEquals(422, found.status())
        assertEquals(303, clean.status())
        for (word in listOf("Luca", "Rossi", "Nicolo", "Bianchi", "Marta", "Chiedete", "333", "telo")) {
            assertFalse(output.all.contains(word), "the log has «$word»: ${output.all}")
        }
    }

    @Test
    fun `t16 c16 the forms stay for the teachers, a parent who sends them by hand gets 403 and nothing is saved`() {
        start()
        val id = gatherings.make(status = GatheringStatus.draft)
        val before = Triple(titleOf(id), placeOf(id), infoOf(id))
        val parent = kit.parent()

        for ((path, fields) in listOf(
            "/convivenza/$id/date" to mapOf("title" to "Luca Rossi", "place" to "Casa"),
            "/convivenza/$id/informazioni" to mapOf("infoText" to "Luca Rossi", "whatToBring" to "Un telo"),
            "/convivenza/nuova" to mapOf("title" to "Luca Rossi"),
        )) {
            val posted = mockMvc.post(path) {
                session = parent.cookie
                with(csrf())
                fields.forEach { (name, value) -> param(name, value) }
            }.andReturn()
            assertEquals(403, posted.status(), "POST $path")
            assertFalse(posted.body().contains("nome e cognome"), "no error with a name for a parent: $path")
        }
        assertEquals(before, Triple(titleOf(id), placeOf(id), infoOf(id)))
        assertEquals(1, kit.count())
    }
}
