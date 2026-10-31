package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import talita.teacher.Person
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Convivenza» for the teachers, as the server renders it (criteria 1-8, 15-17, 19, 20, 41): the section, «Nuova convivenza», the detail and
 * the forms of its parts. The teacher has entered with the link, like anyone; a parent is tried on every address.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class GatheringPagesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings

    /** A post made by hand, with the CSRF code of the session (the profile has a form with one). */
    private fun MockBrowser.send(action: String, fields: Map<String, String>): org.springframework.test.web.servlet.MvcResult = submit("/profilo", action, fields)

    private fun newGathering(browser: MockBrowser, title: String, start: LocalDate, end: LocalDate) =
        browser.send("/convivenza/nuova", mapOf("title" to title, "startDate" to start.toString(), "endDate" to end.toString()))

    // ---- The section (criteria 1, 15, 16, 41) --------------------------------------------------------------------------------

    @Test
    fun `t4 c01 a teacher with no gathering sees the empty state with the button to create it`() {
        val (browser, _) = kit.start()

        val page = browser.get("/convivenza")
        val html = page.body()

        assertEquals(200, page.status())
        assertEquals("Convivenza", heading(html))
        assertEquals("Convivenza · Talità", title(html))
        assertTrue(html.contains(">Nessuna convivenza in programma</h2>"))
        assertTrue(html.contains("Quando la crei, la trovi qui con le date, i maestri e il link per i genitori."))
        assertTrue(Regex("""<a href="/convivenza/nuova"[^>]*>[\s\S]*?Crea la convivenza</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="/convivenza" aria-current="page"""").containsMatchIn(html), "Convivenza is the current entry")
        assertFalse(html.contains("Convivenza corrente"))
        assertFalse(html.contains("Convivenze chiuse"))
    }

    @Test
    fun `t4 c41 the way to Maestri is in the section in every state, also when it is empty`() {
        val (browser, _) = kit.start()
        val link = Regex("""<a href="/maestri"[^>]*>[\s\S]*?Maestri[\s\S]*?Chi organizza la convivenza e chi puoi invitare\.""")

        assertTrue(link.containsMatchIn(browser.get("/convivenza").body()), "empty")
        gatherings.make(status = GatheringStatus.draft)
        assertTrue(link.containsMatchIn(browser.get("/convivenza").body()), "a draft")
        gatherings.reset()
        gatherings.make(status = GatheringStatus.`open`)
        assertTrue(link.containsMatchIn(browser.get("/convivenza").body()), "open")
        gatherings.reset()
        gatherings.make(status = GatheringStatus.closed)
        assertTrue(link.containsMatchIn(browser.get("/convivenza").body()), "only closed ones")
        assertTrue(Regex("""<h2 id="maestri"[^>]*>Maestri</h2>""").containsMatchIn(browser.get("/convivenza").body()))
    }

    @Test
    fun `t4 c15 with a draft or an open gathering the section shows it as the current one and there is no button to create another`() {
        val (browser, _) = kit.start()
        gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.draft)

        val draft = browser.get("/convivenza").body()
        assertTrue(draft.contains(">Convivenza corrente</h2>"))
        assertTrue(draft.contains("Convivenza di settembre 2027"))
        assertTrue(Regex(""">Bozza</span>""").containsMatchIn(draft))
        assertFalse(draft.contains("Crea la convivenza"))
        assertFalse(draft.contains("/convivenza/nuova"))

        gatherings.reset()
        gatherings.make(status = GatheringStatus.`open`)
        val open = browser.get("/convivenza").body()
        assertTrue(open.contains(">Convivenza corrente</h2>"))
        assertTrue(open.contains(">Iscrizioni aperte</span>"))
        assertFalse(open.contains("Crea la convivenza"))
    }

    @Test
    fun `t4 c15 a draft or open gathering that is over is still the current one, with the label Finita when it is not a draft`() {
        val (browser, _) = kit.start()
        val over = gatherings.today().minusDays(10)
        gatherings.make(start = over.minusDays(2), end = over, status = GatheringStatus.`open`)

        val html = browser.get("/convivenza").body()

        assertTrue(html.contains(">Convivenza corrente</h2>"))
        assertTrue(html.contains(">Iscrizioni aperte</span>"))
        assertTrue(html.contains(">Finita</span>"))
        assertFalse(html.contains("Crea la convivenza"))
    }

    @Test
    fun `t4 c16 with only closed gatherings the section has the button to create one above the list, the most recent first, and each one leads to its detail`() {
        val (browser, _) = kit.start()
        val older = gatherings.make(title = "Convivenza di settembre 2025", start = gatherings.today().minusDays(800), end = gatherings.today().minusDays(798), status = GatheringStatus.closed)
        val newer = gatherings.make(title = "Convivenza di settembre 2026", start = gatherings.today().minusDays(400), end = gatherings.today().minusDays(398), status = GatheringStatus.closed)

        val html = browser.get("/convivenza").body()

        assertFalse(html.contains("Convivenza corrente"))
        assertTrue(html.contains(">Convivenze chiuse</h2>"))
        assertTrue(html.indexOf("Crea la convivenza") in 1 until html.indexOf("Convivenze chiuse"), "the button is above the list")
        assertTrue(html.indexOf("Convivenza di settembre 2026") < html.indexOf("Convivenza di settembre 2025"), "the most recent first")
        assertTrue(html.contains("""<a href="/convivenza/$newer""""))
        assertTrue(html.contains("""<a href="/convivenza/$older""""))
        // Both are closed and over: «Iscrizioni chiuse» and «Finita».
        assertEquals(2, Regex(""">Iscrizioni chiuse</span>""").findAll(html).count())
        assertEquals(2, Regex(""">Finita</span>""").findAll(html).count())
    }

    @Test
    fun `t4 c16 the section has the current one, the button is not there, and the closed ones come after it`() {
        val (browser, _) = kit.start()
        gatherings.make(title = "Convivenza di settembre 2026", start = gatherings.today().minusDays(400), end = gatherings.today().minusDays(398), status = GatheringStatus.closed)
        gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.draft)

        val html = browser.get("/convivenza").body()

        assertTrue(html.indexOf("Convivenza corrente") < html.indexOf("Convivenze chiuse"))
        assertFalse(html.contains("Crea la convivenza"))
    }

    // ---- Creating (criteria 2, 3, 15, 22) ---------------------------------------------------------------------------------------

    @Test
    fun `t4 c02 a gathering made with a title and valid dates is saved as a draft with its own code, and the detail says what is missing as not provided`() {
        val (browser, _) = kit.start()
        val start = gatherings.today().plusDays(40)

        val created = newGathering(browser, "  Convivenza di settembre 2027 ", start, start.plusDays(2))

        assertEquals(303, created.status())
        val row = dsl.selectFrom(GATHERING).fetchSingle()
        assertEquals("/convivenza/${row.id}", created.location())
        assertEquals(GatheringStatus.draft, row.status)
        assertEquals("Convivenza di settembre 2027", row.title, "the spaces at the sides are not saved")
        assertEquals(start, row.startDate)
        assertEquals(start.plusDays(2), row.endDate)
        assertTrue(Regex("^[A-Za-z0-9_-]{16}$").matches(row.publicCode!!))
        assertNull(row.place)
        assertNull(row.fullFeeCents)

        val detail = browser.get(created.location()!!)
        val html = detail.body()
        assertEquals(200, detail.status())
        assertEquals("Convivenza di settembre 2027", heading(html))
        assertEquals("Convivenza di settembre 2027 · Talità", title(html))
        assertTrue(text(html).contains("Convivenza salvata."), "the confirmation")
        assertTrue(Regex("""role="status" tabindex="-1" data-fuoco""").containsMatchIn(html), "a notice that follows an action takes the focus")
        assertTrue(text(html).contains("Dove Non indicato"))
        assertEquals(3, Regex("""Non prevista""").findAll(html).count())
        assertTrue(text(html).contains("Nessun maestro scelto"))
        assertTrue(text(html).contains("Informazioni Non indicate"))
        assertTrue(text(html).contains("Cosa portare Non indicato"))
        // The notice shows once.
        assertFalse(browser.get(created.location()!!).body().contains("Convivenza salvata."))
    }

    @Test
    fun `t4 c22 the code never changes when the gathering is edited`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft)
        val before = kit.codeOf(id)

        browser.send("/convivenza/$id/date", mapOf("title" to "Un altro titolo", "startDate" to gatherings.today().plusDays(50).toString(), "endDate" to gatherings.today().plusDays(52).toString(), "place" to "Un altro luogo"))
        browser.send("/convivenza/$id/quote", mapOf("fullFee" to "70"))
        browser.send("/convivenza/$id/informazioni", mapOf("infoText" to "Un testo"))

        assertEquals(before, kit.codeOf(id))
        assertEquals("Un altro titolo", dsl.select(GATHERING.TITLE).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.TITLE))
    }

    @Test
    fun `t4 c03 a form with an empty title, a title of only spaces or one that is too long saves nothing and says so under the field`() {
        val (browser, _) = kit.start()
        val start = gatherings.today().plusDays(40)

        for ((typed, error) in listOf(
            "" to "Scrivi il titolo.",
            "   " to "Scrivi il titolo.",
            "a".repeat(101) to "Il titolo può avere al massimo 100 caratteri: ne hai scritti 101.",
        )) {
            val result = newGathering(browser, typed, start, start.plusDays(2))
            val html = result.body()

            assertEquals(422, result.status(), "«$typed»")
            assertEquals(error, errorOf(html, "titolo"), "«$typed»")
            assertTrue(Regex("""<input id="titolo"[^>]*aria-describedby="titolo-aiuto titolo-errore"[^>]*aria-invalid="true"""").containsMatchIn(html))
            // A single error: it is under the field, and there is no notice above the form.
            assertFalse(html.contains("campi segnati in rosso"))
            assertEquals(start.toString(), valueOf(html, "primo-giorno"), "the dates are still there")
            assertEquals(0, kit.count(), "nothing saved")
        }
    }

    @Test
    fun `t4 c03 a form with dates that are missing, backwards, too long or in the past says so under each, and the form comes back filled in`() {
        val (browser, _) = kit.start()
        val today = gatherings.today()

        fun post(title: String, start: String, end: String) =
            browser.send("/convivenza/nuova", mapOf("title" to title, "startDate" to start, "endDate" to end))

        var result = post("Titolo", "", today.plusDays(10).toString())
        assertEquals("Scegli il primo giorno.", errorOf(result.body(), "primo-giorno"))
        result = post("Titolo", today.plusDays(10).toString(), "")
        assertEquals("Scegli l’ultimo giorno.", errorOf(result.body(), "ultimo-giorno"))
        result = post("Titolo", today.plusDays(12).toString(), today.plusDays(10).toString())
        assertEquals("L’ultimo giorno non può essere prima del primo.", errorOf(result.body(), "ultimo-giorno"))
        result = post("Titolo", today.plusDays(10).toString(), today.plusDays(17).toString())
        assertEquals("La convivenza può durare al massimo 7 giorni: controlla le date.", errorOf(result.body(), "ultimo-giorno"))
        result = post("Titolo", today.minusDays(3).toString(), today.plusDays(1).toString())
        assertEquals("Il primo giorno è già passato. Scegli una data da oggi in poi.", errorOf(result.body(), "primo-giorno"))
        result = post("Titolo", today.minusDays(5).toString(), today.minusDays(3).toString())
        assertEquals("L’ultimo giorno è già passato. Scegli una data da oggi in poi.", errorOf(result.body(), "ultimo-giorno"))
        result = post("Titolo", "17/09/2027", today.plusDays(10).toString())
        assertEquals("Scegli una data vera, per esempio 17/09/2027.", errorOf(result.body(), "primo-giorno"))
        assertEquals(422, result.status())
        assertEquals("17/09/2027", valueOf(result.body(), "primo-giorno"), "what was typed comes back")
        assertEquals(0, kit.count())
    }

    @Test
    fun `t4 c03 with more than one wrong field a notice above the form counts them, and the focus has a place to go`() {
        val (browser, _) = kit.start()

        val result = browser.send("/convivenza/nuova", mapOf("title" to "", "startDate" to "", "endDate" to ""))
        val html = result.body()

        assertEquals(422, result.status())
        assertTrue(Regex("""role="alert" tabindex="-1" data-fuoco[^>]*>[\s\S]*?Controlla i 3 campi segnati in rosso\.""").containsMatchIn(html), "the notice with the count")
        assertEquals(3, Regex("""aria-invalid="true"""").findAll(html).count())
        assertEquals("Scrivi il titolo.", errorOf(html, "titolo"))
    }

    @Test
    fun `t4 c15 a gathering cannot be made while one is a draft or open, also by a request made by hand, and the page says which one`() {
        val (browser, _) = kit.start()
        val draft = gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.draft)

        val page = browser.get("/convivenza/nuova")
        assertEquals(200, page.status())
        assertTrue(text(page.body()).contains("C’è già una convivenza in bozza: «Convivenza di settembre 2027». Puoi modificarla invece di crearne un’altra."))
        assertTrue(Regex("""<a href="/convivenza/$draft"[^>]*>Vai alla convivenza</a>""").containsMatchIn(page.body()))
        assertFalse(page.body().contains("""name="title""""), "no form")

        val start = gatherings.today().plusDays(60)
        val byHand = newGathering(browser, "Un'altra convivenza", start, start.plusDays(1))
        assertEquals(409, byHand.status())
        assertTrue(text(byHand.body()).contains("C’è già una convivenza in bozza"))
        assertEquals(1, kit.count())

        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).where(GATHERING.ID.eq(draft)).execute()
        val open = browser.get("/convivenza/nuova")
        assertTrue(text(open.body()).contains("C’è già una convivenza con le iscrizioni aperte: «Convivenza di settembre 2027». Chiudi le iscrizioni prima di crearne un’altra."))
        assertEquals(409, newGathering(browser, "Un'altra convivenza", start, start.plusDays(1)).status())
        assertEquals(1, kit.count())
    }

    @Test
    fun `t4 c16 once the gathering is closed a new one can be made, and the old one stays`() {
        val (browser, _) = kit.start()
        val closed = gatherings.make(title = "Convivenza di settembre 2026", status = GatheringStatus.closed)
        val start = gatherings.today().plusDays(60)

        assertTrue(browser.get("/convivenza/nuova").body().contains("""name="title""""))
        val result = newGathering(browser, "Convivenza di settembre 2027", start, start.plusDays(2))

        assertEquals(303, result.status())
        assertEquals(2, kit.count())
        assertEquals(GatheringStatus.closed, gatherings.statusOf(closed))
        assertTrue(browser.get("/convivenza/$closed").status() == 200, "the closed one can still be read")
    }

    // ---- The detail: the days, the fees, the texts (criteria 4, 5, 7, 20) --------------------------------------------------------

    @Test
    fun `t4 c04 the detail says the days with the day of the week, with no field to choose them, and two days when the end is the 18th`() {
        val (browser, _) = kit.start()
        val three = gatherings.make(start = LocalDate.of(2027, 9, 17), end = LocalDate.of(2027, 9, 19), status = GatheringStatus.draft)

        val html = browser.get("/convivenza/$three").body()

        assertTrue(text(html).contains("Quando Venerdì 17, sabato 18 e domenica 19 settembre 2027"), text(html))
        for (form in listOf("date", "quote", "informazioni", "affidatari")) {
            val page = browser.get("/convivenza/$three/$form").body()
            assertFalse(page.contains("""name="days""""), "$form has a field for the days")
        }
        assertFalse(browser.get("/convivenza/$three/date").body().contains("""type="checkbox""""), "the form of the dates has no boxes for the days")
        assertTrue(browser.get("/convivenza/$three/date").body().contains("I giorni di presenza sono tutti quelli dal primo all’ultimo, al massimo 7."))

        dsl.update(GATHERING).set(GATHERING.END_DATE, LocalDate.of(2027, 9, 18)).where(GATHERING.ID.eq(three)).execute()
        assertTrue(text(browser.get("/convivenza/$three").body()).contains("Quando Venerdì 17 e sabato 18 settembre 2027"))
    }

    @Test
    fun `t4 c05 the information and the list to bring are saved as written, with the line breaks and no spaces at the sides, and read back from the form`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft)

        val saved = browser.send("/convivenza/$id/informazioni", mapOf("infoText" to "  Primo paragrafo.\r\n\r\n\r\nSecondo paragrafo,\r\ncon due righe.  ", "whatToBring" to "\nSacco a pelo\r\n  Torcia  \r\n\r\nBorraccia\n"))

        assertEquals(303, saved.status())
        assertEquals("/convivenza/$id", saved.location())
        val form = browser.get("/convivenza/$id/informazioni").body()
        assertEquals("Primo paragrafo.\n\n\nSecondo paragrafo,\ncon due righe.", valueOf(form, "informazioni"))
        assertEquals("Sacco a pelo\n  Torcia  \n\nBorraccia", valueOf(form, "cosa-portare").also { assertNotNull(it) })
        val detail = browser.get("/convivenza/$id").body()
        assertTrue(text(detail).contains("Informazioni salvate."))
        // Paragraphs are separated by the blank lines (several are one break), a single line break stays inside the paragraph.
        assertEquals(2, Regex("""<p class="font-normal whitespace-pre-line wrap-anywhere">""").findAll(detail).count())
        assertTrue(detail.contains("Secondo paragrafo,\ncon due righe."))
        assertEquals(listOf("Sacco a pelo", "Torcia", "Borraccia"), Regex("""<li class="pl-1">(.*?)</li>""").findAll(detail).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `t4 c05 an empty text comes back as not provided, and one that is too long says the maximum`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, info = "Un testo", whatToBring = "Una cosa")

        assertEquals(303, browser.send("/convivenza/$id/informazioni", mapOf("infoText" to "   ", "whatToBring" to "")).status())
        val detail = text(browser.get("/convivenza/$id").body())
        assertTrue(detail.contains("Informazioni Non indicate"))
        assertTrue(detail.contains("Cosa portare Non indicato"))

        val tooLong = browser.send("/convivenza/$id/informazioni", mapOf("infoText" to "a".repeat(5001), "whatToBring" to "b".repeat(2001)))
        assertEquals(422, tooLong.status())
        assertEquals("Le informazioni possono avere al massimo 5.000 caratteri: ne hai scritti 5.001.", errorOf(tooLong.body(), "informazioni"))
        assertEquals("L’elenco può avere al massimo 2.000 caratteri: ne hai scritti 2.001.", errorOf(tooLong.body(), "cosa-portare"))
        assertTrue(text(tooLong.body()).contains("Controlla i 2 campi segnati in rosso."))
        assertEquals("a".repeat(5001), valueOf(tooLong.body(), "informazioni"), "what was written comes back whole")

        val place = browser.send("/convivenza/$id/date", mapOf("title" to "Titolo", "startDate" to gatherings.today().plusDays(5).toString(), "endDate" to gatherings.today().plusDays(6).toString(), "place" to "p".repeat(201)))
        assertEquals("Il luogo può avere al massimo 200 caratteri: ne hai scritti 201.", errorOf(place.body(), "luogo"))
    }

    @Test
    fun `t4 c06 a text for the public page with an email address or a phone number is not saved, and the error asks to take it away`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft)
        val start = gatherings.today().plusDays(5).toString()
        val end = gatherings.today().plusDays(6).toString()

        val info = browser.send("/convivenza/$id/informazioni", mapOf("infoText" to "Scrivi a nome@example.org", "whatToBring" to "Chiama il 333 1234567"))
        assertEquals(422, info.status())
        assertEquals("Qui c’è un indirizzo email. Toglilo: la pagina la legge chiunque abbia il link.", errorOf(info.body(), "informazioni"))
        assertEquals("Qui c’è un numero di telefono. Toglilo: la pagina la legge chiunque abbia il link.", errorOf(info.body(), "cosa-portare"))
        val both = browser.send("/convivenza/$id/informazioni", mapOf("infoText" to "nome@example.org e 333 1234567", "whatToBring" to ""))
        assertEquals("Qui ci sono un indirizzo email e un numero di telefono. Toglili: la pagina la legge chiunque abbia il link.", errorOf(both.body(), "informazioni"))
        assertNull(dsl.select(GATHERING.INFO_TEXT).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.INFO_TEXT), "nothing saved")

        val title = browser.send("/convivenza/$id/date", mapOf("title" to "Convivenza di nome@example.org", "startDate" to start, "endDate" to end, "place" to "Casa, tel. 333 1234567"))
        assertEquals(422, title.status())
        assertEquals("Qui c’è un indirizzo email. Toglilo: la pagina la legge chiunque abbia il link.", errorOf(title.body(), "titolo"))
        assertEquals("Qui c’è un numero di telefono. Toglilo: la pagina la legge chiunque abbia il link.", errorOf(title.body(), "luogo"))
        val creation = browser.send("/convivenza/nuova", mapOf("title" to "Chiama 333 1234567", "startDate" to start, "endDate" to end))
        assertTrue(creation.status() == 409 || creation.status() == 422)

        // A date and a time, and an amount, pass.
        assertEquals(303, browser.send("/convivenza/$id/informazioni", mapOf("infoText" to "Ci si ritrova il 17/09/2027 alle 18:30, dalle 9.30, per 60 €.", "whatToBring" to "")).status())
        // The help says why and what not to write.
        assertTrue(browser.get("/convivenza/$id/informazioni").body().contains("Non scrivere nome e cognome di bambini o maestri, numeri di telefono né email."))
    }

    @Test
    fun `t4 c07 the fees take 60, 12,50 and 12_5, refuse the rest with an example, and show zero as Gratuita and nothing as Non prevista`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, full = null, daily = null, lunchOnly = null)

        assertEquals(303, browser.send("/convivenza/$id/quote", mapOf("fullFee" to "60", "dailyFee" to "12,50", "lunchOnlyFee" to "12.5")).status())
        val row = dsl.selectFrom(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle()
        assertEquals(listOf(6000, 1250, 1250), listOf(row.fullFeeCents, row.dailyFeeCents, row.lunchOnlyFeeCents))
        val detail = text(browser.get("/convivenza/$id").body())
        assertTrue(detail.contains("Quota intera 60 €"), detail)
        assertTrue(detail.contains("Quota giornaliera 12,50 €"))
        assertTrue(detail.contains("Quota solo pranzo 12,50 €"))
        val form = browser.get("/convivenza/$id/quote").body()
        assertEquals(listOf("60", "12,50", "12,50"), listOf("quota-intera", "quota-giornaliera", "quota-pranzo").map { valueOf(form, it) })

        // Empty is «Non prevista», zero is «Gratuita» (never «0 €»).
        assertEquals(303, browser.send("/convivenza/$id/quote", mapOf("fullFee" to "0", "dailyFee" to "", "lunchOnlyFee" to "0")).status())
        val free = text(browser.get("/convivenza/$id").body())
        assertTrue(free.contains("Quota intera Gratuita"), free)
        assertTrue(free.contains("Quota giornaliera Non prevista"))
        assertTrue(free.contains("Quota solo pranzo Gratuita"))
        assertFalse(free.contains("0 €"))

        for (typed in listOf("-5", "dieci", "1.000", "12,555", "1001")) {
            val refused = browser.send("/convivenza/$id/quote", mapOf("fullFee" to "60", "dailyFee" to typed, "lunchOnlyFee" to ""))
            assertEquals(422, refused.status(), typed)
            assertEquals("Scrivi un importo in euro, per esempio 60 oppure 12,50. Il massimo è 1000 €.", errorOf(refused.body(), "quota-giornaliera"), typed)
            assertEquals(typed, valueOf(refused.body(), "quota-giornaliera"))
        }
        assertNull(dsl.select(GATHERING.DAILY_FEE_CENTS).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.DAILY_FEE_CENTS), "the failed forms saved nothing")
    }

    @Test
    fun `t4 c07 the form of the fees has the sign of the euro inside the field, the help for empty and for zero, and the keyboard of numbers`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft)

        val html = browser.get("/convivenza/$id/quote").body()

        assertEquals(3, Regex("""inputmode="decimal"""").findAll(html).count())
        assertEquals(3, Regex("""<span aria-hidden="true" class="[^"]*">€</span>""").findAll(html).count())
        assertTrue(html.contains("Per chi viene tutti i giorni. Serve per aprire le iscrizioni: scrivi 0 se è gratuita."))
        assertTrue(html.contains("Per chi viene solo alcuni giorni: si paga per ogni giorno. Lascia vuoto se non è prevista; scrivi 0 se è gratuita."))
        assertTrue(html.contains("Per chi viene solo a pranzo: si paga per ogni pranzo. Lascia vuoto se non è prevista; scrivi 0 se è gratuita."))
        assertEquals("Gli importi compaiono nella pagina per i genitori.", Regex("""<p class="mt-2 text-ink-muted wrap-anywhere">(.*?)</p>""").find(html)?.groupValues?.get(1))
    }

    @Test
    fun `t4 c20 a text with markup is shown as text in the detail, the forms, the section and the title of the tab`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(
            title = "<script>alert(1)</script> &amp; <b>titolo</b>", place = "<i>Casa</i> & \"giardino\"", status = GatheringStatus.draft,
            info = "<script>x()</script>\n\n<b>grassetto</b> &amp; basta", whatToBring = "<img src=x onerror=y()>\n&amp;",
        )

        for (path in listOf("/convivenza", "/convivenza/$id", "/convivenza/$id/date", "/convivenza/$id/informazioni")) {
            val html = browser.get(path).body()
            for (markup in listOf("<script>alert", "<script>x()", "<b>titolo", "<b>grassetto", "<i>Casa", "<img src=x")) {
                assertFalse(html.contains(markup), "$path shows «$markup» as markup")
            }
            assertTrue(html.contains("&lt;"), "$path has the text escaped")
        }
        val detail = browser.get("/convivenza/$id").body()
        assertTrue(detail.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp;amp; &lt;b&gt;titolo&lt;/b&gt;"), "the title of the page is text")
        assertTrue(title(detail)!!.contains("&lt;script&gt;"), "the title of the tab is text")
        assertEquals("<script>alert(1)</script> &amp; <b>titolo</b>", valueOf(browser.get("/convivenza/$id/date").body(), "titolo"), "the field gets it back as it was written")
    }

    // ---- Dates after the end (criterion 17) ---------------------------------------------------------------------------------------

    @Test
    fun `t4 c17 once the gathering is over title, place, texts and fees change but the dates are text, and a request that changes them is refused`() {
        val (browser, _) = kit.start()
        val end = gatherings.today().minusDays(3)
        val id = gatherings.make(start = end.minusDays(2), end = end, status = GatheringStatus.closed, title = "Convivenza di settembre 2026")

        val form = browser.get("/convivenza/$id/date").body()
        assertFalse(form.contains("""type="date""""), "no date fields")
        assertTrue(text(form).contains("Date ${GatheringFormat.range(end.minusDays(2), end)}"), text(form))
        assertTrue(form.contains("Le date non si cambiano più, perché la convivenza è finita."))

        // Title and place can change, with no dates in the request.
        assertEquals(303, browser.send("/convivenza/$id/date", mapOf("title" to "Convivenza di settembre 2026, finita", "place" to "Un altro luogo")).status())
        val row = dsl.selectFrom(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle()
        assertEquals("Convivenza di settembre 2026, finita", row.title)
        assertEquals("Un altro luogo", row.place)
        assertEquals(end, row.endDate)

        // A request by hand with other dates: refused whole, nothing changes.
        val byHand = browser.send("/convivenza/$id/date", mapOf("title" to "Cambiato", "startDate" to end.plusDays(10).toString(), "endDate" to end.plusDays(12).toString(), "place" to "Cambiato"))
        assertEquals(409, byHand.status())
        assertTrue(text(byHand.body()).contains("Le date non si cambiano più, perché la convivenza è finita."))
        val after = dsl.selectFrom(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle()
        assertEquals(listOf("Convivenza di settembre 2026, finita", "Un altro luogo", end), listOf(after.title, after.place, after.endDate))

        // Fees and texts are not about the dates.
        assertEquals(303, browser.send("/convivenza/$id/quote", mapOf("fullFee" to "55")).status())
        assertEquals(303, browser.send("/convivenza/$id/informazioni", mapOf("infoText" to "Un testo", "whatToBring" to "")).status())
    }

    @Test
    fun `t4 c17 a draft can always change its dates, and the dates of a gathering that is not over can change`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.`open`)
        val start = gatherings.today().plusDays(30)

        assertTrue(browser.get("/convivenza/$id/date").body().contains("""type="date""""))
        assertEquals(303, browser.send("/convivenza/$id/date", mapOf("title" to "Titolo", "startDate" to start.toString(), "endDate" to start.plusDays(1).toString(), "place" to "Luogo")).status())
        assertEquals(start.plusDays(1), dsl.select(GATHERING.END_DATE).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.END_DATE))

        // A draft whose end has passed can still change its title with the dates as they are, and its dates.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.draft).set(GATHERING.START_DATE, gatherings.today().minusDays(9)).set(GATHERING.END_DATE, gatherings.today().minusDays(7)).where(GATHERING.ID.eq(id)).execute()
        assertEquals(
            303,
            browser.send("/convivenza/$id/date", mapOf("title" to "Solo il titolo", "startDate" to gatherings.today().minusDays(9).toString(), "endDate" to gatherings.today().minusDays(7).toString(), "place" to "Luogo")).status(),
        )
    }

    @Test
    fun `t4 c10 once it is not a draft any more the place and the full fee cannot be emptied, and the form says so`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.`open`)
        val start = gatherings.today().plusDays(30).toString()

        val place = browser.send("/convivenza/$id/date", mapOf("title" to "Titolo", "startDate" to start, "endDate" to start, "place" to " "))
        assertEquals(422, place.status())
        assertEquals(GatheringChecks.PLACE_MISSING, errorOf(place.body(), "luogo"))
        val fee = browser.send("/convivenza/$id/quote", mapOf("fullFee" to "", "dailyFee" to "", "lunchOnlyFee" to ""))
        assertEquals(422, fee.status())
        assertEquals(GatheringChecks.FULL_FEE_MISSING, errorOf(fee.body(), "quota-intera"))
    }

    // ---- The pages as a whole (criterion 38) ---------------------------------------------------------------------------------

    @Test
    fun `t4 c38 the pages of the gathering have no inline script, on handler, hx-on or style attribute, no address outside the app, and none of the forbidden words`() {
        val (browser, meId) = kit.start(Person(Addresses.unique("conv-regole"), "Elisa", "Conti"), Person(Addresses.unique("conv-regole-2"), "Paolo", "Verdi"))
        val forbidden = listOf("koi" + "nè", "cen" + "tro", "comuni" + "tà", "fra" + "telli")
        val pages = mutableMapOf<String, String>()

        fun collect(label: String) {
            val id = dsl.select(GATHERING.ID).from(GATHERING).limit(1).fetchOne(GATHERING.ID)
            val paths = listOf("/convivenza", "/convivenza/nuova", "/maestri") + if (id == null) emptyList() else listOf(
                "/convivenza/$id", "/convivenza/$id/date", "/convivenza/$id/quote", "/convivenza/$id/affidatari", "/convivenza/$id/informazioni",
                "/convivenza/$id/apri", "/convivenza/$id/chiudi", "/convivenza/$id/riapri", "/convivenza/$id/anteprima",
            )
            for (path in paths) pages["$label $path"] = browser.get(path).body()
        }
        collect("empty")
        val id = gatherings.make(status = GatheringStatus.draft, custodians = listOf(meId), info = "Un testo\n\nCon due paragrafi", whatToBring = "Una cosa\nUn'altra")
        collect("draft")
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).where(GATHERING.ID.eq(id)).execute()
        collect("open")
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(id)).execute()
        collect("closed")
        dsl.update(GATHERING).set(GATHERING.START_DATE, gatherings.today().minusDays(9)).set(GATHERING.END_DATE, gatherings.today().minusDays(7)).where(GATHERING.ID.eq(id)).execute()
        collect("over")
        browser.send("/convivenza/$id/quote", mapOf("fullFee" to "dieci", "dailyFee" to "", "lunchOnlyFee" to "-1")).let { pages["fees with errors"] = it.body() }

        assertTrue(pages.size > 30, "${pages.size} pages")
        for ((name, html) in pages) {
            val links = Regex("""\s(?:src|href|action|poster|data|formaction)\s*=\s*["']([^"']*)["']""").findAll(html).map { it.groupValues[1] }
            for (link in links) {
                assertFalse(Regex("""^(?:[a-z][a-z0-9+.-]*:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(link) && !link.startsWith("#"), "$name: external link $link")
            }
            assertFalse(Regex("""<script(?![^>]*\ssrc=)""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: inline script")
            assertFalse(Regex("""<[^>]+\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: on* attribute")
            assertFalse(Regex("""<[^>]+\shx-on""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: hx-on")
            assertFalse(Regex("""<[^>]+\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: style attribute")
            assertFalse(Regex("""<style""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: style element")
            for (word in forbidden) assertFalse(html.contains(word, ignoreCase = true), "$name: the word '$word'")
            // A step that makes no sense for the state leads back to the detail (an empty answer, with a redirect): the others have one h1.
            if (html.isNotBlank()) assertEquals(1, Regex("""<h1[\s>]""").findAll(html).count(), "$name: one h1")
        }
    }

    // ---- Permissions (criterion 19) -----------------------------------------------------------------------------------------------

    private fun addressesOf(id: Long) = listOf(
        "/convivenza", "/convivenza/nuova", "/convivenza/$id", "/convivenza/$id/date", "/convivenza/$id/quote", "/convivenza/$id/affidatari",
        "/convivenza/$id/informazioni", "/convivenza/$id/apri", "/convivenza/$id/chiudi", "/convivenza/$id/riapri", "/convivenza/$id/anteprima",
    )

    @Test
    fun `t4 c19 a parent who asks for any address of the gathering, in GET or POST, gets 403 and nothing changes`() {
        val (_, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft)
        val before = dsl.selectFrom(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle()
        val parent = kit.parent()

        for (path in addressesOf(id)) {
            assertEquals(403, parent.get(path).status(), "GET $path")
            val posted = mockMvc.post(path) {
                session = parent.cookie
                with(csrf())
                param("title", "Cambiato")
                param("from", "draft")
                param("fullFee", "99")
                param("infoText", "Cambiato")
            }.andReturn()
            assertEquals(403, posted.status(), "POST $path")
        }
        assertEquals(before, dsl.selectFrom(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(), "nothing changed")
        assertEquals(1, kit.count())
    }

    @Test
    fun `t4 c19 without access every address of the gathering leads to the access page`() {
        val (_, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft)

        for (path in addressesOf(id)) {
            val get = mockMvc.get(path).andReturn()
            assertEquals(302, get.status(), "GET $path")
            assertEquals("/accedi", get.location(), "GET $path")
            val post = mockMvc.post(path) { with(csrf()) }.andReturn()
            assertEquals(302, post.status(), "POST $path")
            assertEquals("/accedi", post.location(), "POST $path")
        }
    }

    @Test
    fun `t4 c19 a gathering that does not exist is a page that does not exist`() {
        val (browser, _) = kit.start()

        for (path in listOf("/convivenza/999999", "/convivenza/999999/date", "/convivenza/999999/quote", "/convivenza/999999/affidatari", "/convivenza/999999/informazioni", "/convivenza/999999/apri")) {
            assertEquals(404, browser.get(path).status(), path)
        }
        // Not a number: no such page.
        assertEquals(404, browser.get("/convivenza/abc").status())
    }

    @Test
    fun `t4 c19 a person who is a teacher and a parent opens them like any teacher`() {
        val me = Person(Addresses.unique("doppio"), "Elisa", "Conti")
        val (browser, _) = kit.start(me)

        assertEquals(200, browser.get("/convivenza").status())
        assertEquals(200, browser.get("/convivenza/nuova").status())
    }

    @Test
    fun `t4 c19 the teacher who is taken away loses the gathering at the next request`() {
        val me = Person(Addresses.unique("tolto"), "Elisa", "Conti")
        val other = Person(Addresses.unique("resta"), "Paolo", "Ferri")
        val (browser, _) = kit.start(me, other)
        assertEquals(200, browser.get("/convivenza").status())

        dsl.deleteFrom(talita.jooq.tables.references.TEACHER).where(talita.jooq.tables.references.TEACHER.ACCOUNT_ID.eq(kit.accountId(me.email))).execute()

        assertEquals(403, browser.get("/convivenza").status())
        assertEquals(0, dsl.fetchCount(GATHERING_CUSTODIAN))
    }
}
