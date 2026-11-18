package talita.api

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * **The limit of the requests of a token** (TALITA-21, criterion 10): [LIMIT] a minute for each token, counted in memory (it starts again with a restart, like the limit of the links of `MailQuota`)
 * and never touching another token. The minute starts at the first request of the token; the first one after it opens a new one.
 */
@Component
class ApiRateLimit(private val clock: Clock) {

    private class Window(val start: Instant, var count: Int, var refused: Boolean = false)

    private val windows = ConcurrentHashMap<Long, Window>()

    /** A refused request: how many seconds to wait (rounded up, at least one) and whether it is the first refusal of this minute (only that one goes in the register). */
    class Refusal(val seconds: Long, val first: Boolean)

    /** Counts a request of [tokenId]. Returns null if it may go on, or the [Refusal] if the minute is full. */
    fun take(tokenId: Long): Refusal? {
        val now = clock.instant()
        if (windows.size > CLEAN_ABOVE) windows.entries.removeIf { !it.value.start.plus(WINDOW).isAfter(now) }
        var refusal: Refusal? = null
        windows.compute(tokenId) { _, current ->
            val window = if (current == null || !current.start.plus(WINDOW).isAfter(now)) Window(now, 0) else current
            if (window.count >= LIMIT) {
                val left = Duration.between(now, window.start.plus(WINDOW))
                val seconds = (left.seconds + if (left.nano > 0) 1 else 0).coerceAtLeast(1)
                refusal = Refusal(seconds, !window.refused)
                window.refused = true
            } else {
                window.count++
            }
            window
        }
        return refusal
    }

    companion object {
        const val LIMIT = 60
        val WINDOW: Duration = Duration.ofMinutes(1)
        private const val CLEAN_ABOVE = 1000
    }
}
