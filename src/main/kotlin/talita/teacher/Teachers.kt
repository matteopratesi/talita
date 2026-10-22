package talita.teacher

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.account.TeacherName
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER

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
}

/** The teachers: an account with a name. The name is written by the teacher, never by someone else. */
@Component
class Teachers(private val dsl: DSLContext) {

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
     * TALITA-4 adds here, in the same transaction and under the same lock, the check that the teacher is not a
     * custodian of a gathering that is not over (and the message of the dialog that says so).
     */
    fun remove(accountId: Long): RemovalResult = dsl.transactionResult { configuration ->
        val transaction = DSL.using(configuration)
        val locked = transaction.select(TEACHER.ACCOUNT_ID, TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(TEACHER)
            .orderBy(TEACHER.ACCOUNT_ID).forUpdate().fetch()
        val target = locked.firstOrNull { it[TEACHER.ACCOUNT_ID] == accountId } ?: return@transactionResult RemovalResult.NotATeacher
        if (locked.size <= 1) return@transactionResult RemovalResult.LastTeacher

        transaction.deleteFrom(TEACHER).where(TEACHER.ACCOUNT_ID.eq(accountId)).execute()
        RemovalResult.Removed(TeacherName(target[TEACHER.FIRST_NAME]!!, target[TEACHER.LAST_NAME]!!))
    }
}
