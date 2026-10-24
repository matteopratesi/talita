package talita.checkin

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.gathering.ArrivalUsage
import talita.gathering.GatheringInfo
import talita.gathering.GatheringReader
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.REGISTRATION
import talita.registration.RegistrationData
import talita.registration.Registrations
import talita.registration.Rules
import talita.registration.Status
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * A presence as it is kept: the registration, the day, who marked it and when (the one row of that pair). [byAccountId] is null when the account of the teacher
 * who marked it was deleted (TALITA-7): the pages say «un maestro che non è più tra i maestri».
 */
data class Checkin(val registrationId: Long, val day: LocalDate, val at: OffsetDateTime, val byAccountId: Long?)

/** The registration comes on [day]: every day of the gathering, or the days that it chose. */
fun RegistrationData.includes(day: LocalDate): Boolean = presence.allDays || day in presence.days

/** What came of marking a presence (criteria 4, 6-8, 20). */
sealed interface MarkResult {
    /** The presence is made. [confirmed]: the registration was «In forse» and is «Confermata» now. [notExpected]: the registration does not include the day. */
    data class Marked(val confirmed: Boolean, val notExpected: Boolean) : MarkResult

    /** It was marked already, by another teacher a moment before or by the same one twice: the same answer, never an error. */
    data class AlreadyMarked(val byAccountId: Long?, val at: OffsetDateTime) : MarkResult

    /** No such registration (or it belongs to nobody the caller can see: the caller decides). */
    data object NotFound : MarkResult

    /** The registration was withdrawn (maybe while the page was open): nothing was marked. */
    data object Withdrawn : MarkResult

    /** The day is not one of the gathering (the dates changed while the page was open, or a request by hand): nothing was marked. */
    data object OutsideTheDates : MarkResult

    /** The day is after today: a presence is not marked in advance. Nothing was marked. */
    data object NotYet : MarkResult
}

/** What came of taking a presence away (criterion 6). */
sealed interface UnmarkResult {
    data object Unmarked : UnmarkResult

    /** There was none, because another teacher took it away a moment before: the same answer, never an error. */
    data object AlreadyUnmarked : UnmarkResult

    data object NotFound : UnmarkResult

    data object OutsideTheDates : UnmarkResult

    data object NotYet : UnmarkResult
}

/**
 * The presences of each day (criteria 4, 6-8, 19, 20, 29): one row for each registration and day, written by whichever teacher marks first and the same for
 * the second, who finds it. It is also what the gathering asks when the dates change ([ArrivalUsage], rule R3): a day with a presence is not taken away.
 *
 * Marking and taking away start from `Registrations.lock`: the registration is locked for update and then the gathering for sharing, in the order of every other
 * change, so that
 * - a withdrawal and a presence are in a queue: the presence is made before the withdrawal, or the withdrawal comes first and the presence is refused;
 * - two teachers who mark the same child at the same moment are in a queue too, and the second finds the first one's row (and says whose it is);
 * - the dates cannot change in the middle: the day is checked against the dates that the database has now.
 *
 * Marking a child that is «In forse» confirms the registration in the same transaction (`Registrations.confirmIn`): both or neither. Nothing here writes a name,
 * a phone or an amount in the log.
 */
@Component
class Checkins(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val gatherings: GatheringReader,
    private val registrations: Registrations,
) : ArrivalUsage {

    private fun at(): OffsetDateTime = OffsetDateTime.now(clock)

    private fun find(context: DSLContext, id: Long, day: LocalDate): Checkin? =
        context.selectFrom(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(id)).and(CHECKIN.DAY.eq(day))
            .fetchOne { Checkin(it.registrationId!!, it.day!!, it.recordedAt!!, it.recordedByAccountId) }

    private enum class DayProblem { OUTSIDE_THE_DATES, NOT_YET }

    /** Why the day cannot be marked or unmarked: not one of the gathering, or after today. Null when it can. */
    private fun problem(gathering: GatheringInfo, day: LocalDate): DayProblem? = when {
        day !in gathering.days -> DayProblem.OUTSIDE_THE_DATES
        day.isAfter(gatherings.today()) -> DayProblem.NOT_YET
        else -> null
    }

    /**
     * Marks [registrationId] present on [day] for the teacher [byAccountId]: the moment is now. A day of the gathering and not after today, also after the end
     * (the register of paper is copied then). A registration that does not include the day is marked all the same, and the answer says so (criterion 7).
     */
    fun mark(registrationId: Long, day: LocalDate, byAccountId: Long): MarkResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val (registration, gathering) = registrations.lock(tx, registrationId) ?: return@transactionResult MarkResult.NotFound
        if (!Rules.teacherCanChange(gathering)) return@transactionResult MarkResult.NotFound
        if (registration.status == Status.WITHDRAWN) return@transactionResult MarkResult.Withdrawn
        when (problem(gathering, day)) {
            DayProblem.OUTSIDE_THE_DATES -> return@transactionResult MarkResult.OutsideTheDates
            DayProblem.NOT_YET -> return@transactionResult MarkResult.NotYet
            null -> Unit
        }
        find(tx, registrationId, day)?.let { return@transactionResult MarkResult.AlreadyMarked(it.byAccountId, it.at) }
        // The row of the registration is locked: nobody else is writing this pair. The key of the table is the net under it.
        tx.insertInto(CHECKIN)
            .set(CHECKIN.REGISTRATION_ID, registrationId)
            .set(CHECKIN.DAY, day)
            .set(CHECKIN.RECORDED_AT, at())
            .set(CHECKIN.RECORDED_BY_ACCOUNT_ID, byAccountId)
            .onConflict(CHECKIN.REGISTRATION_ID, CHECKIN.DAY).doNothing()
            .execute()
        val confirmed = registration.status == Status.MAYBE && registrations.confirmIn(tx, registrationId)
        MarkResult.Marked(confirmed = confirmed, notExpected = !registration.includes(day))
    }

    /** Takes the presence of [registrationId] on [day] away. Possible also for a withdrawn registration: its presences are moved by hand to the other copy of a duplicate. */
    fun unmark(registrationId: Long, day: LocalDate): UnmarkResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val (_, gathering) = registrations.lock(tx, registrationId) ?: return@transactionResult UnmarkResult.NotFound
        if (!Rules.teacherCanChange(gathering)) return@transactionResult UnmarkResult.NotFound
        when (problem(gathering, day)) {
            DayProblem.OUTSIDE_THE_DATES -> return@transactionResult UnmarkResult.OutsideTheDates
            DayProblem.NOT_YET -> return@transactionResult UnmarkResult.NotYet
            null -> Unit
        }
        val deleted = tx.deleteFrom(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(registrationId)).and(CHECKIN.DAY.eq(day)).execute()
        if (deleted == 0) UnmarkResult.AlreadyUnmarked else UnmarkResult.Unmarked
    }

    // ---- Reading ----------------------------------------------------------------------------------------------------------------------

    private fun fetch(condition: org.jooq.Condition): List<Checkin> =
        dsl.select(CHECKIN.REGISTRATION_ID, CHECKIN.DAY, CHECKIN.RECORDED_AT, CHECKIN.RECORDED_BY_ACCOUNT_ID).from(CHECKIN).join(REGISTRATION).on(REGISTRATION.ID.eq(CHECKIN.REGISTRATION_ID))
            .where(condition).orderBy(CHECKIN.DAY, CHECKIN.RECORDED_AT, CHECKIN.REGISTRATION_ID)
            .fetch { Checkin(it[CHECKIN.REGISTRATION_ID]!!, it[CHECKIN.DAY]!!, it[CHECKIN.RECORDED_AT]!!, it[CHECKIN.RECORDED_BY_ACCOUNT_ID]) }

    /** Every presence of the gathering, in every state of every registration (the pages choose what to show). */
    fun ofGathering(gatheringId: Long): List<Checkin> = fetch(REGISTRATION.GATHERING_ID.eq(gatheringId))

    /** The presences of one registration, by day. */
    fun ofRegistration(registrationId: Long): List<Checkin> = fetch(CHECKIN.REGISTRATION_ID.eq(registrationId))

    override fun presenceUsers(context: DSLContext, gatheringId: Long, days: Collection<LocalDate>): Map<LocalDate, Int> {
        if (days.isEmpty()) return emptyMap()
        return context.select(CHECKIN.DAY, DSL.count()).from(CHECKIN).join(REGISTRATION).on(REGISTRATION.ID.eq(CHECKIN.REGISTRATION_ID))
            .where(REGISTRATION.GATHERING_ID.eq(gatheringId)).and(CHECKIN.DAY.`in`(days))
            .groupBy(CHECKIN.DAY).fetch().associate { it[CHECKIN.DAY]!! to it.value2() }
    }
}
