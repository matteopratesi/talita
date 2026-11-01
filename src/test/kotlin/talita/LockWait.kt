package talita

import org.jooq.DSLContext
import java.time.Duration
import java.util.concurrent.Future
import kotlin.test.fail

/**
 * Waits until a task is really blocked on a lock that another transaction holds, for the tests that prove that two transactions wait for each other (row locks, the lock of a
 * transaction, advisory locks: Postgres reports all of them as `wait_event_type = 'Lock'`).
 *
 * A pause of a fixed time (`Thread.sleep(700)` followed by `assertFalse(task.isDone)`) proves nothing on a slow runner: the task may not have reached the database yet, and then the
 * test passes without having tried what it says, or it lets the lock go too early. Here nothing depends on how long the task takes to start: the database is asked every
 * [POLL], for [MAX_WAIT] at most. The test fails, with a message that says what it expected, if the task finishes without ever having waited, or if the time runs out.
 *
 * A backend that was waiting already when the task was started (what a test that failed has left behind) does not count: only a wait that begins after [start] is called is the proof.
 * The tests run one at a time, so a new wait in the database of the test is the wait of the task.
 *
 * It is in `src/test` and the e2e suite sees it too: the end-to-end tests that need a request to wait for a lock use the same helper (`RepositoryRulesTests` allows no other wait).
 */
object LockWait {

    val MAX_WAIT: Duration = Duration.ofSeconds(30)
    val POLL: Duration = Duration.ofMillis(20)

    /**
     * Starts the task with [start], which submits it and returns its [Future], and returns the same [Future] when the task is blocked on a lock. [what] is what the test says
     * should happen ("the second copy should wait for the first").
     *
     * Without more, any new wait for a lock in the database is the task's (the tests run one at a time). A test that has other transactions around can say which wait it means: the one
     * that is blocked by the backend [blockedBy] (`pg_blocking_pids`: the transaction that the test keeps open, from `select pg_backend_pid()`) and, with [statementLike], whose statement
     * has this piece of text (a case-insensitive `like` on the text of the SQL, and not a name that the test chose).
     */
    fun <T : Future<*>> awaitBlocked(dsl: DSLContext, what: String, maxWait: Duration = MAX_WAIT, blockedBy: Int? = null, statementLike: String? = null, start: () -> T): T {
        var task: T? = null
        awaitBlocked(dsl, what, maxWait, blockedBy, statementLike, finished = { task?.takeIf { it.isDone }?.let(::outcome) }) { task = start() }
        return task!!
    }

    /**
     * The same for a task that is not a [Future] (a request of the browser, for example): [start] begins it, and [finished] says how it ended when it has ended (`null` while it
     * goes on). The test fails if [finished] says something before a lock is waited for.
     */
    fun awaitBlocked(dsl: DSLContext, what: String, maxWait: Duration = MAX_WAIT, blockedBy: Int? = null, statementLike: String? = null, finished: () -> String?, start: () -> Unit) {
        // Taken before the task starts: a wait that is there now is not the task's.
        val before = waiting(dsl, blockedBy, statementLike)
        start()
        val deadline = System.nanoTime() + maxWait.toNanos()
        while (true) {
            if ((waiting(dsl, blockedBy, statementLike) - before).isNotEmpty()) return
            finished()?.let { fail("$what: but the task has finished ($it) and was never blocked") }
            if (System.nanoTime() > deadline) fail("$what: but no transaction waited for a lock in ${maxWait.toSeconds()} seconds and the task has not finished")
            Thread.sleep(POLL.toMillis())
        }
    }

    /** How a finished task ended, for the message of a failure: its result, or what it failed with. */
    private fun outcome(task: Future<*>): String = runCatching { task.get() }.fold({ "result $it" }, { "failed with ${it.cause ?: it}" })

    /**
     * The backends of this database that wait for a lock, except the one that asks. A statement that waits is identified by the backend and the moment its statement began, so a
     * connection of the pool that waits again after it was reused is a new wait and not the old one.
     */
    private fun waiting(dsl: DSLContext, blockedBy: Int?, statementLike: String?): Set<String> {
        // Postgres reads pg_stat_activity once for each transaction and keeps it until the transaction ends: asked from inside a transaction (a test that holds the lock with its own),
        // the second look would show the first one again, forever. Discarding the copy makes every look a new one.
        dsl.fetch("select pg_stat_clear_snapshot()")
        val conditions = StringBuilder("datname = current_database() and pid <> pg_backend_pid() and wait_event_type = 'Lock' and state = 'active'")
        val binds = mutableListOf<Any>()
        if (blockedBy != null) {
            conditions.append(" and ? = any(pg_blocking_pids(pid))")
            binds += blockedBy
        }
        if (statementLike != null) {
            conditions.append(" and query ilike ?")
            binds += "%$statementLike%"
        }
        return dsl.fetch("select pid || '@' || coalesce(query_start::text, '') from pg_stat_activity where $conditions", *binds.toTypedArray())
            .map { it.get(0, String::class.java) }.toSet()
    }
}
