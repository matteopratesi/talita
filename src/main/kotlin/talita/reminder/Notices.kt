package talita.reminder

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import java.time.Clock
import java.time.OffsetDateTime

/**
 * What an account said about the mail that is not a login link (TALITA-13): [hasChildren] (the avviso is for who has children among «I tuoi figli»), [consentAt] (the moment
 * they asked for the avviso, or null) and [stoppedAt] (the moment they asked for no reminders at all, or null). The two moments are never both there.
 */
data class NoticeState(val hasChildren: Boolean, val consentAt: OffsetDateTime?, val stoppedAt: OffsetDateTime?)

/** What came of asking to save the choice about the avviso. */
sealed interface ConsentResult {
    /** Saved: [at] is the moment of the consent (the one that was there if it was there already), or null if the consent is now taken away. */
    data class Saved(val at: OffsetDateTime?) : ConsentResult

    /** The person stopped the reminders: the avviso cannot be asked for before taking them back. */
    data object Stopped : ConsentResult

    /** No children: the avviso is for who has children among «I tuoi figli», and there is nothing to ask for. */
    data object NoChildren : ConsentResult
}

/**
 * The choices of a person about the reminders (TALITA-13): the consent to the avviso, and the stop, that holds for both kinds. **Each action is of the account that makes
 * it** (an account of the session, or the one of a code): nothing here takes an id of another person.
 *
 * - **Consent** (`account.notice_consent_at`): a moment with the hour, written when the person ticks the box and saves (art. 7.1: being able to show it), and taken away when they
 *   untick it or stop. Saving it again keeps the moment that was there. Not for someone who stopped, and not for someone with no children.
 * - **Stop** (`account.reminders_off_at`): from the profile or from the mail. It takes the consent away **in the same statement** (the database refuses both together), and saving it
 *   again keeps the first moment. The login links and the invitations are not reminders: they always arrive.
 * - **Resume**: takes the stop away. The consent does not come back: whoever wants the avviso asks for it again.
 * - **By code** ([stopByCode]): the link in the mail, `/promemoria/smetti?codice=…`, with a code of 96 bits that is only in the mail. It does not say whether the code exists, and the
 *   code is never in the log. Whoever has the mail (it can be forwarded) can stop the reminders of that account, and do nothing else.
 */
@Service
class Notices(private val dsl: DSLContext, private val clock: Clock) {

    /** What the account has said, or null if there is no such account. */
    fun state(accountId: Long): NoticeState? {
        val row = dsl.select(ACCOUNT.NOTICE_CONSENT_AT, ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).fetchOne() ?: return null
        return NoticeState(dsl.fetchExists(CHILD, CHILD.ACCOUNT_ID.eq(accountId)), row[ACCOUNT.NOTICE_CONSENT_AT], row[ACCOUNT.REMINDERS_OFF_AT])
    }

    /** Saves the box «Avvisami…»: [wanted] ticked or not. */
    fun setConsent(accountId: Long, wanted: Boolean): ConsentResult = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        // The row is locked: a stop that comes at the same moment waits, and the two are never both written.
        val row = tx.select(ACCOUNT.NOTICE_CONSENT_AT, ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).forUpdate().fetchOne()
            ?: return@transactionResult ConsentResult.NoChildren
        if (!wanted) {
            if (row[ACCOUNT.NOTICE_CONSENT_AT] != null) tx.update(ACCOUNT).setNull(ACCOUNT.NOTICE_CONSENT_AT).where(ACCOUNT.ID.eq(accountId)).execute()
            return@transactionResult ConsentResult.Saved(null)
        }
        if (row[ACCOUNT.REMINDERS_OFF_AT] != null) return@transactionResult ConsentResult.Stopped
        if (!tx.fetchExists(CHILD, CHILD.ACCOUNT_ID.eq(accountId))) return@transactionResult ConsentResult.NoChildren
        val at = row[ACCOUNT.NOTICE_CONSENT_AT] ?: OffsetDateTime.now(clock).also { now ->
            tx.update(ACCOUNT).set(ACCOUNT.NOTICE_CONSENT_AT, now).where(ACCOUNT.ID.eq(accountId)).execute()
        }
        ConsentResult.Saved(at)
    }

    /**
     * «Non mandarmi più promemoria»: no reminders, no avviso, and the consent goes. Returns whether there was a consent to take away (the notice says so), or null if there is no such
     * account.
     */
    fun stop(accountId: Long): Boolean? = dsl.transactionResult { configuration ->
        val tx = DSL.using(configuration)
        val row = tx.select(ACCOUNT.NOTICE_CONSENT_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).forUpdate().fetchOne() ?: return@transactionResult null
        stopIn(tx, ACCOUNT.ID.eq(accountId))
        row[ACCOUNT.NOTICE_CONSENT_AT] != null
    }

    /** «Riprendi i promemoria»: the stop is taken away (the consent that went with it does not come back). False if there is no such account. */
    fun resume(accountId: Long): Boolean = dsl.update(ACCOUNT).setNull(ACCOUNT.REMINDERS_OFF_AT).where(ACCOUNT.ID.eq(accountId)).execute() > 0

    /**
     * The tap of the page of the mail: stops the account of [code]. Nothing says whether there was one: a code that does not exist is the same answer, and a code that is not even
     * the shape of one is not looked for. The caller never writes the code in the log.
     */
    fun stopByCode(code: String) {
        if (!CODE.matches(code)) return
        dsl.transaction { configuration -> stopIn(DSL.using(configuration), ACCOUNT.REMINDER_CODE.eq(code)) }
    }

    /** Writes the stop for the accounts that [where] says, with the consent taken away in the same statement; the moment of a stop that was there is kept. */
    private fun stopIn(tx: DSLContext, where: Condition) {
        tx.update(ACCOUNT)
            .set(ACCOUNT.REMINDERS_OFF_AT, DSL.coalesce(ACCOUNT.REMINDERS_OFF_AT, OffsetDateTime.now(clock)))
            .setNull(ACCOUNT.NOTICE_CONSENT_AT)
            .where(where).execute()
    }

    private companion object {
        /** The shape of a code (the same as the constraint of the table): 16 characters of base64url. */
        val CODE = Regex("^[A-Za-z0-9_-]{16}$")
    }
}
