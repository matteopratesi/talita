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
import talita.gathering.GatheringFormat
import talita.gathering.body
import talita.gathering.heading
import talita.gathering.status
import talita.gathering.title
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.registration.Notes
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three prints of the arrivals (criteria 18, 23, 25, 26): the register, the list for the venue and the sheet for the teachers, as pages and as the rows that the
 * server divides into sheets. What is on paper is what is in the markup: the hiding of the shell and the black on white are CSS, tried by the test of the browser. Names,
 * numbers and notes are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PrintPagesTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    /** The tables of the document, one for every sheet (the totals are not among them). */
    private fun tables(html: String): List<String> = Regex("""<table class="tabella-stampa[\s\S]*?</table>""").findAll(html).map { it.value }.toList()

    /** The rows of the body of a table, as the words that a person reads in them. */
    private fun rows(table: String): List<String> {
        val body = Regex("""<tbody>([\s\S]*?)</tbody>""").find(table)!!.groupValues[1]
        return Regex("""<tr[\s\S]*?</tr>""").findAll(body).map { words(it.value) }.toList()
    }

    private fun captions(html: String): List<String> = Regex("""<caption>([^<]*)</caption>""").findAll(html).map { it.groupValues[1] }.toList()

    private fun readingCount(): Int = dsl.fetchCount(HEALTH_NOTE_ACCESS)

    private fun weekday(day: LocalDate): String = CheckinFormat.weekdayName(day)

    /**
     * Anna and Luca Rossi (the same parent, the same phone), Nicolò Ferri from paper with the form to come, Elena Marino from paper with the form arrived, Sara Neri in
     * forse, two Giulia Ferri who are born the same day (one in forse), and Chiara Fontana, withdrawn. Anna has notes on health, and none of them is in a print.
     */
    private inner class Scenario(first: Long = -1, last: Long = 1) {
        val setup = kit.start(first, last)
        val parent = kit.account()
        val anna = kit.rows.register(setup.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", "Lattosio", "Antistaminico nello zaino."))
        val luca = kit.rows.register(setup.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), days = mapOf(setup.today to "full", setup.tomorrow to "lunch"))
        val nicolo = kit.rows.register(setup.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), phone = "333 987 6543", enteredBy = setup.martaId)
        val elena = kit.rows.register(setup.id, null, "Elena", "Marino", LocalDate.of(2016, 10, 1), phone = "+39 333-123.4570", enteredBy = setup.martaId, paperReceived = true)
        val sara = kit.rows.register(setup.id, kit.account(), "Sara", "Neri", LocalDate.of(2017, 3, 3), phone = "333 123 4512", status = RegistrationStatus.maybe)
        val giulia = kit.rows.register(setup.id, kit.account(), "Giulia", "Ferri", LocalDate.of(2018, 5, 5), phone = "333 123 4503")
        val giuliaAgain = kit.rows.register(setup.id, kit.account(), "Giulia", "Ferri", LocalDate.of(2018, 5, 5), phone = "333 123 4504", status = RegistrationStatus.maybe)
        val chiara = kit.rows.register(setup.id, kit.account(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), phone = "333 123 4599", status = RegistrationStatus.withdrawn)

        init {
            kit.presence(anna, setup.today, setup.martaId)
            kit.presence(luca, setup.yesterday, setup.paoloId)
            kit.payment(anna, 6000, setup.martaId)
            kit.payment(luca, 1000, setup.paoloId)
            kit.payment(chiara, 1500, setup.martaId)
        }
    }

    /** [count] children of a gathering, every day and confirmed, so that the rows are enough for more than one sheet. */
    private fun crowd(gathering: Long, count: Int) {
        for (number in 1..count) {
            kit.rows.register(gathering, kit.account(), "Bambino", "Prova%02d".format(number), LocalDate.of(2016, 1, 1).plusDays(number.toLong()))
        }
    }

    // ---- The register (criterion 23) --------------------------------------------------------------------------------------------

    @Test
    fun `t6 c23 the register has the title, the dates, the hour of the print and a row for every registration that is not withdrawn, by surname`() {
        val s = Scenario()

        val page = s.setup.marta.get("/arrivi/registro")

        assertEquals(200, page.status())
        val html = page.body()
        assertEquals("Registro arrivi · Talità", title(html))
        assertEquals("Registro arrivi", heading(html))
        val text = words(html)
        assertTrue(text.contains("Convivenza di settembre 2027 · ${GatheringFormat.range(s.setup.yesterday, s.setup.tomorrow)}"), text)
        assertTrue(Regex("""Stampato il \d{1,2} [a-zà-ù]+ \d{4} alle \d{1,2}:\d{2}\.""").containsMatchIn(text), text)
        assertTrue(text.contains("G giornata · P solo pranzo · – giorno non previsto. La X nella casella è una presenza già segnata in Talità."), text)
        assertEquals(listOf("Registro arrivi · pagina 1 di 1"), captions(html))
        val names = rows(tables(html).single()).filterNot { it.isEmpty() }.map { Regex("""^(\S+ \S+)""").find(it)?.groupValues?.get(1) }.take(7)
        assertEquals(listOf("Giulia Ferri", "Giulia Ferri", "Nicolò Ferri", "Elena Marino", "Sara Neri", "Anna Rossi", "Luca Rossi"), names, "by surname and name, and Chiara, who is withdrawn, is not there")
        assertFalse(text.contains("Chiara Fontana"))
    }

    @Test
    fun `t6 c23 each row has the days with G or P or a dash and the box, what is due, what was paid, the manleva and the notes of the app`() {
        val s = Scenario()
        val day = weekday(s.setup.yesterday)

        val rows = rows(tables(s.setup.marta.get("/arrivi/registro").body()).single())

        assertEquals(
            listOf(
                "Giulia Ferri G G G ${euro(6000)} Online Possibile doppione",
                "Giulia Ferri G G G ${euro(6000)} Online In forse · Possibile doppione",
                "Nicolò Ferri G G G ${euro(6000)} da ricevere Dal cartaceo",
                "Elena Marino G G G ${euro(6000)} Cartacea Dal cartaceo",
                "Sara Neri G G G ${euro(6000)} Online In forse",
                // Anna was marked today, the middle day: the X is in the box of her middle day.
                "Anna Rossi G G X presente G ${euro(6000)} ${euro(6000)} Online",
                // Luca does not come yesterday (the dash) and was marked that day all the same: the X, and the note that says so. He pays for two days: 25 and 10.
                "Luca Rossi – X presente G P ${euro(3500)} ${euro(1000)} Online $day non previsto",
            ),
            rows.take(7),
        )
    }

    @Test
    fun `t6 c23 the register ends with five blank rows and the totals, with the amount collected by hand left to write`() {
        val s = Scenario()
        val html = s.setup.marta.get("/arrivi/registro").body()

        assertEquals(5, Regex("""<tr class="riga-vuota">""").findAll(html).count(), "five blank rows")
        // Due from the confirmed (Anna 60, Luca 35, Nicolò 60, Elena 60, Giulia 60), from the ones in forse (Sara and the other Giulia, 60 each), and what was collected,
        // that of the registration that was withdrawn too: 60 + 10 + 15.
        val totals = words(Regex("""<table class="totali-stampa">[\s\S]*?</table>""").find(html)!!.value)
        assertEquals(
            "Totali del registro Totale dovuto dalle iscrizioni confermate ${euro(27500)} Dovuto da chi è in forse (a parte) ${euro(12000)} " +
                "Incassato registrato in Talità (anche delle iscrizioni ritirate) ${euro(8500)} Incassato a mano Da scrivere a mano",
            totals,
        )
        assertTrue(Regex("""<td class="scrivi">""").containsMatchIn(html), "a cell that is left to the pen")
        assertTrue(words(html).contains("Il registro si conserva fra i documenti della convivenza. Non ha recapiti né segnalazioni."))
        assertEquals(1, Regex("""<table class="totali-stampa">""").findAll(html).count())
    }

    @Test
    fun `t6 c23 the columns of the register are the ones of the design, and the header of the days says the weekday to the screen reader`() {
        val s = Scenario()
        val html = s.setup.marta.get("/arrivi/registro").body()

        val head = Regex("""<thead>[\s\S]*?</thead>""").find(tables(html).single())!!.value
        val ven = CheckinFormat.weekdayAbbreviation(s.setup.yesterday)
        assertTrue(
            words(head).contains("Bambino ${letterOf(s.setup.yesterday)} ${CheckinFormat.dayNumber(s.setup.yesterday)} $ven ${CheckinFormat.dayNumber(s.setup.yesterday)} "),
            words(head),
        )
        assertEquals(listOf("col-nome", "col-giorno", "col-giorno", "col-giorno", "col-dovuta", "col-pagato", "col-manleva", "col-note"), Regex("""<th class="([a-z-]+)" scope="col">""").findAll(head).map { it.groupValues[1] }.toList())
        assertTrue(words(head).endsWith("Dovuta Pagato Manleva Note"), words(head))
        assertFalse(html.contains("tabella-stampa--7"), "three days are not squeezed")
    }

    private fun letterOf(day: LocalDate): String = when (day.dayOfWeek.value) {
        1 -> "L"
        2 -> "Ma"
        3 -> "Me"
        4 -> "G"
        5 -> "V"
        6 -> "S"
        else -> "D"
    }

    @Test
    fun `t6 c18 c23 nothing in the register, the list for the venue or the sheet says that a child has notes on health, and printing writes no reading`() {
        val s = Scenario()

        for (path in listOf("/arrivi/registro", "/arrivi/elenco", "/arrivi/elenco?giorno=${s.setup.today}", "/arrivi/foglio")) {
            // The register says, in its closing line, that it has no notes on health: that sentence is the only place for the word.
            val html = s.setup.marta.get(path).body().replace("Non ha recapiti né segnalazioni.", "")

            for (word in listOf("Arachidi", "Lattosio", "Antistaminico", "segnalazion", "allergi", "intolleranz", "farmac", "salute")) {
                assertFalse(html.lowercase().contains(word.lowercase()), "$path has '$word'")
            }
        }
        assertEquals(0, readingCount(), "the prints read no notes, so they write no reading")
    }

    @Test
    fun `t6 c23 the register has no phone number`() {
        val s = Scenario()

        val html = s.setup.marta.get("/arrivi/registro").body()

        for (phone in listOf("333 123 4567", "333 987 6543", "333 123 4512", "333-123.4570", "333 123 4503")) assertFalse(html.contains(phone), phone)
    }

    // ---- The sheets of paper (criteria 23, 24) -------------------------------------------------------------------------------

    @Test
    fun `t6 c24 the register of many children is divided into sheets, each with its own table and its header, and the totals are under the last`() {
        val s = Scenario()
        crowd(s.setup.id, 19)

        val html = s.setup.marta.get("/arrivi/registro").body()

        val tables = tables(html)
        // 7 children of the scenario and 19 more are 26, and the five blank rows are 31: 20 on the first sheet and 11 on the second.
        assertEquals(2, tables.size)
        assertEquals(listOf("Registro arrivi · pagina 1 di 2", "Registro arrivi · pagina 2 di 2"), captions(html))
        assertEquals(listOf(20, 11), tables.map { rows(it).size })
        assertTrue(tables.all { it.contains("<thead>") && it.contains("""scope="col">Bambino</th>""") }, "every sheet has its header")
        assertEquals(1, Regex("""<table class="totali-stampa">""").findAll(html).count())
        assertTrue(html.indexOf("""<table class="totali-stampa">""") > html.indexOf("pagina 2 di 2"), "the totals are on the last sheet")
        assertEquals(2, Regex("""<div class="blocco-stampa">""").findAll(html).count(), "one block for each sheet")
        assertEquals(5, Regex("""<tr class="riga-vuota">""").findAll(html).count())
        // Every registration that is not withdrawn is in one of the sheets, once.
        assertEquals(26, Regex("""<td class="nome">""").findAll(html).count())
    }

    @Test
    fun `t6 c24 when the last sheet has no room for the totals they have a sheet of their own, with the header of the columns and the caption`() {
        // Seven days: 16 rows on the first sheet, 20 on the others and 15 under which the totals fit; 27 children and the five blank rows are 32.
        val s = Scenario(first = -3, last = 3)
        crowd(s.setup.id, 20)

        val html = s.setup.marta.get("/arrivi/registro").body()

        val tables = tables(html)
        assertEquals(listOf("Registro arrivi · pagina 1 di 3", "Registro arrivi · pagina 2 di 3", "Registro arrivi · pagina 3 di 3"), captions(html))
        assertEquals(listOf(16, 16, 0), tables.map { rows(it).size })
        assertTrue(tables.all { it.contains("tabella-stampa--7") }, "seven days are squeezed")
        assertTrue(tables.all { it.contains("<thead>") }, "the sheet of the totals has the header too")
        assertEquals(7, Regex("""col-giorno""").findAll(tables.last()).count())
        assertTrue(html.indexOf("""<table class="totali-stampa">""") > html.indexOf("pagina 3 di 3"), "the totals are on the sheet of their own")
    }

    @Test
    fun `t6 c24 the sheet of the totals is not made when they fit, with seven days too`() {
        val s = Scenario(first = -3, last = 3)
        crowd(s.setup.id, 19)

        val html = s.setup.marta.get("/arrivi/registro").body()

        // 26 children and the five blank rows are 31: 16 and 15, and the 15 leave the room of the totals.
        assertEquals(listOf("Registro arrivi · pagina 1 di 2", "Registro arrivi · pagina 2 di 2"), captions(html))
        assertEquals(listOf(16, 15), tables(html).map { rows(it).size })
    }

    @Test
    fun `t6 c24 the bar with the button is not printed, the button is in the page hidden for app js to show it, and the page is wide`() {
        val s = Scenario()

        for (path in listOf("/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            val html = s.setup.marta.get(path).body()

            val bar = Regex("""<div class="mb-4 flex flex-col gap-3 print:hidden">[\s\S]*?</p>\s*</div>""").find(html)
            assertTrue(bar != null, "$path: the bar is not printed")
            assertTrue(Regex("""<button type="button" hidden data-azione="stampa"[^>]*>[\s\S]*?Stampa\s*</button>""").containsMatchIn(bar!!.value), "$path: the button is hidden in the page, and app.js shows it")
            assertTrue(html.contains("""<div class="mx-auto w-full max-w-5xl">"""), "$path: the page is as wide as the tables want")
            assertFalse(Regex("""<script(?![^>]*\bsrc=)""").containsMatchIn(html), "$path: no inline script in the page")
            assertFalse(html.contains("style=\""), "$path: no style in the page")
        }
        // The other pages keep their width.
        assertTrue(s.setup.marta.get("/arrivi").body().contains("""<div class="mx-auto w-full max-w-3xl">"""))
    }

    @Test
    fun `t6 c24 the prints are not kept by the browser or by a proxy`() {
        val s = Scenario()

        for (path in listOf("/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            val cache = s.setup.marta.get(path).response.getHeader("Cache-Control").orEmpty()

            assertTrue(cache.contains("no-store"), "$path: Cache-Control is '$cache'")
        }
    }

    @Test
    fun `t6 c24 every class of the three prints has its rule in the stylesheet that is served, so none is left that the scanner of Tailwind did not see`() {
        val s = Scenario()
        crowd(s.setup.id, 22)
        val pages = listOf("/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio", "/arrivi/elenco?giorno=${s.setup.today}").map { s.setup.marta.get(it).body() }
        val css = s.setup.marta.get(Regex("""href="([^"]+\.css)"""").find(pages.first())!!.groupValues[1]).body()

        // A marker for the tests that has no rule: the blank rows of the tables.
        val markers = setOf("riga-vuota")
        val missing = pages.flatMap { html -> Regex("""\sclass="([^"]*)"""").findAll(html).flatMap { it.groupValues[1].split(Regex("""\s+""")) }.toList() }
            .filter { it.isNotBlank() && it !in markers }.toSet()
            .filter { token -> !css.contains("." + token.replace(Regex("""([^a-zA-Z0-9_-])"""), """\\$1""")) }

        assertEquals(emptyList(), missing, "classes of the prints with no rule in the CSS")
    }

    // ---- The list for the venue (criterion 25) --------------------------------------------------------------------------------

    @Test
    fun `t6 c25 the list for the venue has the children with a confirmed registration and the teachers in custody, surname and name and nothing else`() {
        val s = Scenario()

        val page = s.setup.marta.get("/arrivi/elenco")

        assertEquals(200, page.status())
        val html = page.body()
        assertEquals("Elenco per la struttura · Talità", title(html))
        assertEquals("Elenco per la struttura", heading(html))
        val text = words(html)
        assertTrue(text.contains("Convivenza di settembre 2027 · ${GatheringFormat.range(s.setup.yesterday, s.setup.tomorrow)}"), text)
        // Confirmed: Anna, Luca, Nicolò, Elena and the first Giulia. Not Sara and the second Giulia (in forse), not Chiara (withdrawn).
        assertTrue(text.contains("Bambini con l’iscrizione confermata · 5"), text)
        assertTrue(text.contains("Maestri · 2"), text)
        val tables = tables(html)
        assertEquals(listOf(listOf("Ferri Giulia", "Ferri Nicolò", "Marino Elena", "Rossi Anna", "Rossi Luca"), listOf("Bianchi Marta", "Verdi Paolo", "", "", "")), tables.map { rows(it) })
        assertEquals(3, Regex("""<tr class="riga-vuota">""").findAll(html).count(), "three blank rows for the other adults")
        assertEquals(2, Regex("""<th[^>]*scope="col">(Cognome|Nome)</th>""").findAll(tables.first()).count())
        assertTrue(text.contains("Gli adulti in più si scrivono a mano nelle righe vuote."), text)
        assertTrue(Regex("""Stampato il \d{1,2} [a-zà-ù]+ \d{4} alle \d{1,2}:\d{2}\.""").containsMatchIn(text), text)
        assertEquals(listOf("Elenco per la struttura · pagina 1 di 1"), captions(html))
        // Nothing but names: no number, no amount, no state, no day.
        for (other in listOf("333", "€", "Online", "Dal cartaceo", "In forse", "Possibile doppione")) assertFalse(text.contains(other), other)
        assertFalse(text.contains("Giulia Serra"), "a teacher who is not a custodian is not in the list")
        assertFalse(text.contains("Chiara"))
    }

    @Test
    fun `t6 c25 with a day chosen the children are the ones who are present that day, and the choice has a day for each day of the gathering`() {
        val s = Scenario()
        kit.presence(s.sara, s.setup.today, s.setup.martaId)
        kit.presence(s.chiara, s.setup.today, s.setup.paoloId)

        val html = s.setup.marta.get("/arrivi/elenco?giorno=${s.setup.today}").body()

        val text = words(html)
        // Anna, and Sara who is in forse; Chiara was withdrawn, and Luca was marked yesterday.
        assertTrue(text.contains("Bambini presenti ${CheckinFormat.longDay(s.setup.today)} · 2"), text)
        assertEquals(listOf("Neri Sara", "Rossi Anna"), rows(tables(html).first()))
        assertFalse(text.contains("Chiara"))
        // The choice: a select with «Tutte le iscrizioni confermate» and a day for each day, and the chosen one is selected; the form is a GET with nothing personal.
        assertTrue(text.contains("Chi mettere fra i bambini Tutte le iscrizioni confermate Presenti ${CheckinFormat.longDay(s.setup.yesterday)} Presenti ${CheckinFormat.longDay(s.setup.today)} Presenti ${CheckinFormat.longDay(s.setup.tomorrow)} Aggiorna l’elenco"), text)
        assertTrue(html.contains("""<form method="get" action="/arrivi/elenco""""))
        assertTrue(Regex("""<option value="${s.setup.today}" selected>""").containsMatchIn(html), "the day of the address is the one that is chosen")
        assertTrue(Regex("""<option value="" selected>""").containsMatchIn(s.setup.marta.get("/arrivi/elenco").body()), "without a day, all the confirmed ones")
        assertTrue(Regex("""<label for="chi"[^>]*>Chi mettere fra i bambini</label>""").containsMatchIn(html))
        assertTrue(Regex("""<select id="chi" name="giorno"""").containsMatchIn(html))
    }

    @Test
    fun `t6 c25 a day without presences says so and leads back to the confirmed ones, and a day that is not one of the gathering is a page that does not exist`() {
        val s = Scenario()

        val none = s.setup.marta.get("/arrivi/elenco?giorno=${s.setup.tomorrow}")

        assertEquals(200, none.status())
        val text = words(none.body())
        assertTrue(text.contains("Nessun bambino presente ${CheckinFormat.longDay(s.setup.tomorrow)} L’elenco dei presenti si riempie man mano che segni le presenze. Puoi scegliere un altro giorno o tutte le iscrizioni confermate. Vedi le iscrizioni confermate"), text)
        assertTrue(none.body().contains("""href="/arrivi/elenco""""))
        assertFalse(none.body().contains("data-azione=\"stampa\""), "nothing to print")
        for (day in listOf(s.setup.today.plusDays(9).toString(), "ieri", "2027-13-45", s.setup.today.minusDays(5).toString())) {
            assertEquals(404, s.setup.marta.get("/arrivi/elenco?giorno=$day").status(), day)
        }
        // An empty choice is the same as none.
        assertEquals(200, s.setup.marta.get("/arrivi/elenco?giorno=").status())
    }

    @Test
    fun `t6 c25 a gathering with no confirmed registration has the empty state, with the way back`() {
        val setup = kit.start()
        kit.rows.register(setup.id, kit.account(), "Sara", "Neri", status = RegistrationStatus.maybe)

        val html = setup.marta.get("/arrivi/elenco").body()

        assertTrue(words(html).contains("Nessuna iscrizione confermata L’elenco ha una riga per ogni iscrizione confermata. Quando i genitori iscrivono i bambini, lo trovi qui. Torna agli arrivi"), words(html))
        assertTrue(html.contains("""href="/arrivi""""))
    }

    @Test
    fun `t6 c24 c25 the list for the venue keeps the teachers under the children when they fit and gives them a sheet when they do not`() {
        val s = Scenario()
        crowd(s.setup.id, 22)

        // 5 + 22 = 27 children: 20 and 7, and the teachers under the 7.
        val two = s.setup.marta.get("/arrivi/elenco").body()
        assertEquals(listOf("Elenco per la struttura · pagina 1 di 2", "Elenco per la struttura · pagina 2 di 2"), captions(two))
        assertEquals(listOf(20, 7, 5), tables(two).map { rows(it).size })
        assertFalse(two.contains("print:break-before-page"), "the teachers follow the children on the same sheet")

        // 5 + 40 = 45 children: 20 and 25, and 25 leave no room for the teachers: a sheet of their own, with the caption.
        crowd(s.setup.id, 18)
        val three = s.setup.marta.get("/arrivi/elenco").body()
        assertEquals(listOf("Elenco per la struttura · pagina 1 di 3", "Elenco per la struttura · pagina 2 di 3", "Elenco per la struttura · pagina 3 di 3"), captions(three))
        assertEquals(listOf(20, 25, 5), tables(three).map { rows(it).size })
        assertTrue(Regex("""<div class="print:break-before-page">\s*<h2[^>]*>Maestri · 2</h2>""").containsMatchIn(three), "the heading and the table of the teachers start a sheet")
        assertTrue(Regex("""<h2[^>]*>Bambini con l’iscrizione confermata · 45</h2>""").containsMatchIn(three))
    }

    // ---- The sheet for the teachers (criterion 26) -----------------------------------------------------------------------------

    @Test
    fun `t6 c26 the sheet for the teachers has the child and the contact of the parent as it was written, the hour of the print and the sentence to destroy it`() {
        val s = Scenario()

        val page = s.setup.marta.get("/arrivi/foglio")

        assertEquals(200, page.status())
        val html = page.body()
        assertEquals("Foglio per i maestri · Talità", title(html))
        assertEquals("Foglio per i maestri", heading(html))
        val text = words(html)
        assertTrue(text.contains("Convivenza di settembre 2027 · ${GatheringFormat.range(s.setup.yesterday, s.setup.tomorrow)}"), text)
        assertTrue(Regex("""Stampato il \d{1,2} [a-zà-ù]+ \d{4} alle \d{1,2}:\d{2}\.""").containsMatchIn(text), text)
        assertTrue(text.contains("Questo foglio contiene i recapiti dei genitori. Tienilo fra i maestri e distruggilo alla fine della convivenza."), text)
        assertTrue(text.contains("Alla fine della convivenza questo foglio va distrutto."), text)
        assertTrue(Regex("""<p class="riquadro-riservato">""").containsMatchIn(html), "the sentence is in a box with a border, which prints in black")
        assertEquals(
            listOf(
                "Giulia Ferri 333 123 4503", "Giulia Ferri 333 123 4504", "Nicolò Ferri 333 987 6543", "Elena Marino +39 333-123.4570", "Sara Neri 333 123 4512",
                "Anna Rossi 333 123 4567", "Luca Rossi 333 123 4567",
            ),
            rows(tables(html).single()),
            "the same phone is repeated for the brothers, with no grouping, and the withdrawn one is not there",
        )
        assertEquals(listOf("Foglio per i maestri · pagina 1 di 1"), captions(html))
        assertEquals(0, readingCount(), "no notes in the sheet, so opening it reads none")
        assertFalse(text.contains("Chiara") || html.contains("333 123 4599"))
        assertTrue(Regex("""<th scope="col">Bambino</th><th class="col-recapito" scope="col">Recapito del genitore</th>""").containsMatchIn(html))
    }

    @Test
    fun `t6 c24 c26 the sheet for the teachers is divided in sheets, and the closing sentence has a sheet when the last one is full`() {
        val s = Scenario()
        crowd(s.setup.id, 24)

        // 7 + 24 = 31 children: 18 and 13, and the sentence fits under the last.
        val two = s.setup.marta.get("/arrivi/foglio").body()
        assertEquals(listOf("Foglio per i maestri · pagina 1 di 2", "Foglio per i maestri · pagina 2 di 2"), captions(two))
        assertEquals(listOf(18, 13), tables(two).map { rows(it).size })
        assertTrue(tables(two).all { it.contains("<thead>") })

        // 7 + 34 = 41: 18 and 23, and 23 leave no room for the sentence.
        crowd(s.setup.id, 10)
        val three = s.setup.marta.get("/arrivi/foglio").body()
        assertEquals(listOf("Foglio per i maestri · pagina 1 di 3", "Foglio per i maestri · pagina 2 di 3", "Foglio per i maestri · pagina 3 di 3"), captions(three))
        assertEquals(listOf(18, 23, 0), tables(three).map { rows(it).size })
        assertTrue(words(three.substring(three.indexOf("pagina 3 di 3"))).contains("Alla fine della convivenza questo foglio va distrutto."))
        assertEquals(1, Regex("""Alla fine della convivenza questo foglio va distrutto\.""").findAll(three).count())
    }

    // ---- With nothing to print (criteria 23, 26) -------------------------------------------------------------------------------

    @Test
    fun `t6 c23 c26 a gathering with no registration that is not withdrawn has the empty state in the register and in the sheet, and no bar to print`() {
        val setup = kit.start()
        kit.rows.register(setup.id, kit.account(), "Chiara", "Fontana", status = RegistrationStatus.withdrawn)

        for (path in listOf("/arrivi/registro", "/arrivi/foglio")) {
            val html = setup.marta.get(path).body()

            assertTrue(words(html).contains("Nessuna iscrizione da stampare Il registro ha una riga per ogni iscrizione non ritirata. Quando i genitori iscrivono i bambini, lo trovi qui. Torna agli arrivi"), "$path: ${words(html)}")
            assertTrue(html.contains("""href="/arrivi""""))
            assertFalse(html.contains("data-azione=\"stampa\""), "$path: nothing to print")
            assertFalse(html.contains("tabella-stampa"), path)
        }
    }

    @Test
    fun `t6 c28 without a gathering that is not a draft there is nothing to print, and the three pages do not exist`() {
        val setup = kit.start()
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.draft).where(GATHERING.ID.eq(setup.id)).execute()

        for (path in listOf("/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            assertEquals(404, setup.marta.get(path).status(), path)
        }
    }

    // ---- What is written is text (criterion 30) ---------------------------------------------------------------------------------

    @Test
    fun `t6 c30 names and phones with markup are shown as text in the three prints`() {
        val setup = kit.start()
        kit.rows.register(setup.id, kit.account(), "<b>Anna</b>", "R<script>alert(1)</script>ossi", phone = "<i>333</i> 123")

        for (path in listOf("/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            val html = setup.marta.get(path).body()

            assertFalse(html.contains("<b>Anna") || html.contains("<script>alert") || html.contains("<i>333"), "$path: markup that was written is markup in the page")
            assertTrue(html.contains("&lt;b&gt;Anna&lt;/b&gt;") || html.contains("&lt;b>Anna&lt;/b>"), "$path: it is shown as the text that was written")
        }
    }

    @Test
    fun `t6 c24 the three prints belong to the arrivals, with the entry Arrivi as the current one`() {
        val s = Scenario()
        val browser: MockBrowser = s.setup.marta

        for (path in listOf("/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            assertTrue(Regex("""<a href="/arrivi" aria-current="page"""").containsMatchIn(browser.get(path).body()), path)
        }
    }
}
