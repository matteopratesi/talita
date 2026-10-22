package talita.account

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER

/** The name of a teacher, as the teacher wrote it when accepting the invitation (or corrected it in the profile). */
data class TeacherName(val first: String, val last: String) {
    val full: String get() = "$first $last"
}

/**
 * The person who is making the request, as read from the database for this request (see
 * [CurrentAccounts]). Ticket code asks for it as a parameter of a controller method:
 *
 * ```kotlin
 * @GetMapping("/iscrizioni")
 * fun registrations(account: CurrentAccount, model: Model): String
 * ```
 *
 * Every account is a parent; a teacher is an account with a [teacher] name. The two roles do not
 * exclude each other: a teacher who is also a parent has one account, the navigation of the teacher,
 * and it is the same account that the registrations of their own children are tied to (TALITA-5).
 *
 * @property id the id of the account: what the registrations of the parent are tied to
 * @property email the address with which the person entered, in lowercase
 * @property teacher the name of the teacher, or null if the person is not a teacher
 */
data class CurrentAccount(val id: Long, val email: String, val teacher: TeacherName? = null) {

    val isTeacher: Boolean get() = teacher != null

    /** The letters of the "Profilo" circle in the header: those of name and surname of a teacher, else the first of the address. */
    val initials: String
        get() = if (teacher != null) (teacher.first.take(1) + teacher.last.take(1)).uppercase() else email.take(1).uppercase()
}

/** The accounts: one row per email address, born at the first successful login. */
@Component
class Accounts(private val dsl: DSLContext) {

    /** The id of the account of [email]; the account is created if the address has none. */
    fun findOrCreate(email: EmailAddress): Long {
        dsl.insertInto(ACCOUNT)
            .set(ACCOUNT.EMAIL, email.value)
            .onConflict(ACCOUNT.EMAIL).doNothing()
            .execute()
        return dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email.value)).fetchSingle(ACCOUNT.ID)!!
    }

    /** The account with this id as it is now, with its role, or null if it is gone. */
    fun current(id: Long): CurrentAccount? =
        dsl.select(ACCOUNT.ID, ACCOUNT.EMAIL, TEACHER.FIRST_NAME, TEACHER.LAST_NAME)
            .from(ACCOUNT)
            .leftJoin(TEACHER).on(TEACHER.ACCOUNT_ID.eq(ACCOUNT.ID))
            .where(ACCOUNT.ID.eq(id))
            .fetchOne {
                val first = it[TEACHER.FIRST_NAME]
                val last = it[TEACHER.LAST_NAME]
                CurrentAccount(it[ACCOUNT.ID]!!, it[ACCOUNT.EMAIL]!!, if (first != null && last != null) TeacherName(first, last) else null)
            }
}
