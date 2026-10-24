package talita.retention

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor
import org.springframework.scheduling.config.CronTask
import talita.TestcontainersConfiguration
import talita.registration.Notes
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The schedule as it is in production (criterion 11): no profile and nothing switched off, so the start and the cron are on. The application of this class is
 * started without `talita.retention.automatic=false` (the tests of the other classes have it), and the job is not called by hand: the event of the start makes it run,
 * with the real clock, on data that are old. A gathering of 2020 stands for a night that the application was off.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["talita.retention.automatic=true"])
class RetentionScheduleTests(
    @Autowired private val context: ConfigurableApplicationContext,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val processor: ScheduledAnnotationBeanPostProcessor,
) {

    private val kit = RetentionTestKit(dsl, clock)

    @BeforeEach
    fun clean() = kit.reset()

    @Test
    fun `t7 c11 with no profile the start and the cron are on, and the cron has the expression of the criterion`() {
        assertEquals(emptyList(), context.environment.activeProfiles.toList(), "no profile")
        assertEquals(1, context.getBeanNamesForType(RetentionSchedule::class.java).size)
        val crons = processor.scheduledTasks.mapNotNull { it.task as? CronTask }.filter { it.expression == RetentionSchedule.CRON }
        assertEquals(1, crons.size, "the application has the cron of the night: ${processor.scheduledTasks.map { it.task }}")
    }

    @Test
    fun `t7 c11 when the application is ready the job runs once, and what a night of an application that was off did not do is done at the start`() {
        // A gathering that ended in 2020, with the registrations and the notes still there: a deletion that the application was off for.
        val old = kit.gatherings.make(
            title = "Convivenza di settembre 2020", start = LocalDate.of(2020, 9, 11), end = LocalDate.of(2020, 9, 13),
            status = talita.jooq.enums.GatheringStatus.closed, code = talita.gathering.GatheringSupport.nextCode(),
        )
        kit.registration(old, kit.account("genitore-a"), notes = Notes("Arachidi", null, null))
        assertEquals(1, kit.registrations())
        assertEquals(1, kit.notes())

        context.publishEvent(ApplicationReadyEvent(SpringApplication(), emptyArray(), context, Duration.ZERO))

        assertEquals(0, kit.notes())
        assertEquals(0, kit.registrations())
        assertEquals(0, kit.children())
        assertTrue(kit.log().map { it.first }.containsAll(listOf("notes", "registrations", "children")), "the register says what was deleted: ${kit.log()}")
        assertNotNull(dsl.select(talita.jooq.tables.references.GATHERING.ID).from(talita.jooq.tables.references.GATHERING).fetchOne(), "the gathering stays")
    }
}
