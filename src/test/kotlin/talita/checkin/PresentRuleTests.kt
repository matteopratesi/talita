package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.gathering.ArrivalUsage
import talita.gathering.GatheringReader
import talita.gathering.Gatherings
import talita.gathering.RegistrationUsage
import talita.registration.ChangeResult
import talita.registration.ChildInput
import talita.registration.FormInput
import talita.registration.Offer
import talita.registration.Quota
import talita.registration.RegistrationChecks
import talita.registration.Registrations
import talita.registration.Status
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who is present on a day, and what stays true however the rows are written (TALITA-19, criteria 7, 8 and 14). These tests move the clock by whole days, so they have a context of
 * their own (the property below makes it a different one), and each one gives the clock back when it ends (`MutableClock.restore`, in `@AfterEach`, also when the test fails).
 *
 * - criterion 7: one function says who is present, and the list, the summary, the register and the list for the venue say the same of every day;
 * - criterion 8: the rows of a day after today are not presences, and at midnight, with nothing written, they are;
 * - criterion 14: whatever happens to a registration, a row of a day after today has a registration that is not withdrawn, a day that it includes and a row the day before.
 *
 * Friday, Saturday and Sunday, Luca, Sara and Nicolò as in [ArrivalWeekend]. Names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["talita.test.moves-the-clock=days"])
@AutoConfigureMockMvc
class PresentRuleTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: MutableClock,
    @Autowired private val checkins: Checkins,
    @Autowired private val registrations: Registrations,
    @Autowired private val gatherings: Gatherings,
    @Autowired private val reader: GatheringReader,
    @Autowired private val usage: RegistrationUsage,
    @Autowired private val arrivalUsage: ArrivalUsage,
    @Autowired private val arrivals: Arrivals,
    @Autowired private val presenter: ArrivalsPresenter,
    @Autowired private val prints: PrintPresenter,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    /** Where the clock was before the test: every test that moves it by days gives it back, also when it fails, so that no test depends on the order. */
    private var clockBefore: Duration = Duration.ZERO

    @BeforeEach
    fun rememberTheClock() {
        clockBefore = clock.offset()
    }

    @AfterEach
    fun giveTheClockBack() {
        clock.restore(clockBefore)
    }

    private fun load(gathering: Long): ArrivalsData = arrivals.load(reader.byId(gathering)!!)

    /** The names that the four readings of a day give, and the numbers of the summary. */
    private data class Readings(val list: Set<String>, val summary: Pair<Int, Int>, val register: Set<String>, val venue: Set<String>)

    private fun readings(data: ArrivalsData, day: LocalDate): Readings {
        val now = OffsetDateTime.now(clock)
        val choice = DayChoice(data.gathering.days, day, data.today)
        val page = presenter.page(data, choice, now, null)
        val list = page.groups.flatMap { it.rows }.filter { row -> row.labels.any { it.text == "Presente" } }.map { it.name }.toSet()
        fun number(stat: StatView): Int = if (stat.word) 0 else stat.value.toInt()
        val summary = page.summary!!.stats.let { number(it[0]) to number(it[1]) }
        val index = data.gathering.days.indexOf(day)
        val register = prints.register(data, now).blocks.flatMap { it.rows }.filter { !it.blank && it.cells[index].present }.map { it.name!! }.toSet()
        val venue = prints.venue(data, day, now).children.flatMap { it.rows }.map { "${it.first} ${it.last}" }.toSet()
        return Readings(list, summary, register, venue)
    }

    // ---- Who is present (criteria 7, 8) -----------------------------------------------------------------------------------------

    @Test
    fun `t19 c07 a child is present on a day if the registration is not withdrawn, the row is there and the day is not after today, and the list, the summary, the register and the venue say the same`() {
        val w = ArrivalWeekend(kit, first = 1)
        // Luca arrived on Friday, and has the three rows. Sara was withdrawn, and has the row of Friday, which stays. Nicolò is there on Saturday, which he does not include.
        checkins.mark(w.luca, w.friday, w.setup.martaId)
        kit.presence(w.sara, w.friday, w.setup.martaId)
        assertEquals(ChangeResult.Done, registrations.withdraw(w.sara, null))
        checkins.mark(w.nicolo, w.saturday, w.setup.paoloId)
        val data = load(w.setup.id)
        val luca = data.registrations.single { it.id == w.luca }
        val sara = data.registrations.single { it.id == w.sara }
        val nicolo = data.registrations.single { it.id == w.nicolo }

        // The rule, day by day: the row, the registration that is not withdrawn, a day that is not after today.
        assertEquals(listOf(true, true, false), w.days.map { data.present(luca, it) }, "Luca: Sunday has its row and is after today")
        assertEquals(listOf(false, false, false), w.days.map { data.present(sara, it) }, "Sara is withdrawn: her row of Friday does not count")
        assertEquals(listOf(false, true, false), w.days.map { data.present(nicolo, it) }, "Nicolò: Saturday, which he does not include, and Sunday that is after today")
        // The row as it is kept is still there, for the card.
        assertTrue(data.presence(luca, w.sunday) != null)
        assertTrue(data.presence(sara, w.friday) != null)

        // Every reading of every day says the same.
        val expected = mapOf(
            w.friday to setOf("Luca Rossi"),
            w.saturday to setOf("Luca Rossi", "Nicolò Bianchi"),
            w.sunday to emptySet(),
        )
        for ((day, names) in expected) {
            val read = readings(data, day)
            assertEquals(names, read.list, "the list of $day")
            assertEquals(names, read.register, "the register of $day")
            assertEquals(names, read.venue, "the list for the venue of $day")
            // The summary: confirmed children who are expected and present, and the ones present who were not expected.
            val present = data.registrations.filter { data.present(it, day) }
            assertEquals(present.count { it.status == Status.CONFIRMED && it.includes(day) } to present.count { !it.includes(day) }, read.summary, "the summary of $day")
        }
        // «Presenti non previsti» counts Nicolò on Saturday and not on Sunday.
        assertEquals(1 to 1, readings(data, w.saturday).summary)
        assertEquals(0 to 0, readings(data, w.sunday).summary)
    }

    @Test
    fun `t19 c08 nobody is present on a day after today, and at midnight, with nothing written and no job, the rows of that day are presences`() {
        val w = ArrivalWeekend(kit, first = 1)
        checkins.mark(w.luca, w.friday, w.setup.martaId)
        checkins.mark(w.sara, w.friday, w.setup.martaId)
        val rowsBefore = checkins.ofGathering(w.setup.id)
        assertEquals(5, rowsBefore.size, "Luca: three rows, Sara: two")

        // Saturday looks at Sunday: the list, the summary, the venue and the column of the register have nobody.
        val sunday = readings(load(w.setup.id), w.sunday)
        assertEquals(emptySet(), sunday.list)
        assertEquals(0 to 0, sunday.summary)
        assertEquals(emptySet(), sunday.register)
        assertEquals(emptySet(), sunday.venue)

        // Midnight.
        clock.advance(Duration.ofDays(1))

        assertEquals(w.sunday, kit.today())
        assertEquals(rowsBefore, checkins.ofGathering(w.setup.id), "nothing was written")
        val data = load(w.setup.id)
        val after = readings(data, w.sunday)
        assertEquals(setOf("Luca Rossi"), after.list, "Sara has no row on Sunday, and she does not include it")
        assertEquals(setOf("Luca Rossi"), after.register)
        assertEquals(setOf("Luca Rossi"), after.venue)
        assertEquals(1 to 0, after.summary)
        assertFalse(data.present(data.registrations.single { it.id == w.sara }, w.sunday))
    }

    // ---- Whatever the order (criterion 14) ---------------------------------------------------------------------------------------

    /** The rule of the rows after today, from what the database holds now: the registration is not withdrawn, includes the day, and has a row the day before. */
    private fun assertTheRowsAfterTodayAreWorthSomething(gathering: Long, step: String): Int {
        val today = reader.today()
        val rows = checkins.ofGathering(gathering)
        val byRegistration = rows.groupBy { it.registrationId }.mapValues { (_, own) -> own.map { it.day }.toSet() }
        val all = registrations.ofGathering(gathering).associateBy { it.id }
        val after = rows.filter { it.day.isAfter(today) }
        for (row in after) {
            val registration = all.getValue(row.registrationId)
            assertTrue(registration.status != Status.WITHDRAWN, "$step: the row of ${row.day} (after $today) is of a registration that is withdrawn")
            assertTrue(registration.includes(row.day), "$step: the row of ${row.day} (after $today) is of a day that the registration does not include")
            assertTrue(row.day.minusDays(1) in byRegistration.getValue(row.registrationId), "$step: the row of ${row.day} (after $today) has no row the day before")
        }
        return after.size
    }

    @Test
    fun `t19 c14 whatever the order of marks, takings away, withdrawals, corrections, putting back, new dates and midnights, a row after today is worth something`() {
        val setup = kit.start(first = 0, last = 3)
        val owner = kit.account()
        val days = (0L..3L).map { setup.today.plusDays(it) }
        val children = listOf(
            kit.rows.register(setup.id, owner, "Luca", "Rossi"),
            kit.rows.register(setup.id, kit.account(), "Anna", "Verdi", days = mapOf(days[0] to "full", days[2] to "full")),
            kit.rows.register(setup.id, kit.account(), "Sara", "Neri", days = mapOf(days[1] to "full", days[2] to "lunch", days[3] to "full")),
            kit.rows.register(setup.id, kit.account(), "Nicolò", "Bianchi"),
        )
        val names = mapOf(children[0] to ("Luca" to "Rossi"), children[1] to ("Anna" to "Verdi"), children[2] to ("Sara" to "Neri"), children[3] to ("Nicolò" to "Bianchi"))
        val random = Random(19)
        var midnights = 0
        var seenAfterToday = 0
        var extensions = 0
        var correctionsThatHadRowsAfterToday = 0
        var withdrawalsThatHadRowsAfterToday = 0

        fun correct(id: Long) {
            val info = reader.byId(setup.id)!!
            val (first, last) = names.getValue(id)
            val some = info.days.filter { random.nextBoolean() }.ifEmpty { listOf(info.days.random(random)) }
            val everyDay = random.nextInt(4) == 0
            val child = ChildInput(
                firstName = first, lastName = last, birthDay = "4", birthMonth = "4", birthYear = "2016", presence = if (everyDay) "all_days" else "days",
                days = if (everyDay) emptyMap() else some.associate { Quota.key(it) to "full" }, status = "confirmed",
            )
            val check = RegistrationChecks.check(FormInput("333 123 4567", listOf(child)), Offer(info.fees, info.days), info.start, reader.today(), withHealth = false)
            assertTrue(check.valid, "the correction that the test makes is a good one: ${check.children.single().errors}")
            assertEquals(ChangeResult.Done, registrations.update(id, null, check.phone, check.children.single()))
        }

        repeat(240) { step ->
            if (step == 59 || step == 119 || step == 179) {
                // Midnight, three times: the days that were after today come one after the other, and nothing is written.
                clock.advance(Duration.ofDays(1))
                midnights++
                seenAfterToday += assertTheRowsAfterTodayAreWorthSomething(setup.id, "step $step (midnight)")
                return@repeat
            }
            val id = children.random(random)
            val info = reader.byId(setup.id)!!
            val today = reader.today()
            // The days that can be marked (not after today) are the ones that a teacher marks; the others are tried now and then, and refused.
            val markable = info.days.filter { !it.isAfter(today) }.ifEmpty { info.days }
            val hasRowsAfterToday = checkins.ofRegistration(id).any { it.day.isAfter(today) }
            val what = when (random.nextInt(100)) {
                in 0..39 -> { checkins.mark(id, if (random.nextInt(10) == 0) info.days.random(random) else markable.random(random), setup.martaId); "mark" }
                in 40..51 -> { checkins.unmark(id, info.days.random(random)); "unmark" }
                in 52..57 -> {
                    if (hasRowsAfterToday) withdrawalsThatHadRowsAfterToday++
                    registrations.withdraw(id, null)
                    "withdraw"
                }
                in 58..65 -> { registrations.restoreConfirmed(id); "restore" }
                in 66..86 -> {
                    if (hasRowsAfterToday) correctionsThatHadRowsAfterToday++
                    correct(id)
                    "correct"
                }
                in 87..92 -> {
                    // A day is added at the end, or the last one is taken away (refused when something holds it).
                    val end = if (random.nextBoolean() && extensions < 2) info.end.plusDays(1).also { extensions++ } else info.end.minusDays(1)
                    if (!end.isBefore(info.start)) gatherings.saveDates(setup.id, info.title, info.start, end, info.place, usage, arrivalUsage)
                    "dates"
                }
                else -> { checkins.mark(id, markable.random(random), setup.paoloId); "mark by the other teacher" }
            }
            seenAfterToday += assertTheRowsAfterTodayAreWorthSomething(setup.id, "step $step ($what)")
        }

        assertEquals(3, midnights, "the clock moved, so that the rows became presences one day after the other")
        assertTrue(seenAfterToday > 40, "the test did write rows in advance and looked at them ($seenAfterToday)")
        assertEquals(setup.today.plusDays(3), reader.today())
        assertTrue(correctionsThatHadRowsAfterToday >= 5, "corrections that found rows after today: $correctionsThatHadRowsAfterToday")
        assertTrue(withdrawalsThatHadRowsAfterToday >= 3, "withdrawals that found rows after today: $withdrawalsThatHadRowsAfterToday")
    }
}
