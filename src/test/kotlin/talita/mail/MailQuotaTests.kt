package talita.mail

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import talita.ClockConfiguration
import talita.MutableClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The two counts of the day (TALITA-13, criterion 7): 150 for links and invitations, 40 for the reminders, each its own. */
class MailQuotaTests {

    private val settings = MailSettings(from = "", name = "Talità", dailyLimit = 150, reminderDailyLimit = 40)

    @Test
    fun `t13 c07 the defaults are 150 for links and invitations and 40 for the reminders, 190 together, under the 200 a day of the free plan`() {
        val defaults = Binder(MapConfigurationPropertySource(emptyMap<String, String>())).bindOrCreate("talita.mail", MailSettings::class.java)

        assertEquals(150, defaults.dailyLimit)
        assertEquals(40, defaults.reminderDailyLimit)
        assertTrue(defaults.dailyLimit + defaults.reminderDailyLimit < 200)
        assertEquals(40, MailQuota(defaults, MutableClock(ClockConfiguration.ZONE)).limit(MailKind.REMINDER))
        assertEquals(150, MailQuota(defaults, MutableClock(ClockConfiguration.ZONE)).limit(MailKind.ACCESS))
        // The property is the one that docs/04 names.
        val set = Binder(MapConfigurationPropertySource(mapOf("talita.mail.reminder-daily-limit" to "12"))).bindOrCreate("talita.mail", MailSettings::class.java)
        assertEquals(12, set.reminderDailyLimit)
    }

    @Test
    fun `t13 c07 the day allows 40 reminders in any 24 hours, the 41st waits, and the day slides`() {
        val clock = MutableClock(ClockConfiguration.ZONE)
        val quota = MailQuota(settings, clock)

        assertEquals(40, quota.remaining(MailKind.REMINDER))
        assertTrue(quota.tryAcquire(MailKind.REMINDER, 40))
        assertEquals(0, quota.remaining(MailKind.REMINDER))
        assertFalse(quota.tryAcquire(MailKind.REMINDER))

        clock.advance(Duration.ofHours(23))
        assertFalse(quota.tryAcquire(MailKind.REMINDER))
        assertEquals(0, quota.remaining(MailKind.REMINDER))
        clock.advance(Duration.ofHours(1).plusSeconds(1))
        assertEquals(40, quota.remaining(MailKind.REMINDER))
        assertTrue(quota.tryAcquire(MailKind.REMINDER))
    }

    @Test
    fun `t13 c07 the two counts do not touch each other`() {
        val quota = MailQuota(settings, MutableClock(ClockConfiguration.ZONE))

        assertTrue(quota.tryAcquire(MailKind.REMINDER, 40))
        assertFalse(quota.tryAcquire(MailKind.REMINDER))
        assertEquals(150, quota.remaining(MailKind.ACCESS), "a link still has all its places")
        assertTrue(quota.tryAcquire(MailKind.ACCESS, 150))
        assertFalse(quota.tryAcquire(MailKind.ACCESS))
        quota.release(MailKind.REMINDER, 3)
        assertEquals(3, quota.remaining(MailKind.REMINDER))
        assertEquals(0, quota.remaining(MailKind.ACCESS), "giving back reminders does not give back links")
    }

    @Test
    fun `t13 c07 a request is all or nothing, and what did not leave gives its places back`() {
        val quota = MailQuota(settings, MutableClock(ClockConfiguration.ZONE))

        assertTrue(quota.tryAcquire(MailKind.REMINDER, 30))
        assertFalse(quota.tryAcquire(MailKind.REMINDER, 11), "11 do not fit in the 10 that are left")
        assertEquals(10, quota.remaining(MailKind.REMINDER), "a refused request counts nothing")
        assertTrue(quota.tryAcquire(MailKind.REMINDER, 10))
        quota.release(MailKind.REMINDER, 4)
        assertEquals(4, quota.remaining(MailKind.REMINDER))
        quota.release(MailKind.REMINDER, 100)
        assertEquals(40, quota.remaining(MailKind.REMINDER), "it cannot give back more than it took")
    }

    @Test
    fun `t13 c07 without a kind the count is the one of links and invitations, as before`() {
        val quota = MailQuota(settings.copy(dailyLimit = 2), MutableClock(ClockConfiguration.ZONE))

        assertTrue(quota.tryAcquire())
        assertTrue(quota.tryAcquire())
        assertFalse(quota.tryAcquire())
        quota.release()
        assertTrue(quota.tryAcquire())
        assertEquals(40, quota.remaining(MailKind.REMINDER))
    }
}
