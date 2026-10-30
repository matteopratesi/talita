package talita.mail

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.EnumMap

/**
 * Which of the two counts of the day a mail is on. They are separate on purpose (TALITA-13): a reminder, which a teacher sends to many people at
 * once, must never take the place of a login link, which a parent is waiting for.
 */
enum class MailKind {
    /** Login links and invitations: the person who asked is waiting for them. */
    ACCESS,

    /** The reminders that a teacher sends with a button (TALITA-13). */
    REMINDER,
}

/**
 * The most mails the application sends in any 24 hours, in two counts: `talita.mail.daily-limit` (150 by default) for login links and invitations
 * ([MailKind.ACCESS]) and `talita.mail.reminder-daily-limit` (40 by default) for the reminders ([MailKind.REMINDER]). Together they are 190, under the 200 a
 * day of the free plan of the mail provider (which count for the whole account). The counts are in memory: they start again from zero at every restart, which
 * at this scale is enough to stop a runaway loop or someone asking for links in bulk. [Mailer] is the only one that uses it.
 */
@Component
class MailQuota(settings: MailSettings, private val clock: Clock) {

    private val limits = EnumMap(mapOf(MailKind.ACCESS to settings.dailyLimit, MailKind.REMINDER to settings.reminderDailyLimit))

    private val sent = EnumMap<MailKind, ArrayDeque<Instant>>(MailKind::class.java).also { map -> MailKind.entries.forEach { map[it] = ArrayDeque() } }

    /** How many mails the day allows for [kind] (the limit, not what is left). */
    fun limit(kind: MailKind = MailKind.ACCESS): Int = limits.getValue(kind)

    private fun used(kind: MailKind): ArrayDeque<Instant> {
        val times = sent.getValue(kind)
        val since = clock.instant() - WINDOW
        while (times.isNotEmpty() && times.first() <= since) times.removeFirst()
        return times
    }

    /** Reserves [count] mails together, or none: false when [kind] does not have that many left in the last 24 hours. */
    @Synchronized
    fun tryAcquire(kind: MailKind = MailKind.ACCESS, count: Int = 1): Boolean {
        val times = used(kind)
        if (times.size + count > limits.getValue(kind)) return false
        val now = clock.instant()
        repeat(count) { times.addLast(now) }
        return true
    }

    /** How many mails of [kind] can still leave now. */
    @Synchronized
    fun remaining(kind: MailKind = MailKind.ACCESS): Int = (limits.getValue(kind) - used(kind).size).coerceAtLeast(0)

    /** Gives back the last [count] reservations of [kind]: those mails did not leave after all. */
    @Synchronized
    fun release(kind: MailKind = MailKind.ACCESS, count: Int = 1) {
        val times = sent.getValue(kind)
        repeat(count) { if (times.isNotEmpty()) times.removeLast() }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofHours(24)
    }
}
