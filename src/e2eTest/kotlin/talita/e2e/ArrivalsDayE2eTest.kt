package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.options.RequestOptions
import org.jooq.impl.DSL
import talita.checkin.CheckinFormat
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.PAYMENT
import talita.jooq.tables.references.REGISTRATION
import talita.registration.Notes
import talita.registration.RegistrationFormat
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A whole day of a teacher on a real browser, on a phone and on a desktop, with axe (criterion 32): the list, the search, the touch on a row, the card with a payment and the
 * notes, what the other teacher sees, and the three prints with what was done in the day. The steps are the ones of the day at the door; every page is checked with axe.
 * Names, amounts and notes are synthetic.
 */
@E2eApplication
class ArrivalsDayE2eTest {

    private fun Page.shownNames(): List<String> = locator("[data-filtro-riga]:not([hidden]) [data-filtro-testo]").allInnerTexts().map { it.trim() }

    private fun String.plain(): String = replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    @E2eTest
    fun `t6 c32 a day of check-in goes from the search to the prints, with what the other teacher sees, and no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val parent = e2e.someParent()
        val rows = e2e.rows()
        val anna = rows.register(w.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), phone = "333 123 4567", notes = Notes("Arachidi", null, null))
        val luca = rows.register(w.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), phone = "333 123 4567", days = mapOf(w.today to "full", w.tomorrow to "lunch"))
        rows.register(w.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), phone = "333 987 6543", enteredBy = w.martaId)
        rows.register(w.id, e2e.someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3), phone = "333 123 4512", status = RegistrationStatus.maybe)
        rows.register(w.id, e2e.someParent(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), phone = "333 123 4599", status = RegistrationStatus.withdrawn)
        val dayName = CheckinFormat.longDay(w.today)

        // The list, and the search for the two Rossi.
        e2e.open("/arrivi")
        assertAccessible(page, "list of the day (${e2e.profile.label})")
        page.locator("#cerca").fill("rossi")
        assertEquals(listOf("Anna Rossi", "Luca Rossi"), page.shownNames())
        assertThat(page.locator("#cerca-esito")).hasText("Trovati 2 bambini.")

        // One touch on the row: Anna is here, with the search still there and the summary changed.
        e2e.press(page.buttonNamed("Segna presente Anna Rossi, $dayName"))
        assertThat(page.locator("#riga-$anna")).containsText("Presente")
        assertThat(page.locator("#esito")).hasText("Presenza segnata: Anna Rossi.")
        assertEquals(listOf("Anna Rossi", "Luca Rossi"), page.shownNames())
        assertEquals(1, e2e.dsl().fetchCount(CHECKIN, CHECKIN.REGISTRATION_ID.eq(anna)))

        // The card of Anna: the money, then the notes, which are open only after their reading.
        e2e.touch(page.locator("#riga-$anna a").first())
        assertThat(page.locator("h1")).hasText("Anna Rossi")
        assertThat(page.locator("#scheda")).containsText("Segnata da Giulia Serra alle ")
        e2e.press(page.locator("#pagamenti").buttonNamed("Registra il pagamento"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Pagamento registrato.")
        assertEquals(6000, e2e.dsl().select(DSL.sum(PAYMENT.AMOUNT_CENTS)).from(PAYMENT).where(PAYMENT.REGISTRATION_ID.eq(anna)).fetchSingle().value1()!!.toInt())
        assertThat(page.locator("#pagamenti")).containsText("Pagata")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS, HEALTH_NOTE_ACCESS.REGISTRATION_ID.eq(anna)))
        assertAccessible(page, "card of the day (${e2e.profile.label})")

        // The other teacher sees the presence and the money in the list, and nothing of the notes.
        val other = e2e.newPage(teacher = true)
        other.navigate(e2e.url("/arrivi"))
        other.waitForSettledContent()
        assertThat(other.locator("#riga-$anna")).containsText("Presente")
        assertThat(other.locator("#riepilogo")).containsText("Incassato in tutta la convivenza")
        assertTrue(other.locator("#riepilogo").innerText().plain().contains("60 €"), other.locator("#riepilogo").innerText())
        assertFalse(other.content().contains("Arachidi"), "the notes are not in the list")
        other.close()

        // The prints, with what was done: the X of Anna and her payment in the register, the present of the day in the list for the venue, the phones in the sheet.
        e2e.open("/arrivi/registro")
        val register = page.locator("main table.tabella-stampa").first()
        val annaRow = register.locator("tbody tr").filter(Locator.FilterOptions().setHasText("Anna Rossi"))
        assertTrue(annaRow.innerText().plain().contains("60 €"), annaRow.innerText())
        assertEquals(1, annaRow.locator(".casella:has-text('X')").count(), "the presence that was marked is an X in its box")
        assertAccessible(page, "register after a day (${e2e.profile.label})")

        e2e.open("/arrivi/elenco?giorno=${w.today}")
        assertThat(page.locator("main h2").first()).containsText("· 1")
        assertThat(page.locator("main table tbody tr").first()).containsText("Rossi")
        assertAccessible(page, "list for the venue after a day (${e2e.profile.label})")

        e2e.open("/arrivi/foglio")
        assertThat(page.locator("main table tbody")).containsText("333 123 4567")
        assertThat(page.locator("main")).containsText("Questo foglio contiene i recapiti dei genitori. Tienilo fra i maestri e distruggilo alla fine della convivenza.")
        assertFalse(page.content().contains("Arachidi"), "no note on health in any print")
        assertAccessible(page, "sheet for the teachers after a day (${e2e.profile.label})")
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS, HEALTH_NOTE_ACCESS.REGISTRATION_ID.eq(anna)), "printing read nothing: one reading, the one of the card")
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN, CHECKIN.REGISTRATION_ID.eq(luca)))
    }

    @E2eTest
    fun `t6 c32 the states of the list, of the card and of the prints have no violation of axe, with no gathering, a draft, no registration, before the start and after the end`(e2e: E2e) {
        val page = e2e.page
        val label = e2e.profile.label

        e2e.arrivalsGathering()
        GatheringSupport(e2e.dsl(), e2e.application.getBean(Clock::class.java)).reset()
        e2e.open("/arrivi")
        assertThat(page.locator("main h2")).hasText("Nessuna convivenza in programma")
        assertAccessible(page, "arrivals with no gathering ($label)")

        e2e.arrivalsGathering(status = GatheringStatus.draft)
        e2e.open("/arrivi")
        assertThat(page.locator("main")).containsText("è ancora in bozza")
        assertAccessible(page, "arrivals with a draft ($label)")

        e2e.arrivalsGathering()
        e2e.open("/arrivi")
        assertThat(page.locator("main h2")).hasText("Nessun bambino iscritto")
        assertAccessible(page, "arrivals with no registration ($label)")
        for (path in listOf("/arrivi/registro", "/arrivi/foglio", "/arrivi/elenco")) {
            e2e.open(path)
            assertAccessible(page, "$path with nothing to print ($label)")
        }

        for ((first, last, name) in listOf(Triple(3L, 5L, "before the start"), Triple(-5L, -3L, "after the end"))) {
            val w = e2e.arrivalsGathering(first, last)
            val rows = e2e.rows()
            val parent = e2e.someParent()
            val twin = rows.register(w.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.maybe)
            rows.register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9))
            val paper = rows.register(w.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = w.martaId)
            val withdrawn = rows.register(w.id, e2e.someParent(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), status = RegistrationStatus.withdrawn)
            e2e.payment(withdrawn, 6000, w.martaId)
            for (path in listOf("/arrivi", "/arrivi/$twin", "/arrivi/$paper", "/arrivi/$withdrawn", "/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
                e2e.open(path)
                assertAccessible(page, "$path $name ($label)")
            }
        }
    }

    // ---- Added by the QA of TALITA-6: what the other e2e tests of the ticket leave to the unit tests (criteria 2, 13-15, 18, 19, 22, 28-30 and the pages with no JavaScript) ----

    /** What a person reads in the block, on one line, with the non-breaking spaces as plain ones. */
    private fun Locator.said(): String = innerText().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    private fun said(cents: Int): String = RegistrationFormat.money(cents).replace(' ', ' ')

    @E2eTest
    fun `t6 c02 c27 the summary says what each teacher collected, the zero in words and what is still to be collected, and it has no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val rows = e2e.rows()
        val anna = rows.register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        val luca = rows.register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        rows.register(w.id, e2e.someParent(), "Nicolò", "Ferri", LocalDate.of(2015, 6, 2))
        val sara = rows.register(w.id, e2e.someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3), status = RegistrationStatus.maybe)
        val chiara = rows.register(w.id, e2e.someParent(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), status = RegistrationStatus.withdrawn)

        // Nobody has paid: the zero is a word, there is no line for the teachers, and the three confirmed owe 60 euros each (the one in doubt is not counted).
        e2e.open("/arrivi")
        val summary = page.locator("#riepilogo")
        assertTrue(summary.said().contains("Incassato in tutta la convivenza Niente Ancora da incassare ${said(18000)} Delle iscrizioni confermate."), summary.said())
        assertAccessible(page, "summary with no payment (${e2e.profile.label})")

        // Three teachers collect: Marta 60 + 25, Giulia (the person of the test) 10, Paolo 20 for the one in doubt and 60 for the one withdrawn later. The total counts every payment, the
        // line under it is by surname, and what is still to be collected is only the balance of the confirmed ones: 25 for Luca and 60 for Nicolò.
        e2e.payment(anna, 6000, w.martaId)
        e2e.payment(luca, 2500, w.martaId)
        e2e.payment(luca, 1000, w.meId)
        e2e.payment(sara, 2000, w.paoloId)
        e2e.payment(chiara, 6000, w.paoloId)
        e2e.open("/arrivi")
        assertTrue(
            summary.said().contains(
                "Incassato in tutta la convivenza ${said(17500)} Marta Bianchi ${said(8500)} · Giulia Serra ${said(1000)} · Paolo Verdi ${said(8000)} Ancora da incassare ${said(8500)} Delle iscrizioni confermate.",
            ),
            summary.said(),
        )
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "the line of the teachers goes to a new line at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        // The line «dl > div > span» of the mockup that axe flagged as serious is not in the real page: the money is made of dt and dd.
        assertAccessible(page, "summary with the money of three teachers (${e2e.profile.label})")
    }

    @E2eTest
    fun `t6 c18 nothing in the list, in the closed card and in the prints says that a child has notes on health, and none of the pages writes a reading`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val rows = e2e.rows()
        val withNotes = rows.register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", "Lattosio", "Gocce della sera"))
        val without = rows.register(w.id, e2e.someParent(), "Piero", "Galli", LocalDate.of(2019, 1, 9))
        // The names and the numbers are the only thing that may differ between two pages that say the same.
        fun shape(html: String) = html.replace(Regex("value=\"[^\"]*\""), "value=\"\"").replace(Regex("Anna Rossi|Piero Galli"), "X").replace(Regex("\\d+"), "N").replace(Regex("\\s+"), " ")

        e2e.open("/arrivi")
        val row = { id: Long -> page.locator("#riga-$id").evaluate("e => e.outerHTML") as String }
        assertEquals(shape(row(without)), shape(row(withNotes)), "the row of a child with notes is the row of a child without")
        val closed = mutableListOf<String>()
        for (id in listOf(withNotes, without)) {
            e2e.open("/arrivi/$id")
            closed += shape(page.locator("#segnalazioni").evaluate("e => e.outerHTML") as String)
        }
        assertEquals(closed[0], closed[1], "the card of the notes, closed, is the same for a child with notes and for a child without")

        for (path in listOf("/arrivi", "/arrivi/$withNotes", "/arrivi/registro", "/arrivi/elenco", "/arrivi/elenco?giorno=${w.today}", "/arrivi/foglio")) {
            e2e.open(path)
            val html = page.content()
            for (word in listOf("Arachidi", "Lattosio", "Gocce")) assertFalse(html.contains(word), "$path tells $word")
        }
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS), "no page of the arrivals wrote a reading, only the button does")
    }

    @E2eTest
    fun `t6 c22 from the arrivals a child with no registration is typed in from the paper form, Annulla comes back, and the saved registration leads to its card`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()

        e2e.open("/arrivi")
        assertThat(page.locator("main h2")).hasText("Nessun bambino iscritto")
        e2e.press(page.locator("main").linkNamed("Inserisci un’iscrizione"))
        assertThat(page).hasURL(e2e.url("/iscrizioni/nuova?convivenza=${w.id}&ritorno=arrivi"))
        assertThat(page.locator("h1")).hasText("Inserisci un’iscrizione")
        e2e.press(page.locator("main").linkNamed("Annulla"))
        assertThat(page).hasURL(e2e.url("/arrivi"))

        e2e.press(page.locator("main").linkNamed("Inserisci un’iscrizione"))
        page.locator("#telefono").fill("333 987 6543")
        page.fillBlock(0, "Elena", "Marino", "1", "10", "2016")
        page.locator("input[name='children[0].presence'][value='all_days']").check()
        e2e.press(page.locator("main").buttonNamed("Inserisci l’iscrizione"))

        page.waitForURL(Pattern.compile(".*/arrivi/\\d+$"))
        val id = e2e.dsl().select(REGISTRATION.ID).from(REGISTRATION).fetchSingle(REGISTRATION.ID)!!
        assertThat(page).hasURL(e2e.url("/arrivi/$id"))
        assertThat(page.locator("h1")).hasText("Elena Marino")
        assertThat(page.locator("[role=status]").first()).containsText("Iscrizione inserita.")
        assertThat(page.locator("#scheda")).containsText("Da ricevere")
        assertAccessible(page, "card of the registration that was just typed in (${e2e.profile.label})")
        e2e.press(page.locator("main").linkNamed("Torna agli arrivi"))
        assertThat(page.locator("#riga-$id")).containsText("Elena Marino")
        assertThat(page.locator("#riga-$id")).containsText("Manleva da ricevere")
    }

    @E2eTest(teacher = false)
    fun `t6 c28 a parent with a child registered cannot reach any address of the arrivals, not even by hand, and someone with no access is sent to the access page`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(meTeacher = false)
        val rows = e2e.rows()
        val mine = rows.register(w.id, w.meId, "Anna", "Rossi", LocalDate.of(2019, 1, 9), phone = "333 123 4567")
        val other = rows.register(w.id, e2e.someParent(), "Piero", "Galli", LocalDate.of(2016, 4, 4))
        e2e.payment(mine, 1000, w.martaId)
        val payment = e2e.dsl().select(PAYMENT.ID).from(PAYMENT).fetchSingle(PAYMENT.ID)!!
        val gets = listOf(
            "/arrivi", "/arrivi?giorno=${w.today}", "/arrivi/$mine", "/arrivi/$mine?giorno=${w.tomorrow}", "/arrivi/$other", "/arrivi/registro", "/arrivi/elenco",
            "/arrivi/elenco?giorno=${w.today}", "/arrivi/foglio", "/arrivi/$mine/pagamenti/$payment/togli", "/arrivi/999999999",
        )

        // Every address, also the one of a child of the parent and the one of a child that does not exist (no way to tell them apart): the 403 of TALITA-3, with nothing of the children.
        for (path in gets) {
            val response = e2e.open(path)
            assertEquals(403, response.status(), path)
            assertThat(page.locator("h1")).hasText("Non puoi vedere questa pagina")
            val html = page.content()
            for (secret in listOf("Rossi", "Galli", "333 123 4567", "Segna presente", "Incassato")) assertFalse(html.contains(secret), "$path shows «$secret»")
            assertEquals(0, page.mainNavigation().linkNamed("Arrivi").count(), "$path: the entry is not in the navigation of a parent")
        }

        // Every request that changes something, with a good CSRF code, as a person who writes it by hand: 403, and nothing changes in the database.
        e2e.open("/profilo")
        val csrf = page.locator("input[name=_csrf]").first().inputValue()
        val posts = listOf(
            "/arrivi/$mine/presenze", "/arrivi/$mine/presenze/togli", "/arrivi/$mine/pagamenti", "/arrivi/$mine/pagamenti/$payment/togli", "/arrivi/$mine/manleva",
            "/arrivi/$mine/rimetti", "/arrivi/$mine/segnalazioni", "/arrivi/$other/presenze", "/arrivi/999999999/presenze",
        )
        for (path in posts) {
            for (htmx in listOf(false, true)) {
                val form = FormData.create().set("_csrf", csrf).set("giorno", w.today.toString()).set("importo", "10").set("richiesta", UUID.randomUUID().toString())
                val options = RequestOptions.create().setMaxRedirects(0).setForm(form)
                if (htmx) options.setHeader("HX-Request", "true")
                assertEquals(403, page.request().post(e2e.url(path), options).status(), "POST $path (htmx: $htmx)")
            }
        }
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))
        assertEquals(1, e2e.dsl().fetchCount(PAYMENT))
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))

        // The same, as the browser does it for a piece of the page: the page stays as it is and a message says «Accesso negato».
        page.addToContent(
            """<form id="a-mano" method="post" action="/arrivi/$mine/presenze" hx-post="/arrivi/$mine/presenze" hx-target="#bersaglio" hx-swap="outerHTML">
                 <input type="hidden" name="_csrf" value="$csrf"><input type="hidden" name="giorno" value="${w.today}">
                 <button id="manda" type="submit">Manda</button></form>
               <div id="bersaglio"><p>Contenuto di prima</p></div>""",
        )
        e2e.touch(page.locator("#manda"))
        assertThat(page.messages().filter(Locator.FilterOptions().setHasText("Accesso negato"))).hasCount(1)
        assertThat(page.locator("#bersaglio")).hasText("Contenuto di prima")
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))

        // Someone with no access at all goes to the page of access, from every address.
        val stranger = e2e.newPage(signedIn = false)
        for (path in gets) {
            stranger.navigate(e2e.url(path))
            stranger.waitForLoadState()
            assertThat(stranger).hasURL(e2e.url("/accedi"))
        }
    }

    @E2eTest
    fun `t6 c29 a day that has a presence cannot be taken out of the dates, even when only a withdrawn registration has it, and it can when the presence is taken away`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        // The child is not expected yesterday: a presence of yesterday is a day that was not foreseen, and it is marked with the real button of the card.
        val child = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), days = mapOf(w.today to "full", w.tomorrow to "full"))
        e2e.open("/arrivi/$child")
        e2e.press(page.buttonNamed("Segna presente Anna Rossi, ${CheckinFormat.longDay(w.yesterday)}"))
        assertThat(page.locator("#scheda [role=alert]").first()).containsText("Presenza segnata.")
        e2e.dsl().update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(child)).execute()

        e2e.open("/convivenza/${w.id}/date")
        page.locator("#primo-giorno").fill(w.today.toString())
        e2e.press(page.buttonNamed("Salva le modifiche"))
        assertThat(page.locator("main")).containsText(Pattern.compile("1 presenza segnata"))
        assertThat(page.locator("#primo-giorno")).hasAttribute("aria-invalid", "true")
        assertEquals(w.yesterday, e2e.dsl().select(GATHERING.START_DATE).from(GATHERING).fetchSingle(GATHERING.START_DATE), "the dates did not change")

        // The presence is taken away from the card of the withdrawn registration (it stays there, with its Togli), and now the day can go.
        e2e.open("/arrivi/$child")
        e2e.press(page.buttonNamed("Togli la presenza di Anna Rossi, ${CheckinFormat.longDay(w.yesterday)}"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Presenza tolta.")
        e2e.open("/convivenza/${w.id}/date")
        page.locator("#primo-giorno").fill(w.today.toString())
        e2e.press(page.buttonNamed("Salva le modifiche"))
        assertThat(page.locator("main [role=status]").first()).containsText("salvati")
        assertEquals(w.today, e2e.dsl().select(GATHERING.START_DATE).from(GATHERING).fetchSingle(GATHERING.START_DATE))
    }

    @E2eTest
    fun `t6 c15 before the start the payments are registered and taken away and no presence is marked, after the end the presences and the payments are still corrected, and the pages say so`(e2e: E2e) {
        val page = e2e.page
        val label = e2e.profile.label

        // Before the start: three days from now.
        val before = e2e.arrivalsGathering(first = 3, last = 5)
        val anna = e2e.rows().register(before.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        val firstDay = before.today.plusDays(3)
        e2e.open("/arrivi")
        assertThat(page.locator("main")).containsText("La convivenza comincia ${CheckinFormat.longDay(firstDay)}: le presenze si segnano da quel giorno.")
        assertEquals(0, page.getByText("Segna presente").count(), "before the start the list has no button")
        e2e.open("/arrivi/$anna")
        assertThat(page.locator("#scheda")).containsText("La convivenza non è ancora iniziata: le presenze si segnano da ${CheckinFormat.longDay(firstDay)}. I pagamenti si possono già registrare.")
        assertEquals(0, page.getByText("Segna presente").count(), "before the start the card has no button")
        assertAccessible(page, "card before the start ($label)")
        page.locator("#importo").fill("20")
        e2e.press(page.locator("#pagamenti").buttonNamed("Registra il pagamento"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Pagamento registrato.")
        assertEquals(1, e2e.dsl().fetchCount(PAYMENT))
        e2e.press(page.locator("#pagamenti").linkNamed("Togli il pagamento di ${RegistrationFormat.money(2000)}"))
        e2e.touch(page.openDialog().buttonNamed("Togli il pagamento"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Pagamento tolto.")
        assertEquals(0, e2e.dsl().fetchCount(PAYMENT))

        // After the end: three days ago and the two before. The list and the card say that the gathering is over, and the presence of the last day is marked and taken away.
        val after = e2e.arrivalsGathering(first = -5, last = -3)
        val luca = e2e.rows().register(after.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val lastDay = after.today.minusDays(3)
        e2e.open("/arrivi")
        assertThat(page.locator("main")).containsText("La convivenza è finita. Puoi ancora correggere le presenze, per esempio quando trascrivi il registro di carta.")
        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${CheckinFormat.longDay(lastDay)}"))
        assertThat(page.locator("#riga-$luca")).containsText("Presente")
        assertEquals(1, e2e.dsl().fetchCount(CHECKIN))
        e2e.open("/arrivi/$luca?giorno=$lastDay")
        assertThat(page.locator("#scheda")).containsText("La convivenza è finita. Puoi ancora correggere presenze e pagamenti, per esempio quando trascrivi il registro di carta.")
        assertAccessible(page, "card after the end ($label)")
        page.locator("#importo").fill("10")
        e2e.press(page.locator("#pagamenti").buttonNamed("Registra il pagamento"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Pagamento registrato.")
        e2e.press(page.buttonNamed("Togli la presenza di Luca Rossi, ${CheckinFormat.longDay(lastDay)}"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Presenza tolta.")
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))
        assertEquals(1, e2e.dsl().fetchCount(PAYMENT))
    }

    @E2eTest
    fun `t6 c14 when the teachers change the fees after a payment the payment stays as it was and only what is due and the balance change`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        e2e.payment(anna, 3500, w.paoloId)
        val money = page.locator("#pagamenti")

        e2e.open("/arrivi/$anna")
        assertTrue(money.said().contains("Dovuta ${said(6000)} Pagata ${said(3500)} Saldo Da pagare ${said(2500)}"), money.said())

        e2e.open("/convivenza/${w.id}/quote")
        page.locator("#quota-intera").fill("70")
        e2e.press(page.buttonNamed("Salva le modifiche"))
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Quote salvate.")

        e2e.open("/arrivi/$anna")
        assertTrue(money.said().contains("Dovuta ${said(7000)} Pagata ${said(3500)} Saldo Da pagare ${said(3500)}"), money.said())
        assertEquals(listOf(3500), e2e.dsl().select(PAYMENT.AMOUNT_CENTS).from(PAYMENT).fetch(PAYMENT.AMOUNT_CENTS).map { it!! }, "the payment is the one that was registered")
        e2e.open("/arrivi")
        assertTrue(page.locator("#riepilogo").said().contains("Ancora da incassare ${said(3500)}"), page.locator("#riepilogo").said())
    }

    @E2eTest
    fun `t6 c13 a payment that another teacher already took away is shown as it is now, with a sentence and no error`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val luca = e2e.rows().register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        e2e.payment(luca, 2500, w.paoloId)
        e2e.open("/arrivi/$luca")
        val other = e2e.newPage(teacher = true)
        other.navigate(e2e.url("/arrivi/$luca"))
        other.waitForSettledContent()
        assertThat(other.locator("#pagamenti")).containsText("Paolo Verdi")

        e2e.press(page.locator("#pagamenti").linkNamed("Togli il pagamento di ${RegistrationFormat.money(2500)}"))
        e2e.touch(page.openDialog().buttonNamed("Togli il pagamento"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Pagamento tolto.")

        // The second page still shows the payment: the touch answers with the state of now.
        e2e.press(other.locator("#pagamenti").linkNamed("Togli il pagamento di ${RegistrationFormat.money(2500)}"))
        e2e.touch(other.openDialog().buttonNamed("Togli il pagamento"))
        assertThat(other.locator("#scheda")).containsText("Quel pagamento era già stato tolto.")
        assertThat(other.locator("#pagamenti")).containsText("Nessun pagamento registrato.")
        assertEquals(0, other.messages().count(), "it is an answer, not a failure")
        assertEquals(0, e2e.dsl().fetchCount(PAYMENT))
    }

    @E2eTest
    fun `t6 c03 c04 c13 c17 c24 without JavaScript the list is whole with no search field, every touch is a form that comes back to its row, and the card, the payments, the notes and the prints work`(e2e: E2e) {
        val w = e2e.arrivalsGathering()
        val rows = e2e.rows()
        val parent = e2e.someParent()
        val anna = rows.register(w.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        rows.register(w.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val plain = e2e.newPage { setJavaScriptEnabled(false) }
        val dayName = CheckinFormat.longDay(w.today)

        // With JavaScript off Playwright cannot tell when the entrance of the content is over, so every page is let to settle before it is touched.
        fun touch(target: Locator) {
            plain.settled()
            e2e.touch(target)
            plain.waitForLoadState()
            plain.settled()
        }
        plain.navigate(e2e.url("/arrivi"))
        plain.settled()
        assertThat(plain.locator("[data-filtro]")).isHidden()
        assertEquals(2, plain.locator("[data-filtro-riga]:visible").count(), "the list is whole")
        touch(plain.buttonNamed("Segna presente Anna Rossi, $dayName"))
        assertThat(plain).hasURL(Pattern.compile(".*/arrivi\\?giorno=${w.today}#riga-$anna"))
        assertThat(plain.locator("#riga-$anna")).containsText("Presente")
        assertEquals(1, e2e.dsl().fetchCount(CHECKIN))
        touch(plain.buttonNamed("Togli la presenza di Anna Rossi, $dayName"))
        assertThat(plain.locator("#riga-$anna")).not().containsText("Presente")
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))

        // The card: the payment with its form, the confirmation page of «Togli» (the title is the heading), the notes after their reading.
        plain.navigate(e2e.url("/arrivi/$anna"))
        plain.settled()
        plain.locator("#importo").fill("20")
        touch(plain.locator("#pagamenti").buttonNamed("Registra il pagamento"))
        assertThat(plain.locator("#scheda [role=status]").first()).hasText("Pagamento registrato.")
        assertEquals(1, e2e.dsl().fetchCount(PAYMENT))
        touch(plain.locator("#pagamenti").linkNamed("Togli il pagamento di ${RegistrationFormat.money(2000)}"))
        assertThat(plain.locator("h1")).hasText("Togliere il pagamento di ${RegistrationFormat.money(2000)}?")
        touch(plain.locator("main").buttonNamed("Togli il pagamento"))
        assertThat(plain.locator("#scheda [role=status]").first()).hasText("Pagamento tolto.")
        assertEquals(0, e2e.dsl().fetchCount(PAYMENT))
        touch(plain.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(plain.locator("#segnalazioni")).containsText("Arachidi")
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))
        touch(plain.locator("#segnalazioni").linkNamed("Nascondi le segnalazioni"))
        assertThat(plain.locator("#segnalazioni")).not().containsText("Arachidi")
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS), "hiding is a read of the page: no second reading")

        // The prints have no button that prints, and the line under the bar says what to do.
        for (path in listOf("/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            plain.navigate(e2e.url(path))
            plain.settled()
            assertThat(plain.locator("main button[data-azione=stampa]")).isHidden()
            assertThat(plain.locator("main")).containsText("A4, in verticale.")
        }
    }

    @E2eTest
    fun `t6 c30 a name with markup is text on the list, in the answer to a touch, on the card and on the three prints`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val name = "<b>Zeno</b> Rossi & \"Figli\""
        val id = e2e.rows().register(w.id, e2e.someParent(), "<b>Zeno</b>", "Rossi & \"Figli\"", LocalDate.of(2016, 4, 4))

        e2e.open("/arrivi")
        e2e.press(page.locator("#riga-$id button").first())
        assertThat(page.locator("#esito")).hasText("Presenza segnata: $name.")
        for (path in listOf("/arrivi", "/arrivi/$id", "/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            e2e.open(path)
            assertEquals(0, page.locator("main b").count(), "$path: the markup of a name became an element")
            assertTrue(page.locator("main").innerText().contains("<b>Zeno</b>"), "$path: the name is shown as it was written")
        }
    }

    @E2eTest
    fun `t6 c19 the list asks the server nothing by itself, so what another teacher did is seen only at the next request`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val id = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        e2e.open("/arrivi")
        val requests = mutableListOf<String>()
        page.onRequest { requests += it.url() }

        // Somebody else marks Anna in the meantime (straight in the table): this page waits for a touch of «Aggiorna» or of a button, with no timer.
        e2e.dsl().insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, id).set(CHECKIN.DAY, w.today).set(CHECKIN.RECORDED_AT, java.time.OffsetDateTime.now()).set(CHECKIN.RECORDED_BY_ACCOUNT_ID, w.paoloId).execute()
        page.waitForTimeout(3500.0)
        assertEquals(emptyList(), requests, "the page made requests by itself: $requests")
        assertThat(page.locator("#riga-$id")).not().containsText("Presente")

        e2e.press(page.locator("main").linkNamed("Aggiorna"))
        assertThat(page.locator("#riga-$id")).containsText("Presente")
    }

    @E2eTest
    fun `t6 c28 c01 a registration of an older gathering has no card and no touch, with 404 for every address, and the arrivals are those of the current gathering, own child of a teacher included`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val rows = e2e.rows()
        val support = GatheringSupport(e2e.dsl(), e2e.application.getBean(Clock::class.java))
        val older = support.make(
            title = "Convivenza dell’anno scorso", start = w.today.minusDays(400), end = w.today.minusDays(398), status = GatheringStatus.closed, code = GatheringSupport.nextCode(),
            custodians = listOf(w.martaId),
        )
        val old = rows.register(older, e2e.someParent(), "Vecchio", "Iscritto", LocalDate.of(2015, 5, 5))
        e2e.payment(old, 1000, w.martaId)
        val oldPayment = e2e.dsl().select(PAYMENT.ID).from(PAYMENT).fetchSingle(PAYMENT.ID)!!
        // A teacher is also a parent: the child of the person of the test is in the arrivals like the others.
        val own = rows.register(w.id, w.meId, "Mia", "Figlia", LocalDate.of(2018, 2, 2))

        e2e.open("/arrivi")
        assertThat(page.locator("#riga-$own")).containsText("Mia Figlia")
        assertEquals(0, page.getByText("Vecchio Iscritto").count(), "the registrations of an older gathering are not in the list")
        e2e.open("/arrivi/$own")
        assertThat(page.locator("h1")).hasText("Mia Figlia")
        for (path in listOf("/arrivi/$old", "/arrivi/$old?giorno=${w.today}", "/arrivi/$old/pagamenti/$oldPayment/togli")) {
            assertEquals(404, e2e.open(path).status(), path)
            assertThat(page.locator("h1")).hasText("Pagina non trovata")
            assertFalse(page.content().contains("Vecchio"), "$path tells the name")
        }
        e2e.open("/profilo")
        val csrf = page.locator("input[name=_csrf]").first().inputValue()
        for (path in listOf("presenze", "presenze/togli", "pagamenti", "pagamenti/$oldPayment/togli", "manleva", "rimetti", "segnalazioni")) {
            val form = FormData.create().set("_csrf", csrf).set("giorno", w.today.minusDays(399).toString()).set("importo", "10").set("richiesta", UUID.randomUUID().toString())
            val status = page.request().post(e2e.url("/arrivi/$old/$path"), RequestOptions.create().setMaxRedirects(0).setForm(form).setHeader("HX-Request", "true")).status()
            assertEquals(404, status, "POST /arrivi/$old/$path")
        }
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))
        assertEquals(1, e2e.dsl().fetchCount(PAYMENT))
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE_ACCESS))
    }
}
