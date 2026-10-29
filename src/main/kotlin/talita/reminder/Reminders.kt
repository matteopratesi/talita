package talita.reminder

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import talita.jooq.enums.RegistrationStatus
import talita.jooq.enums.ReminderKind
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.REGISTRATION

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
 * The reminders by email (TALITA-13): who would receive each kind, and (with the step that follows) the sending. This is the only class that decides who a reminder is
 * for, so the counts that the teachers see and the people who are written to are always the same.
 *
 * **Who receives «in forse»**: the accounts that have at least one registration «In forse» at the gathering (one mail for the account even with two children in forse).
 * A registration from the paper form has no account and no address, so nobody. **Who receives the «avviso»**: the accounts that have children among «I tuoi figli»
 * (`child.account_id`), asked for the avviso (the moment of the consent is there) and have **no** registration at this gathering, not even a withdrawn one: whoever
 * has already registered, or withdrawn, knows that the gathering is open.
 *
 * **Never**: an account that stopped the reminders (for both kinds), an account with nothing (it came in and registered nobody: an account is made by anyone, and the
 * mail would carry the title and the dates that the address of the information page protects), an address that is not an account (a list of a parish, one typed by
 * the teachers), and an invitation to be a teacher. The login links and the invitations are not reminders: they always leave.
 */
@Service
class Reminders(private val dsl: DSLContext) {

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
}
