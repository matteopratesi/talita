package talita.retention

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import talita.TestcontainersConfiguration
import talita.account.Accounts
import talita.account.CurrentAccount
import talita.account.EmailAddress
import talita.account.Passkeys
import talita.account.RememberedDevices
import talita.account.TeacherName
import talita.account.TestAccounts
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.RETENTION_LOG
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.registration.HealthNotes
import talita.registration.Notes
import talita.teacher.Invitations
import talita.teacher.TeacherSettings
import talita.teacher.Teachers
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deletions on request (criteria 17, 19, 22, 24, 25, 27, 28): what stays and what goes when a parent deletes the data of a child or an account, what blocks, that the check
 * is made inside the transaction, what the register holds, and that a request is made again after a restore. The database is real; the clock is fixed on 5 October 2027,
 * with the «Convivenza di settembre 2027» finished on the 20th of September (the registrations go on 20 September 2028). A and B are parents, Luca and Anna Rossi are
 * children of A, M and N are teachers. Names, dates and addresses are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
class ErasureTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val policy: RetentionPolicy,
    @Autowired private val job: RetentionJob,
) {

    private val kit = RetentionTestKit(dsl, clock)

    private val notes = Notes("Arachidi", null, "Antistaminico")

    @BeforeEach
    fun clean() {
        kit.reset()
        kit.removeAllTeachers()
        kit.removeAccountsWithoutRole()
    }

    /** The service as it is on the day of [at], with the clock fixed (the plan and the checks look at the day). */
    private fun service(at: String = "2027-10-05T10:00"): ErasureService {
        val fixed = kit.clockAt(at)
        return ErasureService(dsl, fixed, policy, HealthNotes(dsl, fixed, policy), Teachers(dsl, fixed), RememberedDevices(dsl, fixed, policy), Passkeys(dsl, fixed), Accounts(dsl, fixed))
    }

    // ---- The plan (criteria 18, 19) -------------------------------------------------------------------------------------------------

    @Test
    fun `t7 c19 the plan of a child with a registration that stays says until when and why, and that the notes go`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        val luca = kit.registration(gathering, a, "Luca", "Rossi", notes = notes)
        val anna = kit.registration(gathering, a, "Anna", "Rossi", RegistrationStatus.withdrawn, birth = LocalDate.of(2019, 1, 9))
        val service = service()

        val lucaPlan = assertNotNull(service.planChild(a, kit.childOf(luca)))
        assertFalse(lucaPlan.blocked)
        assertFalse(lucaPlan.childGoes)
        assertTrue(lucaPlan.hasNotes)
        assertEquals(LocalDate.of(2028, 9, 20), lucaPlan.kept.single().keptUntil)
        assertEquals(KeepReason.PROOF, lucaPlan.kept.single().reason)
        assertEquals(emptyList(), lucaPlan.going)

        val annaPlan = assertNotNull(service.planChild(a, kit.childOf(anna)))
        assertTrue(annaPlan.childGoes, "a withdrawn registration with no presence and no payment goes, and so does the child")
        assertEquals(1, annaPlan.going.size)
        assertFalse(annaPlan.hasNotes)

        assertNull(service.planChild(kit.account("genitore-b"), kit.childOf(luca)), "the child of another is not there")
        assertEquals(listOf("Rossi Anna", "Rossi Luca"), service.childrenOf(a).map { "${it.lastName} ${it.firstName}" })
    }

    // ---- The deletion of the data of a child (criteria 17, 19) -----------------------------------------------------------------------

    @Test
    fun `t7 c19 the data of a child go in one transaction, the notes of every registration, the registrations that can go, and the child is detached if one stays`() {
        val y2027 = kit.september2027()
        val june = kit.gatherings.make(title = "Convivenza di giugno 2027", start = LocalDate.of(2027, 6, 18), end = LocalDate.of(2027, 6, 20), status = GatheringStatus.closed, code = talita.gathering.GatheringSupport.nextCode())
        val a = kit.account("genitore-a")
        val m = kit.teacher("maestro-m")
        val lucaJune = kit.registration(june, a, "Luca", "Rossi", notes = notes)
        val luca = kit.registerAgain(y2027, lucaJune)
        dsl.execute("insert into health_note (registration_id, allergies, consent_at, consent_by_account_id) values (?, 'Latte', now(), ?)", luca, a)
        kit.presence(luca, LocalDate.of(2027, 9, 18), m)
        kit.payment(lucaJune, 2500, m)
        assertEquals(2, kit.notes())

        val result = service().eraseChild(a, kit.childOf(luca))

        val done = assertIs<ChildErasure.Done>(result)
        assertEquals(listOf("Luca", "Rossi"), listOf(done.firstName, done.lastName))
        assertFalse(done.childWent)
        assertEquals(setOf(LocalDate.of(2028, 6, 21), LocalDate.of(2028, 9, 20)), done.kept.map { it.keptUntil }.toSet(), "each registration stays until its own date")
        assertEquals(0, kit.notes(), "the notes of all the registrations of the child go")
        assertEquals(2, kit.registrations(), "the registrations that were not withdrawn stay")
        assertEquals(1, kit.presences(), "the presences stay with the registration")
        assertEquals(1, kit.payments())
        assertNull(dsl.select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.ID.eq(kit.childOf(luca))).fetchSingle(CHILD.ACCOUNT_ID), "the child is detached: not among «I tuoi figli» any more")
        assertEquals(a, dsl.select(REGISTRATION.ACCOUNT_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(luca)).fetchSingle(REGISTRATION.ACCOUNT_ID), "the registration is still in the name of the parent")
        assertEquals(emptyList(), service().childrenOf(a))
    }

    @Test
    fun `t7 c19 a child whose registrations all go is deleted with them, and a withdrawn one with presences stays as the register`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        val m = kit.teacher("maestro-m")
        val anna = kit.registration(gathering, a, "Anna", "Rossi", RegistrationStatus.withdrawn, birth = LocalDate.of(2019, 1, 9))
        val sara = kit.registration(gathering, a, "Sara", "Verdi", RegistrationStatus.withdrawn, birth = LocalDate.of(2018, 2, 3))
        kit.presence(sara, LocalDate.of(2027, 9, 18), m)

        val annaDone = assertIs<ChildErasure.Done>(service().eraseChild(a, kit.childOf(anna)))
        assertTrue(annaDone.childWent)
        assertEquals(emptyList(), annaDone.kept)
        assertEquals(1, kit.registrations(), "Anna is gone, with her registration")
        assertEquals(1, kit.children())

        val plan = assertNotNull(service().planChild(a, kit.childOf(sara)))
        assertEquals(KeepReason.REGISTER, plan.kept.single().reason, "withdrawn, with a presence: it stays as the register of the gathering")
        val saraDone = assertIs<ChildErasure.Done>(service().eraseChild(a, kit.childOf(sara)))
        assertFalse(saraDone.childWent)
        assertEquals(1, kit.registrations())
        assertEquals(1, kit.presences())
    }

    // ---- What blocks (criterion 17) -----------------------------------------------------------------------------------------------

    @Test
    fun `t7 c17 a registration that is not withdrawn in a gathering that is not finished blocks, with the registrations open or closed, and nothing is changed`() {
        val open = kit.september2027(status = GatheringStatus.open)
        val a = kit.account("genitore-a")
        val luca = kit.registration(open, a, "Luca", "Rossi", notes = notes)

        // During the gathering (18 September): not finished.
        val during = service("2027-09-18T10:00")
        val plan = assertNotNull(during.planChild(a, kit.childOf(luca)))
        assertTrue(plan.blocked)
        assertTrue(plan.blockers.single().parentCanWithdraw, "with the registrations open the parent withdraws it first")
        val blocked = assertIs<ChildErasure.Blocked>(during.eraseChild(a, kit.childOf(luca)))
        assertEquals(luca, blocked.blockers.single().registrationId)
        assertEquals(1, kit.notes())
        assertEquals(1, kit.registrations())
        assertEquals(a, dsl.select(CHILD.ACCOUNT_ID).from(CHILD).fetchSingle(CHILD.ACCOUNT_ID))
        assertEquals(emptyList(), kit.log(), "a request that was refused leaves nothing in the register")

        kit.setState(open, GatheringStatus.closed)
        assertFalse(assertNotNull(during.planChild(a, kit.childOf(luca))).blockers.single().parentCanWithdraw, "closed: the teachers have to do it")

        // The day after the end it is finished: no more block.
        assertIs<ChildErasure.Done>(service("2027-09-20T08:00").eraseChild(a, kit.childOf(luca)))
        assertEquals(0, kit.notes())
    }

    @Test
    fun `t7 c17 the check is made inside the transaction, a withdrawal that is not committed yet is waited for, and then the request goes through`() {
        val gathering = kit.september2027(status = GatheringStatus.closed)
        val a = kit.account("genitore-a")
        val luca = kit.registration(gathering, a, "Luca", "Rossi")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            // A teacher is withdrawing the registration: the row is locked, as `Registrations.withdraw` locks it.
            val withdrawal = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    tx.select(REGISTRATION.ID).from(REGISTRATION).where(REGISTRATION.ID.eq(luca)).forUpdate().fetch()
                    locked.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    tx.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(luca)).execute()
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            // Mid-gathering the registration would block; the request has to wait for the lock and then look again.
            val request = pool.submit<ChildErasure> { service("2027-09-18T10:00").eraseChild(a, kit.childOf(luca)) }
            Thread.sleep(700)
            assertFalse(request.isDone, "the request waits for the row that the withdrawal holds")
            release.countDown()
            withdrawal.get(10, TimeUnit.SECONDS)
            val done = assertIs<ChildErasure.Done>(request.get(30, TimeUnit.SECONDS), "it found the withdrawal that was committed, so nothing blocks any more")
            assertTrue(done.childWent)
            assertEquals(0, kit.registrations())
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- A second request, a child of another (criteria 21, 22, 27) -----------------------------------------------------------------

    @Test
    fun `t7 c22 a second request for the same child is not an error, the register has one row, and an id that never existed is not found`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        val b = kit.account("genitore-b")
        val anna = kit.registration(gathering, a, "Anna", "Rossi", RegistrationStatus.withdrawn)
        val child = kit.childOf(anna)
        val service = service()

        assertIs<ChildErasure.NotFound>(service.eraseChild(b, child), "a child of another account is an id that does not exist")
        assertEquals(1, kit.children())
        assertIs<ChildErasure.Done>(service.eraseChild(a, child))
        assertIs<ChildErasure.AlreadyDone>(service.eraseChild(a, child), "the register remembers the number")
        assertIs<ChildErasure.NotFound>(service.eraseChild(a, child + 100_000), "a number that was never there")
        assertEquals(1, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("child-request")))
    }

    @Test
    fun `t7 c27 a request writes one row with the rule, the rows and the number of the child, and the job takes the number out after 37 days`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        val luca = kit.registration(gathering, a, "Luca", "Rossi", notes = notes)
        val child = kit.childOf(luca)

        assertIs<ChildErasure.Done>(service("2027-10-05T10:00").eraseChild(a, child))

        val row = dsl.selectFrom(RETENTION_LOG).fetchSingle()
        assertEquals("child-request", row.rule)
        assertEquals(1, row.rowCount, "the notes of Luca: his registration stays and the child is only detached")
        assertEquals(child, row.targetId)
        // Day 36 after the request: the number is still there. Day 37: the job takes it out, and the row stays.
        job.run(kit.clockAt("2027-11-10T04:15"))
        assertEquals(child, dsl.select(RETENTION_LOG.TARGET_ID).from(RETENTION_LOG).where(RETENTION_LOG.RULE.eq("child-request")).fetchSingle(RETENTION_LOG.TARGET_ID))
        job.run(kit.clockAt("2027-11-11T04:15"))
        assertNull(dsl.select(RETENTION_LOG.TARGET_ID).from(RETENTION_LOG).where(RETENTION_LOG.RULE.eq("child-request")).fetchSingle(RETENTION_LOG.TARGET_ID))
        assertEquals(1, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("child-request")), "the row of the register stays, without the number")
    }

    // ---- The deletion of an account (criteria 24, 25) --------------------------------------------------------------------------

    @Test
    fun `t7 c24 an account that has a child with a registration that is not withdrawn, the last teacher or a custodian of a gathering that is not finished cannot go, and nothing changes`() {
        val gathering = kit.september2027(status = GatheringStatus.open)
        val a = kit.account("genitore-a")
        kit.registration(gathering, a, "Luca", "Rossi")
        val during = service("2027-09-18T10:00")

        val byChild = assertIs<AccountErasure.Blocked>(during.eraseAccount(a))
        assertEquals(1, byChild.children.size)
        assertFalse(byChild.teacher.any)
        assertTrue(kit.accountExists(a))

        // The only teacher: nobody replaces them.
        val m = kit.teacher("maestro-m")
        val last = assertIs<AccountErasure.Blocked>(during.eraseAccount(m))
        assertTrue(last.teacher.lastTeacher)
        assertTrue(kit.isTeacher(m))

        // Two teachers, but N is a custodian of a gathering that is not finished.
        val n = kit.teacher("maestro-n", first = "Paolo", last = "Verdi")
        dsl.execute("insert into gathering_custodian (gathering_id, teacher_account_id) values (?, ?)", gathering, n)
        val custodian = assertIs<AccountErasure.Blocked>(during.eraseAccount(n))
        assertEquals(listOf(gathering), custodian.teacher.custodies.map { it.id })
        assertTrue(kit.isTeacher(n) && kit.accountExists(n))
        assertEquals(emptyList(), kit.log())
        assertEquals(1, during.planAccount(a)!!.blockers.size)
        assertTrue(during.planAccount(m)!!.blocked.not(), "with N there, M is not the last: M can go")
    }

    @Test
    fun `t7 c25 the account goes in one transaction, each child as for a child, what stays loses the link with the account, the teacher is taken away as with Togli, and the invitations to the address go`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        val m = kit.teacher("maestro-m")
        val n = kit.teacher("maestro-n", first = "Paolo", last = "Verdi")
        val luca = kit.registration(gathering, a, "Luca", "Rossi", notes = notes)
        val anna = kit.registration(gathering, a, "Anna", "Rossi", RegistrationStatus.withdrawn, birth = LocalDate.of(2019, 1, 9))
        // N has an old invitation to the same address (and another address has one too), and has marked a presence, registered a payment and typed in a paper form.
        kit.invitation("altro", kit.moment("2027-10-12T10:00"))
        kit.invitationTo(kit.emailOf(n), kit.moment("2027-10-05T10:00"), kit.moment("2027-10-12T10:00"))
        kit.presence(luca, LocalDate.of(2027, 9, 18), n)
        kit.payment(luca, 2500, n)
        val paper = kit.paper(gathering, n)

        val result = service().eraseAccount(n)

        val done = assertIs<AccountErasure.Done>(result)
        assertFalse(done.accountStays)
        assertFalse(kit.accountExists(n))
        assertFalse(kit.isTeacher(n), "the teacher row went, as with «Togli»")
        assertEquals(1, kit.invitations(), "the invitation to the address of N went, the other stays")
        assertEquals(1, kit.presences())
        assertEquals(1, kit.payments())
        assertEquals(3, kit.registrations(), "what N marked or typed in stays, and the registrations of A are not touched")
        assertNull(dsl.select(REGISTRATION.ENTERED_BY_ACCOUNT_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(paper)).fetchSingle(REGISTRATION.ENTERED_BY_ACCOUNT_ID))
        assertTrue(anna > 0)

        // The parent A now: Luca stays until his date, Anna goes with her registration, the notes go, the registration loses the account but keeps the acceptance.
        val doneA = assertIs<AccountErasure.Done>(service().eraseAccount(a))
        assertFalse(doneA.accountStays)
        assertFalse(kit.accountExists(a))
        assertEquals(0, kit.notes())
        val kept = dsl.selectFrom(REGISTRATION).where(REGISTRATION.ID.eq(luca)).fetchSingle()
        assertNull(kept.accountId, "the link with the account is gone")
        assertNotNull(kept.custodyText)
        assertEquals("nome@example.org", kept.custodyAcceptedByEmail, "the address copied into the acceptance stays")
        assertNull(dsl.select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.ID.eq(kept.childId)).fetchSingle(CHILD.ACCOUNT_ID))
        assertEquals(0, dsl.fetchCount(REGISTRATION, REGISTRATION.ID.eq(anna)), "the registration of Anna went")
        assertTrue(kit.isTeacher(m))
        val log = dsl.select(RETENTION_LOG.RULE, RETENTION_LOG.TARGET_ID).from(RETENTION_LOG).orderBy(RETENTION_LOG.ID).fetch { it[RETENTION_LOG.RULE] to it[RETENTION_LOG.TARGET_ID] }
        assertEquals(listOf("account-request" to n, "account-request" to a), log)
    }

    @Test
    fun `t7 c25 an account that is in the register of the readings stays, with the address alone, and the rest of what it was goes`() {
        val gathering = kit.september2027()
        val m = kit.teacher("maestro-m")
        val reader = kit.teacher("maestro-lettore", first = "Paolo", last = "Verdi")
        val registration = kit.registration(gathering, kit.account("genitore-a"), "Luca", "Rossi", notes = notes)
        kit.reading(registration, reader)

        val done = assertIs<AccountErasure.Done>(service().eraseAccount(reader))

        assertTrue(done.accountStays)
        assertTrue(kit.accountExists(reader), "the register must say who read")
        assertFalse(kit.isTeacher(reader), "the name and the role went")
        assertEquals(1, kit.readings())
        val plan = service().planAccount(reader)
        assertNotNull(plan)
        assertEquals(LocalDate.of(2028, 9, 20), plan.readingsUntil, "the address stays until the register of that gathering goes")
        // Asked again, after a restore, there is nothing to change: no row in the register.
        assertEquals(1, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("account-request")))
        assertTrue(m > 0)
    }

    @Test
    fun `t7 c24 the plan of the account has the blockers of a child that was detached, the same that the request finds`() {
        val gathering = kit.september2027(status = GatheringStatus.open)
        val a = kit.account("genitore-a")
        val m = kit.teacher("maestro-m")
        val luca = kit.registration(gathering, a, "Luca", "Rossi", RegistrationStatus.withdrawn)
        kit.presence(luca, LocalDate.of(2027, 9, 18), m)
        // During the gathering the parent deletes the data of Luca: the registration is withdrawn and has a presence, so it stays as the register, and the child is detached.
        val during = service("2027-09-18T10:00")
        assertIs<ChildErasure.Done>(during.eraseChild(a, kit.childOf(luca)))
        assertEquals(emptyList(), during.childrenOf(a))
        // A teacher puts the registration back while the gathering goes on («Rimetti confermata»).
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.confirmed).where(REGISTRATION.ID.eq(luca)).execute()

        val plan = assertNotNull(during.planAccount(a))

        assertEquals(emptyList(), plan.children, "the child is not among «I tuoi figli»")
        val blocker = plan.blockers.single()
        assertEquals(luca, blocker.registrationId)
        assertTrue(blocker.detached, "the parent can only look at it")
        assertFalse(blocker.parentCanWithdraw, "so it is asked of the teachers, also with the registrations open")
        assertTrue(plan.blocked)
        val result = assertIs<AccountErasure.Blocked>(during.eraseAccount(a))
        assertEquals(plan.blockers, result.children, "the plan says what the request finds")
        assertTrue(kit.accountExists(a))
        assertEquals(1, kit.registrations())
    }

    // ---- The requests made again after a restore (criterion 28) ----------------------------------------------------------------------

    @Test
    fun `t7 c28 after a restore the job makes the request again with the same code, and with nothing to delete it does nothing`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        val luca = kit.registration(gathering, a, "Luca", "Rossi", notes = notes)
        val child = kit.childOf(luca)
        assertIs<ChildErasure.Done>(service("2027-10-05T10:00").eraseChild(a, child))
        assertEquals(0, kit.notes())

        // The database goes back to before the request: the notes are back, and the child is attached to the parent again (the register keeps its number).
        dsl.update(CHILD).set(CHILD.ACCOUNT_ID, a).where(CHILD.ID.eq(child)).execute()
        dsl.execute("insert into health_note (registration_id, allergies, consent_at, consent_by_account_id) values (?, 'Arachidi', now(), ?)", luca, a)
        assertEquals(1, kit.notes())

        val report = job.run(kit.clockAt("2027-10-06T04:15"))

        assertTrue(report.succeeded)
        assertEquals(0, kit.notes(), "the request was made again")
        assertNull(dsl.select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.ID.eq(child)).fetchSingle(CHILD.ACCOUNT_ID))
        assertEquals(listOf("child-request", "child-redo"), dsl.select(RETENTION_LOG.RULE).from(RETENTION_LOG).orderBy(RETENTION_LOG.ID).fetch(RETENTION_LOG.RULE))
        // Nothing to delete now: the job runs again and writes nothing.
        job.run(kit.clockAt("2027-10-07T04:15"))
        assertEquals(2, dsl.fetchCount(RETENTION_LOG), "a request that changes nothing leaves nothing")
        assertEquals(1, kit.registrations())
    }

    @Test
    fun `t7 c28 a child that is back with registrations that have gone is deleted, and an account that is back is deleted again as the request did`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        val email = kit.emailOf(a)
        val anna = kit.registration(gathering, a, "Anna", "Rossi", RegistrationStatus.withdrawn, birth = LocalDate.of(2019, 1, 9))
        val child = kit.childOf(anna)
        assertIs<ChildErasure.Done>(service().eraseChild(a, child))
        assertEquals(0, kit.children())
        assertIs<AccountErasure.Done>(service().eraseAccount(a))
        assertFalse(kit.accountExists(a))

        // Restore of a backup of the day before: the account, the child and its registration are all back.
        kit.restoreAccount(a, email)
        kit.restoreChild(child, a, "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        val back = kit.registrationOfChild(gathering, child, a, RegistrationStatus.withdrawn)
        assertTrue(kit.accountExists(a) && kit.children() == 1 && back > 0)

        val report = job.run(kit.clockAt("2027-10-06T04:15"))

        assertTrue(report.succeeded)
        assertEquals(0, kit.children())
        assertEquals(0, kit.registrations())
        assertFalse(kit.accountExists(a), "the account is deleted again")
        assertEquals(listOf("child-request", "account-request", "child-redo"), dsl.select(RETENTION_LOG.RULE).from(RETENTION_LOG).orderBy(RETENTION_LOG.ID).fetch(RETENTION_LOG.RULE).take(3))
    }

    @Test
    fun `t7 c28 a database restored to before a date makes the rules by time delete again at the start`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        kit.registration(gathering, a, "Luca", "Rossi", notes = notes)
        job.run(kit.clockAt("2027-10-20T04:15"))
        assertEquals(0, kit.notes())

        // The notes are back (a backup of the day before), and the application starts again: the rule deletes them again.
        dsl.execute("insert into health_note (registration_id, allergies, consent_at, consent_by_account_id) select id, 'Arachidi', now(), account_id from registration")
        assertEquals(1, kit.notes())
        job.run(kit.clockAt("2027-10-21T04:15"))
        assertEquals(0, kit.notes())
    }

    @Test
    fun `t7 c28 a request that is blocked after a restore is left, with a warning without data, and the number stays`(output: CapturedOutput) {
        val gathering = kit.september2027(status = GatheringStatus.open)
        val a = kit.account("genitore-a")
        val anna = kit.registration(gathering, a, "Anna", "Rossi", RegistrationStatus.withdrawn, birth = LocalDate.of(2019, 1, 9))
        val child = kit.childOf(anna)
        assertIs<ChildErasure.Done>(service().eraseChild(a, child))
        // The backup is older: the child is back with a registration that was not withdrawn yet, and the gathering is still going on for the job (the day of the backup).
        kit.restoreChild(child, a, "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        kit.registrationOfChild(gathering, child, a)

        val report = job.run(kit.clockAt("2027-09-18T04:15"))

        assertTrue(report.succeeded, "a blocked request is not a failure of the job")
        assertEquals(1, kit.children(), "nothing was deleted: the holder decides")
        assertEquals(child, dsl.select(RETENTION_LOG.TARGET_ID).from(RETENTION_LOG).where(RETENTION_LOG.RULE.eq("child-request")).fetchSingle(RETENTION_LOG.TARGET_ID))
        val warnings = output.all.lines().filter { it.contains(" WARN ") && it.contains("cannot be made again now") }
        assertEquals(1, warnings.size, output.all)
        // The message alone: the rest of the line is the time, the process and the thread, whose digits can be the number of the child (the line has no «]: », so the old cut kept all of it).
        val message = warnings.single().substringAfter(" : ")
        assertTrue(message.startsWith("Retention:"), warnings.single())
        for (word in listOf("Anna", "Rossi", "2019", "example.org", "$child")) assertFalse(message.contains(word), "the warning has data: $warnings")
    }

    /** The state of the world after the request of [reader], an account that stayed for the readings: [m] is the other teacher. */
    private class Left(val gathering: Long, val m: Long, val reader: Long)

    /** M and the reader (a teacher who read the notes of a registration) are teachers; the reader asks to delete the account, and it stays, with the address alone, for the readings. */
    private fun readerWhoLeft(): Left {
        val gathering = kit.september2027()
        val m = kit.teacher("maestro-m")
        val reader = kit.teacher("maestro-lettore", first = "Paolo", last = "Verdi")
        val registration = kit.registration(gathering, kit.account("genitore-a"), "Luca", "Rossi", notes = notes)
        kit.reading(registration, reader)
        assertIs<AccountErasure.Done>(service("2027-10-05T10:00").eraseAccount(reader))
        assertTrue(kit.accountExists(reader))
        assertFalse(kit.isTeacher(reader))
        return Left(gathering, m, reader)
    }

    /** What a restore of a backup older than the request brings back to the reader (the rows are made now, by the clock of the kit: older than the request of 5 October 2027). */
    private class Back(val goes: Long, val goesRegistration: Long, val stays: Long, val staysRegistration: Long)

    /**
     * A child with a withdrawn registration and notes (the request deleted them), and a child with a confirmed registration of a finished gathering and notes (the request
     * kept the registration as the proof, detached the child and deleted the notes): all of them are back, attached to the reader.
     */
    private fun backBeforeTheRequest(left: Left): Back {
        val goes = kit.registration(left.gathering, left.reader, "Sara", "Neri", RegistrationStatus.withdrawn, notes = notes, birth = LocalDate.of(2018, 2, 3))
        val stays = kit.registration(left.gathering, left.reader, "Marco", "Neri", notes = notes, birth = LocalDate.of(2015, 6, 7))
        return Back(kit.childOf(goes), goes, kit.childOf(stays), stays)
    }

    private fun accountOfChild(child: Long): Long? = dsl.select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.ID.eq(child)).fetchSingle(CHILD.ACCOUNT_ID)

    /** What the request that is made again deleted of [back]: the child of the withdrawn registration is gone, the other is detached with its registration and no notes. */
    private fun assertTheOldDataWentAsTheRequestDid(back: Back) {
        assertEquals(0, dsl.fetchCount(CHILD, CHILD.ID.eq(back.goes)), "the child of the withdrawn registration goes with it")
        assertEquals(0, dsl.fetchCount(REGISTRATION, REGISTRATION.ID.eq(back.goesRegistration)))
        assertEquals(1, dsl.fetchCount(REGISTRATION, REGISTRATION.ID.eq(back.staysRegistration)), "the registration that is the proof stays until its date")
        assertNull(accountOfChild(back.stays), "the child of what stays is detached")
        assertNull(dsl.select(REGISTRATION.ACCOUNT_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(back.staysRegistration)).fetchSingle(REGISTRATION.ACCOUNT_ID), "and the registration loses the link")
        assertEquals(0, dsl.fetchCount(HEALTH_NOTE, HEALTH_NOTE.REGISTRATION_ID.`in`(back.goesRegistration, back.staysRegistration)), "the notes of both are gone")
    }

    private fun redoRows(): List<Int> = kit.log().filter { it.first == "account-redo" }.map { it.second }

    @Test
    fun `t7 c28 an account that stayed for the readings and was entered again keeps the registration it made after the request, and loses what a backup brought back`() {
        val left = readerWhoLeft()
        val back = backBeforeTheRequest(left)
        // The person came back with the same address after the request and registered a child of theirs, with notes (the consent is of the 6th, after the request).
        val own = kit.rows.register(left.gathering, left.reader, "Elena", "Neri", LocalDate.of(2018, 2, 3), notes = notes, at = kit.moment("2027-10-06T09:00"))

        val report = job.run(kit.clockAt("2027-10-07T04:15"))

        assertTrue(report.succeeded)
        assertEquals(1, dsl.fetchCount(REGISTRATION, REGISTRATION.ID.eq(own)), "what the person registered after the request is theirs, and stays")
        assertEquals(left.reader, accountOfChild(kit.childOf(own)), "with its child, still attached")
        assertEquals(left.reader, dsl.select(REGISTRATION.ACCOUNT_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(own)).fetchSingle(REGISTRATION.ACCOUNT_ID))
        assertEquals(1, dsl.fetchCount(HEALTH_NOTE, HEALTH_NOTE.REGISTRATION_ID.eq(own)), "and the notes they consented to after the request")
        assertEquals(2, kit.notes(), "the notes of Luca, of another account, and these")
        assertTheOldDataWentAsTheRequestDid(back)
        assertTrue(kit.accountExists(left.reader))
        assertEquals(1, kit.readings(), "the register of the readings is not touched")
        assertEquals(listOf(4), redoRows(), "two notes, a registration and a child that were back")
        assertTrue(kit.isTeacher(left.m))
    }

    @Test
    fun `t7 c28 an account that stayed for the readings and was invited to be a teacher again after the request keeps the invitation, and loses what a backup brought back`() {
        val left = readerWhoLeft()
        val back = backBeforeTheRequest(left)

        // The person is invited again the day after the request, and has not accepted yet.
        kit.invitationTo(kit.emailOf(left.reader), kit.moment("2027-10-06T09:00"))
        val report = job.run(kit.clockAt("2027-10-07T04:15"))

        assertTrue(report.succeeded)
        assertEquals(1, dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(kit.emailOf(left.reader))), "what the person was given after the request is theirs")
        assertTheOldDataWentAsTheRequestDid(back)
        assertTrue(kit.accountExists(left.reader))
        assertEquals(listOf(4), redoRows())
        assertTrue(kit.isTeacher(left.m))
    }

    @Test
    fun `t7 c28 an account that stayed for the readings and accepted the invitation after the request is a teacher still, and loses what a backup brought back`() {
        val left = readerWhoLeft()
        val back = backBeforeTheRequest(left)

        // The invitation is used up the day after the request: the person is a teacher again, with a row that is newer than the request.
        TestAccounts.makeTeacher(dsl, kit.emailOf(left.reader), "Paolo", "Verdi")
        kit.madeTeacherAt(left.reader, kit.moment("2027-10-06T10:00"))
        val report = job.run(kit.clockAt("2027-10-07T04:15"))

        assertTrue(report.succeeded)
        assertTrue(kit.isTeacher(left.reader), "the teacher row of the person who came back is not taken away by the job")
        assertTheOldDataWentAsTheRequestDid(back)
        assertTrue(kit.accountExists(left.reader))
        assertEquals(listOf(4), redoRows())
    }

    @Test
    fun `t7 c28 an account that stayed for the readings, invited again after the request and with nothing that a backup brought back, has nothing older to delete, and nothing is written`() {
        val left = readerWhoLeft()
        kit.invitationTo(kit.emailOf(left.reader), kit.moment("2027-10-06T09:00"))
        val report = job.run(kit.clockAt("2027-10-07T04:15"))

        assertTrue(report.succeeded)
        assertEquals(1, dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(kit.emailOf(left.reader))))
        assertTrue(kit.accountExists(left.reader))
        assertEquals(emptyList<Int>(), redoRows(), "a request that changes nothing leaves nothing")
        assertTrue(kit.isTeacher(left.m))
    }

    @Test
    fun `t7 c28 an account that a backup brought back and that was made a teacher after the request stays with the teacher row, and loses the child that came back`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        val email = kit.emailOf(a)
        val anna = kit.registration(gathering, a, "Anna", "Rossi", RegistrationStatus.withdrawn, birth = LocalDate.of(2019, 1, 9))
        val child = kit.childOf(anna)
        assertIs<AccountErasure.Done>(service().eraseAccount(a))
        assertFalse(kit.accountExists(a), "without readings the account went")

        // The backup of the day before: the account, the child and its registration are back; the person is made a teacher on the 6th, before the job (the teacher row would
        // go with the account: `on delete cascade`).
        kit.restoreAccount(a, email)
        kit.restoreChild(child, a, "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        kit.registrationOfChild(gathering, child, a, RegistrationStatus.withdrawn)
        TestAccounts.makeTeacher(dsl, email, "Paolo", "Verdi")
        kit.madeTeacherAt(a, kit.moment("2027-10-06T10:00"))

        val report = job.run(kit.clockAt("2027-10-07T04:15"))

        assertTrue(report.succeeded)
        assertEquals(0, kit.children(), "the child that came back is deleted again")
        assertEquals(0, kit.registrations())
        assertTrue(kit.accountExists(a), "the account holds what the person did after the request")
        assertTrue(kit.isTeacher(a))
        assertEquals(listOf(2), redoRows(), "the registration and the child")
    }

    @Test
    fun `t7 c28 a teacher row and an invitation that a backup brings back are older than the request, and the request is made again over them`() {
        val left = readerWhoLeft()

        // The backup is older than the request: the teacher row is back (made in September), and so is an invitation (made on 1 October).
        TestAccounts.makeTeacher(dsl, kit.emailOf(left.reader), "Paolo", "Verdi")
        kit.madeTeacherAt(left.reader, kit.moment("2027-09-01T09:00"))
        kit.invitationTo(kit.emailOf(left.reader), kit.moment("2027-10-01T09:00"))
        val report = job.run(kit.clockAt("2027-10-06T04:15"))

        assertTrue(report.succeeded)
        assertFalse(kit.isTeacher(left.reader), "the request is made again: the teacher row goes as it did")
        assertEquals(0, dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(kit.emailOf(left.reader))))
        assertTrue(kit.accountExists(left.reader), "the address stays for the readings")
        assertEquals(1, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("account-redo")))
        assertTrue(kit.isTeacher(left.m))
    }

    @Test
    fun `t7 c28 an invitation and a teacher row have the moment they were made, and inviting again renews it with the expiry`() {
        val first = kit.clockAt("2027-10-05T10:00")
        val later = kit.clockAt("2027-10-06T11:30")
        val settings = TeacherSettings(Duration.ofDays(7))
        val account = kit.account("maestro-nuovo")
        val address = EmailAddress.parse(kit.emailOf(account))!!

        Invitations(dsl, first, settings).invite(address)
        fun invitedAt() = dsl.select(TEACHER_INVITATION.INVITED_AT).from(TEACHER_INVITATION).where(TEACHER_INVITATION.EMAIL.eq(address.value)).fetchSingle(TEACHER_INVITATION.INVITED_AT)!!.toInstant()
        assertEquals(first.instant(), invitedAt())

        Invitations(dsl, later, settings).invite(address)
        assertEquals(later.instant(), invitedAt(), "a new invitation: the moment is renewed with the expiry")

        assertTrue(Invitations(dsl, later, settings).accept(CurrentAccount(account, address.value), TeacherName("Paolo", "Verdi")))
        assertEquals(later.instant(), dsl.select(TEACHER.CREATED_AT).from(TEACHER).where(TEACHER.ACCOUNT_ID.eq(account)).fetchSingle(TEACHER.CREATED_AT)!!.toInstant())
        assertEquals(0, dsl.fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(address.value)), "the invitation is used up")
    }
}
