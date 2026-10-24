package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.jooq.impl.DSL
import talita.checkin.CheckinFormat
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.PAYMENT
import talita.registration.Notes
import java.time.Clock
import java.time.LocalDate
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
}
