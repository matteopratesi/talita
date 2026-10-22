package talita

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/**
 * The one clock of the application. Everything that depends on the time (expiry of the links and of
 * the invitations, limits on the mail, the dates in the texts) reads it from here, so that a test
 * can move time forward instead of waiting. Dates are shown in the time zone of the gatherings.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    fun clock(): Clock = Clock.system(ZONE)

    companion object {
        /** The time zone in which people read dates and times. */
        val ZONE: ZoneId = ZoneId.of("Europe/Rome")
    }
}
