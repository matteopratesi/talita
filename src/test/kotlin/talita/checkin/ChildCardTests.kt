package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.GatheringFormat
import talita.gathering.body
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.gathering.title
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.enums.GatheringStatus
import talita.registration.RegistrationFormat
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The card of a child (criteria 5-9): the days with what the registration foresees and who marked the presence, the manleva, the registration and the other
 * children of the same parent, and every action of the card, which answers with the whole block `#scheda`. Teachers have entered with the link; the days are
 * those of the day the test runs, and names and amounts are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ChildCardTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    private fun htmx(): MockHttpServletRequestDsl.() -> Unit = { header("HX-Request", "true") }

    private fun card(browser: MockBrowser, id: Long, day: LocalDate? = null): String = browser.get("/arrivi/$id" + (day?.let { "?giorno=$it" } ?: "")).body()

    /** A touch of the card as the page sends it (or, with [viaHtmx] false, as a form without JavaScript does): the list is asked for first, for the code against forgery. */
    private fun act(browser: MockBrowser, setup: CheckinSetup, id: Long, action: String, fields: Map<String, String>, viaHtmx: Boolean = true): MvcResult =
        browser.submit("/arrivi?giorno=${setup.today}", "/arrivi/$id/$action", fields, if (viaHtmx) htmx() else ({}))

    private fun fromCard(browser: MockBrowser, setup: CheckinSetup, id: Long, action: String, day: LocalDate = setup.today, viaHtmx: Boolean = true): MvcResult =
        act(browser, setup, id, action, mapOf("giorno" to day.toString(), "da" to "scheda"), viaHtmx)

    /** The labels under the title (the first row of badges of the block). */
    private fun badges(html: String): List<String> =
        Regex("""<div class="-mt-4 mb-4[^"]*">([\s\S]*?)</div>""").find(html)!!.groupValues[1].let { row ->
            Regex("""whitespace-nowrap [^"]*">(?:<svg[\s\S]*?</svg>)?([^<]+)</span>""").findAll(row).map { it.groupValues[1].trim() }.toList()
        }

    private inner class Scenario {
        val setup = kit.start()
        val parent = kit.account()
        val luca = kit.rows.register(setup.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), days = mapOf(setup.today to "full", setup.tomorrow to "lunch"))
        val anna = kit.rows.register(setup.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        val nicolo = kit.rows.register(setup.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = setup.martaId)
        val sara = kit.rows.register(setup.id, kit.account(), "Sara", "Neri", LocalDate.of(2017, 3, 3), status = RegistrationStatus.maybe)
        val chiara = kit.rows.register(setup.id, kit.account(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), status = RegistrationStatus.withdrawn)
    }

    // ---- The card (criterion 5) ---------------------------------------------------------------------------------------------------

    @Test
    fun `t6 c05 the card has the name, the gathering, the labels, the days with what the registration foresees, the manleva, the registration and the other children of the same parent`() {
        val s = Scenario()
        val html = card(s.setup.marta, s.luca)
        val text = words(html)

        assertEquals("Luca Rossi · Talità", title(html))
        assertEquals("Luca Rossi", heading(html))
        assertTrue(text.contains("Torna agli arrivi"))
        assertTrue(Regex("""<a href="/arrivi\?giorno=${s.setup.today}"[^>]*>[\s\S]{0,400}?Torna agli arrivi""").containsMatchIn(html), "the way back leads to the list of the same day")
        assertTrue(text.contains("Convivenza di settembre 2027 · ${GatheringFormat.range(s.setup.yesterday, s.setup.tomorrow)}"), text)
        assertTrue(Regex("""<a href="/arrivi" aria-current="page"""").containsMatchIn(html), "Arrivi is the current entry")
        assertEquals(listOf("Confermata", "Accettata online"), badges(html))
        // The three days: what the registration foresees on each (Luca comes today and tomorrow, for lunch tomorrow), «Oggi» on today.
        val days = Regex("""<section[^>]*aria-labelledby="giorni-titolo">([\s\S]*?)</section>""").find(html)!!.groupValues[1]
        assertEquals(
            listOf(RegistrationFormat.dayLabel(s.setup.yesterday), RegistrationFormat.dayLabel(s.setup.today), RegistrationFormat.dayLabel(s.setup.tomorrow)),
            Regex("""<span class="font-semibold">([^<]+)</span>""").findAll(days).map { it.groupValues[1] }.toList(),
        )
        assertEquals(listOf("Non previsto", "Giornata", "Solo pranzo"), Regex("""<span class="ml-auto text-sm[^"]*">([^<]+)</span>""").findAll(days).map { it.groupValues[1] }.toList())
        assertTrue(days.contains("font-semibold text-warning\">Non previsto"), "a day that is not foreseen is in the colour of attention")
        assertEquals(1, Regex(""">Oggi</span>""").findAll(days).count())
        // The manleva, the registration (state, age on the first day, the way to the registration of TALITA-5) and the sister.
        assertTrue(text.contains("Manleva Accettata online Accettata dal genitore il "), text)
        assertTrue(text.contains("Iscrizione Stato Confermata Età ${RegistrationFormat.ageText(LocalDate.of(2016, 4, 4), s.setup.yesterday)} il primo giorno Apri l’iscrizione"), text)
        assertTrue(html.contains("""href="/iscrizioni/${s.luca}""""))
        assertTrue(text.contains("Altri figli dello stesso genitore Anna Rossi Confermata Da pagare ${euro(6000)} Se il genitore ha pagato per tutti, registra a ognuno la sua parte."), text)
        assertTrue(html.contains("""href="/arrivi/${s.anna}?giorno=${s.setup.today}""""))
        // A child with no other child of the same parent has no such card, and a paper registration has no parent.
        assertFalse(words(card(s.setup.marta, s.nicolo)).contains("Altri figli dello stesso genitore"))
        assertFalse(words(card(s.setup.marta, s.sara)).contains("Altri figli dello stesso genitore"))
    }

    @Test
    fun `t6 c05 the card of a possible duplicate says so and leads to the other card, and the day of the address is the one that comes back to the list`() {
        val s = Scenario()
        val twin = kit.rows.register(s.setup.id, kit.account(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.maybe)

        val html = card(s.setup.marta, twin, s.setup.yesterday)

        assertEquals(listOf("In forse", "Accettata online", "Possibile doppione"), badges(html))
        assertTrue(words(html).contains("Possibile doppione. C’è un’altra iscrizione con lo stesso nome e la stessa data di nascita. Se è lo stesso bambino, ritirane una. Apri l’altra scheda"), words(html))
        assertTrue(Regex("""href="/arrivi/${s.anna}\?giorno=${s.setup.yesterday}"[^>]*aria-label="Apri l’altra scheda, iscrizione fatta il """).containsMatchIn(html))
        assertTrue(Regex("""<a href="/arrivi\?giorno=${s.setup.yesterday}"[^>]*>[\s\S]{0,400}?Torna agli arrivi""").containsMatchIn(html))
        assertTrue(words(html).contains("L’iscrizione è in forse. Se segni la presenza, diventa confermata."))
    }

    @Test
    fun `t6 c05 a paper registration says so in its labels and in the card of the manleva, and a card that does not exist, or is of another gathering, is a page that does not exist`() {
        val s = Scenario()

        val html = card(s.setup.marta, s.nicolo)
        assertEquals(listOf("Confermata", "Da ricevere"), badges(html))
        assertTrue(words(html).contains("Manleva Da ricevere Il modulo firmato non è ancora arrivato. Segna come ricevuta"), words(html))
        assertTrue(words(html).contains("Il modulo firmato resta di carta: qui si segna solo se è arrivato."), words(html))

        assertEquals(404, s.setup.marta.get("/arrivi/${s.luca + 100000}").status())
        // Arrivi is about the gathering that starts last: when another one, which starts later, takes its place, the registrations of this one have no card here.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(s.setup.id)).execute()
        val later = kit.kit.gatherings.make(title = "La convivenza dopo", start = s.setup.today.plusDays(60), status = GatheringStatus.`open`, custodians = listOf(s.setup.martaId))
        val newer = kit.rows.register(later, kit.account(), "Elena", "Marino")
        assertEquals(404, s.setup.marta.get("/arrivi/${s.luca}").status(), "a registration of the older gathering")
        assertEquals(200, s.setup.marta.get("/arrivi/$newer").status())
    }

    // ---- The presence of a day (criteria 6, 7) ------------------------------------------------------------------------------------

    @Test
    fun `t6 c06 marking a day in the card changes only that day, with the confirmation, and taking it away asks no question`() {
        val s = Scenario()

        val marked = fromCard(s.setup.marta, s.setup, s.anna, "presenze")

        assertEquals(200, marked.status())
        val html = marked.body()
        assertTrue(html.startsWith("<div id=\"scheda\">") || html.trim().startsWith("<div id=\"scheda\">"), html.take(200))
        assertEquals(listOf(s.setup.today), kit.presenceDays(s.anna))
        assertTrue(Regex("""role="status" tabindex="-1" data-fuoco=""""").containsMatchIn(html), "the confirmation is announced and takes the focus")
        assertTrue(words(html).contains("Presenza segnata."), words(html))
        // Today is marked, with who and when; yesterday and tomorrow are as they were: yesterday can still be marked, tomorrow cannot be yet.
        val at = kit.presenceOf(s.anna, s.setup.today)
        assertTrue(words(html).contains("Presente Segnata da Marta Bianchi alle ${CheckinFormat.time(at.at)}"), words(html))
        assertTrue(words(html).contains("Si potrà segnare da quel giorno."))
        assertTrue(html.contains("""aria-label="Segna presente Anna Rossi, ${CheckinFormat.longDay(s.setup.yesterday)}""""), "the name of the button says whose and which day")
        assertFalse(html.contains("""aria-label="Segna presente Anna Rossi, ${CheckinFormat.longDay(s.setup.today)}""""))
        // Taking it away: no dialog, no question; the day goes back to what it was.
        val undone = fromCard(s.setup.marta, s.setup, s.anna, "presenze/togli")
        assertEquals(200, undone.status())
        assertTrue(words(undone.body()).contains("Presenza tolta."))
        assertEquals(emptyList(), kit.presenceDays(s.anna))
        assertTrue(undone.body().contains("""aria-label="Segna presente Anna Rossi, ${CheckinFormat.longDay(s.setup.today)}""""))
        assertFalse(undone.body().contains("<dialog"), "taking a presence away asks for no confirmation")
    }

    @Test
    fun `t6 c06 a day after today has no button and a request by hand changes nothing, and before the start a notice says from when`() {
        val s = Scenario()

        val html = card(s.setup.marta, s.anna)
        assertTrue(words(html).contains("Si potrà segnare da quel giorno."))
        assertFalse(html.contains("""aria-label="Segna presente Anna Rossi, ${CheckinFormat.longDay(s.setup.tomorrow)}""""))
        val byHand = fromCard(s.setup.marta, s.setup, s.anna, "presenze", day = s.setup.tomorrow)
        assertEquals(409, byHand.status())
        assertTrue(words(byHand.body()).contains("Non è stato possibile segnare la presenza: quel giorno non è ancora arrivato."), words(byHand.body()))
        assertTrue(Regex("""role="alert" tabindex="-1" data-fuoco""").containsMatchIn(byHand.body()), "a refusal is announced at once")
        assertEquals(0, kit.presenceCount())
        val outside = fromCard(s.setup.marta, s.setup, s.anna, "presenze", day = s.setup.today.plusDays(12))
        assertEquals(409, outside.status())
        assertTrue(words(outside.body()).contains("Non è stato possibile segnare la presenza: le date della convivenza sono cambiate. Ecco i giorni di adesso."))

        // Before the start: no «Segna presente» at all, each day says from when, and the notice says that the payments can already be registered.
        val before = kit.start(first = 3, last = 5)
        val child = kit.rows.register(before.id, kit.account(), "Luca", "Rossi")
        val early = card(before.marta, child)
        val first = CheckinFormat.longDay(before.today.plusDays(3))
        assertTrue(words(early).contains("La convivenza non è ancora iniziata: le presenze si segnano da $first. I pagamenti si possono già registrare."), words(early))
        assertEquals(3, Regex("""Si potrà segnare da $first\.""").findAll(words(early)).count())
        assertFalse(early.contains("Segna presente"))
    }

    @Test
    fun `t6 c06 the card without JavaScript is a post that leads back to the card, with the confirmation kept for it`() {
        val s = Scenario()

        val plain = fromCard(s.setup.marta, s.setup, s.anna, "presenze", viaHtmx = false)

        assertEquals(303, plain.status())
        assertEquals("/arrivi/${s.anna}?giorno=${s.setup.today}", plain.location())
        assertEquals(listOf(s.setup.today), kit.presenceDays(s.anna))
        val page = s.setup.marta.get(plain.location()!!).body()
        assertTrue(words(page).contains("Presenza segnata."), "the confirmation is on the page the redirect leads to")
        assertFalse(words(s.setup.marta.get(plain.location()!!).body()).contains("Presenza segnata."), "and only once")
    }

    @Test
    fun `t6 c07 a day that was not foreseen is marked with a notice that the fee does not change and a link to correct the registration, and the child is among the ones who were not expected`() {
        val s = Scenario()

        val marked = fromCard(s.setup.marta, s.setup, s.luca, "presenze", day = s.setup.yesterday)

        assertEquals(200, marked.status())
        val html = marked.body()
        assertEquals(listOf(s.setup.yesterday), kit.presenceDays(s.luca))
        assertTrue(
            words(html).contains("Presenza segnata. ${CheckinFormat.longDayCapital(s.setup.yesterday)} non era previsto per Luca Rossi: la quota dovuta non cambia finché non correggi l’iscrizione. Correggi l’iscrizione"),
            words(html),
        )
        assertTrue(html.contains("""href="/iscrizioni/${s.luca}/correggi""""), "the link goes to the correction of TALITA-5")
        assertTrue(Regex("""<div class="[^"]*bg-warning-soft[^"]*" role="alert"""").containsMatchIn(html), "it is a notice of attention")
        // On the list of that day he is among the ones who were not expected, present, and the summary counts him apart. What is due did not change: still 25 + 10.
        val list = words(s.setup.marta.get("/arrivi?giorno=${s.setup.yesterday}").body())
        assertTrue(list.contains("Presenti non previsti 1"), list)
        assertTrue(list.contains("Luca Rossi Presente Da pagare ${euro(3500)} Non previsto"), list)
    }

    // ---- In forse, withdrawn (criterion 8) ---------------------------------------------------------------------------------------

    @Test
    fun `t6 c08 marking a child who is in forse confirms the registration, the confirmation says so, and the labels of the card change with it`() {
        val s = Scenario()
        val before = card(s.setup.marta, s.sara)
        assertEquals(listOf("In forse", "Accettata online"), badges(before))
        assertTrue(words(before).contains("L’iscrizione è in forse. Se segni la presenza, diventa confermata."))

        val marked = fromCard(s.setup.marta, s.setup, s.sara, "presenze")

        assertEquals(200, marked.status())
        assertEquals("confirmed", kit.statusOf(s.sara))
        assertTrue(words(marked.body()).contains("Presenza segnata. L’iscrizione di Sara Neri era in forse: adesso è confermata."), words(marked.body()))
        assertEquals(listOf("Confermata", "Accettata online"), badges(marked.body()))
        assertFalse(words(marked.body()).contains("L’iscrizione è in forse."), "the notice about the doubt is gone with it")
        assertTrue(words(marked.body()).contains("Iscrizione Stato Confermata"))
    }

    @Test
    fun `t6 c08 a withdrawn registration has no Segna presente but Rimetti confermata, which puts it back, and a request by hand to mark it changes nothing`() {
        val s = Scenario()
        val html = card(s.setup.marta, s.chiara)

        assertEquals(listOf("Ritirata"), badges(html), "a withdrawn registration has no label of the manleva")
        assertTrue(words(html).contains("Iscrizione ritirata. Non si segnano presenze. Quelle già segnate e i pagamenti restano. Rimetti confermata"), words(html))
        assertFalse(html.contains("Segna presente"))
        val byHand = fromCard(s.setup.marta, s.setup, s.chiara, "presenze")
        assertEquals(409, byHand.status())
        assertTrue(words(byHand.body()).contains("Non è stato possibile segnare la presenza: l’iscrizione di Chiara Fontana nel frattempo è stata ritirata."), words(byHand.body()))
        assertEquals(0, kit.presenceCount())

        val back = act(s.setup.marta, s.setup, s.chiara, "rimetti", mapOf("giorno" to s.setup.today.toString()))
        assertEquals(200, back.status())
        assertEquals("confirmed", kit.statusOf(s.chiara))
        assertTrue(words(back.body()).contains("Iscrizione rimessa confermata."), words(back.body()))
        assertEquals("Confermata", badges(back.body()).first())
        assertTrue(back.body().contains("""aria-label="Segna presente Chiara Fontana, ${CheckinFormat.longDay(s.setup.today)}""""))
        // A second tap, as if the page were old, is the same confirmation and changes nothing.
        val again = act(s.setup.marta, s.setup, s.chiara, "rimetti", mapOf("giorno" to s.setup.today.toString()))
        assertEquals(200, again.status())
        assertTrue(words(again.body()).contains("Iscrizione rimessa confermata."))
    }

    @Test
    fun `t6 c08 the presences of a withdrawn registration stay and can be taken away, and the card shows them`() {
        val s = Scenario()
        kit.presence(s.chiara, s.setup.yesterday, s.setup.paoloId)

        val html = card(s.setup.marta, s.chiara)

        assertTrue(words(html).contains("Presente Segnata da Paolo Verdi"), words(html))
        val undone = fromCard(s.setup.marta, s.setup, s.chiara, "presenze/togli", day = s.setup.yesterday)
        assertEquals(200, undone.status())
        assertEquals(0, kit.presenceCount())
    }

    // ---- The manleva (criterion 9) -----------------------------------------------------------------------------------------------

    @Test
    fun `t6 c09 the manleva that arrives is marked with the service of TALITA-5 and is the same everywhere, and the presence can be marked without it`() {
        val s = Scenario()
        // Before: «Manleva da ricevere» in the row and in the summary, and the presence is marked all the same, with the notice still there.
        val list = words(s.setup.marta.get("/arrivi").body())
        assertTrue(list.contains("Manleve da ricevere 1 fra gli attesi"), list)
        assertEquals(200, fromCard(s.setup.marta, s.setup, s.nicolo, "presenze").status())
        assertTrue(words(card(s.setup.marta, s.nicolo)).contains("Il modulo firmato non è ancora arrivato."), "the notice stays after the presence")
        assertTrue(words(s.setup.marta.get("/arrivi").body()).contains("Manleve da ricevere 1 fra gli attesi"))

        val received = act(s.setup.marta, s.setup, s.nicolo, "manleva", mapOf("giorno" to s.setup.today.toString()))

        assertEquals(200, received.status())
        assertTrue(dsl.select(REGISTRATION.PAPER_FORM_RECEIVED).from(REGISTRATION).where(REGISTRATION.ID.eq(s.nicolo)).fetchSingle(REGISTRATION.PAPER_FORM_RECEIVED)!!)
        val html = received.body()
        assertTrue(words(html).contains("Manleva segnata come ricevuta."), words(html))
        assertEquals(listOf("Confermata", "Cartacea ricevuta"), badges(html))
        assertTrue(words(html).contains("Manleva Cartacea ricevuta Il modulo firmato è arrivato. Resta di carta, fuori da Talità."), words(html))
        assertFalse(html.contains("Segna come ricevuta"))
        // The same in the list, in the summary and in the detail of TALITA-5.
        val after = words(s.setup.marta.get("/arrivi").body())
        assertTrue(after.contains("Manleve da ricevere Nessuna"), after)
        assertFalse(Regex("""Nicolò Ferri[^<]{0,40}Manleva da ricevere""").containsMatchIn(after))
        assertTrue(words(s.setup.marta.get("/iscrizioni/${s.nicolo}").body()).contains("Cartacea ricevuta"))
        // A registration made online has no signed form to receive: a request by hand is a page that does not exist.
        assertEquals(404, act(s.setup.marta, s.setup, s.luca, "manleva", mapOf("giorno" to s.setup.today.toString())).status())
    }

    @Test
    fun `t6 c05 c22 the detail of TALITA-5 has the way to the card, only for a registration of the gathering of the arrivals`() {
        val s = Scenario()

        val detail = s.setup.marta.get("/iscrizioni/${s.luca}").body()

        assertTrue(Regex("""<a href="/arrivi/${s.luca}"[^>]*>[\s\S]{0,700}?Apri in Arrivi""").containsMatchIn(detail), "«Apri in Arrivi» leads to the card")
        // A gathering that starts later is the one of the arrivals: the registrations of the older one have no card there, and no link.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(s.setup.id)).execute()
        kit.kit.gatherings.make(title = "La convivenza dopo", start = s.setup.today.plusDays(60), status = GatheringStatus.`open`, custodians = listOf(s.setup.martaId))
        assertFalse(s.setup.marta.get("/iscrizioni/${s.luca}").body().contains("Apri in Arrivi"))
    }
}
