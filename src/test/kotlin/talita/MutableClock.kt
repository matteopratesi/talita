package talita

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The clock of the tests: the real one, plus an offset that a test can move forward to let a link
 * or an invitation expire without waiting. It only goes forward and is never put back: the clock is
 * shared by every test of the application context, and what they wrote with it (a mail counted for
 * the daily limit, a link that expires) is dated by it, so going back would revive it.
 *
 * One exception, for the tests that move it by whole days (TALITA-19: the days of a gathering come one
 * after the other): such a test reads [offset] before it begins and gives it back with [restore] when it
 * ends, also when it fails, so that the tests that come after find the clock as it was and do not depend
 * on the order. What the test wrote with the moved clock is its own: every test makes its data from "today".
 */
class MutableClock(private val zone: ZoneId) : Clock() {

    @Volatile
    private var offset: Duration = Duration.ZERO

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(zone).also { it.offset = offset }

    override fun instant(): Instant = Instant.now().plus(offset)

    fun advance(by: Duration) {
        offset = offset.plus(by)
    }

    /** How far the clock is from the real time now: what a test that moves it by days keeps, to give it back. */
    fun offset(): Duration = offset

    /** Gives the clock back to [offset], the one that [offset] read before a test moved it. Only for the tests that move it by whole days. */
    fun restore(offset: Duration) {
        this.offset = offset
    }
}
