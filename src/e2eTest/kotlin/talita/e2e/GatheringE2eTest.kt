package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.jooq.DSLContext
import talita.Addresses
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Convivenza» for the teachers in a real browser, on a phone and on a desktop, with axe (criteria 1-12, 14-17, 21, 40, 41, 38): the section, the
 * creation, the forms and their errors with the focus where it must be, the dialogs of the steps of the state, the list of what is missing with its
 * links, the same step tapped twice, a lost connection, the dates that no longer change, and «Maestri» with the custodians.
 */
@E2eApplication
class GatheringE2eTest {

    private fun E2e.dsl(): DSLContext = application.getBean(DSLContext::class.java)

    private fun E2e.gatherings(): GatheringSupport = GatheringSupport(dsl(), application.getBean(Clock::class.java))

    /**
     * Only the person of the test is a teacher («Elisa Conti»), with no invitation and no gathering: the database is shared with the other tests,
     * which leave their own. Returns the id of the account of the person.
     */
    private fun E2e.onlyMe(others: List<Triple<String, String, String>> = emptyList()): Long {
        dsl().deleteFrom(TEACHER_INVITATION).execute()
        dsl().deleteFrom(TEACHER).execute()
        val me = TestAccounts.makeTeacher(dsl(), checkNotNull(signedInAs), "Elisa", "Conti")
        for ((email, first, last) in others) TestAccounts.makeTeacher(dsl(), email, first, last)
        gatherings().reset()
        return me
    }

    private fun E2e.press(target: Locator) {
        target.evaluate("e => e.scrollIntoView({ block: 'center' })")
        touch(target)
    }

    private fun Locator.buttonNamed(name: String): Locator = getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name))

    private fun Locator.linkNamed(name: String): Locator = getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName(name))

    private fun Page.settled() {
        waitForLoadState()
        waitForSettledContent()
    }

    private fun Page.noHorizontalScroll() = (evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt() <= 0

    private fun Page.openDialog(): Locator = locator("dialog[open]")

    private fun Page.waitForSettledDialog() {
        waitUntil("document.querySelector('dialog[open]') && document.querySelector('dialog[open]').getAnimations().every(a => a.playState === 'finished')")
    }

    private fun E2e.idOfOnlyGathering(): Long = dsl().select(GATHERING.ID).from(GATHERING).fetchSingle(GATHERING.ID)!!

    // ---- The section (criteria 1, 41) -----------------------------------------------------------------------------------------

    @E2eTest
    fun `c01 c41 with no gathering the section shows the empty state with the button to create it and the way to Maestri, and is accessible`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyMe()

        e2e.open("/convivenza")

        assertThat(page).hasTitle("Convivenza · Talità")
        assertThat(page.locator("h1")).hasText("Convivenza")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Convivenza")
        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Nessuna convivenza in programma", "Maestri"))
        assertThat(page.locator("main")).containsText("Quando la crei, la trovi qui con le date, i maestri e il link per i genitori.")
        assertThat(page.locator("main").linkNamed("Crea la convivenza")).isVisible()
        assertThat(page.locator("main").linkNamed("Maestri")).isVisible()
        assertTrue(page.noHorizontalScroll())
        if (e2e.profile.isPhone) {
            // As wide as the card around it (the screen, less its margins and the padding of the card).
            val card = page.locator("main > div > div").nth(1).box()
            assertTrue(page.locator("main").linkNamed("Crea la convivenza").box().width >= card.width - 50, "the button is as wide as the card on the phone")
        }
        assertAccessible(page, "Convivenza, empty (${e2e.profile.label})")

        e2e.touch(page.locator("main").linkNamed("Maestri"))
        assertThat(page).hasURL(e2e.url("/maestri"))
    }

    @E2eTest
    fun `c15 c16 the section with a current gathering and with closed ones shows them as rows with their labels, and is accessible`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyMe()
        val gatherings = e2e.gatherings()
        gatherings.make(title = "Convivenza di settembre 2026", start = gatherings.today().minusDays(400), end = gatherings.today().minusDays(398), status = GatheringStatus.closed)
        val current = gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.draft)

        e2e.open("/convivenza")

        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Convivenza corrente", "Convivenze chiuse", "Maestri"))
        assertThat(page.locator("main").linkNamed("Crea la convivenza")).hasCount(0)
        val row = page.locator("main li a").filter(Locator.FilterOptions().setHasText("Convivenza di settembre 2027"))
        assertThat(row).containsText("Bozza")
        assertThat(page.locator("main li a").filter(Locator.FilterOptions().setHasText("Convivenza di settembre 2026"))).containsText("Iscrizioni chiuse")
        assertThat(page.locator("main li a").filter(Locator.FilterOptions().setHasText("Convivenza di settembre 2026"))).containsText("Finita")
        assertTrue(row.box().height >= 63.9, "a row is at least 64 px high")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "Convivenza with rows (${e2e.profile.label})")

        e2e.touch(row)
        assertThat(page).hasURL(e2e.url("/convivenza/$current"))
        assertThat(page.locator("h1")).hasText("Convivenza di settembre 2027")

        // Another gathering cannot be made: the address of the form shows why.
        e2e.open("/convivenza/nuova")
        assertThat(page.locator("main")).containsText("C’è già una convivenza in bozza: «Convivenza di settembre 2027». Puoi modificarla invece di crearne un’altra.")
        assertThat(page.locator("main").linkNamed("Vai alla convivenza")).isVisible()
        assertThat(page.locator("input[name=title]")).hasCount(0)
        assertAccessible(page, "Nuova convivenza blocked (${e2e.profile.label})")
    }

    // ---- Creating (criteria 2, 3) -----------------------------------------------------------------------------------------------

    @E2eTest
    fun `c02 c03 a gathering is made from the section, with the errors under the fields, the focus on the first wrong one, and the confirmation that takes the focus`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyMe()
        e2e.open("/convivenza")
        e2e.touch(page.locator("main").linkNamed("Crea la convivenza"))
        assertThat(page).hasURL(e2e.url("/convivenza/nuova"))
        assertThat(page).hasTitle("Nuova convivenza · Talità")
        assertThat(page.locator("h1")).hasText("Nuova convivenza")
        assertThat(page.locator("main")).containsText("Per cominciare bastano il titolo e le date. Il resto lo aggiungi dopo.")
        page.settled()
        assertAccessible(page, "Nuova convivenza (${e2e.profile.label})")

        // Nothing written: three errors, the notice above, the focus on the title.
        e2e.touch(page.buttonNamed("Crea la convivenza"))
        assertThat(page.locator("main [role=alert]")).hasText("Controlla i 3 campi segnati in rosso.")
        assertThat(page.locator("#titolo-errore")).hasText("Scrivi il titolo.")
        assertThat(page.locator("#primo-giorno-errore")).hasText("Scegli il primo giorno.")
        assertThat(page.locator("#ultimo-giorno-errore")).hasText("Scegli l’ultimo giorno.")
        page.focusIs("document.activeElement.id === 'titolo'")
        assertThat(page).hasURL(e2e.url("/convivenza/nuova"))
        assertAccessible(page, "Nuova convivenza with errors (${e2e.profile.label})")

        // One error: under the field, with the focus on it, the rest as written.
        val start = e2e.gatherings().today().plusDays(30)
        page.locator("#primo-giorno").fill(start.toString())
        page.locator("#ultimo-giorno").fill(start.minusDays(1).toString())
        page.locator("#titolo").fill("Convivenza di settembre 2027")
        e2e.touch(page.buttonNamed("Crea la convivenza"))
        assertThat(page.locator("#ultimo-giorno-errore")).hasText("L’ultimo giorno non può essere prima del primo.")
        assertThat(page.locator("main [role=alert]")).hasCount(0)
        assertThat(page.locator("#titolo")).hasValue("Convivenza di settembre 2027")
        page.focusIs("document.activeElement.id === 'ultimo-giorno'")

        page.locator("#ultimo-giorno").fill(start.plusDays(2).toString())
        e2e.touch(page.buttonNamed("Crea la convivenza"))

        assertThat(page.locator("h1")).hasText("Convivenza di settembre 2027")
        assertThat(page).hasURL(e2e.url("/convivenza/${e2e.idOfOnlyGathering()}"))
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Convivenza salvata.")
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertThat(page.locator("main")).containsText("Non indicato")
        assertThat(page.locator("main")).containsText("Nessun maestro scelto")
        assertThat(page.locator("main")).containsText("Non prevista")
        assertTrue(page.noHorizontalScroll())
        page.settled()
        assertAccessible(page, "detail of a new draft (${e2e.profile.label})")
    }

    // ---- The forms (criteria 5, 6, 7, 8, 17) --------------------------------------------------------------------------------------

    @E2eTest
    fun `c05 c07 the cards of the detail lead to their forms, the fees are saved and an error has its place and the focus, the sign of the euro is inside the field`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val id = e2e.gatherings().make(status = GatheringStatus.draft, full = null, daily = null, lunchOnly = null, custodians = listOf(me))
        e2e.open("/convivenza/$id")

        e2e.press(page.locator("main").linkNamed("Modifica le quote"))

        assertThat(page).hasURL(e2e.url("/convivenza/$id/quote"))
        assertThat(page.locator("h1")).hasText("Modifica le quote")
        assertThat(page.locator("main")).containsText("Gli importi compaiono nella pagina per i genitori.")
        assertThat(page.locator("#quota-intera")).hasAttribute("inputmode", "decimal")
        assertThat(page.locator("main").getByText("€", Locator.GetByTextOptions().setExact(true))).hasCount(3)
        page.settled()
        assertAccessible(page, "Quote (${e2e.profile.label})")

        page.locator("#quota-intera").fill("60")
        page.locator("#quota-giornaliera").fill("-5")
        page.locator("#quota-pranzo").fill("dieci")
        e2e.touch(page.buttonNamed("Salva le modifiche"))
        assertThat(page.locator("main [role=alert]")).hasText("Controlla i 2 campi segnati in rosso.")
        assertThat(page.locator("#quota-giornaliera-errore")).hasText("Scrivi un importo in euro, per esempio 60 oppure 12,50. Il massimo è 1000 €.")
        assertThat(page.locator("#quota-pranzo-errore")).hasText("Scrivi un importo in euro, per esempio 60 oppure 12,50. Il massimo è 1000 €.")
        assertThat(page.locator("#quota-giornaliera")).hasValue("-5")
        page.focusIs("document.activeElement.id === 'quota-giornaliera'")
        assertAccessible(page, "Quote with errors (${e2e.profile.label})")

        page.locator("#quota-giornaliera").fill("12,50")
        page.locator("#quota-pranzo").fill("0")
        e2e.touch(page.buttonNamed("Salva le modifiche"))

        assertThat(page).hasURL(e2e.url("/convivenza/$id"))
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Quote salvate.")
        val card = page.locator("section[aria-labelledby=quote]")
        assertThat(card).containsText("Quota intera 60 €")
        assertThat(card).containsText("Quota giornaliera 12,50 €")
        assertThat(card).containsText("Quota solo pranzo Gratuita")
    }

    @E2eTest
    fun `c05 c06 the form of the information keeps what is written, refuses an email address and a phone number under the field and saves paragraphs and a list`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val id = e2e.gatherings().make(status = GatheringStatus.draft, custodians = listOf(me))
        e2e.open("/convivenza/$id/informazioni")

        assertThat(page.locator("main")).containsText("Non scrivere nome e cognome di bambini o maestri, numeri di telefono né email.")
        assertThat(page.locator("#informazioni")).hasAttribute("rows", "9")
        assertThat(page.locator("#cosa-portare")).hasAttribute("rows", "6")
        page.settled()
        assertAccessible(page, "Informazioni (${e2e.profile.label})")

        page.locator("#informazioni").fill("Primo paragrafo.\n\nPer informazioni scrivi a nome@example.org.")
        page.locator("#cosa-portare").fill("Sacco a pelo\nTorcia\nChiama il 333 1234567")
        e2e.touch(page.buttonNamed("Salva le modifiche"))

        assertThat(page.locator("#informazioni-errore")).hasText("Qui c’è un indirizzo email. Toglilo: la pagina la legge chiunque abbia il link.")
        assertThat(page.locator("#cosa-portare-errore")).hasText("Qui c’è un numero di telefono. Toglilo: la pagina la legge chiunque abbia il link.")
        assertThat(page.locator("#informazioni")).hasValue("Primo paragrafo.\n\nPer informazioni scrivi a nome@example.org.")
        page.focusIs("document.activeElement.id === 'informazioni'")
        assertAccessible(page, "Informazioni with errors (${e2e.profile.label})")

        page.locator("#informazioni").fill("Primo paragrafo.\n\nSecondo paragrafo, il 17/09/2027 alle 18:30.")
        page.locator("#cosa-portare").fill("Sacco a pelo\nTorcia\nBorraccia")
        e2e.touch(page.buttonNamed("Salva le modifiche"))

        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Informazioni salvate.")
        val card = page.locator("section[aria-labelledby=informazioni]")
        assertThat(card.locator("p")).hasText(arrayOf("Primo paragrafo.", "Secondo paragrafo, il 17/09/2027 alle 18:30."))
        assertThat(card.locator("li")).hasText(arrayOf("Sacco a pelo", "Torcia", "Borraccia"))
        assertTrue(page.noHorizontalScroll())
        page.settled()
        assertAccessible(page, "detail with information (${e2e.profile.label})")
    }

    @E2eTest
    fun `c08 the custodians are chosen among the teachers, and the last one of an open gathering is refused with the focus on the list`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe(listOf(Triple(Addresses.unique("e2e-aff-paolo"), "Paolo", "Verdi")))
        val id = e2e.gatherings().make(status = GatheringStatus.draft)
        e2e.open("/convivenza/$id")

        e2e.press(page.locator("main").linkNamed("Modifica i maestri affidatari"))

        assertThat(page).hasURL(e2e.url("/convivenza/$id/affidatari"))
        assertThat(page.locator("legend")).hasText("Maestri affidatari")
        assertThat(page.locator("fieldset label")).hasText(arrayOf("Elisa Conti Sei tu", "Paolo Verdi"))
        // The content slides in for 160 ms: while it moves the browser may report 55.99997 px for a row of 56, so the height is read when it is still.
        page.settled()
        assertTrue(page.locator("fieldset label").first().box().height >= 55.9, "a row is at least 56 px high")
        assertAccessible(page, "Affidatari (${e2e.profile.label})")

        e2e.touch(page.getByLabel("Elisa Conti"))
        e2e.touch(page.buttonNamed("Salva le modifiche"))

        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Maestri affidatari salvati.")
        assertThat(page.locator("section[aria-labelledby=affidatari]")).containsText("Elisa Conti")
        assertThat(page.locator("section[aria-labelledby=affidatari]")).containsText("Sei tu")
        assertEquals(listOf(me), e2e.gatherings().custodiansOf(id))

        // Open: the last one is not taken away.
        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.`open`).where(GATHERING.ID.eq(id)).execute()
        e2e.open("/convivenza/$id/affidatari")
        e2e.touch(page.getByLabel("Elisa Conti"))
        e2e.touch(page.buttonNamed("Salva le modifiche"))

        assertThat(page.locator("#affidatari-errore")).hasText("Scegli almeno un maestro: il testo dell’affidamento ha bisogno di almeno un nome.")
        page.focusIs("document.activeElement.name === 'custodians'")
        assertAccessible(page, "Affidatari with the error (${e2e.profile.label})")
        assertEquals(listOf(me), e2e.gatherings().custodiansOf(id))
    }

    @E2eTest
    fun `c17 after the end the form shows the dates as text, with no fields, and the title and the place are saved`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val gatherings = e2e.gatherings()
        val end = gatherings.today().minusDays(3)
        val id = gatherings.make(title = "Convivenza di settembre 2026", status = GatheringStatus.closed, start = end.minusDays(2), end = end, custodians = listOf(me))
        e2e.open("/convivenza/$id/date")

        assertThat(page.locator("input[type=date]")).hasCount(0)
        assertThat(page.locator("main")).containsText("Le date non si cambiano più, perché la convivenza è finita.")
        assertThat(page.locator("main")).containsText(talita.gathering.GatheringFormat.range(end.minusDays(2), end))
        page.settled()
        assertAccessible(page, "dates of a gathering that is over (${e2e.profile.label})")

        page.locator("#titolo").fill("Convivenza di settembre 2026, finita")
        e2e.touch(page.buttonNamed("Salva le modifiche"))

        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Titolo, date e luogo salvati.")
        assertThat(page.locator("h1")).hasText("Convivenza di settembre 2026, finita")
    }

    // ---- The steps of the state (criteria 9-12, 14) --------------------------------------------------------------------------------

    @E2eTest
    fun `c10 a draft that is not complete says what is missing with a link to each, the link leads to the field with the focus on it, and the button stays active`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyMe()
        val id = e2e.gatherings().make(status = GatheringStatus.draft, place = null, full = null)
        e2e.open("/convivenza/$id")
        assertThat(page.locator("main").getByText("Da completare")).hasCount(3)

        e2e.press(page.locator("main").buttonNamed("Apri le iscrizioni"))

        val alert = page.locator("main [role=alert]")
        assertThat(alert).containsText("Prima di aprire le iscrizioni:")
        assertThat(alert.locator("a")).hasText(arrayOf("Scrivi il luogo", "Scrivi la quota intera", "Scegli almeno un maestro affidatario"))
        page.focusIs("document.activeElement.getAttribute('role') === 'alert'")
        assertThat(page.locator("main").buttonNamed("Apri le iscrizioni")).isEnabled()
        assertEquals(GatheringStatus.draft, e2e.gatherings().statusOf(id))
        assertTrue(alert.locator("a").first().box().height >= 43.9, "the links are big enough for a finger")
        page.settled()
        assertAccessible(page, "detail with what is missing (${e2e.profile.label})")

        e2e.press(alert.linkNamed("Scrivi il luogo"))
        assertThat(page).hasURL(e2e.url("/convivenza/$id/date#luogo"))
        page.focusIs("document.activeElement.id === 'luogo'")

        e2e.open("/convivenza/$id")
        e2e.press(page.locator("main").buttonNamed("Apri le iscrizioni"))
        e2e.press(page.locator("main [role=alert]").linkNamed("Scrivi la quota intera"))
        assertThat(page).hasURL(e2e.url("/convivenza/$id/quote#quota-intera"))
        page.focusIs("document.activeElement.id === 'quota-intera'")
    }

    @E2eTest
    fun `c09 c11 c12 the dialogs open, close and reopen the registrations, with the first focus on Annulla, Esc and Annulla changing nothing, and a confirmation that takes the focus`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val id = e2e.gatherings().make(status = GatheringStatus.draft, custodians = listOf(me))
        e2e.open("/convivenza/$id")
        val open = page.locator("main").linkNamed("Apri le iscrizioni")
        assertThat(open).isVisible()

        e2e.press(open)

        val dialog = page.openDialog()
        assertThat(dialog).isVisible()
        assertThat(dialog.locator("h2")).hasText("Aprire le iscrizioni?")
        assertThat(dialog).containsText("Da adesso la pagina con le informazioni è visibile a chi ha il link e i genitori possono iscrivere i figli.")
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        page.waitForSettledDialog()
        if (e2e.profile.isPhone) {
            val box = dialog.box()
            assertTrue(Math.abs(box.y + box.height - page.viewport().height) < 2, "the sheet does not touch the bottom")
        }
        assertAccessible(page, "dialog Apri (${e2e.profile.label})")

        page.keyboard().press("Escape")
        assertThat(page.openDialog()).hasCount(0)
        assertEquals(GatheringStatus.draft, e2e.gatherings().statusOf(id))
        e2e.press(open)
        e2e.touch(page.openDialog().buttonNamed("Annulla"))
        assertThat(page.openDialog()).hasCount(0)
        assertEquals(GatheringStatus.draft, e2e.gatherings().statusOf(id))

        e2e.press(open)
        e2e.touch(page.openDialog().buttonNamed("Apri le iscrizioni"))

        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Iscrizioni aperte.")
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertThat(page.locator("section[aria-labelledby=stato]")).containsText("Iscrizioni aperte")
        assertThat(page).hasURL(e2e.url("/convivenza/$id"))
        assertEquals(GatheringStatus.`open`, e2e.gatherings().statusOf(id))

        e2e.press(page.locator("main").linkNamed("Chiudi le iscrizioni"))
        assertThat(page.openDialog().locator("h2")).hasText("Chiudere le iscrizioni?")
        assertThat(page.openDialog()).containsText("Potrai riaprire le iscrizioni finché la convivenza non è finita.")
        e2e.touch(page.openDialog().buttonNamed("Chiudi le iscrizioni"))
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Iscrizioni chiuse.")
        assertEquals(GatheringStatus.closed, e2e.gatherings().statusOf(id))
        assertThat(page.locator("main")).containsText("I genitori vedono la pagina, ma non possono iscrivere i figli né cambiare le iscrizioni.")

        e2e.press(page.locator("main").linkNamed("Riapri le iscrizioni"))
        assertThat(page.openDialog().locator("h2")).hasText("Riaprire le iscrizioni?")
        e2e.touch(page.openDialog().buttonNamed("Riapri le iscrizioni"))
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Iscrizioni riaperte.")
        assertEquals(GatheringStatus.`open`, e2e.gatherings().statusOf(id))
        // There is no way back to the draft.
        assertThat(page.locator("main").getByText("bozza", Locator.GetByTextOptions().setExact(false))).hasCount(0)
    }

    @E2eTest
    fun `c14 the confirm button tapped twice changes the state once and the page says Iscrizioni chiuse, with no error`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val id = e2e.gatherings().make(status = GatheringStatus.`open`, custodians = listOf(me))
        e2e.open("/convivenza/$id")
        e2e.press(page.locator("main").linkNamed("Chiudi le iscrizioni"))
        val confirm = page.openDialog().buttonNamed("Chiudi le iscrizioni")
        page.waitForSettledDialog()

        if (e2e.profile.isPhone) {
            confirm.tap()
            runCatching { confirm.tap(Locator.TapOptions().setTimeout(500.0)) }
        } else {
            confirm.click(Locator.ClickOptions().setClickCount(2))
        }

        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Iscrizioni chiuse.")
        assertThat(page.locator("#messaggi [role=alert]")).hasCount(0)
        assertEquals(GatheringStatus.closed, e2e.gatherings().statusOf(id))
        assertEquals(0, page.locator("main [role=alert]").count())
    }

    @E2eTest
    fun `c12 a gathering that is over has no button to reopen, and one that is open and over says it and has Chiudi as the main action`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val gatherings = e2e.gatherings()
        val id = gatherings.make(status = GatheringStatus.`open`, start = gatherings.today().minusDays(5), end = gatherings.today().minusDays(3), custodians = listOf(me))
        e2e.open("/convivenza/$id")

        assertThat(page.locator("main")).containsText("La convivenza è finita ma le iscrizioni sono ancora aperte. Chiudile per poter creare la prossima.")
        assertThat(page.locator("section[aria-labelledby=stato]")).containsText("Finita")
        e2e.press(page.locator("main").linkNamed("Chiudi le iscrizioni"))
        assertThat(page.openDialog()).containsText("Non potrai riaprirle, perché la convivenza è finita.")
        e2e.touch(page.openDialog().buttonNamed("Chiudi le iscrizioni"))

        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Iscrizioni chiuse.")
        assertThat(page.locator("main")).containsText("La convivenza è finita: le iscrizioni non si riaprono.")
        assertThat(page.locator("main").linkNamed("Riapri le iscrizioni")).hasCount(0)
        page.settled()
        assertAccessible(page, "closed and over (${e2e.profile.label})")
    }

    @E2eTest
    fun `c09 c11 without JavaScript the step is a page of confirmation with the same words, and it works`(e2e: E2e) {
        val me = e2e.onlyMe()
        val id = e2e.gatherings().make(status = GatheringStatus.draft, custodians = listOf(me))
        val page = e2e.newPage(customize = { setJavaScriptEnabled(false) })
        page.navigate(e2e.url("/convivenza/$id"))
        page.waitForLoadState()
        page.waitForSettledContent()

        page.locator("main").linkNamed("Apri le iscrizioni").click()

        assertThat(page).hasURL(e2e.url("/convivenza/$id/apri"))
        assertThat(page.locator("h1")).hasText("Aprire le iscrizioni?")
        assertThat(page.locator("main")).containsText("Da adesso la pagina con le informazioni è visibile a chi ha il link")
        page.waitForSettledContent()
        page.locator("main").buttonNamed("Apri le iscrizioni").click()
        assertThat(page).hasURL(e2e.url("/convivenza/$id"))
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Iscrizioni aperte.")
        assertEquals(GatheringStatus.`open`, e2e.gatherings().statusOf(id))
    }

    // ---- A lost connection (criterion 21) ----------------------------------------------------------------------------------------

    @E2eTest
    fun `c21 a form sent without the network stays filled in, the message has no Riprova, and once the network is back the same tap saves`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyMe()
        e2e.open("/convivenza/nuova")
        val start = e2e.gatherings().today().plusDays(30)
        page.locator("#titolo").fill("Convivenza di settembre 2027")
        page.locator("#primo-giorno").fill(start.toString())
        page.locator("#ultimo-giorno").fill(start.plusDays(2).toString())
        page.settled()

        e2e.browserContext.setOffline(true)
        e2e.touch(page.buttonNamed("Crea la convivenza"))

        assertThat(page.locator("#messaggi [role=alert]")).containsText("Nessuna connessione")
        assertThat(page.locator("#messaggi").buttonNamed("Riprova")).hasCount(0)
        assertThat(page.locator("#titolo")).hasValue("Convivenza di settembre 2027")
        assertThat(page.locator("#primo-giorno")).hasValue(start.toString())
        assertThat(page.buttonNamed("Crea la convivenza")).isEnabled()
        assertEquals(0, e2e.dsl().fetchCount(GATHERING))

        e2e.browserContext.setOffline(false)
        e2e.touch(page.buttonNamed("Crea la convivenza"))

        assertThat(page.locator("h1")).hasText("Convivenza di settembre 2027")
        assertThat(page.locator("main [role=status][data-fuoco]")).hasText("Convivenza salvata.")
        assertEquals(1, e2e.dsl().fetchCount(GATHERING))
        assertThat(page.locator("#messaggi [role=alert]")).hasCount(0)
    }

    // ---- «Maestri» (criterion 40) --------------------------------------------------------------------------------------------------

    @E2eTest
    fun `c40 a custodian of a gathering that is not over has the explanation and the way to the custodians instead of Togli, and the link leads to the form`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe(listOf(Triple(Addresses.unique("e2e-aff-colleague"), "Paolo", "Verdi")))
        val paolo = e2e.dsl().select(TEACHER.ACCOUNT_ID).from(TEACHER).where(TEACHER.FIRST_NAME.eq("Paolo")).fetchSingle(TEACHER.ACCOUNT_ID)!!
        val id = e2e.gatherings().make(title = "Convivenza di settembre 2027", status = GatheringStatus.`open`, custodians = listOf(me, paolo))
        e2e.open("/maestri")

        val colleague = page.locator("main li").filter(Locator.FilterOptions().setHasText("Paolo Verdi"))
        assertThat(colleague).containsText("Affidatario di «Convivenza di settembre 2027»: prima va tolto dagli affidatari.")
        assertThat(colleague.buttonNamed("Togli")).hasCount(0)
        assertThat(colleague.linkNamed("Togli dagli affidatari")).isVisible()
        val yourself = page.locator("main li").filter(Locator.FilterOptions().setHasText("Elisa Conti"))
        assertThat(yourself).containsText("Sei affidatario di «Convivenza di settembre 2027»: per toglierti, prima togliti dagli affidatari.")
        assertThat(yourself.linkNamed("Togliti dagli affidatari")).isVisible()
        assertTrue(colleague.linkNamed("Togli dagli affidatari").box().height >= 43.9, "the button is big enough for a finger")
        assertTrue(page.noHorizontalScroll())
        page.settled()
        assertAccessible(page, "Maestri with the explanation (${e2e.profile.label})")
        assertEquals(2, e2e.dsl().fetchCount(GATHERING_CUSTODIAN))

        e2e.press(colleague.linkNamed("Togli dagli affidatari"))

        assertThat(page).hasURL(e2e.url("/convivenza/$id/affidatari"))
        assertThat(page.locator("h1")).hasText("Modifica i maestri affidatari")
    }

    // ---- Layout of the long texts -----------------------------------------------------------------------------------------------------

    @E2eTest
    fun `a title of 100 letters in one word and a long place wrap in every page of the gathering, with no horizontal scroll`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.onlyMe()
        val longWord = "L".repeat(100)
        val id = e2e.gatherings().make(title = longWord, place = "P".repeat(200), status = GatheringStatus.draft, custodians = listOf(me), info = "I".repeat(300), whatToBring = "C".repeat(200))

        for (path in listOf("/convivenza", "/convivenza/$id", "/convivenza/$id/date", "/convivenza/$id/apri")) {
            e2e.open(path)
            page.settled()
            assertTrue(page.noHorizontalScroll(), "$path scrolls sideways")
        }
        e2e.open("/maestri")
        assertTrue(page.noHorizontalScroll(), "/maestri scrolls sideways")
    }
}
