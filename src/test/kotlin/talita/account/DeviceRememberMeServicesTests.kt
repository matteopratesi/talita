package talita.account

import jakarta.servlet.http.Cookie
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import talita.Addresses
import talita.ClockConfiguration
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.retention.RetentionPolicy
import java.time.Clock
import java.time.LocalDateTime
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How long a device enters, as the services decide it with a fixed clock (TALITA-15, criterion 6): a teacher who touches the box on 17 September 2027 rejoins without a link until the 23rd included and
 * from the 24th (a day of Rome) goes to the access page; a parent, until 16 October; and the length is the one of the role of the person at the moment of each re-entry. The rows are made with the
 * clock of the day of the touch and used with the clock of the day of the re-entry, as the real application does across the days. The database is real.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class DeviceRememberMeServicesTests(
    @Autowired private val dsl: DSLContext,
    @Autowired private val policy: RetentionPolicy,
    @Autowired private val settings: LoginSettings,
) {

    private val made = mutableListOf<Long>()

    private fun clockAt(local: String): Clock = Clock.fixed(LocalDateTime.parse(local).atZone(ClockConfiguration.ZONE).toInstant(), ClockConfiguration.ZONE)

    private fun account(teacher: Boolean = false): Long {
        val email = Addresses.unique("durata")
        val id = dsl.insertInto(ACCOUNT).set(ACCOUNT.EMAIL, email).set(ACCOUNT.CREATED_ON, java.time.LocalDate.of(2027, 1, 1)).returningResult(ACCOUNT.ID).fetchSingle().value1()!!
        made += id
        if (teacher) TestAccounts.makeTeacher(dsl, email)
        return id
    }

    @AfterEach
    fun clean() {
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(made)).execute()
        for (id in made) {
            dsl.execute("delete from teacher where account_id = ?", id)
            dsl.execute("delete from account where id = ?", id)
        }
        made.clear()
    }

    private fun services(at: String): DeviceRememberMeServices {
        val clock = clockAt(at)
        val devices = RememberedDevices(dsl, clock, policy)
        val accounts = Accounts(dsl, clock)
        return DeviceRememberMeServices(devices, accounts, SessionOrigins(accounts, devices, settings, clock), policy, clock)
    }

    /** The cookie of a device that [account] touched at [at]: the row is made by the application's own code. */
    private fun touch(account: Long, at: String): Cookie {
        val made = RememberedDevices(dsl, clockAt(at), policy).add(account, null)
        return Cookie("ricordami", Base64.getEncoder().withoutPadding().encodeToString("${made.cookie.selector}:${made.cookie.value}".toByteArray()))
    }

    /** The re-entry at [at] with [cookie]: the account that entered, and the cookie that the answer sets (null if none, or if it was cancelled). */
    private fun enterAt(at: String, cookie: Cookie): Pair<Long?, Cookie?> {
        val request = MockHttpServletRequest("GET", "/profilo")
        request.setCookies(cookie)
        val response = MockHttpServletResponse()
        val authentication = services(at).autoLogin(request, response)
        val set = response.cookies.firstOrNull { it.name == "ricordami" && it.maxAge != 0 }
        return (authentication?.principal as? AccountUserDetails)?.id to set
    }

    @Test
    fun `t15 c06 a teacher who touches the box on 17 September 2027 enters until the 23rd included and from the 24th goes to the access page`() {
        val teacher = account(teacher = true)

        val on23 = touch(teacher, "2027-09-17T10:00")
        assertEquals(teacher, enterAt("2027-09-23T23:59", on23).first, "the 23rd, at the last minute")

        val on24 = touch(teacher, "2027-09-17T10:00")
        assertNull(enterAt("2027-09-24T00:00", on24).first, "from midnight of Rome of the 24th")
        assertEquals(0, dsl.fetchCount(REMEMBERED_DEVICE, REMEMBERED_DEVICE.ACCOUNT_ID.eq(teacher).and(REMEMBERED_DEVICE.SELECTOR.eq(Phone.decode(on24.value).first))), "the row of a device that expired goes")
    }

    @Test
    fun `t15 c06 a parent who touches the box on 17 September 2027 enters until 16 October`() {
        val parent = account()

        assertEquals(parent, enterAt("2027-10-16T23:59", touch(parent, "2027-09-17T10:00")).first)
        assertNull(enterAt("2027-10-17T00:00", touch(parent, "2027-09-17T10:00")).first)
    }

    @Test
    fun `t15 c06 the length is the one of the role of today, so a parent who becomes a teacher on the 20th with the row of the 17th does not enter from the 24th`() {
        val person = account()
        val cookie = touch(person, "2027-09-17T10:00")
        // Still a parent on the 20th: the re-entry works, and the cookie that comes back is the next one.
        val (entered, next) = enterAt("2027-09-20T09:00", cookie)
        assertEquals(person, entered)
        val renewed = assertNotNull(next, "the cookie of the re-entry")
        // The role changes on the 20th.
        TestAccounts.makeTeacher(dsl, dsl.select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(person)).fetchSingle(ACCOUNT.EMAIL)!!)

        assertEquals(person, enterAt("2027-09-23T23:59", renewed).first.also { assertTrue(it != null, "the 23rd: still inside") })
    }

    @Test
    fun `t15 c06 and from the 24th the same row, now a teacher's, is over`() {
        val person = account()
        val cookie = touch(person, "2027-09-17T10:00")
        TestAccounts.makeTeacher(dsl, dsl.select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(person)).fetchSingle(ACCOUNT.EMAIL)!!)

        assertNull(enterAt("2027-09-24T00:30", cookie).first)
        assertEquals(0, dsl.fetchCount(REMEMBERED_DEVICE, REMEMBERED_DEVICE.ACCOUNT_ID.eq(person)))
    }

    @Test
    fun `t15 c06 the cookie that the re-entry gives back lasts until the first day on which the row does not enter, at midnight of Rome`() {
        val teacher = account(teacher = true)

        val (_, next) = enterAt("2027-09-17T22:00", touch(teacher, "2027-09-17T10:00"))

        val seconds = java.time.Duration.between(clockAt("2027-09-17T22:00").instant(), java.time.LocalDate.of(2027, 9, 24).atStartOfDay(ClockConfiguration.ZONE).toInstant()).seconds
        assertEquals(seconds.toInt(), assertNotNull(next).maxAge)
        assertTrue(next!!.isHttpOnly)
    }
}
