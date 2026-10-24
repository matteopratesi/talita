package talita.checkin

import org.jooq.DSLContext
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.account.MockBrowser
import talita.gathering.GatheringTestKit
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.PAYMENT
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.TEACHER
import talita.registration.RegistrationFormat
import talita.registration.RegistrationRows
import talita.teacher.Person
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** The visible text of a page, roughly (tags out, spaces squeezed), with the non-breaking spaces as plain ones: the amounts have one before the euro sign. */
internal fun words(html: String): String = talita.gathering.text(html).replace('\u00a0', ' ')

/** An amount as the pages write it, with a plain space before the euro sign (`60 €`). */
internal fun euro(cents: Int): String = RegistrationFormat.money(cents).replace('\u00a0', ' ')

/**
 * A gathering as the tests of the arrivals need it: open, three days with today in the middle (yesterday, today, tomorrow), Marta Bianchi and Paolo Verdi as
 * custodians and Giulia Serra as a teacher who is not. The days of the week are those of the day the test runs (the clock is the real one, moved only forward):
 * the sentences that need a weekday are made from the dates with the same formatter that the pages use.
 */
internal data class CheckinSetup(
    val id: Long,
    /** Marta Bianchi, as a browser that has entered, and the ids of the accounts of the three teachers. */
    val marta: MockBrowser,
    val martaId: Long,
    val paoloId: Long,
    val giuliaId: Long,
    val paolo: MockBrowser,
    val yesterday: LocalDate,
    val today: LocalDate,
    val tomorrow: LocalDate,
)

/**
 * What the tests of the arrivals have in common: the gathering and the teachers, the registrations made straight in the tables (like the ones of yesterday), the
 * presences and payments written as the database holds them, and a few readings. Names, dates and numbers are synthetic.
 */
internal class CheckinTestKit(private val mockMvc: MockMvc, tokens: OneTimeTokenService, private val dsl: DSLContext, private val clock: Clock) {

    val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    val rows = RegistrationRows(dsl, clock)

    fun today(): LocalDate = kit.gatherings.today()

    /** A browser that has entered with the link, for the teacher with this address. */
    fun browserOf(email: String): MockBrowser = MockBrowser(mockMvc).also { it.confirm(kit.link(email)) }

    /**
     * Resets everything and makes a gathering of the days from [first] to [last] days away from today (by default yesterday to tomorrow), open, with the fees
     * 60, 25 and 10 euros, with Marta Bianchi and Paolo Verdi as custodians. Giulia Serra is a teacher too. Marta has entered, and so has Paolo.
     */
    fun start(first: Long = -1, last: Long = 1, status: GatheringStatus = GatheringStatus.`open`, full: Int? = 6000, daily: Int? = 2500, lunch: Int? = 1000): CheckinSetup {
        val marta = Person(Addresses.unique("marta"), "Marta", "Bianchi")
        val paolo = Person(Addresses.unique("paolo"), "Paolo", "Verdi")
        val giulia = Person(Addresses.unique("giulia"), "Giulia", "Serra")
        val (browser, martaId) = kit.start(marta, paolo, giulia)
        val paoloId = kit.accountId(paolo.email)
        val today = today()
        val id = kit.gatherings.make(
            start = today.plusDays(first), end = today.plusDays(last), status = status, full = full, daily = daily, lunchOnly = lunch, custodians = listOf(martaId, paoloId),
        )
        return CheckinSetup(id, browser, martaId, paoloId, kit.accountId(giulia.email), browserOf(paolo.email), today.minusDays(1), today, today.plusDays(1))
    }

    /** An account that nobody has entered with: the owner of an online registration made straight in the table. */
    fun account(name: String = "genitore"): Long {
        val email = Addresses.unique(name)
        dsl.insertInto(ACCOUNT).set(ACCOUNT.EMAIL, email).execute()
        return dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!
    }

    /** The person stops being a teacher (the account stays). */
    fun removeTeacher(accountId: Long) {
        dsl.deleteFrom(TEACHER).where(TEACHER.ACCOUNT_ID.eq(accountId)).execute()
    }

    // ---- The tables ---------------------------------------------------------------------------------------------------------------------

    /** A presence written as it is kept, at the given moment of the day (a moment of the day of the test, in the hour and minute given). */
    fun presence(registration: Long, day: LocalDate, by: Long, at: OffsetDateTime = OffsetDateTime.now(clock)) {
        dsl.insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, registration).set(CHECKIN.DAY, day).set(CHECKIN.RECORDED_AT, at).set(CHECKIN.RECORDED_BY_ACCOUNT_ID, by).execute()
    }

    /** A payment written as it is kept; a new code of the form if none is given. Returns its id. */
    fun payment(registration: Long, cents: Int, by: Long, at: OffsetDateTime = OffsetDateTime.now(clock), request: UUID = UUID.randomUUID()): Long =
        dsl.insertInto(PAYMENT)
            .set(PAYMENT.REGISTRATION_ID, registration)
            .set(PAYMENT.AMOUNT_CENTS, cents)
            .set(PAYMENT.RECORDED_AT, at)
            .set(PAYMENT.RECORDED_BY_ACCOUNT_ID, by)
            .set(PAYMENT.REQUEST_ID, request)
            .returningResult(PAYMENT.ID).fetchSingle().value1()!!

    /** The id of the registration that was made last. */
    fun lastRegistrationId(): Long = dsl.select(REGISTRATION.ID).from(REGISTRATION).orderBy(REGISTRATION.ID.desc()).limit(1).fetchSingle(REGISTRATION.ID)!!

    /** The presence of a registration on a day, as it is kept; fails when there is none. */
    fun presenceOf(registration: Long, day: LocalDate): Checkin =
        dsl.select(CHECKIN.REGISTRATION_ID, CHECKIN.DAY, CHECKIN.RECORDED_AT, CHECKIN.RECORDED_BY_ACCOUNT_ID).from(CHECKIN)
            .where(CHECKIN.REGISTRATION_ID.eq(registration)).and(CHECKIN.DAY.eq(day))
            .fetchSingle { Checkin(it[CHECKIN.REGISTRATION_ID]!!, it[CHECKIN.DAY]!!, it[CHECKIN.RECORDED_AT]!!, it[CHECKIN.RECORDED_BY_ACCOUNT_ID]!!) }

    fun presenceCount(): Int = dsl.fetchCount(CHECKIN)

    fun paymentCount(): Int = dsl.fetchCount(PAYMENT)

    fun presenceDays(registration: Long): List<LocalDate> = dsl.select(CHECKIN.DAY).from(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(registration)).orderBy(CHECKIN.DAY).fetch(CHECKIN.DAY).map { it!! }

    fun statusOf(registration: Long): String = dsl.select(REGISTRATION.STATUS).from(REGISTRATION).where(REGISTRATION.ID.eq(registration)).fetchSingle(REGISTRATION.STATUS)!!.literal
}
