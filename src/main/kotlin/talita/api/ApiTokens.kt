package talita.api

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.jooq.tables.references.API_TOKEN
import talita.jooq.tables.references.TEACHER
import talita.retention.RetentionPolicy
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.util.Base64

/** The token of a teacher as the profile shows it: never the value, only the day it was made. [toString] says nothing, so that a row cannot end up in a log. */
class ApiTokenRow(val id: Long, val accountId: Long, val createdOn: LocalDate) {
    override fun toString(): String = "ApiTokenRow"
}

/** Who a valid token belongs to: the number of the token (for the limit of the requests) and the account of the teacher. Never printed. */
class ApiPrincipal(val tokenId: Long, val accountId: Long) {
    override fun toString(): String = "ApiPrincipal"
}

/** A token that was just made: the row and the value, which exists outside the database only in this object, for the one answer that shows it. */
class NewApiToken(val row: ApiTokenRow, val value: String) {
    override fun toString(): String = "NewApiToken"
}

/**
 * **The token of a teacher for the APIs of configuration, and the only class that reads or writes its table** (TALITA-21, criteria 1-5): a test fails if another class uses the table that jOOQ generates for it
 * (the retention rule and «Esci da tutti gli altri dispositivi» go through the functions here).
 *
 * - **The value** is `talita_` and 43 characters of base64url (256 random bits). It is shown once. The table holds its SHA-256 and the day it was made (a day of Rome, from the one clock): whoever reads the
 *   database cannot use it. No label, no last use, no address of the network.
 * - **One token per teacher**: making a new one takes the old one away in the same transaction, under the lock of the row of the teacher.
 * - **The expiry is not in the row**: it is the day of creation plus the length of [RetentionPolicy.apiTokens], and from the day after it the token does not work any more (the job deletes the row that night).
 * - **A teacher who is not one any more has no token**: the key to `teacher` has a cascade.
 */
@Component
class ApiTokens(private val dsl: DSLContext, private val clock: Clock, private val policy: RetentionPolicy) {

    private val random = SecureRandom()

    fun today(): LocalDate = LocalDate.now(clock)

    private fun toRow(record: org.jooq.Record): ApiTokenRow = ApiTokenRow(record[API_TOKEN.ID]!!, record[API_TOKEN.ACCOUNT_ID]!!, record[API_TOKEN.CREATED_ON]!!)

    /** The token of [accountId], or null. It may be past its day and not deleted yet: [expired] says. */
    fun of(accountId: Long): ApiTokenRow? = dsl.selectFrom(API_TOKEN).where(API_TOKEN.ACCOUNT_ID.eq(accountId)).fetchOne()?.let(::toRow)

    fun expired(row: ApiTokenRow): Boolean = policy.apiTokenExpired(row.createdOn, today())

    /** The last day on which the token still works. */
    fun lastDay(row: ApiTokenRow): LocalDate = policy.apiTokenLastDay(row.createdOn)

    /** What [createUnlessThere] did. */
    sealed interface Created {
        class Made(val token: NewApiToken) : Created

        /** There is a token that still works and the request was not the confirmed replacement: nothing changed. */
        data object Exists : Created

        data object NotATeacher : Created
    }

    /**
     * «Crea un token» from a card that showed none: **under the lock of the teacher** it makes the token only if there is none that still works ([Created.Exists] otherwise, and nothing changes); an expired
     * one may be replaced. The replacement with the dialog of «Crea un token nuovo» is [create].
     */
    fun createUnlessThere(accountId: Long): Created = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        tx.select(TEACHER.ACCOUNT_ID).from(TEACHER).where(TEACHER.ACCOUNT_ID.eq(accountId)).forUpdate().fetchOne() ?: return@transactionResult Created.NotATeacher
        val current = tx.selectFrom(API_TOKEN).where(API_TOKEN.ACCOUNT_ID.eq(accountId)).fetchOne()?.let(::toRow)
        if (current != null && !expired(current)) return@transactionResult Created.Exists
        Created.Made(insertNew(tx, accountId))
    }

    private fun insertNew(tx: DSLContext, accountId: Long): NewApiToken {
        tx.deleteFrom(API_TOKEN).where(API_TOKEN.ACCOUNT_ID.eq(accountId)).execute()
        val day = today()
        val value = PREFIX + base64.encodeToString(ByteArray(BYTES).also(random::nextBytes))
        val id = tx.insertInto(API_TOKEN)
            .set(API_TOKEN.ACCOUNT_ID, accountId)
            .set(API_TOKEN.TOKEN_HASH, hash(value))
            .set(API_TOKEN.CREATED_ON, day)
            .returning(API_TOKEN.ID).fetchSingle().id!!
        return NewApiToken(ApiTokenRow(id, accountId, day), value)
    }

    /** Makes a token for [accountId], which must be a teacher (null if it is not: a request made by hand after the teacher was taken away). The one it had goes in the same transaction. */
    fun create(accountId: Long): NewApiToken? = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        // The row of the teacher is locked: two taps together leave one token and the second one replaces the first.
        tx.select(TEACHER.ACCOUNT_ID).from(TEACHER).where(TEACHER.ACCOUNT_ID.eq(accountId)).forUpdate().fetchOne() ?: return@transactionResult null
        insertNew(tx, accountId)
    }

    /** Takes away the token [id] **of [accountId]**: a number that is not theirs, or that is gone (a double tap, two pages), takes away nothing and says so. */
    fun revoke(accountId: Long, id: Long): Boolean =
        dsl.deleteFrom(API_TOKEN).where(API_TOKEN.ID.eq(id)).and(API_TOKEN.ACCOUNT_ID.eq(accountId)).execute() > 0

    /** The token of [accountId], inside the transaction of the caller («Esci da tutti gli altri dispositivi»). Returns whether there was one. */
    fun revokeOf(context: DSLContext, accountId: Long): Boolean = context.deleteFrom(API_TOKEN).where(API_TOKEN.ACCOUNT_ID.eq(accountId)).execute() > 0

    /**
     * Who the token [presented] is, or null: a token that is not there, has another form, is revoked, is past its day or is of someone who is not a teacher any more are the same, and the answer cannot
     * tell them apart. The value is compared by its hash.
     */
    fun authenticate(presented: String): ApiPrincipal? {
        if (!FORMAT.matches(presented)) return null
        val row = dsl.selectFrom(API_TOKEN).where(API_TOKEN.TOKEN_HASH.eq(hash(presented))).fetchOne()?.let(::toRow) ?: return null
        if (expired(row)) return null
        return ApiPrincipal(row.id, row.accountId)
    }

    /** Deletes the tokens that are past their day as of [today] (the job). Returns how many rows went. */
    fun removeExpired(context: DSLContext, today: LocalDate): Int {
        val rows = context.select(API_TOKEN.ID, API_TOKEN.CREATED_ON).from(API_TOKEN).fetch()
        val gone = rows.filter { policy.apiTokenExpired(it[API_TOKEN.CREATED_ON]!!, today) }.map { it[API_TOKEN.ID]!! }
        return if (gone.isEmpty()) 0 else context.deleteFrom(API_TOKEN).where(API_TOKEN.ID.`in`(gone)).execute()
    }

    companion object {
        const val PREFIX = "talita_"
        private const val BYTES = 32

        /** `talita_` and 43 characters of base64url. */
        val FORMAT = Regex("^talita_[A-Za-z0-9_-]{43}$")

        private val base64 = Base64.getUrlEncoder().withoutPadding()

        /** SHA-256 of the value of a token: what the table holds in its place. */
        fun hash(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }
}
