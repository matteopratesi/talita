package talita.teacher

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import talita.account.TeacherName
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

/** The teachers: an account with a name. The name is written by the teacher, never by someone else. */
@Component
class Teachers(private val dsl: DSLContext) {

    fun count(): Int = dsl.fetchCount(TEACHER)

    /** Makes [accountId] a teacher with this name. If it already is, nothing changes. */
    fun create(accountId: Long, name: TeacherName) {
        dsl.insertInto(TEACHER)
            .set(TEACHER.ACCOUNT_ID, accountId)
            .set(TEACHER.FIRST_NAME, name.first)
            .set(TEACHER.LAST_NAME, name.last)
            .onConflict(TEACHER.ACCOUNT_ID).doNothing()
            .execute()
    }
}
