package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import talita.checkin.CheckinFormat
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.REGISTRATION
import java.time.LocalDate
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The list of the arrivals and the card of a child on a real browser, on a phone and on a desktop, with axe (criteria 1-9, 19-22, 27): the list with its summary, the
 * search by name that runs in the browser, the touch on a row, what another teacher does at the same moment, a lost network, a registration withdrawn meanwhile, and the
 * card with its notices. Names and numbers are synthetic; the days are those around the day the test runs.
 */
@E2eApplication
class ArrivalsE2eTest {

    /** The children of the tests of the list: Luca, Anna, Nicolò (paper), Sara (in forse), Costanza D'Angelo, Jacopo (yesterday only) and Chiara (withdrawn). */
    private data class Children(val luca: Long, val anna: Long, val nicolo: Long, val sara: Long, val costanza: Long, val jacopo: Long, val chiara: Long)

    private fun E2e.children(w: ArrivalsWorld): Children {
        val parent = someParent()
        val rows = rows()
        return Children(
            luca = rows.register(w.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), days = mapOf(w.today to "full", w.tomorrow to "lunch")),
            anna = rows.register(w.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9)),
            nicolo = rows.register(w.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = w.martaId),
            sara = rows.register(w.id, someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3), status = RegistrationStatus.maybe),
            costanza = rows.register(w.id, someParent(), "Costanza", "D’Angelo", LocalDate.of(2016, 3, 3)),
            jacopo = rows.register(w.id, someParent(), "Jacopo", "Leone", LocalDate.of(2014, 9, 9), days = mapOf(w.yesterday to "full")),
            chiara = rows.register(w.id, someParent(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), status = RegistrationStatus.withdrawn),
        )
    }

    private fun day(date: LocalDate): String = CheckinFormat.longDay(date)

    private fun Page.shownRows(): Locator = locator("[data-filtro-riga]:not([hidden])")

    private fun Page.shownNames(): List<String> = shownRows().locator("[data-filtro-testo]").allInnerTexts().map { it.trim() }

    // ---- The list (criteria 1, 2, 27) -------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c01 c02 c27 the list has the days, the summary and the children in groups, with no sideways scrolling and no violation of axe`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.children(w)

        e2e.open("/arrivi")

        assertThat(page).hasTitle("Arrivi · Talità")
        assertThat(page.locator("h1")).hasText("Arrivi")
        assertThat(page.locator("nav[aria-label='Giorni della convivenza'] a")).hasCount(3)
        assertThat(page.locator("nav[aria-label='Giorni della convivenza'] a[aria-current=date]")).containsText("oggi")
        assertThat(page.locator("#riepilogo")).containsText(CheckinFormat.longDayCapital(w.today))
        assertThat(page.locator("#riepilogo")).containsText("Presenti")
        assertThat(page.locator("#riepilogo")).containsText("Nessuno")
        assertThat(page.locator("#riepilogo")).containsText("su 4 attesi")
        assertEquals(
            listOf(CheckinFormat.longDayCapital(w.today), "Attesi ${CheckinFormat.shortDay(w.today)} 5", "Non previsti ${CheckinFormat.shortDay(w.today)} 1", "Ritirate 1", "Un bambino non è in elenco?", "Da stampare"),
            page.locator("main h2:visible").allInnerTexts().map { it.replace(Regex("\\s+"), " ").trim() },
        )
        assertEquals(listOf("Costanza D’Angelo", "Nicolò Ferri", "Sara Neri", "Anna Rossi", "Luca Rossi", "Jacopo Leone", "Chiara Fontana"), page.shownNames())
        // The target of the button of a row is at least 44 px tall (it is 56), and the whole row is at least 56.
        val button = page.buttonNamed("Segna presente Anna Rossi, ${day(w.today)}")
        assertTrue(button.box().height >= 55.9 && button.box().width >= 95.9, "the button of a row is ${button.box()}")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        for (name in page.locator("[data-filtro-testo]").all()) {
            val box = name.box()
            assertTrue(box.width > 40, "a name has room to be read at 320 px: $box")
        }
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "list of the arrivals (${e2e.profile.label})")
    }

    @E2eTest
    fun `t6 c01 the selector changes the day with a link, and the page says which day it is, before and after today`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.children(w)
        e2e.open("/arrivi")

        e2e.touch(page.locator("nav[aria-label='Giorni della convivenza'] a[href='/arrivi?giorno=${w.yesterday}']"))
        assertThat(page).hasURL(e2e.url("/arrivi?giorno=${w.yesterday}"))
        assertThat(page.locator("main")).containsText("Stai guardando ${day(w.yesterday)}, un giorno passato.")
        assertThat(page.locator("nav[aria-label='Giorni della convivenza'] a[aria-current=date]")).containsText("${w.yesterday.dayOfMonth}")
        assertThat(page.buttonNamed("Segna presente Jacopo Leone, ${day(w.yesterday)}")).isVisible()
        e2e.touch(page.locator("main").linkNamed("Vai a oggi"))
        assertThat(page).hasURL(e2e.url("/arrivi?giorno=${w.today}"))
        assertThat(page.locator("main")).not().containsText("Stai guardando")

        e2e.open("/arrivi?giorno=${w.tomorrow}")
        assertThat(page.locator("main")).containsText("non è ancora arrivato: le presenze si segnano da quel giorno.")
        assertEquals(0, page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(Pattern.compile("Segna presente"))).count(), "a day that has not come has no button")
        assertAccessible(page, "list of a day that has not come (${e2e.profile.label})")
    }

    // ---- The search by name (criterion 3) ---------------------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c03 the search keeps the rows in which every word is in the name or the surname, without capitals, accents and apostrophes, and asks the server nothing`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.children(w)
        e2e.open("/arrivi")
        val field = page.locator("#cerca")
        assertThat(field).isVisible()
        val requests = mutableListOf<String>()
        page.onRequest { requests += it.url() }

        field.fill("nicolo")
        assertEquals(listOf("Nicolò Ferri"), page.shownNames())
        assertThat(page.locator("#cerca-esito")).hasText("Trovato 1 bambino.")
        field.fill("NICOLÒ")
        assertEquals(listOf("Nicolò Ferri"), page.shownNames())
        field.fill("ferr")
        assertEquals(listOf("Nicolò Ferri"), page.shownNames())
        field.fill("rossi luca")
        assertEquals(listOf("Luca Rossi"), page.shownNames())
        field.fill("luca rossi")
        assertEquals(listOf("Luca Rossi"), page.shownNames())
        field.fill("dangelo")
        assertEquals(listOf("Costanza D’Angelo"), page.shownNames())
        field.fill("d'angelo")
        assertEquals(listOf("Costanza D’Angelo"), page.shownNames())
        field.fill("ross")
        assertEquals(listOf("Anna Rossi", "Luca Rossi"), page.shownNames())
        assertThat(page.locator("#cerca-esito")).hasText("Trovati 2 bambini.")
        // A group with no row left goes away with its title, and the summary gives way to the results.
        assertThat(page.locator("main h2:visible").filter(Locator.FilterOptions().setHasText("Ritirate"))).hasCount(0)
        assertThat(page.locator("#riepilogo")).isHidden()
        assertEquals(emptyList(), requests.filter { !it.contains("/js/") && !it.contains("/css/") && !it.contains("/fonts/") }, "typing asks the server nothing: $requests")
        assertTrue(requests.none { it.contains("ross") || it.contains("nicol") }, "no name in an address")
        assertAccessible(page, "list with a search (${e2e.profile.label})")

        // Nobody has this name: the card says so and leads to the paper form; with the field empty everything is back.
        field.fill("zzz")
        assertEquals(emptyList(), page.shownNames())
        assertThat(page.locator("[data-filtro-vuoto]")).isVisible()
        assertThat(page.locator("[data-filtro-vuoto]")).containsText("Nessun bambino con questo nome")
        assertThat(page.locator("[data-filtro-vuoto]")).containsText("Controlla come l’hai scritto. Se il bambino non è iscritto, puoi inserirlo dal modulo di carta.")
        assertThat(page.locator("#cerca-esito")).hasText("Nessun bambino con questo nome.")
        assertThat(page.locator("[data-filtro-vuoto]").linkNamed("Inserisci un’iscrizione")).hasAttribute("href", Pattern.compile("/iscrizioni/nuova\\?convivenza=${w.id}&ritorno=arrivi"))
        assertAccessible(page, "list with no result (${e2e.profile.label})")
        field.fill("")
        assertEquals(7, page.shownNames().size)
        assertThat(page.locator("#riepilogo")).isVisible()
        assertThat(page.locator("[data-filtro-vuoto]")).isHidden()
        assertThat(page.locator("#cerca-esito")).hasText("")
        // «Invio» closes the keyboard and marks nobody.
        field.fill("anna")
        field.press("Enter")
        assertEquals("", page.evaluate("document.activeElement.id === 'cerca' ? 'cerca' : ''"))
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))
    }

    // ---- A touch on a row (criteria 4, 19, 20, 21) --------------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c04 a touch on Segna presente changes the row and the summary and nothing else, the search stays, and the focus is on the new button`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        e2e.open("/arrivi")
        page.locator("#cerca").fill("rossi")
        val url = page.url()

        e2e.press(page.buttonNamed("Segna presente Anna Rossi, ${day(w.today)}"))

        val row = page.locator("#riga-${c.anna}")
        assertThat(row).containsText("Presente")
        assertThat(row.locator("button")).hasText(Pattern.compile("Togli"))
        assertThat(page.locator("#esito")).hasText("Presenza segnata: Anna Rossi.")
        assertEquals(url, page.url(), "the page does not change")
        assertEquals(listOf("Anna Rossi", "Luca Rossi"), page.shownNames(), "the search is as it was")
        assertThat(page.locator("#riepilogo")).isHidden()
        page.focusIs("document.activeElement.closest('li') && document.activeElement.closest('li').id === 'riga-${c.anna}' && document.activeElement.textContent.includes('Togli')")
        assertEquals(1, e2e.dsl().fetchCount(CHECKIN))
        // The summary, behind the search, has the new number when the search goes away; the green row keeps its place.
        page.locator("#cerca").fill("")
        assertThat(page.locator("#riepilogo")).containsText("su 4 attesi")
        assertThat(page.locator("#riepilogo dd").first()).containsText("1")
        assertAccessible(page, "list after a touch (${e2e.profile.label})")

        // Taking it away has no question, and the button of the row goes back to what it was.
        e2e.press(page.buttonNamed("Togli la presenza di Anna Rossi, ${day(w.today)}"))
        assertThat(page.locator("#esito")).hasText("Presenza tolta: Anna Rossi.")
        assertThat(page.buttonNamed("Segna presente Anna Rossi, ${day(w.today)}")).isVisible()
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))
        assertEquals(0, page.locator("dialog").count())
    }

    @E2eTest
    fun `t6 c08 a touch on a child in forse confirms the registration and the row loses the label`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        e2e.open("/arrivi")
        assertThat(page.locator("#riga-${c.sara}")).containsText("In forse")

        e2e.press(page.buttonNamed("Segna presente Sara Neri, ${day(w.today)}"))

        assertThat(page.locator("#riga-${c.sara}")).containsText("Presente")
        assertThat(page.locator("#riga-${c.sara}")).not().containsText("In forse")
        assertThat(page.locator("#esito")).containsText("L’iscrizione era in forse: adesso è confermata.")
        assertEquals("confirmed", e2e.dsl().select(REGISTRATION.STATUS).from(REGISTRATION).where(REGISTRATION.ID.eq(c.sara)).fetchSingle(REGISTRATION.STATUS)!!.literal)
    }

    @E2eTest
    fun `t6 c19 c20 a touch on a child that another teacher marked a moment before shows the row as it is with a note that says who, and nothing is doubled`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        val other = e2e.newPage(teacher = true)
        e2e.open("/arrivi")
        other.navigate(e2e.url("/arrivi"))
        other.waitForSettledContent()

        // The second page is old by the time it touches: the first one has marked Luca already.
        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${day(w.today)}"))
        assertThat(page.locator("#riga-${c.luca}")).containsText("Presente")
        val button = other.buttonNamed("Segna presente Luca Rossi, ${day(w.today)}")
        button.evaluate("e => e.scrollIntoView({ block: 'center' })")
        if (e2e.profile.isPhone) button.tap() else button.click()

        assertThat(other.locator("#riga-${c.luca}")).containsText("Era già presente: l’ha segnata Giulia Serra alle ")
        assertThat(other.locator("#riga-${c.luca}")).containsText("Presente")
        assertThat(other.locator("#esito")).containsText("Luca Rossi era già presente")
        assertEquals(1, e2e.dsl().fetchCount(CHECKIN))
        // The page that was drawn after sees it too, with no refresh of its own: a new load shows who and the green row.
        other.navigate(e2e.url("/arrivi"))
        other.waitForSettledContent()
        assertThat(other.locator("#riga-${c.luca}")).containsText("Presente")
        assertAccessible(other, "list with a note (${e2e.profile.label})")
    }

    @E2eTest
    fun `t6 c21 without a network a touch changes nothing, the message has no Riprova, and when the network is back a new touch works once`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        e2e.open("/arrivi")
        val before = page.locator("#riga-${c.anna}").innerText()

        e2e.browserContext.setOffline(true)
        e2e.press(page.buttonNamed("Segna presente Anna Rossi, ${day(w.today)}"))
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(0, page.messages().locator("button:has-text('Riprova')").count(), "a touch is not repeated for the person")
        assertEquals(before, page.locator("#riga-${c.anna}").innerText(), "a presence that is not saved is not shown")
        assertThat(page.locator("#riga-${c.anna}").locator("button")).isEnabled()
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))
        assertAccessible(page, "list without a network (${e2e.profile.label})")

        e2e.browserContext.setOffline(false)
        e2e.press(page.buttonNamed("Segna presente Anna Rossi, ${day(w.today)}"))
        assertThat(page.locator("#riga-${c.anna}")).containsText("Presente")
        assertEquals(1, e2e.dsl().fetchCount(CHECKIN))
        assertEquals(0, page.messages().count(), "the message goes with the next request that works")
    }

    @E2eTest
    fun `t6 c08 c21 a registration withdrawn while the page is open answers with the row as it is and a note, and the server error says that something did not work`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        e2e.open("/arrivi")
        e2e.dsl().update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(c.anna)).execute()

        e2e.press(page.buttonNamed("Segna presente Anna Rossi, ${day(w.today)}"))

        val row = page.locator("#riga-${c.anna}")
        assertThat(row).containsText("Ritirata")
        assertThat(row).containsText("Non segnata: nel frattempo l’iscrizione è stata ritirata.")
        assertEquals(0, row.locator("button").count())
        assertThat(page.locator("#esito")).hasText("Anna Rossi non è stata segnata: l’iscrizione è stata ritirata.")
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))
        assertEquals(0, page.messages().count(), "a refusal that the state of things gives is the answer, not a failure")
        assertAccessible(page, "list with a refused touch (${e2e.profile.label})")

        // A request that the server cannot answer (the registration is not there at all) says that something did not work: nothing is ever left without an answer.
        // A copy of the form of a row, with the code against forgery, that points at an id that does not exist.
        page.evaluate(
            """() => {
                const copy = document.querySelector('#riga-${c.luca} form').cloneNode(true);
                copy.id = 'prova-404';
                copy.setAttribute('action', '/arrivi/999999/presenze');
                copy.setAttribute('hx-post', '/arrivi/999999/presenze');
                copy.setAttribute('hx-target', 'this');
                copy.setAttribute('hx-swap', 'outerHTML');
                document.querySelector('main > div').prepend(copy);
                htmx.process(copy);
            }""",
        )
        e2e.press(page.locator("#prova-404 button"))
        assertThat(page.messages()).containsText("Qualcosa non ha funzionato")
        assertEquals(0, page.locator("#riga-${c.luca}.bg-success-soft").count(), "nothing was marked")
    }

    // ---- The card (criteria 5-9) ---------------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c05 c06 the card marks and takes away a day and says so in a notice that takes the focus, and the labels change with it`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        e2e.open("/arrivi/${c.anna}")

        assertThat(page.locator("h1")).hasText("Anna Rossi")
        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Giorni", "Manleva", "Iscrizione", "Altri figli dello stesso genitore"))
        assertThat(page.locator("main").linkNamed("Torna agli arrivi")).hasAttribute("href", "/arrivi?giorno=${w.today}")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "card of a child (${e2e.profile.label})")

        e2e.press(page.buttonNamed("Segna presente Anna Rossi, ${day(w.today)}"))
        val notice = page.locator("#scheda [role=status]").first()
        assertThat(notice).hasText("Presenza segnata.")
        page.focusIs("document.activeElement.getAttribute('role') === 'status' && document.activeElement.textContent.includes('Presenza segnata.')")
        assertThat(page.locator("#scheda")).containsText("Presente")
        assertThat(page.locator("#scheda")).containsText("Segnata da Giulia Serra alle ")
        assertThat(page.locator("h1")).hasText("Anna Rossi")
        assertAccessible(page, "card after a presence (${e2e.profile.label})")

        e2e.press(page.buttonNamed("Togli la presenza di Anna Rossi, ${day(w.today)}"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Presenza tolta.")
        assertThat(page.buttonNamed("Segna presente Anna Rossi, ${day(w.today)}")).isVisible()
        assertEquals(0, e2e.dsl().fetchCount(CHECKIN))
    }

    @E2eTest
    fun `t6 c07 a day that was not foreseen is marked with a notice of attention and a link to correct the registration`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)
        e2e.open("/arrivi/${c.luca}")

        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${day(w.yesterday)}"))

        val notice = page.locator("#scheda [role=alert]").first()
        assertThat(notice).containsText("Presenza segnata.")
        assertThat(notice).containsText("non era previsto per Luca Rossi: la quota dovuta non cambia finché non correggi l’iscrizione.")
        assertThat(notice.linkNamed("Correggi l’iscrizione")).hasAttribute("href", "/iscrizioni/${c.luca}/correggi")
        assertAccessible(page, "card with a day that was not foreseen (${e2e.profile.label})")
    }

    @E2eTest
    fun `t6 c08 c09 the card of a withdrawn registration puts it back, and the manleva that arrives is marked in the card`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val c = e2e.children(w)

        e2e.open("/arrivi/${c.chiara}")
        assertThat(page.locator("#scheda")).containsText("Iscrizione ritirata. Non si segnano presenze.")
        assertEquals(0, page.getByText("Segna presente").count())
        assertAccessible(page, "card of a withdrawn registration (${e2e.profile.label})")
        e2e.press(page.locator("#scheda").buttonNamed("Rimetti confermata"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Iscrizione rimessa confermata.")
        assertThat(page.buttonNamed("Segna presente Chiara Fontana, ${day(w.today)}")).isVisible()

        e2e.open("/arrivi/${c.nicolo}")
        assertThat(page.locator("#scheda")).containsText("Il modulo firmato non è ancora arrivato.")
        e2e.press(page.locator("#scheda").buttonNamed("Segna come ricevuta"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Manleva segnata come ricevuta.")
        assertThat(page.locator("#scheda")).containsText("Cartacea ricevuta")
        assertThat(page.locator("#scheda")).containsText("Il modulo firmato è arrivato. Resta di carta, fuori da Talità.")
        assertAccessible(page, "card with the manleva received (${e2e.profile.label})")
    }
}
