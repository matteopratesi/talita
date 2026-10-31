package talita.checkin

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.LockWait
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.PAYMENT
import talita.jooq.tables.references.REGISTRATION
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The schema of the check-in (V6): what the database refuses by itself, whatever the application does (criterion 32). The application checks the same things
 * first and with kinder words; this is the net under it, and the proof that two teachers at the same moment leave one row. Names and amounts are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class CheckinSchemaTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    /** The name of the constraint that refused (or the text of the refusal when it has no name). */
    private fun refusedBy(block: () -> Unit): String {
        val failure = assertFailsWith<Exception> { block() }
        val sql = generateSequence<Throwable>(failure) { it.cause?.takeIf { cause -> cause !== it } }.filterIsInstance<SQLException>().first()
        return Regex("""constraint "([^"]+)"""").find(sql.message.orEmpty())?.groupValues?.get(1) ?: sql.message.orEmpty()
    }

    private fun insertPayment(registration: Long, cents: Int, by: Long, request: UUID = UUID.randomUUID()) =
        dsl.insertInto(PAYMENT).set(PAYMENT.REGISTRATION_ID, registration).set(PAYMENT.AMOUNT_CENTS, cents).set(PAYMENT.RECORDED_AT, OffsetDateTime.now(clock))
            .set(PAYMENT.RECORDED_BY_ACCOUNT_ID, by).set(PAYMENT.REQUEST_ID, request).execute()

    @Test
    fun `t6 c32 the database keeps one presence for each registration and day, and two teachers who write the same one at the same moment leave one row`() {
        val setup = kit.start()
        val registration = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")

        kit.presence(registration, setup.today, setup.martaId)
        assertEquals("checkin_pkey", refusedBy { kit.presence(registration, setup.today, setup.paoloId) })
        // The same registration on another day, and another registration on the same day, are other pairs.
        kit.presence(registration, setup.tomorrow, setup.martaId)
        kit.presence(kit.rows.register(setup.id, kit.account(), "Anna", "Rossi"), setup.today, setup.martaId)
        assertEquals(3, kit.presenceCount())

        // Two transactions that insert the same pair at the same moment: the second waits for the first, and with `on conflict do nothing` leaves it as it is.
        val other = kit.rows.register(setup.id, kit.account(), "Sara", "Neri")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writer = pool.submit<Int> {
                dsl.transactionResult { configuration ->
                    val tx = DSL.using(configuration)
                    val written = insert(tx, other, setup.today, setup.martaId)
                    first.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    written
                }
            }
            assertTrue(first.await(10, TimeUnit.SECONDS))
            val second = LockWait.awaitBlocked(dsl, "the second insert should wait for the first, which has not ended") {
                pool.submit<Int> { dsl.transactionResult { configuration -> insert(DSL.using(configuration), other, setup.today, setup.paoloId) } }
            }
            release.countDown()
            assertEquals(1, writer.get(20, TimeUnit.SECONDS))
            assertEquals(0, second.get(20, TimeUnit.SECONDS), "the second finds the pair there and writes nothing")
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, dsl.fetchCount(CHECKIN, CHECKIN.REGISTRATION_ID.eq(other)))
        assertEquals(setup.martaId, dsl.select(CHECKIN.RECORDED_BY_ACCOUNT_ID).from(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(other)).fetchSingle(CHECKIN.RECORDED_BY_ACCOUNT_ID))
    }

    private fun insert(tx: DSLContext, registration: Long, day: java.time.LocalDate, by: Long): Int =
        tx.insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, registration).set(CHECKIN.DAY, day).set(CHECKIN.RECORDED_AT, OffsetDateTime.now(clock))
            .set(CHECKIN.RECORDED_BY_ACCOUNT_ID, by).onConflict(CHECKIN.REGISTRATION_ID, CHECKIN.DAY).doNothing().execute()

    @Test
    fun `t6 c32 a presence needs its registration and the moment, and the teacher who marked it if there is one`() {
        val setup = kit.start()
        val registration = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")

        assertEquals("checkin_registration_id_fkey", refusedBy { kit.presence(registration + 1000, setup.today, setup.martaId) })
        assertEquals("checkin_recorded_by_account_id_fkey", refusedBy { kit.presence(registration, setup.today, -1) })
        // TALITA-7: the teacher is optional in the row, because the account of the teacher can be deleted and the presence stays.
        dsl.insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, registration).set(CHECKIN.DAY, setup.today).set(CHECKIN.RECORDED_AT, OffsetDateTime.now(clock)).execute()
        assertEquals(1, kit.presenceCount())
        dsl.deleteFrom(CHECKIN).execute()
        assertTrue(
            refusedBy {
                dsl.insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, registration).set(CHECKIN.DAY, setup.today).set(CHECKIN.RECORDED_BY_ACCOUNT_ID, setup.martaId).execute()
            }.contains("recorded_at"),
        )
        assertEquals(0, kit.presenceCount())
    }

    @Test
    fun `t6 c32 a payment is from one cent to 1000 euros, and has its registration, its teacher and a request code that nobody else has`() {
        val setup = kit.start()
        val registration = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")

        assertEquals("payment_amount_cents_check", refusedBy { insertPayment(registration, 0, setup.martaId) })
        assertEquals("payment_amount_cents_check", refusedBy { insertPayment(registration, -500, setup.martaId) })
        assertEquals("payment_amount_cents_check", refusedBy { insertPayment(registration, 100_001, setup.martaId) })
        insertPayment(registration, 1, setup.martaId)
        insertPayment(registration, 100_000, setup.martaId)
        assertEquals(2, kit.paymentCount())

        assertEquals("payment_registration_id_fkey", refusedBy { insertPayment(registration + 1000, 2500, setup.martaId) })
        assertEquals("payment_recorded_by_account_id_fkey", refusedBy { insertPayment(registration, 2500, -1) })
        val request = UUID.randomUUID()
        insertPayment(registration, 2500, setup.martaId, request)
        assertEquals("payment_request_id_key", refusedBy { insertPayment(registration, 2500, setup.paoloId, request) })
        assertTrue(
            refusedBy {
                dsl.insertInto(PAYMENT).set(PAYMENT.REGISTRATION_ID, registration).set(PAYMENT.AMOUNT_CENTS, 2500).set(PAYMENT.RECORDED_AT, OffsetDateTime.now(clock))
                    .set(PAYMENT.RECORDED_BY_ACCOUNT_ID, setup.martaId).execute()
            }.contains("request_id"),
        )
        assertEquals(3, kit.paymentCount())
    }

    @Test
    fun `t6 c32 presences and payments go with their registration, and when the teacher who marked them is deleted they stay and have no teacher`() {
        val setup = kit.start()
        val registration = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")
        kit.presence(registration, setup.today, setup.martaId)
        kit.payment(registration, 2500, setup.paoloId)

        // TALITA-7: the key to the teacher is `on delete set null`. A teacher has to stop being one before the account goes (the teacher row cascades from the account).
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(setup.martaId)).execute()
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(setup.paoloId)).execute()
        assertEquals(1, kit.presenceCount())
        assertEquals(1, kit.paymentCount())
        assertNull(dsl.select(CHECKIN.RECORDED_BY_ACCOUNT_ID).from(CHECKIN).fetchSingle(CHECKIN.RECORDED_BY_ACCOUNT_ID))
        assertNull(dsl.select(PAYMENT.RECORDED_BY_ACCOUNT_ID).from(PAYMENT).fetchSingle(PAYMENT.RECORDED_BY_ACCOUNT_ID))

        dsl.deleteFrom(REGISTRATION).where(REGISTRATION.ID.eq(registration)).execute()
        assertEquals(0, kit.presenceCount())
        assertEquals(0, kit.paymentCount())
    }
}
