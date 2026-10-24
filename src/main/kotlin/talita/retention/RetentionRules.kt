package talita.retention

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import talita.ClockConfiguration
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.registration.HealthNotes
import java.time.LocalDate
import java.time.OffsetDateTime

/** The moment a run of the job is about: the day of Rome (what every rule looks at, never the hour) and the moment itself (what goes in the register). */
data class RetentionContext(val today: LocalDate, val now: OffsetDateTime)

/** What a rule deleted: the name that goes in the register of the deletions (`retention_log.rule`) and how many rows. */
data class Deleted(val rule: String, val rows: Int)

/**
 * **A rule of the job** (criteria 3-7, 14): it deletes what the date says it must, inside the transaction it is given, and says what it deleted. The job
 * ([RetentionJob]) runs the rules in order, each in a transaction of its own under an advisory lock named after the rule, and writes the register of the deletions.
 *
 * Every rule is idempotent: run again on the same day it finds nothing. To add a rule (the rooms of TALITA-11 will) is to add a bean that implements this
 * interface, with the `@Order` that says where it goes: nothing else in the job changes.
 */
interface RetentionRule {

    /** The name of the rule: also the key of its advisory lock, and what the log says when it fails. Without personal data. */
    val name: String

    /** Deletes what is past its date as of [context] inside [tx], and returns what went, rule by rule (a rule may write more than one line of the register). */
    fun apply(tx: DSLContext, context: RetentionContext): List<Deleted>
}

/** The last day of every gathering that is not a draft (a draft has no registrations: no rule touches it, R1 of TALITA-4). Small table, read whole. */
private fun lastDaysOfGatherings(tx: DSLContext): Map<Long, LocalDate> =
    tx.select(GATHERING.ID, GATHERING.END_DATE).from(GATHERING).where(GATHERING.STATUS.ne(GatheringStatus.draft)).fetch().associate { it[GATHERING.ID]!! to it[GATHERING.END_DATE]!! }

/**
 * The notes on health, one month after the end of the gathering (criteria 2, 3): of **every** registration of the gathering, the withdrawn and the paper ones
 * too, and nothing else. They go through the one class of the notes ([HealthNotes]).
 */
@Component
@Order(10)
class NotesRule(private val policy: RetentionPolicy, private val healthNotes: HealthNotes) : RetentionRule {

    override val name: String = "notes"

    override fun apply(tx: DSLContext, context: RetentionContext): List<Deleted> {
        val expired = lastDaysOfGatherings(tx).filterValues { policy.notesExpired(it, context.today) }.keys
        return listOf(Deleted("notes", healthNotes.removeOfGatherings(tx, expired)))
    }
}

/**
 * The registrations, one year after the end of the gathering (criterion 4): the acceptance of the custody, the days, the notes that are left, the register of
 * the readings, the presences and the payments (they go with the registration: `on delete cascade`), and the links with the custodians. The gathering stays: it
 * holds no personal data.
 */
@Component
@Order(20)
class RegistrationsRule(private val policy: RetentionPolicy) : RetentionRule {

    override val name: String = "registrations"

    override fun apply(tx: DSLContext, context: RetentionContext): List<Deleted> {
        val expired = lastDaysOfGatherings(tx).filterValues { policy.registrationsExpired(it, context.today) }.keys
        if (expired.isEmpty()) return listOf(Deleted("registrations", 0))
        val registrations = tx.deleteFrom(REGISTRATION).where(REGISTRATION.GATHERING_ID.`in`(expired)).execute()
        val custodians = tx.deleteFrom(GATHERING_CUSTODIAN).where(GATHERING_CUSTODIAN.GATHERING_ID.`in`(expired)).execute()
        return listOf(Deleted("registrations", registrations + custodians))
    }
}

/**
 * A child with no registration left (criterion 5): the registrations went with their date, or a parent asked for them to go. The rows that another transaction
 * holds locked are skipped (a registration that is being made for that child right now): they are looked at again the next night.
 */
@Component
@Order(30)
class ChildrenRule : RetentionRule {

    override val name: String = "children"

    override fun apply(tx: DSLContext, context: RetentionContext): List<Deleted> {
        val orphans = tx.select(CHILD.ID).from(CHILD)
            .whereNotExists(DSL.selectOne().from(REGISTRATION).where(REGISTRATION.CHILD_ID.eq(CHILD.ID)))
            .forUpdate().skipLocked()
        return listOf(Deleted("children", tx.deleteFrom(CHILD).where(CHILD.ID.`in`(orphans)).execute()))
    }
}

/** The invitations to be a teacher that nobody accepted, 30 days after they expired (criterion 6): the teachers see «Scaduto» in the meantime and can send it again. */
@Component
@Order(40)
class InvitationsRule(private val policy: RetentionPolicy) : RetentionRule {

    override val name: String = "invitations"

    override fun apply(tx: DSLContext, context: RetentionContext): List<Deleted> {
        val gone = tx.select(TEACHER_INVITATION.EMAIL, TEACHER_INVITATION.EXPIRES_AT).from(TEACHER_INVITATION).fetch()
            .filter { !policy.invitationDeletionDate(it[TEACHER_INVITATION.EXPIRES_AT]!!.toInstant()).isAfter(context.today) }.map { it[TEACHER_INVITATION.EMAIL]!! }
        if (gone.isEmpty()) return listOf(Deleted("invitations", 0))
        return listOf(Deleted("invitations", tx.deleteFrom(TEACHER_INVITATION).where(TEACHER_INVITATION.EMAIL.`in`(gone)).execute()))
    }
}

/**
 * The accounts that stayed empty (criteria 7, 8): not a teacher, with no children, no registrations in their name and not in the register of the readings
 * (the register must say who read, so the address stays while the readings do), born at least 30 days ago. What a teacher had marked or typed in (presences,
 * payments, paper registrations) stays, and says «un maestro che non è più tra i maestri» (`on delete set null`).
 */
@Component
@Order(50)
class AccountsRule(private val policy: RetentionPolicy, private val healthNotes: HealthNotes) : RetentionRule {

    override val name: String = "accounts"

    private fun empty(): Condition =
        DSL.notExists(DSL.selectOne().from(TEACHER).where(TEACHER.ACCOUNT_ID.eq(ACCOUNT.ID)))
            .and(DSL.notExists(DSL.selectOne().from(CHILD).where(CHILD.ACCOUNT_ID.eq(ACCOUNT.ID))))
            .and(DSL.notExists(DSL.selectOne().from(REGISTRATION).where(REGISTRATION.ACCOUNT_ID.eq(ACCOUNT.ID))))
            .and(healthNotes.holdsAccount(ACCOUNT.ID).not())

    override fun apply(tx: DSLContext, context: RetentionContext): List<Deleted> {
        val candidates = tx.select(ACCOUNT.ID, ACCOUNT.CREATED_ON).from(ACCOUNT).where(empty()).fetch()
            .filter { !policy.emptyAccountDeletionDate(it[ACCOUNT.CREATED_ON]!!).isAfter(context.today) }.map { it[ACCOUNT.ID]!! }
        if (candidates.isEmpty()) return listOf(Deleted("accounts", 0))
        // The conditions are asked again in the statement that deletes: an account that got a child in the meantime is not deleted.
        return listOf(Deleted("accounts", tx.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(candidates)).and(empty()).execute()))
    }
}

/** The day of Rome of a moment: what the rules look at. */
internal fun OffsetDateTime.dayOfRome(): LocalDate = atZoneSameInstant(ClockConfiguration.ZONE).toLocalDate()
