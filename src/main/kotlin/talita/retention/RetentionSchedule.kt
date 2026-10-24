package talita.retention

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

/**
 * **When the job runs** (criteria 11, 12): once when the application is ready, and then every night at 4:15 of Rome (`zone`, so the summer time and the winter
 * time do not move it, and on the nights when the clocks change there is still one run). A day on which the application was off is made up at the first start, so a
 * deletion is never later than the next start after its date.
 *
 * Nothing switches it off without a profile: with no profile (production, and the jar of the smoke test) the start and the cron are on. A test that wants the
 * rows it writes left alone sets `talita.retention.automatic=false` (the tests of the application do: they run the job by hand, with a fixed clock). The scheduler is
 * the default one of Spring Boot, with one thread: one application, no need for more (and no ShedLock: the advisory locks of [RetentionJob] are enough).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "talita.retention", name = ["automatic"], havingValue = "true", matchIfMissing = true)
class RetentionSchedule(private val job: RetentionJob) {

    /** At the start, when the application is ready to answer: what a night of a switched-off application did not do is done now. */
    @EventListener(ApplicationReadyEvent::class)
    fun atStart() {
        job.run()
    }

    /** Every night at 4:15, before the backup of the day (docs/03-privacy.md: the backup starts after this job). */
    @Scheduled(cron = CRON, zone = "Europe/Rome")
    fun everyNight() {
        job.run()
    }

    companion object {
        /** Seconds, minutes, hours, day of the month, month, day of the week: every day at 4:15. */
        const val CRON = "0 15 4 * * *"
    }
}
