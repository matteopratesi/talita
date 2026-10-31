package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.REMEMBERED_DEVICE
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How long a session counts (TALITA-15, criteria 7, 15, 16): the two hours without use of the container stay, and on top of them there is a cap of 12 hours from the moment of the entry, used or
 * not; a session that was not born from a login of ours counts for nothing. A person with the cookie of «Ricordami su questo telefono» enters again in the same request; the others go to the access
 * page, with the notice that says why. The age of a session is moved back in the session itself: the clock of the application is shared by every test and only goes forward.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SessionLimitsTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val settings: LoginSettings,
) {

    private val made = mutableListOf<String>()

    private fun phone() = Phone(mockMvc, tokens)

    private fun person(): String = Addresses.unique("limiti").also { made += it }

    @AfterEach
    fun clean() {
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
    }

    /** The session of [phone] was born [age] ago. */
    private fun backdate(phone: Phone, age: Duration) {
        val session = checkNotNull(phone.session)
        val origin = session.getAttribute(SessionOrigins.KEY) as SessionOrigin
        session.setAttribute(SessionOrigins.KEY, origin.copy(startedAt = origin.startedAt.minus(age)))
    }

    private val inactivityNotice = "L’accesso si chiude da solo dopo 2 ore che non usi Talità, e comunque dopo 12 ore dall’entrata. Per rientrare, chiedi un nuovo link."

    @Test
    fun `t15 c07 the cap of the session is 12 hours, a number of LoginSettings`() {
        assertEquals(Duration.ofHours(12), settings.sessionMaxAge)
        assertEquals(Duration.ofMinutes(10), settings.confirmationValidity)
    }

    @Test
    fun `t15 c07 a session born 12 hours ago, even if it was used until now, is closed by the next request, and the person goes to the access page with the notice of the inactivity`() {
        val phone = phone()
        phone.enter(person())
        assertEquals(200, phone.get("/profilo").response.status)
        backdate(phone, Duration.ofHours(12))

        val answer = phone.get("/profilo")

        assertEquals(302, answer.response.status)
        assertEquals("/accedi", answer.response.getHeader("Location"))
        val access = phone.body("/accedi")
        assertTrue(access.contains(inactivityNotice), access)
        assertFalse(phone.body("/accedi").contains("si chiude da solo"), "once")
    }

    @Test
    fun `t15 c07 a session of 11 hours and 59 minutes still counts`() {
        val phone = phone()
        phone.enter(person())
        backdate(phone, Duration.ofHours(11).plusMinutes(59))

        assertEquals(200, phone.get("/profilo").response.status)
    }

    @Test
    fun `t15 c07 with the cookie of Ricordami the person who passed the 12 hours enters again in the same request, with a new session`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        backdate(phone, Duration.ofHours(13))
        val before = checkNotNull(phone.session).id

        val answer = phone.get("/profilo")

        assertEquals(200, answer.response.status, "no access page, no link")
        assertTrue(answer.response.contentAsString.contains(email))
        assertNotEquals(before, checkNotNull(phone.session).id, "a new session, with its own 12 hours")
        val origin = checkNotNull(phone.session).getAttribute(SessionOrigins.KEY) as SessionOrigin
        assertTrue(origin.startedAt.isAfter(java.time.Instant.now().minus(Duration.ofMinutes(5))), "born now")
        assertNotNull(origin.deviceId, "from the row of the device")
        // And again 13 hours later: the cookie is the new one, and the person is still inside.
        backdate(phone, Duration.ofHours(13))
        assertEquals(200, phone.get("/profilo").response.status)
    }

    @Test
    fun `t15 c07 a piece of page and the back button of a person who passed the 12 hours with no cookie get the answers of a person without access`() {
        val phone = phone()
        phone.enter(person())
        backdate(phone, Duration.ofHours(12))

        val fragment = phone.get("/iscrizioni") { header("HX-Request", "true") }
        assertEquals(200, fragment.response.status)
        assertEquals("/accedi", fragment.response.getHeader("HX-Redirect"), "htmx loads the access page as a whole page")

        val second = phone()
        second.enter(person())
        backdate(second, Duration.ofHours(12))
        val back = second.get("/profilo") { header("HX-Request", "true"); header("HX-History-Restore-Request", "true") }
        assertEquals(401, back.response.status, "the back button makes the browser load the address")
    }

    @Test
    fun `t15 c07 a session that has no origin, that no login of ours made, counts for nothing`() {
        val phone = phone()
        phone.enter(person())
        checkNotNull(phone.session).removeAttribute(SessionOrigins.KEY)

        val answer = phone.get("/profilo")

        assertEquals(302, answer.response.status)
        assertEquals("/accedi", answer.response.getHeader("Location"))
    }

    @Test
    fun `t15 c07 a new login gives a new origin, so the 12 hours start again from the entry`() {
        val email = person()
        val phone = phone()
        phone.enter(email)
        backdate(phone, Duration.ofHours(12))
        assertEquals(302, phone.get("/profilo").response.status)

        phone.enter(email)

        assertEquals(200, phone.get("/profilo").response.status)
    }
}
