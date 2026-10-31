package talita.registration

import org.jooq.DSLContext
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import talita.LockWait
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.body
import talita.gathering.errorOf
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.gathering.title
import talita.gathering.valueOf
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationAttendance
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.REGISTRATION
import talita.ui.Alert
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registration of the children by the parent, in two steps (criteria 1-10, 18, 33, 34, 36): the first step with its blocks, the quota that the server
 * works out, the second step with the custody, what is saved when the person confirms, and what is not when the text changes, the registrations close
 * or the confirmation arrives twice. The parent has entered with the link, like anyone; every name, date and number is synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class EnrollmentTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val gatherings: talita.gathering.Gatherings,
) {

    private val kit = RegistrationTestKit(mockMvc, tokens, dsl, clock)

    /** The visible text of a page, with the spaces that do not break (before the euro sign) as plain ones. */
    private fun text(html: String): String = talita.gathering.text(html).replace('\u00a0', ' ')

    private fun htmx(): org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = { header("HX-Request", "true") }

    private fun blocks(parent: MockBrowser, setup: Setup, fields: Map<String, String>, action: String, fragment: Boolean = false) =
        parent.submit(setup.form, "${setup.form}/figli", fields + ("azione" to action), if (fragment) htmx() else ({}))

    // ---- The first step (criterion 1) -------------------------------------------------------------------------------------------

    @Test
    fun `t5 c01 a parent who opens the registration of a gathering with open registrations sees step 1 with an empty block`() {
        val setup = kit.start()
        val parent = kit.parent()

        val page = parent.get(setup.form)
        val html = page.body()
        val words = text(html)

        assertEquals(200, page.status())
        assertEquals("Iscrizione", heading(html))
        assertEquals("Iscrizione · Talità", title(html))
        assertTrue(words.contains("Passo 1 di 2 · I dati"), words)
        assertTrue(words.contains("Scrivi i dati dei bambini che vuoi iscrivere. Al passo 2 ricontrolli tutto e accetti l’affidamento: finché non accetti, non salviamo niente."))
        assertTrue(words.contains("Telefono per i giorni della convivenza"))
        assertTrue(words.contains("Un numero a cui i maestri possono chiamarti mentre i bambini sono con noi. Lo vedono solo i maestri. Per esempio 333 123 4567."))
        assertTrue(words.contains("Bambino 1"))
        for (label in listOf("Nome", "Cognome", "Data di nascita", "Quando viene?", "Stato dell’iscrizione", "Confermata", "In forse", "Allergie, intolleranze, farmaci", "Aggiungi un figlio")) {
            assertTrue(words.contains(label), label)
        }
        // «Confermata» is chosen already; nothing of the presence is.
        assertTrue(html.contains("""name="children[0].status" value="confirmed" checked"""))
        assertFalse(html.contains("""name="children[0].status" value="maybe" checked"""))
        assertFalse(Regex("""name="children\[0]\.presence" value="[a-z_]+" checked""").containsMatchIn(html), "a presence is chosen before the person chooses")
        // One block only: nothing to take away; the field of the phone is a phone field.
        assertFalse(words.contains("Togli"))
        assertTrue(html.contains("""name="phone" type="tel" autocomplete="tel" """))
        // Without JavaScript the buttons of the blocks send the form by themselves.
        assertTrue(Regex("""<button type="submit" name="azione" value="aggiungi" formaction="${Regex.escape(setup.form)}/figli" formnovalidate""").containsMatchIn(html))
        assertTrue(Regex("""<a href="/info/${setup.code}"[^>]*>[\s\S]*?Annulla</a>""").containsMatchIn(html))
        assertTrue(words.contains("Controlla e accetta"))
    }

    @Test
    fun `t5 c01 without an account the registration leads to the access page`() {
        val setup = kit.start()

        val page = MockBrowser(mockMvc).get(setup.form)

        assertEquals(302, page.status())
        assertEquals("/accedi", page.response.getHeader("Location"))
    }

    @Test
    fun `t5 c01 a draft and a code that does not exist are the same page, and a gathering whose registrations are closed leads to the information page`() {
        val setup = kit.start(status = GatheringStatus.draft)
        val parent = kit.parent()

        val draft = parent.get(setup.form)
        val unknown = parent.get("/info/xxxxxxxxxxxxxxxx/iscrizione")
        assertEquals(404, draft.status())
        assertEquals(404, unknown.status())
        assertEquals(draft.body(), unknown.body())

        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(setup.id)).execute()
        val closed = parent.get(setup.form)
        assertEquals(303, closed.status())
        assertEquals("/info/${setup.code}", closed.location())
    }

    @Test
    fun `t5 c01 the blocks are added and taken away up to ten, with a page without JavaScript and a piece with htmx`() {
        val setup = kit.start()
        val parent = kit.parent()
        var fields = kit.form(kit.child(0, first = "Luca"))

        val two = blocks(parent, setup, fields, "aggiungi")
        assertEquals(200, two.status())
        val twoHtml = two.body()
        assertEquals("Iscrizione", heading(twoHtml), "without JavaScript the answer is the whole page")
        assertTrue(twoHtml.contains("Bambino 2"))
        assertEquals("Luca", valueOf(twoHtml, "b0-nome"), "what was written stays")
        assertTrue(twoHtml.contains("""aria-label="Togli il bambino 2""""))
        assertTrue(Regex("""id="b1-nome"[^>]*data-fuoco""").containsMatchIn(twoHtml), "the new block takes the focus")

        // With htmx only the blocks come, and the total out of band.
        val piece = blocks(parent, setup, fields, "aggiungi", fragment = true).body()
        assertTrue(piece.trimStart().startsWith("""<div id="figli""""), piece.take(120))
        assertFalse(piece.contains("<html"))
        assertTrue(piece.contains("""<div id="quota-totale" hx-swap-oob="true""""))

        // Taking one away goes back to one, and the focus to the block before.
        fields = kit.form(kit.child(0, first = "Luca"), kit.child(1, first = "Anna"))
        val back = blocks(parent, setup, fields, "togli:1").body()
        assertFalse(back.contains("Bambino 2"))
        assertEquals("Luca", valueOf(back, "b0-nome"))
        // The only block cannot be taken away, not even by hand.
        assertTrue(blocks(parent, setup, kit.form(kit.child(0, first = "Luca")), "togli:0").body().contains("Bambino 1"))

        // Up to ten: at the tenth the button is gone and a sentence says why; the eleventh is not made.
        var many = kit.form(*(0 until 9).map { kit.child(it, first = "Bambino$it") }.toTypedArray())
        val ten = blocks(parent, setup, many, "aggiungi").body()
        assertTrue(ten.contains("Bambino 10"))
        assertFalse(ten.contains("Aggiungi un figlio"))
        assertTrue(text(ten).contains("Hai aggiunto 10 bambini, il massimo per volta. Per iscriverne altri, finisci questa iscrizione e poi tocca «Iscrivi un altro figlio»."))
        many = kit.form(*(0 until 10).map { kit.child(it, first = "Bambino$it") }.toTypedArray())
        assertFalse(blocks(parent, setup, many, "aggiungi").body().contains("Bambino 11"))
    }

    @Test
    fun `t5 c01 the page stays at what the fees allow, a fee that is not set gives no choice, and with the full fee alone there is nothing to choose`() {
        val parent = kit.parent()

        val noDaily = kit.start(daily = null)
        val noDailyHtml = parent.get(noDaily.form).body()
        assertTrue(text(noDailyHtml).contains("10 € per ogni solo pranzo."))
        assertFalse(noDailyHtml.contains("Giornata"))
        assertTrue(text(noDailyHtml).contains("Per ogni giorno scegli «Solo pranzo». Se non viene, lascia «Non viene»."))

        val noLunch = kit.start(lunch = null)
        val noLunchHtml = parent.get(noLunch.form).body()
        assertTrue(text(noLunchHtml).contains("25 € per ogni giornata."))
        assertFalse(noLunchHtml.contains("Solo pranzo"))

        val onlyFull = kit.start(daily = null, lunch = null)
        val onlyFullHtml = parent.get(onlyFull.form).body()
        assertFalse(onlyFullHtml.contains("Alcuni giorni"))
        assertTrue(onlyFullHtml.contains("""<input type="hidden" name="children[0].presence" value="all_days""""))
        assertTrue(text(onlyFullHtml).contains("Tutti i giorni"))

        val oneDay = kit.start(days = 1)
        val oneDayHtml = parent.get(oneDay.form).body()
        assertTrue(text(oneDayHtml).contains("Tutto il giorno"))
        assertTrue(text(oneDayHtml).contains("Solo pranzo"))
        assertFalse(oneDayHtml.contains("Alcuni giorni"))
    }

    // ---- «I tuoi figli» (criterion 2) -------------------------------------------------------------------------------------------

    @Test
    fun `t5 c02 a child of a previous gathering is offered again with the name and the date, and nothing else, and correcting it does not make another child`() {
        val setup = kit.start()
        val parent = kit.parent()
        assertEquals(303, kit.register(parent, setup, kit.form(kit.child(first = "Luca", more = arrayOf("allergies" to "Arachidi", "healthConsent" to "true")))).status())
        val next = kit.nextGathering(setup)

        val page = parent.get(next.form).body()
        assertTrue(text(page).contains("I tuoi figli Hai già iscritto questi bambini a una convivenza. Tocca «Aggiungi» per ritrovare i loro dati: potrai correggerli. LR Luca Rossi Data di nascita 4 aprile 2016 Aggiungi"), text(page))
        assertTrue(page.contains("""aria-label="Aggiungi Luca Rossi""""))
        // Its phone is not there, and the other child of another parent is not offered.
        assertEquals("", valueOf(page, "telefono"))
        assertFalse(text(kit.parent("altro").get(next.form).body()).contains("Luca Rossi"))

        val known = dsl.select(CHILD.ID).from(CHILD).fetchSingle(CHILD.ID)!!
        val added = blocks(parent, next, kit.form(kit.child(0, first = "", last = "", day = "", month = "", year = "", presence = "")), "aggiungi:$known")
        val html = added.body()
        assertEquals("Luca", valueOf(html, "b0-nome"))
        assertEquals("Rossi", valueOf(html, "b0-cognome"))
        assertEquals("4", valueOf(html, "b0-giorno"))
        assertEquals("2016", valueOf(html, "b0-anno"))
        assertFalse(html.contains("Bambino 2"), "the child fills the block that was still empty")
        assertFalse(Regex("""name="children\[0]\.presence" value="[a-z_]+" checked""").containsMatchIn(html))
        assertEquals("", valueOf(html, "b0-allergie"))
        assertFalse(text(html).contains("I tuoi figli"), "the row goes away from the list")
        assertTrue(html.contains("""name="children[0].childId" value="$known""""))

        // The date is corrected on the way: it is the same child, with the new date.
        val corrected = kit.child(0, first = "Luca", day = "5", presence = "all_days", more = arrayOf("childId" to known.toString()))
        assertEquals(303, kit.register(parent, next, kit.form(corrected)).status())
        assertEquals(1, kit.childCount())
        assertEquals(LocalDate.of(2016, 4, 5), dsl.select(CHILD.BIRTH_DATE).from(CHILD).fetchSingle(CHILD.BIRTH_DATE))
        assertEquals(1, kit.registrationCount(next.id))
    }

    // ---- The errors (criteria 3 and 18) -----------------------------------------------------------------------------------------

    private fun refused(setup: Setup, parent: MockBrowser, fields: Map<String, String>): String {
        val answer = parent.submit(setup.form, setup.form, fields)
        assertEquals(422, answer.status(), "a wrong form is answered with 422")
        assertEquals(0, kit.registrationCount(setup.id) + kit.childCount(), "nothing is saved")
        return answer.body()
    }

    @Test
    fun `t5 c03 a wrong phone stays at step 1 with the error under the field and what was written`() {
        val setup = kit.start()
        val parent = kit.parent()

        for ((typed, error) in listOf(
            "" to "Scrivi un numero di telefono.",
            "   " to "Scrivi un numero di telefono.",
            "12345" to "Scrivi un numero di telefono completo, per esempio 333 123 4567.",
            "1234567890123456" to "Scrivi un numero di telefono completo, per esempio 333 123 4567.",
            "tre tre tre" to "Scrivi un numero di telefono completo, per esempio 333 123 4567.",
            "333 123 456a" to "Scrivi un numero di telefono completo, per esempio 333 123 4567.",
            "(333) 1234567" to "Scrivi un numero di telefono completo, per esempio 333 123 4567.",
        )) {
            val html = refused(setup, parent, kit.form(kit.child(), phone = typed))
            assertEquals(error, errorOf(html, "telefono"), typed)
            assertEquals(typed.trim().ifEmpty { "" }, valueOf(html, "telefono")?.trim(), typed)
            assertEquals("Iscrizione", heading(html))
            assertTrue(Regex("""id="telefono"[^>]*aria-invalid="true"""").containsMatchIn(html))
        }
        // These are numbers: spaces, dots, dashes, and a plus at the start.
        for (typed in listOf("333 123 4567", "333.123.4567", "333-123-4567", "+39 333 123 4567", "+393331234567", "123456", "123456789012345")) {
            val answer = parent.submit(setup.form, setup.form, kit.form(kit.child(), phone = typed))
            assertEquals(200, answer.status(), typed)
        }
    }

    @Test
    fun `t5 c03 the name, the surname and the date of birth have their errors, one for each field`() {
        val setup = kit.start()
        val parent = kit.parent()
        val today = kit.kit.gatherings.today()

        val empty = refused(setup, parent, kit.form(kit.child(first = "", last = "")))
        assertEquals("Scrivi il nome.", errorOf(empty, "b0-nome"))
        assertEquals("Scrivi il cognome.", errorOf(empty, "b0-cognome"))
        assertTrue(text(empty).contains("Controlla i 2 campi segnati in rosso."))
        assertEquals("Usa al massimo 100 caratteri.", errorOf(refused(setup, parent, kit.form(kit.child(first = "a".repeat(101)))), "b0-nome"))
        assertEquals("Usa al massimo 100 caratteri.", errorOf(refused(setup, parent, kit.form(kit.child(last = "è".repeat(101)))), "b0-cognome"))
        // 100 letters, accents and apostrophes are names.
        for (name in listOf("a".repeat(100), "Nicolò", "D’Angelo", "D'Amico", "Maria Rosa")) {
            val answer = parent.submit(setup.form, setup.form, kit.form(kit.child(first = name)))
            assertEquals(200, answer.status(), name)
        }

        val birth = mapOf(
            Triple("", "4", "2016") to "Scrivi giorno, mese e anno: per esempio 4 4 2016.",
            Triple("4", "", "2016") to "Scrivi giorno, mese e anno: per esempio 4 4 2016.",
            Triple("4", "4", "") to "Scrivi giorno, mese e anno: per esempio 4 4 2016.",
            Triple("31", "2", "2016") to "Scrivi una data vera, per esempio 4 4 2016.",
            Triple("0", "4", "2016") to "Scrivi una data vera, per esempio 4 4 2016.",
            Triple("4", "13", "2016") to "Scrivi una data vera, per esempio 4 4 2016.",
            Triple("4", "4", "16") to "Scrivi una data vera, per esempio 4 4 2016.",
            Triple("a", "4", "2016") to "Scrivi una data vera, per esempio 4 4 2016.",
            Triple(today.plusDays(1).dayOfMonth.toString(), today.plusDays(1).monthValue.toString(), today.plusDays(1).year.toString()) to "La data di nascita non può essere nel futuro.",
        )
        for ((typed, error) in birth) {
            val html = refused(setup, parent, kit.form(kit.child(day = typed.first, month = typed.second, year = typed.third)))
            assertEquals(error, errorOf(html, "b0-nascita"), typed.toString())
            assertEquals(typed.first, valueOf(html, "b0-giorno"))
        }
        // A child who is 18 on the first day is not a child (the day before, still): the gathering is on a Friday, a month away.
        val first = setup.friday
        val eighteen = first.minusYears(18)
        val adult = refused(setup, parent, kit.form(kit.child(day = eighteen.dayOfMonth.toString(), month = eighteen.monthValue.toString(), year = eighteen.year.toString())))
        assertEquals("Controlla la data: il bambino ha già 18 anni il primo giorno della convivenza.", errorOf(adult, "b0-nascita"))
        val almost = eighteen.plusDays(1)
        assertEquals(200, parent.submit(setup.form, setup.form, kit.form(kit.child(day = almost.dayOfMonth.toString(), month = almost.monthValue.toString(), year = almost.year.toString()))).status())
    }

    @Test
    fun `t5 c03 the presence, the status and the text of the notes have their errors, and the same child twice is said`() {
        val setup = kit.start()
        val parent = kit.parent()

        assertEquals("Scegli quando viene.", errorOf(refused(setup, parent, kit.form(kit.child(presence = ""))), "b0-quando"))
        assertEquals("Scegli almeno un giorno.", errorOf(refused(setup, parent, kit.form(kit.child(presence = "days"))), "b0-quando"))
        assertEquals("Questa scelta non è prevista per questa convivenza.", errorOf(refused(setup, parent, kit.form(kit.child(presence = "weekly"))), "b0-quando"))
        assertEquals("Questa scelta non è prevista per questa convivenza.", errorOf(refused(setup, parent, kit.form(kit.child(presence = "days", more = arrayOf("day.fri" to "dinner")))), "b0-quando"))
        assertEquals("Scegli «Confermata» o «In forse».", errorOf(refused(setup, parent, kit.form(kit.child(status = "withdrawn"))), "b0-stato"))

        val tooLong = refused(setup, parent, kit.form(kit.child(more = arrayOf("allergies" to "a".repeat(1001), "healthConsent" to "true"))))
        assertEquals("Usa al massimo 1.000 caratteri: ne hai scritti 1.001.", errorOf(tooLong, "b0-allergie"))
        assertTrue(Regex("""<details[^>]*\sopen""").containsMatchIn(tooLong), "the block with the error is open")
        assertEquals(200, parent.submit(setup.form, setup.form, kit.form(kit.child(more = arrayOf("allergies" to "a".repeat(1000), "healthConsent" to "true")))).status())

        val twice = refused(setup, parent, kit.form(kit.child(0, first = "Luca"), kit.child(1, first = " LUCA ", last = "rossi")))
        assertEquals("Questo bambino c’è già in questo modulo.", errorOf(twice, "b1-nome"))
        assertEquals(null, errorOf(twice, "b0-nome"))
    }

    @Test
    fun `t5 c03 a form with more than ten blocks, made by hand, is refused with a notice`() {
        val setup = kit.start()
        val parent = kit.parent()

        val html = refused(setup, parent, kit.form(*(0 until 11).map { kit.child(it, first = "Bambino$it") }.toTypedArray()))

        assertTrue(text(html).contains("Puoi iscrivere fino a 10 bambini alla volta."))
        assertFalse(html.contains("Bambino 11"))
    }

    @Test
    fun `t5 c18 notes on health need the box of the consent next to them, which is not ticked, and without notes the consent is not needed`() {
        val setup = kit.start()
        val parent = kit.parent()

        val page = parent.get(setup.form).body()
        assertTrue(text(page).contains("Acconsento che i maestri della convivenza leggano queste informazioni sulla salute del bambino."))
        assertTrue(text(page).contains("Sono facoltative. Se non le scrivi, l’iscrizione vale lo stesso, ma i maestri non sapranno di allergie, intolleranze o farmaci. Puoi toglierle quando vuoi dalla pagina «Iscrizioni»."))
        assertFalse(Regex("""name="children\[0]\.healthConsent"[^>]*checked""").containsMatchIn(page))
        assertTrue(Regex("""<details class="group[^"]*"\s*>""").containsMatchIn(page), "closed when there is nothing in it")

        val without = refused(setup, parent, kit.form(kit.child(more = arrayOf("medications" to "Antistaminico in caso di reazione"))))
        assertEquals(
            "Per salvare le segnalazioni serve il tuo consenso: spunta la casella. Se non vuoi darlo, svuota i campi: l’iscrizione vale lo stesso.",
            errorOf(without, "b0-consenso"),
        )
        assertEquals("Antistaminico in caso di reazione", valueOf(without, "b0-farmaci"))
        // The box ticked on an empty note is not a note: the registration is made and there is nothing in the table.
        val empty = kit.register(parent, setup, kit.form(kit.child(more = arrayOf("healthConsent" to "true"))))
        assertEquals(303, empty.status())
        assertEquals(0, kit.healthNoteCount())
    }

    // ---- The quota that the server works out (criteria 4 and 5) ----------------------------------------------------------------

    private fun quotaOf(html: String, index: Int): String? =
        Regex("""<div hx-swap-oob="innerHTML:#quota-$index">(.*?)</div>""").find(html)?.groupValues?.get(1)?.let { text(it) }

    @Test
    fun `t5 c05 the quota of every block is worked out by the server and sent as a piece, with the total out of band`() {
        val setup = kit.start()
        val parent = kit.parent()
        val form = kit.form(
            kit.child(0, presence = "all_days"),
            kit.child(1, first = "Anna", presence = "days", more = arrayOf("day.fri" to "full", "day.sat" to "lunch")),
            kit.child(2, first = "Paolo", presence = ""),
        )

        val answer = parent.submit(setup.form, "${setup.form}/quota", form, htmx())
        val html = answer.body()

        assertEquals(200, answer.status())
        assertEquals("Quota 60 €", quotaOf(html, 0)?.replace(' ', ' '))
        assertEquals("Quota 35 €", quotaOf(html, 1)?.replace(' ', ' '))
        assertEquals("", quotaOf(html, 2), "no choice, no quota: the line is empty and hidden")
        val total = Regex("""<div hx-swap-oob="innerHTML:#quota-totale">([\s\S]*)</div>""").find(html)?.groupValues?.get(1).orEmpty()
        assertTrue(text(total).replace(' ', ' ').contains("Luca Rossi 60 € Anna Rossi 35 € Totale 95 €"), text(total))
        assertTrue(text(total).contains("La quota definitiva la vedi al passo 2."))
        // The notes on health do not take part, and the server never sends them back.
        assertFalse(html.contains("Arachidi"))
    }

    @Test
    fun `t5 c05 the quota line is a live region that the pieces fill, and the request for it carries only the fields of the days`() {
        val setup = kit.start()
        val parent = kit.parent()
        val html = parent.get(setup.form).body()

        assertTrue(html.contains("""<p id="quota-0" aria-live="polite" class="mt-3 flex"""))
        assertTrue(Regex("""<fieldset class="group min-w-0" hx-post="${Regex.escape(setup.form)}/quota" hx-trigger="change" hx-swap="none" hx-params="[^"]+" hx-sync="closest form:replace">""").containsMatchIn(html))
        assertFalse(Regex("""<textarea[^>]*data-quota""").containsMatchIn(html), "the notes on health do not travel at every touch")
        // htmx adds to a POST every field of the form, so the request is closed by name: the presence and the days of the block, and the CSRF code.
        assertEquals(setOf("children[0].presence", "children[0].day.fri", "children[0].day.sat", "children[0].day.sun", "_csrf"), kit.quotaRequestFields(html, 1))
        // With two blocks every block lists the days of both, because the total adds them up, and their names, which are the labels of its lines.
        val two = blocks(parent, setup, kit.form(kit.child(0)), "aggiungi").body()
        assertEquals(
            setOf(
                "children[0].presence", "children[0].day.fri", "children[0].day.sat", "children[0].day.sun", "children[0].firstName", "children[0].lastName",
                "children[1].presence", "children[1].day.fri", "children[1].day.sat", "children[1].day.sun", "children[1].firstName", "children[1].lastName", "_csrf",
            ),
            kit.quotaRequestFields(two, 2),
        )
    }

    /** The first submit button of the form [id] of a page: the one that a press of Enter in a field clicks. */
    private fun firstSubmitButton(html: String, id: String): String {
        val form = checkNotNull(Regex("""<form id="$id"[\s\S]*?</form>""").find(html)) { "no form $id" }.value
        return checkNotNull(Regex("""<button type="submit"[^>]*>""").find(form)) { "no submit button in $id" }.value
    }

    @Test
    fun `t5 c01 c06 the first submit button of each step is the one that goes on, so Enter never adds or takes away a block or goes back`() {
        val setup = kit.start()
        val parent = kit.parent()

        // Step 1 with two blocks («Togli» in each) and with a single block («Aggiungi un figlio»): always the same first button.
        val two = blocks(parent, setup, kit.form(kit.child(0)), "aggiungi").body()
        assertTrue(two.contains("""name="azione" value="togli:0""""), "the page has the buttons that Enter must not reach")
        val one = parent.get(setup.form).body()
        assertTrue(one.contains("""name="azione" value="aggiungi""""))
        for (html in listOf(two, one)) {
            val first = firstSubmitButton(html, "iscrizione")
            assertEquals("""<button type="submit" tabindex="-1" aria-hidden="true" class="sr-only">""", first)
        }
        // Step 2: «Modifica i dati» comes before «Accetta e iscrivi» in the page, and Enter on a box must not go back.
        val step2 = parent.submit(setup.form, setup.form, kit.form(kit.child(0))).body()
        assertTrue(step2.contains("""formaction="${setup.form}/indietro""""), "the page has the button that Enter must not reach")
        assertEquals("""<button type="submit" tabindex="-1" aria-hidden="true" class="sr-only">""", firstSubmitButton(step2, "conferma"))
    }

    @Test
    fun `t5 c05 the quota of a form is zero for a free gathering and a quota that is not offered is refused`() {
        val setup = kit.start(full = 0, daily = 0, lunch = 0)
        val parent = kit.parent()
        val answer = parent.submit(setup.form, "${setup.form}/quota", kit.form(kit.child(presence = "all_days")), htmx())

        assertEquals("Quota Gratuita", quotaOf(answer.body(), 0))
        assertTrue(text(parent.get(setup.form).body()).contains("Gratuita"))
        // A day that the fees do not offer comes back as «not offered» at step 1 (see c04 of the rules for the sums).
        val noLunch = kit.start(lunch = null)
        assertEquals(
            "Questa scelta non è prevista per questa convivenza.",
            errorOf(refusedAt(noLunch, parent, kit.form(kit.child(presence = "days", more = arrayOf("day.fri" to "lunch")))), "b0-quando"),
        )
    }

    private fun refusedAt(setup: Setup, parent: MockBrowser, fields: Map<String, String>): String {
        val answer = parent.submit(setup.form, setup.form, fields)
        assertEquals(422, answer.status())
        return answer.body()
    }

    // ---- The second step (criteria 6 and 33) ------------------------------------------------------------------------------------

    @Test
    fun `t5 c06 step 2 shows the recap, the short notice, the text of the custody and the boxes that are not ticked, and saves nothing`() {
        val setup = kit.start()
        val parent = kit.parent()
        val form = kit.form(
            kit.child(0, presence = "all_days"),
            kit.child(1, first = "Anna", day = "9", month = "1", year = "2019", presence = "days", status = "maybe", more = arrayOf("day.sat" to "full", "day.sun" to "lunch", "allergies" to "Arachidi", "healthConsent" to "true")),
        )

        val answer = parent.submit(setup.form, setup.form, form)
        val html = answer.body()
        val words = text(html).replace(' ', ' ')

        assertEquals(200, answer.status(), "step 2 is the answer to step 1, not a redirect")
        assertEquals("false", answer.response.getHeader("HX-Push-Url"), "and it makes no entry in the history")
        assertEquals("Controlla e accetta", heading(html))
        assertEquals("Controlla e accetta · Talità", title(html))
        assertTrue(words.contains("Passo 2 di 2 · Controlla e accetta"))
        assertTrue(words.contains("il tasto indietro del telefono esce dall’iscrizione e quello che hai scritto si perde. Quando tocchi «Accetta e iscrivi» l’iscrizione è fatta."))
        assertTrue(words.contains("Luca Rossi Confermata Data di nascita 4 aprile 2016 Quando Tutti i giorni (${talita.gathering.GatheringFormat.range(setup.friday, setup.sunday)}) Segnalazioni Nessuna Quota 60 €"), words)
        assertTrue(words.contains("Anna Rossi In forse Data di nascita 9 gennaio 2019 Quando Giornata: sabato ${setup.saturday.dayOfMonth}. Solo pranzo: domenica ${setup.sunday.dayOfMonth}. Segnalazioni Scritte, con il tuo consenso Quota 35 €"), words)
        assertTrue(words.contains("Telefono 333 123 4567 Totale 95 €"))
        assertTrue(words.contains("In Talità non si paga: qui vedi solo l’importo. Per pagare, segui le indicazioni dei maestri."))
        assertFalse(words.contains("Arachidi"), "the recap does not show the notes: it only says whether there are")
        // The text of the custody, with the names of the custodians of now, the children, the place and the days.
        for (part in listOf(
            "Affidamento di figli minorenni", "affido Luca Rossi, nato/a il 4 aprile 2016 e Anna Rossi, nato/a il 9 gennaio 2019", "alla responsabilità di Marta Bianchi e Paolo Verdi",
            "«Convivenza di settembre 2027»", "si svolge a Casa per ritiri di esempio dal ${talita.gathering.GatheringFormat.date(setup.friday)} al ${talita.gathering.GatheringFormat.date(setup.sunday)}",
            "e li autorizzo a prendere, in mia assenza, tutte le decisioni necessarie per il loro bene.",
        )) {
            assertTrue(words.contains(part), part)
        }
        assertTrue(words.contains("Per iscrivere spunta tutte le caselle Ho letto il testo dell’affidamento e lo accetto. Dichiaro di accettare anche con il consenso dell’altro genitore, oppure di esercitare da solo/a la responsabilità genitoriale. Acconsento al trattamento dei miei dati e di quelli dei bambini"))
        for (box in listOf("acceptCustody", "otherParent", "religionConsent")) {
            assertTrue(Regex("""name="$box" value="true"""").containsMatchIn(html))
            assertFalse(Regex("""name="$box" value="true" checked""").containsMatchIn(html), "$box is ticked before the person ticks it")
        }
        assertTrue(Regex("""<button type="submit" id="conferma-invia"""").containsMatchIn(html))
        assertTrue(html.contains("""formaction="${setup.form}/indietro""""))
        assertEquals(0, kit.registrationCount(setup.id) + kit.childCount() + kit.healthNoteCount(), "nothing is saved at step 2")
        assertEquals(64, kit.hashIn(html).length)
    }

    @Test
    fun `t5 c06 «Indietro» goes back to step 1 with everything that was written, and a reload of a step 2 address leads to step 1`() {
        val setup = kit.start()
        val parent = kit.parent()
        val form = kit.form(kit.child(0, first = "Luca", more = arrayOf("allergies" to "Arachidi", "healthConsent" to "true")), kit.child(1, first = "Anna", presence = "days", more = arrayOf("day.sun" to "lunch")))
        val second = parent.submit(setup.form, setup.form, form).body()

        val back = parent.submit(setup.form, "${setup.form}/indietro", kit.hiddenFields(second))
        val html = back.body()

        assertEquals(200, back.status())
        assertEquals("Iscrizione", heading(html))
        assertEquals("333 123 4567", valueOf(html, "telefono"))
        assertEquals("Luca", valueOf(html, "b0-nome"))
        assertEquals("Anna", valueOf(html, "b1-nome"))
        assertEquals("Arachidi", valueOf(html, "b0-allergie"))
        assertTrue(Regex("""name="children\[0]\.healthConsent" value="true" checked""").containsMatchIn(html))
        assertTrue(Regex("""name="children\[1]\.day\.sun" value="lunch" checked""").containsMatchIn(html))
        assertEquals(0, kit.registrationCount(setup.id))

        for (path in listOf("${setup.form}/indietro", "${setup.form}/conferma")) {
            val reloaded = parent.get(path)
            assertEquals(303, reloaded.status(), path)
            assertEquals(setup.form, reloaded.location())
        }
    }

    @Test
    fun `t5 c33 the short notice says who the controller is, why, what is optional, who receives the data, how long, the rights and links to the complete one`() {
        val setup = kit.start()
        val html = kit.parent().submit(setup.form, setup.form, kit.form(kit.child())).body()
        val words = text(html)

        for (part in listOf(
            "Come usiamo i tuoi dati", "Chi li gestisce Il titolare è Titolare di esempio. Per qualsiasi domanda scrivi a nome@example.org.",
            "A cosa servono A iscrivere i bambini alla convivenza e a organizzarla: li usiamo perché ce lo chiedi con l’iscrizione. I dati dell’iscrizione sono necessari: senza non possiamo iscrivere i bambini.",
            "Salute (facoltativa) Allergie, intolleranze e farmaci li salviamo solo se ci dai il consenso.",
            "Chi li riceve I maestri della convivenza.", "Per quanto tempo Le segnalazioni le cancelliamo un mese dopo la fine della convivenza.",
            "I tuoi diritti Puoi vedere, correggere o cancellare i dati e togliere il consenso quando vuoi", "puoi rivolgerti al Garante per la protezione dei dati personali.",
            "Leggi l’informativa completa", "Si apre in una nuova scheda: così non perdi quello che hai scritto.",
        )) {
            assertTrue(words.contains(part), part)
        }
        assertTrue(Regex("""<a href="/informativa" target="_blank" rel="noopener" hx-boost="false"""").containsMatchIn(html))
    }

    // ---- Confirming (criterion 7) -------------------------------------------------------------------------------------------------

    private fun second(parent: MockBrowser, setup: Setup, form: Map<String, String>): String = parent.submit(setup.form, setup.form, form).body()

    /** The confirmation of step 2; with a [csrf] already in hand it does not ask for the page of the form first (which, once closed, has none). */
    private fun confirm(parent: MockBrowser, setup: Setup, html: String, boxes: Map<String, String>, csrf: String? = null) =
        if (csrf == null) parent.submit(setup.form, "${setup.form}/conferma", kit.hiddenFields(html) + ("textHash" to kit.hashIn(html)) + boxes)
        else parent.post("${setup.form}/conferma", kit.hiddenFields(html) + ("textHash" to kit.hashIn(html)) + boxes, csrf = csrf)

    @Test
    fun `t5 c07 without one of the boxes nothing is saved and the error is under the box`() {
        val setup = kit.start()
        val parent = kit.parent()
        val html = second(parent, setup, kit.form(kit.child()))

        val expected = mapOf(
            "accetto" to ("accept" to "Per iscrivere il bambino devi accettare l’affidamento."),
            "altro-genitore" to ("other" to "Serve la dichiarazione sull’altro genitore: spunta la casella."),
            "convinzioni" to ("religion" to "Senza questa casella non possiamo fare l’iscrizione."),
        )
        for ((id, entry) in expected) {
            val boxes = kit.boxes(accept = entry.first != "accept", other = entry.first != "other", religion = entry.first != "religion")
            val answer = confirm(parent, setup, html, boxes)
            assertEquals(422, answer.status(), id)
            assertEquals(entry.second, errorOf(answer.body(), id), id)
            assertEquals("Controlla e accetta", heading(answer.body()))
            assertEquals(0, kit.registrationCount(setup.id) + kit.childCount(), "nothing is saved without $id")
            // The boxes that were ticked stay ticked.
            for (other in expected.keys - id) assertTrue(Regex("""id="$other"[^>]*checked""").containsMatchIn(answer.body()), "$other stays ticked")
        }
        val none = confirm(parent, setup, html, emptyMap())
        assertEquals(422, none.status())
        assertTrue(text(none.body()).contains("Controlla i 3 campi segnati in rosso."))
        // With more than one child the first error says «i bambini».
        val two = second(parent, setup, kit.form(kit.child(0), kit.child(1, first = "Anna")))
        assertEquals("Per iscrivere i bambini devi accettare l’affidamento.", errorOf(confirm(parent, setup, two, emptyMap()).body(), "accetto"))
        assertEquals(0, kit.registrationCount(setup.id))
    }

    @Test
    fun `t5 c07 with all the boxes there is a registration for each child with the exact text, the version, the moment, the account and its address`() {
        val setup = kit.start()
        val email = talita.Addresses.unique("genitore-uno")
        val parent = kit.kit.parent(email)
        val form = kit.form(
            kit.child(0, presence = "all_days"),
            kit.child(1, first = "Anna", day = "9", month = "1", year = "2019", presence = "days", status = "maybe", more = arrayOf("day.fri" to "full", "day.sun" to "lunch", "allergies" to "Arachidi", "healthConsent" to "true")),
            phone = "+39 333.123-4567",
        )
        val html = second(parent, setup, form)
        val before = java.time.OffsetDateTime.now(clock)

        val answer = confirm(parent, setup, html, kit.boxes())

        assertEquals(303, answer.status())
        assertEquals("/iscrizioni", answer.location())
        val flash = (parent.cookie as MockHttpSession).getAttribute("talita.flash") as Alert
        assertEquals("Iscrizioni fatte.", flash.text)
        val rows = kit.registrations(setup.id)
        assertEquals(2, rows.size)
        val (luca, anna) = rows
        assertEquals(kit.accountId(email), luca.accountId)
        assertEquals("+39 333 123 4567", luca.phone, "one phone for the registrations of one submission")
        assertEquals(luca.phone, anna.phone)
        assertEquals(RegistrationAttendance.all_days, luca.attendance)
        assertEquals(RegistrationStatus.confirmed, luca.status)
        assertEquals(RegistrationAttendance.days, anna.attendance)
        assertEquals(RegistrationStatus.maybe, anna.status)
        assertEquals(mapOf(setup.friday to "full", setup.sunday to "lunch"), kit.daysOf(anna.id!!))
        assertEquals(emptyMap(), kit.daysOf(luca.id!!))
        for (row in rows) {
            assertEquals("affidamento-1", row.custodyTextVersion)
            assertEquals(email, row.custodyAcceptedByEmail)
            assertEquals(true, row.custodyReligionConsent)
            assertFalse(row.paperFormReceived!!)
            assertNull(row.enteredByAccountId)
            assertTrue(!row.custodyAcceptedAt!!.isBefore(before.minusSeconds(1)) && !row.custodyAcceptedAt!!.isAfter(before.plus(Duration.ofMinutes(1))))
        }
        // The text kept is the one shown at step 2, with all the names, whole.
        assertEquals(luca.custodyText, anna.custodyText)
        assertEquals(CustodyText.hash(luca.custodyText!!), kit.hashIn(html))
        assertTrue(luca.custodyText!!.startsWith("Affidamento di figli minorenni\n\nIo, titolare dell’account $email, affido Luca Rossi, nato/a il 4 aprile 2016 e Anna Rossi, nato/a il 9 gennaio 2019 alla responsabilità di Marta Bianchi e Paolo Verdi"), luca.custodyText)
        assertTrue(luca.custodyText!!.endsWith("Dichiaro di accettare anche con il consenso dell’altro genitore, oppure di esercitare da solo/a la responsabilità genitoriale."))
        assertEquals(2, kit.childCount())
        assertEquals(1, kit.healthNoteCount())
    }

    @Test
    fun `t5 c07 a single registration is confirmed with Iscrizione fatta`() {
        val setup = kit.start()
        val parent = kit.parent()

        assertEquals(303, kit.register(parent, setup, kit.form(kit.child())).status())

        assertEquals("Iscrizione fatta.", ((parent.cookie as MockHttpSession).getAttribute("talita.flash") as Alert).text)
    }

    // ---- The text changes meanwhile (criterion 8) --------------------------------------------------------------------------------

    @Test
    fun `t5 c08 if the custodians, the title, the dates or the place change before the confirmation nothing is saved and step 2 comes back with the new text`() {
        val setup = kit.start()
        val parent = kit.parent()
        val form = kit.form(kit.child())

        // The custodians change.
        var html = second(parent, setup, form)
        dsl.deleteFrom(GATHERING_CUSTODIAN).where(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID.eq(setup.paoloId)).execute()
        var answer = confirm(parent, setup, html, kit.boxes())
        assertEquals(409, answer.status())
        var words = text(answer.body())
        assertTrue(words.contains("Mentre leggevi, i maestri hanno cambiato il testo dell’affidamento (maestri affidatari, date o luogo). Rileggilo con attenzione: non abbiamo salvato niente e le caselle sono da spuntare di nuovo."), words)
        assertTrue(words.contains("alla responsabilità di Marta Bianchi per la durata"), "the new text has only one custodian")
        assertTrue(words.contains("e lo/la autorizzo"))
        assertFalse(words.contains("Paolo Verdi"))
        assertEquals(0, kit.registrationCount(setup.id) + kit.childCount())
        for (box in listOf("acceptCustody", "otherParent", "religionConsent")) assertFalse(Regex("""name="$box" value="true" checked""").containsMatchIn(answer.body()))
        // Reading the new text and ticking again, it goes through.
        assertEquals(303, confirm(parent, setup, answer.body(), kit.boxes()).status())
        assertEquals(1, kit.registrationCount(setup.id))

        // The title, the dates and the place, one at a time, each with a new parent.
        val changes = listOf(
            "title" to { dsl.update(GATHERING).set(GATHERING.TITLE, "Un altro titolo").where(GATHERING.ID.eq(setup.id)).execute() },
            "end" to { dsl.update(GATHERING).set(GATHERING.END_DATE, setup.sunday.minusDays(1)).where(GATHERING.ID.eq(setup.id)).execute() },
            "place" to { dsl.update(GATHERING).set(GATHERING.PLACE, "Un’altra casa").where(GATHERING.ID.eq(setup.id)).execute() },
        )
        for ((name, change) in changes) {
            val other = kit.parent("altro-$name")
            html = second(other, setup, kit.form(kit.child(first = "Paolo-$name")))
            change()
            answer = confirm(other, setup, html, kit.boxes())
            assertEquals(409, answer.status(), name)
            assertTrue(text(answer.body()).contains("Mentre leggevi, i maestri hanno cambiato il testo"), name)
        }
        assertEquals(1, kit.registrationCount(setup.id), "nothing else was saved")
    }

    // ---- Closed meanwhile (criterion 9) ------------------------------------------------------------------------------------------

    @Test
    fun `t5 c09 if the registrations close, or the gathering ends, before the confirmation nothing is saved and the page says to ask the teachers`() {
        val setup = kit.start()
        val parent = kit.parent()
        val html = second(parent, setup, kit.form(kit.child()))
        val csrf = MockBrowser.csrfIn(parent.get(setup.form).body())

        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(setup.id)).execute()
        var answer = confirm(parent, setup, html, kit.boxes(), csrf)
        assertEquals(409, answer.status())
        assertEquals("Le iscrizioni sono chiuse", heading(answer.body()))
        assertTrue(text(answer.body()).contains("Mentre compilavi, le iscrizioni si sono chiuse: non abbiamo salvato niente. Chiedi ai maestri se puoi ancora iscrivere i tuoi figli."))
        assertTrue(Regex("""<a href="/info/${setup.code}"[^>]*>Torna alle informazioni</a>""").containsMatchIn(answer.body()))
        assertEquals(0, kit.registrationCount(setup.id) + kit.childCount())

        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).set(GATHERING.START_DATE, kit.kit.gatherings.today().minusDays(5)).set(GATHERING.END_DATE, kit.kit.gatherings.today().minusDays(3))
            .where(GATHERING.ID.eq(setup.id)).execute()
        answer = confirm(parent, setup, html, kit.boxes(), csrf)
        assertEquals(409, answer.status())
        assertEquals("La convivenza è finita", heading(answer.body()))
        assertTrue(text(answer.body()).contains("La convivenza è finita: non abbiamo salvato niente. Per la prossima arriverà un link nuovo."))
        // The same for the first step: it finds the registrations closed too.
        assertEquals(409, parent.post(setup.form, kit.form(kit.child()), csrf = csrf).status())
        assertEquals(0, kit.registrationCount(setup.id) + kit.childCount())
    }

    @Test
    fun `t5 c09 a confirmation that waits for the closing finds the registrations closed, and the closing waits for a confirmation that is being made`() {
        val setup = kit.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            // 1. The closing is in the middle (it holds the row of the gathering for update): the confirmation waits, and finds it closed.
            val parent = kit.parent()
            val html = second(parent, setup, kit.form(kit.child()))
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closing = pool.submit {
                dsl.transaction { configuration ->
                    val tx = org.jooq.impl.DSL.using(configuration)
                    tx.selectFrom(GATHERING).where(GATHERING.ID.eq(setup.id)).forUpdate().fetch()
                    holding.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    tx.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(setup.id)).execute()
                }
            }
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            val confirming = LockWait.awaitBlocked(dsl, "the confirmation should wait for the closing that is in progress") { pool.submit<Int> { confirm(parent, setup, html, kit.boxes()).status() } }
            release.countDown()
            closing.get(20, TimeUnit.SECONDS)
            assertEquals(409, confirming.get(20, TimeUnit.SECONDS))
            assertEquals(0, kit.registrationCount(setup.id))

            // 2. A confirmation is in the middle (it holds the row of the gathering for sharing): the closing waits for it and comes after.
            dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).where(GATHERING.ID.eq(setup.id)).execute()
            val sharing = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val registering = pool.submit {
                dsl.transaction { configuration ->
                    val tx = org.jooq.impl.DSL.using(configuration)
                    tx.selectFrom(GATHERING).where(GATHERING.ID.eq(setup.id)).forShare().fetch()
                    sharing.countDown()
                    finish.await(20, TimeUnit.SECONDS)
                }
            }
            assertTrue(sharing.await(10, TimeUnit.SECONDS))
            val closes = LockWait.awaitBlocked(dsl, "the closing should wait for the registration that is being made") { pool.submit<Any> { gatherings.change(setup.id, talita.gathering.StateAction.CLOSE) } }
            finish.countDown()
            registering.get(20, TimeUnit.SECONDS)
            assertTrue(closes.get(20, TimeUnit.SECONDS) is talita.gathering.TransitionResult.Done)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- The same confirmation twice (criterion 10) --------------------------------------------------------------------------------

    @Test
    fun `t5 c10 a confirmation that arrives twice makes one registration for each child and the second answer shows the registrations`() {
        val setup = kit.start()
        val parent = kit.parent()
        val html = second(parent, setup, kit.form(kit.child(0), kit.child(1, first = "Anna")))

        val first = confirm(parent, setup, html, kit.boxes())
        val again = confirm(parent, setup, html, kit.boxes())

        assertEquals(303, first.status())
        assertEquals(303, again.status())
        assertEquals("/iscrizioni", again.location())
        assertEquals(2, kit.registrationCount(setup.id))
        assertEquals(2, kit.childCount())
        assertEquals("Iscrizioni fatte.", ((parent.cookie as MockHttpSession).getAttribute("talita.flash") as Alert).text)
    }

    @Test
    fun `t5 c10 two confirmations at the same moment make one registration for each child`() {
        val setup = kit.start()
        val parent = kit.parent()
        val html = second(parent, setup, kit.form(kit.child()))
        val fields = kit.hiddenFields(html) + ("textHash" to kit.hashIn(html)) + kit.boxes()
        // Two taps: the same session, the same code of the form, the same moment.
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val taps = (1..2).map {
                pool.submit<Int> {
                    start.await()
                    parent.post("${setup.form}/conferma", fields, csrf = MockBrowser.csrfIn(parent.get(setup.form).body())).status()
                }
            }
            start.countDown()
            val statuses = taps.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(listOf(303, 303), statuses)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, kit.registrationCount(setup.id))
        assertEquals(1, kit.childCount())
    }

    @Test
    fun `t5 c10 a child written again with other capitals, accents and spaces at the sides is the same child, and one already registered is said`() {
        val setup = kit.start()
        val parent = kit.parent()
        assertEquals(303, kit.register(parent, setup, kit.form(kit.child(first = "Nicolò", last = "Rossi"))).status())
        val next = kit.nextGathering(setup)

        assertEquals(303, kit.register(parent, next, kit.form(kit.child(first = "  NICOLO ", last = "rossi "))).status())
        assertEquals(1, kit.childCount(), "it is the same child")
        assertEquals(1, kit.registrationCount(next.id))

        // At the same gathering it is already registered: the page says so, with a link to «Iscrizioni».
        val taken = parent.submit(next.form, next.form, kit.form(kit.child(first = "nicolo", last = "ROSSI")))
        assertEquals(422, taken.status())
        assertEquals("nicolo ROSSI è già iscritto a questa convivenza: puoi cambiarne l’iscrizione da «Iscrizioni».", errorOf(taken.body(), "b0-nome"))
        assertTrue(Regex("""<a href="/iscrizioni"[^>]*>«Iscrizioni»</a>\.""").containsMatchIn(taken.body()))
        assertEquals(1, kit.registrationCount(next.id))
    }

    // ---- The complete notice (criterion 34) --------------------------------------------------------------------------------------

    @Test
    fun `t5 c34 the complete notice is public, in the public shell, with no script or cookie, not indexed, and has nothing personal but the controller`() {
        val answer = MockBrowser(mockMvc).get("/informativa")
        val html = answer.body()

        assertEquals(200, answer.status())
        assertEquals("noindex, nofollow", answer.response.getHeader("X-Robots-Tag"))
        assertTrue(html.contains("""<meta name="robots" content="noindex, nofollow">"""))
        assertTrue(answer.response.getHeader("Cache-Control").orEmpty().contains("no-store"))
        assertNull(answer.response.getHeader("Set-Cookie"), "the page starts no session")
        assertNull(answer.request.getSession(false))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("_csrf"))
        assertEquals("Informativa sulla privacy", heading(html))
        assertEquals("Informativa sulla privacy · Talità", title(html))
        val words = text(html)
        assertTrue(words.contains("Come Talità usa i dati di chi iscrive i bambini a una convivenza."))
        val headings = Regex("""<h2[^>]*>(.*?)</h2>""").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "Chi è il titolare", "Quali dati raccogliamo", "A cosa servono e perché possiamo usarli", "Cosa è obbligatorio e cosa no", "Chi vede i dati",
                "Chi li riceve per far funzionare Talità", "Per quanto tempo", "I tuoi diritti", "Reclamo", "L’accettazione dell’affidamento",
            ),
            headings,
        )
        assertTrue(words.contains("Il titolare del trattamento è Titolare di esempio: decide perché e come si usano i dati e ne risponde. Puoi scrivergli a nome@example.org."))
        assertTrue(words.contains("Non raccogliamo mai il sesso, il luogo di nascita, documenti o firme."))
        assertTrue(words.contains(Informativa.PLACEHOLDER_NOTICE))
        assertTrue(Regex("""<a href="/"[^>]*>Torna a Talità</a>""").containsMatchIn(html))
        // The only address in the page is the placeholder of the controller.
        assertEquals(setOf("nome@example.org"), Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").findAll(html).map { it.value }.toSet())
    }

    // ---- What is not written in the log, and what is shown as text (criterion 36) --------------------------------------------------

    @Test
    fun `t5 c36 the log has no name, date of birth, phone, note or address, and a text with markup is shown as text`(output: CapturedOutput) {
        val setup = kit.start()
        val email = talita.Addresses.unique("diario-genitore")
        val parent = kit.kit.parent(email)
        val markup = "<b>Luca</b>"
        val form = kit.form(
            kit.child(first = markup, last = "Rossi", more = arrayOf("allergies" to "<script>alert(1)</script> Arachidi", "healthConsent" to "true")),
            phone = "333 123 4567",
        )

        val html = second(parent, setup, form)
        assertTrue(html.contains("&lt;b&gt;Luca&lt;/b&gt;"), "the name is text")
        assertFalse(html.contains("<b>Luca</b>"))
        assertFalse(html.contains("<script>alert"))
        assertEquals(303, confirm(parent, setup, html, kit.boxes()).status())
        // Errors too: a wrong form, a form with a box missing, a form at a gathering that is closed.
        parent.submit(setup.form, setup.form, kit.form(kit.child(first = "", last = "Rossi", more = arrayOf("allergies" to "Arachidi")), phone = "333 123 4567"))

        val log = output.all
        for (secret in listOf("Luca", "Rossi", "333 123 4567", "3331234567", "Arachidi", "alert(1)", "2016", "aprile", email)) {
            assertFalse(log.contains(secret), "the log has '$secret': ${log.lines().filter { it.contains(secret) }.take(3)}")
        }
        assertTrue(log.contains("Registrations made: gathering ${setup.id}, 1 of them."), "the log says only what was made")
        val stored = dsl.select(REGISTRATION.CUSTODY_TEXT).from(REGISTRATION).fetchSingle(REGISTRATION.CUSTODY_TEXT)!!
        assertTrue(stored.contains("<b>Luca</b>"), "what is kept is what the person wrote")
        assertNotNull(dsl.select(HEALTH_NOTE.ALLERGIES).from(HEALTH_NOTE).fetchSingle(HEALTH_NOTE.ALLERGIES))
    }

    // ---- The texts of the development must not be released (-Prelease) -------------------------------------------------------------

    @Test
    fun `t5 release the texts of the custody and of the notices have no placeholder that nobody filled`() {
        assumeTrue(System.getProperty("talita.release") == "true", "only with -Prelease: the texts are still waiting for the person who reads them")

        val pages = listOf("/informativa").associateWith { mockMvc.get(it).andReturn().response.contentAsString }
        val found = Informativa.markers.flatMap { marker ->
            buildList {
                if (CustodyText.model.contains(marker)) add("the model of the custody has $marker")
                pages.forEach { (path, html) -> if (html.contains(marker)) add("$path has $marker") }
            }
        }

        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `t5 release the markers are the ones that the texts of the development carry, and the model of the custody has none`() {
        assertTrue(Informativa.markers.contains(Informativa.PLACEHOLDER_NOTICE))
        // The notice of the placeholder is in the page today: the release test above would fail on it, and that is what it is for.
        val page = mockMvc.get("/informativa").andReturn().response.contentAsString
        assertTrue(Informativa.markers.any { page.contains(it) })
        assertFalse(Informativa.markers.any { CustodyText.model.contains(it) })
        assertTrue(CustodyText.model.lines().none { it.trimStart().startsWith("[") }, "no line of the model is a bracketed note")
    }
}
