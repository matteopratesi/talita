package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.CapturesOutput
import talita.TestcontainersConfiguration
import talita.jooq.enums.RegistrationStatus
import talita.registration.ChangeResult
import talita.registration.Registrations
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The arrival that is for every day (TALITA-19, criteria 1-6 and 9-11, and the service part of 19): marking a child on a day writes the rows of the days after it that the
 * registration includes, in a row, in one transaction; taking a presence away takes the day and the days after it that have a row. The example of the analysis: a gathering
 * from Friday to Sunday, Luca (every day), Sara (Friday whole day, Saturday lunch only) and Nicolò (Friday and Sunday), the teachers Marta Bianchi and Paolo Verdi. The days of the
 * week are those of the day the test runs: here the days are `friday`, `saturday` and `sunday`, three days in a row. Names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ArrivalDaysTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val checkins: Checkins,
    @Autowired private val registrations: Registrations,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    private fun weekend(first: Long = 0) = ArrivalWeekend(kit, first)

    // ---- Marking the arrival (criteria 1-6) -------------------------------------------------------------------------------------

    @Test
    fun `t19 c01 marking the arrival writes the day and the days after it in one transaction, with the same teacher and the same moment`() {
        val w = weekend()

        val marked = checkins.mark(w.luca, w.friday, w.setup.martaId)

        assertEquals(MarkResult.Marked(confirmed = false, notExpected = false, days = listOf(w.friday, w.saturday, w.sunday)), marked)
        val rows = checkins.ofRegistration(w.luca)
        assertEquals(listOf(w.friday, w.saturday, w.sunday), rows.map { it.day })
        assertEquals(setOf(w.setup.martaId), rows.map { it.byAccountId }.toSet(), "the teacher of the arrival")
        assertEquals(1, rows.map { it.at }.toSet().size, "the moment of the arrival, the same for every row: ${rows.map { it.at }}")
        assertEquals(3, kit.presenceCount())
    }

    @Test
    fun `t19 c01 the rows of the arrival are written all together or not at all`() {
        val w = weekend()

        // The last row cannot be written (a constraint that only this test adds): the rows that came before it go back too.
        dsl.execute("alter table checkin add constraint test_no_sunday check (day <> date '${w.sunday}') not valid")
        try {
            kotlin.runCatching { checkins.mark(w.luca, w.friday, w.setup.martaId) }.also { assertTrue(it.isFailure, "the transaction fails as a whole") }
        } finally {
            dsl.execute("alter table checkin drop constraint test_no_sunday")
        }
        assertEquals(emptyList(), kit.presenceDays(w.luca))
        // And the touch can be made again.
        assertEquals(listOf(w.friday, w.saturday, w.sunday), (checkins.mark(w.luca, w.friday, w.setup.martaId) as MarkResult.Marked).days)
    }

    @Test
    fun `t19 c01 a day of the past, and a day after the end, carry the arrival on too, the paper register is copied with one touch`() {
        val setup = kit.start(first = -5, last = -3)
        val first = setup.today.minusDays(5)
        val luca = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")

        val marked = checkins.mark(luca, first, setup.martaId)

        assertEquals(MarkResult.Marked(confirmed = false, notExpected = false, days = listOf(first, first.plusDays(1), first.plusDays(2))), marked)
        assertEquals(listOf(first, first.plusDays(1), first.plusDays(2)), kit.presenceDays(luca))
    }

    @Test
    fun `t19 c02 the arrival stops at the first day that the registration does not include, and a day of lunch only counts like the others`() {
        val w = weekend()

        // Sara: Friday whole day and Saturday lunch only (Sunday is not hers). Nicolò: Friday and Sunday, so the arrival of Friday stops at Saturday.
        assertEquals(MarkResult.Marked(confirmed = false, notExpected = false, days = listOf(w.friday, w.saturday)), checkins.mark(w.sara, w.friday, w.setup.martaId))
        assertEquals(MarkResult.Marked(confirmed = false, notExpected = false, days = listOf(w.friday)), checkins.mark(w.nicolo, w.friday, w.setup.martaId))

        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(w.sara))
        assertEquals(listOf(w.friday), kit.presenceDays(w.nicolo), "Sunday is for the touch of the day he comes back")
    }

    @Test
    fun `t19 c03 the arrival stops at a day that has its row already, and that row stays as it is, with its teacher and its moment`() {
        val w = weekend(first = 1)
        // Nobody marks Luca on Friday; on Saturday (today) Paolo does, and then takes Sunday away because Luca goes home on Saturday evening.
        assertEquals(MarkResult.Marked(false, false, listOf(w.saturday, w.sunday)), checkins.mark(w.luca, w.saturday, w.setup.paoloId))
        assertEquals(UnmarkResult.Unmarked(listOf(w.sunday)), checkins.unmark(w.luca, w.sunday))
        val before = kit.presenceOf(w.luca, w.saturday)

        // Afterwards Marta writes down the paper register: Friday only, because Saturday has its row.
        val marked = checkins.mark(w.luca, w.friday, w.setup.martaId)

        assertEquals(MarkResult.Marked(false, false, listOf(w.friday)), marked)
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(w.luca))
        assertEquals(before, kit.presenceOf(w.luca, w.saturday), "Saturday is Paolo's, at his moment")
        assertEquals(w.setup.paoloId, kit.presenceOf(w.luca, w.saturday).byAccountId)
        // A day that has its row says whose it is, and writes nothing, and the days after it stay as they are.
        val again = checkins.mark(w.luca, w.saturday, w.setup.martaId)
        assertEquals(MarkResult.AlreadyMarked(w.setup.paoloId, before.at), again)
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(w.luca), "Sunday is not written by a touch that finds Saturday done")
    }

    @Test
    fun `t19 c04 a day that is not foreseen is marked with the warning of today, and the arrival goes on to the days that the registration includes`() {
        val w = weekend(first = 1)

        // Nicolò (Friday and Sunday) stays on Saturday too: Marta marks him on Saturday, and Sunday, which he includes, follows.
        val marked = checkins.mark(w.nicolo, w.saturday, w.setup.martaId)

        assertEquals(MarkResult.Marked(confirmed = false, notExpected = true, days = listOf(w.saturday, w.sunday)), marked)
        assertEquals(listOf(w.saturday, w.sunday), kit.presenceDays(w.nicolo))
        // A registration that has only Sunday: the arrival of Friday (not foreseen) does not jump over Saturday, which is not hers.
        val sundayOnly = kit.rows.register(w.setup.id, kit.account(), "Anna", "Verdi", days = mapOf(w.sunday to "full"))
        assertEquals(MarkResult.Marked(confirmed = false, notExpected = true, days = listOf(w.friday)), checkins.mark(sundayOnly, w.friday, w.setup.martaId))
        assertEquals(listOf(w.friday), kit.presenceDays(sundayOnly))
    }

    @Test
    fun `t19 c05 a child who is in forse is confirmed with the rows, both or neither, and what cannot be done writes nothing`() {
        val w = weekend()
        val maybe = kit.rows.register(w.setup.id, kit.account(), "Elena", "Marino", status = RegistrationStatus.maybe)

        assertEquals(MarkResult.Marked(confirmed = true, notExpected = false, days = listOf(w.friday, w.saturday, w.sunday)), checkins.mark(maybe, w.friday, w.setup.martaId))
        assertEquals("confirmed", kit.statusOf(maybe))

        // The confirmation fails: no row, not even the first one.
        val other = kit.rows.register(w.setup.id, kit.account(), "Marco", "Gallo", status = RegistrationStatus.maybe)
        dsl.execute("alter table registration add constraint test_no_confirmation check (status <> 'confirmed') not valid")
        try {
            kotlin.runCatching { checkins.mark(other, w.friday, w.setup.martaId) }.also { assertTrue(it.isFailure, "the transaction fails as a whole") }
        } finally {
            dsl.execute("alter table registration drop constraint test_no_confirmation")
        }
        assertEquals("maybe", kit.statusOf(other))
        assertEquals(emptyList(), kit.presenceDays(other), "no row without the confirmation")

        // Withdrawn, a day after today and a day that is not one of the gathering: as before, and nothing is written.
        val withdrawn = kit.rows.register(w.setup.id, kit.account(), "Cleo", "Pace", status = RegistrationStatus.withdrawn)
        assertEquals(MarkResult.Withdrawn, checkins.mark(withdrawn, w.friday, w.setup.martaId))
        assertEquals(MarkResult.NotYet, checkins.mark(w.luca, w.saturday, w.setup.martaId))
        assertEquals(MarkResult.OutsideTheDates, checkins.mark(w.luca, w.sunday.plusDays(1), w.setup.martaId))
        assertEquals(MarkResult.OutsideTheDates, checkins.mark(w.luca, w.friday.minusDays(1), w.setup.martaId))
        assertEquals(emptyList(), kit.presenceDays(withdrawn))
        assertEquals(emptyList(), kit.presenceDays(w.luca))
    }

    @Test
    fun `t19 c06 two teachers who mark Luca at the same moment are in a queue, and the second finds the rows of the first`() {
        val w = weekend(first = 1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            // On the same day: one writes the three rows, the other finds Saturday done, and nothing is written twice.
            repeat(10) { round ->
                val id = kit.rows.register(w.setup.id, kit.account(), "Bambino$round", "Rossi")
                val barrier = CyclicBarrier(2)
                val teachers = listOf(w.setup.martaId, w.setup.paoloId)
                val results = teachers.map { teacher -> pool.submit<MarkResult> { barrier.await(10, TimeUnit.SECONDS); checkins.mark(id, w.saturday, teacher) } }.map { it.get(30, TimeUnit.SECONDS) }

                assertEquals(1, results.count { it is MarkResult.Marked }, "round $round: $results")
                assertEquals(1, results.count { it is MarkResult.AlreadyMarked }, "round $round: $results")
                val winner = teachers[results.indexOfFirst { it is MarkResult.Marked }]
                assertEquals(listOf(w.saturday, w.sunday), kit.presenceDays(id))
                assertEquals(setOf(winner), checkins.ofRegistration(id).map { it.byAccountId }.toSet(), "the rows are of whoever was first")
                assertEquals((results.single { it is MarkResult.AlreadyMarked } as MarkResult.AlreadyMarked).byAccountId, winner)
            }
            // On two days: Friday and Saturday. Either Friday goes first and carries the arrival through Sunday (and Saturday's touch finds it), or Saturday goes first and
            // Friday's stops at it. The three days have their row in both cases, and none is written twice.
            repeat(10) { round ->
                val id = kit.rows.register(w.setup.id, kit.account(), "Altro$round", "Rossi")
                val barrier = CyclicBarrier(2)
                val friday = pool.submit<MarkResult> { barrier.await(10, TimeUnit.SECONDS); checkins.mark(id, w.friday, w.setup.martaId) }
                val saturday = pool.submit<MarkResult> { barrier.await(10, TimeUnit.SECONDS); checkins.mark(id, w.saturday, w.setup.paoloId) }
                val results = friday.get(30, TimeUnit.SECONDS) to saturday.get(30, TimeUnit.SECONDS)

                assertEquals(listOf(w.friday, w.saturday, w.sunday), kit.presenceDays(id), "round $round: $results")
                val fridayFirst = results.first == MarkResult.Marked(false, false, listOf(w.friday, w.saturday, w.sunday)) && results.second is MarkResult.AlreadyMarked
                val saturdayFirst = results.first == MarkResult.Marked(false, false, listOf(w.friday)) && results.second == MarkResult.Marked(false, false, listOf(w.saturday, w.sunday))
                assertTrue(fridayFirst || saturdayFirst, "round $round: $results")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- Taking away (criteria 9-11) ---------------------------------------------------------------------------------------------

    @Test
    fun `t19 c09 taking a presence away takes the day and the days after it that have a row, in a row, and the day before stays`() {
        val w = weekend(first = 1)
        checkins.mark(w.luca, w.friday, w.setup.martaId)
        assertEquals(listOf(w.friday, w.saturday, w.sunday), kit.presenceDays(w.luca))

        val unmarked = checkins.unmark(w.luca, w.saturday)

        assertEquals(UnmarkResult.Unmarked(listOf(w.saturday, w.sunday)), unmarked)
        assertEquals(listOf(w.friday), kit.presenceDays(w.luca))
        // A row after a day without one is not taken away with the day before it: «in a row, up to the first that has none».
        kit.presence(w.nicolo, w.friday, w.setup.martaId)
        kit.presence(w.nicolo, w.sunday, w.setup.martaId)
        assertEquals(UnmarkResult.Unmarked(listOf(w.friday)), checkins.unmark(w.nicolo, w.friday))
        assertEquals(listOf(w.sunday), kit.presenceDays(w.nicolo), "Saturday has no row: the take away stops there")
    }

    @Test
    fun `t19 c10 a day after today can be taken away alone, and taking away a day that has no row is the same answer, also after today`() {
        val w = weekend(first = 1)
        checkins.mark(w.luca, w.friday, w.setup.martaId)

        assertEquals(UnmarkResult.Unmarked(listOf(w.sunday)), checkins.unmark(w.luca, w.sunday), "Sunday is after today: nothing is refused")
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(w.luca), "Saturday stays")
        assertEquals(UnmarkResult.AlreadyUnmarked, checkins.unmark(w.luca, w.sunday))
        assertEquals(UnmarkResult.AlreadyUnmarked, checkins.unmark(w.sara, w.sunday))
        assertEquals(UnmarkResult.OutsideTheDates, checkins.unmark(w.luca, w.sunday.plusDays(1)))
        assertEquals(UnmarkResult.NotFound, checkins.unmark(w.luca + 100_000, w.saturday))
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(w.luca))
    }

    @Test
    fun `t19 c11 one touch takes away the whole mark that was wrong, also on the registration that was withdrawn`() {
        val w = weekend()
        // Marta marks Sara on Friday instead of another child: Friday and Saturday are hers. One touch on Friday and nothing of Sara is left.
        checkins.mark(w.sara, w.friday, w.setup.martaId)
        assertEquals(UnmarkResult.Unmarked(listOf(w.friday, w.saturday)), checkins.unmark(w.sara, w.friday))
        assertEquals(0, kit.presenceCount())
    }

    @Test
    fun `t19 c11 the rows of a registration that was withdrawn, a duplicate, are taken away by the same rule`() {
        val w = weekend(first = 1)
        // The rows of the past and of today stay with the withdrawal: they are moved by hand to the other copy.
        val copy = kit.rows.register(w.setup.id, kit.account(), "Sara", "Neri", status = RegistrationStatus.withdrawn)
        kit.presence(copy, w.friday, w.setup.martaId)
        kit.presence(copy, w.saturday, w.setup.martaId)

        assertEquals(UnmarkResult.Unmarked(listOf(w.friday, w.saturday)), checkins.unmark(copy, w.friday))
        assertEquals(emptyList(), kit.presenceDays(copy))
    }

    // ---- Who can do it, and what the log says (criterion 19) --------------------------------------------------------------------------

    @Test
    @CapturesOutput
    fun `t19 c19 the log has no name while the arrival is marked, taken away, withdrawn and corrected on several days`(output: CapturedOutput) {
        val setup = kit.start()
        val owner = kit.account()
        val zeffirino = kit.rows.register(setup.id, owner, "Zeffirino", "Quagliotti", LocalDate.of(2019, 1, 9), phone = "333 777 8888")

        assertIs<MarkResult.Marked>(checkins.mark(zeffirino, setup.yesterday, setup.martaId))
        assertIs<UnmarkResult.Unmarked>(checkins.unmark(zeffirino, setup.tomorrow))
        assertIs<MarkResult.AlreadyMarked>(checkins.mark(zeffirino, setup.today, setup.martaId))
        assertIs<UnmarkResult.Unmarked>(checkins.unmark(zeffirino, setup.today))
        assertIs<MarkResult.Marked>(checkins.mark(zeffirino, setup.today, setup.paoloId))
        assertEquals(ChangeResult.Done, registrations.withdraw(zeffirino, owner))

        val log = output.all
        for (secret in listOf("Zeffirino", "Quagliotti", "333 777", "3337778888", "Marta", "Bianchi", "Paolo", "Verdi", "2019-01-09")) {
            assertFalse(log.contains(secret), "the log has '$secret': ${log.lines().filter { it.contains(secret) }.take(3)}")
        }
    }
}
