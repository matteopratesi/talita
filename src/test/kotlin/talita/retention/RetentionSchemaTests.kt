package talita.retention

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.RETENTION_LOG
import talita.registration.Notes
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V7 on the real database (criterion 34): the day an account was born, the register of the deletions, the keys that let a teacher go without taking with them what
 * the teacher marked, and the keys that do **not** let an account go while something points at it (so a mistake in a deletion shows here, in a test, and does not
 * detach data in silence).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RetentionSchemaTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = RetentionTestKit(dsl, clock)

    @BeforeEach
    fun clean() = kit.reset()

    /** The name of the constraint that refused [write], or the message if it is not a constraint. */
    private fun refusedBy(write: () -> Unit): String {
        val failure = runCatching(write).exceptionOrNull() ?: error("the database accepted it")
        val sql = generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()
        return sql?.message?.let { Regex("""constraint "([^"]+)"""").find(it)?.groupValues?.get(1) ?: it } ?: failure.message.orEmpty()
    }

    @Test
    fun `t7 c34 the day of birth of an account is there for every account and has no default, the application writes it`() {
        val message = refusedBy { dsl.insertInto(ACCOUNT).set(ACCOUNT.EMAIL, Addresses.unique("senza-giorno")).execute() }
        assertTrue(message.contains("created_on"), message)
        assertEquals(
            "date", dsl.resultQuery("select data_type from information_schema.columns where table_name = 'account' and column_name = 'created_on'").fetchSingle(0, String::class.java),
            "only the day, not the hour",
        )
        assertNull(
            dsl.resultQuery("select column_default from information_schema.columns where table_name = 'account' and column_name = 'created_on'").fetchSingle(0, String::class.java),
        )
    }

    @Test
    fun `t7 c34 the register says a rule that exists, a number of rows that is not negative and a number of person only for a request`() {
        fun write(rule: String, rows: Int, target: Long?) {
            dsl.insertInto(RETENTION_LOG).set(RETENTION_LOG.DONE_AT, OffsetDateTime.now(clock)).set(RETENTION_LOG.RULE, rule).set(RETENTION_LOG.ROW_COUNT, rows)
                .set(RETENTION_LOG.TARGET_ID, target).execute()
        }
        assertEquals("retention_log_rule", refusedBy { write("lunedi", 1, null) })
        assertEquals("retention_log_row_count", refusedBy { write("notes", -1, null) })
        assertEquals("retention_log_target_only_on_request", refusedBy { write("notes", 1, 12) })
        assertEquals("retention_log_target_only_on_request", refusedBy { write("accounts", 1, 12) })
        assertEquals("retention_log_target_only_on_request", refusedBy { write("child-redo", 1, 12) })
        for (rule in listOf("notes", "registrations", "children", "invitations", "accounts", "child-redo", "account-redo")) write(rule, 0, null)
        write("child-request", 3, 12)
        write("account-request", 5, 13)
        assertEquals(9, dsl.fetchCount(RETENTION_LOG))
    }

    @Test
    fun `t7 c34 when the account of a teacher is deleted the paper registration that the teacher typed in stays and has no teacher`() {
        val gathering = kit.september2027()
        val teacher = kit.account("ex-maestro")
        val paper = kit.paper(gathering, teacher)
        val registration = kit.registration(gathering, kit.account("genitore-a"))
        kit.presence(registration, LocalDate.of(2027, 9, 18), teacher)
        kit.payment(registration, 2500, teacher)

        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(teacher)).execute()

        assertNull(dsl.select(REGISTRATION.ENTERED_BY_ACCOUNT_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(paper)).fetchSingle(REGISTRATION.ENTERED_BY_ACCOUNT_ID))
        assertEquals(2, kit.registrations())
        assertEquals(1, kit.presences())
        assertEquals(1, kit.payments())
    }

    private fun deleteAccount(id: Long) = { dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).execute(); Unit }

    @Test
    fun `t7 c34 an account cannot be deleted while a child, a registration, the consent to some notes or a reading points at it`() {
        val gathering = kit.september2027()

        // A child of the account (made with a registration, then the registration is taken off the account: only the child points at it).
        val withChild = kit.account("con-figlio")
        val first = kit.registration(gathering, withChild, "Luca", "Rossi")
        dsl.update(REGISTRATION).set(REGISTRATION.ACCOUNT_ID, null as Long?).where(REGISTRATION.ID.eq(first)).execute()
        assertEquals("child_account_id_fkey", refusedBy(deleteAccount(withChild)))

        // A registration in the name of the account, with the child already detached.
        val withRegistration = kit.account("con-iscrizione")
        val second = kit.registration(gathering, withRegistration, "Anna", "Rossi", birth = LocalDate.of(2019, 1, 9))
        dsl.update(CHILD).set(CHILD.ACCOUNT_ID, null as Long?).where(CHILD.ID.eq(kit.childOf(second))).execute()
        assertEquals("registration_account_id_fkey", refusedBy(deleteAccount(withRegistration)))

        // The consent to some notes, with the registration and the child detached.
        val withConsent = kit.account("con-consenso")
        val third = kit.registration(gathering, withConsent, "Sara", "Verdi", notes = Notes("Arachidi", null, null))
        dsl.update(REGISTRATION).set(REGISTRATION.ACCOUNT_ID, null as Long?).where(REGISTRATION.ID.eq(third)).execute()
        dsl.update(CHILD).set(CHILD.ACCOUNT_ID, null as Long?).where(CHILD.ID.eq(kit.childOf(third))).execute()
        assertEquals("health_note_consent_by_account_id_fkey", refusedBy(deleteAccount(withConsent)))

        // A reading in the register.
        val reader = kit.account("lettore")
        kit.reading(third, reader)
        assertEquals("health_note_access_teacher_account_id_fkey", refusedBy(deleteAccount(reader)))

        // Once what points at them is gone, they go.
        dsl.deleteFrom(HEALTH_NOTE_ACCESS).execute()
        dsl.deleteFrom(HEALTH_NOTE).execute()
        for (id in listOf(withConsent, reader)) dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).execute()
        assertTrue(!kit.accountExists(withConsent) && !kit.accountExists(reader))
    }

    @Test
    fun `t7 c34 a registration keeps its acceptance when the account is gone, and an online registration is told from a paper one by the acceptance`() {
        val gathering = kit.september2027()
        val parent = kit.account("genitore-a")
        val registration = kit.registration(gathering, parent)

        dsl.update(REGISTRATION).set(REGISTRATION.ACCOUNT_ID, null as Long?).where(REGISTRATION.ID.eq(registration)).execute()
        dsl.update(CHILD).set(CHILD.ACCOUNT_ID, null as Long?).execute()
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(parent)).execute()

        val kept = dsl.selectFrom(REGISTRATION).where(REGISTRATION.ID.eq(registration)).fetchSingle()
        assertNull(kept.accountId)
        assertTrue(kept.custodyText != null && kept.custodyAcceptedByEmail != null, "the acceptance and the address copied into it stay")
    }
}
