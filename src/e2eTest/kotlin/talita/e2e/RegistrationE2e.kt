package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.AriaRole
import org.jooq.DSLContext
import talita.Addresses
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.jooq.tables.references.TEACHER
import talita.registration.RegistrationRows
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate

/** What the e2e tests of the registrations have in common: a gathering with two custodians, and the readings of a page. Everything here is synthetic. */
internal data class OpenGathering(val id: Long, val code: String, val friday: LocalDate, val martaId: Long, val paoloId: Long) {
    val saturday: LocalDate get() = friday.plusDays(1)
    val sunday: LocalDate get() = friday.plusDays(2)
}

internal fun E2e.dsl(): DSLContext = application.getBean(DSLContext::class.java)

/** The day of the application (its clock may be moved by a test). */
internal fun E2e.today(): LocalDate = LocalDate.now(application.getBean(Clock::class.java))

internal fun E2e.rows(): RegistrationRows = RegistrationRows(dsl(), application.getBean(Clock::class.java))

internal fun E2e.accountId(email: String): Long = dsl().select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!

/**
 * The teachers Marta Bianchi and Paolo Verdi (and no other, and no invitation), and a gathering of three days from a Friday a month away with the days in
 * one month, as the others tests leave nothing: the database is shared. The person of the test is not touched.
 */
internal fun E2e.openGathering(
    daily: Int? = 2500,
    lunch: Int? = 1000,
    status: GatheringStatus = GatheringStatus.`open`,
    days: Int = 3,
): OpenGathering {
    val dsl = dsl()
    dsl.deleteFrom(TEACHER_INVITATION).execute()
    dsl.deleteFrom(TEACHER).execute()
    val support = GatheringSupport(dsl, application.getBean(Clock::class.java))
    support.reset()
    val marta = TestAccounts.makeTeacher(dsl, Addresses.unique("e2e-marta"), "Marta", "Bianchi")
    val paolo = TestAccounts.makeTeacher(dsl, Addresses.unique("e2e-paolo"), "Paolo", "Verdi")
    var friday = support.today().plusDays(30)
    while (friday.dayOfWeek != DayOfWeek.FRIDAY || friday.plusDays(days - 1L).month != friday.month) friday = friday.plusDays(1)
    val code = GatheringSupport.nextCode()
    val id = support.make(start = friday, end = friday.plusDays(days - 1L), status = status, daily = daily, lunchOnly = lunch, code = code, custodians = listOf(marta, paolo))
    return OpenGathering(id, code, friday, marta, paolo)
}

internal fun Page.settled() {
    waitForLoadState()
    waitForSettledContent()
}

internal fun Page.noHorizontalScroll(): Boolean = (evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt() <= 0

internal fun Page.focusIs(expression: String) = waitUntil("document.activeElement && ($expression)")

/** The button with exactly this name: «Segnalazioni di Anna» is not the name of «Togli le segnalazioni di Anna». */
internal fun Locator.buttonNamed(name: String): Locator = getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name).setExact(true))

internal fun Locator.linkNamed(name: String): Locator = getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName(name).setExact(true))

/** Brings the element to the middle of the screen before touching it, as a person who scrolls does (the bar of the phone covers what is at the edge). */
internal fun E2e.press(target: Locator) {
    target.evaluate("e => e.scrollIntoView({ block: 'center' })")
    touch(target)
}

internal fun Page.openDialog(): Locator = locator("dialog[open]")

internal fun Page.waitForSettledDialog() {
    waitUntil("document.querySelector('dialog[open]') && document.querySelector('dialog[open]').getAnimations().every(a => a.playState === 'finished')")
}

/** Writes the name, the surname and the date of birth of block [index] of a form of registration. */
internal fun Page.fillBlock(index: Int, first: String, last: String, day: String, month: String, year: String) {
    locator("#b$index-nome").fill(first)
    locator("#b$index-cognome").fill(last)
    locator("#b$index-giorno").fill(day)
    locator("#b$index-mese").fill(month)
    locator("#b$index-anno").fill(year)
}
