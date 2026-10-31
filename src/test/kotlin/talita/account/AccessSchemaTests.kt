package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.Addresses
import talita.TestcontainersConfiguration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V12 on the real database (TALITA-15, criteria 31, 36): the devices of «Ricordami su questo telefono» and the passkeys, with the checks that the application relies on, and the keys to
 * `account` that have no cascade. A constraint that is not tried here is a constraint that nobody knows is there.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class AccessSchemaTests(@Autowired private val dsl: DSLContext) {

    /** The name of the constraint that refused [write], or the message if it is not a constraint. */
    private fun refusedBy(write: () -> Unit): String {
        val failure = runCatching(write).exceptionOrNull() ?: error("the database accepted it")
        val sql = generateSequence(failure) { it.cause }.filterIsInstance<java.sql.SQLException>().firstOrNull()
        return sql?.message?.let { Regex("""constraint "([^"]+)"""").find(it)?.groupValues?.get(1) ?: it } ?: failure.message.orEmpty()
    }

    private val made = mutableListOf<Long>()

    private fun account(): Long =
        dsl.fetchOne("insert into account (email, created_on) values (?, current_date) returning id", Addresses.unique("schema-accesso"))!!.get(0, Long::class.java).also { made += it }

    /** The database is shared by every test: what this one made goes, devices and passkeys first (the keys to the account have no cascade). */
    @org.junit.jupiter.api.AfterEach
    fun clean() {
        dsl.execute("delete from remembered_device where account_id = any(?)", made.toTypedArray())
        dsl.execute("delete from passkey where account_id = any(?)", made.toTypedArray())
        dsl.execute("delete from account where id = any(?)", made.toTypedArray())
        made.clear()
    }

    private fun device(account: Long, selector: String = "AAAAAAAAAAAAAAAAAAAAAA", hash: ByteArray = ByteArray(32), createdOn: String = "2027-09-17"): Int =
        dsl.execute("insert into remembered_device (account_id, selector, validator_hash, created_on) values (?, ?, ?, ?::date)", account, selector, hash, createdOn)

    private fun passkey(account: Long, id: ByteArray = ByteArray(32) { 7 }, key: ByteArray = byteArrayOf(1), count: Long = 0, backupEligible: Boolean = false, backupState: Boolean = false): Int =
        dsl.execute(
            "insert into passkey (credential_id, account_id, public_key, signature_count, uv_initialized, backup_eligible, backup_state, transports, created_on) values (?, ?, ?, ?, true, ?, ?, 'internal', current_date)",
            id, account, key, count, backupEligible, backupState,
        )

    private fun columns(table: String): Map<String, Pair<String, String>> =
        dsl.resultQuery("select column_name, data_type, is_nullable from information_schema.columns where table_schema = 'public' and table_name = '$table'")
            .fetch().associate { it.get(0, String::class.java) to (it.get(1, String::class.java) to it.get(2, String::class.java)) }

    // ---- The account -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c36 the account has the number of the sessions, that starts at zero, and the handle of the passkeys, empty at the start, and nothing else was added`() {
        val columns = columns("account")

        assertEquals("integer" to "NO", columns["session_generation"])
        assertEquals("bytea" to "YES", columns["passkey_handle"])
        assertEquals(
            setOf("id", "email", "created_on", "notice_consent_at", "reminders_off_at", "reminder_code", "session_generation", "passkey_handle"),
            columns.keys,
            "no other datum about a person was added to the account",
        )
        val id = account()
        assertEquals(0, dsl.fetchOne("select session_generation from account where id = ?", id)!!.get(0, Int::class.java))
        assertEquals(null, dsl.fetchOne("select passkey_handle from account where id = ?", id)!!.get(0))
    }

    @Test
    fun `t15 c36 the account refuses a number of the sessions that is below zero, a handle of the wrong size and the same handle for two accounts`() {
        val one = account()
        val two = account()

        assertEquals("account_session_generation_not_negative", refusedBy { dsl.execute("update account set session_generation = -1 where id = ?", one) })
        assertEquals("account_passkey_handle_size", refusedBy { dsl.execute("update account set passkey_handle = ? where id = ?", ByteArray(63), one) })
        assertEquals("account_passkey_handle_size", refusedBy { dsl.execute("update account set passkey_handle = ? where id = ?", ByteArray(65), one) })
        dsl.execute("update account set passkey_handle = ? where id = ?", ByteArray(64) { 1 }, one)
        assertEquals("account_passkey_handle_unique", refusedBy { dsl.execute("update account set passkey_handle = ? where id = ?", ByteArray(64) { 1 }, two) })
    }

    // ---- The devices -------------------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c36 a device has the account, the selector, the hash of the value and the day, the hash of the value before and the moment of the change, and nothing about the device, the browser or the use`() {
        assertEquals(
            mapOf(
                "id" to ("bigint" to "NO"), "account_id" to ("bigint" to "NO"), "selector" to ("text" to "NO"),
                "validator_hash" to ("bytea" to "NO"), "created_on" to ("date" to "NO"),
                "previous_hash" to ("bytea" to "YES"), "rotated_at" to ("timestamp with time zone" to "YES"),
            ),
            columns("remembered_device"),
        )
    }

    @Test
    fun `t15 c03 the table of the devices refuses a value that is not 32 bytes, a selector that is not 22 characters of base64url and the same selector twice`() {
        val owner = account()

        assertEquals("remembered_device_validator_hash_size", refusedBy { device(owner, hash = ByteArray(31)) })
        assertEquals("remembered_device_validator_hash_size", refusedBy { device(owner, hash = ByteArray(33)) })
        // The value before the last one is a hash too, of 32 bytes, and goes with the moment of the change: both or none.
        assertEquals("remembered_device_previous_hash_size", refusedBy { dsl.execute("insert into remembered_device (account_id, selector, validator_hash, created_on, previous_hash, rotated_at) values (?, 'CCCCCCCCCCCCCCCCCCCCCC', ?, current_date, ?, now())", owner, ByteArray(32), ByteArray(31)) })
        assertEquals("remembered_device_rotation_together", refusedBy { dsl.execute("insert into remembered_device (account_id, selector, validator_hash, created_on, previous_hash) values (?, 'CCCCCCCCCCCCCCCCCCCCCC', ?, current_date, ?)", owner, ByteArray(32), ByteArray(32)) })
        assertEquals("remembered_device_rotation_together", refusedBy { dsl.execute("insert into remembered_device (account_id, selector, validator_hash, created_on, rotated_at) values (?, 'CCCCCCCCCCCCCCCCCCCCCC', ?, current_date, now())", owner, ByteArray(32)) })
        assertEquals("remembered_device_selector_format", refusedBy { device(owner, selector = "short") })
        assertEquals("remembered_device_selector_format", refusedBy { device(owner, selector = "AAAAAAAAAAAAAAAAAAAA==") })
        assertEquals("remembered_device_selector_format", refusedBy { device(owner, selector = "AAAAAAAAAAAAAAAAAAAA A") })
        assertEquals(1, device(owner, selector = "BBBBBBBBBBBBBBBBBBBBBB"))
        assertEquals("remembered_device_selector_unique", refusedBy { device(owner, selector = "BBBBBBBBBBBBBBBBBBBBBB") })
        assertEquals("remembered_device_account_id_fkey", refusedBy { device(Long.MAX_VALUE, selector = "CCCCCCCCCCCCCCCCCCCCCC") })
    }

    // ---- The passkeys ------------------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c21 a passkey has the credential, the public key, the counter, the flags, the transports and the day, and no attestation, label, model or last use`() {
        assertEquals(
            mapOf(
                "credential_id" to ("bytea" to "NO"), "account_id" to ("bigint" to "NO"), "public_key" to ("bytea" to "NO"), "signature_count" to ("bigint" to "NO"),
                "uv_initialized" to ("boolean" to "NO"), "backup_eligible" to ("boolean" to "NO"), "backup_state" to ("boolean" to "NO"), "transports" to ("text" to "YES"),
                "created_on" to ("date" to "NO"),
            ),
            columns("passkey"),
        )
    }

    @Test
    fun `t15 c36 the table of the passkeys refuses a credential of the wrong size, a counter below zero, a synchronized passkey that cannot be synchronized and the same credential twice`() {
        val owner = account()

        assertEquals("passkey_credential_id_size", refusedBy { passkey(owner, id = ByteArray(15)) })
        assertEquals("passkey_credential_id_size", refusedBy { passkey(owner, id = ByteArray(1024)) })
        assertEquals("passkey_signature_count_not_negative", refusedBy { passkey(owner, count = -1) })
        assertEquals("passkey_public_key_not_empty", refusedBy { passkey(owner, key = ByteArray(0)) })
        assertEquals("passkey_backup_state_needs_eligible", refusedBy { passkey(owner, backupEligible = false, backupState = true) })
        assertEquals(1, passkey(owner, id = ByteArray(16) { 3 }))
        assertEquals(1, passkey(owner, id = ByteArray(1023) { 4 }), "the longest credential that WebAuthn allows")
        assertTrue(refusedBy { passkey(owner, id = ByteArray(16) { 3 }) }.contains("passkey_pkey"))
    }

    // ---- No cascade (criterion 31) -------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c31 the keys to the account have no cascade, so an account with a device or a passkey is not deleted by a mistake`() {
        val withDevice = account()
        device(withDevice, selector = "DDDDDDDDDDDDDDDDDDDDDD")
        val withPasskey = account()
        passkey(withPasskey, id = ByteArray(32) { 9 })

        assertEquals("remembered_device_account_id_fkey", refusedBy { dsl.execute("delete from account where id = ?", withDevice) })
        assertEquals("passkey_account_id_fkey", refusedBy { dsl.execute("delete from account where id = ?", withPasskey) })

        dsl.execute("delete from remembered_device where account_id = ?", withDevice)
        dsl.execute("delete from passkey where account_id = ?", withPasskey)
        assertEquals(1, dsl.execute("delete from account where id = ?", withDevice))
        assertEquals(1, dsl.execute("delete from account where id = ?", withPasskey))
        for ((table, column) in listOf("remembered_device" to "account_id", "passkey" to "account_id")) {
            val rule = dsl.fetchOne(
                "select confdeltype from pg_constraint where conrelid = ?::regclass and contype = 'f' and conkey = (select array_agg(attnum) from pg_attribute where attrelid = ?::regclass and attname = ?)",
                table, table, column,
            )!!.get(0, String::class.java)
            assertEquals("a", rule, "$table: no action")
        }
    }

    // ---- One class per table -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c31 only one class of the application uses the table of the devices and one the table of the passkeys`() {
        val sources = Files.walk(Path.of("src/main/kotlin")).use { stream -> stream.filter { Files.isRegularFile(it) }.toList() }

        fun users(table: String, record: String): List<String> = sources.filter {
            val text = Files.readString(it)
            text.contains("references.$table") || Regex("""tables\.(records\.)?$record""").containsMatchIn(text) || text.contains("talita.jooq.tables.$record")
        }.map { it.fileName.toString() }

        assertEquals(listOf("RememberedDevices.kt"), users("REMEMBERED_DEVICE", "RememberedDevice"), "the devices are read and written by one class, the one that knows what a row is")
        assertEquals(listOf("Passkeys.kt"), users("PASSKEY", "Passkey"), "the passkeys are read and written by one class, which is also the repository that Spring asks for")
    }

    // ---- The migration itself --------------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c36 the migration is V12, in the file of the devices and passkeys, and adds no extension`() {
        val script = Files.readString(Path.of("src/main/resources/db/migration/V12__devices_and_passkeys.sql"))

        assertFalse(Regex("""(?i)create\s+extension""").containsMatchIn(script), "the only extension of the project is unaccent, in V1")
        assertEquals(
            "12",
            dsl.resultQuery("select version from flyway_schema_history where script = 'V12__devices_and_passkeys.sql' and success").fetchSingle(0, String::class.java),
        )
        // The addresses of the tests are never in a migration (the repository is public).
        assertTrue(!script.contains(Addresses.of("")), "an address in the migration")
    }
}
