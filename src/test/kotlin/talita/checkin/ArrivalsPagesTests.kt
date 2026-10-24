package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
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
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.ui.Alert
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * «Arrivi» as the teachers see it (criteria 1-4, 19-22): the days, the summary, the list in groups, the touch on a row, what another teacher sees, what happens
 * when a touch arrives late. Teachers have entered with the link; the days are those of the day the test runs (yesterday, today, tomorrow), and names and
 * amounts are synthetic. The search by name runs in the browser: its tests are the e2e ones.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ArrivalsPagesTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    private fun htmx(): MockHttpServletRequestDsl.() -> Unit = { header("HX-Request", "true") }

    private fun flash(browser: MockBrowser): String? = ((browser.cookie as MockHttpSession).getAttribute("talita.flash") as? Alert)?.text

    /** The names in the order in which the list shows them. */
    private fun names(html: String): List<String> = Regex("""data-filtro-testo>([^<]+)<""").findAll(html).map { it.groupValues[1] }.toList()

    /** The row of a registration, from its `<li>` to the next one. */
    private fun row(html: String, id: Long): String = Regex("""<li id="riga-$id"[\s\S]*?</li>""").find(html)!!.value

    /** The labels of a row, in order, with a plain space in the amounts. */
    private fun labels(html: String, id: Long): List<String> =
        Regex("""whitespace-nowrap [^"]*">(?:<svg[\s\S]*?</svg>)?([^<]+)</span>""").findAll(row(html, id)).map { it.groupValues[1].replace('\u00a0', ' ').trim() }.toList()

    /**
     * What the tests of the list share: Luca (today and tomorrow, 25 + 10 euros), Anna (every day, 60) and Nicolò (typed in from paper, the signed form not arrived, 60)
     * confirmed; Sara in forse; Jacopo (yesterday only); Chiara withdrawn.
     */
    private inner class Scenario {
        val setup = kit.start()
        private val parent = kit.account()
        val luca = kit.rows.register(setup.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), days = mapOf(setup.today to "full", setup.tomorrow to "lunch"))
        val anna = kit.rows.register(setup.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        val nicolo = kit.rows.register(setup.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = setup.martaId)
        val sara = kit.rows.register(setup.id, kit.account(), "Sara", "Neri", LocalDate.of(2017, 3, 3), status = RegistrationStatus.maybe)
        val jacopo = kit.rows.register(setup.id, kit.account(), "Jacopo", "Leone", LocalDate.of(2014, 9, 9), days = mapOf(setup.yesterday to "full"))
        val chiara = kit.rows.register(setup.id, kit.account(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), status = RegistrationStatus.withdrawn)
    }

    // ---- The page (criteria 1, 2) -----------------------------------------------------------------------------------------------

    @Test
    fun `t6 c01 the page has the gathering, the days to change with a link, the summary and the children by surname and name, the expected first and the withdrawn last`() {
        val s = Scenario()
        val html = s.setup.marta.get("/arrivi").body()
        val text = words(html)

        assertEquals("Arrivi · Talità", title(html))
        assertEquals("Arrivi", heading(html))
        assertTrue(text.contains("Convivenza di settembre 2027 · ${GatheringFormat.range(s.setup.yesterday, s.setup.tomorrow)}"), text)
        assertTrue(Regex("""<a href="/arrivi" aria-current="page"""").containsMatchIn(html), "Arrivi is the current entry")
        // The three days, each a link with its date; the one of today is the current one and is said so, for the eyes and for a screen reader.
        val days = Regex("""<nav aria-label="Giorni della convivenza">([\s\S]*?)</nav>""").find(html)!!.groupValues[1]
        assertEquals(
            listOf("/arrivi?giorno=${s.setup.yesterday}", "/arrivi?giorno=${s.setup.today}", "/arrivi?giorno=${s.setup.tomorrow}"),
            Regex("""<a href="([^"]+)"""").findAll(days).map { it.groupValues[1] }.toList(),
        )
        assertEquals(1, Regex("""aria-current="date"""").findAll(days).count())
        assertTrue(Regex("""<a href="/arrivi\?giorno=${s.setup.today}" aria-current="date"""").containsMatchIn(days))
        assertTrue(days.contains(" (oggi)</span>"), "a screen reader says which day is today")
        assertTrue(days.contains("""aria-hidden="true">oggi</span>"""))
        // The children: the ones expected today first, by surname and name (Rossi Anna before Rossi Luca), Sara in forse among them; then the one who is not expected; the withdrawn one last.
        assertEquals(listOf("Nicolò Ferri", "Sara Neri", "Anna Rossi", "Luca Rossi", "Jacopo Leone", "Chiara Fontana"), names(html))
        assertTrue(text.contains("Attesi ${CheckinFormat.shortDay(s.setup.today)} 4"), text)
        assertTrue(text.contains("Non previsti ${CheckinFormat.shortDay(s.setup.today)} 1 Hanno l’iscrizione, ma non per questo giorno. Per segnarli apri la scheda."), text)
        assertTrue(text.contains("Ritirate 1"), text)
        // «Aggiornato alle», «Aggiorna» and the two cards at the bottom.
        assertTrue(Regex("""Aggiornato alle [0-9]{1,2}:[0-9]{2} Aggiorna""").containsMatchIn(text), text)
        assertTrue(text.contains("Un bambino non è in elenco? Se un genitore ha compilato il modulo di carta, inseriscilo tu: poi lo trovi qui. Inserisci un’iscrizione"), text)
        assertTrue(
            text.contains(
                "Da stampare Registro arrivi Una riga per bambino, con giorni, quota e manleva. Per segnare a mano. Elenco per la struttura Solo nome e cognome, per chi ospita la convivenza. " +
                    "Foglio per i maestri Bambini e recapiti dei genitori. Resta fra i maestri.",
            ),
            text,
        )
        assertTrue(text.contains("Stampa il registro e il foglio prima di partire: se manca la rete, segni a mano e trascrivi dopo."))
        for (print in listOf("/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) assertTrue(html.contains("""href="$print""""), print)
    }

    @Test
    fun `t6 c01 each row has its labels in the order of the design, and what is still to pay, to receive or in doubt is said`() {
        val s = Scenario()
        val html = s.setup.marta.get("/arrivi").body()

        // Anna owes 60 euros; Luca, two days (25 + 10); Nicolò has the signed form still to come and owes the whole 60; Sara is in forse and nobody asks her for money yet.
        assertEquals(listOf("Da pagare ${euro(6000)}"), labels(html, s.anna))
        assertEquals(listOf("Da pagare ${euro(3500)}"), labels(html, s.luca))
        assertEquals(listOf("Manleva da ricevere", "Da pagare ${euro(6000)}"), labels(html, s.nicolo))
        assertEquals(listOf("In forse"), labels(html, s.sara))
        assertEquals(listOf("Da pagare ${euro(2500)}", "Non previsto"), labels(html, s.jacopo))
        assertEquals(listOf("Ritirata"), labels(html, s.chiara))
        // The ones that are expected today have the button; the others are only a link to the card.
        assertTrue(row(html, s.anna).contains("Segna presente"))
        assertFalse(row(html, s.jacopo).contains("Segna presente"))
        assertFalse(row(html, s.chiara).contains("Segna presente"))
        // Every row goes to the card of the same day, with the number of the registration and the day: no name in an address.
        assertTrue(row(html, s.anna).contains("""href="/arrivi/${s.anna}?giorno=${s.setup.today}""""))
        assertFalse(Regex("""href="[^"]*(Anna|Rossi|Ferri)""").containsMatchIn(html), "no name in an address")
    }

    @Test
    fun `t6 c01 a possible duplicate is marked on both rows, and a child who is present today has the label Presente first and the green row`() {
        val s = Scenario()
        val twin = kit.rows.register(s.setup.id, kit.account(), "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        kit.presence(s.anna, s.setup.today, s.setup.martaId)

        val html = s.setup.marta.get("/arrivi").body()

        assertEquals(listOf("Presente", "Possibile doppione", "Da pagare ${euro(6000)}"), labels(html, s.anna))
        assertEquals(listOf("Possibile doppione", "Da pagare ${euro(6000)}"), labels(html, twin))
        assertTrue(Regex("""<li id="riga-${s.anna}"[^>]*bg-success-soft""").containsMatchIn(html))
        assertTrue(words(html).contains("Togli") && words(html).contains(" la presenza di Anna Rossi, "), "the button says whose presence it takes away")
    }

    @Test
    fun `t6 c02 the summary says who is here out of who is expected, who is here and was not, the forms to come, who is in doubt and the money, with the zero in words`() {
        val s = Scenario()

        val before = words(s.setup.marta.get("/arrivi").body())
        val day = CheckinFormat.longDayCapital(s.setup.today)
        assertTrue(before.contains("$day Oggi Stampe Presenti Nessuno su 3 attesi Presenti non previsti Nessuno Manleve da ricevere 1 fra gli attesi In forse 1 non contati"), before)
        // Nobody paid: «Niente», with no line under it. Still to collect: what the confirmed registrations owe (35 + 60 + 60, and 25 of Jacopo, who is for another day).
        assertTrue(before.contains("Incassato in tutta la convivenza Niente Ancora da incassare ${euro(18000)} Delle iscrizioni confermate."), before)

        // Anna and Luca are here, and so is Jacopo, who was not expected today. Marta collected 60 and 10 from a child who was withdrawn since, Paolo 25.
        kit.presence(s.anna, s.setup.today, s.setup.martaId)
        kit.presence(s.luca, s.setup.today, s.setup.paoloId)
        kit.presence(s.jacopo, s.setup.today, s.setup.paoloId)
        kit.payment(s.anna, 6000, s.setup.martaId)
        kit.payment(s.luca, 2500, s.setup.paoloId)
        kit.payment(s.chiara, 1000, s.setup.martaId)
        val after = words(s.setup.marta.get("/arrivi").body())

        assertTrue(after.contains("Presenti 2 su 3 attesi Presenti non previsti 1 Manleve da ricevere 1 fra gli attesi In forse 1 non contati"), after)
        assertTrue(after.contains("Incassato in tutta la convivenza ${euro(9500)} Marta Bianchi ${euro(7000)} · Paolo Verdi ${euro(2500)}"), after)
        // Luca still owes 10, Nicolò 60 and Jacopo 25; Anna paid.
        assertTrue(after.contains("Ancora da incassare ${euro(9500)} Delle iscrizioni confermate."), after)
    }

    @Test
    fun `t6 c01 the day of the address is taken only when it is a day of the gathering, and the page says when it is not today`() {
        val s = Scenario()

        // Yesterday: a past day, with the buttons (to correct) and a notice with «Vai a oggi».
        val past = s.setup.marta.get("/arrivi?giorno=${s.setup.yesterday}").body()
        assertTrue(words(past).contains("Stai guardando ${CheckinFormat.longDay(s.setup.yesterday)}, un giorno passato. Oggi è ${CheckinFormat.longDay(s.setup.today)}. Vai a oggi"), words(past))
        assertTrue(Regex("""<a href="/arrivi\?giorno=${s.setup.today}"[^>]*>Vai a oggi""").containsMatchIn(past))
        assertTrue(words(past).contains("Passato"), "the label of the summary")
        assertTrue(past.contains("Segna presente"), "a past day can still be corrected")
        // Tomorrow: nothing to touch, the rows are links, and the notice says from when.
        val future = s.setup.marta.get("/arrivi?giorno=${s.setup.tomorrow}").body()
        assertTrue(
            words(future).contains("${CheckinFormat.longDayCapital(s.setup.tomorrow)} non è ancora arrivato: le presenze si segnano da quel giorno. Oggi è ${CheckinFormat.longDay(s.setup.today)}. Vai a oggi"),
            words(future),
        )
        assertFalse(future.contains("Segna presente"))
        assertTrue(words(future).contains("Non ancora"))
        // Anything else in the address is ignored with no error: the day is today.
        for (junk in listOf("giorno=2001-01-01", "giorno=abc", "giorno=", "giorno=${s.setup.tomorrow.plusDays(9)}", "giorno=%3Cb%3E")) {
            val answer = s.setup.marta.get("/arrivi?$junk")
            assertEquals(200, answer.status(), junk)
            assertTrue(Regex("""<a href="/arrivi\?giorno=${s.setup.today}" aria-current="date"""").containsMatchIn(answer.body()), junk)
            assertFalse(answer.body().contains("<b>"), junk)
        }
    }

    @Test
    fun `t6 c01 before the start the first day is shown with no buttons, and after the end the last one with them and a notice that it is over`() {
        val before = kit.start(first = 3, last = 5)
        kit.rows.register(before.id, kit.account(), "Luca", "Rossi")
        val early = before.marta.get("/arrivi").body()
        assertTrue(words(early).contains("La convivenza comincia ${CheckinFormat.longDay(before.today.plusDays(3))}: le presenze si segnano da quel giorno."), words(early))
        assertFalse(early.contains("Segna presente"))
        assertTrue(Regex("""<a href="/arrivi\?giorno=${before.today.plusDays(3)}" aria-current="date"""").containsMatchIn(early), "the first day is the chosen one")

        val over = kit.start(first = -5, last = -3)
        kit.rows.register(over.id, kit.account(), "Luca", "Rossi")
        val late = over.marta.get("/arrivi").body()
        assertTrue(words(late).contains("La convivenza è finita. Puoi ancora correggere le presenze, per esempio quando trascrivi il registro di carta."), words(late))
        assertTrue(late.contains("Segna presente"), "the presences of the paper register can still be copied")
        assertTrue(words(late).contains("Attesi ${CheckinFormat.shortDay(over.today.minusDays(3))}"), "the last day is the chosen one")
    }

    // ---- The states with nothing to show (criterion 1) ----------------------------------------------------------------------------

    @Test
    fun `t6 c01 with a gathering that has no registration, with only a draft and with no gathering the page says it and offers what can be done`() {
        val setup = kit.start()
        // No registration: the empty state, with the days and the way to type one in, and no summary, no search, no prints.
        val empty = setup.marta.get("/arrivi").body()
        assertTrue(words(empty).contains("Nessun bambino iscritto Quando i genitori iscrivono i bambini, li trovi qui con gli arrivi. Se un genitore ha compilato il modulo di carta, puoi inserirlo tu. Inserisci un’iscrizione"), words(empty))
        assertTrue(empty.contains("""href="/iscrizioni/nuova?convivenza=${setup.id}&amp;ritorno=arrivi""""), "the link says that it comes from the arrivals")
        assertTrue(empty.contains("""aria-label="Giorni della convivenza""""))
        for (absent in listOf("id=\"riepilogo\"", "data-filtro", "Da stampare")) assertFalse(empty.contains(absent), absent)

        // Only a draft.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.draft).where(GATHERING.ID.eq(setup.id)).execute()
        val draft = setup.marta.get("/arrivi").body()
        assertTrue(
            words(draft).contains("La convivenza «Convivenza di settembre 2027» è ancora in bozza: gli arrivi si segnano dopo l’apertura delle iscrizioni. Apri le iscrizioni dalla pagina della convivenza. Vai alla convivenza"),
            words(draft),
        )
        assertTrue(draft.contains("""href="/convivenza/${setup.id}""""))
        assertFalse(draft.contains("Giorni della convivenza"))

        // Nothing at all.
        kit.kit.gatherings.reset()
        val none = setup.marta.get("/arrivi")
        assertEquals(200, none.status())
        assertTrue(words(none.body()).contains("Nessuna convivenza in programma Gli arrivi si segnano dopo che hai creato la convivenza e aperto le iscrizioni. Crea la convivenza"), words(none.body()))
        assertTrue(none.body().contains("""href="/convivenza/nuova""""))
    }

    // ---- A touch on a row (criterion 4) --------------------------------------------------------------------------------------------

    /** The touch of a row as the page sends it: it asks for the list of the day first (for the code against forgery), then posts. */
    private fun touch(
        browser: MockBrowser,
        setup: CheckinSetup,
        id: Long,
        day: String = setup.today.toString(),
        action: String = "presenze",
        viaHtmx: Boolean = true,
        listDay: LocalDate = setup.today,
    ): MvcResult = browser.submit("/arrivi?giorno=$listDay", "/arrivi/$id/$action", mapOf("giorno" to day), if (viaHtmx) htmx() else ({}))

    @Test
    fun `t6 c04 a touch on Segna presente writes the presence with the moment and the teacher and answers with the row, the summary and the sentence for the screen reader, and nothing else`() {
        val s = Scenario()

        val answer = touch(s.setup.marta, s.setup, s.anna)

        assertEquals(200, answer.status())
        val html = answer.body()
        assertEquals(listOf(s.setup.today), kit.presenceDays(s.anna))
        assertEquals(s.setup.martaId, kit.presenceOf(s.anna, s.setup.today).byAccountId)
        // The row as it is now: green, «Presente» and «Togli», the name in the name of the button, the focus on the new button.
        assertTrue(Regex("""^\s*<li id="riga-${s.anna}"[^>]*bg-success-soft""").containsMatchIn(html), html.take(300))
        assertTrue(html.contains("Presente"))
        // The name of the button is «Togli» and what follows for a screen reader (the label of the wait, «Tolgo…», is not shown, nor read).
        assertTrue(Regex("""Togli</span><span class="hidden busy:inline">Tolgo…</span><span class="sr-only"> la presenza di Anna Rossi, ${CheckinFormat.longDay(s.setup.today)}</span>""").containsMatchIn(html))
        assertTrue(Regex("""<button type="submit" data-fuoco=""""").containsMatchIn(html), "the new button takes the focus")
        assertFalse(html.contains("Segna presente"))
        // The summary comes out of band and has the new number; the announcement goes in the region that is already in the page.
        assertTrue(html.contains("""<section id="riepilogo" aria-labelledby="riepilogo-titolo" hx-swap-oob="true""""))
        assertTrue(words(html).contains("Presenti 1 su 3 attesi"), words(html))
        assertTrue(html.contains("""<p hx-swap-oob="innerHTML:#esito">Presenza segnata: Anna Rossi.</p>"""), html.takeLast(300))
        // Nothing else of the page comes back: not the title, the days, the search, the other children.
        for (other in listOf("<h1", "Giorni della convivenza", "id=\"cerca\"", "Luca Rossi", "Nicolò Ferri", "<nav", "<html")) assertFalse(html.contains(other), "the answer has $other")
        // The page that is drawn afterwards has the row as it is, and no button has the mark of the focus.
        val page = s.setup.marta.get("/arrivi").body()
        assertTrue(Regex("""<li id="riga-${s.anna}"[^>]*bg-success-soft""").containsMatchIn(page))
        assertFalse(page.contains("data-fuoco"), "in the page that is drawn first no button has the mark of the focus")
    }

    @Test
    fun `t6 c04 c06 Togli takes the presence away with no question, and a touch without JavaScript is a normal form that leads back to the row`() {
        val s = Scenario()
        assertEquals(200, touch(s.setup.marta, s.setup, s.anna).status())

        val undone = touch(s.setup.marta, s.setup, s.anna, action = "presenze/togli")

        assertEquals(200, undone.status())
        assertEquals(emptyList(), kit.presenceDays(s.anna))
        assertTrue(undone.body().contains("Segna presente") && !undone.body().contains("bg-success-soft"))
        assertTrue(undone.body().contains("""<p hx-swap-oob="innerHTML:#esito">Presenza tolta: Anna Rossi.</p>"""))
        assertTrue(words(undone.body()).contains("Presenti Nessuno su 3 attesi"))

        // Without JavaScript the same form is a post that answers 303 to the list, at the row, and the presence is there.
        val plain = touch(s.setup.marta, s.setup, s.luca, viaHtmx = false)
        assertEquals(303, plain.status())
        assertEquals("/arrivi?giorno=${s.setup.today}#riga-${s.luca}", plain.location())
        assertEquals(listOf(s.setup.today), kit.presenceDays(s.luca))
        // The forms of the page are posts with an address of their own and a code against forgery.
        val html = s.setup.marta.get("/arrivi").body()
        assertTrue(Regex("""<form method="post" action="/arrivi/${s.luca}/presenze/togli" hx-post="/arrivi/${s.luca}/presenze/togli" hx-target="closest li" hx-swap="outerHTML"""").containsMatchIn(html))
        assertTrue(Regex("""action="/arrivi/${s.anna}/presenze"[\s\S]{0,400}name="_csrf"""").containsMatchIn(html))
    }

    // ---- Two teachers (criteria 19, 20) --------------------------------------------------------------------------------------------

    @Test
    fun `t6 c19 what one teacher marks the other sees in the list and in every answer, and nothing refreshes by itself`() {
        val s = Scenario()
        assertEquals(200, touch(s.setup.marta, s.setup, s.luca).status())

        val html = s.setup.paolo.get("/arrivi").body()

        assertTrue(Regex("""<li id="riga-${s.luca}"[^>]*bg-success-soft""").containsMatchIn(html), "Paolo sees Luca present")
        assertTrue(words(html).contains("Presenti 1 su 3 attesi"))
        // There is no polling of any kind: the page says when it was made and leaves the refresh to the person.
        assertFalse(html.contains("hx-trigger"))
        assertTrue(words(html).contains("Aggiorna"))
        val script = String(javaClass.getResourceAsStream("/static/js/app.js")!!.readAllBytes(), Charsets.UTF_8)
        for (automatic in listOf("setInterval", "EventSource", "hx-trigger")) assertFalse(script.contains(automatic), "app.js has $automatic")
        // Each answer is the database of that moment: Paolo marks Anna and the summary that comes back has Luca, marked by Marta.
        val answer = touch(s.setup.paolo, s.setup, s.anna)
        assertTrue(words(answer.body()).contains("Presenti 2 su 3 attesi"), words(answer.body()))
    }

    @Test
    fun `t6 c20 a touch on a child who is present already, or a Togli on one who is not, is not an error, the row is as it is with a note that says who and when`() {
        val s = Scenario()
        assertEquals(200, touch(s.setup.marta, s.setup, s.luca).status())
        val time = CheckinFormat.time(kit.presenceOf(s.luca, s.setup.today).at)

        // Paolo touches the same row, as if his page were old: 200, the row as it is and the note.
        val again = touch(s.setup.paolo, s.setup, s.luca)
        assertEquals(200, again.status())
        assertEquals(1, kit.presenceCount())
        assertTrue(words(again.body()).contains("Era già presente: l’ha segnata Marta Bianchi alle $time."), words(again.body()))
        assertTrue(again.body().contains("""<p hx-swap-oob="innerHTML:#esito">Luca Rossi era già presente: l’ha segnata Marta Bianchi alle $time.</p>"""))
        assertTrue(again.body().contains("bg-success-soft"), "the row shows what is true: present")

        // Taking away what another teacher took away already.
        assertEquals(200, touch(s.setup.marta, s.setup, s.luca, action = "presenze/togli").status())
        val gone = touch(s.setup.paolo, s.setup, s.luca, action = "presenze/togli")
        assertEquals(200, gone.status())
        assertTrue(words(gone.body()).contains("La presenza era già stata tolta."))
        assertEquals(0, kit.presenceCount())
    }

    // ---- What cannot be done any more (criteria 8, 21) -------------------------------------------------------------------------------

    @Test
    fun `t6 c08 c21 a touch on a registration that was withdrawn meanwhile writes nothing and answers 409 with the row as it is and a note`() {
        val s = Scenario()
        assertTrue(s.setup.marta.get("/arrivi").body().contains("Segna presente"))
        // The registration is withdrawn while the page is open.
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(s.anna)).execute()

        val answer = touch(s.setup.marta, s.setup, s.anna)

        assertEquals(409, answer.status())
        assertEquals(0, kit.presenceCount())
        val text = words(answer.body())
        assertTrue(text.contains("Anna Rossi Ritirata"), text)
        assertTrue(text.contains("Non segnata: nel frattempo l’iscrizione è stata ritirata."), text)
        assertFalse(answer.body().contains("Segna presente"), "the row is not a button any more")
        assertTrue(answer.body().contains("""<p hx-swap-oob="innerHTML:#esito">Anna Rossi non è stata segnata: l’iscrizione è stata ritirata.</p>"""))
    }

    @Test
    fun `t6 c06 c21 a day after today, one that is not of the gathering and one that is not a date are refused with 409 and nothing is written`() {
        val s = Scenario()

        val tomorrow = touch(s.setup.marta, s.setup, s.anna, day = s.setup.tomorrow.toString())
        assertEquals(409, tomorrow.status())
        assertTrue(words(tomorrow.body()).contains("Non segnata: quel giorno non è ancora arrivato."))
        val outside = touch(s.setup.marta, s.setup, s.anna, day = s.setup.today.plusDays(10).toString())
        assertEquals(409, outside.status())
        assertTrue(words(outside.body()).contains("Non segnata: nel frattempo le date della convivenza sono cambiate. Aggiorna la pagina."))
        val junk = touch(s.setup.marta, s.setup, s.anna, day = "ieri")
        assertEquals(409, junk.status())
        assertEquals(0, kit.presenceCount())
        // The row of a refused touch is not a button, and the summary is the one of today: nothing is invented.
        assertFalse(outside.body().contains("Segna presente"))
        // The same refusal without JavaScript: a notice at the top of the list.
        val plain = touch(s.setup.marta, s.setup, s.anna, day = s.setup.tomorrow.toString(), viaHtmx = false)
        assertEquals(303, plain.status())
        assertEquals("Anna Rossi non è stata segnata: quel giorno non è ancora arrivato.", flash(s.setup.marta))
        assertTrue(words(s.setup.marta.get(plain.location()!!.substringBefore('#')).body()).contains("Anna Rossi non è stata segnata: quel giorno non è ancora arrivato."))
    }

    // ---- The paper form and the way back (criterion 22) ------------------------------------------------------------------------------

    @Test
    fun `t6 c22 the links to the paper form of TALITA-5 say that they come from the arrivals, and the form that is saved leads to the card of the new registration`() {
        val s = Scenario()
        val html = s.setup.marta.get("/arrivi").body()
        val href = "/iscrizioni/nuova?convivenza=${s.setup.id}&amp;ritorno=arrivi"
        assertEquals(2, Regex("""href="${Regex.escape(href)}"""").findAll(html).count(), "the card «Un bambino non è in elenco?» and the empty result of the search")
        assertTrue(Regex("""<h2[^>]*>Nessun bambino con questo nome</h2>""").containsMatchIn(html))
        assertTrue(html.contains("<div hidden data-filtro-vuoto>"))

        // The form remembers where it came from in a field that holds no data, and «Annulla» goes back to the arrivals.
        val form = s.setup.marta.get("/iscrizioni/nuova?convivenza=${s.setup.id}&ritorno=arrivi").body()
        assertTrue(form.contains("""<input type="hidden" name="ritorno" value="arrivi">"""))
        assertTrue(Regex("""href="/arrivi"[^>]*>Annulla""").containsMatchIn(form), "Annulla leads back to the arrivals")
        assertFalse(s.setup.marta.get("/iscrizioni/nuova?convivenza=${s.setup.id}").body().contains("name=\"ritorno\""))

        // Saved, it leads to the card of the new registration, with the notice of TALITA-5.
        val fields = mapOf(
            "convivenza" to s.setup.id.toString(), "ritorno" to "arrivi", "phone" to "333 987 6543", "children[0].firstName" to "Elena", "children[0].lastName" to "Marino",
            "children[0].birthDay" to "1", "children[0].birthMonth" to "10", "children[0].birthYear" to "2016", "children[0].presence" to "all_days", "children[0].status" to "confirmed",
        )
        val saved = s.setup.marta.submit("/iscrizioni/nuova?convivenza=${s.setup.id}&ritorno=arrivi", "/iscrizioni/nuova", fields)
        assertEquals(303, saved.status())
        assertEquals("/arrivi/${kit.lastRegistrationId()}", saved.location())
        val card = s.setup.marta.get(saved.location()!!)
        assertTrue(words(card.body()).contains("Iscrizione inserita."), words(card.body()))
        assertEquals("Elena Marino", heading(card.body()))
        // Without the field it leads where it always did.
        val other = s.setup.marta.submit("/iscrizioni/nuova?convivenza=${s.setup.id}", "/iscrizioni/nuova", fields - "ritorno" + ("children[0].firstName" to "Sole"))
        assertTrue(other.location()!!.startsWith("/iscrizioni/"), other.location())
    }
}
