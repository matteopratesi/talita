package talita.checkin

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.LockWait
import talita.TestcontainersConfiguration
import talita.gathering.ArrivalUsage
import talita.gathering.DatesResult
import talita.gathering.DayInUse
import talita.gathering.Fees
import talita.gathering.FeesResult
import talita.gathering.GatheringReader
import talita.gathering.Gatherings
import talita.gathering.RegistrationUsage
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.registration.ChangeResult
import talita.registration.ChildInput
import talita.registration.FormInput
import talita.registration.Offer
import talita.registration.Quota
import talita.registration.RegistrationChecks
import talita.registration.Registrations
import talita.registration.RestoreResult
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The services of the check-in (criteria 4, 6-8, 10-15, 20, 29): what a presence and a payment do to the database, in one transaction, with the other teachers at
 * the same moment (two real transactions where the criterion asks for them), and what the gathering says when its dates change. No page here: the pages
 * have their own tests. Names, dates and amounts are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class CheckinServicesTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val checkins: Checkins,
    @Autowired private val payments: Payments,
    @Autowired private val registrations: Registrations,
    @Autowired private val gatherings: Gatherings,
    @Autowired private val reader: GatheringReader,
    @Autowired private val usage: RegistrationUsage,
    @Autowired private val arrivals: ArrivalUsage,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    // ---- A presence (criteria 4, 6, 7) ----------------------------------------------------------------------------------------------------

    @Test
    fun `t6 c04 c06 a presence is one row with the moment and the teacher, on a day of the gathering that is not after today`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")

        val marked = checkins.mark(id, setup.today, setup.martaId)

        assertEquals(MarkResult.Marked(confirmed = false, notExpected = false), marked)
        val row = checkins.ofRegistration(id).single()
        assertEquals(setup.today, row.day)
        assertEquals(setup.martaId, row.byAccountId)
        assertTrue(java.time.Duration.between(row.at, java.time.OffsetDateTime.now(clock)).abs().seconds < 60, "the moment is now: ${row.at}")
        // A day of the past, to correct. A day after today, a day that is not one of the gathering, and an id that does not exist: nothing is written.
        assertIs<MarkResult.Marked>(checkins.mark(id, setup.yesterday, setup.paoloId))
        assertEquals(MarkResult.NotYet, checkins.mark(id, setup.tomorrow, setup.martaId))
        assertEquals(MarkResult.OutsideTheDates, checkins.mark(id, setup.today.plusDays(5), setup.martaId))
        assertEquals(MarkResult.OutsideTheDates, checkins.mark(id, setup.yesterday.minusDays(1), setup.martaId))
        assertEquals(MarkResult.NotFound, checkins.mark(id + 1000, setup.today, setup.martaId))
        assertEquals(listOf(setup.yesterday, setup.today), kit.presenceDays(id))
    }

    @Test
    fun `t6 c06 taking a presence away changes only that day, and taking away one that is not there is the same answer, never an error`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")
        checkins.mark(id, setup.yesterday, setup.martaId)
        checkins.mark(id, setup.today, setup.martaId)

        assertEquals(UnmarkResult.Unmarked, checkins.unmark(id, setup.today))
        assertEquals(listOf(setup.yesterday), kit.presenceDays(id))
        assertEquals(UnmarkResult.AlreadyUnmarked, checkins.unmark(id, setup.today))
        assertEquals(UnmarkResult.NotYet, checkins.unmark(id, setup.tomorrow))
        assertEquals(UnmarkResult.OutsideTheDates, checkins.unmark(id, setup.today.plusDays(9)))
        assertEquals(UnmarkResult.NotFound, checkins.unmark(id + 1000, setup.today))
        assertEquals(listOf(setup.yesterday), kit.presenceDays(id))
    }

    @Test
    fun `t6 c07 a registration that does not include the day is marked all the same, and the fee that is due does not change`() {
        val setup = kit.start()
        val owner = kit.account()
        val id = kit.rows.register(setup.id, owner, "Luca", "Rossi", days = mapOf(setup.today to "full", setup.tomorrow to "lunch"))
        val info = reader.byId(setup.id)!!
        val before = Quota.due(Offer(info.fees, info.days), registrations.find(id)!!.presence)

        val marked = checkins.mark(id, setup.yesterday, setup.martaId)

        assertEquals(MarkResult.Marked(confirmed = false, notExpected = true), marked)
        assertEquals(MarkResult.Marked(confirmed = false, notExpected = false), checkins.mark(id, setup.today, setup.martaId))
        assertEquals(3500, before)
        assertEquals(before, Quota.due(Offer(info.fees, info.days), registrations.find(id)!!.presence), "the presence does not touch what is due: only correcting the registration does")
    }

    // ---- In forse and withdrawn (criterion 8) ----------------------------------------------------------------------------------------------

    @Test
    fun `t6 c08 marking a child who is in forse confirms the registration in the same transaction, and both happen or neither does`() {
        val setup = kit.start()
        val maybe = kit.rows.register(setup.id, kit.account(), "Sara", "Neri", status = RegistrationStatus.maybe)

        assertEquals(MarkResult.Marked(confirmed = true, notExpected = false), checkins.mark(maybe, setup.today, setup.martaId))
        assertEquals("confirmed", kit.statusOf(maybe))
        // A second day of the same child: it is confirmed already.
        assertEquals(MarkResult.Marked(confirmed = false, notExpected = false), checkins.mark(maybe, setup.yesterday, setup.martaId))

        // The confirmation fails (a constraint that only this test adds): the presence that was written before it goes back too.
        val other = kit.rows.register(setup.id, kit.account(), "Elena", "Marino", status = RegistrationStatus.maybe)
        dsl.execute("alter table registration add constraint test_no_confirmation check (status <> 'confirmed') not valid")
        try {
            kotlin.runCatching { checkins.mark(other, setup.today, setup.martaId) }.also { assertTrue(it.isFailure, "the transaction fails as a whole") }
        } finally {
            dsl.execute("alter table registration drop constraint test_no_confirmation")
        }
        assertEquals("maybe", kit.statusOf(other))
        assertEquals(emptyList(), kit.presenceDays(other), "no presence without the confirmation")
        // And the other way: the presence cannot be written, so the registration stays in forse.
        dsl.execute("alter table checkin add constraint test_cannot_write check (false) not valid")
        try {
            kotlin.runCatching { checkins.mark(other, setup.today, setup.martaId) }.also { assertTrue(it.isFailure) }
        } finally {
            dsl.execute("alter table checkin drop constraint test_cannot_write")
        }
        assertEquals("maybe", kit.statusOf(other))
    }

    @Test
    fun `t6 c08 a withdrawn registration is not marked, its presences can be taken away, and Rimetti confermata puts it back`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Anna", "Rossi")
        checkins.mark(id, setup.yesterday, setup.martaId)
        assertEquals(ChangeResult.Done, registrations.withdraw(id, null))

        assertEquals(MarkResult.Withdrawn, checkins.mark(id, setup.today, setup.martaId))
        assertEquals(listOf(setup.yesterday), kit.presenceDays(id), "the presences that were there stay")
        // To move them by hand to the other copy of a duplicate they can be taken away.
        assertEquals(UnmarkResult.Unmarked, checkins.unmark(id, setup.yesterday))

        assertEquals(RestoreResult.Done, registrations.restoreConfirmed(id))
        assertEquals("confirmed", kit.statusOf(id))
        assertEquals(RestoreResult.AlreadyDone, registrations.restoreConfirmed(id), "a second tap is the same confirmation")
        assertIs<MarkResult.Marked>(checkins.mark(id, setup.today, setup.martaId))
        assertEquals(RestoreResult.NotFound, registrations.restoreConfirmed(id + 1000))
    }

    @Test
    fun `t6 c08 a withdrawn registration whose days are not those of the gathering any more is not put back by a tap, and in a draft nothing is`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Anna", "Rossi", status = RegistrationStatus.withdrawn, days = mapOf(setup.tomorrow to "full"))
        // The last day goes (the registration is withdrawn: it does not hold it).
        val info = reader.byId(setup.id)!!
        assertEquals(DatesResult.Saved, gatherings.saveDates(setup.id, info.title, setup.yesterday, setup.today, info.place, usage, arrivals))

        assertEquals(RestoreResult.MustBeCorrected, registrations.restoreConfirmed(id))
        assertEquals("withdrawn", kit.statusOf(id))

        // In a draft there are no registrations to put back, and no presences to mark.
        val inDraft = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi", status = RegistrationStatus.withdrawn)
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.draft).where(GATHERING.ID.eq(setup.id)).execute()
        assertEquals(RestoreResult.NotAllowed, registrations.restoreConfirmed(inDraft))
        assertEquals(MarkResult.NotFound, checkins.mark(inDraft, setup.today, setup.martaId))
    }

    @Test
    fun `t6 c08 a withdrawal and a presence are in a queue, and the one that comes second finds what the first did`() {
        val setup = kit.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            // 1. A withdrawal is in the middle (it holds the registration for update): the presence waits, and then it is refused.
            val first = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val withdrawing = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    registrations.lock(tx, first)
                    holding.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    tx.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(first)).execute()
                }
            }
            assertTrue(holding.await(10, TimeUnit.SECONDS))
            val marking = LockWait.awaitBlocked(dsl, "the presence should wait for the withdrawal that is in progress") { pool.submit<MarkResult> { checkins.mark(first, setup.today, setup.martaId) } }
            release.countDown()
            withdrawing.get(20, TimeUnit.SECONDS)
            assertEquals(MarkResult.Withdrawn, marking.get(20, TimeUnit.SECONDS))
            assertEquals(emptyList(), kit.presenceDays(first), "the presence is not made")

            // 2. A presence is in the middle (it holds the registration for update and has written its row): the withdrawal waits, and comes after it.
            val second = kit.rows.register(setup.id, kit.account(), "Anna", "Rossi")
            val marked = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val presence = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    registrations.lock(tx, second)
                    tx.insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, second).set(CHECKIN.DAY, setup.today).set(CHECKIN.RECORDED_AT, java.time.OffsetDateTime.now(clock))
                        .set(CHECKIN.RECORDED_BY_ACCOUNT_ID, setup.martaId).execute()
                    marked.countDown()
                    finish.await(20, TimeUnit.SECONDS)
                }
            }
            assertTrue(marked.await(10, TimeUnit.SECONDS))
            val withdraws = LockWait.awaitBlocked(dsl, "the withdrawal should wait for the presence that is being made") { pool.submit<ChangeResult> { registrations.withdraw(second, null) } }
            finish.countDown()
            presence.get(20, TimeUnit.SECONDS)
            assertEquals(ChangeResult.Done, withdraws.get(20, TimeUnit.SECONDS))
            assertEquals(listOf(setup.today), kit.presenceDays(second), "the presence was made before the withdrawal")
            assertEquals("withdrawn", kit.statusOf(second))
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- Two teachers (criterion 20) -------------------------------------------------------------------------------------------------------

    @Test
    fun `t6 c20 two teachers who mark the same child on the same day at the same moment leave one presence, and both are told it is there`() {
        val setup = kit.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { round ->
                val id = kit.rows.register(setup.id, kit.account(), "Bambino$round", "Rossi")
                val barrier = CyclicBarrier(2)
                val teachers = listOf(setup.martaId, setup.paoloId)
                val results = teachers.map { teacher -> pool.submit<MarkResult> { barrier.await(10, TimeUnit.SECONDS); checkins.mark(id, setup.today, teacher) } }.map { it.get(30, TimeUnit.SECONDS) }

                assertEquals(1, results.count { it is MarkResult.Marked }, "round $round: $results")
                assertEquals(1, results.count { it is MarkResult.AlreadyMarked }, "round $round: $results")
                assertEquals(1, dsl.fetchCount(CHECKIN, CHECKIN.REGISTRATION_ID.eq(id)))
                val already = results.filterIsInstance<MarkResult.AlreadyMarked>().single()
                assertEquals(checkins.ofRegistration(id).single().byAccountId, already.byAccountId, "the answer says whose presence it is")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t6 c20 the same teacher touching twice, and taking away twice, gets the same answer the second time`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")

        assertIs<MarkResult.Marked>(checkins.mark(id, setup.today, setup.martaId))
        val again = checkins.mark(id, setup.today, setup.martaId)
        assertIs<MarkResult.AlreadyMarked>(again)
        assertEquals(setup.martaId, again.byAccountId)
        assertEquals(1, kit.presenceCount())
        assertEquals(UnmarkResult.Unmarked, checkins.unmark(id, setup.today))
        assertEquals(UnmarkResult.AlreadyUnmarked, checkins.unmark(id, setup.today))
    }

    // ---- Payments (criteria 10-13) ---------------------------------------------------------------------------------------------------------

    @Test
    fun `t6 c10 c11 each payment is a row with its amount, its moment and the teacher, and the amount paid is the sum`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")

        assertIs<PaymentResult.Registered>(payments.register(id, UUID.randomUUID(), 2500, setup.paoloId))
        assertIs<PaymentResult.Registered>(payments.register(id, UUID.randomUUID(), 1050, setup.martaId))

        val rows = payments.ofRegistration(id)
        assertEquals(listOf(2500, 1050), rows.map { it.cents })
        assertEquals(listOf(setup.paoloId, setup.martaId), rows.map { it.byAccountId })
        assertTrue(rows.all { java.time.Duration.between(it.at, java.time.OffsetDateTime.now(clock)).abs().seconds < 60 })
        assertEquals(mapOf(id to 3550), payments.totals(setup.id))
        assertEquals(PaymentResult.NotFound, payments.register(id + 1000, UUID.randomUUID(), 100, setup.martaId))
        assertEquals(2, kit.paymentCount())
    }

    @Test
    fun `t6 c02 the money collected is the sum of all the payments of the gathering and of each teacher, withdrawn registrations included`() {
        val setup = kit.start()
        val a = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")
        val b = kit.rows.register(setup.id, kit.account(), "Sara", "Neri", status = RegistrationStatus.withdrawn)
        kit.payment(a, 6000, setup.martaId)
        kit.payment(a, 1250, setup.paoloId)
        kit.payment(b, 3000, setup.paoloId)
        kit.payment(b, 500, setup.giuliaId)

        val collected = payments.collectedBy(setup.id)

        // By surname: Bianchi, Serra, Verdi.
        assertEquals(listOf("Marta Bianchi" to 6000, "Giulia Serra" to 500, "Paolo Verdi" to 4250), collected.map { it.name to it.cents })
        assertEquals(10750, collected.sumOf { it.cents })
        assertEquals(mapOf(a to 7250, b to 3500), payments.totals(setup.id))

        // A person who is not a teacher any more is still counted: last, with no name.
        kit.removeTeacher(setup.martaId)
        val later = payments.collectedBy(setup.id)
        assertEquals(listOf("Giulia Serra" to 500, "Paolo Verdi" to 4250, null to 6000), later.map { it.name to it.cents })
        assertEquals(10750, later.sumOf { it.cents })
    }

    @Test
    fun `t6 c12 the same form sent twice makes one payment, also when the two arrive at the same moment, and a code cannot be used for another registration`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")
        val other = kit.rows.register(setup.id, kit.account(), "Anna", "Rossi")
        val request = UUID.randomUUID()

        val first = payments.register(id, request, 2500, setup.martaId)
        assertIs<PaymentResult.Registered>(first)
        assertEquals(PaymentResult.AlreadyRegistered(first.id), payments.register(id, request, 2500, setup.martaId))
        assertEquals(PaymentResult.RequestUsedElsewhere, payments.register(other, request, 2500, setup.martaId))
        assertEquals(1, kit.paymentCount())

        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { round ->
                val code = UUID.randomUUID()
                val barrier = CyclicBarrier(2)
                val results = listOf(setup.martaId, setup.paoloId).map { teacher -> pool.submit<PaymentResult> { barrier.await(10, TimeUnit.SECONDS); payments.register(other, code, 1000, teacher) } }.map { it.get(30, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it is PaymentResult.Registered }, "round $round: $results")
                assertEquals(1, results.count { it is PaymentResult.AlreadyRegistered }, "round $round: $results")
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1 + 10, kit.paymentCount())
    }

    @Test
    fun `t6 c13 taking a payment away changes the sums, a payment that is gone is the same answer, and a payment of another registration is not found`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")
        val other = kit.rows.register(setup.id, kit.account(), "Anna", "Rossi")
        val wrong = kit.payment(id, 2500, setup.paoloId)
        kit.payment(id, 1000, setup.martaId)
        val ofOther = kit.payment(other, 4000, setup.martaId)

        assertEquals(RemoveResult.NotFound, payments.remove(id, ofOther), "it is not a payment of this registration")
        assertEquals(RemoveResult.Removed(2500), payments.remove(id, wrong))
        assertEquals(mapOf(id to 1000, other to 4000), payments.totals(setup.id))
        assertEquals(RemoveResult.AlreadyRemoved, payments.remove(id, wrong))
        assertEquals(RemoveResult.AlreadyRemoved, payments.remove(id, wrong + 5000))
        assertEquals(2, kit.paymentCount())
    }

    // ---- Fees and days that change (criterion 14) ------------------------------------------------------------------------------------------

    @Test
    fun `t6 c14 when the fees or the days change the payments stay as they are and only what is due, and so the balance, changes`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")
        kit.payment(id, 3500, setup.martaId)
        fun due(): Int? = reader.byId(setup.id)!!.let { Quota.due(Offer(it.fees, it.days), registrations.find(id)!!.presence) }
        fun balance(): Balance = Balance.of(due(), payments.totals(setup.id)[id] ?: 0, withdrawn = false)
        assertEquals(6000, due())
        assertEquals(Balance.ToPay(2500), balance())

        // The full fee goes up.
        assertEquals(FeesResult.Saved, gatherings.saveFees(setup.id, Fees(7000, 2500, 1000), usage))
        assertEquals(7000, due())
        assertEquals(listOf(3500), payments.ofRegistration(id).map { it.cents })
        assertEquals(Balance.ToPay(3500), balance())

        // The days of the registration change: Saturday only, a whole day.
        val info = reader.byId(setup.id)!!
        val check = RegistrationChecks.check(
            FormInput("333 123 4567", listOf(ChildInput(firstName = "Luca", lastName = "Rossi", birthDay = "4", birthMonth = "4", birthYear = "2016", presence = "days", days = mapOf(Quota.key(setup.today) to "full"), status = "confirmed"))),
            Offer(info.fees, info.days), info.start, setup.today, withHealth = false,
        )
        assertEquals(ChangeResult.Done, registrations.update(id, null, check.phone, check.children.single()))
        assertEquals(2500, due())
        assertEquals(listOf(3500), payments.ofRegistration(id).map { it.cents })
        assertEquals(Balance.Over(1000), balance())
    }

    // ---- Before the start and after the end (criterion 15) ---------------------------------------------------------------------------------

    @Test
    fun `t6 c15 payments are registered and taken away before the start of the gathering, and presences are not marked in advance`() {
        val setup = kit.start(first = 3, last = 5)
        val id = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")

        val paid = payments.register(id, UUID.randomUUID(), 2000, setup.martaId)
        assertIs<PaymentResult.Registered>(paid)
        assertEquals(RemoveResult.Removed(2000), payments.remove(id, paid.id))
        assertEquals(MarkResult.NotYet, checkins.mark(id, setup.today.plusDays(3), setup.martaId))
        assertEquals(UnmarkResult.NotYet, checkins.unmark(id, setup.today.plusDays(3)))
        assertEquals(0, kit.presenceCount())
    }

    @Test
    fun `t6 c15 after the end the payments and the presences of its days are still corrected, for the paper register that is copied`() {
        val setup = kit.start(first = -5, last = -3)
        val id = kit.rows.register(setup.id, kit.account(), "Luca", "Rossi")
        val firstDay = setup.today.minusDays(5)

        assertIs<MarkResult.Marked>(checkins.mark(id, firstDay, setup.martaId))
        assertEquals(UnmarkResult.Unmarked, checkins.unmark(id, firstDay))
        assertIs<MarkResult.Marked>(checkins.mark(id, firstDay.plusDays(2), setup.paoloId))
        assertIs<PaymentResult.Registered>(payments.register(id, UUID.randomUUID(), 6000, setup.martaId))
        // Not the days that were never in the gathering.
        assertEquals(MarkResult.OutsideTheDates, checkins.mark(id, setup.today, setup.martaId))
        assertTrue(reader.byId(setup.id)!!.over)
    }

    // ---- The days that the dates cannot take away (criterion 29) ---------------------------------------------------------------------------

    @Test
    fun `t6 c29 a day with a presence is not taken away by changing the dates, even when the registration is withdrawn, and a day that nobody holds goes`() {
        val setup = kit.start()
        val info = reader.byId(setup.id)!!
        // One registration is withdrawn, one is not; both chose today only. The withdrawn one has a presence today and yesterday.
        val withdrawn = kit.rows.register(setup.id, kit.account(), "Sara", "Neri", status = RegistrationStatus.withdrawn, days = mapOf(setup.today to "full"))
        kit.rows.register(setup.id, kit.account(), "Anna", "Rossi", days = mapOf(setup.today to "full"))
        kit.presence(withdrawn, setup.today, setup.martaId)
        kit.presence(withdrawn, setup.yesterday, setup.martaId)
        fun save(start: java.time.LocalDate, end: java.time.LocalDate) = gatherings.saveDates(setup.id, info.title, start, end, info.place, usage, arrivals)

        // Yesterday is held by a presence alone (no registration chose it, the withdrawn one does not count).
        assertEquals(DatesResult.DaysInUse(listOf(DayInUse(setup.yesterday, registrations = 0, presences = 1))), save(setup.today, setup.tomorrow))
        // Today is held by a registration and by a presence: the sentence has both. Tomorrow is held by nobody.
        assertEquals(DatesResult.DaysInUse(listOf(DayInUse(setup.today, registrations = 1, presences = 1))), save(setup.yesterday, setup.yesterday))
        assertEquals(setup.yesterday, reader.byId(setup.id)!!.start, "nothing was changed")
        assertEquals(setup.tomorrow, reader.byId(setup.id)!!.end)

        // The last day, held by nobody, goes; a day is added after it and nothing is asked.
        assertEquals(DatesResult.Saved, save(setup.yesterday, setup.today))
        assertEquals(setup.today, reader.byId(setup.id)!!.end)
        assertEquals(DatesResult.Saved, save(setup.yesterday, setup.today.plusDays(3)))
        assertEquals(mapOf(setup.today to 1, setup.yesterday to 1), arrivals.presenceUsers(dsl, setup.id, listOf(setup.yesterday, setup.today, setup.tomorrow)))
        assertEquals(emptyMap(), arrivals.presenceUsers(dsl, setup.id, emptyList()))
    }
}
