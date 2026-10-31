package talita.account

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER
import java.time.Clock
import java.time.LocalDate

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

/**
 * The accounts: one row per email address, born at the first successful login. The row has the address and the day it was born (the day of Rome, from the
 * one clock: TALITA-7 deletes the accounts that stay empty 30 days later). Nothing else about the use of an account is kept: no last access, no hour, no
 * address of the network, no browser.
 */
@Component
class Accounts(private val dsl: DSLContext, private val clock: Clock) {

    /** The id of the account of [email]; the account is created, with today's date, if the address has none. */
    fun findOrCreate(email: EmailAddress): Long {
        dsl.insertInto(ACCOUNT)
            .set(ACCOUNT.EMAIL, email.value)
            .set(ACCOUNT.CREATED_ON, LocalDate.now(clock))
            .onConflict(ACCOUNT.EMAIL).doNothing()
            .execute()
        return dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email.value)).fetchSingle(ACCOUNT.ID)!!
    }

    /**
     * The account with this id for a login that has no address to start from (the cookie of «Ricordami su questo telefono», a passkey): what the session remembers about it, or null if the
     * account is gone. **It never creates the account**: a cookie or a passkey of an account that was deleted is worth nothing, and the account does not come back to life by being used (TALITA-7,
     * criterion 26). The other reader is [AccountUserDetailsService], which is for the link and creates the account at the first login.
     */
    fun userDetailsById(id: Long): AccountUserDetails? =
        dsl.select(ACCOUNT.ID, ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchOne { AccountUserDetails(it[ACCOUNT.ID]!!, it[ACCOUNT.EMAIL]!!) }

    /** The number of the sessions of [id] (it only grows, V12), or null if the account is gone. A session born with another number is over at its next request. */
    fun sessionGeneration(id: Long): Int? = dsl.select(ACCOUNT.SESSION_GENERATION).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchOne(ACCOUNT.SESSION_GENERATION)

    /**
     * Closes every session of [id] but the one that the caller then writes the new number into: raises the number of the sessions and returns it. Inside the transaction of whoever calls
     * ([context]), so that «Esci da tutti gli altri dispositivi» and the rows of the devices that it deletes go together.
     */
    fun raiseSessionGeneration(context: DSLContext, id: Long): Int =
        context.update(ACCOUNT).set(ACCOUNT.SESSION_GENERATION, ACCOUNT.SESSION_GENERATION.plus(1)).where(ACCOUNT.ID.eq(id)).returning(ACCOUNT.SESSION_GENERATION).fetchOne()?.get(ACCOUNT.SESSION_GENERATION) ?: 0

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
