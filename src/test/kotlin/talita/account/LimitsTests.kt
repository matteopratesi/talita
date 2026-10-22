package talita.account

import org.junit.jupiter.api.Test
import talita.Addresses
import talita.ClockConfiguration
import talita.MutableClock
import talita.mail.MailQuota
import talita.mail.MailSettings
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The two limits on the mail (criterion 16), without a browser: three links for an address in fifteen minutes, 150 mails in 24 hours. */
class LimitsTests {

    private fun address(name: String) = EmailAddress.parse(Addresses.of(name))!!

    private val settings = LoginSettings(linkValidity = Duration.ofMinutes(15), linksPerAddress = 3, window = Duration.ofMinutes(15))

    @Test
    fun `t3 c16 the settings say 3 links in 15 minutes for an address and 150 mails in 24 hours, and the links last 15 minutes`() {
        val defaults = org.springframework.boot.context.properties.bind.Binder(
            org.springframework.boot.context.properties.source.MapConfigurationPropertySource(emptyMap<String, String>()),
        )
        assertEquals(settings, defaults.bindOrCreate("talita.login", LoginSettings::class.java))
        assertEquals(150, defaults.bindOrCreate("talita.mail", MailSettings::class.java).dailyLimit)
    }

    @Test
    fun `t3 c16 an address can ask for three links in fifteen minutes, the fourth waits until the first is older than that`() {
        val clock = MutableClock(ClockConfiguration.ZONE)
        val limiter = LinkRequestLimiter(settings, clock)

        assertTrue(limiter.tryAcquire(address("uno")))
        clock.advance(Duration.ofMinutes(5))
        assertTrue(limiter.tryAcquire(address("uno")))
        assertTrue(limiter.tryAcquire(address("uno")))
        assertFalse(limiter.tryAcquire(address("uno")))
        // Another address has its own.
        assertTrue(limiter.tryAcquire(address("due")))

        clock.advance(Duration.ofMinutes(10).plusSeconds(1))
        // The first request is now 15 minutes and one second old: one place is free, not three.
        assertTrue(limiter.tryAcquire(address("uno")))
        assertFalse(limiter.tryAcquire(address("uno")))
    }

    @Test
    fun `t3 c16 a link that did not leave does not count`() {
        val limiter = LinkRequestLimiter(settings, MutableClock(ClockConfiguration.ZONE))

        repeat(10) {
            assertTrue(limiter.tryAcquire(address("guasto")))
            limiter.release(address("guasto"))
        }
        repeat(3) { assertTrue(limiter.tryAcquire(address("guasto"))) }
        assertFalse(limiter.tryAcquire(address("guasto")))
    }

    @Test
    fun `t3 c16 the limiter forgets the addresses that are over, so that its memory does not grow`() {
        val clock = MutableClock(ClockConfiguration.ZONE)
        val limiter = LinkRequestLimiter(settings, clock)
        repeat(50) { limiter.tryAcquire(address("n$it")) }

        clock.advance(Duration.ofMinutes(16))
        limiter.tryAcquire(address("ultimo"))

        val field = LinkRequestLimiter::class.java.getDeclaredField("requests").also { it.isAccessible = true }
        assertEquals(1, (field.get(limiter) as Map<*, *>).size)
    }

    @Test
    fun `t3 c16 the day allows 150 mails in any 24 hours, the 151st waits`() {
        val clock = MutableClock(ClockConfiguration.ZONE)
        val quota = MailQuota(MailSettings(from = "", name = "Talità", dailyLimit = 150), clock)

        repeat(150) { assertTrue(quota.tryAcquire(), "mail ${it + 1}") }
        assertFalse(quota.tryAcquire())

        // 24 hours after the first, not at midnight: the day slides.
        clock.advance(Duration.ofHours(23))
        assertFalse(quota.tryAcquire())
        clock.advance(Duration.ofHours(1).plusSeconds(1))
        assertTrue(quota.tryAcquire())
    }

    @Test
    fun `t3 c16 a mail that did not leave gives its place back`() {
        val quota = MailQuota(MailSettings(from = "", name = "Talità", dailyLimit = 2), MutableClock(ClockConfiguration.ZONE))

        assertTrue(quota.tryAcquire())
        assertTrue(quota.tryAcquire())
        assertFalse(quota.tryAcquire())
        quota.release()
        assertTrue(quota.tryAcquire())
    }
}
