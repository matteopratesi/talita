package talita.room

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.checkin.CheckinSetup
import talita.checkin.words
import talita.gathering.GatheringFormat
import talita.gathering.body
import talita.gathering.heading
import talita.gathering.status
import talita.gathering.title
import talita.jooq.enums.RegistrationStatus
import talita.registration.Notes
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The print «Camere» (criterion 14): what is on the sheet, what is never on it (ages, presences, contacts, notes on health), the sentence that says to keep it among the teachers and to
 * destroy it, the empty and the deleted states, and that every class of the print has its rule in the stylesheet that is served. The look on a real Chromium is in the e2e test. Names are
 * synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomPrintTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    private inner class World {
        val setup: CheckinSetup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val two = kit.room(setup.id, "Camera 2", 4)
        val azzurra = kit.room(setup.id, "Stanza azzurra", 2)
        val vuota = kit.room(setup.id, "Mansarda", 3)
        val birth = LocalDate.of(2016, 4, 4)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi", birth, phone = "333 777 4242", notes = Notes("Arachidi", null, "Cortisone"))
        val anna = kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi", birth.plusYears(2))
        val sara = kit.rows.register(setup.id, kit.checkin.account(), "Sara", "Neri", birth.plusYears(1), status = RegistrationStatus.maybe, days = mapOf(setup.today to "full"))
        val davide = kit.rows.register(setup.id, kit.checkin.account(), "Davide", "Costa", birth.plusYears(3))
        val nicolo = kit.rows.register(setup.id, null, "Nicolò", "Ferri", birth, enteredBy = setup.martaId, days = mapOf(setup.today to "lunch"))
        val marta = setup.marta
        val print = "/convivenza/${setup.id}/camere/stampa"

        init {
            kit.put(luca, setup.id, one)
            kit.put(anna, setup.id, one)
            kit.put(sara, setup.id, two)
            kit.putTeacher(setup.id, setup.paoloId, one)
            kit.putTeacher(setup.id, setup.giuliaId, azzurra)
        }
    }

    @Test
    fun `t11 c14 the sheet has the title, the dates, when it was printed, the sentence of the secrecy, each room with who sleeps there and who has no room at the bottom`() {
        val w = World()
        val response = w.marta.get(w.print)
        val html = response.body()
        val text = words(html)

        assertEquals(200, response.status())
        assertEquals("Camere", heading(html))
        assertEquals("Camere · stampa · Talità", title(html))
        assertTrue(text.contains("Convivenza di settembre 2027 · ${GatheringFormat.range(w.setup.yesterday, w.setup.tomorrow)}"), text)
        assertTrue(Regex("""Stampato il \d{1,2} [a-zà-ù]+ \d{4} alle \d{1,2}:\d{2}\.""").containsMatchIn(text), "the day and the hour of the print: $text")
        assertTrue(text.contains("Questo foglio dice dove dorme ogni persona. Tienilo fra i maestri e distruggilo alla fine della convivenza."), text)
        assertTrue(text.contains("Alla fine della convivenza questo foglio va distrutto. Non ha età, presenze, recapiti né segnalazioni."), text)
        // A block for each room: the name, the beds, the children one for each line by surname and name (and «in forse»), then «Maestri», and «Vuota» for a room with nobody.
        val one = words(Regex("""<section id="stampa-camera-${w.one}".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value)
        assertTrue(one.contains("Camera 1 4 posti Anna Rossi Luca Rossi Maestri Paolo Verdi"), one)
        val two = words(Regex("""<section id="stampa-camera-${w.two}".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value)
        assertTrue(two.contains("Camera 2 4 posti Sara Neri (in forse)"), two)
        assertTrue(words(Regex("""<section id="stampa-camera-${w.azzurra}".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value).contains("Stanza azzurra 2 posti Maestri Giulia Serra"))
        assertTrue(words(Regex("""<section id="stampa-camera-${w.vuota}".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value).contains("Mansarda 3 posti Vuota"))
        // The rooms are in the order they were written, and who has no room is after them: the children (not Nicolò, who comes for lunch), then the custodian.
        assertTrue(html.indexOf("stampa-camera-${w.one}\"") < html.indexOf("stampa-camera-${w.two}\"") && html.indexOf("stampa-camera-${w.vuota}\"") < html.indexOf("stampa-senza-camera\""))
        val free = words(Regex("""<section id="stampa-senza-camera".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value)
        assertTrue(free.contains("Senza camera · 2 persone Bambini Davide Costa Maestri Marta Bianchi"), free)
        assertFalse(free.contains("Nicolò"), "lunch only is not in the list")
    }

    @Test
    fun `t11 c14 the sheet has no age, no presence, no contact and no note on health, and no count of the people of a room`() {
        val w = World()
        val html = w.marta.get(w.print).body()
        val text = words(html)

        for (word in listOf("anni", "Tutti i giorni", "Maestro", "333 777 4242", "3337774242", "Arachidi", "Cortisone", "Segnalazioni", "Recapito", "persone ·", "persona ·", "2016", "tel:")) {
            // «Maestri» is the label of the teachers, and «persone» is in «Senza camera · 3 persone» only: the words that are checked here are the ones that must not be.
            assertFalse(text.contains(word), "the sheet says '$word': $text")
        }
        assertFalse(text.contains("Maestro "), "the label is «Maestri»")
        // Nothing that changes anything: no form, no button but the one that prints, no link to a person.
        assertFalse(html.contains("<form") || html.contains("<select"), "it is a sheet, not a page that changes")
        assertEquals(1, Regex("""<button""").findAll(html.substringAfter("<main").substringBefore("</main>")).count(), "only the button that prints")
        assertTrue(html.contains("""data-azione="stampa"""") && html.contains("print:hidden"))
    }

    @Test
    fun `t11 c14 the page has the bar with the button and the help, and the sheet is the whole page as the prints of the arrivals are, kept by nobody`() {
        val w = World()
        val response = w.marta.get(w.print)
        val html = response.body()

        assertTrue(Regex("""<div class="mb-4 flex flex-col gap-3 print:hidden">[\s\S]*?</p>\s*</div>""").containsMatchIn(html), "the bar is not printed")
        assertTrue(Regex("""<button type="button" hidden data-azione="stampa"[^>]*>[\s\S]*?Stampa\s*</button>""").containsMatchIn(html), "the button is hidden in the page and app.js shows it")
        assertTrue(words(html).contains("Contiene i nomi di chi dorme in ogni camera: stampalo solo se ti serve e tienilo fra i maestri. Stampa su A4, in verticale."))
        assertTrue(html.contains("""<div class="mx-auto w-full max-w-5xl">"""), "the page is as wide as the print wants")
        assertTrue(response.response.getHeader("Cache-Control").orEmpty().contains("no-store"), "it is never kept")
        assertTrue(html.contains("""href="/convivenza/${w.setup.id}/camere"""") && words(html).contains("Torna alle camere"))
        // A room is never split between two sheets, and the block of who has no room may be, with its lines whole.
        assertEquals(4, Regex("""<section id="stampa-camera-\d+"[^>]*class="[^"]*\bbreak-inside-avoid\b""").findAll(html).count(), "each room is a block that does not split")
        assertFalse(Regex("""<section id="stampa-senza-camera"[^>]*class="[^"]*\bbreak-inside-avoid\b""").containsMatchIn(html), "the block of who has no room can split")
        assertTrue(html.contains("print:columns-2") && html.contains("break-inside-avoid"), "in two columns, with every line whole")
    }

    @Test
    fun `t11 c14 without rooms the address of the print says that there is nothing to print, and so does a finished gathering whose people were deleted`() {
        val setup = kit.start()
        val print = "/convivenza/${setup.id}/camere/stampa"
        val none = setup.marta.get(print)
        assertEquals(200, none.status())
        val noneText = words(none.body())
        assertTrue(noneText.contains("Nessuna camera da stampare Quando scrivi le camere, le trovi qui da stampare.") && noneText.contains("Torna alle camere"), noneText)
        assertFalse(none.body().contains("data-azione=\"stampa\""), "no button: nothing to print")

        val room = kit.room(setup.id, "Camera 1", 4)
        kit.put(kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi"), setup.id, room)
        kit.endedDaysAgo(setup.id, 1)
        assertTrue(words(setup.marta.get(print).body()).contains("Luca Rossi"), "until the job deletes them the sheet is there")
        dsl.deleteFrom(talita.jooq.tables.references.ROOM_CHILD).execute()
        val deleted = words(setup.marta.get(print).body())
        assertTrue(deleted.contains("Niente da stampare Chi dormiva dove è stato cancellato il"), deleted)
        assertFalse(deleted.contains("Luca"))
        assertEquals(404, setup.marta.get("/convivenza/${setup.id + 100_000}/camere/stampa").status())
    }

    @Test
    fun `t11 c14 every class of the page of the rooms and of the print has its rule in the stylesheet that is served`() {
        val w = World()
        kit.otherGathering("Convivenza di settembre 2026", daysFromToday = -400)
        val pages = listOf(w.print, "/convivenza/${w.setup.id}/camere", "/convivenza/${w.setup.id}/camere/nuova", "/convivenza/${w.setup.id}/camere/${w.one}/modifica", "/convivenza/${w.setup.id}/camere/${w.one}/togli")
            .map { w.marta.get(it).body() }
        val css = w.marta.get(Regex("""href="([^"]+\.css)"""").find(pages.first())!!.groupValues[1]).body()

        val missing = pages.flatMap { html -> Regex("""\sclass="([^"]*)"""").findAll(html).flatMap { it.groupValues[1].split(Regex("""\s+""")) }.toList() }
            .filter { it.isNotBlank() }.toSet()
            .filter { token -> !css.contains("." + token.replace(Regex("""([^a-zA-Z0-9_-])"""), """\\$1""")) }

        assertEquals(emptyList(), missing, "classes of the pages of the rooms with no rule in the CSS")
        // The rules of the page of A4 are the ones of the other prints: upright, with the margins of the guide.
        assertTrue(css.contains("size:A4 portrait") || css.contains("size: A4 portrait"), "the page of the print is A4 upright")
    }
}
