package talita.venue

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.GatheringTestKit
import talita.gathering.body
import talita.gathering.errorOf
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.gathering.text
import talita.gathering.title
import talita.gathering.valueOf
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.ROOM
import talita.jooq.tables.references.VENUE
import talita.teacher.Person
import talita.ui.Alert
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The venues for the teachers, as the server renders them (TALITA-20, part 1, criteria 1-3, 6 and 7): the list, «Nuova struttura», the page of a venue, «Modifica la struttura» and «Togli la
 * struttura», with the words of the design. The teacher has entered with the link, like anyone; a parent is tried on every address. Names, places and numbers are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class VenuePagesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings

    private val newForm = "/strutture/nuova"
    private val nameError = "Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link."

    private fun MockBrowser.create(name: String, place: String): MvcResult = submit(newForm, newForm, mapOf("name" to name, "place" to place))

    private fun MockBrowser.change(id: Long, name: String, place: String): MvcResult = submit("/strutture/$id/modifica", "/strutture/$id/modifica", mapOf("name" to name, "place" to place))

    private fun rows(): List<Pair<String, String>> = dsl.select(VENUE.NAME, VENUE.PLACE).from(VENUE).orderBy(VENUE.ID).fetch { it[VENUE.NAME]!! to it[VENUE.PLACE]!! }

    private fun venue(name: String, place: String): Long = gatherings.makeVenue(place, name)

    private fun child(first: String, last: String): Long =
        dsl.insertInto(CHILD).set(CHILD.FIRST_NAME, first).set(CHILD.LAST_NAME, last).set(CHILD.BIRTH_DATE, LocalDate.of(2016, 4, 4)).returningResult(CHILD.ID).fetchSingle().value1()!!

    /** The notice that the next page shows, left in the session by the answer (once). */
    private fun noticeOf(browser: MockBrowser): Alert? = (browser.cookie?.getAttribute("talita.flash")) as? Alert

    // ---- The list (criterion 1) ------------------------------------------------------------------------------------------------

    @Test
    fun `t20 c01 with no venue the list has the empty state with Nuova struttura, and no search, no map and no photo`() {
        val (browser, _) = kit.start()

        val page = browser.get("/strutture")
        val html = page.body()
        val words = text(html)

        assertEquals(200, page.status())
        assertEquals("Strutture", heading(html))
        assertEquals("Strutture · Talità", title(html))
        assertTrue(words.contains("Le case dove si fa la convivenza. Le vedono solo i maestri."), words)
        assertTrue(words.contains("Nessuna struttura"), words)
        assertTrue(words.contains("Scrivi la casa dove fai la convivenza, con le sue camere e i moduli che chiede. Poi la scegli nella convivenza."), words)
        assertTrue(Regex("""<a href="/strutture/nuova"[^>]*>[\s\S]*?Nuova struttura</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="/convivenza"[^>]*>[\s\S]*?Torna alla convivenza</a>""").containsMatchIn(html))
        assertFalse(html.contains("""type="search""""), "no search")
        assertFalse(Regex("""<img|<iframe|<canvas|maps""", RegexOption.IGNORE_CASE).containsMatchIn(html), "no map and no photo")
        // «Convivenza» is the current entry of the navigation: the venues are reached from there.
        assertTrue(Regex("""<a href="/convivenza" aria-current="page"""").containsMatchIn(html))
    }

    @Test
    fun `t20 c01 the list has every venue with its name, its place and how many gatherings use it, by name, each row a link`() {
        val (browser, _) = kit.start()
        val lake = venue("Casa sul lago", "Casa per ritiri sul lago, Località Esempio")
        val wood = venue("casa del bosco", "Casa per ritiri del bosco, Località Esempio")
        val end = gatherings.today().minusDays(300)
        gatherings.make(status = GatheringStatus.draft, venue = wood)
        gatherings.make(title = "Convivenza di settembre 2026", start = end.minusDays(2), end = end, status = GatheringStatus.closed, venue = wood)
        dsl.insertInto(ROOM).set(ROOM.VENUE_ID, wood).set(ROOM.NAME, "Camera azzurra").set(ROOM.BEDS, 4.toShort()).execute()
        dsl.insertInto(ROOM).set(ROOM.VENUE_ID, wood).set(ROOM.NAME, "Camera 2").set(ROOM.BEDS, 2.toShort()).execute()

        val html = browser.get("/strutture").body()
        val words = text(html)

        assertTrue(words.indexOf("casa del bosco") < words.indexOf("Casa sul lago"), "by name, whatever the capitals: $words")
        // The rooms and the beds of each venue, in words, and «Nessuna camera» for a venue that has none (TALITA-20, criterion 1).
        assertTrue(words.contains("casa del bosco Casa per ritiri del bosco, Località Esempio 2 camere, 6 posti · Nessun modulo Usata da 2 convivenze"), words)
        assertTrue(words.contains("Casa sul lago Casa per ritiri sul lago, Località Esempio Nessuna camera · Nessun modulo Nessuna convivenza la usa"), words)
        assertTrue(Regex("""<a href="/strutture/$wood"[^>]*>[\s\S]*?casa del bosco""").containsMatchIn(html), "the whole row is the link")
        assertTrue(Regex("""<a href="/strutture/$lake"[^>]*>[\s\S]*?Casa sul lago""").containsMatchIn(html))
        assertFalse(words.contains("Nessuna struttura"), "no empty state")
        assertTrue(Regex("""<a href="/strutture/nuova"[^>]*>[\s\S]*?Nuova struttura</a>""").containsMatchIn(html), "the way to write a new one is above the list")
        // One gathering says «1 convivenza».
        gatherings.reset()
        val used = venue("Casa del bosco", "Casa per ritiri del bosco, Località Esempio")
        gatherings.make(status = GatheringStatus.draft, venue = used)
        assertTrue(text(browser.get("/strutture").body()).contains("Usata da 1 convivenza"))
    }

    @Test
    fun `t20 c01 the section Convivenza has the row Strutture above Maestri, which stays the last`() {
        val (browser, _) = kit.start()
        val link = Regex("""<a href="/strutture"[^>]*>[\s\S]*?Strutture[\s\S]*?Le case dove si fa la convivenza, con luogo, camere e moduli\.""")

        for (state in listOf("empty", "a draft")) {
            if (state == "a draft") gatherings.make(status = GatheringStatus.draft)
            val html = browser.get("/convivenza").body()
            assertTrue(link.containsMatchIn(html), state)
            assertTrue(html.indexOf("""<h2 id="strutture"""") < html.indexOf("""<h2 id="maestri""""), "$state: Strutture is above Maestri")
        }
    }

    // ---- The forms (criterion 2) -----------------------------------------------------------------------------------------------

    @Test
    fun `t20 c02 a venue is saved with a name of 1 to 60 characters and a place of 1 to 200, one line each, with the spaces at the sides taken away`() {
        val (browser, _) = kit.start()

        val created = browser.create("  Casa del bosco ", "Casa per ritiri\ndel   bosco ".replace("   ", " "))

        assertEquals(303, created.status())
        assertEquals(listOf("Casa del bosco" to "Casa per ritiri del bosco"), rows())
        val id = dsl.select(VENUE.ID).from(VENUE).fetchSingle(VENUE.ID)!!
        assertEquals("/strutture/$id", created.location())
        // The notice shows once, on the page of the venue, with how to use it.
        val page = browser.get(created.location()!!)
        val words = text(page.body())
        assertTrue(words.contains("Struttura salvata. Per usarla, scegli «Casa del bosco» in «Modifica titolo, date e luogo» della convivenza. Vai alla convivenza"), words)
        assertFalse(browser.get(created.location()!!).body().contains("Struttura salvata."), "once")
        // The limits are at 60 and at 200 characters: the longest are saved.
        assertEquals(303, browser.create("N".repeat(60), "P".repeat(200)).status())
        assertEquals(60, rows().last().first.length)
        assertEquals(200, rows().last().second.length)
    }

    @Test
    fun `t20 c02 an empty field, a name over 60 characters and a place over 200 are not saved, with the error under the field and the form as it was written`() {
        val (browser, _) = kit.start()

        val both = browser.create("   ", "")
        assertEquals(422, both.status())
        assertEquals("Scrivi il nome della struttura.", errorOf(both.body(), "nome"))
        assertEquals("Scrivi il luogo: la pagina per i genitori lo mostra.", errorOf(both.body(), "luogo"))
        assertTrue(text(both.body()).contains("Controlla i 2 campi segnati in rosso."))
        assertEquals(emptyList(), rows())

        val long = browser.create("N".repeat(71), "Un luogo")
        assertEquals(422, long.status())
        assertEquals("Il nome può avere al massimo 60 caratteri: ne hai scritti 71.", errorOf(long.body(), "nome"))
        assertNull(errorOf(long.body(), "luogo"))
        assertFalse(text(long.body()).contains("Controlla i"), "one error: no notice above, the focus goes to the field")
        assertEquals("N".repeat(71), valueOf(long.body(), "nome"), "what was written comes back whole")
        assertEquals("Un luogo", valueOf(long.body(), "luogo"))
        assertTrue(Regex("""id="nome"[^>]*aria-invalid="true"""").containsMatchIn(long.body()), "the field is marked, so the focus goes to it")

        val place = browser.create("Casa del bosco", "L".repeat(213))
        assertEquals(422, place.status())
        assertEquals("Il luogo può avere al massimo 200 caratteri: ne hai scritti 213.", errorOf(place.body(), "luogo"))
        assertEquals(emptyList(), rows())
    }

    @Test
    fun `t20 c02 a name that exists, whatever the capitals and the spaces at the sides, has the error under the field, in the new form and in the change, and a venue can take its own name again`() {
        val (browser, _) = kit.start()
        val wood = venue("Casa del bosco", "Casa per ritiri del bosco, Località Esempio")
        val lake = venue("Casa sul lago", "Casa per ritiri sul lago, Località Esempio")

        val same = browser.create("  CASA del BOSCO  ", "Un altro luogo")
        assertEquals(422, same.status())
        assertEquals("C’è già una struttura con questo nome.", errorOf(same.body(), "nome"))
        assertEquals("  CASA del BOSCO  ".trim(), valueOf(same.body(), "nome"))
        assertEquals("Un altro luogo", valueOf(same.body(), "luogo"))
        assertEquals(2, rows().size)

        val taken = browser.change(lake, "casa del bosco", "Casa per ritiri sul lago, Località Esempio")
        assertEquals(422, taken.status())
        assertEquals("C’è già una struttura con questo nome.", errorOf(taken.body(), "nome"))
        assertEquals(listOf("Casa del bosco" to "Casa per ritiri del bosco, Località Esempio", "Casa sul lago" to "Casa per ritiri sul lago, Località Esempio"), rows())

        // Its own name, written again with other capitals, is not «another venue».
        assertEquals(303, browser.change(wood, "CASA DEL BOSCO", "Casa per ritiri del bosco, Località Esempio").status())
        assertEquals("CASA DEL BOSCO", rows().first().first)
    }

    @Test
    fun `t20 c02 the form of a change has the name and the place of the venue, says what changing a venue that is used does, and saves`() {
        val (browser, _) = kit.start()
        val wood = venue("Casa del bosco", "Casa per ritiri del bosco, Località Esempio")

        val form = browser.get("/strutture/$wood/modifica")
        val words = text(form.body())
        assertEquals(200, form.status())
        assertEquals("Modifica la struttura", heading(form.body()))
        assertEquals("Casa del bosco", valueOf(form.body(), "nome"))
        assertEquals("Casa per ritiri del bosco, Località Esempio", valueOf(form.body(), "luogo"))
        assertFalse(words.contains("è usata da"), "nothing uses it: no notice")

        gatherings.make(status = GatheringStatus.draft, venue = wood)
        val used = text(browser.get("/strutture/$wood/modifica").body())
        assertTrue(used.contains("Questa struttura è usata da 1 convivenza. Se cambi il nome o il luogo, lo leggono subito i genitori. Le iscrizioni già fatte restano con il testo di allora."), used)

        val saved = browser.change(wood, "Casa del bosco grande", "Casa per ritiri del bosco, Località Esempio")
        assertEquals(303, saved.status())
        assertEquals("/strutture/$wood", saved.location())
        assertEquals(listOf("Casa del bosco grande" to "Casa per ritiri del bosco, Località Esempio"), rows())
        val page = text(browser.get("/strutture/$wood").body())
        assertTrue(page.contains("Struttura salvata. Nome e luogo nuovi sono già nella pagina per i genitori. Le iscrizioni già fatte restano con il testo di allora."), page)
        // A venue that nothing uses says only «Struttura salvata.».
        gatherings.reset()
        val free = venue("Casa sul lago", "Casa per ritiri sul lago, Località Esempio")
        assertEquals(303, browser.change(free, "Casa sul lago grande", "Casa per ritiri sul lago, Località Esempio").status())
        val after = text(browser.get("/strutture/$free").body())
        assertTrue(after.contains("Struttura salvata."), after)
        assertFalse(after.contains("Nome e luogo nuovi sono già nella pagina per i genitori"), after)
        // An id that is not there, in the page and in the form, is a page that does not exist.
        assertEquals(404, browser.get("/strutture/${free + 100_000}").status())
        assertEquals(404, browser.get("/strutture/${free + 100_000}/modifica").status())
        assertEquals(404, browser.submit("/strutture/nuova", "/strutture/${free + 100_000}/modifica", mapOf("name" to "Casa", "place" to "Luogo")).status())
    }

    // ---- Public text (criterion 3) ---------------------------------------------------------------------------------------------

    @Test
    fun `t20 c03 an email, a phone number, an IBAN or the name of a child or of a teacher in the name or in the place is refused with the sentences of the public texts, and nothing is saved`() {
        val (browser, _) = kit.start(Person(Addresses.unique("t20-elisa"), "Elisa", "Conti"), Person(Addresses.unique("t20-marta"), "Marta", "Bianchi"))
        child("Luca", "Rossi")
        val wood = venue("Casa del bosco", "Casa per ritiri del bosco, Località Esempio")
        val tail = "Toglili: la pagina la legge chiunque abbia il link."
        val cases = listOf(
            "Casa di nome@example.org" to "Qui c’è un indirizzo email. Toglilo: la pagina la legge chiunque abbia il link.",
            "Casa, tel. 333 1234567" to "Qui c’è un numero di telefono. Toglilo: la pagina la legge chiunque abbia il link.",
            "Casa IT60X0542811101000000123456" to "Qui c’è un IBAN. Toglilo: la pagina la legge chiunque abbia il link.",
            "Casa di Luca Rossi" to nameError,
            "rossi luca" to nameError,
            "Casa di Marta Bianchi" to nameError,
            "Luca Rossi nome@example.org 333 123 4567" to "Qui ci sono nome e cognome di una persona, un indirizzo email e un numero di telefono. $tail",
        )

        for ((typed, error) in cases) {
            // The name and the place are public, both: the same sentences, with «la pagina la legge chiunque abbia il link».
            val inName = browser.create(typed, "Un luogo")
            assertEquals(422, inName.status(), typed)
            assertEquals(error, errorOf(inName.body(), "nome"), typed)
            assertNull(errorOf(inName.body(), "luogo"), typed)
            assertEquals(typed, valueOf(inName.body(), "nome"), "the field keeps what was written")
            val inPlace = browser.create("Un nome", typed)
            assertEquals(422, inPlace.status(), typed)
            assertEquals(error, errorOf(inPlace.body(), "luogo"), typed)
            assertNull(errorOf(inPlace.body(), "nome"), typed)
            // The same in the form of a change, and nothing is saved there either.
            assertEquals(error, errorOf(browser.change(wood, typed, "Un luogo").body(), "nome"), typed)
            assertEquals(error, errorOf(browser.change(wood, "Un nome", typed).body(), "luogo"), typed)
        }
        assertEquals(listOf("Casa del bosco" to "Casa per ritiri del bosco, Località Esempio"), rows(), "nothing was saved")
        // Both at once: two errors, and the notice above.
        val both = browser.create("Casa di Luca Rossi", "Casa, tel. 333 1234567")
        assertTrue(text(both.body()).contains("Controlla i 2 campi segnati in rosso."))
        // The name alone, the two parts apart, and a name of a person that the application does not know pass: it is a net, and the help says not to write names.
        for (typed in listOf("Casa di Luca", "Casa Rossi", "Luca, Rossi", "Lucarossi", "Casa di Nicolò Verdi")) {
            assertEquals(303, browser.create(typed, "Un luogo").status(), typed)
        }
    }

    // ---- Taking a venue away (criterion 6) -------------------------------------------------------------------------------------

    @Test
    fun `t20 c06 a venue that no gathering uses is taken away after a dialog, and the list says so`() {
        val (browser, _) = kit.start()
        val lake = venue("Casa sul lago", "Casa per ritiri sul lago, Località Esempio")
        val wood = venue("Casa del bosco", "Casa per ritiri del bosco, Località Esempio")

        val page = browser.get("/strutture/$lake")
        val html = page.body()
        val words = text(html)
        assertEquals("Casa sul lago", heading(html))
        assertEquals("Casa sul lago · Talità", title(html))
        assertTrue(words.contains("Nome e luogo Modifica Nome Casa sul lago Luogo Casa per ritiri sul lago, Località Esempio"), words)
        assertTrue(words.contains("Nome e luogo li leggono i genitori, nella pagina informativa e nell’anteprima del link. Nessuna convivenza la usa."), words)
        // The button opens the dialog (it takes nothing away by itself) and the dialog says what goes.
        assertTrue(Regex("""<a href="/strutture/$lake/togli" data-apri="togli-struttura"[^>]*>[\s\S]*?Togli la struttura</a>""").containsMatchIn(html), "the button of danger, outlined")
        assertTrue(Regex("""<dialog id="togli-struttura"""").containsMatchIn(html))
        assertTrue(words.contains("Togliere Casa sul lago? Casa sul lago sparisce dall’elenco delle strutture. Nessuna convivenza la usa. Potrai scriverla di nuovo quando vuoi."), words)
        assertTrue(Regex("""<button[^>]*class="[^"]*bg-danger[^"]*"[\s\S]*?Togli la struttura""").containsMatchIn(html), "the button of the dialog is the one of danger")
        assertEquals(2, rows().size, "nothing was taken away by looking at it")

        // Without JavaScript the same words are a page of their own.
        val confirm = browser.get("/strutture/$lake/togli")
        assertEquals(200, confirm.status())
        assertTrue(text(confirm.body()).contains("Togliere Casa sul lago? Casa sul lago sparisce dall’elenco delle strutture. Nessuna convivenza la usa. Potrai scriverla di nuovo quando vuoi."))

        val removed = browser.submit("/strutture/$lake/togli", "/strutture/$lake/togli", mapOf("struttura" to lake.toString()))
        assertEquals(303, removed.status())
        assertEquals("/strutture", removed.location())
        assertEquals(listOf("Casa del bosco" to "Casa per ritiri del bosco, Località Esempio"), rows())
        val list = browser.get("/strutture")
        assertTrue(text(list.body()).contains("Struttura tolta."), "the list says so, once")
        assertFalse(browser.get("/strutture").body().contains("Struttura tolta."))
        assertFalse(text(list.body()).contains("Casa sul lago"))
        // It is not there any more: the page, the form and a second request are pages that do not exist.
        assertEquals(404, browser.get("/strutture/$lake").status())
        assertEquals(404, browser.get("/strutture/$lake/togli").status())
        assertEquals(404, browser.submit("/strutture/$wood", "/strutture/$lake/togli", mapOf("struttura" to lake.toString())).status())
        assertEquals(1, rows().size)
    }

    @Test
    fun `t20 c06 a venue that a gathering uses is not taken away, the page says why, and a request made by hand gets 409 and the database says no too`() {
        val (browser, _) = kit.start()
        val wood = venue("Casa del bosco", "Casa per ritiri del bosco, Località Esempio")
        gatherings.make(status = GatheringStatus.draft, venue = wood)

        val page = browser.get("/strutture/$wood")
        val words = text(page.body())
        assertTrue(words.contains("Nome e luogo li leggono i genitori, nella pagina informativa e nell’anteprima del link. Usata da 1 convivenza."), words)
        assertTrue(words.contains("Questa struttura è usata da 1 convivenza, quindi non si toglie. Puoi cambiarle il nome e il luogo."), words)
        assertFalse(words.contains("Togli la struttura"), "no button, no dialog")
        assertFalse(page.body().contains("<dialog"))

        // A request made by hand: nothing is taken away, and the page is the one of now, with the reason.
        val byHand = browser.submit(newForm, "/strutture/$wood/togli", mapOf("struttura" to wood.toString()))
        assertEquals(409, byHand.status())
        assertTrue(text(byHand.body()).contains("Non è stato tolto niente. Questa struttura è usata da 1 convivenza."), text(byHand.body()))
        assertTrue(text(byHand.body()).contains("Casa del bosco"))
        assertEquals(1, rows().size)
        // The page of confirmation has nothing to confirm: it leads back to the page of the venue.
        val confirm = browser.get("/strutture/$wood/togli")
        assertEquals(303, confirm.status())
        assertEquals("/strutture/$wood", confirm.location())
        // The key from the gathering has no cascade: the database refuses to delete the venue under a gathering.
        assertFailsWith<Exception> { dsl.deleteFrom(VENUE).where(VENUE.ID.eq(wood)).execute() }
        assertEquals(1, rows().size)
        // Two gatherings say «2 convivenze».
        val end = gatherings.today().minusDays(300)
        gatherings.make(title = "Convivenza di settembre 2026", start = end.minusDays(2), end = end, status = GatheringStatus.closed, venue = wood)
        val twice = text(browser.get("/strutture/$wood").body())
        assertTrue(twice.contains("Usata da 2 convivenze."), twice)
        assertTrue(twice.contains("Questa struttura è usata da 2 convivenze, quindi non si toglie."), twice)
    }

    // ---- Who can open them (criterion 7) ---------------------------------------------------------------------------------------

    @Test
    fun `t20 c07 a parent is told 403 on every address of the venues, with a page and with a form, and nothing changes, and whoever has not entered goes to the access page`() {
        kit.start()
        val wood = venue("Casa del bosco", "Casa per ritiri del bosco, Località Esempio")
        gatherings.make(status = GatheringStatus.draft, venue = wood)
        val parent = kit.parent()
        val pages = listOf("/strutture", "/strutture/nuova", "/strutture/$wood", "/strutture/$wood/modifica", "/strutture/$wood/togli")
        val forms = listOf(
            "/strutture/nuova" to mapOf("name" to "Casa sul lago", "place" to "Un luogo"),
            "/strutture/$wood/modifica" to mapOf("name" to "Cambiata", "place" to "Cambiato"),
            "/strutture/$wood/togli" to mapOf("struttura" to wood.toString()),
        )
        val before = rows()

        for (path in pages) {
            assertEquals(403, parent.get(path).status(), "GET $path")
            // Whoever has no access goes to the access page.
            val anonymous = mockMvc.get(path).andReturn().response
            assertEquals(302, anonymous.status, "GET $path")
            assertEquals("/accedi", anonymous.getHeader("Location"), "GET $path")
        }
        for ((path, fields) in forms) {
            val answer = mockMvc.post(path) { session = parent.cookie; with(csrf()); fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
            assertEquals(403, answer.status, "POST $path")
            val htmx = mockMvc.post(path) { session = parent.cookie; with(csrf()); header("HX-Request", "true"); fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
            assertEquals(403, htmx.status, "POST (htmx) $path")
            val anonymous = mockMvc.post(path) { fields.forEach { (name, value) -> param(name, value) } }.andReturn().response
            assertEquals(302, anonymous.status, "POST $path")
            assertEquals("/accedi", anonymous.getHeader("Location"), "POST $path")
        }
        assertEquals(before, rows(), "nothing was changed")
        // A venue is never in the answer to a parent.
        assertFalse(parent.get("/strutture/$wood").body().contains("Casa del bosco"))
        assertNotNull(parent.cookie)
    }

    // ---- The notices are left once, for the next page --------------------------------------------------------------------------

    @Test
    fun `t20 c02 the notice of a venue that was saved is left for the page of the venue, and a form that goes well has no body`() {
        val (browser, _) = kit.start()

        val created = browser.create("Casa del bosco", "Un luogo")

        assertEquals(303, created.status())
        assertEquals("", created.body())
        assertEquals("Struttura salvata.", noticeOf(browser)?.lead)
    }
}
