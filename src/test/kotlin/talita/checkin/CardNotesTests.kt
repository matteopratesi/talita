package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.body
import talita.gathering.status
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.PAYMENT
import talita.registration.HealthNotes
import talita.registration.Notes
import talita.registration.RegistrationFormat
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The telephone and the notes on health in the card (criteria 16-18): the number that can be called, the notes that are shown only in the answer to the touch that
 * wrote their reading and are closed in every other answer, and nothing that says whether a child has any. The class that reads the notes is the one of TALITA-5
 * ([HealthNotes]); its test (t5 c23) stays as it is. Names and notes are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class CardNotesTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val healthNotes: HealthNotes,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    private fun readingCount(): Int = dsl.fetchCount(HEALTH_NOTE_ACCESS)

    private val peanuts = Notes("Arachidi", null, "Antistaminico in caso di reazione, nello zaino.")

    /** The markup of a card with what makes two cards different taken out: the name, the number of the registration, the request code and the code against forgery. */
    private fun sameShape(html: String, id: Long, first: String): String =
        html.replace(Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""), "UUID")
            .replace(Regex("""name="_csrf" value="[^"]+""""), """name="_csrf" value="X"""")
            .replace(Regex("""(?<=/)$id(?=[/?"])"""), "ID")
            .replace(first, "NOME")

    /** The card as the page leaves it closed: the section of the notes, with the number of the registration taken out. */
    private fun closedSection(html: String, id: Long): String =
        Regex("""<section id="segnalazioni"[\s\S]*?</section>""").find(html)!!.value.replace("/arrivi/$id/", "/arrivi/ID/").replace(Regex("""name="_csrf" value="[^"]+""""), """name="_csrf" value="X"""")

    // ---- The telephone (criterion 16) ----------------------------------------------------------------------------------------

    @Test
    fun `t6 c16 the phone is shown as it was written, with Chiama that dials only the digits and a plus at the start`() {
        val setup = kit.start()
        val plain = kit.rows.register(setup.id, kit.account(), "Anna", "Rossi", phone = "333 123 4567")
        val international = kit.rows.register(setup.id, kit.account(), "Luca", "Verdi", phone = "+39 333-123.4567")

        val first = setup.marta.card(plain)
        val second = setup.marta.card(international)

        assertTrue(words(first).contains("Recapito del genitore 333 123 4567 Chiama 333 123 4567"), words(first))
        assertTrue(Regex("""<a href="tel:3331234567" hx-boost="false"[^>]*>""").containsMatchIn(first), "the link dials the digits")
        assertTrue(words(second).contains("Recapito del genitore +39 333-123.4567 Chiama +39 333-123.4567"), words(second))
        assertTrue(Regex("""<a href="tel:\+393331234567" hx-boost="false"[^>]*>""").containsMatchIn(second), "and a plus at the start, nothing else")
        // The name of the button says whom it calls, the icon is only for the eyes, and the area of the touch is the one of the buttons (48 px, at least 44).
        val link = Regex("""<a href="tel:3331234567"[^>]*>[\s\S]*?</a>""").find(first)!!.value
        assertTrue(link.contains("min-h-12") && link.contains("min-w-34"), link)
        assertTrue(Regex("""<svg[^>]*aria-hidden="true"""").containsMatchIn(link), "the icon is hidden from the screen reader")
        assertTrue(link.contains("""<span class="sr-only"> 333 123 4567</span>"""), link)
    }

    @Test
    fun `t6 c16 the number to dial is made only of digits, with a plus only when the number starts with one`() {
        assertEquals("3331234567", CheckinFormat.dialable("333 123 4567"))
        assertEquals("+393331234567", CheckinFormat.dialable("+39 333-123.4567"))
        assertEquals("+393331234567", CheckinFormat.dialable("  +39 (333) 123 4567 "))
        assertEquals("3331234567", CheckinFormat.dialable("333 123 4567 +"))
        assertEquals("333123", CheckinFormat.dialable("333+123"))
        assertEquals("", CheckinFormat.dialable("non c’è"))
        assertEquals("+", CheckinFormat.dialable("+"))
    }

    // ---- The notes, closed (criteria 17, 18) ------------------------------------------------------------------------------------

    private inner class Scenario {
        val setup = kit.start()
        val at: OffsetDateTime = OffsetDateTime.now(clock).minusDays(20)
        val parent = kit.account()
        val anna = kit.rows.register(setup.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = peanuts, at = at)
        val luca = kit.rows.register(setup.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), at = at)
        val nicolo = kit.rows.register(setup.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = setup.martaId, notes = Notes(null, "Lattosio", null), notesOnPaper = true, at = at)
    }

    @Test
    fun `t6 c17 the card is closed, says that they are health data and that every reading is written, and has a button that needs the network`() {
        val s = Scenario()

        val html = s.setup.marta.card(s.anna)

        assertTrue(words(html).contains("Segnalazioni Sono dati sulla salute. Ogni volta che le mostri, resta scritto chi le ha lette e quando. Mostra le segnalazioni Apertura… Per vederle serve la rete: la lettura si registra prima di mostrarle."), words(html))
        assertTrue(html.contains("""<form method="post" action="/arrivi/${s.anna}/segnalazioni" hx-post="/arrivi/${s.anna}/segnalazioni" hx-target="#scheda" hx-swap="outerHTML""""))
        assertFalse(html.contains("Arachidi") || html.contains("Antistaminico"), "nothing of the notes in a closed card")
        assertFalse(html.contains("""id="segnalazioni" aria-labelledby="segnalazioni-titolo" tabindex"""), "a closed card does not take the focus")
        assertEquals(0, readingCount(), "looking at the card is not a reading")
    }

    @Test
    fun `t6 c17 the touch writes the reading of the teacher and then shows the notes with who read and when, and the consent`() {
        val s = Scenario()

        val answer = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/segnalazioni", emptyMap())

        assertEquals(200, answer.status())
        val html = answer.body()
        assertTrue(html.trim().startsWith("<div id=\"scheda\">"), html.take(200))
        val readings = healthNotes.readings(s.anna)
        assertEquals(1, readings.size)
        assertEquals(s.setup.martaId, readings.single().teacherAccountId)
        val text = words(html)
        assertTrue(text.contains("Segnalazioni Lettura registrata: Marta Bianchi, ${CheckinFormat.momentWithYear(readings.single().at)}."), text)
        assertTrue(text.contains("Allergie Arachidi Farmaci Antistaminico in caso di reazione, nello zaino. Consenso dato dal genitore il ${RegistrationFormat.moment(s.at)}. Nascondi le segnalazioni"), text)
        assertFalse(text.contains("Intolleranze"), "what is not written is not shown")
        assertFalse(text.contains("Mostra le segnalazioni"), "the button is gone while they are shown")
        assertTrue(Regex("""<section id="segnalazioni"[^>]*tabindex="-1" data-fuoco=""""").containsMatchIn(html), "the notes take the focus, so the screen reader reads them")
        // The paper consent says where it was given.
        val paper = s.setup.marta.touch(s.setup, "/arrivi/${s.nicolo}/segnalazioni", emptyMap()).body()
        assertTrue(words(paper).contains("Intolleranze Lattosio Il genitore ha dato il consenso sul modulo cartaceo."), words(paper))
        assertEquals(2, readingCount())
    }

    @Test
    fun `t6 c17 a child with no notes gets Nessuna segnalazione, and the reading is written all the same`() {
        val s = Scenario()

        val html = s.setup.marta.touch(s.setup, "/arrivi/${s.luca}/segnalazioni", emptyMap()).body()

        assertTrue(words(html).contains("Segnalazioni Lettura registrata: Marta Bianchi, "), words(html))
        assertTrue(words(html).contains("Nessuna segnalazione."), words(html))
        assertFalse(words(html).contains("Consenso"))
        assertEquals(1, healthNotes.readings(s.luca).size, "the register does not say whether there was anything")
    }

    @Test
    fun `t6 c17 what is written is shown as text and never as markup`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Anna", "Rossi", notes = Notes("<b>Arachidi</b> <script>alert(1)</script>", null, null))

        val html = setup.marta.touch(setup, "/arrivi/$id/segnalazioni", emptyMap()).body()

        assertFalse(html.contains("<b>Arachidi") || html.contains("<script>alert"), "the notes are not markup")
        assertTrue(html.contains("&lt;b&gt;Arachidi&lt;/b&gt;"), "they are shown as the text that was written")
    }

    @Test
    fun `t6 c17 after any other action the notes are closed again, and no answer of the arrivals has them without a reading that was written`() {
        val s = Scenario()
        val marta = s.setup.marta
        val everything = mutableListOf<String>()
        fun seen(html: String) {
            everything += html
        }
        // Every page and every touch of the arrivals, for a child who has notes (Anna) and for one who has them on paper (Nicolò).
        seen(marta.get("/arrivi").body())
        seen(marta.get("/arrivi?giorno=${s.setup.yesterday}").body())
        seen(marta.card(s.anna))
        seen(marta.card(s.nicolo, s.setup.tomorrow))
        seen(marta.touch(s.setup, "/arrivi/${s.anna}/presenze", mapOf("da" to "scheda")).body())
        seen(marta.touch(s.setup, "/arrivi/${s.anna}/presenze", mapOf("da" to "scheda")).body())
        seen(marta.touch(s.setup, "/arrivi/${s.anna}/presenze/togli", mapOf("da" to "scheda")).body())
        seen(marta.touch(s.setup, "/arrivi/${s.anna}/presenze", emptyMap()).body())
        seen(marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "10", "richiesta" to UUID.randomUUID().toString())).body())
        seen(marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "no", "richiesta" to UUID.randomUUID().toString())).body())
        val payment = dsl.select(PAYMENT.ID).from(PAYMENT).fetchSingle(PAYMENT.ID)!!
        seen(marta.get("/arrivi/${s.anna}/pagamenti/$payment/togli?giorno=${s.setup.today}").body())
        seen(marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti/$payment/togli", emptyMap()).body())
        seen(marta.touch(s.setup, "/arrivi/${s.nicolo}/manleva", emptyMap()).body())
        seen(marta.touch(s.setup, "/arrivi/${s.nicolo}/rimetti", emptyMap()).body())
        seen(marta.touch(s.setup, "/arrivi/${s.anna}/presenze", emptyMap(), viaHtmx = false).body())
        seen(marta.get("/arrivi/${s.anna}?giorno=${s.setup.today}").body())

        for (html in everything) {
            assertFalse(html.contains("Arachidi") || html.contains("Antistaminico") || html.contains("Lattosio"), "an answer has the notes with no reading: ${words(html).take(200)}")
            assertTrue(!html.contains("Lettura registrata:"))
        }
        assertEquals(0, readingCount())

        // After a reading, the very next thing (the list, the card, a touch) is closed again, and no new reading is written by it.
        assertTrue(words(marta.touch(s.setup, "/arrivi/${s.anna}/segnalazioni", emptyMap()).body()).contains("Arachidi"))
        assertEquals(1, readingCount())
        val after = listOf(
            marta.card(s.anna),
            marta.get("/arrivi").body(),
            marta.touch(s.setup, "/arrivi/${s.anna}/presenze", mapOf("da" to "scheda")).body(),
            marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "5", "richiesta" to UUID.randomUUID().toString())).body(),
        )
        for (html in after) {
            assertFalse(html.contains("Arachidi"), "closed again")
        }
        assertEquals(1, readingCount(), "nothing but the touch on Mostra le segnalazioni writes a reading")
    }

    @Test
    fun `t6 c17 the link Nascondi le segnalazioni leads to the card closed and writes nothing`() {
        val s = Scenario()
        val shown = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/segnalazioni", emptyMap()).body()
        val hide = Regex("""<a href="(/arrivi/${s.anna}\?giorno=[0-9-]+)"[^>]*>[\s\S]{0,900}?Nascondi le segnalazioni""").find(shown)!!.groupValues[1]

        val closed = s.setup.marta.get(hide)

        assertEquals(200, closed.status())
        assertFalse(closed.body().contains("Arachidi"))
        assertTrue(words(closed.body()).contains("Mostra le segnalazioni"))
        assertEquals(1, readingCount())
    }

    @Test
    fun `t6 c17 without JavaScript the touch answers with the whole page that has the notes, and the notes are not kept for the next one`() {
        val s = Scenario()

        val answer = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/segnalazioni", emptyMap(), viaHtmx = false)

        assertEquals(200, answer.status())
        assertTrue(answer.body().contains("<html"), "a whole page, with the shell")
        assertTrue(words(answer.body()).contains("Allergie Arachidi"), words(answer.body()))
        assertEquals(1, readingCount())
        val next = s.setup.marta.card(s.anna)
        assertFalse(next.contains("Arachidi"))
        assertEquals(1, readingCount())
    }

    @Test
    fun `t6 c17 if the reading cannot be written nothing is shown and the answer is an error, never the notes`() {
        val s = Scenario()
        val csrf = MockBrowser.csrfIn(s.setup.marta.get("/arrivi").body())!!
        dsl.execute("alter table health_note_access add constraint test_cannot_write check (false) not valid")
        try {
            val failure = assertFailsWith<Exception> { s.setup.marta.post("/arrivi/${s.anna}/segnalazioni", mapOf("giorno" to s.setup.today.toString()), csrf, htmx()) }
            assertFalse(generateSequence<Throwable>(failure) { it.cause }.any { (it.message ?: "").contains("Arachidi") }, "the failure does not carry the notes")
        } finally {
            dsl.execute("alter table health_note_access drop constraint test_cannot_write")
        }
        assertEquals(0, readingCount())
        // The card is as it was: closed, and the button is still there for when the network comes back.
        assertTrue(words(s.setup.marta.card(s.anna)).contains("Mostra le segnalazioni"))
    }

    // ---- Nothing says whether there are any (criterion 18) -------------------------------------------------------------------------

    @Test
    fun `t6 c18 the closed card of a child with notes is the same as the one of a child with none, and so is the row of the list`() {
        val setup = kit.start()
        val at = OffsetDateTime.now(clock).minusDays(20)
        val withNotes = kit.rows.register(setup.id, kit.account(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = peanuts, at = at)
        val without = kit.rows.register(setup.id, kit.account(), "Elena", "Rossi", LocalDate.of(2019, 1, 9), at = at)

        val a = setup.marta.card(withNotes)
        val b = setup.marta.card(without)

        assertEquals(closedSection(a, withNotes), closedSection(b, without), "the card of the notes")
        assertEquals(words(a).replace("Anna", "NOME"), words(b).replace("Elena", "NOME"), "the whole card, but the name")
        assertEquals(sameShape(a, withNotes, "Anna"), sameShape(b, without, "Elena"), "the same markup, but for the name, the number of the registration and the codes of the forms")
        // The row of the list and the summary: the labels are the same.
        val list = words(setup.marta.get("/arrivi").body())
        assertTrue(Regex("""Elena Rossi\s+Da pagare""").containsMatchIn(list) && Regex("""Anna Rossi\s+Da pagare""").containsMatchIn(list), list)
        assertFalse(list.contains("segnalazion"), "the list does not even say the word")
    }
}
