package talita.reminder

import org.jooq.DSLContext
import talita.Addresses
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.REMINDER_BATCH
import talita.registration.RegistrationRows
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/** A person of a test: the id of the account and its address (made with [Addresses], on the reserved domain). */
internal data class Person(val id: Long, val email: String)

/**
 * The people and the gatherings of the examples of TALITA-13 (the criteria begin with them), made straight in the tables like data of yesterday. Names, titles and
 * dates are synthetic: the gathering «Convivenza di settembre 2027» is made from the day of the test clock (a month away), the other one is last year's.
 *
 * - **A** has Luca (confirmed) and Anna (in forse) at the gathering that is open;
 * - **B** has Sara at last year's gathering, has asked for the avviso, and has no registration to the open one;
 * - **D** is like B without having asked for the avviso;
 * - **C** is an empty account (it came in and registered nobody);
 * - **Nicolò Ferri** is a paper registration, in forse: no account, no address;
 * - **M** and **N** are teachers, M is the custodian.
 */
internal class ReminderTestKit(private val dsl: DSLContext, private val clock: Clock) {

    val support = GatheringSupport(dsl, clock)

    val rows = RegistrationRows(dsl, clock)

    /** Every gathering with its registrations and children, so with its batches too (`on delete cascade`): the reminders have nothing left. */
    fun reset() = support.reset()

    fun today(): LocalDate = support.today()

    // ---- People ---------------------------------------------------------------------------------------------------------------

    /** An account that nobody has used yet, with what it chose about the reminders: the avviso (with the moment) and the stop (with the moment). */
    fun account(
        name: String,
        notice: OffsetDateTime? = null,
        stopped: OffsetDateTime? = null,
        code: String? = null,
    ): Person {
        val email = Addresses.unique(name)
        val id = dsl.insertInto(ACCOUNT)
            .set(ACCOUNT.EMAIL, email)
            .set(ACCOUNT.CREATED_ON, today())
            .set(ACCOUNT.NOTICE_CONSENT_AT, notice)
            .set(ACCOUNT.REMINDERS_OFF_AT, stopped)
            .set(ACCOUNT.REMINDER_CODE, code)
            .returningResult(ACCOUNT.ID).fetchSingle().value1()!!
        return Person(id, email)
    }

    fun teacher(name: String, first: String = "Marta", last: String = "Bianchi"): Person {
        val email = Addresses.unique(name)
        return Person(TestAccounts.makeTeacher(dsl, email, first, last), email)
    }

    // ---- The example ----------------------------------------------------------------------------------------------------------

    data class Scenario(
        /** The gathering that is open, «Convivenza di settembre 2027». */
        val gathering: Long,
        /** Last year's, closed. */
        val previous: Long,
        val a: Person,
        val b: Person,
        val c: Person,
        val d: Person,
        val m: Person,
        val n: Person,
        val luca: Long,
        val anna: Long,
        val sara: Long,
        val nicolo: Long,
    )

    /** Clears everything and makes the example: see the description of the class. [noticeAt] is when B asked for the avviso. */
    fun scenario(noticeAt: OffsetDateTime = OffsetDateTime.now(clock).minusDays(30)): Scenario {
        reset()
        val m = teacher("promemoria-m", "Marta", "Bianchi")
        val n = teacher("promemoria-n", "Paolo", "Verdi")
        val today = today()
        val previous = support.make(
            title = "Convivenza dell’anno scorso", start = today.minusDays(400), end = today.minusDays(398), status = GatheringStatus.closed, custodians = listOf(m.id),
        )
        val gathering = support.make(title = "Convivenza di settembre 2027", start = today.plusDays(30), end = today.plusDays(32), status = GatheringStatus.`open`, custodians = listOf(m.id))
        val a = account("promemoria-a")
        val luca = rows.register(gathering, a.id, "Luca", "Rossi", email = a.email)
        val anna = rows.register(gathering, a.id, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.maybe, email = a.email)
        val b = account("promemoria-b", notice = noticeAt)
        val sara = rows.register(previous, b.id, "Sara", "Neri", LocalDate.of(2017, 6, 2), email = b.email)
        val c = account("promemoria-c")
        val d = account("promemoria-d")
        rows.register(previous, d.id, "Marco", "Gialli", LocalDate.of(2016, 3, 11), email = d.email)
        val nicolo = rows.register(gathering, null, "Nicolò", "Ferri", LocalDate.of(2018, 8, 8), status = RegistrationStatus.maybe, enteredBy = m.id, paperReceived = true)
        return Scenario(gathering, previous, a, b, c, d, m, n, luca, anna, sara, nicolo)
    }

    // ---- The tables -----------------------------------------------------------------------------------------------------------

    fun batches(gathering: Long): List<talita.jooq.tables.records.ReminderBatchRecord> =
        dsl.selectFrom(REMINDER_BATCH).where(REMINDER_BATCH.GATHERING_ID.eq(gathering)).orderBy(REMINDER_BATCH.ID).fetch()

    fun consentOf(id: Long): OffsetDateTime? = dsl.select(ACCOUNT.NOTICE_CONSENT_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.NOTICE_CONSENT_AT)

    fun stopOf(id: Long): OffsetDateTime? = dsl.select(ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.REMINDERS_OFF_AT)

    fun codeOf(id: Long): String? = dsl.select(ACCOUNT.REMINDER_CODE).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.REMINDER_CODE)

    fun exists(id: Long): Boolean = dsl.fetchExists(ACCOUNT, ACCOUNT.ID.eq(id))
}
