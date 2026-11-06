package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.options.RequestOptions
import talita.MutableClock
import talita.checkin.CheckinFormat
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.REGISTRATION
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The arrival that is for every day, as QA checks it (TALITA-19), on a phone and on a desktop: one criterion at a time, on the interface that the teacher and the parent really use, and
 * not only the way that the services and the pages are written. They add to `ArrivalWeekendE2eTest` (the whole gathering with the clock that moves, the notes of the rows): here the arrival
 * that stops at a row that was there, the card for a day that was not foreseen and for a child in doubt, «Togli» in the list and on the card (also after another teacher), the withdrawal
 * and the correction of the registration, the same presents everywhere, the five states of a day on the card, what a parent can reach, the notice and the transcription of a register
 * after the end. Names are synthetic. In the code the three days are Friday, Saturday and Sunday (the roles of the weekend); their dates come from the day the test runs, so the weekdays that the pages say are the real ones of those dates, and the tests write them with `CheckinFormat`.
 */
@E2eApplication
class ArrivalWeekendQaE2eTest {

    /** The weekend of the examples: Friday, Saturday, Sunday; Luca every day, Sara «in forse» (Friday and, for lunch, Saturday), Nicolò Friday and Sunday. */
    private data class Weekend(val w: ArrivalsWorld, val friday: LocalDate, val saturday: LocalDate, val sunday: LocalDate, val luca: Long, val sara: Long, val nicolo: Long)

    /** [first]: the day of the first day of the weekend from today (0: today is Friday; -1: today is Saturday; -2: today is Sunday). */
    private fun E2e.weekend(first: Long): Weekend {
        val w = arrivalsGathering(first = first, last = first + 2)
        val friday = w.today.plusDays(first)
        val saturday = friday.plusDays(1)
        val sunday = friday.plusDays(2)
        val rows = rows()
        val luca = rows.register(w.id, someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val sara = rows.register(w.id, someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3), days = mapOf(friday to "full", saturday to "lunch"), status = RegistrationStatus.maybe)
        val nicolo = rows.register(w.id, someParent(), "Nicolò", "Bianchi", LocalDate.of(2015, 6, 2), days = mapOf(friday to "full", sunday to "full"))
        return Weekend(w, friday, saturday, sunday, luca, sara, nicolo)
    }

    private fun E2e.clock(): MutableClock = application.getBean(MutableClock::class.java)

    /** A test that moves the clock by days gives it back when it ends, also when it fails (see `ArrivalWeekendE2eTest`). */
    private fun E2e.keepingTheClock(test: () -> Unit) {
        val before = clock().offset()
        try {
            test()
        } finally {
            clock().restore(before)
        }
    }

    /** Midnight: nothing is written; the access of the teacher closes after twelve hours, so the person enters again with a new link. */
    private fun E2e.nextDay() {
        clock().advance(Duration.ofDays(1))
        signIn(email = checkNotNull(signedInAs), teacher = false)
    }

    private fun day(date: LocalDate): String = CheckinFormat.longDay(date)

    private fun E2e.days(registration: Long): List<LocalDate> =
        dsl().select(CHECKIN.DAY).from(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(registration)).orderBy(CHECKIN.DAY).fetch(CHECKIN.DAY).map { it!! }

    /** A row of presence written straight in the table, as the arrival of another teacher or of an earlier day left it. */
    private fun E2e.row(registration: Long, day: LocalDate, by: Long, at: OffsetDateTime = OffsetDateTime.now(clock())) {
        dsl().insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, registration).set(CHECKIN.DAY, day).set(CHECKIN.RECORDED_AT, at).set(CHECKIN.RECORDED_BY_ACCOUNT_ID, by).execute()
    }

    private fun E2e.rowOf(registration: Long, day: LocalDate) =
        dsl().select(CHECKIN.RECORDED_AT, CHECKIN.RECORDED_BY_ACCOUNT_ID).from(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(registration)).and(CHECKIN.DAY.eq(day)).fetchOne()

    /** The notice that follows a touch on the card: a confirmation is a status, a warning an alert. */
    private fun Page.notice(): Locator = locator("#scheda [role=status], #scheda [role=alert]").first()

    /** The days of the card, in order. */
    private fun Page.cardDays(): Locator = locator("#scheda section[aria-labelledby=giorni-titolo] li")

    private fun Locator.said(): String = innerText().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    /** The page does not scroll sideways at the width of the profile, nor at 320 px, and axe finds nothing serious. */
    private fun Page.looksRight(e2e: E2e, what: String) {
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling")
        setViewportSize(320, 800)
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling at 320 px")
        setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(this, "$what (${e2e.profile.label})")
    }

    // ---- Criteria 1 and 3: the arrival stops at a row that was there ------------------------------------------------------------------

    @E2eTest
    fun `t19 c03 marking Friday on the list stops at Saturday, that another teacher marked before, and that row keeps its teacher and its moment, and the card says so`(e2e: E2e) {
        val page = e2e.page
        val x = e2e.weekend(first = -1)
        val before = OffsetDateTime.now(e2e.clock()).minusSeconds(20).truncatedTo(ChronoUnit.MICROS)
        // Paolo marked Luca on Saturday (today) and the arrival went on to Sunday; nobody marked Friday.
        e2e.row(x.luca, x.saturday, x.w.paoloId, before)
        e2e.row(x.luca, x.sunday, x.w.paoloId, before)

        e2e.open("/arrivi?giorno=${x.friday}")
        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${day(x.friday)}"))
        assertThat(page.locator("#riga-${x.luca}")).containsText("Presente")
        assertThat(page.locator("#esito")).hasText("Presenza segnata: Luca Rossi.")
        assertThat(page.locator("#riga-${x.luca}")).not().containsText("anche per")

        assertEquals(listOf(x.friday, x.saturday, x.sunday), e2e.days(x.luca))
        val saturday = e2e.rowOf(x.luca, x.saturday)!!
        assertEquals(x.w.paoloId, saturday[CHECKIN.RECORDED_BY_ACCOUNT_ID], "Saturday stays Paolo's")
        assertEquals(before.toInstant(), saturday[CHECKIN.RECORDED_AT]!!.toInstant(), "and keeps its moment")
        assertEquals(x.w.meId, e2e.rowOf(x.luca, x.friday)!![CHECKIN.RECORDED_BY_ACCOUNT_ID])

        e2e.open("/arrivi/${x.luca}")
        val days = page.cardDays()
        assertThat(days.nth(0)).containsText("Segnata da Giulia Serra il ${CheckinFormat.dayAndMonth(x.saturday)} alle")
        assertThat(days.nth(1)).containsText("Segnata da Paolo Verdi alle ${CheckinFormat.time(before)}")
        assertThat(days.nth(2)).containsText("Segnata da Paolo Verdi con l’arrivo, ${CheckinFormat.shortDay(x.saturday)} alle ${CheckinFormat.time(before)}")
        page.looksRight(e2e, "card with the rows of two teachers")
    }

    @E2eTest
    fun `t19 c01 the register of paper copied after the end is marked with one touch, the arrival writes every day and the card says that the rows were written after`(e2e: E2e) {
        val page = e2e.page
        val x = e2e.weekend(first = -4)

        e2e.open("/arrivi?giorno=${x.friday}")
        assertThat(page.locator("main")).containsText("Segna presente e Togli valgono anche per i giorni dopo.")
        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${day(x.friday)}"))
        assertThat(page.locator("#riga-${x.luca}")).containsText("Presente")
        assertThat(page.locator("#esito")).hasText("${CheckinFormat.markedHead(listOf(x.friday, x.saturday, x.sunday))}: Luca Rossi.")
        assertEquals(listOf(x.friday, x.saturday, x.sunday), e2e.days(x.luca))
        assertEquals(1, e2e.dsl().select(CHECKIN.RECORDED_AT).from(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(x.luca)).fetch(CHECKIN.RECORDED_AT).map { it!!.toInstant() }.toSet().size, "one moment")

        e2e.open("/arrivi/${x.luca}")
        for (n in 0..2) {
            assertThat(page.cardDays().nth(n)).containsText("Presente")
            assertThat(page.cardDays().nth(n)).not().containsText("Sarà presente")
            assertThat(page.cardDays().nth(n)).containsText("il ${CheckinFormat.dayAndMonth(x.w.today)} alle")
        }
        assertThat(page.buttonNamed("Togli la presenza di Luca Rossi, da ${day(x.friday)} in poi")).isVisible()
        page.looksRight(e2e, "card of a register copied after the end")
    }

    // ---- Criteria 4 and 5: a day that was not foreseen, and a child in doubt -----------------------------------------------------------

    @E2eTest
    fun `t19 c04 c05 on the card a day that was not foreseen says the days with the old warning, and a child in forse is confirmed with the rows`(e2e: E2e) {
        val page = e2e.page
        val x = e2e.weekend(first = -1)

        // Nicolò stays on Saturday, a day that his registration does not include: Saturday and Sunday are marked, the quota does not change, and the warning says it.
        e2e.open("/arrivi/${x.nicolo}")
        assertThat(page.cardDays().nth(1)).containsText("Non previsto")
        e2e.press(page.buttonNamed("Segna presente Nicolò Bianchi, ${day(x.saturday)}"))
        val alert = page.notice()
        assertThat(alert).containsText("${CheckinFormat.markedHead(listOf(x.saturday, x.sunday))}.")
        assertThat(alert).containsText("${CheckinFormat.longDayCapital(x.saturday)} non era previsto per Nicolò Bianchi: la quota dovuta non cambia finché non correggi l’iscrizione.")
        assertThat(alert.linkNamed("Correggi l’iscrizione")).isVisible()
        assertEquals(listOf(x.saturday, x.sunday), e2e.days(x.nicolo))
        assertThat(page.cardDays().nth(2)).containsText("Sarà presente")
        page.looksRight(e2e, "card after a day that was not foreseen")

        // «Presenti non previsti» counts Nicolò on Saturday and not on Sunday, which has not come.
        e2e.open("/arrivi")
        assertThat(page.locator("#riepilogo")).containsText(Pattern.compile("Presenti non previsti\\s*1"))
        e2e.open("/arrivi?giorno=${x.sunday}")
        assertThat(page.locator("#riepilogo")).containsText(Pattern.compile("Presenti non previsti\\s*Nessuno"))

        // Sara is «in forse»: Friday and, for lunch, Saturday are marked and the registration is confirmed, in the same notice.
        e2e.open("/arrivi/${x.sara}")
        assertThat(page.locator("#scheda")).containsText("L’iscrizione è in forse. Se segni la presenza, diventa confermata.")
        e2e.press(page.buttonNamed("Segna presente Sara Neri, ${day(x.friday)}"))
        assertThat(page.notice()).containsText("${CheckinFormat.markedHead(listOf(x.friday, x.saturday))}. L’iscrizione di Sara Neri era in forse: adesso è confermata.")
        assertEquals(listOf(x.friday, x.saturday), e2e.days(x.sara))
        assertEquals(RegistrationStatus.confirmed, e2e.dsl().select(REGISTRATION.STATUS).from(REGISTRATION).where(REGISTRATION.ID.eq(x.sara)).fetchSingle(REGISTRATION.STATUS))
        assertThat(page.locator("#scheda")).not().containsText("L’iscrizione è in forse.")
        page.looksRight(e2e, "card of a child that was in forse")
    }

    // ---- Criteria 9, 10 and 11: «Togli» ---------------------------------------------------------------------------------------------

    @E2eTest
    fun `t19 c09 c10 c11 Togli takes the day and the days after it, a day after today can be taken alone, a day already taken is not an error, and one touch takes away a mark that was wrong`(e2e: E2e) {
        val page = e2e.page
        val x = e2e.weekend(first = -1)

        // Luca arrives on Friday (the touch is made on the list of Friday), and Saturday and Sunday come with it.
        e2e.open("/arrivi?giorno=${x.friday}")
        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${day(x.friday)}"))
        assertThat(page.locator("#esito")).hasText("${CheckinFormat.markedHead(listOf(x.friday, x.saturday, x.sunday))}: Luca Rossi.")
        assertEquals(listOf(x.friday, x.saturday, x.sunday), e2e.days(x.luca))

        // Criterion 9: «Togli» on Saturday, on the list of Saturday, takes Saturday and Sunday; Friday stays.
        e2e.open("/arrivi")
        e2e.press(page.buttonNamed("Togli la presenza di Luca Rossi, da ${day(x.saturday)} in poi"))
        assertThat(page.locator("#riga-${x.luca}")).containsText("Tolta anche per ${CheckinFormat.daysInSentence(listOf(x.sunday))}.")
        assertThat(page.locator("#esito")).hasText("${CheckinFormat.unmarkedHead(listOf(x.saturday, x.sunday))}: Luca Rossi.")
        assertEquals(listOf(x.friday), e2e.days(x.luca))
        assertThat(page.buttonNamed("Segna presente Luca Rossi, ${day(x.saturday)}")).isVisible()

        // Marked again from Saturday, Sunday comes back with it: the card has the three days.
        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${day(x.saturday)}"))
        assertThat(page.locator("#riga-${x.luca}")).containsText("Segnata anche per ${CheckinFormat.daysInSentence(listOf(x.sunday))}.")
        assertEquals(listOf(x.friday, x.saturday, x.sunday), e2e.days(x.luca))

        // Criterion 10, a day taken by another teacher a moment before: «Togli» on Sunday is a notice and no error, and Saturday stays.
        e2e.open("/arrivi/${x.luca}")
        assertThat(page.cardDays().nth(2)).containsText("Sarà presente")
        e2e.dsl().deleteFrom(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(x.luca)).and(CHECKIN.DAY.eq(x.sunday)).execute()
        e2e.press(page.buttonNamed("Togli la presenza di Luca Rossi, ${day(x.sunday)}"))
        assertThat(page.notice()).hasText("La presenza era già stata tolta.")
        assertEquals(listOf(x.friday, x.saturday), e2e.days(x.luca))
        assertThat(page.cardDays().nth(2)).containsText("Si potrà segnare da quel giorno.")

        // Criterion 10: Sunday, a day after today, is taken away alone from the card (Luca goes home on Saturday evening), and Saturday stays.
        e2e.row(x.luca, x.sunday, x.w.martaId)
        e2e.open("/arrivi/${x.luca}")
        assertThat(page.cardDays().nth(1)).containsText("Togli vale anche per ${CheckinFormat.daysInSentence(listOf(x.sunday))}.")
        e2e.press(page.buttonNamed("Togli la presenza di Luca Rossi, ${day(x.sunday)}"))
        assertThat(page.notice()).hasText("Presenza tolta.")
        assertEquals(listOf(x.friday, x.saturday), e2e.days(x.luca))
        assertThat(page.cardDays().nth(1)).containsText("Presente")
        assertThat(page.cardDays().nth(1)).not().containsText("Togli vale anche")
        page.looksRight(e2e, "card after taking Sunday away")

        // Criterion 11: Sara was marked by mistake on Friday; one touch on Friday takes away the whole mark and no row is left.
        e2e.open("/arrivi?giorno=${x.friday}")
        e2e.press(page.buttonNamed("Segna presente Sara Neri, ${day(x.friday)}"))
        assertThat(page.locator("#riga-${x.sara}")).containsText("Presente")
        assertEquals(listOf(x.friday, x.saturday), e2e.days(x.sara))
        e2e.press(page.buttonNamed("Togli la presenza di Sara Neri, da ${day(x.friday)} in poi"))
        assertThat(page.locator("#riga-${x.sara}")).containsText("Tolta anche per ${CheckinFormat.daysInSentence(listOf(x.saturday))}.")
        assertEquals(emptyList(), e2e.days(x.sara))
    }

    @E2eTest
    fun `t19 c09 c16 while the answer of Togli is late the button of the card says Tolgo and waits, and the answer says the days that went`(e2e: E2e) {
        val page = e2e.page
        val x = e2e.weekend(first = -1)
        val moment = OffsetDateTime.now(e2e.clock()).minusDays(1)
        for (d in listOf(x.friday, x.saturday, x.sunday)) e2e.row(x.luca, d, x.w.martaId, moment)
        e2e.open("/arrivi/${x.luca}")
        val togli = page.buttonNamed("Togli la presenza di Luca Rossi, da ${day(x.saturday)} in poi")
        val held = AtomicReference<Route>()
        page.route("**/presenze/togli") { held.set(it) }

        e2e.press(togli)
        page.waitUntil("document.querySelector('#scheda form[action$=\"/presenze/togli\"] button[disabled]') !== null")
        assertThat(togli).isDisabled()
        assertThat(togli.getByText("Tolgo…")).isVisible()
        assertEquals(listOf(x.friday, x.saturday, x.sunday), e2e.days(x.luca), "nothing has happened yet")
        assertThat(page.cardDays().nth(2)).containsText("Sarà presente")
        assertTrue(page.noHorizontalScroll())

        held.get().resume()
        assertThat(page.notice()).hasText("${CheckinFormat.unmarkedHead(listOf(x.saturday, x.sunday))}.")
        assertEquals(listOf(x.friday), e2e.days(x.luca))
        assertThat(page.cardDays().nth(1).buttonNamed("Segna presente Luca Rossi, ${day(x.saturday)}")).isVisible()
        assertEquals(0, page.locator(".htmx-request").count())
        page.looksRight(e2e, "card after Togli on two days")
    }

    // ---- Criteria 12 and 13: the withdrawal and the correction of the registration --------------------------------------------------------

    @E2eTest
    fun `t19 c12 the withdrawal of the teacher takes away the days that have not come and keeps today, the card of a withdrawn child has no promise, and putting it back does not bring them back`(e2e: E2e) {
        val page = e2e.page
        val x = e2e.weekend(first = 0)
        e2e.open("/arrivi")
        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${day(x.friday)}"))
        assertThat(page.locator("#riga-${x.luca}")).containsText("Presente")
        e2e.press(page.buttonNamed("Segna presente Sara Neri, ${day(x.friday)}"))
        assertThat(page.locator("#riga-${x.sara}")).containsText("Presente")
        assertEquals(listOf(x.friday, x.saturday, x.sunday), e2e.days(x.luca))
        assertEquals(listOf(x.friday, x.saturday), e2e.days(x.sara))

        e2e.open("/iscrizioni/${x.luca}")
        e2e.press(page.locator("main").linkNamed("Ritira l’iscrizione"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Ritira l’iscrizione"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione ritirata.")

        assertEquals(listOf(x.friday), e2e.days(x.luca), "Friday is today: it stays, the days that have not come go")
        assertEquals(listOf(x.friday, x.saturday), e2e.days(x.sara), "the other child is not touched")

        e2e.open("/arrivi/${x.luca}")
        assertThat(page.locator("#scheda")).containsText("Iscrizione ritirata.")
        assertThat(page.cardDays().nth(0)).containsText("Presente")
        assertThat(page.cardDays().nth(0).buttonNamed("Togli la presenza di Luca Rossi, ${day(x.friday)}")).isVisible()
        for (n in 1..2) {
            assertThat(page.cardDays().nth(n)).not().containsText("Sarà presente")
            assertEquals(0, page.cardDays().nth(n).locator("button").count(), "no button on a day that has not come, for a withdrawn registration")
        }
        page.looksRight(e2e, "card of a withdrawn registration")

        // Putting back brings no row back: Saturday and Sunday are marked when they come.
        e2e.press(page.locator("#scheda").buttonNamed("Rimetti confermata"))
        assertThat(page.notice()).containsText("Iscrizione rimessa confermata.")
        assertEquals(listOf(x.friday), e2e.days(x.luca))
        assertThat(page.cardDays().nth(1)).not().containsText("Sarà presente")
        assertThat(page.cardDays().nth(1)).containsText("Si potrà segnare da quel giorno.")
    }

    @E2eTest
    fun `t19 c13 the correction of the days of the registration takes away the rows of the days that are not covered, and keeps the ones that still are`(e2e: E2e) {
        val page = e2e.page
        val x = e2e.weekend(first = 0)
        val anna = e2e.rows().register(x.w.id, e2e.someParent(), "Anna", "Verdi", LocalDate.of(2018, 2, 2))
        e2e.open("/arrivi")
        e2e.press(page.buttonNamed("Segna presente Luca Rossi, ${day(x.friday)}"))
        assertThat(page.locator("#riga-${x.luca}")).containsText("Presente")
        e2e.press(page.buttonNamed("Segna presente Anna Verdi, ${day(x.friday)}"))
        assertThat(page.locator("#riga-$anna")).containsText("Presente")
        assertEquals(listOf(x.friday, x.saturday, x.sunday), e2e.days(x.luca))
        assertEquals(listOf(x.friday, x.saturday, x.sunday), e2e.days(anna))

        // Luca is now Friday and Sunday: Saturday goes, and Sunday goes with it (Saturday would be its day before).
        e2e.correctDays(x.luca, mapOf("fri" to "full", "sat" to "", "sun" to "full"))
        assertEquals(listOf(x.friday), e2e.days(x.luca))
        // Anna is now Friday and Saturday: Sunday goes, Saturday stays.
        e2e.correctDays(anna, mapOf("fri" to "full", "sat" to "full", "sun" to ""))
        assertEquals(listOf(x.friday, x.saturday), e2e.days(anna))

        e2e.open("/arrivi/$anna")
        assertThat(page.cardDays().nth(1)).containsText("Sarà presente")
        assertThat(page.cardDays().nth(2)).not().containsText("Sarà presente")
        e2e.open("/arrivi/${x.luca}")
        assertThat(page.cardDays().nth(0)).containsText("Presente")
        assertThat(page.cardDays().nth(1)).containsText("Non previsto")
        assertThat(page.cardDays().nth(2)).not().containsText("Sarà presente")
        page.looksRight(e2e, "card of a corrected registration")
    }

    /** The correction of the days of [registration] as a teacher makes it: the form of the page of the registration, with «Alcuni giorni» and the kind of each day (empty: not that day). */
    private fun E2e.correctDays(registration: Long, kinds: Map<String, String>) {
        open("/iscrizioni/$registration")
        press(page.locator("main").linkNamed("Correggi l’iscrizione"))
        page.locator("input[name='children[0].presence'][value='days']").check()
        for ((key, kind) in kinds) page.locator("input[name='children[0].day.$key'][value='$kind']").check()
        press(page.locator("main").buttonNamed("Salva le correzioni"))
        assertThat(page.locator("[role=status]")).containsText("Iscrizione salvata.")
    }

    // ---- Criteria 7, 8 and 17: the same presents everywhere ---------------------------------------------------------------------------

    @E2eTest
    fun `t19 c07 c08 c17 the list, the summary, the register and the list for the venue say the same presents, a day that has not come has none, and at midnight the rows of the day count with nothing written`(e2e: E2e) =
        e2e.keepingTheClock { samePresents(e2e) }

    private fun samePresents(e2e: E2e) {
        val page = e2e.page
        val x = e2e.weekend(first = -1)
        val anna = e2e.rows().register(x.w.id, e2e.someParent(), "Anna", "Verdi", LocalDate.of(2018, 2, 2))
        val yesterday = OffsetDateTime.now(e2e.clock()).minusDays(1)
        // Luca arrived on Friday (the three days written); Sara, withdrawn, has the rows of Friday and Saturday; Nicolò is on Saturday, which his registration does not include, and Sunday.
        for (d in listOf(x.friday, x.saturday, x.sunday)) e2e.row(x.luca, d, x.w.martaId, yesterday)
        for (d in listOf(x.friday, x.saturday)) e2e.row(x.sara, d, x.w.martaId, yesterday)
        e2e.dsl().update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(x.sara)).execute()
        for (d in listOf(x.saturday, x.sunday)) e2e.row(x.nicolo, d, x.w.paoloId, OffsetDateTime.now(e2e.clock()).minusSeconds(30))
        val rowsBefore = e2e.dsl().fetchCount(CHECKIN)
        assertTrue(anna > 0)

        // Saturday (today): Luca (expected) and Nicolò (not expected) are present; Sara is withdrawn and Anna has no row.
        e2e.open("/arrivi")
        assertThat(page.locator("#riepilogo")).containsText(Pattern.compile("Presenti\\s*1\\s*su 2 attesi"))
        assertThat(page.locator("#riepilogo")).containsText(Pattern.compile("Presenti non previsti\\s*1"))
        assertThat(page.locator("#riga-${x.luca}")).containsText("Presente")
        assertThat(page.locator("#riga-${x.nicolo}")).containsText("Presente")
        assertThat(page.locator("#riga-${x.sara}")).not().containsText("Presente")
        assertThat(page.locator("#riga-$anna")).not().containsText("Presente")

        e2e.open("/arrivi/registro")
        val register = page.locator("main table.tabella-stampa").first()
        fun marks(name: String): List<String> = register.locator("tbody tr").filter(Locator.FilterOptions().setHasText(name)).locator(".casella").allInnerTexts().map { it.trim() }
        // The columns are Friday, Saturday and Sunday: the X of Sunday is not there for anybody, because Sunday has not come.
        assertEquals(listOf("X", "X", ""), marks("Luca Rossi"))
        assertEquals(listOf("", "X", ""), marks("Nicolò Bianchi"))
        assertEquals(listOf("", "", ""), marks("Anna Verdi"))
        assertEquals(0, register.locator("tbody tr").filter(Locator.FilterOptions().setHasText("Sara Neri")).count(), "a withdrawn registration is not in the register")
        assertTrue(page.noHorizontalScroll())

        e2e.open("/arrivi/elenco?giorno=${x.saturday}")
        assertEquals(listOf("Bianchi", "Rossi"), page.locator("main table").first().locator("tbody tr:not(.riga-vuota) td.nome").allInnerTexts().map { it.trim() }, "the same two presents")
        assertThat(page.locator("main h2").first()).containsText("· 2")

        // Sunday has not come: nobody is present in the list, in the summary and in the list for the venue, although the rows are there.
        e2e.open("/arrivi?giorno=${x.sunday}")
        assertThat(page.locator("#riepilogo")).containsText(Pattern.compile("Presenti\\s*Nessuno\\s*su 3 attesi"))
        assertEquals(0, page.locator("#riga-${x.luca}:has-text('Presente')").count())
        e2e.open("/arrivi/elenco?giorno=${x.sunday}")
        assertThat(page.locator("main")).containsText("Nessun bambino presente ${day(x.sunday)}")
        assertThat(page.locator("main")).containsText("${CheckinFormat.longDayCapital(x.sunday)} non è ancora arrivato: i bambini si contano presenti da quel giorno.")
        page.looksRight(e2e, "list for the venue of a day that has not come")

        // Midnight: Sunday comes. Nothing is written, and Luca and Nicolò are present on Sunday with no touch.
        e2e.nextDay()
        e2e.open("/arrivi")
        assertThat(page.locator("#riepilogo")).containsText(Pattern.compile("Presenti\\s*2\\s*su 3 attesi"))
        assertThat(page.locator("#riga-${x.luca}")).containsText("Presente")
        assertThat(page.locator("#riga-${x.nicolo}")).containsText("Presente")
        assertEquals(rowsBefore, e2e.dsl().fetchCount(CHECKIN), "nothing was written at midnight")
        e2e.open("/arrivi/elenco?giorno=${x.sunday}")
        assertThat(page.locator("main h2").first()).containsText("· 2")
        e2e.open("/arrivi/registro")
        assertEquals(listOf("X", "X", "X"), marks("Luca Rossi"))
        assertEquals(listOf("", "X", "X"), marks("Nicolò Bianchi"))
    }

    // ---- Criterion 15: the states of a day on the card ----------------------------------------------------------------------------------

    @E2eTest
    fun `t19 c15 the card says for every day if the child is present, who wrote it and when, if it will be present, or if it can be marked, and the days that have not come have no Segna presente`(e2e: E2e) {
        val page = e2e.page
        // Thursday, Friday, Saturday (today), Sunday.
        val w = e2e.arrivalsGathering(first = -2, last = 1)
        val thursday = w.today.minusDays(2)
        val friday = w.yesterday
        val saturday = w.today
        val sunday = w.tomorrow
        val threeDaysAgo = OffsetDateTime.now(e2e.clock()).minusDays(2)
        val rows = e2e.rows()
        val luca = rows.register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val sara = rows.register(w.id, e2e.someParent(), "Sara", "Neri", LocalDate.of(2017, 3, 3))
        val nicolo = rows.register(w.id, e2e.someParent(), "Nicolò", "Bianchi", LocalDate.of(2015, 6, 2))
        // Luca: Thursday (his own day), Friday (written by the arrival of Thursday), nothing on Saturday, nothing on Sunday.
        e2e.row(luca, thursday, w.martaId, threeDaysAgo)
        e2e.row(luca, friday, w.martaId, threeDaysAgo)
        // Sara: Friday (her own day) and, with it, Saturday and Sunday.
        val fridayMoment = OffsetDateTime.now(e2e.clock()).minusDays(1)
        e2e.row(sara, friday, w.paoloId, fridayMoment)
        e2e.row(sara, saturday, w.paoloId, fridayMoment)
        e2e.row(sara, sunday, w.paoloId, fridayMoment)
        // Nicolò: Thursday, copied from the register of paper today.
        val today = OffsetDateTime.now(e2e.clock())
        e2e.row(nicolo, thursday, w.martaId, today)

        // Luca: Presente (own day), Presente (the arrival of Thursday), Segna presente (today, no row), Si potrà segnare (Sunday, no row).
        e2e.open("/arrivi/$luca")
        val days = page.cardDays()
        assertEquals(4, days.count())
        assertThat(days.nth(0)).containsText(Pattern.compile("Presente\\s*Segnata da Marta Bianchi alle \\d+:\\d\\d"))
        assertThat(days.nth(0)).containsText("Togli")
        assertThat(days.nth(1)).containsText("Presente")
        assertThat(days.nth(1)).containsText("Segnata da Marta Bianchi con l’arrivo, ${CheckinFormat.shortDay(thursday)} alle")
        assertThat(days.nth(1)).containsText("Togli")
        assertThat(days.nth(1)).not().containsText("Togli vale anche")
        assertThat(days.nth(2).buttonNamed("Segna presente Luca Rossi, ${day(saturday)}")).isVisible()
        assertThat(days.nth(2)).not().containsText("Presente")
        assertThat(days.nth(3)).containsText("Si potrà segnare da quel giorno.")
        assertEquals(0, days.nth(3).locator("button").count(), "a day that has not come has no Segna presente")
        page.looksRight(e2e, "card of Luca, the four states")

        // Sara: Friday, her own day (Presente, with the hour); Saturday, written with the arrival of Friday (Presente); Sunday (Sarà presente) with the line that says from when it counts.
        e2e.open("/arrivi/$sara")
        assertThat(days.nth(1)).containsText(Pattern.compile("Presente\\s*Segnata da Paolo Verdi alle \\d+:\\d\\d"))
        assertThat(days.nth(1)).containsText("Togli vale anche per ${CheckinFormat.daysInSentence(listOf(saturday, sunday))}.")
        assertThat(days.nth(2)).containsText("Presente")
        assertThat(days.nth(2)).not().containsText("Sarà presente")
        assertThat(days.nth(2)).containsText("Segnata da Paolo Verdi con l’arrivo, ${CheckinFormat.shortDay(friday)} alle")
        assertThat(days.nth(2)).containsText("Togli vale anche per ${CheckinFormat.daysInSentence(listOf(sunday))}.")
        assertThat(days.nth(3)).containsText("Sarà presente")
        assertThat(days.nth(3)).containsText("Conta come presente da ${CheckinFormat.daysInSentence(listOf(sunday))}.")
        assertThat(days.nth(3)).not().containsText("Togli vale anche")
        assertEquals(0, days.nth(3).locator("button:has-text('Segna presente')").count(), "no Segna presente on a day that has a row and has not come")
        assertEquals(1, days.nth(3).locator("button:has-text('Togli')").count(), "but Togli is there")
        page.looksRight(e2e, "card of Sara, the day that will be present")

        // Nicolò: Thursday was written today, after the day: the row says the day of the writing.
        e2e.open("/arrivi/$nicolo")
        assertThat(days.nth(0)).containsText("il ${CheckinFormat.dayAndMonth(w.today)} alle ${CheckinFormat.time(today)}")
        assertThat(days.nth(0).locator("p").first()).not().containsText("Sarà presente")
    }

    // ---- Criterion 19: what a parent can reach ---------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t19 c19 a parent is refused the arrival, by the page and by hand, no row changes, and the pages of the parent say nothing of the presences`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering(first = -1, last = 1, meTeacher = false)
        val luca = e2e.rows().register(w.id, w.meId, "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val moment = OffsetDateTime.now(e2e.clock()).minusDays(1)
        for (d in listOf(w.yesterday, w.today, w.tomorrow)) e2e.row(luca, d, w.martaId, moment)

        // The pages of the parent: no presence, no arrival, no word of the check-in.
        e2e.open("/iscrizioni")
        assertThat(page.locator("main")).containsText("Luca Rossi")
        val text = page.locator("body").said()
        for (word in listOf("presente", "Presente", "Sarà presente", "presenze", "Segna presente")) assertTrue(!text.contains(word), "the page of the parent says «$word»: $text")
        assertAccessible(page, "registrations of the parent with rows of presence (${e2e.profile.label})")

        // The pages of the teachers are refused, the registrations of the others too.
        for (path in listOf("/arrivi", "/arrivi/$luca", "/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            assertEquals(403, e2e.open(path).status(), path)
            assertThat(page.locator("h1")).hasText("Non puoi vedere questa pagina")
            assertTrue(!page.content().contains("Luca Rossi"), "$path: a refused page says no name")
        }

        // By hand, with a valid token of the session: the same 403, and the rows are as they were.
        e2e.open("/iscrizioni")
        val csrf = page.locator("input[name=_csrf]").first().inputValue()
        for ((path, day) in listOf("/arrivi/$luca/presenze" to w.today, "/arrivi/$luca/presenze/togli" to w.yesterday, "/arrivi/$luca/presenze/togli" to w.tomorrow)) {
            val response = page.request().post(
                e2e.url(path),
                RequestOptions.create().setForm(FormData.create().set("_csrf", csrf).set("giorno", day.toString()).set("da", "scheda")).setMaxRedirects(0).setHeader("HX-Request", "true"),
            )
            assertEquals(403, response.status(), "$path $day")
        }
        assertEquals(listOf(w.yesterday, w.today, w.tomorrow), e2e.days(luca))
        assertEquals(3, e2e.dsl().fetchCount(CHECKIN))
    }

    // ---- Criterion 20: the notice -----------------------------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `t19 c20 the complete notice says that the mark of the arrival is for the days after it too, until a teacher takes it away, and it reads at 320 px`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/informativa")
        assertThat(page.locator("main")).containsText("il segno dell’arrivo vale anche per i giorni dopo a cui il bambino è iscritto, finché un maestro non lo toglie")
        assertThat(page.locator("main")).containsText("il giorno e l’ora in cui un maestro segna che un bambino c’è")
        page.looksRight(e2e, "complete notice")
    }
}
