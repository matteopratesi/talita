package talita.retention

import org.jooq.DSLContext
import talita.Addresses
import talita.ClockConfiguration
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.PAYMENT
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REGISTRATION_DAY
import talita.jooq.tables.references.RETENTION_LOG
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.registration.Notes
import talita.registration.RegistrationRows
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What the tests of the retention have in common: a clock that is fixed at a moment of Rome (the job and the pages look at the day of Rome), the example of the
 * criteria (the «Convivenza di settembre 2027», from the 17th to the 19th, finished on the 20th), and the rows that a test sets up straight in the tables, like
 * data of yesterday. The database is shared by every test of the application: each test starts with [reset]. Names, dates and addresses are synthetic.
 */
internal class RetentionTestKit(val dsl: DSLContext, val clock: Clock) {

    val gatherings = GatheringSupport(dsl, clock)
    val rows = RegistrationRows(dsl, clock)

    /** A fixed clock at [local] (`2027-10-19T23:59`), a moment of Rome. */
    fun clockAt(local: String): Clock = Clock.fixed(LocalDateTime.parse(local).atZone(ClockConfiguration.ZONE).toInstant(), ClockConfiguration.ZONE)

    /** The moment of a fixed clock, with the offset of Rome. */
    fun moment(local: String): OffsetDateTime = OffsetDateTime.ofInstant(clockAt(local).instant(), ClockConfiguration.ZONE)

    /** Nothing of the data: no gathering, registration, child, invitation or register; the teachers and the accounts of the other tests stay. */
    fun reset() {
        gatherings.reset()
        dsl.deleteFrom(RETENTION_LOG).execute()
        dsl.deleteFrom(TEACHER_INVITATION).execute()
    }

    /** The «Convivenza di settembre 2027» of the criteria: from 17 to 19 September 2027, closed, with these custodians. */
    fun september2027(custodians: List<Long> = emptyList(), status: GatheringStatus = GatheringStatus.closed): Long =
        gatherings.make(title = "Convivenza di settembre 2027", start = LocalDate.of(2027, 9, 17), end = LocalDate.of(2027, 9, 19), status = status, custodians = custodians)

    /** The year after: from 15 to 17 September 2028, finished on the 18th. */
    fun september2028(status: GatheringStatus = GatheringStatus.closed): Long =
        gatherings.make(title = "Convivenza di settembre 2028", start = LocalDate.of(2028, 9, 15), end = LocalDate.of(2028, 9, 17), status = status)

    /** A new account with this address, born on [bornOn]. Returns its id. */
    fun account(name: String, bornOn: LocalDate = LocalDate.of(2027, 1, 1)): Long {
        val email = Addresses.unique(name)
        return dsl.insertInto(ACCOUNT).set(ACCOUNT.EMAIL, email).set(ACCOUNT.CREATED_ON, bornOn).returningResult(ACCOUNT.ID).fetchSingle().value1()!!
    }

    fun teacher(name: String, bornOn: LocalDate = LocalDate.of(2027, 1, 1), first: String = "Marta", last: String = "Bianchi"): Long {
        val id = account(name, bornOn)
        TestAccounts.makeTeacher(dsl, emailOf(id), first, last)
        return id
    }

    fun emailOf(account: Long): String = dsl.select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(account)).fetchSingle(ACCOUNT.EMAIL)!!

    /** An online registration of [first] [last] by the account [owner], with the notes on health if they are given. Returns the id of the registration. */
    fun registration(
        gathering: Long,
        owner: Long,
        first: String = "Luca",
        last: String = "Rossi",
        status: RegistrationStatus = RegistrationStatus.confirmed,
        notes: Notes? = null,
        birth: LocalDate = LocalDate.of(2016, 4, 4),
    ): Long = rows.register(gathering, owner, first, last, birth, status = status, notes = notes)

    /** A paper registration typed in by [teacher], with the notes if they are given. */
    fun paper(gathering: Long, teacher: Long?, first: String = "Nicolò", last: String = "Ferri", notes: Notes? = null): Long =
        rows.register(gathering, null, first, last, enteredBy = teacher, notes = notes, notesOnPaper = notes != null)

    /** A second registration of the child of [from], at [gathering], with the same acceptance and parent: the child registered again the year after. Returns its id. */
    fun registerAgain(gathering: Long, from: Long): Long =
        dsl.fetchOne(
            "insert into registration (gathering_id, child_id, account_id, phone, attendance, status, custody_text, custody_text_version, custody_accepted_at, " +
                "custody_accepted_by_email, created_at, updated_at) select ?, child_id, account_id, phone, attendance, status, custody_text, custody_text_version, " +
                "custody_accepted_at, custody_accepted_by_email, created_at, updated_at from registration where id = ? returning id",
            gathering, from,
        )!!.get(0, Long::class.java)

    /** No account that is not a teacher: the database is shared, and a test that counts the accounts that go starts from a place where there are none. After [reset]. */
    fun removeAccountsWithoutRole() {
        dsl.deleteFrom(ACCOUNT).whereNotExists(org.jooq.impl.DSL.selectOne().from(TEACHER).where(TEACHER.ACCOUNT_ID.eq(ACCOUNT.ID))).execute()
    }

    fun childOf(registration: Long): Long = dsl.select(REGISTRATION.CHILD_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(registration)).fetchSingle(REGISTRATION.CHILD_ID)!!

    /** A presence marked by [teacher] (null: by a teacher whose account is gone). */
    fun presence(registration: Long, day: LocalDate, teacher: Long?) {
        dsl.insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, registration).set(CHECKIN.DAY, day).set(CHECKIN.RECORDED_AT, OffsetDateTime.now(clock))
            .set(CHECKIN.RECORDED_BY_ACCOUNT_ID, teacher).execute()
    }

    /** A payment of [cents] registered by [teacher]. */
    fun payment(registration: Long, cents: Int, teacher: Long?) {
        dsl.insertInto(PAYMENT).set(PAYMENT.REGISTRATION_ID, registration).set(PAYMENT.AMOUNT_CENTS, cents).set(PAYMENT.RECORDED_AT, OffsetDateTime.now(clock))
            .set(PAYMENT.RECORDED_BY_ACCOUNT_ID, teacher).set(PAYMENT.REQUEST_ID, UUID.randomUUID()).execute()
    }

    /** A row of the register of the readings: [teacher] read the notes of [registration]. */
    fun reading(registration: Long, teacher: Long) {
        dsl.insertInto(HEALTH_NOTE_ACCESS).set(HEALTH_NOTE_ACCESS.REGISTRATION_ID, registration).set(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID, teacher)
            .set(HEALTH_NOTE_ACCESS.ACCESSED_AT, OffsetDateTime.now(clock)).execute()
    }

    /** An invitation to be a teacher that expires (or expired) at [expiresAt]. */
    fun invitation(name: String, expiresAt: OffsetDateTime): String {
        val email = Addresses.unique(name)
        dsl.insertInto(TEACHER_INVITATION).set(TEACHER_INVITATION.EMAIL, email).set(TEACHER_INVITATION.EXPIRES_AT, expiresAt).execute()
        return email
    }

    // ---- What is there ------------------------------------------------------------------------------------------------------

    fun notes(): Int = dsl.fetchCount(HEALTH_NOTE)
    fun readings(): Int = dsl.fetchCount(HEALTH_NOTE_ACCESS)
    fun registrations(): Int = dsl.fetchCount(REGISTRATION)
    fun days(): Int = dsl.fetchCount(REGISTRATION_DAY)
    fun children(): Int = dsl.fetchCount(CHILD)
    fun presences(): Int = dsl.fetchCount(CHECKIN)
    fun payments(): Int = dsl.fetchCount(PAYMENT)
    fun custodians(): Int = dsl.fetchCount(GATHERING_CUSTODIAN)
    fun gatheringsCount(): Int = dsl.fetchCount(GATHERING)
    fun invitations(): Int = dsl.fetchCount(TEACHER_INVITATION)
    fun accountExists(id: Long): Boolean = dsl.fetchExists(ACCOUNT, ACCOUNT.ID.eq(id))
    fun isTeacher(id: Long): Boolean = dsl.fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(id))

    /** The register of the deletions, oldest first: the rule and the number of rows. */
    fun log(): List<Pair<String, Int>> = dsl.select(RETENTION_LOG.RULE, RETENTION_LOG.ROW_COUNT).from(RETENTION_LOG).orderBy(RETENTION_LOG.ID).fetch { it[RETENTION_LOG.RULE]!! to it[RETENTION_LOG.ROW_COUNT]!! }
}
