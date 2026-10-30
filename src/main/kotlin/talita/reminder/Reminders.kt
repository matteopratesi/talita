package talita.reminder

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import talita.account.PublicUrl
import talita.gathering.GatheringFormat
import talita.gathering.GatheringInfo
import talita.gathering.GatheringReader
import talita.gathering.PublicCodes
import talita.jooq.enums.RegistrationStatus
import talita.jooq.enums.ReminderKind
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REMINDER_BATCH
import talita.mail.MailKind
import talita.mail.MailQuota
import talita.mail.MailResult
import talita.mail.Mailer
import java.time.Clock
import java.time.OffsetDateTime

/**
 * The two kinds of reminder (TALITA-13). The word is [code], the one that goes in the `id` of the block, in the body of the request of a teacher and in the log: never a
 * name or an address. [column] is the same kind in the database (`reminder_kind`).
 */
enum class ReminderType(val code: String, val column: ReminderKind) {
    /** A registration that is «In forse»: the mail invites to confirm it. */
    MAYBE("maybe", ReminderKind.maybe),

    /** The avviso: someone who has children from an earlier gathering and asked to be told when the registrations of a new one open. */
    NOTICE("notice", ReminderKind.notice),
    ;

    companion object {
        /** The kind with this word, or null for anything else: a request made by hand with another word sends nothing. */
        fun ofCode(code: String?): ReminderType? = entries.firstOrNull { it.code == code }
    }
}

/** Who a reminder is for: the account, with the address that the mail goes to. Never shown to a teacher, never in the log. */
data class Recipient(val accountId: Long, val email: String)

/**
 * The last time a kind of reminder was sent for a gathering, from the register (`reminder_batch`): [recipients] people were written to, and the mail did not leave for
 * [failed] of them.
 */
data class LastBatch(val sentAt: OffsetDateTime, val recipients: Int, val failed: Int) {

    /** To how many people the mail left: what the pages say («a 4 persone»). */
    val reached: Int get() = recipients - failed

    /**
     * From when the next one of this kind can go: **exactly** [Reminders.WAIT_DAYS] days (168 hours) after, whatever the clock of the wall says. In the week of the change
     * between winter time and summer time the hour that the page says is one hour more or less than the one of the last send; the rule is the same for the page and for the
     * check at the moment of sending, so what the page says is when the button works.
     */
    val again: OffsetDateTime get() = sentAt.plusDays(Reminders.WAIT_DAYS)
}

/** What came of asking to send a reminder. Every answer but [Sent] and [TooSoon] means that no mail left (and the register has no new row). */
sealed interface SendResult {
    /** The gathering does not exist. */
    data object NotFound : SendResult

    /** The registrations are not open (a draft, closed) or the gathering is over: reminders go with the registrations open. */
    data object NotOpen : SendResult

    /** The last one of this kind is not [Reminders.WAIT_DAYS] days old: nothing is sent, and [last] says when it left. A double tap, or two teachers, end here too. */
    data class TooSoon(val last: LastBatch) : SendResult

    /** Nobody would receive it (a request made by hand: the page has no button then). */
    data object Nobody : SendResult

    /** The day does not have as many reminders as this one needs: [remaining] can still go, [needed] are needed. Nothing is sent. */
    data class QuotaLow(val remaining: Int, val needed: Int) : SendResult

    /** No mail left: no server, no sender, no public address, or a server that is down or too slow. The register has no row, and the teacher can try again at once. */
    data object NotSent : SendResult

    /** The mail left for [reached] of [recipients] people (the others, [recipients] minus [reached], did not get it); the register has its row, in [last]. */
    data class Sent(val recipients: Int, val reached: Int, val last: LastBatch) : SendResult {
        val failed: Int get() = recipients - reached
    }
}

/**
 * The reminders by email (TALITA-13): who receives each kind, and the sending. This is the only class that decides who a reminder is for, so the counts that the
 * teachers see and the people who are written to are always the same.
 *
 * **Who receives «in forse»**: the accounts that have at least one registration «In forse» at the gathering (one mail for the account even with two children in forse).
 * A registration from the paper form has no account and no address, so nobody. **Who receives the «avviso»**: the accounts that have children among «I tuoi figli»
 * (`child.account_id`), asked for the avviso (the moment of the consent is there) and have **no** registration at this gathering, not even a withdrawn one: whoever
 * has already registered, or withdrawn, knows that the gathering is open.
 *
 * **Never**: an account that stopped the reminders (for both kinds), an account with nothing (it came in and registered nobody: an account is made by anyone, and the
 * mail would carry the title and the dates that the address of the information page protects), an address that is not an account (a list of a parish, one typed by
 * the teachers), and an invitation to be a teacher. The login links and the invitations are not reminders: they always leave.
 *
 * **Sending** ([send]) is one transaction under an advisory lock of Postgres on the gathering and the kind, so that a double tap or two teachers at the same moment send
 * once: the second finds the row of the first. Inside it, in this order: the gathering is open, the last one of this kind is [WAIT_DAYS] days old or more, the people are
 * worked out **there** (not before: who is in forse when the lock is held is who gets the mail), the day has enough reminders left, **the codes to stop are written and
 * committed in a transaction of their own** (a code that is in a mailbox must exist whatever happens next: a rollback after the mails left would leave a link that stops
 * nobody), the mails leave in one connection, and the row of the register is written only if at least one mail left. The log has the gathering, the kind and the numbers,
 * never an address, a code or a link.
 */
@Service
class Reminders(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val gatherings: GatheringReader,
    private val mailer: Mailer,
    private val quota: MailQuota,
    private val publicUrl: PublicUrl,
    transactions: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(Reminders::class.java)

    /**
     * A transaction that is **never** the one around it: it commits when its block ends, whatever the caller does after. `dsl.transaction` is not that: the one of Spring Boot
     * is `PROPAGATION_NESTED`, a savepoint inside the transaction that is already there, which a later rollback takes away with everything else.
     */
    private val ofItsOwn = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    // ---- Who ---------------------------------------------------------------------------------------------------------------------

    /** The condition on `account` for the people who would receive [type] at [gatheringId]. */
    private fun receives(gatheringId: Long, type: ReminderType): Condition {
        val active = ACCOUNT.REMINDERS_OFF_AT.isNull
        val atThisGathering = DSL.selectOne().from(REGISTRATION).where(REGISTRATION.ACCOUNT_ID.eq(ACCOUNT.ID)).and(REGISTRATION.GATHERING_ID.eq(gatheringId))
        return when (type) {
            ReminderType.MAYBE -> active.and(DSL.exists(atThisGathering.and(REGISTRATION.STATUS.eq(RegistrationStatus.maybe))))
            ReminderType.NOTICE ->
                active
                    .and(ACCOUNT.NOTICE_CONSENT_AT.isNotNull)
                    .and(DSL.exists(DSL.selectOne().from(CHILD).where(CHILD.ACCOUNT_ID.eq(ACCOUNT.ID))))
                    .and(DSL.notExists(atThisGathering))
        }
    }

    /** How many people would receive [type] at [gatheringId] now: what the card of the teachers says. */
    fun count(gatheringId: Long, type: ReminderType): Int = count(dsl, gatheringId, type)

    internal fun count(context: DSLContext, gatheringId: Long, type: ReminderType): Int =
        context.fetchCount(ACCOUNT, receives(gatheringId, type))

    /** The people who receive [type] at [gatheringId], in the order of their accounts. Inside the transaction of [context] when it is sending. */
    internal fun recipients(context: DSLContext, gatheringId: Long, type: ReminderType): List<Recipient> =
        context.select(ACCOUNT.ID, ACCOUNT.EMAIL).from(ACCOUNT).where(receives(gatheringId, type)).orderBy(ACCOUNT.ID)
            .fetch { Recipient(it[ACCOUNT.ID]!!, it[ACCOUNT.EMAIL]!!) }

    // ---- The register ----------------------------------------------------------------------------------------------------------------

    /** The last batch of [type] for [gatheringId], or null if none was ever sent. */
    fun lastBatch(gatheringId: Long, type: ReminderType): LastBatch? = lastBatch(dsl, gatheringId, type)

    internal fun lastBatch(context: DSLContext, gatheringId: Long, type: ReminderType): LastBatch? =
        context.select(REMINDER_BATCH.SENT_AT, REMINDER_BATCH.RECIPIENTS, REMINDER_BATCH.FAILED).from(REMINDER_BATCH)
            .where(REMINDER_BATCH.GATHERING_ID.eq(gatheringId)).and(REMINDER_BATCH.KIND.eq(type.column))
            .orderBy(REMINDER_BATCH.SENT_AT.desc(), REMINDER_BATCH.ID.desc()).limit(1)
            .fetchOne { LastBatch(it[REMINDER_BATCH.SENT_AT]!!, it[REMINDER_BATCH.RECIPIENTS]!!.toInt(), it[REMINDER_BATCH.FAILED]!!.toInt()) }

    // ---- Sending -----------------------------------------------------------------------------------------------------------------------

    /** Sends [type] to the people who receive it at [gatheringId], if everything allows it; see the description of the class for what is checked, in which order. */
    fun send(gatheringId: Long, type: ReminderType): SendResult {
        if (gatherings.byId(gatheringId) == null) return SendResult.NotFound
        if (publicUrl.of("/iscrizioni") == null) {
            log.error("Reminders not sent: talita.public-url is not set or is not an address.")
            return SendResult.NotSent
        }
        return dsl.transactionResult { configuration ->
            val tx = DSL.using(configuration)
            // Two requests for the same gathering and kind wait for each other here: the second one finds the row that the first one wrote (and sends nothing).
            tx.fetch("select pg_advisory_xact_lock(?, ?)", LOCK_NAMESPACE, lockKey(gatheringId, type))
            val gathering = gatherings.sharedIn(tx, gatheringId) ?: return@transactionResult SendResult.NotFound
            if (!gathering.registrationsPossibleToday) return@transactionResult SendResult.NotOpen
            lastBatch(tx, gatheringId, type)?.let { last ->
                if (OffsetDateTime.now(clock).isBefore(last.again)) return@transactionResult SendResult.TooSoon(last)
            }
            val people = recipients(tx, gatheringId, type)
            if (people.isEmpty()) return@transactionResult SendResult.Nobody
            val remaining = quota.remaining(MailKind.REMINDER)
            if (people.size > remaining) {
                log.info("Reminders not sent: gathering {}, {}, {} left of the day and {} needed.", gatheringId, type.code, remaining, people.size)
                return@transactionResult SendResult.QuotaLow(remaining, people.size)
            }

            // Committed before any mail leaves (not with `tx`: a failure after the mails left, say the row of the register, would take the codes away with it, and the links
            // «Non mandarmi più promemoria» of the mails in the mailboxes would point to codes that no account has). It writes only for people who are going to be written to.
            val codes = checkNotNull(ofItsOwn.execute { codesFor(dsl, people) })
            val mails = people.mapNotNull { person -> codes[person.accountId]?.let { mail(type, gathering, person, it) } }
            val results = mailer.sendAll(mails, MailKind.REMINDER)
            val reached = results.count { it == MailResult.SENT }
            if (reached == 0) {
                // Everything was refused by the count of the day (another kind of mail took the places in the meantime) or nothing left: no row, one can try again at once.
                return@transactionResult if (results.isNotEmpty() && results.all { it == MailResult.DAILY_LIMIT }) {
                    SendResult.QuotaLow(quota.remaining(MailKind.REMINDER), mails.size)
                } else {
                    SendResult.NotSent
                }
            }
            val sentAt = OffsetDateTime.now(clock)
            tx.insertInto(REMINDER_BATCH)
                .set(REMINDER_BATCH.GATHERING_ID, gatheringId)
                .set(REMINDER_BATCH.KIND, type.column)
                .set(REMINDER_BATCH.SENT_AT, sentAt)
                .set(REMINDER_BATCH.RECIPIENTS, mails.size.toShort())
                .set(REMINDER_BATCH.FAILED, (mails.size - reached).toShort())
                .execute()
            log.info("Reminders sent: gathering {}, {}, {} of {}.", gatheringId, type.code, reached, mails.size)
            SendResult.Sent(mails.size, reached, LastBatch(sentAt, mails.size, mails.size - reached))
        }
    }

    /** The mail of [type] for [person]: the links start from `talita.public-url` and never from the request, and the code to stop is only in the link to stop. */
    private fun mail(type: ReminderType, gathering: GatheringInfo, person: Recipient, code: String) = run {
        val stop = checkNotNull(publicUrl.of("/promemoria/smetti?codice=$code")) { "the public address was there a moment ago" }
        val dates = GatheringFormat.range(gathering.start, gathering.end)
        when (type) {
            ReminderType.MAYBE -> ReminderMails.maybe(person.email, gathering.title, dates, checkNotNull(publicUrl.of("/iscrizioni")), stop)
            ReminderType.NOTICE -> ReminderMails.notice(person.email, gathering.title, dates, checkNotNull(publicUrl.of("/info/${gathering.code}")), stop)
        }
    }

    /**
     * The code to stop of each of [people]: the one they have, or a new one (96 random bits, written at the first reminder: `update … where reminder_code is null`, so two
     * requests cannot give an account two). An account that is gone in the meantime has none and is left out. It runs in [ofItsOwn], with the [DSLContext] of the application
     * (which reads the connection of the transaction that is current, that one).
     */
    private fun codesFor(tx: DSLContext, people: List<Recipient>): Map<Long, String> {
        val ids = people.map { it.accountId }
        val codes = tx.select(ACCOUNT.ID, ACCOUNT.REMINDER_CODE).from(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).fetch()
            .mapNotNull { row -> row[ACCOUNT.REMINDER_CODE]?.let { row[ACCOUNT.ID]!! to it } }.toMap().toMutableMap()
        for (id in ids.filter { it !in codes }) {
            val code = PublicCodes.next()
            val written = tx.update(ACCOUNT).set(ACCOUNT.REMINDER_CODE, code).where(ACCOUNT.ID.eq(id)).and(ACCOUNT.REMINDER_CODE.isNull).execute()
            val kept = if (written == 1) code else tx.select(ACCOUNT.REMINDER_CODE).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchOne(ACCOUNT.REMINDER_CODE)
            if (kept != null) codes[id] = kept
        }
        return codes
    }

    companion object {
        /** A reminder of a kind can be sent again [WAIT_DAYS] days after the last one: the same words in the dialog, in the card and in the rule. */
        const val WAIT_DAYS = 7L

        /** The first number of the two of `pg_advisory_xact_lock(int, int)`: the key of this ticket, so that it never meets the single number of another lock. */
        internal const val LOCK_NAMESPACE = 13

        /** The second number: the gathering and the kind (ids are far below the limit of an int). */
        internal fun lockKey(gatheringId: Long, type: ReminderType): Int = (gatheringId * ReminderType.entries.size + type.ordinal).toInt()
    }
}
