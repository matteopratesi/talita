package talita.registration

import org.jooq.DSLContext
import talita.jooq.enums.RegistrationAttendance
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REGISTRATION_DAY
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Children and registrations made straight in the tables, like registrations made yesterday: the set-up of the tests that are not about making them
 * (the pages of the parent and of the teachers, in a browser). The way in of the people is still the real one. Names and dates are synthetic.
 */
class RegistrationRows(private val dsl: DSLContext, private val clock: Clock) {

    /**
     * A registration at [gathering]. With an [owner] it is an online one, with the text accepted ([custody]) and the address of the parent; without,
     * a paper one typed in by [enteredBy]. [days] are the days of a registration of some days (`full` or `lunch`). Returns the id.
     */
    fun register(
        gathering: Long,
        owner: Long?,
        first: String = "Luca",
        last: String = "Rossi",
        birth: LocalDate = LocalDate.of(2016, 4, 4),
        phone: String = "333 123 4567",
        status: RegistrationStatus = RegistrationStatus.confirmed,
        days: Map<LocalDate, String>? = null,
        custody: String = "Affidamento di figli minorenni\n\nTesto di prova accettato da $first $last.",
        email: String = "nome@example.org",
        enteredBy: Long? = null,
        paperReceived: Boolean = false,
        notes: Notes? = null,
        notesOnPaper: Boolean = false,
        at: OffsetDateTime = OffsetDateTime.now(clock),
    ): Long {
        val childId = dsl.insertInto(CHILD).set(CHILD.ACCOUNT_ID, owner).set(CHILD.FIRST_NAME, first).set(CHILD.LAST_NAME, last).set(CHILD.BIRTH_DATE, birth)
            .returningResult(CHILD.ID).fetchSingle().value1()!!
        val id = dsl.insertInto(REGISTRATION)
            .set(REGISTRATION.GATHERING_ID, gathering)
            .set(REGISTRATION.CHILD_ID, childId)
            .set(REGISTRATION.ACCOUNT_ID, owner)
            .set(REGISTRATION.PHONE, phone)
            .set(REGISTRATION.ATTENDANCE, if (days == null) RegistrationAttendance.all_days else RegistrationAttendance.days)
            .set(REGISTRATION.STATUS, status)
            .set(REGISTRATION.CUSTODY_TEXT, if (owner != null) custody else null)
            .set(REGISTRATION.CUSTODY_TEXT_VERSION, if (owner != null) CustodyText.VERSION else null)
            .set(REGISTRATION.CUSTODY_ACCEPTED_AT, if (owner != null) at else null)
            .set(REGISTRATION.CUSTODY_ACCEPTED_BY_EMAIL, if (owner != null) email else null)
            .set(REGISTRATION.CUSTODY_RELIGION_CONSENT, owner != null)
            .set(REGISTRATION.PAPER_FORM_RECEIVED, paperReceived)
            .set(REGISTRATION.ENTERED_BY_ACCOUNT_ID, if (owner == null) enteredBy else null)
            .set(REGISTRATION.CREATED_AT, at)
            .set(REGISTRATION.UPDATED_AT, at)
            .returningResult(REGISTRATION.ID).fetchSingle().value1()!!
        days?.forEach { (day, kind) ->
            dsl.insertInto(REGISTRATION_DAY).set(REGISTRATION_DAY.REGISTRATION_ID, id).set(REGISTRATION_DAY.DAY, day)
                .set(REGISTRATION_DAY.KIND, DayKind.ofCode(kind)!!.column).execute()
        }
        if (notes != null) {
            dsl.insertInto(HEALTH_NOTE)
                .set(HEALTH_NOTE.REGISTRATION_ID, id)
                .set(HEALTH_NOTE.ALLERGIES, notes.allergies)
                .set(HEALTH_NOTE.INTOLERANCES, notes.intolerances)
                .set(HEALTH_NOTE.MEDICATIONS, notes.medications)
                .set(HEALTH_NOTE.CONSENT_AT, at)
                .set(HEALTH_NOTE.CONSENT_BY_ACCOUNT_ID, if (notesOnPaper) null else owner)
                .set(HEALTH_NOTE.CONSENT_ON_PAPER, notesOnPaper)
                .execute()
        }
        return id
    }
}
