package talita.e2e

import talita.Addresses
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.PAYMENT
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A gathering for the tests of the arrivals: the days are around today (yesterday, today and tomorrow by default), because the presences are marked on the days that
 * are not after today. The person of the test is a teacher, Giulia Serra, who is not a custodian; the custodians are Marta Bianchi and Paolo Verdi. Everything here is synthetic.
 */
internal data class ArrivalsWorld(val id: Long, val martaId: Long, val paoloId: Long, val meId: Long, val today: LocalDate) {
    val yesterday: LocalDate get() = today.minusDays(1)
    val tomorrow: LocalDate get() = today.plusDays(1)
}

/**
 * The teachers Marta Bianchi, Paolo Verdi and the person of the test (Giulia Serra), and no other, no invitation and no gathering but this one: the database is shared.
 * [first] and [last] are the days from today that the gathering begins and ends. With [meTeacher] false the person of the test is left as the account that it is (a parent):
 * [ArrivalsWorld.meId] is then the id of that account, which is not a teacher.
 */
internal fun E2e.arrivalsGathering(first: Long = -1, last: Long = 1, status: GatheringStatus = GatheringStatus.`open`, meTeacher: Boolean = true): ArrivalsWorld {
    val dsl = dsl()
    dsl.deleteFrom(TEACHER_INVITATION).execute()
    dsl.deleteFrom(TEACHER).execute()
    val support = GatheringSupport(dsl, application.getBean(Clock::class.java))
    support.reset()
    val marta = TestAccounts.makeTeacher(dsl, Addresses.unique("e2e-marta"), "Marta", "Bianchi")
    val paolo = TestAccounts.makeTeacher(dsl, Addresses.unique("e2e-paolo"), "Paolo", "Verdi")
    val me = if (meTeacher) TestAccounts.makeTeacher(dsl, checkNotNull(signedInAs), "Giulia", "Serra") else accountId(checkNotNull(signedInAs))
    val today = support.today()
    val id = support.make(start = today.plusDays(first), end = today.plusDays(last), status = status, code = GatheringSupport.nextCode(), custodians = listOf(marta, paolo))
    return ArrivalsWorld(id, marta, paolo, me, today)
}

/** An account that nobody enters with: the owner of an online registration made straight in the table. */
internal fun E2e.someParent(): Long {
    val email = Addresses.unique("e2e-genitore")
    dsl().insertInto(talita.jooq.tables.references.ACCOUNT).set(talita.jooq.tables.references.ACCOUNT.EMAIL, email).execute()
    return accountId(email)
}

/** A payment written as it is kept, by the teacher [by], at this moment. */
internal fun E2e.payment(registration: Long, cents: Int, by: Long) {
    val clock = application.getBean(Clock::class.java)
    dsl().insertInto(PAYMENT)
        .set(PAYMENT.REGISTRATION_ID, registration)
        .set(PAYMENT.AMOUNT_CENTS, cents)
        .set(PAYMENT.RECORDED_AT, OffsetDateTime.now(clock))
        .set(PAYMENT.RECORDED_BY_ACCOUNT_ID, by)
        .set(PAYMENT.REQUEST_ID, UUID.randomUUID())
        .execute()
}
