package talita.checkin

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import talita.jooq.enums.RegistrationAttendance
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REGISTRATION_DAY
import talita.registration.CorrectionListener
import talita.registration.WithdrawalListener
import java.time.Clock
import java.time.LocalDate

/**
 * The rows of the days after today that are not worth anything any more go away with what made them worthless (TALITA-19, criteria 12-14): the arrival writes
 * the rows of the next days when the child arrives, and a row of a day that has not come is only the promise «he will be there», that is true while the registration
 * is not withdrawn, includes the day and has a row the day before.
 *
 * - a **withdrawal** takes away all the rows after today (the ones of today and of the past stay: they are the register, and they keep the registration until its date);
 * - a **correction** of the registration takes away the rows after today of the days that it does not include any more, and the ones after them (a day after the gap
 *   is not covered by anything);
 * - **putting back** a withdrawn registration writes nothing: the child is marked again.
 *
 * Both run in the transaction that holds the registration locked, and read what the database says in that transaction. They need nothing but the transaction and the clock
 * (`Checkins` uses `Registrations`, so a listener that used `Checkins` would make a cycle).
 */
@Component
class FuturePresences(private val clock: Clock) : WithdrawalListener, CorrectionListener {

    override fun withdrawn(tx: DSLContext, registrationId: Long) {
        prune(tx, registrationId)
    }

    override fun corrected(tx: DSLContext, registrationId: Long) {
        prune(tx, registrationId)
    }

    /** Takes away the rows after today that break the rule above, and returns the days that went. */
    internal fun prune(tx: DSLContext, registrationId: Long): List<LocalDate> {
        val today = LocalDate.now(clock)
        val registration = tx.select(REGISTRATION.STATUS, REGISTRATION.ATTENDANCE).from(REGISTRATION).where(REGISTRATION.ID.eq(registrationId)).fetchOne() ?: return emptyList()
        val withdrawn = registration[REGISTRATION.STATUS] == RegistrationStatus.withdrawn
        val everyDay = registration[REGISTRATION.ATTENDANCE] == RegistrationAttendance.all_days
        val chosen = tx.select(REGISTRATION_DAY.DAY).from(REGISTRATION_DAY).where(REGISTRATION_DAY.REGISTRATION_ID.eq(registrationId)).fetch(REGISTRATION_DAY.DAY).toSet()
        // Today and the days before it are not touched, but today's row says whether the first day after it is covered.
        val kept = tx.select(CHECKIN.DAY).from(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(registrationId)).and(CHECKIN.DAY.le(today)).fetch(CHECKIN.DAY).filterNotNull().toMutableSet()
        val after = tx.select(CHECKIN.DAY).from(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(registrationId)).and(CHECKIN.DAY.gt(today)).orderBy(CHECKIN.DAY).fetch(CHECKIN.DAY).filterNotNull()
        val gone = mutableListOf<LocalDate>()
        for (day in after) {
            if (!withdrawn && (everyDay || day in chosen) && day.minusDays(1) in kept) kept += day else gone += day
        }
        if (gone.isNotEmpty()) tx.deleteFrom(CHECKIN).where(CHECKIN.REGISTRATION_ID.eq(registrationId)).and(CHECKIN.DAY.`in`(gone)).execute()
        return gone
    }
}
