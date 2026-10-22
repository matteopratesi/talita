package talita.account

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import talita.jooq.tables.references.ACCOUNT

/**
 * The person who is making the request, as read from the database for this request (see
 * [CurrentAccounts]). Ticket code asks for it as a parameter of a controller method:
 *
 * ```kotlin
 * @GetMapping("/iscrizioni")
 * fun registrations(account: CurrentAccount, model: Model): String
 * ```
 *
 * @property id the id of the account: what the registrations of the parent are tied to
 * @property email the address with which the person entered, in lowercase
 */
data class CurrentAccount(val id: Long, val email: String) {

    /** The letters of the "Profilo" circle in the header. */
    val initials: String get() = email.take(1).uppercase()
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

    /** The account with this id as it is now, or null if it is gone. */
    fun current(id: Long): CurrentAccount? =
        dsl.select(ACCOUNT.ID, ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(id))
            .fetchOne { CurrentAccount(it[ACCOUNT.ID]!!, it[ACCOUNT.EMAIL]!!) }
}
