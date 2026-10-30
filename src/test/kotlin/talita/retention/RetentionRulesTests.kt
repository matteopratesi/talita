package talita.retention

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.Accounts
import talita.account.EmailAddress
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.PAYMENT
import talita.jooq.tables.references.REGISTRATION
import talita.registration.Notes
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules by time (criteria 2-10): what the job deletes and when, on a database that is real, with a fixed clock at a moment of Rome. The example is the
 * «Convivenza di settembre 2027» (17-19 September, finished on the 20th): the notes go on 20 October 2027, the rest on 20 September 2028. A is a parent, B another,
 * Luca and Anna Rossi are children of A, Nicolò Ferri comes from the paper form; M and N are teachers. Names, dates and addresses are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RetentionRulesTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val job: RetentionJob,
) {

    private val kit = RetentionTestKit(dsl, clock)

    private val notes = Notes("Arachidi", null, "Antistaminico")

    @BeforeEach
    fun clean() {
        kit.reset()
        // The accounts that the other tests left would be empty and old for a clock of 2027: the job would delete them and the counts would not be ours.
        kit.removeAccountsWithoutRole()
    }

    /** What the job deleted but the accounts: the parents of these tests have no registration left when the registrations go, so they are empty accounts, and go too. */
    private fun RetentionReport.withoutAccounts(): List<Deleted> = deleted.filter { it.rule != "accounts" }

    // ---- The day of Rome (criteria 2, 3) ---------------------------------------------------------------------------------------

    @Test
    fun `t7 c02 the job looks at the day of Rome and not at the hour, at 23_59 of the 19th the notes are there, at 00_30 of the 20th they are not`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        kit.registration(gathering, a, notes = notes)

        assertTrue(job.run(kit.clockAt("2027-10-19T23:59")).succeeded)
        assertEquals(1, kit.notes(), "the day before the date the notes are there, at any hour")
        assertEquals(emptyList<Pair<String, Int>>(), kit.log())

        assertTrue(job.run(kit.clockAt("2027-10-20T00:30")).succeeded)
        assertEquals(0, kit.notes(), "from the day of the date they are gone, also at half past midnight (22:30 of the 19th in UTC)")
        assertEquals(1, kit.log().count { it == "notes" to 1 }, "one row of the register for the rule that deleted")
        assertEquals(1, kit.registrations(), "only the notes go on that day")
    }

    @Test
    fun `t7 c03 one month after the end every note of the gathering goes, the withdrawn and the paper ones too, and nothing else`() {
        val gathering = kit.september2027()
        val next = kit.september2028(GatheringStatus.open)
        val a = kit.account("genitore-a")
        val b = kit.account("genitore-b")
        val m = kit.teacher("maestro-m")
        val luca = kit.registration(gathering, a, "Luca", "Rossi", notes = notes)
        val withdrawn = kit.registration(gathering, a, "Anna", "Rossi", RegistrationStatus.withdrawn, notes = Notes("Latte", null, null))
        val paper = kit.paper(gathering, m, "Nicolò", "Ferri", Notes(null, "Glutine", null))
        val other = kit.registration(next, b, "Sara", "Verdi", notes = notes)
        kit.reading(luca, m)
        kit.presence(luca, LocalDate.of(2027, 9, 17), m)
        kit.payment(luca, 6000, m)

        val report = job.run(kit.clockAt("2027-10-20T04:15"))

        assertEquals(listOf(Deleted("notes", 3)), report.withoutAccounts())
        assertEquals(1, kit.notes(), "the notes of the gathering that is not finished stay")
        assertEquals(4, kit.registrations(), "the registrations stay a year")
        assertEquals(4, kit.children())
        assertEquals(1, kit.readings(), "the register of the readings stays a year")
        assertEquals(1, kit.presences())
        assertEquals(1, kit.payments())
        assertTrue(other > 0 && paper > 0 && withdrawn > 0)
    }

    // ---- The registrations, a year after (criterion 4) ---------------------------------------------------------------------------

    @Test
    fun `t7 c04 one year after the end the registrations go with their days, notes, readings, presences, payments and custodians, the gathering stays`() {
        val m = kit.teacher("maestro-m")
        val gathering = kit.september2027(custodians = listOf(m))
        val a = kit.account("genitore-a")
        val days = kit.registration(gathering, a, "Luca", "Rossi", notes = notes)
        kit.rows.register(gathering, a, "Anna", "Rossi", LocalDate.of(2019, 1, 9), days = mapOf(LocalDate.of(2027, 9, 18) to "full", LocalDate.of(2027, 9, 19) to "lunch"))
        kit.paper(gathering, m, "Nicolò", "Ferri")
        kit.reading(days, m)
        kit.presence(days, LocalDate.of(2027, 9, 18), m)
        kit.payment(days, 2500, m)
        assertEquals(2, kit.days())

        job.run(kit.clockAt("2028-09-19T23:59"))
        assertEquals(3, kit.registrations(), "the day before the date the registrations are there")
        assertEquals(0, kit.notes(), "the notes went a year before, with their own date")

        val report = job.run(kit.clockAt("2028-09-20T04:15"))

        assertEquals(0, kit.registrations())
        assertEquals(0, kit.days())
        assertEquals(0, kit.notes())
        assertEquals(0, kit.readings())
        assertEquals(0, kit.presences())
        assertEquals(0, kit.payments())
        assertEquals(0, kit.custodians(), "the links with the custodians go too")
        assertEquals(1, kit.gatheringsCount(), "the gathering holds no personal data and stays")
        assertTrue(kit.accountExists(m) && kit.isTeacher(m), "the teacher is the same person, with the same role")
        assertEquals(listOf(Deleted("registrations", 4), Deleted("children", 3)), report.withoutAccounts(), "three registrations and one custodian, then the children that had only those")
    }

    // ---- The children (criterion 5) ---------------------------------------------------------------------------------------------

    @Test
    fun `t7 c05 a child with no registration left goes, Nicolò with his, Luca with his last one, Anna, registered in 2028 too, stays with that one`() {
        val m = kit.teacher("maestro-m")
        val a = kit.account("genitore-a")
        val y2027 = kit.september2027()
        val y2028 = kit.september2028(GatheringStatus.open)
        val luca = kit.registration(y2027, a, "Luca", "Rossi")
        kit.paper(y2027, m, "Nicolò", "Ferri")
        val annaOld = kit.registration(y2027, a, "Anna", "Rossi", birth = LocalDate.of(2019, 1, 9))
        // Anna is registered again the year after: the same child, a second registration.
        val anna = kit.childOf(annaOld)
        kit.registerAgain(y2028, annaOld)
        assertEquals(4, kit.registrations())
        assertEquals(3, kit.children())

        val report = job.run(kit.clockAt("2028-09-20T04:15"))

        assertEquals(1, kit.registrations(), "the three of 2027 are gone, the one of 2028 stays")
        assertEquals(1, kit.children(), "only Anna is left: Luca and Nicolò had no registration any more")
        assertEquals(anna, dsl.select(REGISTRATION.CHILD_ID).from(REGISTRATION).fetchSingle(REGISTRATION.CHILD_ID))
        assertEquals(listOf(Deleted("registrations", 3), Deleted("children", 2)), report.withoutAccounts())
        assertTrue(luca > 0)
    }

    // ---- The invitations (criterion 6) -------------------------------------------------------------------------------------------

    @Test
    fun `t7 c06 an invitation that expired 30 days ago goes, one that expired 29 days ago stays`() {
        val at = kit.clockAt("2027-11-15T04:15")
        val now = kit.moment("2027-11-15T04:15")
        kit.invitation("invito-30", now.minusDays(30))
        val recent = kit.invitation("invito-29", now.minusDays(29))
        val waiting = kit.invitation("invito-valido", now.plusDays(3))

        val report = job.run(at)

        assertEquals(listOf(Deleted("invitations", 1)), report.withoutAccounts())
        assertEquals(2, kit.invitations())
        assertTrue(dsl.fetchExists(talita.jooq.tables.references.TEACHER_INVITATION, talita.jooq.tables.references.TEACHER_INVITATION.EMAIL.eq(recent)), "expired 29 days ago: still «Scaduto» in the page of the teachers")
        assertTrue(dsl.fetchExists(talita.jooq.tables.references.TEACHER_INVITATION, talita.jooq.tables.references.TEACHER_INVITATION.EMAIL.eq(waiting)))
    }

    // ---- The accounts (criteria 7, 8) --------------------------------------------------------------------------------------------

    @Test
    fun `t7 c07 an empty account goes 30 days after it was born, not before, a teacher and a parent with a registration stay`() {
        val gathering = kit.september2027()
        val old = kit.account("vuoto-vecchio", LocalDate.of(2027, 9, 15))
        val thirty = kit.account("vuoto-trenta", LocalDate.of(2027, 9, 16))
        val twentyNine = kit.account("vuoto-ventinove", LocalDate.of(2027, 9, 17))
        val teacher = kit.teacher("maestro-m", LocalDate.of(2027, 1, 1))
        val parent = kit.account("genitore-a", LocalDate.of(2027, 1, 1))
        kit.registration(gathering, parent)

        val report = job.run(kit.clockAt("2027-10-16T04:15"))

        assertFalse(kit.accountExists(old), "born 31 days ago")
        assertFalse(kit.accountExists(thirty), "born 30 days ago: 30 days after the creation")
        assertTrue(kit.accountExists(twentyNine), "born 29 days ago")
        assertTrue(kit.accountExists(teacher) && kit.accountExists(parent))
        assertEquals(Deleted("accounts", 2), report.deleted.single { it.rule == "accounts" })
    }

    @Test
    fun `t7 c07 what a teacher had marked or typed in stays when the account goes, and says that the teacher is not among the teachers any more`() {
        val gathering = kit.september2027()
        val former = kit.account("ex-maestro", LocalDate.of(2027, 1, 1))
        val luca = kit.registration(gathering, kit.account("genitore-a"))
        val paper = kit.paper(gathering, former, "Nicolò", "Ferri")
        kit.presence(luca, LocalDate.of(2027, 9, 18), former)
        kit.payment(luca, 6000, former)

        job.run(kit.clockAt("2027-10-16T04:15"))

        assertFalse(kit.accountExists(former))
        assertEquals(1, kit.presences())
        assertNull(dsl.select(CHECKIN.RECORDED_BY_ACCOUNT_ID).from(CHECKIN).fetchSingle(CHECKIN.RECORDED_BY_ACCOUNT_ID))
        assertEquals(1, kit.payments())
        assertNull(dsl.select(PAYMENT.RECORDED_BY_ACCOUNT_ID).from(PAYMENT).fetchSingle(PAYMENT.RECORDED_BY_ACCOUNT_ID))
        assertNull(dsl.select(REGISTRATION.ENTERED_BY_ACCOUNT_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(paper)).fetchSingle(REGISTRATION.ENTERED_BY_ACCOUNT_ID))
        assertEquals(2, kit.registrations(), "the paper registration stays, now with no teacher")
    }

    @Test
    fun `t7 c08 an account that is in the register of the readings stays while the readings do, and then it goes like the others`() {
        val gathering = kit.september2027()
        val formerTeacher = kit.account("ex-maestro-lettore", LocalDate.of(2027, 1, 1))
        val registration = kit.registration(gathering, kit.account("genitore-a"), notes = notes)
        kit.reading(registration, formerTeacher)

        job.run(kit.clockAt("2027-10-20T04:15"))
        assertTrue(kit.accountExists(formerTeacher), "the register must say who read: the address stays")
        assertEquals(1, kit.readings())

        job.run(kit.clockAt("2028-09-20T04:15"))
        assertEquals(0, kit.readings(), "the register goes a year after the end, with the registrations")
        assertFalse(kit.accountExists(formerTeacher), "with no readings left the account is empty and old, so it goes")
    }

    @Test
    fun `t7 c08 an account that gave the consent to some notes is not deleted while the notes are there`() {
        val gathering = kit.september2027()
        val parent = kit.account("genitore-a", LocalDate.of(2027, 1, 1))
        kit.registration(gathering, parent, notes = notes)

        job.run(kit.clockAt("2027-10-16T04:15"))

        assertTrue(kit.accountExists(parent), "it has a registration and notes whose consent points at it")
    }

    // ---- A new account (criterion 9) ---------------------------------------------------------------------------------------------

    @Test
    fun `t7 c09 an account is born with the day of Rome, and nothing about its use is kept`() {
        // 00:30 of the 20th in Rome is still the 19th in UTC: the day is the one of Rome.
        val accounts = Accounts(dsl, kit.clockAt("2027-10-20T00:30"))
        val email = Addresses.unique("nuovo")

        val id = accounts.findOrCreate(EmailAddress.parse(email)!!)

        assertEquals(LocalDate.of(2027, 10, 20), dsl.select(ACCOUNT.CREATED_ON).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.CREATED_ON))
        // TALITA-13 adds what the person chose about the reminders (the moment of the consent to the avviso, the moment of the stop, the code to stop): nothing about the use of the account.
        assertEquals(
            setOf("id", "email", "created_on", "notice_consent_at", "reminders_off_at", "reminder_code"), dsl.meta().getTables("account").single().fields().map { it.name }.toSet(),
            "no last access, hour, address or browser",
        )
        // The account that is there already is not touched when the person enters again.
        Accounts(dsl, kit.clockAt("2028-01-01T10:00")).findOrCreate(EmailAddress.parse(email)!!)
        assertEquals(LocalDate.of(2027, 10, 20), dsl.select(ACCOUNT.CREATED_ON).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.CREATED_ON))
    }

    // ---- The states of a gathering (criterion 10) --------------------------------------------------------------------------------

    @Test
    fun `t7 c10 a draft is touched by no rule even if its last day is long past, a finished gathering with the registrations still open follows its end like the others`() {
        val m = kit.teacher("maestro-m")
        val draft = kit.gatherings.make(title = "Bozza di prova", start = LocalDate.of(2020, 9, 11), end = LocalDate.of(2020, 9, 13), status = GatheringStatus.draft, custodians = listOf(m))

        job.run(kit.clockAt("2030-01-01T04:15"))

        assertEquals(1, kit.gatheringsCount())
        assertEquals(1, kit.custodians(), "a draft has no registrations, and its custodians are not touched either")
        assertTrue(draft > 0)

        kit.reset()
        val open = kit.september2027(custodians = listOf(m), status = GatheringStatus.open)
        kit.registration(open, kit.account("genitore-a"), notes = notes)

        job.run(kit.clockAt("2027-10-20T04:15"))
        assertEquals(0, kit.notes(), "the registrations are still open, but the gathering is finished: the notes follow the end")
        assertEquals(1, kit.registrations())
        job.run(kit.clockAt("2028-09-20T04:15"))
        assertEquals(0, kit.registrations())
        assertEquals(1, kit.gatheringsCount())
    }
}
