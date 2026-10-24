package talita.registration

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationAttendance
import talita.jooq.enums.RegistrationDayKind
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REGISTRATION_DAY
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The schema of the registrations (V5): what the database refuses by itself, whatever the application does (criterion 38). The application
 * checks the same things first and with kinder words; this is the net under it. Names and dates are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RegistrationSchemaTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val support = GatheringSupport(dsl, clock)

    /** The name of the constraint that refused (or the text of the refusal when it has no name, like a text that is too long). */
    private fun refusedBy(block: () -> Unit): String {
        val failure = assertFailsWith<Exception> { block() }
        val sql = generateSequence<Throwable>(failure) { it.cause?.takeIf { cause -> cause !== it } }.filterIsInstance<SQLException>().first()
        return Regex("""constraint "([^"]+)"""").find(sql.message.orEmpty())?.groupValues?.get(1) ?: sql.message.orEmpty()
    }

    private fun account(name: String = "genitore"): Long {
        val email = Addresses.unique(name)
        dsl.insertInto(ACCOUNT).set(ACCOUNT.EMAIL, email).set(ACCOUNT.CREATED_ON, java.time.LocalDate.now()).execute()
        return dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!
    }

    private fun child(owner: Long? = null, first: String = "Luca", last: String = "Rossi", born: LocalDate = LocalDate.of(2016, 4, 4)): Long =
        dsl.insertInto(CHILD).set(CHILD.ACCOUNT_ID, owner).set(CHILD.FIRST_NAME, first).set(CHILD.LAST_NAME, last).set(CHILD.BIRTH_DATE, born)
            .returningResult(CHILD.ID).fetchSingle().value1()!!

    private fun gathering(): Long = support.make(status = GatheringStatus.`open`)

    /** A registration with the parts that the test changes; by default an online one, complete. */
    private fun registration(
        gathering: Long,
        child: Long,
        owner: Long? = null,
        phone: String = "333 123 4567",
        attendance: RegistrationAttendance = RegistrationAttendance.all_days,
        custody: Boolean = owner != null,
        partialCustody: Boolean = false,
        enteredBy: Long? = null,
        paperReceived: Boolean = false,
        religion: Boolean = false,
    ): Long = dsl.insertInto(REGISTRATION)
        .set(REGISTRATION.GATHERING_ID, gathering)
        .set(REGISTRATION.CHILD_ID, child)
        .set(REGISTRATION.ACCOUNT_ID, owner)
        .set(REGISTRATION.PHONE, phone)
        .set(REGISTRATION.ATTENDANCE, attendance)
        .set(REGISTRATION.STATUS, RegistrationStatus.confirmed)
        .set(REGISTRATION.CUSTODY_TEXT, if (custody || partialCustody) "Testo accettato" else null)
        .set(REGISTRATION.CUSTODY_TEXT_VERSION, if (custody) "affidamento-1" else null)
        .set(REGISTRATION.CUSTODY_ACCEPTED_AT, if (custody) OffsetDateTime.now(clock) else null)
        .set(REGISTRATION.CUSTODY_ACCEPTED_BY_EMAIL, if (custody) "nome@example.org" else null)
        .set(REGISTRATION.CUSTODY_RELIGION_CONSENT, religion)
        .set(REGISTRATION.PAPER_FORM_RECEIVED, paperReceived)
        .set(REGISTRATION.ENTERED_BY_ACCOUNT_ID, enteredBy)
        .set(REGISTRATION.CREATED_AT, OffsetDateTime.now(clock))
        .set(REGISTRATION.UPDATED_AT, OffsetDateTime.now(clock))
        .returningResult(REGISTRATION.ID).fetchSingle().value1()!!

    private fun healthNote(registration: Long, allergies: String? = "Arachidi", intolerances: String? = null, byAccount: Long? = null, onPaper: Boolean = false) =
        dsl.insertInto(HEALTH_NOTE)
            .set(HEALTH_NOTE.REGISTRATION_ID, registration)
            .set(HEALTH_NOTE.ALLERGIES, allergies)
            .set(HEALTH_NOTE.INTOLERANCES, intolerances)
            .set(HEALTH_NOTE.CONSENT_AT, OffsetDateTime.now(clock))
            .set(HEALTH_NOTE.CONSENT_BY_ACCOUNT_ID, byAccount)
            .set(HEALTH_NOTE.CONSENT_ON_PAPER, onPaper)
            .execute()

    // ---- The child ----------------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c38 the database refuses a child with an empty name or surname, or of more than 100 characters`() {
        assertEquals("child_first_name_not_empty", refusedBy { child(first = "") })
        assertEquals("child_first_name_not_empty", refusedBy { child(first = "   ") })
        assertEquals("child_last_name_not_empty", refusedBy { child(last = " ") })
        assertTrue(refusedBy { child(first = "a".repeat(101)) }.contains("too long"))
        assertTrue(refusedBy { child(last = "a".repeat(101)) }.contains("too long"))
        child(first = "a".repeat(100), last = "b".repeat(100))
    }

    // ---- The registration ---------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c38 the database refuses two registrations of the same child at the same gathering`() {
        support.reset()
        val gathering = gathering()
        val owner = account()
        val child = child(owner)
        registration(gathering, child, owner)

        assertEquals("registration_one_per_child", refusedBy { registration(gathering, child, owner) })
        // At another gathering, the year after, it is the same child.
        support.reset()
    }

    @Test
    fun `t5 c38 the database refuses an online registration without the custody, and a custody that is half there`() {
        support.reset()
        val gathering = gathering()
        val owner = account()

        assertEquals("registration_online_has_custody", refusedBy { registration(gathering, child(owner), owner, custody = false) })
        assertEquals("registration_custody_whole", refusedBy { registration(gathering, child(owner), owner, custody = false, partialCustody = true) })
        registration(gathering, child(owner), owner)
        support.reset()
    }

    @Test
    fun `t5 c38 the database refuses a paper registration that has the box of the beliefs, and an online one that was typed in by a teacher`() {
        support.reset()
        val gathering = gathering()
        val owner = account()
        val teacher = account("maestro")

        // What tells a paper registration from an online one is the acceptance (TALITA-7: the account of an online registration can be deleted, the acceptance stays).
        assertEquals("registration_online_not_typed_in", refusedBy { registration(gathering, child(), null, custody = true, enteredBy = teacher) })
        assertEquals("registration_online_not_typed_in", refusedBy { registration(gathering, child(owner), owner, enteredBy = teacher) })
        assertEquals("registration_paper_has_no_consent", refusedBy { registration(gathering, child(), null, custody = false, enteredBy = teacher, religion = true) })
        registration(gathering, child(), null, custody = false, enteredBy = teacher, paperReceived = true)
        // A paper registration whose teacher is gone (the account was deleted) has no teacher, and an online one whose parent is gone has no account and keeps its acceptance.
        registration(gathering, child(), null, custody = false)
        registration(gathering, child(), null, custody = true)
        support.reset()
    }

    @Test
    fun `t5 c38 the paper form that was received is only for a paper registration`() {
        support.reset()
        val gathering = gathering()
        val owner = account()

        assertEquals("registration_paper_form_is_paper", refusedBy { registration(gathering, child(owner), owner, paperReceived = true) })
        support.reset()
    }

    @Test
    fun `t5 c38 the database refuses a phone that is too short or too long`() {
        support.reset()
        val gathering = gathering()
        val owner = account()

        assertEquals("registration_phone_length", refusedBy { registration(gathering, child(owner), owner, phone = "12345") })
        assertTrue(refusedBy { registration(gathering, child(owner), owner, phone = "1".repeat(21)) }.contains("too long"))
        registration(gathering, child(owner), owner, phone = "+39 333 123 4567")
        support.reset()
    }

    // ---- The days -----------------------------------------------------------------------------------------------------------

    private fun day(registration: Long, date: LocalDate, kind: RegistrationDayKind = RegistrationDayKind.full) =
        dsl.insertInto(REGISTRATION_DAY).set(REGISTRATION_DAY.REGISTRATION_ID, registration).set(REGISTRATION_DAY.DAY, date).set(REGISTRATION_DAY.KIND, kind).execute()

    @Test
    fun `t5 c38 a day is only for a registration of some days, and the registration cannot become every day while it has days`() {
        support.reset()
        val gathering = gathering()
        val owner = account()
        val everyDay = registration(gathering, child(owner), owner)
        val someDays = registration(gathering, child(owner, "Anna"), owner, attendance = RegistrationAttendance.days)
        val saturday = LocalDate.of(2027, 9, 18)

        assertEquals("registration_day_registration", refusedBy { day(everyDay, saturday) })
        day(someDays, saturday)
        day(someDays, saturday.plusDays(1), RegistrationDayKind.lunch)
        assertEquals("registration_day_pkey", refusedBy { day(someDays, saturday) })
        assertEquals(
            "registration_day_registration",
            refusedBy { dsl.update(REGISTRATION).set(REGISTRATION.ATTENDANCE, RegistrationAttendance.all_days).where(REGISTRATION.ID.eq(someDays)).execute() },
        )
        dsl.deleteFrom(REGISTRATION_DAY).where(REGISTRATION_DAY.REGISTRATION_ID.eq(someDays)).execute()
        dsl.update(REGISTRATION).set(REGISTRATION.ATTENDANCE, RegistrationAttendance.all_days).where(REGISTRATION.ID.eq(someDays)).execute()
        support.reset()
    }

    // ---- The notes on health ------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c38 the database refuses a note with nothing written, one of more than 1000 characters, and a consent that is not from exactly one source`() {
        support.reset()
        val gathering = gathering()
        val owner = account()
        val registration = registration(gathering, child(owner), owner)

        assertEquals("health_note_not_empty", refusedBy { healthNote(registration, allergies = null, byAccount = owner) })
        assertEquals("health_note_allergies_length", refusedBy { healthNote(registration, allergies = "", byAccount = owner) })
        assertEquals("health_note_allergies_length", refusedBy { healthNote(registration, allergies = "a".repeat(1001), byAccount = owner) })
        assertEquals("health_note_intolerances_length", refusedBy { healthNote(registration, allergies = null, intolerances = "b".repeat(1001), byAccount = owner) })
        assertEquals("health_note_consent_by_one", refusedBy { healthNote(registration, byAccount = owner, onPaper = true) })
        assertEquals("health_note_consent_by_one", refusedBy { healthNote(registration) })
        healthNote(registration, allergies = "a".repeat(1000), byAccount = owner)
        assertEquals("health_note_pkey", refusedBy { healthNote(registration, byAccount = owner) })
        support.reset()
    }

    @Test
    fun `t5 c38 a paper registration has its notes with the consent on paper`() {
        support.reset()
        val gathering = gathering()
        val teacher = account("maestro")
        val registration = registration(gathering, child(), null, custody = false, enteredBy = teacher)

        healthNote(registration, onPaper = true)
        assertEquals(1, dsl.fetchCount(HEALTH_NOTE))
        support.reset()
    }

    @Test
    fun `t5 c38 deleting a registration takes with it its days, its note and the readings of the note`() {
        support.reset()
        val gathering = gathering()
        val owner = account()
        val teacher = account("maestro")
        val registration = registration(gathering, child(owner), owner, attendance = RegistrationAttendance.days)
        day(registration, LocalDate.of(2027, 9, 18))
        healthNote(registration, byAccount = owner)
        dsl.insertInto(HEALTH_NOTE_ACCESS).set(HEALTH_NOTE_ACCESS.REGISTRATION_ID, registration).set(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID, teacher)
            .set(HEALTH_NOTE_ACCESS.ACCESSED_AT, OffsetDateTime.now(clock)).execute()

        dsl.deleteFrom(REGISTRATION).where(REGISTRATION.ID.eq(registration)).execute()

        assertEquals(0, dsl.fetchCount(REGISTRATION_DAY))
        assertEquals(0, dsl.fetchCount(HEALTH_NOTE))
        assertEquals(0, dsl.fetchCount(HEALTH_NOTE_ACCESS))
        support.reset()
    }

    // ---- What does not cascade ----------------------------------------------------------------------------------------------

    @Test
    fun `t5 c38 a child, an account and a gathering with registrations are not deleted by mistake`() {
        support.reset()
        val gathering = gathering()
        val owner = account()
        val child = child(owner)
        registration(gathering, child, owner)

        assertTrue(refusedBy { dsl.deleteFrom(CHILD).where(CHILD.ID.eq(child)).execute() }.contains("registration"))
        assertTrue(refusedBy { dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(owner)).execute() }.isNotEmpty())
        assertTrue(refusedBy { dsl.deleteFrom(GATHERING).where(GATHERING.ID.eq(gathering)).execute() }.contains("registration"))
        support.reset()
    }

    @Test
    fun `t5 c38 the reading of a note needs a teacher account that exists`() {
        support.reset()
        val gathering = gathering()
        val owner = account()
        val registration = registration(gathering, child(owner), owner)

        assertTrue(
            refusedBy {
                dsl.insertInto(HEALTH_NOTE_ACCESS).set(HEALTH_NOTE_ACCESS.REGISTRATION_ID, registration).set(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID, -1L)
                    .set(HEALTH_NOTE_ACCESS.ACCESSED_AT, OffsetDateTime.now(clock)).execute()
            }.contains("health_note_access"),
        )
        support.reset()
    }
}
