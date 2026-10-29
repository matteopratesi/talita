package talita.reminder

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.TestcontainersConfiguration
import talita.account.Accounts
import talita.account.EmailAddress
import talita.gathering.PublicCodes
import talita.jooq.enums.ReminderKind
import talita.jooq.tables.references.ACCOUNT
import talita.retention.AccountErasure
import talita.retention.ErasureService
import java.time.Clock
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deletion of an account (TALITA-7) takes with it what TALITA-13 keeps about the reminders (criterion 13): the consent to the avviso, the stop and the code are columns of the account and go
 * with it; the register of the sends stays with the gathering, because it has nothing of a person; whoever comes back with the same address is a new account, with no stop and no avviso.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ReminderErasureTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val erasures: ErasureService,
    @Autowired private val accounts: Accounts,
) {

    private val kit = ReminderTestKit(dsl, clock)

    @BeforeEach
    fun clean() = kit.reset()

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    @Test
    fun `t13 c13 an account that is deleted takes the consent, the stop and the code with it, and the register of the sends stays with the gathering`() {
        val s = kit.scenario()
        kit.batch(s.gathering, ReminderKind.notice, 8, recipients = 1)
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDER_CODE, PublicCodes.next()).where(ACCOUNT.ID.eq(s.b.id)).execute()
        assertTrue(kit.consentOf(s.b.id) != null && kit.codeOf(s.b.id) != null)

        val result = erasures.eraseAccount(s.b.id)

        assertIs<AccountErasure.Done>(result)
        assertEquals(false, kit.exists(s.b.id), "the row of the account is gone, with its three columns")
        assertEquals(0, dsl.fetchCount(ACCOUNT, ACCOUNT.REMINDER_CODE.isNotNull.and(ACCOUNT.ID.eq(s.b.id))))
        assertEquals(1, kit.batches(s.gathering).size, "the register has nothing of a person, and stays with the gathering")
        assertEquals(1, kit.batches(s.gathering).single().recipients?.toInt())
        // The other accounts are not touched.
        assertTrue(kit.exists(s.a.id) && kit.exists(s.d.id))
    }

    @Test
    fun `t13 c13 an empty account that stopped goes with its stop and its code, as every empty account does`() {
        val s = kit.scenario()
        val empty = kit.account("vuoto-fermo", stopped = now(), code = PublicCodes.next())
        assertTrue(kit.stopOf(empty.id) != null && kit.codeOf(empty.id) != null)

        assertIs<AccountErasure.Done>(erasures.eraseAccount(empty.id))

        assertEquals(false, kit.exists(empty.id))
        assertTrue(kit.exists(s.c.id))
    }

    @Test
    fun `t13 c13 whoever comes back with the same address is a new account, with no stop, no avviso and no code`() {
        val s = kit.scenario()
        val stopped = kit.account("rientra", stopped = now(), code = PublicCodes.next())
        assertIs<AccountErasure.Done>(erasures.eraseAccount(stopped.id))

        val again = accounts.findOrCreate(EmailAddress.parse(stopped.email)!!)

        assertNotEquals(stopped.id, again, "a new account")
        assertNull(kit.stopOf(again), "nobody remembers that they stopped")
        assertNull(kit.consentOf(again))
        assertNull(kit.codeOf(again))
        // B kept the avviso and A the box unticked, as they were.
        assertTrue(kit.consentOf(s.b.id) != null)
        assertNull(kit.consentOf(s.a.id))
    }
}
