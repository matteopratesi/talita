package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.MutableClock
import talita.checkin.CheckinFormat
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHECKIN
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arrival that is for every day, on a real browser, on a phone and on a desktop, with axe (TALITA-19, criterion 18): a whole gathering with the clock that moves, and the
 * notes of the rows. Friday, Saturday and Sunday, Luca (every day), Sara (Friday and, for lunch, Saturday) and Nicolò (Friday and Sunday); the days of the week are those of the
 * day the test runs. The clock of the application is the one of the tests, moved by whole days: the rows are written in advance and the days come one after the other. Names are
 * synthetic.
 */
@E2eApplication
class ArrivalWeekendE2eTest {

    private fun E2e.clock(): MutableClock = application.getBean(MutableClock::class.java)

    /**
     * Midnight: the clock of the application moves a day. The access of the teacher closes after twelve hours, by that clock, so the person enters again with a new link, as at the
     * door in the morning. Nothing is written by the passing of the day.
     */
    private fun E2e.nextDay() {
        clock().advance(Duration.ofDays(1))
        signIn(email = checkNotNull(signedInAs), teacher = false)
    }

    private fun day(date: LocalDate): String = CheckinFormat.longDay(date)

    private fun E2e.days(registration: Long): List<LocalDate> =
        dsl().select(CHECKIN.DAY).from(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(registration)).orderBy(CHECKIN.DAY).fetch(CHECKIN.DAY).map { it!! }

    /** The page does not scroll sideways at the width of the profile, nor at 320 px, and axe finds nothing serious. */
    private fun Page.looksRight(e2e: E2e, what: String) {
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling")
        setViewportSize(320, 800)
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling at 320 px")
        setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(this, "$what (${e2e.profile.label})")
    }

    @E2eTest
    fun `t19 c18 a whole gathering with the clock moving, Friday is marked once, Saturday has who arrived with no touch, Sunday is taken away on the card, and on Sunday who comes back is marked`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = 0, last = 2)
        val friday = w.today
        val saturday = friday.plusDays(1)
        val sunday = friday.plusDays(2)
        val rows = e2e.rows()
        val luca = rows.register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val sara = rows.register(w.id, e2e.someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3), days = mapOf(friday to "full", saturday to "lunch"), status = RegistrationStatus.maybe)
        val nicolo = rows.register(w.id, e2e.someParent(), "Nicolò", "Bianchi", LocalDate.of(2015, 6, 2), days = mapOf(friday to "full", sunday to "full"))

        // Friday: one touch for each child, three touches in all.
        e2e.open("/arrivi")
        for (name in listOf("Luca Rossi", "Sara Neri", "Nicolò Bianchi")) e2e.press(page.buttonNamed("Segna presente $name, ${day(friday)}"))
        assertThat(page.locator("#riga-$nicolo")).containsText("Presente")
        assertEquals(listOf(friday, saturday, sunday), e2e.days(luca))
        assertEquals(listOf(friday, saturday), e2e.days(sara), "the day of lunch only counts like the others")
        assertEquals(listOf(friday), e2e.days(nicolo), "Saturday is not his: the arrival stops there")
        assertEquals(6, e2e.dsl().fetchCount(CHECKIN))
        assertThat(page.locator("#esito")).hasText("${CheckinFormat.markedHead(listOf(friday))}: Nicolò Bianchi.")
        assertThat(page.locator("#riga-$sara")).not().containsText("In forse")

        // Saturday: Luca and Sara are present with no touch, Nicolò is among the ones who are not expected; the summary says it. Sunday has not come: nobody is present there.
        e2e.nextDay()
        e2e.open("/arrivi")
        assertThat(page.locator("#riga-$luca")).containsText("Presente")
        assertThat(page.locator("#riga-$sara")).containsText("Presente")
        assertThat(page.locator("#riga-$nicolo")).containsText("Non previsto")
        assertThat(page.locator("#riga-$nicolo")).not().containsText("Presente")
        assertThat(page.locator("#riepilogo")).containsText("su 2 attesi")
        assertThat(page.locator("#riepilogo dd").first()).containsText("2")
        assertThat(page.locator("main")).containsText("Segna presente e Togli valgono anche per i giorni dopo.")
        assertEquals(6, e2e.dsl().fetchCount(CHECKIN), "nothing was written at midnight")
        page.looksRight(e2e, "list of Saturday")

        e2e.open("/arrivi?giorno=$sunday")
        assertThat(page.locator("main")).containsText("Chi è già arrivato e resta sarà presente anche ${CheckinFormat.daysInSentence(listOf(sunday))}, se non lo togli dalla sua scheda.")
        assertThat(page.locator("#riepilogo dd").first()).containsText("Nessuno")
        assertThat(page.locator("#riepilogo")).containsText("su 2 attesi")
        assertEquals(0, page.locator("main button:has-text('Segna presente')").count() + page.locator("main button:has-text('Togli')").count(), "a day that has not come is read only")
        page.looksRight(e2e, "list of Sunday, that has not come")

        // The card of Luca: Friday and Saturday are present, Sunday will be. The card of Nicolò has the four states: Presente, Segna presente, and the day that has not come.
        e2e.open("/arrivi/$luca")
        assertThat(page.locator("#scheda")).containsText("Sarà presente")
        assertThat(page.locator("#scheda")).containsText("Conta come presente da ${CheckinFormat.daysInSentence(listOf(sunday))}.")
        assertThat(page.locator("#scheda")).containsText("Togli vale anche per ${CheckinFormat.daysInSentence(listOf(saturday, sunday))}.")
        page.looksRight(e2e, "card of Luca on Saturday")
        e2e.open("/arrivi/$nicolo")
        assertThat(page.locator("#scheda")).containsText("Presente")
        assertThat(page.buttonNamed("Segna presente Nicolò Bianchi, ${day(saturday)}")).isVisible()
        assertThat(page.locator("#scheda")).containsText("Si potrà segnare da quel giorno.")
        page.looksRight(e2e, "card of Nicolò on Saturday")

        // Luca goes home on Saturday evening: Marta takes Sunday away from the card, and only Sunday.
        e2e.open("/arrivi/$luca")
        e2e.press(page.buttonNamed("Togli la presenza di Luca Rossi, ${day(sunday)}"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("${CheckinFormat.unmarkedHead(listOf(sunday))}.")
        assertEquals(listOf(friday, saturday), e2e.days(luca))
        assertAccessible(page, "card after taking Sunday away (${e2e.profile.label})")

        // The prints of Saturday: the venue has who is present, and the day that has not come says that nobody is there yet.
        e2e.open("/arrivi/elenco?giorno=$saturday")
        assertThat(page.locator("main h2").first()).containsText("· 2")
        assertTrue(page.noHorizontalScroll())
        e2e.open("/arrivi/elenco?giorno=$sunday")
        assertThat(page.locator("main")).containsText("${CheckinFormat.longDayCapital(sunday)} non è ancora arrivato: i bambini si contano presenti da quel giorno.")
        page.looksRight(e2e, "list for the venue of a day that has not come")

        // Sunday: Luca is not there, and Nicolò, who comes back, is marked with one touch.
        e2e.nextDay()
        e2e.open("/arrivi")
        assertThat(page.locator("#riga-$luca")).not().containsText("Presente")
        assertThat(page.locator("#riga-$sara")).containsText("Non previsto")
        assertThat(page.locator("#riepilogo dd").first()).containsText("Nessuno")
        assertThat(page.locator("#riepilogo")).containsText("su 2 attesi")
        e2e.press(page.buttonNamed("Segna presente Nicolò Bianchi, ${day(sunday)}"))
        assertThat(page.locator("#riga-$nicolo")).containsText("Presente")
        assertEquals(listOf(friday, sunday), e2e.days(nicolo))
        assertEquals(listOf(friday, saturday), e2e.days(luca))
        assertAccessible(page, "list of Sunday after the touch (${e2e.profile.label})")

        // The register of Sunday: the X are the days that came, and the legend says the rule.
        e2e.open("/arrivi/registro")
        assertThat(page.locator("main")).containsText("un arrivo vale anche per i giorni dopo a cui il bambino è iscritto, ma la X compare solo dal loro giorno.")
        val register = page.locator("main table.tabella-stampa").first()
        assertEquals(2, register.locator("tbody tr").filter(Locator.FilterOptions().setHasText("Luca Rossi")).locator(".casella:has-text('X')").count())
        assertEquals(2, register.locator("tbody tr").filter(Locator.FilterOptions().setHasText("Nicolò Bianchi")).locator(".casella:has-text('X')").count())
        page.looksRight(e2e, "register after the gathering")
    }

    @E2eTest
    fun `t19 c16 c18 Togli and Segna presente on a child who has other rows say the days in a note of the row, and the names of the buttons say from which day`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = -1, last = 1)
        val friday = w.yesterday
        val saturday = w.today
        val sunday = w.tomorrow
        val rows = e2e.rows()
        val luca = rows.register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        for (day in listOf(friday, saturday, sunday)) {
            e2e.dsl().insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, luca).set(CHECKIN.DAY, day).set(CHECKIN.RECORDED_AT, java.time.OffsetDateTime.now(e2e.clock()).minusDays(1))
                .set(CHECKIN.RECORDED_BY_ACCOUNT_ID, w.martaId).execute()
        }
        e2e.open("/arrivi")

        // Today's «Togli» takes today and Sunday, and says so before the touch.
        e2e.press(page.buttonNamed("Togli la presenza di Luca Rossi, da ${day(saturday)} in poi"))
        assertThat(page.locator("#riga-$luca")).containsText("Tolta anche per ${CheckinFormat.daysInSentence(listOf(sunday))}.")
        assertThat(page.locator("#esito")).hasText("${CheckinFormat.unmarkedHead(listOf(saturday, sunday))}: Luca Rossi.")
        assertEquals(listOf(friday), e2e.days(luca))
        assertAccessible(page, "list with the note of Togli (${e2e.profile.label})")

        // Marked again from today: Friday is his already, so the row says that Sunday came with it.
        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${day(saturday)}"))
        assertThat(page.locator("#riga-$luca")).containsText("Segnata anche per ${CheckinFormat.daysInSentence(listOf(sunday))}.")
        assertThat(page.locator("#esito")).hasText("${CheckinFormat.markedHead(listOf(saturday, sunday))}: Luca Rossi.")
        assertEquals(listOf(friday, saturday, sunday), e2e.days(luca))
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "list with the note of Segna presente (${e2e.profile.label})")
    }
}
