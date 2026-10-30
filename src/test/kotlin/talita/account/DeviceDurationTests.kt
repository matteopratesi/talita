package talita.account

import org.junit.jupiter.api.Test
import talita.retention.RetentionPolicy
import java.time.LocalDate
import java.time.Period
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How long a device of «Ricordami su questo telefono» enters (TALITA-15, criterion 6): from the day of the touch, never from the last use, with the length of the role of the person
 * today, and every date a day of Rome. The example is the one of the criteria: a teacher touches the box on 17 September 2027. Pure functions of [RetentionPolicy], no database.
 */
class DeviceDurationTests {

    private val policy = RetentionPolicy.STANDARD
    private val touched = LocalDate.of(2027, 9, 17)

    @Test
    fun `t15 c06 a teacher who touches the box on 17 September 2027 enters until the 23rd included and goes to the access page from the 24th`() {
        assertEquals(Period.ofDays(7), policy.devices(teacher = true))
        assertEquals(LocalDate.of(2027, 9, 23), policy.deviceLastDay(touched, teacher = true))
        assertEquals(LocalDate.of(2027, 9, 24), policy.deviceGoneOn(touched, teacher = true))

        assertFalse(policy.deviceExpired(touched, teacher = true, today = LocalDate.of(2027, 9, 17)))
        assertFalse(policy.deviceExpired(touched, teacher = true, today = LocalDate.of(2027, 9, 23)))
        assertTrue(policy.deviceExpired(touched, teacher = true, today = LocalDate.of(2027, 9, 24)))
    }

    @Test
    fun `t15 c06 a parent who touches the box the same day enters until 16 October`() {
        assertEquals(Period.ofDays(30), policy.devices(teacher = false))
        assertEquals(LocalDate.of(2027, 10, 16), policy.deviceLastDay(touched, teacher = false))

        assertFalse(policy.deviceExpired(touched, teacher = false, today = LocalDate.of(2027, 10, 16)))
        assertTrue(policy.deviceExpired(touched, teacher = false, today = LocalDate.of(2027, 10, 17)))
    }

    @Test
    fun `t15 c06 the length is the one of the role of today, so a parent who becomes a teacher on the 20th does not enter from the 24th with the row of the 17th`() {
        val twentieth = LocalDate.of(2027, 9, 20)

        // Still a parent on the 20th: the row is good until the 16th of October.
        assertFalse(policy.deviceExpired(touched, teacher = false, today = twentieth))
        // A teacher from the 20th: the same row is gone on the 24th, not on 17 October.
        assertFalse(policy.deviceExpired(touched, teacher = true, today = LocalDate.of(2027, 9, 23)))
        assertTrue(policy.deviceExpired(touched, teacher = true, today = LocalDate.of(2027, 9, 24)))
    }

    @Test
    fun `t15 c06 the lengths are constants of the policy, and a policy with other lengths gives other dates`() {
        val other = RetentionPolicy(devicesParent = Period.ofDays(10), devicesTeacher = Period.ofDays(2))

        assertEquals(LocalDate.of(2027, 9, 18), other.deviceLastDay(touched, teacher = true))
        assertEquals(LocalDate.of(2027, 9, 26), other.deviceLastDay(touched, teacher = false))
    }

    @Test
    fun `t15 c06 the month does not matter, only the days, and the last day crosses the end of the month and of the year`() {
        assertEquals(LocalDate.of(2028, 1, 5), policy.deviceLastDay(LocalDate.of(2027, 12, 30), teacher = true))
        assertEquals(LocalDate.of(2028, 1, 28), policy.deviceLastDay(LocalDate.of(2027, 12, 30), teacher = false))
    }
}
