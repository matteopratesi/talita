package talita.retention

import org.jooq.DSLContext
import talita.jooq.tables.references.RETENTION_LOG
import java.time.OffsetDateTime

/**
 * **The register of the deletions** (art. 5.2 GDPR, criteria 15, 27): a row for each rule that deleted something, and for each deletion made on request. It says
 * when, which rule and how many rows, and nothing else: no name, no address, no date of birth, no phone, no note. The only thing in it that points to a person is the
 * number of the child or of the account deleted on request (`target_id`), kept for 37 days so that a request is made again after a backup older than it is restored;
 * the job then takes the number out (`ExpireRequestIdsRule`), and the row stays, without it.
 *
 * It is written in the transaction that deletes, so a deletion and its row are both there or neither is.
 */
internal object RetentionLog {

    /** The rule of the register for the deletion of a child on request. */
    const val CHILD_REQUEST = "child-request"

    /** The rule of the register for the deletion of an account on request. */
    const val ACCOUNT_REQUEST = "account-request"

    /** A request that was made again after a restore, for a child or for an account. */
    const val CHILD_REDO = "child-redo"
    const val ACCOUNT_REDO = "account-redo"

    /** Writes a row in [tx]. [targetId] only for a request. */
    fun write(tx: DSLContext, at: OffsetDateTime, rule: String, rows: Int, targetId: Long? = null) {
        tx.insertInto(RETENTION_LOG)
            .set(RETENTION_LOG.DONE_AT, at)
            .set(RETENTION_LOG.RULE, rule)
            .set(RETENTION_LOG.ROW_COUNT, rows)
            .set(RETENTION_LOG.TARGET_ID, targetId)
            .execute()
    }

    /** Whether a request of [rule] was made for [targetId] and its number is still in the register: what tells a second request (a double tap) from a number that never existed. */
    fun hasRequest(context: DSLContext, rule: String, targetId: Long): Boolean =
        context.fetchExists(RETENTION_LOG, RETENTION_LOG.RULE.eq(rule).and(RETENTION_LOG.TARGET_ID.eq(targetId)))
}
