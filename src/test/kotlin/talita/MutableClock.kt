package talita

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The clock of the tests: the real one, plus an offset that a test can move forward to let a link
 * or an invitation expire without waiting. A test that moves it puts it back with [reset] when it
 * is done (`try`/`finally`), because the application context, and so the clock, is shared.
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

    fun reset() {
        offset = Duration.ZERO
    }
}
