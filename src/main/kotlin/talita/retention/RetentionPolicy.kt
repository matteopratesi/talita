package talita.retention

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import talita.ClockConfiguration
import java.time.Instant
import java.time.LocalDate
import java.time.Period

/**
 * **How long each datum is kept, and the one place that works out the dates** (criterion 1). The job that deletes, the dialogs of the profile, the card
 * «Conservazione dei dati», the pages of a gathering whose data are gone and the notices of privacy all take their dates and their numbers from here: nothing else
 * does sums with dates. The periods are constants in the code (approved by Matteo, and written in `docs/03-privacy.md`), not settings: a
 * test builds a policy with other periods to prove that the texts follow them.
 *
 * A gathering is *finished* the day after its last day (rule R6 of TALITA-4); every period starts from there, and every date is a day of Rome. A datum is gone
 * **from** its date (the job deletes it that night, at 4:15), and the pages say «cancellate» from that day, not from the hour of the job: so the pages never
 * show a datum past its date (the notes on health, above all), whatever hour the job runs.
 *
 * @property notes the notes on health (allergies, intolerances, medicines): one month after the end
 * @property registrations the registration with the custody, the presences, the payments and the register of the readings: one year after the end
 * @property invitations an invitation to be a teacher that was not accepted: 30 days after it expired
 * @property emptyAccounts an account with no children, no registrations and no readings: 30 days after it was born
 * @property backups the longest that a backup lives (TALITA-8): a datum that is deleted is gone from the backups in this time
 * @property requestIds the number of the child or account deleted on request, in the register of the deletions: the backups and a week more
 * @property rooms who sleeps in which room (TALITA-11): nothing after the end, so the day after the last day. The rooms are tied to the house, and they are emptied when the
 * gathering is over (decision of Matteo, question 4); the rooms themselves, with no names, stay for the copy of the year after
 * @property devicesParent how long a parent stays inside on a phone where they touched «Ricordami su questo telefono» (TALITA-15): 30 days from the day of the touch, never from the last use
 * @property devicesTeacher the same for a teacher: 7 days, because a teacher can open the notes on health. The length is the one of the role **today**: a parent who becomes a teacher is
 * a teacher for the row of the day before as well
 */
data class RetentionPolicy(
    val notes: Period = Period.ofMonths(1),
    val registrations: Period = Period.ofYears(1),
    val invitations: Period = Period.ofDays(30),
    val emptyAccounts: Period = Period.ofDays(30),
    val backups: Period = Period.ofDays(30),
    val requestIds: Period = backups.plusDays(7),
    val rooms: Period = Period.ZERO,
    val devicesParent: Period = Period.ofDays(30),
    val devicesTeacher: Period = Period.ofDays(7),
) {

    /** The day after the last day: from here every period counts (R6). */
    fun finishedOn(lastDay: LocalDate): LocalDate = lastDay.plusDays(1)

    /** The day the notes on health of a gathering that ends on [lastDay] are deleted. `plus` of months clamps: 31 January and one month is the last day of February. */
    fun notesDeletionDate(lastDay: LocalDate): LocalDate = finishedOn(lastDay).plus(notes)

    /** The day the registrations of a gathering that ends on [lastDay] (and what hangs from them) are deleted. */
    fun registrationsDeletionDate(lastDay: LocalDate): LocalDate = finishedOn(lastDay).plus(registrations)

    /** The day the people of the rooms of a gathering that ends on [lastDay] are deleted: the day after the last day, with the standard policy (TALITA-11). */
    fun roomsDeletionDate(lastDay: LocalDate): LocalDate = finishedOn(lastDay).plus(rooms)

    /** The people of the rooms of the gathering are gone as of [today] (a day of Rome): the job deletes them that night, at its first run of the day. */
    fun roomsExpired(lastDay: LocalDate, today: LocalDate): Boolean = !today.isBefore(roomsDeletionDate(lastDay))

    /** The notes of the gathering are gone as of [today] (a day of Rome): from the day of their date, not from the hour of the job. */
    fun notesExpired(lastDay: LocalDate, today: LocalDate): Boolean = !today.isBefore(notesDeletionDate(lastDay))

    /** The registrations of the gathering are gone as of [today]. */
    fun registrationsExpired(lastDay: LocalDate, today: LocalDate): Boolean = !today.isBefore(registrationsDeletionDate(lastDay))

    /** The day an invitation that expired at [expiredAt] is deleted: the day of Rome of the expiry plus the period (the job looks at days, not at hours). */
    fun invitationDeletionDate(expiredAt: Instant): LocalDate = expiredAt.atZone(ClockConfiguration.ZONE).toLocalDate().plus(invitations)

    /** How long a device stays collected for a person with this role. */
    fun devices(teacher: Boolean): Period = if (teacher) devicesTeacher else devicesParent

    /**
     * The first day on which the device made on [createdOn] does not enter any more, and the job deletes it that night: the day of the touch plus the length of the role (a day of Rome).
     * A teacher who touched the box on 17 September 2027 is inside until the 23rd and goes to the access page from the 24th.
     */
    fun deviceGoneOn(createdOn: LocalDate, teacher: Boolean): LocalDate = createdOn.plus(devices(teacher))

    /** The last day on which the device made on [createdOn] still enters: the day before [deviceGoneOn]. The profile says «fino al» this day. */
    fun deviceLastDay(createdOn: LocalDate, teacher: Boolean): LocalDate = deviceGoneOn(createdOn, teacher).minusDays(1)

    /** The device made on [createdOn] does not enter any more as of [today] (a day of Rome): from its day, not from the hour of the job. */
    fun deviceExpired(createdOn: LocalDate, teacher: Boolean, today: LocalDate): Boolean = !today.isBefore(deviceGoneOn(createdOn, teacher))

    /** The day an account born on [bornOn] is deleted, if it is still empty. */
    fun emptyAccountDeletionDate(bornOn: LocalDate): LocalDate = bornOn.plus(emptyAccounts)

    /** The day the number of a deletion made at [doneAt] is taken out of the register. */
    fun requestIdDeletionDate(doneAt: Instant): LocalDate = doneAt.atZone(ClockConfiguration.ZONE).toLocalDate().plus(requestIds)

    companion object {
        /** The periods of the product. */
        val STANDARD = RetentionPolicy()
    }
}

/** The one policy of the application. */
@Configuration(proxyBeanMethods = false)
class RetentionConfiguration {

    @Bean
    fun retentionPolicy(): RetentionPolicy = RetentionPolicy.STANDARD
}
