package talita.retention

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import talita.ClockConfiguration
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/** What a run of the job did: what each rule deleted (only the rules that deleted something), and the names of the rules that failed. */
data class RetentionReport(val deleted: List<Deleted>, val failed: List<String>) {
    val succeeded: Boolean get() = failed.isEmpty()
}

/**
 * **The job that applies the rules by time** (criteria 2, 11-15): it deletes every datum that is past its date, in the order of the rules ([RetentionRule]: notes,
 * registrations, children, invitations, accounts, then the requests to make again and the numbers to take out of the register).
 *
 * - **A run looks at the day of Rome**, from the [Clock] it is given (the real one, or a fixed one in a test), never at the hour: at 23:59 of the day before a date
 *   the data are there, at 00:30 of the date they are not.
 * - **Each rule is a transaction of its own**, under an advisory lock of Postgres named after the rule (`pg_advisory_xact_lock`): two runs at the same moment (the
 *   start and the cron, or two instances during a deploy) take the rules in turn, the second finds nothing left to delete and writes nothing in the register. It is
 *   one application and one database, so there is no need for more than this.
 * - **A rule that fails** does not stop the others: its transaction goes back (nothing of it is kept), the log has an ERROR with the name of the rule and the type of
 *   the exception (never its message, which could carry data), the application stays up and the next run tries again.
 * - **The log says only numbers**: the rule and how many rows. No name, no address, no date of birth, no phone, no note and no number of a person.
 *
 * It is started by [RetentionSchedule] at every start and every night at 4:15 of Rome, so a day on which the application was off is made up at the first start.
 */
@Component
class RetentionJob(private val dsl: DSLContext, private val clock: Clock, private val rules: List<RetentionRule>) {

    private val log = LoggerFactory.getLogger(RetentionJob::class.java)

    /** Runs every rule once, as of the moment of [at] (the real clock by default). Never throws: what went wrong is in the report and in the log. */
    fun run(at: Clock = clock): RetentionReport {
        val moment = OffsetDateTime.now(at)
        val context = RetentionContext(LocalDate.ofInstant(at.instant(), ClockConfiguration.ZONE), moment)
        val deleted = mutableListOf<Deleted>()
        val failed = mutableListOf<String>()
        for (rule in rules) {
            try {
                val done = dsl.transactionResult { configuration ->
                    val tx = DSL.using(configuration)
                    lock(tx, rule.name)
                    rule.apply(tx, context).filter { it.rows > 0 }.also { lines -> lines.forEach { RetentionLog.write(tx, moment, it.rule, it.rows) } }
                }
                for (line in done) {
                    log.info("Retention: rule {} deleted {} rows.", line.rule, line.rows)
                    deleted += line
                }
            } catch (e: Exception) {
                // The type of the exception and nothing else: its message could carry a value of a row.
                log.error("Retention: rule {} failed ({}): nothing of it was kept, the other rules went on.", rule.name, e.javaClass.simpleName)
                failed += rule.name
            }
        }
        return RetentionReport(deleted, failed)
    }

    /** The advisory lock of a rule, until the end of the transaction: whoever comes second waits and then looks at what is left. */
    private fun lock(tx: DSLContext, rule: String) {
        tx.fetch("select pg_advisory_xact_lock(?)", lockKey(rule))
    }

    companion object {
        /** The key of the lock of [rule]: the same on every run and on every machine (the hash of a string is defined by the language). */
        fun lockKey(rule: String): Long = "talita.retention.$rule".hashCode().toLong()
    }
}
