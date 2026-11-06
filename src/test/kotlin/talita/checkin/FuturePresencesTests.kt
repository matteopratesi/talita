package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.gathering.GatheringReader
import talita.registration.ChangeResult
import talita.registration.ChildInput
import talita.registration.FormInput
import talita.registration.Offer
import talita.registration.Quota
import talita.registration.RegistrationChecks
import talita.registration.Registrations
import talita.registration.RestoreResult
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a withdrawal and a correction do to the rows that the arrival wrote in advance (TALITA-19, criteria 12 and 13): the rows of the days after today go away when they are not
 * worth anything any more, in the transaction of the withdrawal or of the correction, and the ones of today and of the past stay. Friday, Saturday and Sunday, Luca, Sara and
 * Nicolò as in [ArrivalWeekend]. Names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class FuturePresencesTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val checkins: Checkins,
    @Autowired private val registrations: Registrations,
    @Autowired private val reader: GatheringReader,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    /** The correction of a child registered with the default birth date (`4 4 2016`) to [presence], as a form sends it: `all_days`, or `days` with the whole days in [days]. */
    private fun correct(w: ArrivalWeekend, id: Long, owner: Long?, first: String, last: String, presence: String, days: List<LocalDate> = emptyList()): ChangeResult {
        val info = reader.byId(w.setup.id)!!
        val child = ChildInput(
            firstName = first, lastName = last, birthDay = "4", birthMonth = "4", birthYear = "2016", presence = presence,
            days = days.associate { Quota.key(it) to "full" }, status = "confirmed",
        )
        val check = RegistrationChecks.check(FormInput("333 123 4567", listOf(child)), Offer(info.fees, info.days), info.start, w.setup.today, withHealth = false)
        return registrations.update(id, owner, check.phone, check.children.single())
    }

    // ---- The withdrawal (criterion 12) ------------------------------------------------------------------------------------------

    @Test
    fun `t19 c12 a withdrawal takes away the rows after today, by a teacher and by the parent, and keeps the rows of today and of the past`() {
        val w = ArrivalWeekend(kit, first = 1)
        checkins.mark(w.luca, w.friday, w.setup.martaId)
        assertEquals(w.days, kit.presenceDays(w.luca))

        assertEquals(ChangeResult.Done, registrations.withdraw(w.luca, null))

        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(w.luca), "Friday and today stay: they are the register; Sunday has not come")
        // The parent withdraws too, and finds the same.
        val owner = kit.account()
        val anna = kit.rows.register(w.setup.id, owner, "Anna", "Verdi")
        checkins.mark(anna, w.friday, w.setup.paoloId)
        assertEquals(ChangeResult.Done, registrations.withdraw(anna, owner))
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(anna))
        assertEquals(ChangeResult.AlreadyDone, registrations.withdraw(anna, owner), "the same touch again changes nothing")
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(anna))
    }

    @Test
    fun `t19 c12 Rimetti confermata does not put the rows back, the child is marked again, and the arrival carries on`() {
        val w = ArrivalWeekend(kit, first = 1)
        checkins.mark(w.luca, w.friday, w.setup.martaId)
        registrations.withdraw(w.luca, null)

        assertEquals(RestoreResult.Done, registrations.restoreConfirmed(w.luca))

        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(w.luca), "Sunday does not come back by itself")
        // Marked again from Saturday: Saturday has its row, so the touch is «already present» and nothing follows; taking it away and marking again carries it on.
        assertIs<MarkResult.AlreadyMarked>(checkins.mark(w.luca, w.saturday, w.setup.martaId))
        assertEquals(UnmarkResult.Unmarked(listOf(w.saturday)), checkins.unmark(w.luca, w.saturday))
        assertEquals(MarkResult.Marked(false, false, listOf(w.saturday, w.sunday)), checkins.mark(w.luca, w.saturday, w.setup.martaId))
    }

    @Test
    fun `t19 c12 with Friday still today a withdrawal takes away Saturday and Sunday and keeps Friday`() {
        val w = ArrivalWeekend(kit)
        checkins.mark(w.luca, w.friday, w.setup.martaId)
        checkins.mark(w.sara, w.friday, w.setup.martaId)

        registrations.withdraw(w.luca, null)

        assertEquals(listOf(w.friday), kit.presenceDays(w.luca))
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(w.sara), "the other child is not touched")
    }

    @Test
    fun `t19 c12 a withdrawal and the arrival at the same moment are in a queue, either the arrival and then the withdrawal, or the withdrawal and the arrival refused`() {
        val w = ArrivalWeekend(kit)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { round ->
                val id = kit.rows.register(w.setup.id, kit.account(), "Bambino$round", "Rossi")
                val barrier = CyclicBarrier(2)
                val marking = pool.submit<MarkResult> { barrier.await(10, TimeUnit.SECONDS); checkins.mark(id, w.friday, w.setup.martaId) }
                val withdrawing = pool.submit<ChangeResult> { barrier.await(10, TimeUnit.SECONDS); registrations.withdraw(id, null) }
                val marked = marking.get(30, TimeUnit.SECONDS)
                assertEquals(ChangeResult.Done, withdrawing.get(30, TimeUnit.SECONDS), "round $round")

                when (marked) {
                    is MarkResult.Marked -> assertEquals(listOf(w.friday), kit.presenceDays(id), "round $round: the withdrawal came after the arrival and took away the days after today")
                    MarkResult.Withdrawn -> assertEquals(emptyList(), kit.presenceDays(id), "round $round: the withdrawal came first")
                    else -> error("round $round: $marked")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- The correction (criterion 13) ------------------------------------------------------------------------------------------

    @Test
    fun `t19 c13 a correction keeps a row after today only if the registration still includes the day and the day before has a row`() {
        val w = ArrivalWeekend(kit)
        val owner = kit.account()
        // From every day to Friday and Sunday: Saturday goes, and Sunday goes with it (the day before it has no row any more).
        val a = kit.rows.register(w.setup.id, owner, "Luca", "Rossi")
        checkins.mark(a, w.friday, w.setup.martaId)
        assertEquals(w.days, kit.presenceDays(a))
        assertEquals(ChangeResult.Done, correct(w, a, null, "Luca", "Rossi", "days", listOf(w.friday, w.sunday)))
        assertEquals(listOf(w.friday), kit.presenceDays(a))

        // To Friday and Saturday: Sunday goes.
        val b = kit.rows.register(w.setup.id, kit.account(), "Luca", "Rossi")
        checkins.mark(b, w.friday, w.setup.martaId)
        assertEquals(ChangeResult.Done, correct(w, b, null, "Luca", "Rossi", "days", listOf(w.friday, w.saturday)))
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(b))

        // To Saturday and Sunday: Friday, which is today, stays (it is «not foreseen» now), and Saturday and Sunday follow it only if they are included: both are.
        val c = kit.rows.register(w.setup.id, kit.account(), "Luca", "Rossi")
        checkins.mark(c, w.friday, w.setup.martaId)
        assertEquals(ChangeResult.Done, correct(w, c, null, "Luca", "Rossi", "days", listOf(w.saturday, w.sunday)))
        assertEquals(w.days, kit.presenceDays(c), "Friday is kept, and the days after it are carried by it")
    }

    @Test
    fun `t19 c13 adding a day writes no row, and the rows of today and of the past do not change when a day is taken from the registration`() {
        val w = ArrivalWeekend(kit, first = 1)
        val owner = kit.account()
        val luca = kit.rows.register(w.setup.id, owner, "Luca", "Rossi")
        checkins.mark(luca, w.friday, w.setup.martaId)
        val before = checkins.ofRegistration(luca)

        // Saturday only: Friday (past) stays as a day that was not foreseen, Saturday is today and stays, Sunday is not included and goes.
        assertEquals(ChangeResult.Done, correct(w, luca, owner, "Luca", "Rossi", "days", listOf(w.saturday)))

        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(luca))
        assertEquals(before.take(2), checkins.ofRegistration(luca), "the rows that stay are the same, with their teacher and their moment")
        // Back to every day: no row is written for Sunday.
        assertEquals(ChangeResult.Done, correct(w, luca, owner, "Luca", "Rossi", "all_days"))
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(luca))
    }

    @Test
    fun `t19 c13 a registration of the parent is corrected by the parent with the same effect, and the days that nobody touched are not read again`() {
        val w = ArrivalWeekend(kit)
        val owner = kit.account()
        val anna = kit.rows.register(w.setup.id, owner, "Anna", "Verdi")
        checkins.mark(anna, w.friday, w.setup.martaId)

        assertEquals(ChangeResult.Done, correct(w, anna, owner, "Anna", "Verdi", "days", listOf(w.friday, w.saturday)))

        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(anna))
        // A correction that changes nothing about the days leaves the rows as they are.
        assertEquals(ChangeResult.Done, correct(w, anna, owner, "Anna", "Verdi", "days", listOf(w.friday, w.saturday)))
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(anna))
        assertTrue(registrations.find(anna)!!.includes(w.saturday))
    }
}
