package talita.retention

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dates of the retention (criterion 1): one place works them out, from the day after the last day, with the months and the years of the calendar. Plain unit
 * tests: no database. The example of the criteria is the «Convivenza di settembre 2027», from the 17th to the 19th, finished on the 20th. Everything is synthetic.
 */
class RetentionPolicyTests {

    private val policy = RetentionPolicy.STANDARD

    @Test
    fun `t7 c01 the notes go one month after the day the gathering is finished, the registrations one year after`() {
        val lastDay = LocalDate.of(2027, 9, 19)

        assertEquals(LocalDate.of(2027, 9, 20), policy.finishedOn(lastDay), "finished is the day after the last day (R6 of TALITA-4)")
        assertEquals(LocalDate.of(2027, 10, 20), policy.notesDeletionDate(lastDay))
        assertEquals(LocalDate.of(2028, 9, 20), policy.registrationsDeletionDate(lastDay))
    }

    @Test
    fun `t7 c01 a month or a year that does not exist in the target month is the last day of that month`() {
        // Finished on 31 January 2027: plusMonths gives 28 February 2027.
        assertEquals(LocalDate.of(2027, 2, 28), policy.notesDeletionDate(LocalDate.of(2027, 1, 30)))
        // Finished on 29 February 2028: plusYears gives 28 February 2029.
        assertEquals(LocalDate.of(2029, 2, 28), policy.registrationsDeletionDate(LocalDate.of(2028, 2, 28)))
        // A finished day in March after a month of 30 days.
        assertEquals(LocalDate.of(2027, 4, 30), policy.notesDeletionDate(LocalDate.of(2027, 3, 30)))
    }

    @Test
    fun `t7 c01 the invitations, the empty accounts, the backups and the numbers of the requests have their days`() {
        assertEquals(Period.ofDays(30), policy.invitations)
        assertEquals(Period.ofDays(30), policy.emptyAccounts)
        assertEquals(Period.ofDays(30), policy.backups)
        assertEquals(Period.ofDays(37), policy.requestIds, "the rotation of the backups and a week")

        // An invitation that expired on the evening of 1 October (Rome) goes on 31 October; the hour does not move the day.
        assertEquals(LocalDate.of(2027, 10, 31), policy.invitationDeletionDate(Instant.parse("2027-10-01T19:30:00Z")))
        assertEquals(LocalDate.of(2027, 10, 31), policy.invitationDeletionDate(Instant.parse("2027-09-30T22:30:00Z")), "the evening of the 30th in UTC is already the 1st in Rome")
        assertEquals(LocalDate.of(2027, 10, 5), policy.emptyAccountDeletionDate(LocalDate.of(2027, 9, 5)))
        assertEquals(LocalDate.of(2027, 11, 7), policy.requestIdDeletionDate(Instant.parse("2027-10-01T10:00:00Z")))
    }

    @Test
    fun `t7 c02 a datum is gone from its date, the day before it is there, the day itself it is not`() {
        val lastDay = LocalDate.of(2027, 9, 19)

        assertFalse(policy.notesExpired(lastDay, LocalDate.of(2027, 10, 19)))
        assertTrue(policy.notesExpired(lastDay, LocalDate.of(2027, 10, 20)))
        assertTrue(policy.notesExpired(lastDay, LocalDate.of(2028, 1, 1)))
        assertFalse(policy.registrationsExpired(lastDay, LocalDate.of(2028, 9, 19)))
        assertTrue(policy.registrationsExpired(lastDay, LocalDate.of(2028, 9, 20)))
    }

    @Test
    fun `t7 c01 other periods give other dates, so nothing outside the policy does sums with dates`() {
        val other = RetentionPolicy(notes = Period.ofMonths(2), registrations = Period.ofYears(2), backups = Period.ofDays(14))
        val lastDay = LocalDate.of(2027, 9, 19)

        assertEquals(LocalDate.of(2027, 11, 20), other.notesDeletionDate(lastDay))
        assertEquals(LocalDate.of(2029, 9, 20), other.registrationsDeletionDate(lastDay))
        assertEquals(Period.ofDays(21), other.requestIds, "the number of a request follows the backups")
    }
}
