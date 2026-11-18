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

    private class Window(val start: Instant, var count: Int)

    private val windows = ConcurrentHashMap<Long, Window>()

    /** Counts a request of [tokenId]. Returns null if it may go on, or how many seconds to wait (at least one) if the minute is full. */
    fun take(tokenId: Long): Long? {
        val now = clock.instant()
        if (windows.size > CLEAN_ABOVE) windows.entries.removeIf { !it.value.start.plus(WINDOW).isAfter(now) }
        var wait: Long? = null
        windows.compute(tokenId) { _, current ->
            val window = if (current == null || !current.start.plus(WINDOW).isAfter(now)) Window(now, 0) else current
            if (window.count >= LIMIT) {
                wait = Duration.between(now, window.start.plus(WINDOW)).seconds.coerceAtLeast(1)
            } else {
                window.count++
            }
            window
        }
        return wait
    }

    companion object {
        const val LIMIT = 60
        val WINDOW: Duration = Duration.ofMinutes(1)
        private const val CLEAN_ABOVE = 1000
    }
}
