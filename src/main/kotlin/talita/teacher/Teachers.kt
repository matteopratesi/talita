package talita.teacher

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.account.TeacherName
import talita.gathering.Custodianship
import talita.gathering.Custodianships
import talita.gathering.GatheringRef
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER
import java.time.Clock
import java.time.LocalDate

/** What a teacher may write as name and surname: the rules of the invitation and of the profile are the same. */
object PersonName {

    const val MAX_LENGTH = 100

    const val FIRST_MISSING = "Scrivi il nome."
    const val LAST_MISSING = "Scrivi il cognome."
    const val TOO_LONG = "Usa al massimo $MAX_LENGTH caratteri."

    /** What a person wrote in the two fields, and what is wrong with it (null: nothing). */
    data class Check(val first: String, val last: String, val firstError: String?, val lastError: String?) {
        val valid: Boolean get() = firstError == null && lastError == null
        val errors: Int get() = listOfNotNull(firstError, lastError).size
        fun name(): TeacherName = TeacherName(first, last)
    }

    /**
     * Name and surname are required, at most 100 characters each after the spaces at the sides, and letters
     * of every language are fine, with accents and apostrophes. Nothing else is asked: no pattern, because a
     * name that a rule does not expect is still a name.
     */
    fun check(firstRaw: String?, lastRaw: String?): Check {
        val (first, firstError) = one(firstRaw, FIRST_MISSING)
        val (last, lastError) = one(lastRaw, LAST_MISSING)
        return Check(first, last, firstError, lastError)
    }

    private fun one(raw: String?, missing: String): Pair<String, String?> {
        val value = raw.orEmpty().trim()
        val error = when {
            value.isEmpty() || value.any { it.isISOControl() } -> missing
            value.length > MAX_LENGTH -> TOO_LONG
            else -> null
        }
        return value to error
    }
}

/** A teacher as the list shows them: the id of the account (it is what a form sends, never an address) and the name. */
data class TeacherRow(val accountId: Long, val name: TeacherName)

/** What came of taking a teacher away. */
sealed interface RemovalResult {
    /** The person is not a teacher any more; their name was deleted, the account stays. */
    data class Removed(val name: TeacherName) : RemovalResult

    /** The person was not a teacher (somebody else took them away a moment ago). */
    data object NotATeacher : RemovalResult

    /** The only teacher: there must always be at least one. */
    data object LastTeacher : RemovalResult

    /** The person is a custodian of a gathering that is not over: they have to be taken away from its custodians first (criterion 40). */
    data class IsCustodian(val name: TeacherName, val gatherings: List<GatheringRef>) : RemovalResult
}

/**
 * The teachers: an account with a name. The name is written by the teacher, never by someone else. [clock] says what day it is (the
 * day of Rome) for «a gathering that is not over».
 */
@Component
class Teachers(private val dsl: DSLContext, private val clock: Clock) {

    fun count(): Int = dsl.fetchCount(TEACHER)

    fun isTeacher(email: String): Boolean =
        dsl.fetchExists(dsl.selectOne().from(TEACHER).join(ACCOUNT).on(ACCOUNT.ID.eq(TEACHER.ACCOUNT_ID)).where(ACCOUNT.EMAIL.eq(email)))

    /** Every teacher by name and surname, in alphabetical order (the addresses are not in the list on purpose). */
    fun all(): List<TeacherRow> =
        dsl.select(TEACHER.ACCOUNT_ID, TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(TEACHER)
            .orderBy(DSL.lower(TEACHER.LAST_NAME), DSL.lower(TEACHER.FIRST_NAME), TEACHER.ACCOUNT_ID)
            .fetch { TeacherRow(it[TEACHER.ACCOUNT_ID]!!, TeacherName(it[TEACHER.FIRST_NAME]!!, it[TEACHER.LAST_NAME]!!)) }

    /** Makes [accountId] a teacher with this name. If it already is, nothing changes. */
    fun create(accountId: Long, name: TeacherName) {
        dsl.insertInto(TEACHER)
            .set(TEACHER.ACCOUNT_ID, accountId)
            .set(TEACHER.FIRST_NAME, name.first)
            .set(TEACHER.LAST_NAME, name.last)
            .onConflict(TEACHER.ACCOUNT_ID).doNothing()
            .execute()
    }

    /** A teacher corrects their name. False if the person is not a teacher. */
    fun rename(accountId: Long, name: TeacherName): Boolean =
        dsl.update(TEACHER).set(TEACHER.FIRST_NAME, name.first).set(TEACHER.LAST_NAME, name.last)
            .where(TEACHER.ACCOUNT_ID.eq(accountId)).execute() > 0

    /**
     * Takes a teacher away (they become a parent: the account stays, the name is deleted), but never the last one.
     *
     * All in one transaction: first every row of `teacher` is locked, then the teachers are counted, then the
     * row is deleted. Two teachers who take each other away at the same moment are in a queue: the second one
     * finds one teacher and is told no. Without the lock both would see two and both would go ahead.
     *
     * Under the same lock, and in the same transaction, a teacher who is a custodian of a gathering that is not over (a draft, or one
     * whose last day has not passed) is not taken away ([RemovalResult.IsCustodian], criterion 40): the custodians of such a
     * gathering are saved with the teachers locked in share mode (`Gatherings.saveCustodians`), so a custodianship written at the
     * same moment is either seen here or refused there. For a gathering that is over the row of the custodians goes with the
     * teacher (the foreign key cascades).
     */
    fun remove(accountId: Long): RemovalResult = dsl.transactionResult { configuration ->
        val transaction = DSL.using(configuration)
        when (val check = lockAndCheck(transaction, accountId)) {
            is TeacherCheck.Blocked -> check.result
            is TeacherCheck.NotATeacher -> RemovalResult.NotATeacher
            is TeacherCheck.Free -> {
                deleteRow(transaction, accountId)
                RemovalResult.Removed(check.name)
            }
        }
    }

    /** What the check that comes before taking a teacher away found. */
    sealed interface TeacherCheck {
        /** The person is not a teacher. */
        data object NotATeacher : TeacherCheck

        /** The person can be taken away: [name] is the name that goes with the row. */
        data class Free(val name: TeacherName) : TeacherCheck

        /** The person cannot be taken away: the last teacher, or a custodian of a gathering that is not over. */
        data class Blocked(val result: RemovalResult) : TeacherCheck
    }

    /**
     * The check of [remove], on its own, inside the transaction of [transaction]: locks every row of `teacher`, counts the teachers and looks at the custodianships
     * of [accountId]. Nothing is deleted. The cancelling of an account (`talita.retention.ErasureService`) asks it first, under the same lock and with the same
     * words, so «Togli» and «Cancella il mio account» can never disagree about who may go.
     */
    fun lockAndCheck(transaction: DSLContext, accountId: Long, today: LocalDate = LocalDate.now(clock)): TeacherCheck {
        val locked = transaction.select(TEACHER.ACCOUNT_ID, TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(TEACHER)
            .orderBy(TEACHER.ACCOUNT_ID).forUpdate().fetch()
        val target = locked.firstOrNull { it[TEACHER.ACCOUNT_ID] == accountId } ?: return TeacherCheck.NotATeacher
        if (locked.size <= 1) return TeacherCheck.Blocked(RemovalResult.LastTeacher)

        val name = TeacherName(target[TEACHER.FIRST_NAME]!!, target[TEACHER.LAST_NAME]!!)
        val keeping = Custodianships.unfinished(transaction, accountId, today)
        if (keeping.isNotEmpty()) return TeacherCheck.Blocked(RemovalResult.IsCustodian(name, keeping))
        return TeacherCheck.Free(name)
    }

    /** Deletes the row of the teacher (and, for the gatherings that are over, their links as custodian). After [lockAndCheck] said [TeacherCheck.Free], in the same transaction. */
    fun deleteRow(transaction: DSLContext, accountId: Long) {
        transaction.deleteFrom(TEACHER).where(TEACHER.ACCOUNT_ID.eq(accountId)).execute()
    }

    /**
     * Why [accountId] cannot be taken away now, without locking anything: for what the pages say before anyone asks (the profile). Null if they can, or if they are
     * not a teacher. The same rules as [lockAndCheck]; the one who asks to be taken away finds them again inside the transaction.
     */
    fun blockOf(accountId: Long, today: LocalDate = LocalDate.now(clock)): RemovalResult? {
        val count = dsl.fetchCount(TEACHER)
        if (!dsl.fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(accountId))) return null
        if (count <= 1) return RemovalResult.LastTeacher
        val keeping = Custodianships.unfinished(dsl, accountId, today)
        if (keeping.isEmpty()) return null
        val name = dsl.select(TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(TEACHER).where(TEACHER.ACCOUNT_ID.eq(accountId)).fetchSingle { TeacherName(it[TEACHER.FIRST_NAME]!!, it[TEACHER.LAST_NAME]!!) }
        return RemovalResult.IsCustodian(name, keeping)
    }

    /** Where each teacher is a custodian, split by whether the gathering is over: what «Maestri» says in the row and in the dialog of «Togli». */
    fun custodianships(): Map<Long, Custodianship> = Custodianships.byTeacher(dsl, LocalDate.now(clock))
}
