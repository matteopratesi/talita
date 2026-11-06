package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import talita.CapturesOutput
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.GatheringFormat
import talita.gathering.body
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.gathering.title
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.VENUE
import talita.jooq.tables.references.VENUE_FORM
import talita.registration.Notes
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sheet of a form of the venue (TALITA-20, part 3): the rows of «Da stampare» in Arrivi (criterion 21), what the sheet says (criterion 22: the children present on at least one day by the rule of TALITA-19, the age on
 * the first day, the days, the table of the teachers), what it never says (criterion 23), the state with nobody present (24), the sheets of paper (25), the addresses (26), the notices (27) and the fields of the teachers
 * in the markup (29, 30). Names, venues, numbers and the values of the fields are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ModuleSheetTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    private fun venueOf(gathering: Long): Long = checkNotNull(kit.kit.gatherings.venueIdOf(gathering))

    private fun venueName(gathering: Long): String = dsl.select(VENUE.NAME).from(VENUE).where(VENUE.ID.eq(venueOf(gathering))).fetchSingle(VENUE.NAME)!!

    /** A form of the venue of the gathering, made straight in the table: its id. */
    private fun form(gathering: Long, name: String, age: Boolean = false, days: Boolean = false, document: Boolean = false, taxCode: Boolean = false): Long =
        dsl.insertInto(VENUE_FORM).set(VENUE_FORM.VENUE_ID, venueOf(gathering)).set(VENUE_FORM.NAME, name).set(VENUE_FORM.WITH_AGE, age).set(VENUE_FORM.WITH_DAYS, days)
            .set(VENUE_FORM.WITH_TEACHER_DOCUMENT, document).set(VENUE_FORM.WITH_TEACHER_TAX_CODE, taxCode).returningResult(VENUE_FORM.ID).fetchSingle().value1()!!

    private fun sheet(browser: MockBrowser, form: Long): String = browser.get("/arrivi/modulo/$form").body()

    /** The tables of the sheet, one for every sheet of paper of the children and then the one of the teachers. */
    private fun tables(html: String): List<String> = Regex("""<table class="tabella-stampa[\s\S]*?</table>""").findAll(html).map { it.value }.toList()

    private fun rows(table: String): List<String> {
        val body = Regex("""<tbody>([\s\S]*?)</tbody>""").find(table)!!.groupValues[1]
        return Regex("""<tr[\s\S]*?</tr>""").findAll(body).map { words(it.value) }.toList()
    }

    private fun captions(html: String): List<String> = Regex("""<caption>([^<]*)</caption>""").findAll(html).map { it.groupValues[1] }.toList()

    private fun age(birth: LocalDate, on: LocalDate): Int = Period.between(birth, on).years

    /**
     * Anna Rossi (present today, born 9 January 2019), Luca Rossi (present yesterday, whole day, and today for lunch only, born 4 April 2016), Sara Neri (in doubt, never marked), Nicolò Ferri (from paper, for tomorrow:
     * not present, the day has not come), and Chiara Fontana, who is withdrawn and has a presence row. The teachers in custody are Marta Bianchi and Paolo Verdi.
     */
    private inner class Scenario(first: Long = -1, last: Long = 1) {
        val setup = kit.start(first, last)
        val parent = kit.account()
        val anna = kit.rows.register(setup.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", "Lattosio", "Antistaminico nello zaino."))
        val luca = kit.rows.register(setup.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), days = mapOf(setup.yesterday to "full", setup.today to "lunch"))
        val sara = kit.rows.register(setup.id, kit.account(), "Sara", "Neri", LocalDate.of(2017, 3, 3), phone = "333 123 4512", status = RegistrationStatus.maybe)
        val nicolo = kit.rows.register(setup.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), phone = "333 987 6543", enteredBy = setup.martaId, days = mapOf(setup.tomorrow to "full"))
        val chiara = kit.rows.register(setup.id, kit.account(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), status = RegistrationStatus.withdrawn)

        init {
            kit.presence(anna, setup.today, setup.martaId)
            kit.presence(luca, setup.yesterday, setup.paoloId)
            kit.presence(luca, setup.today, setup.paoloId)
            kit.presence(nicolo, setup.tomorrow, setup.martaId)
            kit.presence(chiara, setup.today, setup.martaId)
            kit.payment(anna, 6000, setup.martaId)
        }
    }

    // ---- «Da stampare» in Arrivi (criterion 21) ------------------------------------------------------------------------------------

    @Test
    fun `t20 c21 Da stampare has a row for each form of the venue after the list for the venue, with what the sheet has, and the sentence that says to print after the arrivals`() {
        val w = Scenario()
        val register = form(w.setup.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)
        val presence = form(w.setup.id, "Registro presenze", days = true)
        val names = form(w.setup.id, "Solo nomi")
        val code = form(w.setup.id, "Con il codice", taxCode = true)

        val html = w.setup.marta.get("/arrivi").body()
        val section = Regex("""<section id="stampe".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value
        val text = words(section)
        val house = venueName(w.setup.id)
        assertTrue(text.contains("Registro arrivi"), text)
        assertTrue(text.indexOf("Elenco per la struttura") < text.indexOf("Con il codice") && text.indexOf("Con il codice") < text.indexOf("Foglio per i maestri"), "after the list for the venue: $text")
        assertTrue(text.contains("Registro per la tassa di soggiorno Per $house: nome e cognome, età al primo giorno e giorni di presenza. Maestri: documento e codice fiscale."), text)
        assertTrue(text.contains("Registro presenze Per $house: nome e cognome, giorni di presenza."), text)
        assertTrue(text.contains("Solo nomi Per $house: solo nome e cognome."), text)
        assertTrue(text.contains("Con il codice Per $house: solo nome e cognome. Maestri: codice fiscale."), text)
        for (id in listOf(register, presence, names, code)) assertTrue(section.contains("""href="/arrivi/modulo/$id""""), "the row leads to the sheet $id")
        assertTrue(text.contains("I moduli della struttura hanno i bambini già segnati presenti: stampali dopo gli arrivi."), text)
        // The forms are by name: the order of the rows is the one of the page of the venue.
        val titles = Regex("""<span class="font-semibold wrap-anywhere">([^<]*)</span>""").findAll(section).map { it.groupValues[1] }.filter { it !in setOf("Registro arrivi", "Elenco per la struttura", "Foglio per i maestri") }.toList()
        assertEquals(listOf("Con il codice", "Registro per la tassa di soggiorno", "Registro presenze", "Solo nomi"), titles)
    }

    @Test
    fun `t20 c21 with no form the card has the three rows of before and no sentence about the forms`() {
        val w = Scenario()

        val text = words(Regex("""<section id="stampe".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(w.setup.marta.get("/arrivi").body())!!.value)
        assertTrue(text.contains("Registro arrivi") && text.contains("Elenco per la struttura") && text.contains("Foglio per i maestri"), text)
        assertFalse(text.contains("I moduli della struttura"), text)
        assertFalse(text.contains("/arrivi/modulo"), text)
    }

    // ---- What the sheet says (criteria 22 and 29) ----------------------------------------------------------------------------------

    @Test
    fun `t20 c22 the sheet has the name of the form, the gathering and the venue, the children present on at least one day by surname and name with the age on the first day and the days, and the teachers`() {
        val w = Scenario()
        val id = form(w.setup.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)

        val response = w.setup.marta.get("/arrivi/modulo/$id")
        val html = response.body()
        val text = words(html)

        assertEquals(200, response.status())
        assertEquals("Registro per la tassa di soggiorno", heading(html))
        assertEquals("Registro per la tassa di soggiorno · Talità", title(html))
        assertTrue(text.contains("Convivenza di settembre 2027 · ${GatheringFormat.range(w.setup.yesterday, w.setup.tomorrow)}"), text)
        assertTrue(text.contains("Torna agli arrivi"), text)
        assertTrue(Regex("""Struttura: ${Regex.escape(venueName(w.setup.id))}\. Stampato il \d{1,2} \p{L}+ \d{4} alle \d{1,2}:\d{2}\.""").containsMatchIn(text), text)
        assertTrue(text.contains("Età in anni al primo giorno della convivenza, il ${GatheringFormat.date(w.setup.yesterday)}."), text)
        assertTrue(text.contains("Bambini presenti · 2"), text)
        assertTrue(text.contains("Ci sono i bambini segnati presenti almeno un giorno, fino al momento della stampa. Un giorno vuoto è senza presenza, oppure non ancora arrivato."), text)
        assertTrue(text.contains("Il foglio ha il nome e il cognome dei bambini presenti e dei maestri affidatari, l’età e i giorni di presenza, come chiede la struttura."), text)
        assertTrue(text.contains("Per i maestri ci sono anche il documento d’identità e il codice fiscale: li scrivi nella tabella dei maestri, prima di stampare o di salvare il PDF. Stampa su A4, in verticale."), text)

        val tables = tables(html)
        assertEquals(2, tables.size, "one sheet of children and the table of the teachers")
        assertEquals(listOf("Registro per la tassa di soggiorno · pagina 1 di 1", "Maestri"), captions(html))
        // The children: those who are present on at least one day (Anna, Luca), not who was never marked (Sara), whose day has not come (Nicolò) or is withdrawn (Chiara); by surname and name.
        val annaAge = age(LocalDate.of(2019, 1, 9), w.setup.yesterday)
        val lucaAge = age(LocalDate.of(2016, 4, 4), w.setup.yesterday)
        assertEquals(listOf("Rossi Anna $annaAge presente", "Rossi Luca $lucaAge presente solo pranzo"), rows(tables[0]))
        val head = words(Regex("""<thead>[\s\S]*?</thead>""").find(tables[0])!!.value)
        assertTrue(head.startsWith("Cognome e nome Età "), head)
        assertTrue(head.contains(CheckinFormat.weekdayName(w.setup.yesterday)) && head.contains(CheckinFormat.weekdayName(w.setup.tomorrow)), head)
        // The cells of the days: Anna is present today only; Luca yesterday (a whole day) and today (lunch only); nothing is written for a day that has no presence.
        assertEquals(6, Regex("""<td class="cella-presenza"""").findAll(tables[0]).count(), "three days for each of the two children")
        // The teachers: the custodians and three blank rows, with a field for each column of the teachers.
        assertEquals(setOf("Bianchi Marta", "Verdi Paolo"), rows(tables[1]).take(2).toSet())
        assertEquals(5, rows(tables[1]).size)
        assertTrue(text.contains("Maestri · 2"), text)
        assertTrue(text.contains("Gli adulti in più si scrivono a mano nelle righe vuote."), text)
    }

    @Test
    fun `t20 c22 the columns are the ones of the form, and a form with only the name has only the name and the cognome`() {
        val w = Scenario()
        val onlyName = form(w.setup.id, "Solo nomi")
        val onlyAge = form(w.setup.id, "Con l'età", age = true)
        val onlyDays = form(w.setup.id, "Con i giorni", days = true)

        val names = sheet(w.setup.marta, onlyName)
        assertEquals(listOf("Rossi Anna", "Rossi Luca"), rows(tables(names)[0]))
        assertFalse(words(names).contains("Età") || words(names).contains("presente") || words(names).contains("Età in anni al primo giorno"), words(names))
        assertTrue(words(names).contains("Il foglio ha solo il nome e il cognome dei bambini presenti e dei maestri affidatari. Stampa su A4, in verticale."), words(names))
        assertFalse(names.contains("<input"), "no fields without columns for the teachers")
        assertFalse(words(names).contains("Quello che scrivi qui non si salva"), "no notice without fields")

        val ages = sheet(w.setup.marta, onlyAge)
        assertEquals(listOf("Rossi Anna ${age(LocalDate.of(2019, 1, 9), w.setup.yesterday)}", "Rossi Luca ${age(LocalDate.of(2016, 4, 4), w.setup.yesterday)}"), rows(tables(ages)[0]))
        assertTrue(words(ages).contains("Il foglio ha il nome e il cognome dei bambini presenti e dei maestri affidatari, l’età, come chiede la struttura."), words(ages))

        val days = sheet(w.setup.marta, onlyDays)
        assertEquals(listOf("Rossi Anna presente", "Rossi Luca presente solo pranzo"), rows(tables(days)[0]))
        assertFalse(words(days).contains("Età in anni"), words(days))
    }

    @Test
    fun `t20 c22 the age is in whole years on the first day of the gathering, and the date of birth is never written`() {
        val w = Scenario()
        // A child who turns 8 the day after the first day is still 7 on it; one who turns 8 on the first day is 8.
        val before = kit.rows.register(w.setup.id, kit.account(), "Elena", "Marino", w.setup.yesterday.minusYears(8).plusDays(1))
        val on = kit.rows.register(w.setup.id, kit.account(), "Gaia", "Esposito", w.setup.yesterday.minusYears(8))
        kit.presence(before, w.setup.today, w.setup.martaId)
        kit.presence(on, w.setup.today, w.setup.martaId)
        val id = form(w.setup.id, "Con l'età", age = true)

        val text = rows(tables(sheet(w.setup.marta, id))[0])
        assertTrue("Esposito Gaia 8" in text, "turned 8 on the first day: $text")
        assertTrue("Marino Elena 7" in text, "turns 8 the day after: $text")
    }

    @Test
    fun `t20 c22 who is present is decided by the rule of TALITA-19 and nothing else, a presence of a day that has not come is not one, and a withdrawn registration is never on the sheet`() {
        val w = Scenario()
        val id = form(w.setup.id, "Solo nomi")

        val list = rows(tables(sheet(w.setup.marta, id))[0])
        assertEquals(listOf("Rossi Anna", "Rossi Luca"), list, "Nicolò has a row for tomorrow and Chiara is withdrawn: neither is present")
        // The lists for the venue of the days say the same people: one rule, in one place (the sheet has who is present on at least one of the days).
        val perDay = listOf(w.setup.yesterday, w.setup.today, w.setup.tomorrow).flatMap { day -> tables(w.setup.marta.get("/arrivi/elenco?giorno=$day").body()).take(1).flatMap { rows(it) } }.toSet()
        assertEquals(list.toSet(), perDay)
    }

    // ---- What the sheet never says (criterion 23) ---------------------------------------------------------------------------------

    @Test
    fun `t20 c23 the sheet has no date of birth, sex, document, tax code, contact, note on health, fee, payment, room and no sign of the app, whatever the child has`() {
        val w = Scenario()
        val giulia = kit.rows.register(w.setup.id, kit.account(), "Giulia", "Ferri", LocalDate.of(2018, 5, 5), phone = "333 123 4503")
        val giuliaAgain = kit.rows.register(w.setup.id, kit.account(), "Giulia", "Ferri", LocalDate.of(2018, 5, 5), phone = "333 123 4504", status = RegistrationStatus.maybe)
        val paper = kit.rows.register(w.setup.id, null, "Elena", "Marino", LocalDate.of(2016, 10, 1), phone = "+39 333-123.4570", enteredBy = w.setup.martaId, paperReceived = true)
        for (child in listOf(giulia, giuliaAgain, paper)) kit.presence(child, w.setup.today, w.setup.martaId)
        val id = form(w.setup.id, "Registro completo", age = true, days = true, document = true, taxCode = true)
        val readings = dsl.fetchCount(HEALTH_NOTE_ACCESS)

        val html = sheet(w.setup.marta, id)
        val text = words(html)

        val inMain = html.substringAfter("<main").substringBefore("</main>")
        for (forbidden in listOf(
            "333", "987", "Arachidi", "Lattosio", "Antistaminico", "2019", "2016", "2018", "2015", "9 gennaio", "4 aprile", "nome@example.org", "Dal cartaceo", "Possibile doppione", "In forse", "Manleva", "Dovuta", "Pagato",
            "60,00", "Quota", "Camera", "Allergie", "sesso", "Sesso", "luogo di nascita", "Cittadinanza", "Recapito", "Telefono", "Presenza segnata",
        )) {
            assertFalse(text.contains(forbidden), "the sheet says «$forbidden»: $text")
            assertFalse(inMain.contains(forbidden), "the markup of the sheet has «$forbidden»")
        }
        assertEquals(readings, dsl.fetchCount(HEALTH_NOTE_ACCESS), "printing the sheet reads no note on health: no reading is written")
        // Two children with the same name and the same birth day are two rows, with nothing that says «doppione».
        assertEquals(2, rows(tables(html)[0]).count { it.startsWith("Ferri Giulia") })
    }

    // ---- Nobody present (criterion 24) --------------------------------------------------------------------------------------------

    @Test
    fun `t20 c24 with nobody present the sheet says so and leads to the list for the venue, with no table of the children`() {
        val setup = kit.start()
        kit.rows.register(setup.id, kit.account(), "Sara", "Neri", LocalDate.of(2017, 3, 3))
        val id = form(setup.id, "Registro per la tassa di soggiorno", age = true, document = true)

        val html = sheet(setup.marta, id)
        val text = words(html)
        assertTrue(text.contains("Nessun bambino presente Il modulo ha i bambini che i maestri hanno segnato presenti almeno un giorno, e per ora non ce n’è nessuno. Segna gli arrivi, oppure stampa l’elenco di chi è iscritto."), text)
        assertTrue(html.contains("""href="/arrivi/elenco"""") && text.contains("Apri l’elenco per la struttura"), text)
        assertTrue(tables(html).isEmpty(), "no table")
        assertFalse(html.contains("<input"), "no field")
        assertEquals(200, setup.marta.get("/arrivi/modulo/$id").status())
    }

    // ---- The sheets of paper (criterion 25) ----------------------------------------------------------------------------------------

    @Test
    fun `t20 c25 a sheet holds 20 and 26 rows with up to four days and 19 and 25 with more, the table of the teachers is on the last sheet if eight rows are left and alone on a new one if not`() {
        assertEquals(Capacity(20, 26), Pagination.module(1))
        assertEquals(Capacity(20, 26), Pagination.module(3))
        assertEquals(Capacity(20, 26), Pagination.module(4))
        assertEquals(Capacity(19, 25), Pagination.module(5))
        assertEquals(Capacity(19, 25), Pagination.module(7))
        // 35 children and 7 days: two sheets (19 and 16), with the teachers under the second; with 3 days: 20 and 15.
        val seven = Pagination.split((1..35).toList(), Pagination.module(7), Pagination.venueReserve(2))
        assertEquals(listOf(19, 16), seven.pages.map { it.size })
        assertFalse(seven.trailerAlone)
        assertEquals(2, seven.count)
        assertEquals(listOf(20, 15), Pagination.split((1..35).toList(), Pagination.module(3), Pagination.venueReserve(2)).pages.map { it.size })
        // 39 children and 3 days: the second sheet has 19, and the table of the teachers (8 rows) does not fit under it: a third sheet, with the teachers alone.
        val alone = Pagination.split((1..39).toList(), Pagination.module(3), Pagination.venueReserve(2))
        assertTrue(alone.trailerAlone)
        assertEquals(3, alone.count)
    }

    @Test
    fun `t20 c25 the sheet of 35 children and seven days is two sheets, every one with its caption pagina N di M, and the teachers are under the second`() {
        val setup = kit.start(first = -1, last = 5)
        for (number in 1..35) {
            val child = kit.rows.register(setup.id, kit.account(), "Bambino", "Prova%02d".format(number), LocalDate.of(2016, 1, 1).plusDays(number.toLong()))
            kit.presence(child, setup.yesterday, setup.martaId)
        }
        val id = form(setup.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)

        val html = sheet(setup.marta, id)
        assertEquals(listOf("Registro per la tassa di soggiorno · pagina 1 di 2", "Registro per la tassa di soggiorno · pagina 2 di 2", "Maestri"), captions(html))
        val tables = tables(html)
        assertEquals(listOf(19, 16, 5), tables.map { rows(it).size })
        assertTrue(tables.take(2).all { it.contains("tabella-stampa--7") }, "more than four days: the narrow columns")
        // The teachers' table with 7 days and the two fields has no column of the days: there is no room for them on the paper.
        assertFalse(tables[2].contains("col-giorno"), "the days are not in the table of the teachers")
        assertTrue(tables[0].contains("col-giorno"))
        assertTrue(tables[2].contains("col-campo") && tables[2].contains("col-eta"), "the age stays")
        // Every sheet is a block with its own header (Safari does not repeat the thead).
        assertEquals(3, Regex("""<div class="blocco-stampa">""").findAll(html).count())
    }

    @Test
    fun `t20 c25 the days stay in the table of the teachers while the name keeps 27 millimetres, with the age`() {
        // One field: up to six days; two fields: up to three; none: always (A4 portrait, 174 mm).
        for (days in 1..6) assertTrue(ModuleLayout.daysFit(age = true, fields = 1, days = days), "$days days, one field")
        assertFalse(ModuleLayout.daysFit(age = true, fields = 1, days = 7))
        for (days in 1..3) assertTrue(ModuleLayout.daysFit(age = true, fields = 2, days = days), "$days days, two fields")
        for (days in 4..7) assertFalse(ModuleLayout.daysFit(age = true, fields = 2, days = days), "$days days, two fields")
        for (days in 1..7) assertTrue(ModuleLayout.daysFit(age = true, fields = 0, days = days), "$days days, no field")
        assertEquals(21, ModuleLayout.dayWidth(4))
        assertEquals(16, ModuleLayout.dayWidth(5))
    }

    @Test
    fun `t20 c25 with three days and two fields the table of the teachers keeps the days`() {
        val w = Scenario()
        val id = form(w.setup.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)

        val teachers = tables(sheet(w.setup.marta, id))[1]
        assertEquals(3, Regex("""<th class="col-giorno"""").findAll(teachers).count())
        assertEquals(2, Regex("""<th class="col-campo"""").findAll(teachers).count())
    }

    // ---- The addresses (criterion 26) ---------------------------------------------------------------------------------------------

    @Test
    fun `t20 c26 a parent is told 403 on the address of a sheet and whoever has not entered goes to the access page, a form that is not there or is of another venue is 404, and nothing is saved`() {
        val w = Scenario()
        val id = form(w.setup.id, "Registro per la tassa di soggiorno", age = true, document = true)
        val other = kit.kit.gatherings.makeVenue("Casa per ritiri sul lago, Località Esempio", "Casa sul lago")
        val elsewhere = dsl.insertInto(VENUE_FORM).set(VENUE_FORM.VENUE_ID, other).set(VENUE_FORM.NAME, "Altro modulo").returningResult(VENUE_FORM.ID).fetchSingle().value1()!!
        val forms = dsl.fetchCount(VENUE_FORM)
        val parent = kit.kit.parent()

        assertEquals(403, parent.get("/arrivi/modulo/$id").status())
        assertFalse(parent.get("/arrivi/modulo/$id").body().contains("Rossi"), "nothing of the sheet is in the 403")
        val anonymous = mockMvc.get("/arrivi/modulo/$id").andReturn().response
        assertEquals(302, anonymous.status)
        assertEquals("/accedi", anonymous.getHeader("Location"))
        assertEquals(404, w.setup.marta.get("/arrivi/modulo/$elsewhere").status(), "a form of another venue is a form that does not exist")
        assertEquals(404, w.setup.marta.get("/arrivi/modulo/${id + 100_000}").status())
        assertEquals(404, w.setup.marta.get("/arrivi/modulo/abc").status())
        assertEquals(forms, dsl.fetchCount(VENUE_FORM), "nothing was written")
    }

    @Test
    @CapturesOutput
    fun `t20 c26 the log has no name of a child, of a teacher, of the form or of the venue, and no age, while the sheet is asked for`(output: CapturedOutput) {
        val w = Scenario()
        val id = form(w.setup.id, "Registro riservato di prova", age = true, days = true, document = true, taxCode = true)
        sheet(w.setup.marta, id)
        sheet(w.setup.marta, id + 100_000)
        w.setup.marta.get("/arrivi")

        val log = output.all
        for (secret in listOf("Registro riservato di prova", "Anna", "Luca", "Rossi", "Marta", "Bianchi", "Verdi", venueName(w.setup.id))) assertFalse(log.contains(secret), "the log has «$secret»")
    }

    // ---- The notices (criterion 27) ------------------------------------------------------------------------------------------------

    @Test
    fun `t20 c27 the complete notice says what the venue receives on paper and that Talità does not receive nor keep the document and the tax code that the teachers write, and so does the short one of the second step`() {
        val full = words(mockMvc.get("/informativa").andReturn().response.contentAsString)
        for (part in listOf(
            "La struttura che ospita la convivenza riceve su carta da Talità un elenco con il nome e il cognome dei bambini e dei maestri affidatari.",
            "Se il suo modulo lo chiede, nell’elenco ci sono anche l’età dei bambini il primo giorno della convivenza (non la data di nascita) e i giorni in cui sono presenti. Nient’altro.",
            "Se il suo modulo lo chiede, al momento della stampa un maestro scrive sul foglio anche il documento d’identità e il codice fiscale dei maestri affidatari. Restano nel browser del maestro e nel foglio, stampato o salvato in PDF: Talità non li riceve e non li conserva.",
        )) {
            assertTrue(full.contains(part), "the complete notice does not say: $part")
        }
        assertFalse(full.contains("e nient’altro."), "the old sentence is gone")
        val registrations = talita.registration.RegistrationTestKit(mockMvc, tokens, dsl, clock)
        val setup = registrations.start()
        val second = registrations.parent().submit(setup.form, setup.form, registrations.form(registrations.child())).body()
        assertTrue(
            words(second).contains(
                "Chi li riceve I maestri della convivenza. La struttura che ospita la convivenza riceve su carta da Talità il nome e il cognome dei bambini e dei maestri affidatari. " +
                    "Se il suo modulo lo chiede, riceve anche l’età dei bambini il primo giorno della convivenza (non la data di nascita) e i giorni in cui sono presenti. Nient’altro.",
            ),
            words(second),
        )
    }

    // ---- The fields of the teachers in the markup (criteria 29 and 30) -------------------------------------------------------------

    @Test
    fun `t20 c29 with the document and the tax code every row of the teachers has a field for each column and the blank rows have the name too, and the rows of the children have none, with the notice above the table`() {
        val w = Scenario()
        val id = form(w.setup.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)

        val html = sheet(w.setup.marta, id)
        val tables = tables(html)
        assertFalse(tables[0].contains("<input"), "the rows of the children have no field")
        val inputs = Regex("""<input[^>]*>""").findAll(tables[1]).map { it.value }.toList()
        // Two teachers with two fields each, three blank rows with the name too.
        assertEquals(2 * 2 + 3 * 3, inputs.size)
        val labels = inputs.map { Regex("""aria-label="([^"]*)"""").find(it)!!.groupValues[1] }
        assertTrue("Documento d’identità di Marta Bianchi" in labels && "Codice fiscale di Paolo Verdi" in labels, labels.toString())
        assertTrue("Cognome e nome, riga vuota 1" in labels && "Documento d’identità, riga vuota 2" in labels && "Codice fiscale, riga vuota 3" in labels, labels.toString())
        assertEquals(3, labels.count { it.startsWith("Cognome e nome") }, "only the blank rows have the field of the name")
        val text = words(html)
        assertTrue(text.contains("Quello che scrivi qui non si salva in Talità. Finisce solo nel foglio o nel PDF che salvi tu. Se esci dalla pagina o la ricarichi, sparisce."), text)
        assertTrue(html.contains("""<div class="mb-3 print:hidden">"""), "the notice is not printed")
    }

    @Test
    fun `t20 c29 with the tax code only the table of the teachers has one column of fields, and with no column of the teachers it has no field and no notice`() {
        val w = Scenario()
        val onlyCode = form(w.setup.id, "Solo il codice", taxCode = true)
        val none = form(w.setup.id, "Senza campi", age = true)

        val code = sheet(w.setup.marta, onlyCode)
        assertEquals(1, Regex("""<th class="col-campo"""").findAll(tables(code)[1]).count())
        assertEquals(2 + 3 * 2, Regex("""<input""").findAll(tables(code)[1]).count())
        assertTrue(words(code).contains("Per i maestri c’è anche il codice fiscale: lo scrivi nella tabella dei maestri, prima di stampare o di salvare il PDF."), words(code))
        val plain = sheet(w.setup.marta, none)
        assertFalse(plain.contains("<input"))
        assertEquals(2, tables(plain).size, "the table of the teachers is there, with the names and the blank rows, and no field")
    }

    @Test
    fun `t20 c30 every field of the teachers is outside every form, has no name, keeps the browser from helping, has its limit and its accessible name, and the page is not kept by htmx`() {
        val w = Scenario()
        val id = form(w.setup.id, "Registro per la tassa di soggiorno", age = true, days = true, document = true, taxCode = true)

        val html = sheet(w.setup.marta, id)
        val main = html.substringAfter("<main").substringBefore("</main>")
        assertFalse(main.contains("<form"), "no form in the page of the sheet: nothing can send a field")
        val fields = Regex("""<input[^>]*>""").findAll(main).map { it.value }.toList()
        assertEquals(13, fields.size)
        for (field in fields) {
            assertTrue(Regex("""\stype="text"""").containsMatchIn(field), field)
            assertFalse(Regex("""\sname=""").containsMatchIn(field), "no name: $field")
            assertFalse(Regex("""\sform=""").containsMatchIn(field), field)
            for (attribute in listOf("""autocomplete="off"""", """autocorrect="off"""", """autocapitalize="off"""", """spellcheck="false"""", "data-solo-carta")) assertTrue(field.contains(attribute), "$field misses $attribute")
            assertTrue(Regex("""maxlength="(20|16|60)"""").containsMatchIn(field), field)
            assertTrue(Regex("""aria-label="[^"]+"""").containsMatchIn(field), field)
            assertFalse(Regex("""\s(value|id|list)=""").containsMatchIn(field), "no value, no id, no list: $field")
        }
        assertEquals(listOf(20, 16), fields.take(2).map { Regex("""maxlength="(\d+)"""").find(it)!!.groupValues[1].toInt() })
        assertEquals(60, fields.map { Regex("""maxlength="(\d+)"""").find(it)!!.groupValues[1].toInt() }.max())
        assertTrue(html.contains("""hx-history="false""""), "htmx does not keep the page in its history")
        assertFalse(Regex("""\sstyle=|<script|\son[a-z]+=""", RegexOption.IGNORE_CASE).containsMatchIn(main), "no style, script or handler")
    }

    @Test
    fun `t20 c30 app js has only the few lines that empty the fields when the page comes back from the cache of the browser, and reads and keeps no value`() {
        val js = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/js/app.js"))
        val lines = Regex("""(?s)window\.addEventListener\("pageshow", \(event\) => \{\s*if \(!event\.persisted\) return;(.*?)\}\);""").find(js)
        assertNotNull(lines, "the lines of the fields are in app.js")
        assertTrue(lines.groupValues[1].contains("[data-solo-carta]") && lines.groupValues[1].contains("""field.value = "";"""), lines.groupValues[1])
        assertFalse(lines.groupValues[1].contains("localStorage") || lines.groupValues[1].contains("sessionStorage") || lines.groupValues[1].contains("indexedDB"))
        // Nothing else in the scripts of the application reads or writes the fields of the teachers, or keeps a value of a field in the browser.
        for (file in listOf("app.js")) {
            val text = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/static/js/$file"))
            assertEquals(1, Regex("""\[data-solo-carta]""").findAll(text).count(), "the fields are named in one place in the code: the lines of the pageshow")
        }
    }
}
