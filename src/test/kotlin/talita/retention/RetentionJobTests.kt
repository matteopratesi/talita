package talita.retention

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import talita.ClockConfiguration
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.RETENTION_LOG
import talita.registration.Notes
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The job (criteria 11-15): when it runs, that two runs at the same moment delete every row once and write the register once, that a rule that fails does not
 * stop the others and leaves nothing of itself, and what it says in the register and in the log. The database is real, the clock is fixed at a moment of Rome.
 * Names, dates and addresses are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ExtendWith(OutputCaptureExtension::class)
class RetentionJobTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val job: RetentionJob,
    @Autowired private val rules: List<RetentionRule>,
) {

    private val kit = RetentionTestKit(dsl, clock)

    private val notes = Notes("Arachidi", null, "Antistaminico")

    @BeforeEach
    fun clean() {
        kit.reset()
        kit.removeAccountsWithoutRole()
    }

    /** A finished gathering of 2027 with a parent, three children and notes: what the job has to delete a year after. */
    private fun oldData(children: Int = 3): Long {
        val gathering = kit.september2027()
        val parent = kit.account("genitore-a")
        repeat(children) { kit.registration(gathering, parent, "Luca$it", "Rossi", notes = notes) }
        return gathering
    }

    // ---- The rules in order, and the register (criteria 14, 15) -------------------------------------------------------------------

    @Test
    fun `t7 c14 the rules are in the order notes, rooms, registrations, children, invitations, accounts, then the requests to make again and the numbers to take out`() {
        // The rooms of TALITA-11 are a rule more, between the notes and the registrations: nothing else in the job changed.
        assertEquals(
            listOf("notes", "rooms", "registrations", "children", "invitations", "accounts"),
            rules.map { it.name }.take(6),
        )
    }

    @Test
    fun `t7 c15 for each rule that deleted something there is a row of the register and a line of the log, with numbers only`(output: CapturedOutput) {
        oldData(children = 2)

        val report = job.run(kit.clockAt("2028-09-20T04:15"))

        val expected = listOf("notes" to 2, "registrations" to 2, "children" to 2, "accounts" to 1)
        assertEquals(expected, kit.log())
        assertEquals(expected.map { Deleted(it.first, it.second) }, report.deleted)
        val lines = output.all.lines().filter { it.contains("Retention: rule") }.map { it.substringAfter(": ") }
        assertEquals(expected.map { "Retention: rule ${it.first} deleted ${it.second} rows." }, lines)
        // Nothing of a person: no name, no address, no date of birth, no phone, no note.
        for (word in listOf("Luca", "Rossi", "Arachidi", "Antistaminico", "example.org", "2016", "333 123 4567")) {
            assertFalse(lines.any { it.contains(word) }, "the log says $word: $lines")
        }
        // A run on the same day finds nothing: no row, no line.
        val again = job.run(kit.clockAt("2028-09-20T04:30"))
        assertEquals(emptyList(), again.deleted)
        assertEquals(expected, kit.log())
    }

    @Test
    fun `t7 c15 the register holds a moment, a rule and a number of rows and nothing else, apart from the number of a request`() {
        assertEquals(
            setOf("id", "done_at", "rule", "row_count", "target_id"),
            dsl.meta().getTables("retention_log").single().fields().map { it.name }.toSet(),
        )
        oldData(children = 1)
        job.run(kit.clockAt("2028-09-20T04:15"))
        assertTrue(dsl.select(RETENTION_LOG.TARGET_ID).from(RETENTION_LOG).fetch(RETENTION_LOG.TARGET_ID).all { it == null }, "the rules by time never write the number of a person")
    }

    // ---- A rule that fails (criterion 14) --------------------------------------------------------------------------------------------

    /** A rule that deletes a row and then fails, with a message that carries a name: it must leave nothing, and the log must not repeat the message. */
    private class Failing(override val name: String = "boom") : RetentionRule {
        var fail = true

        override fun apply(tx: DSLContext, context: RetentionContext): List<Deleted> {
            val rows = tx.deleteFrom(CHILD).execute()
            if (fail) throw IllegalStateException("could not delete the child Luca Rossi born on 2016-04-04 of nome@example.org")
            return listOf(Deleted("children", rows))
        }
    }

    @Test
    fun `t7 c14 a rule that fails goes back and leaves nothing, the other rules run, the log has an ERROR without data, and the next run tries again`(output: CapturedOutput) {
        oldData(children = 2)
        val failing = Failing()
        val ordered = rules.filter { it.name in setOf("notes", "registrations") } + failing + rules.filter { it.name == "accounts" }
        val broken = RetentionJob(dsl, clock, ordered)

        val report = broken.run(kit.clockAt("2028-09-20T04:15"))

        assertEquals(listOf("boom"), report.failed)
        assertFalse(report.succeeded)
        assertEquals(0, kit.notes(), "the rule before it ran")
        assertEquals(0, kit.registrations(), "so did the one after the failing one's turn: the order goes on")
        assertEquals(2, kit.children(), "the failing rule deleted the children and went back: nothing of it was kept")
        assertEquals(listOf("notes" to 2, "registrations" to 2), kit.log(), "the account still has its children, so it is not empty and stays")
        val errors = output.all.lines().filter { it.contains(" ERROR ") && it.contains("Retention: rule") }
        assertEquals(1, errors.size)
        assertTrue(errors.single().contains("Retention: rule boom failed (IllegalStateException)"), errors.single())
        for (word in listOf("Luca", "Rossi", "2016", "example.org")) {
            assertFalse(output.all.contains("could not delete the child") || errors.any { it.contains(word) }, "the log repeats the message of the exception: $errors")
        }

        // The application is still there and the next run tries again: this time it works.
        failing.fail = false
        val next = broken.run(kit.clockAt("2028-09-21T04:15"))
        assertTrue(next.succeeded)
        assertEquals(0, kit.children())
        assertEquals(listOf(Deleted("children", 2), Deleted("accounts", 1)), next.deleted)
    }

    // ---- Two runs at the same moment (criterion 13) ----------------------------------------------------------------------------------

    @Test
    fun `t7 c13 two runs at the same moment delete every row once, fail at nothing and write the register once, and a third run finds nothing`() {
        oldData(children = 40)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val barrier = CyclicBarrier(2)
            val at = kit.clockAt("2028-09-20T04:15")
            val runs = (1..2).map { pool.submit<RetentionReport> { barrier.await(10, TimeUnit.SECONDS); job.run(at) } }
            val reports = runs.map { it.get(60, TimeUnit.SECONDS) }

            assertTrue(reports.all { it.succeeded }, "nothing goes wrong: $reports")
            // Each row went once: what the two runs report, together, is what there was.
            assertEquals(40, reports.sumOf { r -> r.deleted.filter { it.rule == "notes" }.sumOf { it.rows } })
            assertEquals(40, reports.sumOf { r -> r.deleted.filter { it.rule == "registrations" }.sumOf { it.rows } })
            assertEquals(40, reports.sumOf { r -> r.deleted.filter { it.rule == "children" }.sumOf { it.rows } })
            assertEquals(0, kit.registrations())
            assertEquals(0, kit.children())
            // The register does not count twice: one row for each rule.
            assertEquals(listOf("notes" to 40, "registrations" to 40, "children" to 40, "accounts" to 1), kit.log())
            assertEquals(emptyList(), job.run(at).deleted, "run again at once, it deletes nothing")
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t7 c13 a rule takes the advisory lock of its name, so a second run waits for the first one`() {
        oldData(children = 1)
        val pool = Executors.newFixedThreadPool(1)
        try {
            val holding = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            // Another transaction holds the lock of the rule «notes»: whoever runs the job has to wait.
            val holder = pool.submit {
                dsl.transaction { configuration ->
                    DSL.using(configuration).fetch("select pg_advisory_xact_lock(?)", RetentionJob.lockKey("notes"))
                    holding.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            val waiting = Executors.newSingleThreadExecutor().submit<RetentionReport> { job.run(kit.clockAt("2027-10-20T04:15")) }
            Thread.sleep(700)
            assertFalse(waiting.isDone, "the run waits for the lock of «notes»")
            assertEquals(1, kit.notes())
            release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            val report = waiting.get(30, TimeUnit.SECONDS)
            assertEquals(0, kit.notes())
            assertEquals(listOf(Deleted("notes", 1)), report.deleted.filter { it.rule == "notes" })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t7 c13 the key of a lock does not change from a run to another or from a machine to another`() {
        assertEquals(RetentionJob.lockKey("notes"), RetentionJob.lockKey("notes"))
        assertEquals("talita.retention.notes".hashCode().toLong(), RetentionJob.lockKey("notes"))
        assertEquals(rules.map { it.name }.toSet().size, rules.map { RetentionJob.lockKey(it.name) }.toSet().size, "every rule has a lock of its own")
    }

    // ---- When it runs (criteria 11, 12) -----------------------------------------------------------------------------------------------

    @Test
    fun `t7 c12 the cron is one run at 4_15 of Rome, also on the day when the clocks go forward and on the one when they go back`() {
        val method = RetentionSchedule::class.java.getMethod("everyNight")
        val scheduled = assertNotNull(method.getAnnotation(Scheduled::class.java))
        assertEquals("0 15 4 * * *", scheduled.cron)
        assertEquals("Europe/Rome", scheduled.zone)
        val cron = CronExpression.parse(scheduled.cron)
        val rome = ClockConfiguration.ZONE

        for (day in listOf(LocalDate.of(2027, 3, 28), LocalDate.of(2027, 10, 31), LocalDate.of(2027, 7, 14))) {
            val start = day.atStartOfDay(rome)
            val end = day.plusDays(1).atStartOfDay(rome)
            val runs = generateSequence(cron.next(start.minusSeconds(1))) { cron.next(it) }.takeWhile { it.isBefore(end) }.toList()
            assertEquals(1, runs.size, "$day: $runs")
            assertEquals(LocalDateTime.of(day, java.time.LocalTime.of(4, 15)), runs.single().toLocalDateTime())
            assertEquals(rome, runs.single().zone)
        }
        // And a run is never at the hour of the change: 4:15 of Rome exists on both days.
        assertTrue(ZonedDateTime.of(LocalDateTime.of(2027, 3, 28, 4, 15), rome).toLocalTime() == java.time.LocalTime.of(4, 15))
    }

    @Test
    fun `t7 c11 the job runs at the start, when the application is ready, and the tests of the application switch the schedule off`(@Autowired context: org.springframework.context.ApplicationContext) {
        val method = RetentionSchedule::class.java.getMethod("atStart")
        val listener = assertNotNull(method.getAnnotation(EventListener::class.java))
        assertEquals(listOf(ApplicationReadyEvent::class.java), listener.value.map { it.java })
        // The tests set `talita.retention.automatic=false` (TestcontainersConfiguration): here the schedule is not there. RetentionScheduleTests has it on, as in production.
        assertEquals(0, context.getBeanNamesForType(RetentionSchedule::class.java).size)
    }
}
