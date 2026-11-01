package talita

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The helper that waits for a lock (TALITA-16, criteria 1 and 2) on a Postgres that is real: a task that does not block fails at once, with the message of the test and how the
 * task ended; one that blocks makes the helper return as soon as it waits, not when the maximum time has passed; a wait that was there before does not count as the proof.
 * The locks are advisory ones with numbers that only this class uses.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class LockWaitTests(
    @Autowired private val dsl: DSLContext,
) {

    /** A transaction that takes the advisory lock [key] and keeps it until [release] is counted down: [held] says that it has the lock. */
    private fun hold(pool: ExecutorService, key: Long, held: CountDownLatch, release: CountDownLatch): Future<*> = pool.submit {
        dsl.transaction { configuration ->
            DSL.using(configuration).fetch("select pg_advisory_xact_lock(?)", key)
            held.countDown()
            release.await(30, TimeUnit.SECONDS)
        }
    }

    private fun waitFor(pool: ExecutorService, key: Long): Future<Int> = pool.submit<Int> {
        dsl.transactionResult { configuration ->
            DSL.using(configuration).fetch("select pg_advisory_xact_lock(?)", key)
            1
        }
    }

    @Test
    fun `t16 c02 a task that does not block fails at once with the message of the test and the result of the task`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val failure = assertFailsWith<AssertionError> { LockWait.awaitBlocked(dsl, "the second copy should wait for the first") { pool.submit<Int> { 42 } } }
            val message = failure.message!!
            assertTrue(message.startsWith("the second copy should wait for the first: "), message)
            assertTrue(message.contains("and was never blocked"), message)
            assertTrue(message.contains("result 42"), message)
            assertFalse(message.contains("seconds"), "it failed because the task had ended, not because the time was over: $message")

            val failed = assertFailsWith<AssertionError> { LockWait.awaitBlocked(dsl, "the withdrawal should wait") { pool.submit<Int> { error("synthetic failure") } } }
            val failedMessage = failed.message!!
            assertTrue(failedMessage.contains("the withdrawal should wait"), failedMessage)
            assertTrue(failedMessage.contains("failed with java.lang.IllegalStateException: synthetic failure"), failedMessage)
            assertTrue(failedMessage.contains("was never blocked"), failedMessage)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t16 c02 a task that blocks on a lock makes the helper return as soon as it waits, and not when the maximum time has passed`() {
        val pool = Executors.newFixedThreadPool(3)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val holder = hold(pool, 16_001, held, release)
            assertTrue(held.await(30, TimeUnit.SECONDS))

            val maximum = Duration.ofSeconds(20)
            val began = System.nanoTime()
            val task = LockWait.awaitBlocked(dsl, "the task should wait for the holder", maximum) { waitFor(pool, 16_001) }
            val took = Duration.ofNanos(System.nanoTime() - began)

            assertFalse(task.isDone, "it waits for the lock: it is not finished")
            assertTrue(took < maximum.dividedBy(2), "it returned when the task was waiting, not at the end of the maximum time: $took")
            release.countDown()
            holder.get(30, TimeUnit.SECONDS)
            assertEquals(1, task.get(30, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `t16 c01 a wait that was there before the task started does not count as the proof, and the wait of the task does`() {
        val pool = Executors.newFixedThreadPool(5)
        val heldA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val heldB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        try {
            val holderA = hold(pool, 16_002, heldA, releaseA)
            assertTrue(heldA.await(30, TimeUnit.SECONDS))
            // What a test that failed leaves behind: a transaction that waits for a lock that nobody lets go of.
            val leftover = LockWait.awaitBlocked(dsl, "the leftover should wait for the first holder") { waitFor(pool, 16_002) }

            // A task that does not block is not saved by the leftover that waits: it fails, with its message.
            val failure = assertFailsWith<AssertionError> { LockWait.awaitBlocked(dsl, "the task should wait") { pool.submit<Int> { 7 } } }
            assertTrue(failure.message!!.contains("the task should wait") && failure.message!!.contains("was never blocked"), failure.message)

            // A task that blocks on another lock is the proof, with the leftover still there.
            val holderB = hold(pool, 16_003, heldB, releaseB)
            assertTrue(heldB.await(30, TimeUnit.SECONDS))
            val task = LockWait.awaitBlocked(dsl, "the task should wait for the second holder") { waitFor(pool, 16_003) }
            assertFalse(task.isDone)
            assertFalse(leftover.isDone)

            releaseB.countDown()
            holderB.get(30, TimeUnit.SECONDS)
            assertEquals(1, task.get(30, TimeUnit.SECONDS))
            releaseA.countDown()
            holderA.get(30, TimeUnit.SECONDS)
            assertEquals(1, leftover.get(30, TimeUnit.SECONDS))
        } finally {
            releaseA.countDown()
            releaseB.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `t16 c01 the helper sees the wait also when the test holds the lock with a transaction of its own, which keeps the view of the database that it had at the start`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val task = dsl.transactionResult { configuration ->
                DSL.using(configuration).fetch("select pg_advisory_xact_lock(?)", 16_004L)
                val waiting = LockWait.awaitBlocked(dsl, "the task should wait for the transaction of the test") { waitFor(pool, 16_004) }
                assertFalse(waiting.isDone)
                waiting
            }
            assertEquals(1, task.get(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t16 c01 the wait that is meant can be told by the backend that blocks it and by the text of its statement, and another wait is not the proof`() {
        val pool = Executors.newFixedThreadPool(5)
        val heldA = CountDownLatch(1)
        val releaseA = CountDownLatch(1)
        val heldB = CountDownLatch(1)
        val releaseB = CountDownLatch(1)
        val pidOfB = java.util.concurrent.atomic.AtomicInteger()
        try {
            hold(pool, 16_005, heldA, releaseA)
            // The second holder says its backend, as the tests of the erasure do with the transaction that they keep open.
            pool.submit {
                dsl.transaction { configuration ->
                    pidOfB.set(DSL.using(configuration).fetchOne("select pg_backend_pid()")!!.get(0, Int::class.java))
                    DSL.using(configuration).fetch("select pg_advisory_xact_lock(?)", 16_006L)
                    heldB.countDown()
                    releaseB.await(30, TimeUnit.SECONDS)
                }
            }
            assertTrue(heldA.await(30, TimeUnit.SECONDS) && heldB.await(30, TimeUnit.SECONDS))

            // A task that waits for the lock of A is not blocked by B: with the backend of B the helper does not take it for the proof.
            val wrongBackend = assertFailsWith<AssertionError> {
                LockWait.awaitBlocked(dsl, "the task should wait for B", Duration.ofSeconds(1), blockedBy = pidOfB.get()) { waitFor(pool, 16_005) }
            }
            assertEquals("the task should wait for B: but no transaction waited for a lock in 1 seconds and the task has not finished", wrongBackend.message)
            // A text that its statement does not have is not it either; one that it has is.
            val wrongText = assertFailsWith<AssertionError> {
                LockWait.awaitBlocked(dsl, "the task should wait", Duration.ofSeconds(1), statementLike = "a text that no statement has") { waitFor(pool, 16_006) }
            }
            assertTrue(wrongText.message!!.contains("no transaction waited for a lock in 1 seconds"), wrongText.message)
            val task = LockWait.awaitBlocked(dsl, "the task should wait for B", blockedBy = pidOfB.get(), statementLike = "PG_ADVISORY_XACT_LOCK") { waitFor(pool, 16_006) }
            assertFalse(task.isDone)

            releaseA.countDown()
            releaseB.countDown()
            assertEquals(1, task.get(30, TimeUnit.SECONDS))
        } finally {
            releaseA.countDown()
            releaseB.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `t16 c02 a task that neither waits for a lock nor ends fails when the maximum time has passed, and says so`() {
        val pool = Executors.newFixedThreadPool(2)
        val never = CountDownLatch(1)
        try {
            val failure = assertFailsWith<AssertionError> {
                LockWait.awaitBlocked(dsl, "the task should wait for a lock", Duration.ofSeconds(1)) { pool.submit<Boolean> { never.await(30, TimeUnit.SECONDS) } }
            }
            assertEquals("the task should wait for a lock: but no transaction waited for a lock in 1 seconds and the task has not finished", failure.message)
        } finally {
            never.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `t16 c02 a task that is not a future is followed through what it says about its end`() {
        val pool = Executors.newFixedThreadPool(2)
        val done = AtomicBoolean(false)
        try {
            val failure = assertFailsWith<AssertionError> {
                LockWait.awaitBlocked(dsl, "the request should wait", finished = { if (done.get()) "status 200" else null }) { pool.submit { done.set(true) } }
            }
            val message = failure.message!!
            assertTrue(message.contains("the request should wait") && message.contains("status 200") && message.contains("was never blocked"), message)
        } finally {
            pool.shutdownNow()
        }
    }
}
