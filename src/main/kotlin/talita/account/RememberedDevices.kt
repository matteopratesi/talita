package talita.account

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.jooq.tables.references.TEACHER
import talita.retention.RetentionPolicy
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Base64

/**
 * A row of `remembered_device`: a browser that touched «Ricordami su questo telefono». It holds the SHA-256 of the value of the cookie and never the value, and never says anything
 * about the device. [toString] says nothing either, so that a row cannot end up in a log.
 */
class DeviceRow(
    val id: Long,
    val accountId: Long,
    val selector: String,
    val validatorHash: ByteArray,
    val createdOn: LocalDate,
    /** The SHA-256 of the value that the cookie had before the last change, and the moment of the change: both, or neither. Never the value. */
    val previousHash: ByteArray? = null,
    val rotatedAt: OffsetDateTime? = null,
) {
    override fun toString(): String = "DeviceRow"
}

/** What a value that a browser presents is for a row: the last one, the one before it (good for [RememberedDevices.ROTATION_MARGIN] after the change), or none (a copy). */
enum class Presented { LAST, PREVIOUS, NONE }

/** The cookie of «Ricordami su questo telefono» as the browser holds it: a [selector] that says which row, and a [value] that only the browser knows. */
class DeviceCookie(val selector: String, val value: String) {
    override fun toString(): String = "DeviceCookie"
}

/** A device that was just made: its row, and the cookie to give to the browser (the only moment the value exists outside the browser). */
class NewDevice(val row: DeviceRow, val cookie: DeviceCookie)

/**
 * **The devices that enter without a link, and the only class that reads or writes their table** (TALITA-15, criteria 3-6, 10, 29-32): a test fails if another class of the application
 * uses the table that jOOQ generates for it.
 *
 * - **The row** has the account, a random selector (128 bits), the SHA-256 of a random value (256 bits) and the day it was made (a day of Rome, from the one clock). The value is never
 *   written, not even here: whoever reads the database cannot enter.
 * - **The expiry is not in the row.** It is the day of the touch plus the length of the role of the person today ([RetentionPolicy.deviceGoneOn]: 30 days for a parent, 7 for a teacher), so
 *   a parent who becomes a teacher shortens the rows that he or she already has. No last use that is kept, no name of the device, no browser, no address of the network. The one moment
 *   that the row holds, `rotated_at` (the last re-entry with the cookie), is only there for the margin of 60 seconds and the job empties it the night after ([clearRotations]).
 * - **At most [MAX_PER_ACCOUNT] rows per account**: the eleventh takes away the oldest. A browser that touches the box again has one row only (the one of its cookie goes).
 * - **The value changes at every re-entry** ([rotate]): a value that is not the last one with a selector that exists is a cookie that was copied, and the row goes.
 */
@Component
class RememberedDevices(private val dsl: DSLContext, private val clock: Clock, private val policy: RetentionPolicy) {

    private val random = SecureRandom()

    /** Today, a day of Rome. */
    fun today(): LocalDate = LocalDate.now(clock)

    private fun toRow(record: org.jooq.Record): DeviceRow = DeviceRow(
        record[REMEMBERED_DEVICE.ID]!!, record[REMEMBERED_DEVICE.ACCOUNT_ID]!!, record[REMEMBERED_DEVICE.SELECTOR]!!, record[REMEMBERED_DEVICE.VALIDATOR_HASH]!!, record[REMEMBERED_DEVICE.CREATED_ON]!!,
        record[REMEMBERED_DEVICE.PREVIOUS_HASH], record[REMEMBERED_DEVICE.ROTATED_AT],
    )

    fun find(selector: String): DeviceRow? = dsl.selectFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.SELECTOR.eq(selector)).fetchOne()?.let(::toRow)

    /** The row with this id, or null if it is gone (taken away from another device, expired, the account was deleted). */
    fun get(id: Long): DeviceRow? = dsl.selectFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ID.eq(id)).fetchOne()?.let(::toRow)

    fun exists(id: Long): Boolean = dsl.fetchExists(REMEMBERED_DEVICE, REMEMBERED_DEVICE.ID.eq(id))

    /** The devices of [accountId], the most recent first (by the day, then the order they were made in). */
    fun ofAccount(accountId: Long): List<DeviceRow> =
        dsl.selectFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId)).orderBy(REMEMBERED_DEVICE.CREATED_ON.desc(), REMEMBERED_DEVICE.ID.desc()).fetch().map(::toRow)

    fun count(accountId: Long): Int = dsl.fetchCount(REMEMBERED_DEVICE, REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId))

    /**
     * What [value] is for [row] (compared without stopping at the first different byte): the last value, the one before it **if the change was less than [ROTATION_MARGIN] ago**, or none. A value
     * from two changes ago has no hash in the row any more, so it is none at once; and after the margin the previous value is none too: from then on it is a copy.
     */
    fun presented(row: DeviceRow, value: String): Presented {
        val hashed = hash(value)
        if (MessageDigest.isEqual(hashed, row.validatorHash)) return Presented.LAST
        val previous = row.previousHash
        val at = row.rotatedAt
        if (previous != null && at != null && MessageDigest.isEqual(hashed, previous) && clock.instant().isBefore(at.toInstant().plus(ROTATION_MARGIN))) return Presented.PREVIOUS
        return Presented.NONE
    }

    /** The row that [cookie] belongs to: the selector exists and the value is the last one or the one before it inside the margin. Null otherwise. */
    fun match(cookie: DeviceCookie): DeviceRow? {
        val row = find(cookie.selector) ?: return null
        return if (presented(row, cookie.value) != Presented.NONE) row else null
    }

    /** The device is past its day: from the day it says, whatever hour the job runs. [teacher] is the role of the person **now**. */
    fun expired(row: DeviceRow, teacher: Boolean): Boolean = policy.deviceExpired(row.createdOn, teacher, today())

    /** Whether the account is a teacher now (the length of the rows follows the role). */
    fun isTeacher(accountId: Long): Boolean = dsl.fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(accountId))

    /**
     * Makes a device for [accountId], in one transaction. The row of [replacing] (the cookie that the browser presented: it is about to be overwritten) goes, and if the account is left with
     * [MAX_PER_ACCOUNT] rows the oldest goes too, so that it never has more.
     */
    fun add(accountId: Long, replacing: Long?): NewDevice = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        // The rows of one account are changed one at a time: two phones that touch the box together do not make an eleventh.
        tx.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).forUpdate().fetch()
        if (replacing != null) tx.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ID.eq(replacing)).execute()
        val excess = tx.select(REMEMBERED_DEVICE.ID).from(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId))
            .orderBy(REMEMBERED_DEVICE.CREATED_ON.desc(), REMEMBERED_DEVICE.ID.desc()).offset(MAX_PER_ACCOUNT - 1).fetch(REMEMBERED_DEVICE.ID).map { it!! }
        if (excess.isNotEmpty()) tx.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ID.`in`(excess)).execute()
        val cookie = DeviceCookie(randomToken(SELECTOR_BYTES), randomToken(VALIDATOR_BYTES))
        val id = tx.insertInto(REMEMBERED_DEVICE)
            .set(REMEMBERED_DEVICE.ACCOUNT_ID, accountId)
            .set(REMEMBERED_DEVICE.SELECTOR, cookie.selector)
            .set(REMEMBERED_DEVICE.VALIDATOR_HASH, hash(cookie.value))
            .set(REMEMBERED_DEVICE.CREATED_ON, today())
            .returning(REMEMBERED_DEVICE.ID).fetchSingle().id!!
        NewDevice(DeviceRow(id, accountId, cookie.selector, hash(cookie.value), today()), cookie)
    }

    /**
     * Gives [row] a new value and returns the cookie with it, or null if the value of the row is no longer the one that [row] was read with: someone else has used the cookie in the meantime
     * (two tabs that open together after the session ended). The change is made only if the hash is still the old one, so one of two requests that come together wins and the other does not.
     */
    fun rotate(row: DeviceRow): DeviceCookie? {
        val cookie = DeviceCookie(row.selector, randomToken(VALIDATOR_BYTES))
        // The value that goes is kept as its hash for the margin, with the moment of the change: the tab that arrives a moment later with the old cookie is not a thief.
        val changed = dsl.update(REMEMBERED_DEVICE).set(REMEMBERED_DEVICE.VALIDATOR_HASH, hash(cookie.value))
            .set(REMEMBERED_DEVICE.PREVIOUS_HASH, row.validatorHash).set(REMEMBERED_DEVICE.ROTATED_AT, OffsetDateTime.now(clock))
            .where(REMEMBERED_DEVICE.ID.eq(row.id)).and(REMEMBERED_DEVICE.VALIDATOR_HASH.eq(row.validatorHash)).execute()
        return if (changed == 1) cookie else null
    }

    /**
     * Empties `previous_hash` and `rotated_at` of the rows whose margin of [ROTATION_MARGIN] is over as of [now] (the job, every night): `rotated_at` is the moment of the last re-entry with the cookie,
     * which only the margin needs, and the minimization does not keep it a night longer than that. Both go together (a rule of the table). Returns how many rows were emptied.
     */
    fun clearRotations(context: DSLContext, now: OffsetDateTime): Int =
        context.update(REMEMBERED_DEVICE).setNull(REMEMBERED_DEVICE.PREVIOUS_HASH).setNull(REMEMBERED_DEVICE.ROTATED_AT)
            .where(REMEMBERED_DEVICE.ROTATED_AT.isNotNull).and(REMEMBERED_DEVICE.ROTATED_AT.le(now.minus(ROTATION_MARGIN))).execute()

    fun delete(id: Long): Boolean = dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ID.eq(id)).execute() > 0

    /** Takes away the device [id] **of [accountId]**: an id of another account, or one that is not there, takes away nothing (the answer is the same, criterion 17). */
    fun deleteOwn(accountId: Long, id: Long): Boolean =
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ID.eq(id)).and(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId)).execute() > 0

    /** The devices of [accountId] except [keep] (the one of the browser that asks): «Esci da tutti gli altri dispositivi». Inside the transaction of the caller. */
    fun deleteOthers(context: DSLContext, accountId: Long, keep: Long?): Int =
        context.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId)).and(if (keep == null) DSL.trueCondition() else REMEMBERED_DEVICE.ID.ne(keep)).execute()

    // ---- What deletes (TALITA-7, criteria 29-32) -----------------------------------------------------------------------------------

    /** Deletes every device of [accountId]: the account is deleted, or the request is made again after a restore. Returns how many rows went. */
    fun removeOfAccount(context: DSLContext, accountId: Long): Int = context.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId)).execute()

    /** The same for several accounts (the job that deletes the accounts that stayed empty). */
    fun removeOfAccounts(context: DSLContext, accountIds: Collection<Long>): Int =
        if (accountIds.isEmpty()) 0 else context.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(accountIds)).execute()

    /** Deletes the devices that are past their day as of [today] (a day of Rome), with the role that each account has now. Returns how many rows went. */
    fun removeExpired(context: DSLContext, today: LocalDate): Int {
        val rows = context.select(REMEMBERED_DEVICE.ID, REMEMBERED_DEVICE.CREATED_ON, DSL.exists(DSL.selectOne().from(TEACHER).where(TEACHER.ACCOUNT_ID.eq(REMEMBERED_DEVICE.ACCOUNT_ID))))
            .from(REMEMBERED_DEVICE).fetch()
        val gone = rows.filter { policy.deviceExpired(it.value2()!!, it.value3()!!, today) }.map { it.value1()!! }
        return if (gone.isEmpty()) 0 else context.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ID.`in`(gone)).execute()
    }

    companion object {
        /** The most devices of an account (criterion 10). */
        const val MAX_PER_ACCOUNT = 10

        /**
         * For how long after a change of the value of the cookie the value before it is good too (decision of Matteo, 31 October): two tabs that the phone reopens together after the session ended
         * present the same cookie, and the second is not a thief. Not a setting: a number that decides what a copy is does not change by the configuration.
         */
        val ROTATION_MARGIN: Duration = Duration.ofSeconds(60)

        private const val SELECTOR_BYTES = 16
        private const val VALIDATOR_BYTES = 32

        private val base64 = Base64.getUrlEncoder().withoutPadding()

        /** SHA-256 of the value of a cookie: what the table holds in its place. */
        fun hash(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(base64::encodeToString)
}
