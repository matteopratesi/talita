package talita.account

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.CredentialRecord
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.context.annotation.Import
import talita.CapturesOutput
import talita.LockWait
import talita.TestcontainersConfiguration
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.PASSKEY
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.jooq.tables.references.RETENTION_LOG
import talita.registration.HealthNotes
import talita.registration.Notes
import talita.retention.AccountErasure
import talita.retention.ErasureService
import talita.retention.RetentionJob
import talita.retention.RetentionLog
import talita.retention.RetentionPolicy
import talita.retention.RetentionReport
import talita.retention.RetentionTestKit
import talita.teacher.Teachers
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Devices and passkeys in the deletions of TALITA-7 (TALITA-15, criteria 29-33): they go with the account, on request and by the job, in the same transaction; they go all, also those
 * made after a request that is made again after a restore; an account that stays for the readings closes the other sessions and empties its handle; the job deletes the devices that are
 * past their day each night, without a row in the register of the deletions and with a line of the log that says how many. The database is real, the clock is fixed at a moment of Rome.
 * Addresses, dates and names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class AccessErasureTests(
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
        dsl.deleteFrom(REMEMBERED_DEVICE).execute()
        dsl.deleteFrom(PASSKEY).execute()
        kit.removeAccountsWithoutRole()
    }

    private fun service(at: String = "2027-10-05T10:00"): ErasureService {
        val fixed = kit.clockAt(at)
        return ErasureService(dsl, fixed, policy, HealthNotes(dsl, fixed, policy), Teachers(dsl, fixed), RememberedDevices(dsl, fixed, policy), Passkeys(dsl, fixed), Accounts(dsl, fixed))
    }

    private var serial = 0

    private fun device(account: Long, createdOn: String = "2027-10-01"): Long {
        serial += 1
        val selector = "S%020d".format(serial) + "A"
        return dsl.fetchOne(
            "insert into remembered_device (account_id, selector, validator_hash, created_on) values (?, ?, ?, ?::date) returning id", account, selector, ByteArray(32) { serial.toByte() }, createdOn,
        )!!.get(0, Long::class.java)
    }

    /** The device was last re-entered with its cookie at [moment] (a moment of Rome): the hash of the value before, and the moment, as a re-entry writes them. */
    private fun rotated(id: Long, moment: String) {
        dsl.update(REMEMBERED_DEVICE).set(REMEMBERED_DEVICE.PREVIOUS_HASH, ByteArray(32) { 3 }).set(REMEMBERED_DEVICE.ROTATED_AT, kit.moment(moment)).where(REMEMBERED_DEVICE.ID.eq(id)).execute()
    }

    /** What the row holds of the last re-entry: the hash of the value before and the moment, each or null. */
    private fun rotation(id: Long): Pair<ByteArray?, java.time.OffsetDateTime?> =
        dsl.select(REMEMBERED_DEVICE.PREVIOUS_HASH, REMEMBERED_DEVICE.ROTATED_AT).from(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ID.eq(id)).fetchSingle().let { it.value1() to it.value2() }

    private fun passkey(account: Long): Int {
        serial += 1
        return dsl.execute(
            "insert into passkey (credential_id, account_id, public_key, signature_count, uv_initialized, backup_eligible, backup_state, created_on) values (?, ?, ?, 0, true, false, false, '2027-10-01')",
            ByteArray(32) { serial.toByte() }, account, byteArrayOf(1, 2, 3),
        )
    }

    private fun handle(account: Long) = dsl.update(ACCOUNT).set(ACCOUNT.PASSKEY_HANDLE, ByteArray(64) { 5 }).where(ACCOUNT.ID.eq(account)).execute()

    private fun devices(account: Long) = dsl.fetchCount(REMEMBERED_DEVICE, REMEMBERED_DEVICE.ACCOUNT_ID.eq(account))

    private fun passkeys(account: Long) = dsl.fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(account))

    private fun generation(account: Long): Int = dsl.select(ACCOUNT.SESSION_GENERATION).from(ACCOUNT).where(ACCOUNT.ID.eq(account)).fetchSingle(ACCOUNT.SESSION_GENERATION)!!

    private fun handleOf(account: Long): ByteArray? = dsl.select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(account)).fetchSingle(ACCOUNT.PASSKEY_HANDLE)

    // ---- On request (criterion 29) ----------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c29 the account that is deleted takes its devices and its passkeys with it, in the same transaction`() {
        val a = kit.account("genitore-a")
        val other = kit.account("genitore-b")
        device(a); device(a, "2027-09-20"); passkey(a); passkey(a)
        device(other); passkey(other)
        handle(a)

        val result = service().eraseAccount(a)

        assertEquals(false, assertIs<AccountErasure.Done>(result).accountStays)
        assertFalse(kit.accountExists(a))
        assertEquals(0, devices(a))
        assertEquals(0, passkeys(a))
        assertEquals(1, devices(other), "the devices of another account are not touched")
        assertEquals(1, passkeys(other))
        // The rows that went are in the number of the register: four rows of access and the account.
        assertEquals("account-request" to 5, kit.log().single())
    }

    @Test
    fun `t15 c29 an account that stays for the register of the readings loses devices and passkeys, raises the number of the sessions and empties the handle`() {
        val gathering = kit.september2027()
        val teacher = kit.teacher("maestro-m")
        val child = kit.registration(gathering, kit.account("genitore-a"), notes = notes)
        kit.reading(child, teacher)
        device(teacher); passkey(teacher); handle(teacher)
        // A second teacher, so that the last one can be taken away.
        kit.teacher("maestro-n")
        val before = generation(teacher)

        val result = service().eraseAccount(teacher)

        assertEquals(true, assertIs<AccountErasure.Done>(result).accountStays)
        assertTrue(kit.accountExists(teacher), "the address stays, alone, for the register of the readings")
        assertEquals(0, devices(teacher))
        assertEquals(0, passkeys(teacher))
        assertEquals(before + 1, generation(teacher), "the other sessions of the account end at their next request: the case that TALITA-7 left open")
        assertNull(handleOf(teacher), "the handle is not needed any more")
    }

    @Test
    fun `t15 c29 an account that is deleted for good does not need its number of the sessions to grow, because the sessions end when the account is not there`() {
        val a = kit.account("genitore-a")
        val before = generation(a)

        service().eraseAccount(a)

        assertFalse(kit.accountExists(a))
        assertEquals(0, before)
    }

    // ---- After a restore (criterion 30) -------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c30 a request made again after a restore deletes every device and passkey of the account, also those born after the request`() {
        val gathering = kit.september2027()
        val teacher = kit.teacher("maestro-m")
        kit.teacher("maestro-n")
        val child = kit.registration(gathering, kit.account("genitore-a"), notes = notes)
        kit.reading(child, teacher)
        // The request of the teacher: the account stays for the readings.
        service("2027-10-05T10:00").eraseAccount(teacher)
        assertTrue(kit.accountExists(teacher))
        // The person came back with a link after the request (the register still has the number of the request, for 37 days), and added a device and a passkey: the job makes the request
        // again, as it does after a restore of an old backup, and what the person did after is not spared, because it only costs a link.
        device(teacher, "2027-10-06"); passkey(teacher); handle(teacher)
        val before = generation(teacher)

        job.run(kit.clockAt("2027-10-07T04:15"))

        assertEquals(0, devices(teacher))
        assertEquals(0, passkeys(teacher))
        assertTrue(kit.accountExists(teacher))
        assertEquals(listOf("account-redo" to 2), kit.log().filter { it.first == "account-redo" }, "the rows that went are in the register of the redo")
        assertEquals(before + 1, generation(teacher), "the sessions that the person opened after the request end as well")
        assertNull(handleOf(teacher))
    }

    @Test
    fun `t15 c30 a person who came back after the request, with no new device or passkey, is not signed out by the job that makes the request again, night after night`() {
        val gathering = kit.september2027()
        val teacher = kit.teacher("maestro-m")
        kit.teacher("maestro-n")
        val child = kit.registration(gathering, kit.account("genitore-a"), notes = notes)
        kit.reading(child, teacher)
        device(teacher); handle(teacher)
        service("2027-10-05T10:00").eraseAccount(teacher)
        // After the request the person came back with a link and registered a child of their own: the account stays with that, and the register still has the request for 37 days.
        kit.rows.register(gathering, teacher, "Luca", "Verdi", at = kit.moment("2027-10-06T09:00"))
        val afterTheRequest = generation(teacher)

        job.run(kit.clockAt("2027-10-07T04:15"))
        job.run(kit.clockAt("2027-10-08T04:15"))

        assertTrue(kit.accountExists(teacher))
        assertEquals(afterTheRequest, generation(teacher), "the sessions that the person opened after the request stay: nothing was taken away, so there is nothing to close")
        assertEquals(emptyList(), kit.log().filter { it.first == "account-redo" }, "and the redo has nothing to write")
    }

    @Test
    fun `t15 c30 a person who came back after the request and has a device that was born after it is signed out once, by the first night that takes the device away, and not by the nights after`() {
        val gathering = kit.september2027()
        val teacher = kit.teacher("maestro-m")
        kit.teacher("maestro-n")
        val child = kit.registration(gathering, kit.account("genitore-a"), notes = notes)
        kit.reading(child, teacher)
        service("2027-10-05T10:00").eraseAccount(teacher)
        kit.rows.register(gathering, teacher, "Luca", "Verdi", at = kit.moment("2027-10-06T09:00"))
        device(teacher, "2027-10-06")
        val afterTheRequest = generation(teacher)

        job.run(kit.clockAt("2027-10-07T04:15"))

        assertEquals(0, devices(teacher), "the device that was born after the request goes, as criterion 30 says")
        assertEquals(afterTheRequest + 1, generation(teacher), "and the number of the sessions grows, because an access was taken away")
        job.run(kit.clockAt("2027-10-08T04:15"))
        assertEquals(afterTheRequest + 1, generation(teacher), "the next night has nothing to take away: the sessions are not closed again")
    }

    /** A credential as the registration makes it: what the table keeps of a passkey. */
    private fun record(id: Int = 9): CredentialRecord = ImmutableCredentialRecord.builder()
        .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
        .credentialId(Bytes(ByteArray(32) { id.toByte() }))
        .userEntityUserId(Bytes(ByteArray(64) { 7 }))
        .publicKey(ImmutablePublicKeyCose(byteArrayOf(1, 2, 3)))
        .signatureCount(0)
        .uvInitialized(true)
        .transports(emptySet())
        .backupEligible(false)
        .backupState(false)
        .label("")
        .build()

    @Test
    fun `t15 c29 a passkey that was being made while the account was erased is refused when it gets the lock, and nothing is saved, so the account that stays for the readings cannot be entered`() {
        val gathering = kit.september2027()
        val teacher = kit.teacher("maestro-m")
        kit.teacher("maestro-n")
        val child = kit.registration(gathering, kit.account("genitore-a"), notes = notes)
        kit.reading(child, teacher)
        // The registration found the account and verified what the phone made: it started with this number of the sessions, and now it waits for the lock of the account.
        val started = generation(teacher)
        val store = Passkeys(dsl, clock)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val locked = CountDownLatch(1)
            val release = CountDownLatch(1)
            // The erasure is in its transaction: it has the account locked, and it commits when the test says.
            val holder = java.util.concurrent.atomic.AtomicInteger()
            val erasure = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    service("2027-10-05T10:00").eraseAccountIn(tx, teacher, kit.moment("2027-10-05T10:00"), RetentionLog.ACCOUNT_REQUEST)
                    holder.set(tx.fetchOne("select pg_backend_pid()")!!.get(0, Int::class.java))
                    locked.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            // Committing before the registration is really blocked on the lock of the account would let it find the account already erased, and the case that this test is about would not happen:
            // the waiter is the registration (its statement reads the handle of the account `for update`, a text that has `passkey_handle`), and what it waits for is the transaction of the erasure.
            val registration = LockWait.awaitBlocked(dsl, "the registration should wait for the account that the erasure holds", blockedBy = holder.get(), statementLike = "passkey_handle") {
                pool.submit<Boolean> { store.addChecked(teacher, record(), ByteArray(64) { 7 }, started) }
            }
            release.countDown()
            erasure.get(10, TimeUnit.SECONDS)

            val failure = assertFailsWith<ExecutionException> { registration.get(30, TimeUnit.SECONDS) }
            assertIs<PasskeyRefusedException>(failure.cause, "the registration found that the account had been erased")
        } finally {
            pool.shutdownNow()
        }
        assertTrue(kit.accountExists(teacher), "the address stays, alone, for the register of the readings")
        assertEquals(0, passkeys(teacher), "and no passkey was saved: it would have given access again to an account that was deleted")
        assertNull(handleOf(teacher), "and no handle either")
        assertEquals(started + 1, generation(teacher))
    }

    @Test
    fun `t15 c29 a registration with the number of the sessions that the account has saves the passkey, and one with an old number is refused`() {
        val account = kit.account("genitore-a")
        val store = Passkeys(dsl, clock)
        val current = generation(account)

        assertFailsWith<PasskeyRefusedException> { store.addChecked(account, record(), ByteArray(64) { 7 }, current - 1) }
        assertEquals(0, passkeys(account))
        assertNull(handleOf(account))

        assertTrue(store.addChecked(account, record(), ByteArray(64) { 7 }, current))
        assertEquals(1, passkeys(account))
        assertEquals(64, handleOf(account)!!.size)
    }

    // ---- The accounts that stay empty (criterion 31) ----------------------------------------------------------------------------------

    @Test
    fun `t15 c31 an empty account that the job deletes takes its devices and its passkeys with it`() {
        val empty = kit.account("vuoto", bornOn = LocalDate.of(2027, 9, 1))
        device(empty, "2027-09-02"); passkey(empty)
        val withChild = kit.account("con-figlio", bornOn = LocalDate.of(2027, 9, 1))
        kit.registration(kit.september2027(), withChild)
        device(withChild); passkey(withChild)

        val report = job.run(kit.clockAt("2027-10-05T04:15"))

        assertFalse(kit.accountExists(empty), "30 days after it was born, and it is empty")
        assertEquals(0, devices(empty))
        assertEquals(0, passkeys(empty))
        assertTrue(kit.accountExists(withChild))
        assertEquals(1, devices(withChild), "an account that is not empty keeps its access")
        assertEquals(1, passkeys(withChild))
        assertEquals(1, report.deleted.single { it.rule == "accounts" }.rows)
    }

    @Test
    fun `t15 c31 an empty account that gets a child while the job runs is not deleted, and keeps its devices and its passkeys`() {
        val empty = kit.account("vuoto", bornOn = LocalDate.of(2027, 9, 1))
        device(empty, "2027-10-01"); passkey(empty)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder = java.util.concurrent.atomic.AtomicInteger()
            val inserted = CountDownLatch(1)
            val release = CountDownLatch(1)
            // A registration that has written a child for the account and has not committed yet: it holds the lock that a foreign key takes on the row of the account.
            val registering = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    tx.insertInto(CHILD).set(CHILD.ACCOUNT_ID, empty).set(CHILD.FIRST_NAME, "Luca").set(CHILD.LAST_NAME, "Rossi").set(CHILD.BIRTH_DATE, LocalDate.of(2016, 4, 4)).execute()
                    holder.set(tx.fetchOne("select pg_backend_pid()")!!.get(0, Int::class.java))
                    inserted.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            assertTrue(inserted.await(10, TimeUnit.SECONDS))
            // The job looks at the accounts: the account is empty for it (the child is not committed), it is a candidate, and the rule has to lock it before it touches anything.
            val run = LockWait.awaitBlocked(dsl, "the rule of the accounts should wait for the registration that holds the account", blockedBy = holder.get(), statementLike = "for update") {
                pool.submit<RetentionReport> { job.run(kit.clockAt("2027-10-05T04:15")) }
            }
            release.countDown()
            registering.get(10, TimeUnit.SECONDS)

            val report = run.get(30, TimeUnit.SECONDS)
            assertEquals(emptyList(), report.failed, "no rule failed: the account is not deleted, and nothing goes back")
            assertEquals(0, report.deleted.filter { it.rule == "accounts" }.sumOf { it.rows })
        } finally {
            pool.shutdownNow()
        }
        assertTrue(kit.accountExists(empty), "the account got a child: it is not empty any more")
        assertEquals(1, dsl.fetchCount(CHILD, CHILD.ACCOUNT_ID.eq(empty)))
        assertEquals(1, devices(empty), "and it keeps its device")
        assertEquals(1, passkeys(empty), "and its passkey")
    }

    // ---- The devices that expire (criterion 32) ------------------------------------------------------------------------------------------

    @Test
    @CapturesOutput
    fun `t15 c32 every night the job deletes the devices past their day, with the length of the role of today, and the register of the deletions does not hear of it`(output: CapturedOutput) {
        // The parents have a child registered, so that their accounts are not empty (the job would delete an empty one with its devices).
        val gathering = kit.september2027()
        val teacher = kit.teacher("maestro-m")
        val parent = kit.account("genitore-a")
        kit.registration(gathering, parent, "Luca", "Rossi")
        val teacherDevice = device(teacher, "2027-09-17")
        val parentDevice = device(parent, "2027-09-17")
        // A parent who became a teacher on the 20th: the row of the 17th is a teacher's from then on.
        val promoted = kit.account("promosso")
        kit.registration(gathering, promoted, "Anna", "Rossi")
        device(promoted, "2027-09-17")

        job.run(kit.clockAt("2027-09-23T23:59"))
        assertEquals(setOf(teacherDevice, parentDevice), dsl.select(REMEMBERED_DEVICE.ID).from(REMEMBERED_DEVICE).fetch(REMEMBERED_DEVICE.ID).filter { it in setOf(teacherDevice, parentDevice) }.toSet(), "on the 23rd all are inside")

        TestAccounts.makeTeacher(dsl, kit.emailOf(promoted))
        val report = job.run(kit.clockAt("2027-09-24T00:30"))

        assertEquals(0, devices(teacher), "a teacher's row: gone on the 24th")
        assertEquals(0, devices(promoted), "a parent who is a teacher now: the length of the role of today")
        assertEquals(1, devices(parent), "a parent's row is good until 16 October")
        assertEquals(emptyList(), report.deleted, "the rows are not in the report of the register")
        assertEquals(emptyList(), kit.log().filter { it.first == "devices" }, "no row in retention_log")
        assertEquals(0, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("devices")))
        assertTrue(output.all.contains("Retention: rule devices deleted 2 rows."), output.all)

        job.run(kit.clockAt("2027-10-16T23:59"))
        assertEquals(1, devices(parent))
        job.run(kit.clockAt("2027-10-17T00:30"))
        assertEquals(0, devices(parent))
    }

    @Test
    @CapturesOutput
    fun `t15 c32 every night the job empties the moment of the last re-entry and the hash of the value before it, in the rows whose 60 seconds are over, and keeps them in the rows still inside the margin`(output: CapturedOutput) {
        val parent = kit.account("genitore-a", bornOn = LocalDate.of(2027, 9, 20))
        kit.registration(kit.september2027(), parent, "Luca", "Rossi")
        val over = device(parent, "2027-09-22")
        val inside = device(parent, "2027-09-22")
        val never = device(parent, "2027-09-22")
        // Two re-entries with the cookie, at 04:13:00 and at 04:14:30, and a device that never came back; the job runs at 04:15:00.
        rotated(over, "2027-09-23T04:13:00")
        rotated(inside, "2027-09-23T04:14:30")

        val report = job.run(kit.clockAt("2027-09-23T04:15:00"))

        assertEquals(null to null, rotation(over), "the margin of this row was over: both columns are empty, together")
        assertEquals(true to true, rotation(inside).let { (it.first != null) to (it.second != null) }, "this one is still inside its 60 seconds: both stay, for the margin")
        assertEquals(null to null, rotation(never))
        assertEquals(3, devices(parent), "no device was deleted: only the two columns were emptied")
        assertEquals(emptyList(), report.deleted, "the register of the deletions does not hear of it")
        assertEquals(0, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("device-rotations")))
        assertTrue(output.all.contains("Retention: rule device-rotations emptied 1 rows."), output.all)

        // The night after the other row is emptied too (at most until the night after), and the third night finds nothing and says nothing.
        job.run(kit.clockAt("2027-09-24T04:15:00"))
        assertEquals(null to null, rotation(inside), "at most until the night after")
        job.run(kit.clockAt("2027-09-25T04:15:00"))
        assertEquals(2, output.all.lines().count { it.contains("rule device-rotations emptied 1 rows") }, "one line for each of the two nights that emptied a row, and none for the third")
    }

    @Test
    @CapturesOutput
    fun `t15 c32 a run that has no device to delete writes nothing in the log`(output: CapturedOutput) {
        device(kit.account("genitore-a"), "2027-10-01")

        job.run(kit.clockAt("2027-10-05T04:15"))

        assertFalse(output.all.contains("Retention: rule devices"), output.all)
    }

    @Test
    fun `t15 c32 the job never touches the passkeys, which stay until the person takes them away or the account goes`() {
        val a = kit.account("genitore-a")
        kit.registration(kit.september2027(), a)
        passkey(a)

        job.run(kit.clockAt("2027-12-01T04:15"))

        assertEquals(1, passkeys(a), "an account that is not empty keeps its passkeys")
    }

    // ---- The registrations that are still pending: nothing of this changes the other rules ------------------------------------------------

    @Test
    fun `t15 c31 the deletion of a child does not touch the access of the account`() {
        val gathering = kit.september2027()
        val a = kit.account("genitore-a")
        val registration = kit.registration(gathering, a, status = RegistrationStatus.withdrawn)
        device(a); passkey(a)

        service().eraseChild(a, kit.childOf(registration))

        assertEquals(1, devices(a))
        assertEquals(1, passkeys(a))
    }
}
