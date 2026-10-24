package talita.gathering

import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import talita.jooq.tables.references.TEACHER
import java.security.SecureRandom
import java.sql.SQLException
import java.time.Clock
import java.time.LocalDate
import java.util.Base64

/**
 * The code of the public address of a gathering: 16 characters of base64url made from 12 random bytes (96 bits) of
 * [SecureRandom]. Nobody can guess it and it never changes, whatever is edited.
 */
internal object PublicCodes {
    private val random = SecureRandom()

    fun next(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** A step of the state: the state it leaves and the one it reaches. There is no step back to the draft. */
enum class StateAction(val from: GatheringState, val to: GatheringState) {
    OPEN(GatheringState.DRAFT, GatheringState.OPEN),
    CLOSE(GatheringState.OPEN, GatheringState.CLOSED),
    REOPEN(GatheringState.CLOSED, GatheringState.OPEN),
}

/** What came of asking for a step of the state (criteria 9-14). */
sealed interface TransitionResult {
    data object NotFound : TransitionResult

    /** The state is now [state]. */
    data class Done(val state: GatheringState) : TransitionResult

    /** It already was: someone else did the same a moment ago (a double tap, two teachers). The answer is the same confirmation. */
    data class AlreadyDone(val state: GatheringState) : TransitionResult

    /** The draft cannot be opened yet: [missing] says what it lacks. */
    data class Incomplete(val missing: List<Missing>) : TransitionResult

    /** A step that does not exist (from the state the gathering is in). The state does not change. */
    data object NotAllowed : TransitionResult

    /** The registrations of a gathering that is over do not reopen. */
    data object IsOver : TransitionResult

    /** Another gathering is a draft or open: only one at a time. */
    data class OtherIsCurrent(val id: Long, val title: String) : TransitionResult
}

sealed interface CreateResult {
    data class Created(val id: Long) : CreateResult

    /** There is already a gathering that is a draft or open. */
    data class AlreadyCurrent(val id: Long, val title: String, val state: GatheringState) : CreateResult
}

sealed interface CustodiansResult {
    data object Saved : CustodiansResult
    data object NotFound : CustodiansResult

    /** A gathering that is open or closed and not over needs at least one custodian (criterion 8). */
    data object NeedsOne : CustodiansResult
}

/** What came of saving the dates: saved, or refused because registrations have days that the new dates would take away (rule R3). */
sealed interface DatesResult {
    data object Saved : DatesResult
    data object NotFound : DatesResult

    /** Each day that would go, with the number of registrations that have it. */
    data class DaysInUse(val days: List<Pair<LocalDate, Int>>) : DatesResult
}

/** What came of saving the fees: saved, or refused because registrations use a fee that would be emptied (rule R2). */
sealed interface FeesResult {
    data object Saved : FeesResult
    data object NotFound : FeesResult

    /** How many registrations use the daily fee and the fee of the lunch only, among the ones that the new fees would leave empty. */
    data class InUse(val daily: Int, val lunchOnly: Int) : FeesResult
}

/** A gathering that a teacher is in custody of: enough for a sentence and a link. */
data class GatheringRef(val id: Long, val title: String)

/**
 * The gatherings, as the pages of the teachers use them: reading, creating, saving the four parts of the detail and the steps of
 * the state. «Today» is the day of Rome, from the one [Clock] of the application: a test moves it, the real one does not stop.
 *
 * Whatever depends on another row (the one gathering that is not closed, the custodians of a gathering that must not be empty) is
 * decided inside a transaction that first locks the row of the gathering: two teachers who tap at the same moment are in a queue.
 */
@Component
class Gatherings(private val dsl: DSLContext, private val clock: Clock) {

    /** The day of Rome. */
    fun today(): LocalDate = LocalDate.now(clock)

    // ---- Reading --------------------------------------------------------------------------------------------------------------

    fun find(id: Long): Gathering? = dsl.selectFrom(GATHERING).where(GATHERING.ID.eq(id)).fetchOne()?.let(::gathering)

    fun findByCode(code: String): Gathering? = dsl.selectFrom(GATHERING).where(GATHERING.PUBLIC_CODE.eq(code)).fetchOne()?.let(::gathering)

    /** The gathering that is a draft or open: at most one (the database sees to it). */
    fun current(): Gathering? = dsl.selectFrom(GATHERING).where(GATHERING.STATUS.ne(GatheringStatus.closed)).fetchOne()?.let(::gathering)

    /** The gathering that is not a draft with the latest first day, or null (a teacher's list of registrations starts from it). */
    fun latestNotDraft(): Gathering? =
        dsl.selectFrom(GATHERING).where(GATHERING.STATUS.ne(GatheringStatus.draft)).orderBy(GATHERING.START_DATE.desc(), GATHERING.ID.desc()).limit(1).fetchOne()?.let(::gathering)

    /** The closed gatherings, the most recent first. */
    fun closed(): List<Gathering> =
        dsl.selectFrom(GATHERING).where(GATHERING.STATUS.eq(GatheringStatus.closed))
            .orderBy(GATHERING.START_DATE.desc(), GATHERING.ID.desc()).fetch().map(::gathering)

    /** The custodians of a gathering by surname and name. */
    fun custodians(id: Long): List<Custodian> = custodians(dsl, id)

    /** Every teacher, by surname and name: those who can be chosen as custodians. */
    fun teachers(): List<Custodian> =
        dsl.select(TEACHER.ACCOUNT_ID, TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(TEACHER)
            .orderBy(DSL.lower(TEACHER.LAST_NAME), DSL.lower(TEACHER.FIRST_NAME), TEACHER.ACCOUNT_ID)
            .fetch { Custodian(it[TEACHER.ACCOUNT_ID]!!, it[TEACHER.FIRST_NAME]!!, it[TEACHER.LAST_NAME]!!) }

    /** What a draft still lacks to open (criterion 10), in the order of the forms. [today] is the day of Rome. */
    fun missing(gathering: Gathering, custodianCount: Int, today: LocalDate): List<Missing> = buildList {
        if (gathering.place == null) add(Missing.PLACE)
        if (gathering.fees.fullCents == null) add(Missing.FULL_FEE)
        if (custodianCount == 0) add(Missing.CUSTODIAN)
        if (gathering.endDate.isBefore(today)) add(Missing.DATES)
    }

    /**
     * The gathering inside the transaction of [context], with a lock: for sharing (a registration is being made: the others can look, nobody can
     * close it, change its dates or its custodians until the transaction ends) or, with [exclusive], for updating. Null if there is none.
     */
    fun findLocked(context: DSLContext, id: Long, exclusive: Boolean = false): Gathering? {
        val query = context.selectFrom(GATHERING).where(GATHERING.ID.eq(id))
        return (if (exclusive) query.forUpdate() else query.forShare()).fetchOne()?.let(::gathering)
    }

    // ---- Creating -------------------------------------------------------------------------------------------------------------

    /**
     * A new gathering, a draft with a code of its own. If there is one already that is a draft or open it is not created
     * ([CreateResult.AlreadyCurrent]): a pre-check, and the unique index of the database for two requests at the same moment.
     */
    fun create(title: String, start: LocalDate, end: LocalDate): CreateResult {
        repeat(CODE_ATTEMPTS) {
            try {
                val result = dsl.transactionResult { configuration ->
                    val transaction = DSL.using(configuration)
                    val existing = transaction.select(GATHERING.ID, GATHERING.TITLE, GATHERING.STATUS).from(GATHERING)
                        .where(GATHERING.STATUS.ne(GatheringStatus.closed)).fetchOne()
                    if (existing != null) {
                        return@transactionResult CreateResult.AlreadyCurrent(existing[GATHERING.ID]!!, existing[GATHERING.TITLE]!!, GatheringState.of(existing[GATHERING.STATUS]!!))
                    }
                    transaction.insertInto(GATHERING)
                        .set(GATHERING.PUBLIC_CODE, PublicCodes.next())
                        .set(GATHERING.TITLE, title)
                        .set(GATHERING.START_DATE, start)
                        .set(GATHERING.END_DATE, end)
                        .onConflict(GATHERING.PUBLIC_CODE).doNothing()
                        .returningResult(GATHERING.ID).fetchOne()?.value1()?.let { CreateResult.Created(it) }
                }
                // No row back: the code already existed (in practice never), so it is tried again with a new one.
                if (result != null) return result
            } catch (e: RuntimeException) {
                if (!e.isUniqueViolation()) throw e
                // Another teacher created a gathering at the same moment: the unique index let one through.
                return current()?.let { CreateResult.AlreadyCurrent(it.id, it.title, it.state) } ?: throw e
            }
        }
        error("No free public code after $CODE_ATTEMPTS attempts")
    }

    // ---- Saving the parts -----------------------------------------------------------------------------------------------------

    /**
     * Title, dates and place (criteria 17, 31). A day that a registration that is not withdrawn has cannot be taken away by changing the dates (rule R3):
     * the answer says which days and how many registrations. Adding days is always possible. Dates of a gathering that is over are not passed (see the
     * controller). The count is made in the same transaction, under the lock of the gathering.
     */
    fun saveDates(id: Long, title: String, start: LocalDate?, end: LocalDate?, place: String?, usage: RegistrationUsage): DatesResult = dsl.transactionResult { configuration ->
        val transaction = DSL.using(configuration)
        val row = lock(transaction, id) ?: return@transactionResult DatesResult.NotFound
        if (start != null && end != null) {
            val old = gathering(row)
            val kept = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toSet()
            val removed = old.days.filter { it !in kept }
            if (removed.isNotEmpty()) {
                val users = usage.dayUsers(transaction, id, removed).filterValues { it > 0 }
                if (users.isNotEmpty()) return@transactionResult DatesResult.DaysInUse(removed.mapNotNull { day -> users[day]?.let { day to it } })
            }
        }
        val update = transaction.update(GATHERING).set(GATHERING.TITLE, title).set(GATHERING.PLACE, place)
        if (start != null && end != null) update.set(GATHERING.START_DATE, start).set(GATHERING.END_DATE, end)
        update.where(GATHERING.ID.eq(id)).execute()
        DatesResult.Saved
    }

    /**
     * The three fees (criteria 7, 30). A fee that registrations that are not withdrawn use (the daily one, the one of the lunch only) cannot be emptied
     * (rule R2): the answer says how many use each. The fees that stay do not change what is due: it is worked out again from the new ones.
     */
    fun saveFees(id: Long, fees: Fees, usage: RegistrationUsage): FeesResult = dsl.transactionResult { configuration ->
        val transaction = DSL.using(configuration)
        lock(transaction, id) ?: return@transactionResult FeesResult.NotFound
        val users = usage.feeUsers(transaction, id)
        val daily = if (fees.dailyCents == null) users.daily else 0
        val lunch = if (fees.lunchOnlyCents == null) users.lunchOnly else 0
        if (daily > 0 || lunch > 0) return@transactionResult FeesResult.InUse(daily, lunch)
        transaction.update(GATHERING)
            .set(GATHERING.FULL_FEE_CENTS, fees.fullCents)
            .set(GATHERING.DAILY_FEE_CENTS, fees.dailyCents)
            .set(GATHERING.LUNCH_ONLY_FEE_CENTS, fees.lunchOnlyCents)
            .where(GATHERING.ID.eq(id)).execute()
        FeesResult.Saved
    }

    fun saveInfo(id: Long, infoText: String?, whatToBring: String?): Boolean =
        dsl.update(GATHERING).set(GATHERING.INFO_TEXT, infoText).set(GATHERING.WHAT_TO_BRING, whatToBring)
            .where(GATHERING.ID.eq(id)).execute() > 0

    /**
     * Replaces the custodians with [wanted] (ids of teachers). The row of the gathering is locked first, then the rows of the
     * chosen teachers are locked in share mode: a teacher who is taken away at the same moment is either gone before (and is not
     * saved) or waits for this to end and then finds the custodianship (and is refused, see `Teachers.remove`). Someone who is no
     * longer a teacher is left out without a word. An open or closed gathering that is not over needs one at least.
     */
    fun saveCustodians(id: Long, wanted: Set<Long>): CustodiansResult = dsl.transactionResult { configuration ->
        val transaction = DSL.using(configuration)
        val row = lock(transaction, id) ?: return@transactionResult CustodiansResult.NotFound
        val gathering = gathering(row)
        val chosen = if (wanted.isEmpty()) emptyList() else transaction.select(TEACHER.ACCOUNT_ID).from(TEACHER)
            .where(TEACHER.ACCOUNT_ID.`in`(wanted)).orderBy(TEACHER.ACCOUNT_ID).forShare().fetch(TEACHER.ACCOUNT_ID).map { it!! }
        if (chosen.isEmpty() && gathering.state != GatheringState.DRAFT && gathering.isNotOver(today())) {
            return@transactionResult CustodiansResult.NeedsOne
        }
        val notChosen = GATHERING_CUSTODIAN.GATHERING_ID.eq(id).let { if (chosen.isEmpty()) it else it.and(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID.notIn(chosen)) }
        transaction.deleteFrom(GATHERING_CUSTODIAN).where(notChosen).execute()
        for (teacher in chosen) {
            transaction.insertInto(GATHERING_CUSTODIAN)
                .set(GATHERING_CUSTODIAN.GATHERING_ID, id)
                .set(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID, teacher)
                .onConflict().doNothing().execute()
        }
        CustodiansResult.Saved
    }

    // ---- The steps of the state ----------------------------------------------------------------------------------------------

    /**
     * One step of the state (criteria 9-14), all inside a transaction that locks the row of the gathering: two taps at the same
     * moment are in a queue, and the second finds the state already changed and gets the same confirmation as the first
     * ([TransitionResult.AlreadyDone]), never an error. [from] is the state that the page of the person showed (the hidden field of
     * the form); a step that does not leave that state is refused.
     */
    fun change(id: Long, action: StateAction, from: GatheringState? = null): TransitionResult {
        try {
            return dsl.transactionResult { configuration -> changeInTransaction(DSL.using(configuration), id, action, from) }
        } catch (e: RuntimeException) {
            // Another gathering became a draft at the same moment as this one was reopened: the unique index said no.
            if (action == StateAction.REOPEN && e.isUniqueViolation()) {
                current()?.let { return TransitionResult.OtherIsCurrent(it.id, it.title) }
            }
            throw e
        }
    }

    private fun changeInTransaction(transaction: DSLContext, id: Long, action: StateAction, from: GatheringState?): TransitionResult {
        val row = lock(transaction, id) ?: return TransitionResult.NotFound
        val gathering = gathering(row)
        if (gathering.state == action.to) return TransitionResult.AlreadyDone(gathering.state)
        if (gathering.state != action.from || (from != null && from != action.from)) return TransitionResult.NotAllowed

        val today = today()
        when (action) {
            StateAction.OPEN -> {
                val missing = missing(gathering, custodians(transaction, id).size, today)
                if (missing.isNotEmpty()) return TransitionResult.Incomplete(missing)
            }

            StateAction.REOPEN -> {
                if (gathering.endDate.isBefore(today)) return TransitionResult.IsOver
                val other = transaction.select(GATHERING.ID, GATHERING.TITLE).from(GATHERING)
                    .where(GATHERING.STATUS.ne(GatheringStatus.closed)).and(GATHERING.ID.ne(id)).fetchOne()
                if (other != null) return TransitionResult.OtherIsCurrent(other[GATHERING.ID]!!, other[GATHERING.TITLE]!!)
            }

            StateAction.CLOSE -> Unit
        }
        transaction.update(GATHERING).set(GATHERING.STATUS, action.to.column)
            .where(GATHERING.ID.eq(id)).and(GATHERING.STATUS.eq(action.from.column)).execute()
        return TransitionResult.Done(action.to)
    }

    // ---- Support --------------------------------------------------------------------------------------------------------------

    private fun lock(transaction: DSLContext, id: Long): Record? =
        transaction.selectFrom(GATHERING).where(GATHERING.ID.eq(id)).forUpdate().fetchOne()

    internal fun custodians(context: DSLContext, id: Long): List<Custodian> =
        context.select(TEACHER.ACCOUNT_ID, TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(GATHERING_CUSTODIAN)
            .join(TEACHER).on(TEACHER.ACCOUNT_ID.eq(GATHERING_CUSTODIAN.TEACHER_ACCOUNT_ID))
            .where(GATHERING_CUSTODIAN.GATHERING_ID.eq(id))
            .orderBy(DSL.lower(TEACHER.LAST_NAME), DSL.lower(TEACHER.FIRST_NAME), TEACHER.ACCOUNT_ID)
            .fetch { Custodian(it[TEACHER.ACCOUNT_ID]!!, it[TEACHER.FIRST_NAME]!!, it[TEACHER.LAST_NAME]!!) }

    private fun gathering(row: Record): Gathering = Gathering(
        id = row[GATHERING.ID]!!,
        publicCode = row[GATHERING.PUBLIC_CODE]!!,
        title = row[GATHERING.TITLE]!!,
        startDate = row[GATHERING.START_DATE]!!,
        endDate = row[GATHERING.END_DATE]!!,
        place = row[GATHERING.PLACE],
        fees = Fees(row[GATHERING.FULL_FEE_CENTS], row[GATHERING.DAILY_FEE_CENTS], row[GATHERING.LUNCH_ONLY_FEE_CENTS]),
        infoText = row[GATHERING.INFO_TEXT],
        whatToBring = row[GATHERING.WHAT_TO_BRING],
        state = GatheringState.of(row[GATHERING.STATUS]!!),
    )

    private companion object {
        const val CODE_ATTEMPTS = 5
    }
}

/** A violation of a unique index or key (SQLSTATE 23505), wherever it is in the chain of the causes. */
internal fun Throwable.isUniqueViolation(): Boolean =
    generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.any { it is SQLException && it.sqlState == "23505" }
