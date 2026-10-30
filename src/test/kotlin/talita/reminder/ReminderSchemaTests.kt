package talita.reminder

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.enums.ReminderKind
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REMINDER_BATCH
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V11 on the real database (TALITA-13, criterion 15): what a person said about the reminders, in three columns of the account, and the register of what was sent,
 * with the checks that the application relies on. A constraint that is not tried here is a constraint that nobody knows is there.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ReminderSchemaTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = ReminderTestKit(dsl, clock)

    @BeforeEach
    fun clean() = kit.reset()

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    /** The name of the constraint that refused [write], or the message if it is not a constraint. */
    private fun refusedBy(write: () -> Unit): String {
        val failure = runCatching(write).exceptionOrNull() ?: error("the database accepted it")
        val sql = generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()
        return sql?.message?.let { Regex("""constraint "([^"]+)"""").find(it)?.groupValues?.get(1) ?: it } ?: failure.message.orEmpty()
    }

    private fun columns(table: String): Map<String, Pair<String, String>> =
        dsl.resultQuery("select column_name, data_type, is_nullable from information_schema.columns where table_schema = 'public' and table_name = '$table'")
            .fetch().associate { it.get(0, String::class.java) to (it.get(1, String::class.java) to it.get(2, String::class.java)) }

    private fun gathering(): Long = kit.support.make(status = talita.jooq.enums.GatheringStatus.`open`)

    private fun batch(gathering: Long, kind: ReminderKind = ReminderKind.maybe, recipients: Int = 3, failed: Int = 0): Long =
        dsl.insertInto(REMINDER_BATCH)
            .set(REMINDER_BATCH.GATHERING_ID, gathering)
            .set(REMINDER_BATCH.KIND, kind)
            .set(REMINDER_BATCH.SENT_AT, now())
            .set(REMINDER_BATCH.RECIPIENTS, recipients.toShort())
            .set(REMINDER_BATCH.FAILED, failed.toShort())
            .returningResult(REMINDER_BATCH.ID).fetchSingle().value1()!!

    // ---- The account ----------------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c15 the account has the moment of the avviso, the moment of the stop and the code to stop, all empty at the start`() {
        val columns = columns("account")

        assertEquals("timestamp with time zone" to "YES", columns["notice_consent_at"])
        assertEquals("timestamp with time zone" to "YES", columns["reminders_off_at"])
        assertEquals("text" to "YES", columns["reminder_code"])
        val person = kit.account("schema-vuoto")
        assertNull(kit.consentOf(person.id))
        assertNull(kit.stopOf(person.id))
        assertNull(kit.codeOf(person.id))
        // An account that was there before the migration has nothing in them, and the others are not touched: only these three columns were added to account.
        assertEquals(
            setOf("id", "email", "created_on", "notice_consent_at", "reminders_off_at", "reminder_code"),
            columns.keys,
            "no other datum about a person was added to the account",
        )
    }

    @Test
    fun `t13 c15 the code to stop is 16 characters of base64url and belongs to one account only`() {
        val code = "Xq3Zp0mV_8aTe-Lw"
        kit.account("codice-uno", code = code)

        assertEquals("account_reminder_code_unique", refusedBy { kit.account("codice-due", code = code) }, "two accounts with the same code")
        for (wrong in listOf("corto", "Xq3Zp0mV_8aTe-Lw1", "Xq3Zp0mV 8aTe-Lw", "Xq3Zp0mV+8aTe/Lw", "")) {
            assertEquals("account_reminder_code_format", refusedBy { kit.account("codice-sbagliato", code = wrong) }, "«$wrong»")
        }
        // Many accounts without a code are fine: null is not a repeated value.
        kit.account("senza-codice-uno")
        kit.account("senza-codice-due")
    }

    @Test
    fun `t13 c15 a person who stopped has no consent to the avviso, and the two are never both there`() {
        val both = refusedBy { kit.account("entrambi", notice = now(), stopped = now()) }
        assertEquals("account_notice_or_stop", both)

        val person = kit.account("scelta", notice = now())
        assertEquals(
            "account_notice_or_stop",
            refusedBy { dsl.update(ACCOUNT).set(ACCOUNT.REMINDERS_OFF_AT, now()).where(ACCOUNT.ID.eq(person.id)).execute() },
            "stopping without taking the consent away",
        )
        // Taking the consent away and stopping, in one statement, is what the application does.
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDERS_OFF_AT, now()).setNull(ACCOUNT.NOTICE_CONSENT_AT).where(ACCOUNT.ID.eq(person.id)).execute()
        assertNull(kit.consentOf(person.id))
        assertTrue(kit.stopOf(person.id) != null)
        // And a person who stopped can only ask for the avviso again after taking the reminders back.
        assertEquals(
            "account_notice_or_stop",
            refusedBy { dsl.update(ACCOUNT).set(ACCOUNT.NOTICE_CONSENT_AT, now()).where(ACCOUNT.ID.eq(person.id)).execute() },
        )
    }

    // ---- The register of what was sent --------------------------------------------------------------------------------------------

    @Test
    fun `t13 c15 a batch is a gathering, a kind, a moment and two numbers, and nothing about a person`() {
        val columns = columns("reminder_batch")

        assertEquals(setOf("id", "gathering_id", "kind", "sent_at", "recipients", "failed"), columns.keys, "no recipient, no teacher, no address")
        assertEquals("smallint", columns.getValue("recipients").first)
        assertEquals("smallint", columns.getValue("failed").first)
        assertEquals("timestamp with time zone", columns.getValue("sent_at").first)
        assertTrue(columns.values.all { it.second == "NO" }, "everything is there")
        assertEquals(
            listOf("maybe", "notice"),
            dsl.resultQuery("select unnest(enum_range(null::reminder_kind))::text").fetch(0, String::class.java),
            "the two kinds, in the words of the code",
        )
    }

    @Test
    fun `t13 c15 a batch needs at least one recipient, and the mails that did not leave are between none and all`() {
        val gathering = gathering()

        assertEquals("reminder_batch_recipients", refusedBy { batch(gathering, recipients = 0) })
        assertEquals("reminder_batch_failed", refusedBy { batch(gathering, recipients = 3, failed = 4) })
        assertEquals("reminder_batch_failed", refusedBy { batch(gathering, recipients = 3, failed = -1) })
        batch(gathering, recipients = 3, failed = 0)
        batch(gathering, recipients = 3, failed = 2)
        assertEquals(2, kit.batches(gathering).size)
    }

    @Test
    fun `t13 c15 a batch is of a gathering that exists, and goes with it, as the rest of what hangs from a gathering does in the tests`() {
        assertEquals(
            "reminder_batch_gathering_id_fkey",
            refusedBy { batch(Long.MAX_VALUE - 7) },
            "a batch without a gathering",
        )
        val gathering = gathering()
        batch(gathering, ReminderKind.maybe)
        batch(gathering, ReminderKind.notice)
        assertEquals(2, kit.batches(gathering).size)

        dsl.deleteFrom(GATHERING).where(GATHERING.ID.eq(gathering)).execute()

        assertEquals(0, dsl.fetchCount(REMINDER_BATCH, REMINDER_BATCH.GATHERING_ID.eq(gathering)))
    }

    @Test
    fun `t13 c15 the kind is one of the two, and the index finds the last batch of a kind for a gathering`() {
        val gathering = gathering()

        val failure = runCatching { dsl.execute("insert into reminder_batch (gathering_id, kind, sent_at, recipients, failed) values (?, 'sms', now(), 1, 0)", gathering) }.exceptionOrNull()
        assertTrue(failure != null && generateSequence(failure) { it.cause }.any { it.message.orEmpty().contains("reminder_kind") }, "$failure")
        val index = dsl.resultQuery("select indexdef from pg_indexes where indexname = 'reminder_batch_last'").fetchSingle(0, String::class.java).orEmpty()
        assertTrue(index.contains("(gathering_id, kind, sent_at DESC)"), index)
    }

    // ---- The migration itself -----------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c15 the migration is V11, in the file of the reminders, and adds no extension`() {
        val script = Files.readString(Path.of("src/main/resources/db/migration/V11__reminders.sql"))

        assertTrue(script.contains("create table reminder_batch"))
        assertTrue(!Regex("""(?im)^\s*create\s+extension""").containsMatchIn(script), "the only extension of the project is unaccent, in V1")
        assertEquals(
            "11",
            dsl.resultQuery("select version from flyway_schema_history where script = 'V11__reminders.sql' and success").fetchSingle(0, String::class.java),
        )
        // The addresses of the tests are never in a migration (the repository is public).
        assertTrue(!script.contains(Addresses.of("")), "an address in the migration")
    }
}
