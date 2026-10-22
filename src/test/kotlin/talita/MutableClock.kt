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
}
