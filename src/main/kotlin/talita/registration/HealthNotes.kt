package talita.registration

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.TEACHER
import java.time.Clock
import java.time.OffsetDateTime

/** The notes on health of a registration as they are kept, with the moment of the consent and where it was given. */
data class HealthNote(val notes: Notes, val consentAt: OffsetDateTime, val onPaper: Boolean)

/** One reading of the notes by a teacher; [teacher] is null when the person is not among the teachers any more. */
data class Reading(val teacherAccountId: Long, val teacher: String?, val at: OffsetDateTime)

/** The notes read by a teacher, with the moment of the reading that was written for it (the same moment). */
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
 */
@Component
class HealthNotes(private val dsl: DSLContext, private val clock: Clock) {

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

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

    /** Which of these registrations have notes: only whether, for the card of the parent and the page of a paper correction; never the text. */
    fun existing(registrationIds: Collection<Long>): Set<Long> =
        if (registrationIds.isEmpty()) emptySet()
        else dsl.select(HEALTH_NOTE.REGISTRATION_ID).from(HEALTH_NOTE).where(HEALTH_NOTE.REGISTRATION_ID.`in`(registrationIds)).fetch(HEALTH_NOTE.REGISTRATION_ID).map { it!! }.toSet()

    /** The notes for the parent who owns the registration: nothing is written about it. */
    fun readForOwner(registrationId: Long): HealthNote? = read(dsl, registrationId)

    /**
     * The notes for a teacher: the reading is written first and then the notes are read, in one transaction. If the writing fails the
     * exception goes up and no note leaves this method. Written also when there are no notes (the register does not say).
     */
    fun readForTeacher(registrationId: Long, teacherAccountId: Long): NotesRead = dsl.transactionResult { configuration ->
        val transaction = DSL.using(configuration)
        val at = recordReading(transaction, registrationId, teacherAccountId)
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

    private fun read(context: DSLContext, registrationId: Long): HealthNote? =
        context.selectFrom(HEALTH_NOTE).where(HEALTH_NOTE.REGISTRATION_ID.eq(registrationId)).fetchOne()?.let {
            HealthNote(Notes(it.allergies, it.intolerances, it.medications), it.consentAt!!, it.consentOnPaper!!)
        }
}
