package talita.mail

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The most mails the application sends in any 24 hours (`talita.mail.daily-limit`, 150 by default,
 * under the 200 a day of the free plan of the mail provider). The count is in memory: it starts
 * again from zero at every restart, which at this scale is enough to stop a runaway loop or
 * someone asking for links in bulk. [Mailer] is the only one that uses it.
 */
@Component
class MailQuota(settings: MailSettings, private val clock: Clock) {

    private val limit = settings.dailyLimit

    private val sent = ArrayDeque<Instant>()

    /** Reserves one mail. False when [limit] mails have already left in the last 24 hours. */
    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock.instant()
        val since = now - WINDOW
        while (sent.isNotEmpty() && sent.first() <= since) sent.removeFirst()
        if (sent.size >= limit) return false
        sent.addLast(now)
        return true
    }

    /** Gives back the last reservation: the mail did not leave after all. */
    @Synchronized
    fun release() {
        if (sent.isNotEmpty()) sent.removeLast()
    }

    private companion object {
        val WINDOW: Duration = Duration.ofHours(24)
    }
}
