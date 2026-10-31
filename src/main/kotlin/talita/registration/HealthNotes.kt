package talita.registration

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.account.ConfirmationProof
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.TEACHER
import talita.retention.RetentionPolicy
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/** The notes on health of a registration as they are kept, with the moment of the consent and where it was given. */
data class HealthNote(val notes: Notes, val consentAt: OffsetDateTime, val onPaper: Boolean)

/** One reading of the notes by a teacher; [teacher] is null when the person is not among the teachers any more. */
data class Reading(val teacherAccountId: Long, val teacher: String?, val at: OffsetDateTime)

/**
 * The notes read by a teacher, with the moment of the reading that was written for it (the same moment). [note] is null when there are none, and also when
 * their date has passed ([HealthNotes.expired]): then no reading is written either.
 */
data class NotesRead(val note: HealthNote?, val at: OffsetDateTime)

/**
 * **The only class that reads or writes the table of the notes on health and the one of their readings** (criterion 23): the privacy of the
 * data hangs on this, so a test fails if another class of the application uses the tables that jOOQ generates for them. A later ticket
 * (the check-in, the cancellations) goes through here as well.
 *
 * - The parent who owns a registration reads and writes its notes with no trace ([readForOwner], [save], [remove]).
 * - A teacher reads them only by asking ([readForTeacher]): the reading is written first, in the same transaction that gives the answer, so
 *   if it cannot be written nothing is shown. Whatever a teacher changes is a reading as well ([save] with a teacher, [recordReading]).
 * - The register keeps who, which registration and when; never the text, and it does not say whether there was any.
 * - The notes of a registration that is withdrawn or whose consent is taken away are deleted at once ([remove]).
 * - **The notes are gone from their date, not from the hour of the job** (TALITA-7): one month after the end of the gathering ([RetentionPolicy.notesExpired])
 *   nothing here reads them any more, so a page never shows a datum on health that is past its date, even in the hours between midnight and the job. The
 *   deletions of TALITA-7 (the job, the requests of a parent, the cancelling of an account) go through here as well: [removeOfGatherings],
 *   [removeOfRegistrations], [removeConsentedBy]; and the question «does this account appear in the register of the readings» ([holdsAccount]).
 */
@Component
class HealthNotes(private val dsl: DSLContext, private val clock: Clock, private val policy: RetentionPolicy) {

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    private fun today(): LocalDate = LocalDate.now(clock)

    /** The notes of [registrationId] are past their date (a day of Rome): they are not read, not shown and not counted, whether the job has deleted them yet or not. */
    fun expired(context: DSLContext, registrationId: Long): Boolean {
        val end = context.select(GATHERING.END_DATE).from(REGISTRATION).join(GATHERING).on(GATHERING.ID.eq(REGISTRATION.GATHERING_ID))
            .where(REGISTRATION.ID.eq(registrationId)).fetchOne(GATHERING.END_DATE) ?: return false
        return policy.notesExpired(end, today())
    }

    /**
     * Writes the notes of [registrationId] with the consent: of the account [byAccount] (online) or, with [onPaper], the one on the paper form. A
     * second call replaces the first, and the moment of the consent is the one of the last call.
     */
    fun save(context: DSLContext, registrationId: Long, notes: Notes, byAccount: Long?, onPaper: Boolean) {
        val at = now()
        context.insertInto(HEALTH_NOTE)
            .set(HEALTH_NOTE.REGISTRATION_ID, registrationId)
            .set(HEALTH_NOTE.ALLERGIES, notes.allergies)
            .set(HEALTH_NOTE.INTOLERANCES, notes.intolerances)
            .set(HEALTH_NOTE.MEDICATIONS, notes.medications)
            .set(HEALTH_NOTE.CONSENT_AT, at)
            .set(HEALTH_NOTE.CONSENT_BY_ACCOUNT_ID, byAccount)
            .set(HEALTH_NOTE.CONSENT_ON_PAPER, onPaper)
            .onConflict(HEALTH_NOTE.REGISTRATION_ID).doUpdate()
            .set(HEALTH_NOTE.ALLERGIES, notes.allergies)
            .set(HEALTH_NOTE.INTOLERANCES, notes.intolerances)
            .set(HEALTH_NOTE.MEDICATIONS, notes.medications)
            .set(HEALTH_NOTE.CONSENT_AT, at)
            .set(HEALTH_NOTE.CONSENT_BY_ACCOUNT_ID, byAccount)
            .set(HEALTH_NOTE.CONSENT_ON_PAPER, onPaper)
            .execute()
    }

    /** Takes the notes away (a withdrawal, the consent taken back). Nothing happens when there are none. */
    fun remove(context: DSLContext, registrationId: Long) {
        context.deleteFrom(HEALTH_NOTE).where(HEALTH_NOTE.REGISTRATION_ID.eq(registrationId)).execute()
    }

    /**
     * Which of these registrations have notes that are not past their date: only whether, for the card of the parent and the page of a paper correction; never
     * the text.
     */
    fun existing(registrationIds: Collection<Long>): Set<Long> =
        if (registrationIds.isEmpty()) {
            emptySet()
        } else {
            dsl.select(HEALTH_NOTE.REGISTRATION_ID, GATHERING.END_DATE).from(HEALTH_NOTE)
                .join(REGISTRATION).on(REGISTRATION.ID.eq(HEALTH_NOTE.REGISTRATION_ID)).join(GATHERING).on(GATHERING.ID.eq(REGISTRATION.GATHERING_ID))
                .where(HEALTH_NOTE.REGISTRATION_ID.`in`(registrationIds)).fetch()
                .filterNot { policy.notesExpired(it[GATHERING.END_DATE]!!, today()) }.map { it[HEALTH_NOTE.REGISTRATION_ID]!! }.toSet()
        }

    /** The notes for the parent who owns the registration: nothing is written about it. */
    fun readForOwner(registrationId: Long): HealthNote? = read(dsl, registrationId)

    /**
     * The notes for a teacher: the reading is written first and then the notes are read, in one transaction. If the writing fails the
     * exception goes up and no note leaves this method. Written also when there are no notes (the register does not say).
     *
     * **It wants a [ConfirmationProof]** (TALITA-15, criterion 39): the proof that the teacher has no passkey, or has confirmed with one in the last ten minutes. Nothing outside the class that
     * checks can make one, so a call that did not go through the check does not compile: the three places that open the notes (the detail of a registration, the card in the arrivals, the
     * correction of a paper form) cannot forget it. This class knows nothing of sessions or passkeys: it only asks for the proof, and the teacher who read is the one that the proof is for.
     */
    fun readForTeacher(registrationId: Long, proof: ConfirmationProof): NotesRead = dsl.transactionResult { configuration ->
        val transaction = DSL.using(configuration)
        // Past their date there is nothing to read, so nothing to register: the same answer as for no notes, without a row in the register.
        if (expired(transaction, registrationId)) return@transactionResult NotesRead(null, now())
        val at = recordReading(transaction, registrationId, proof.accountId)
        NotesRead(read(transaction, registrationId), at)
    }

    /** Writes a reading: used by what a teacher changes (the notes of a paper registration). Returns its moment. */
    fun recordReading(context: DSLContext, registrationId: Long, teacherAccountId: Long): OffsetDateTime {
        val at = now()
        context.insertInto(HEALTH_NOTE_ACCESS)
            .set(HEALTH_NOTE_ACCESS.REGISTRATION_ID, registrationId)
            .set(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID, teacherAccountId)
            .set(HEALTH_NOTE_ACCESS.ACCESSED_AT, at)
            .execute()
        return at
    }

    /** Who read the notes of this registration and when, the most recent first. */
    fun readings(registrationId: Long): List<Reading> =
        dsl.select(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID, TEACHER.FIRST_NAME, TEACHER.LAST_NAME, HEALTH_NOTE_ACCESS.ACCESSED_AT)
            .from(HEALTH_NOTE_ACCESS).leftJoin(TEACHER).on(TEACHER.ACCOUNT_ID.eq(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID))
            .where(HEALTH_NOTE_ACCESS.REGISTRATION_ID.eq(registrationId))
            .orderBy(HEALTH_NOTE_ACCESS.ACCESSED_AT.desc(), HEALTH_NOTE_ACCESS.ID.desc())
            .fetch {
                val first = it[TEACHER.FIRST_NAME]
                val last = it[TEACHER.LAST_NAME]
                Reading(it[HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID]!!, if (first != null && last != null) "$first $last" else null, it[HEALTH_NOTE_ACCESS.ACCESSED_AT]!!)
            }

    private fun read(context: DSLContext, registrationId: Long): HealthNote? {
        if (expired(context, registrationId)) return null
        return context.selectFrom(HEALTH_NOTE).where(HEALTH_NOTE.REGISTRATION_ID.eq(registrationId)).fetchOne()?.let {
            HealthNote(Notes(it.allergies, it.intolerances, it.medications), it.consentAt!!, it.consentOnPaper!!)
        }
    }

    // ---- What deletes (TALITA-7) -----------------------------------------------------------------------------------------------

    /** Deletes the notes of every registration of these gatherings (the job: one month after the end). Returns how many rows went. */
    fun removeOfGatherings(context: DSLContext, gatheringIds: Collection<Long>): Int =
        if (gatheringIds.isEmpty()) {
            0
        } else {
            context.deleteFrom(HEALTH_NOTE)
                .where(HEALTH_NOTE.REGISTRATION_ID.`in`(context.select(REGISTRATION.ID).from(REGISTRATION).where(REGISTRATION.GATHERING_ID.`in`(gatheringIds)))).execute()
        }

    /** Deletes the notes of these registrations, whatever their date (a parent asks for the data of a child to be deleted). Returns how many rows went. */
    fun removeOfRegistrations(context: DSLContext, registrationIds: Collection<Long>): Int =
        if (registrationIds.isEmpty()) 0 else context.deleteFrom(HEALTH_NOTE).where(HEALTH_NOTE.REGISTRATION_ID.`in`(registrationIds)).execute()

    /**
     * Deletes the notes to which [accountId] gave the consent (an account that is deleted takes with it the consent it gave). With [until], only those whose consent was given
     * up to that moment: a request that is made again after a restore leaves the consent that the person gave after it. Returns how many rows went.
     */
    fun removeConsentedBy(context: DSLContext, accountId: Long, until: OffsetDateTime? = null): Int =
        context.deleteFrom(HEALTH_NOTE).where(HEALTH_NOTE.CONSENT_BY_ACCOUNT_ID.eq(accountId)).and(if (until == null) DSL.trueCondition() else HEALTH_NOTE.CONSENT_AT.le(until)).execute()

    /**
     * The condition «this account is in the register of the readings, or gave the consent to some notes»: [account] is the column of the account in the query that
     * asks. An account for which it holds cannot be deleted (the register must say who read, and the consent points to the account): the rule of the empty
     * accounts and the cancelling of an account ask it here, so that the tables of the notes are named in this class alone.
     */
    fun holdsAccount(account: Field<Long?>): Condition =
        DSL.exists(DSL.selectOne().from(HEALTH_NOTE_ACCESS).where(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID.eq(account)))
            .or(DSL.exists(DSL.selectOne().from(HEALTH_NOTE).where(HEALTH_NOTE.CONSENT_BY_ACCOUNT_ID.eq(account))))

    /** Whether [accountId] appears in the register of the readings (it read the notes of some registration that is still there). */
    fun hasReadings(context: DSLContext, accountId: Long): Boolean =
        context.fetchExists(context.selectOne().from(HEALTH_NOTE_ACCESS).where(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID.eq(accountId)))

    /** The last day of each gathering whose notes [accountId] read: the register of the readings goes with the registrations, so the address stays until the latest of them. */
    fun readGatheringEnds(context: DSLContext, accountId: Long): List<LocalDate> =
        context.selectDistinct(GATHERING.END_DATE).from(HEALTH_NOTE_ACCESS)
            .join(REGISTRATION).on(REGISTRATION.ID.eq(HEALTH_NOTE_ACCESS.REGISTRATION_ID)).join(GATHERING).on(GATHERING.ID.eq(REGISTRATION.GATHERING_ID))
            .where(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID.eq(accountId)).fetch(GATHERING.END_DATE).map { it!! }
}
