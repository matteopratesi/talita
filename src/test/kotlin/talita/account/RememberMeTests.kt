package talita.account

import jakarta.servlet.http.Cookie
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.CapturesOutput
import talita.ClockConfiguration
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.teacher.Invitations
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Ricordami su questo telefono» against the whole application (TALITA-15, criteria 1-5 and 8-13): the box on the page of the link, what the tap leaves (a row and a cookie, or nothing), the
 * re-entry without a link, the rotation of the value, the cookie that was copied, the form with a CSRF code that expired, the invitation of whoever is inside, the limit of ten devices, an account
 * that is gone and a database that fails, and what «Esci» does. A [Phone] has its cookies, so two phones are two devices. Every test has its own addresses; the lengths by role and the days are in
 * `DeviceRememberMeServicesTests`, with a fixed clock.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RememberMeTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val devices: RememberedDevices,
    @Autowired private val invitations: Invitations,
) {

    private val made = mutableListOf<String>()

    private fun phone(secure: Boolean = false) = Phone(mockMvc, tokens, secure)

    private fun person(name: String = "ricordami"): String = Addresses.unique(name).also { made += it }

    /** The database is shared by every test: the accounts of this one go, with their devices (the keys to the account have no cascade). */
    @AfterEach
    fun clean() {
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
        dsl.execute("alter table remembered_device drop constraint if exists test_cannot_change")
    }

    private fun accountId(email: String): Long = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!

    private fun rows(email: String) = dsl.selectFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId(email))).fetch()

    private fun deviceCount(): Int = dsl.fetchCount(REMEMBERED_DEVICE)

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    /** The last change of the value was [seconds] ago (the clock of the application is shared by every test and does not go back, so the row says it, with the moment of the application clock). */
    private fun pastTheMargin(email: String, seconds: Long = 61) {
        dsl.update(REMEMBERED_DEVICE).set(REMEMBERED_DEVICE.ROTATED_AT, java.time.OffsetDateTime.now(clock).minusSeconds(seconds)).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId(email))).execute()
    }

    private fun encode(selector: String, value: String): String = Base64.getEncoder().withoutPadding().encodeToString("$selector:$value".toByteArray())

    // ---- The box (criteria 1, 2) -------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c01 the page of the link has the box Ricordami su questo telefono, never checked, with how long it lasts and the advice, and opening the page writes nothing`() {
        val email = person()
        val phone = phone()
        val before = deviceCount()

        val html = phone.get("/accedi/conferma?token=${phone.link(email)}").response.contentAsString

        val box = Regex("""<input type="checkbox" name="ricordami"[^>]*>""").find(html)?.value
        assertNotNull(box, "the box is on the page")
        assertFalse(box.contains("checked"), "never checked")
        assertTrue(html.contains("Ricordami su questo telefono"))
        assertTrue(html.contains("Entri in Talità senza chiedere un nuovo link, per 30 giorni (7 se sei tra i maestri) o finché non esci."), "the lengths of the policy")
        assertTrue(html.contains("Non spuntarla su un telefono o un computer di altri: chi lo usa entrerebbe al posto tuo."))
        assertTrue(html.indexOf("ricordami") < html.indexOf("""Accesso in corso"""), "the box is above «Entra»")
        assertEquals(before, deviceCount(), "opening the page writes nothing")
        assertNull(phone.deviceCookie, "and sets no cookie")
        assertEquals(0, dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.eq(email)), "the account is born with the tap, not with the page")
    }

    @Test
    fun `t15 c02 the tap on Entra without the box enters as before, with no cookie but the session and no row`() {
        val email = person()
        val phone = phone()

        val tap = phone.enter(email)

        assertEquals(303, tap.response.status)
        assertEquals("/", tap.response.getHeader("Location"))
        assertEquals(emptyList(), tap.response.cookies.map { it.name }, "no cookie but the one of the session, which the mock of the container does not write")
        assertNull(phone.deviceCookie)
        assertEquals(0, rows(email).size)
    }

    // ---- What the tap leaves (criterion 3) --------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c03 the tap with the box leaves a row and a cookie, HttpOnly, SameSite Lax, on the whole site, that expires with the row, and the value is never in the database`() {
        val email = person()
        val phone = phone()

        val tap = phone.enter(email, remember = true)

        assertEquals("/", tap.response.getHeader("Location"))
        val cookie = assertNotNull(phone.deviceCookie, "the answer of the tap has the cookie")
        assertTrue(cookie.isHttpOnly)
        assertEquals("Lax", cookie.getAttribute("SameSite"))
        assertEquals("/", cookie.path)
        assertFalse(cookie.secure, "Secure only when the request came by HTTPS")
        val (selector, value) = checkNotNull(phone.deviceTokens())
        assertEquals(22, selector.length, "128 bits")
        assertEquals(43, value.length, "256 bits")
        val row = rows(email).single()
        assertEquals(selector, row.selector)
        assertEquals(sha256(value).toList(), row.validatorHash!!.toList(), "the row has the SHA-256 of the value")
        assertEquals(LocalDate.now(clock), row.createdOn, "the day of Rome, from the clock of the application")
        // The cookie expires with the row: at midnight of Rome of the day it is gone (30 days for a parent).
        val goneAt = LocalDate.now(clock).plusDays(30).atStartOfDay(ClockConfiguration.ZONE).toInstant()
        val expected = Duration.between(clock.instant(), goneAt).seconds
        assertTrue(kotlin.math.abs(cookie.maxAge - expected) < 60, "max-age ${cookie.maxAge} vs $expected")
        // Nothing but the two codes is in the cookie, and nothing of the value is in the database, in any column.
        assertEquals(setOf("ricordami"), phone.jar.keys, "no other cookie")
        for (text in dsl.fetch("select t::text from remembered_device t").getValues(0, String::class.java)) assertFalse(text.contains(value), "the value is in the database")
        assertFalse(Base64.getDecoder().decode(cookie.value + "=".repeat((4 - cookie.value.length % 4) % 4)).decodeToString().contains(email), "no address in the cookie")
    }

    @Test
    fun `t15 c03 the cookie is Secure when the request came by HTTPS`() {
        val phone = phone(secure = true)

        phone.enter(person(), remember = true)

        assertTrue(assertNotNull(phone.deviceCookie).secure)
    }

    @Test
    fun `t15 c03 the filter of the link calls loginSuccess of the services, so the cookie is born in the answer of the tap and not later`() {
        val email = person()
        val phone = phone()

        val tap = phone.enter(email, remember = true)

        val set = tap.response.cookies.filter { it.name == "ricordami" }
        assertEquals(1, set.size, "one cookie of the device in the answer of the tap")
        assertTrue(set.single().isHttpOnly && set.single().getAttribute("SameSite") == "Lax" && set.single().path == "/", "HttpOnly, SameSite=Lax, Path=/")
        assertEquals(1, rows(email).size, "and the row in the same moment")
    }

    // ---- The re-entry (criteria 4, 5) -----------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c04 with the cookie and no session a page, a piece of page and the back button enter without the access page, with a new session and a new value`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        val (selector, firstValue) = checkNotNull(phone.deviceTokens())
        val firstSession = checkNotNull(phone.session).id

        // Two hours without use, a restart of the application, a browser that was closed.
        phone.forgetSession()
        val page = phone.get("/profilo")
        assertEquals(200, page.response.status)
        assertTrue(page.response.contentAsString.contains(email), "the profile of the person")
        assertNotEquals(firstSession, checkNotNull(phone.session).id, "a new session")
        val (sameSelector, secondValue) = checkNotNull(phone.deviceTokens())
        assertEquals(selector, sameSelector, "the selector stays")
        assertNotEquals(firstValue, secondValue, "the value changes")
        assertEquals(sha256(secondValue).toList(), rows(email).single().validatorHash!!.toList())

        phone.forgetSession()
        val fragment = phone.get("/iscrizioni") { header("HX-Request", "true") }
        assertEquals(200, fragment.response.status, "a piece of page asked by htmx")
        assertNull(fragment.response.getHeader("HX-Redirect"))

        phone.forgetSession()
        val back = phone.get("/profilo") { header("HX-Request", "true"); header("HX-History-Restore-Request", "true") }
        assertEquals(200, back.response.status, "the back button, which would get a 401 without the cookie")

        assertEquals(1, rows(email).size)
    }

    @Test
    fun `t15 c04 the static files do not use the cookie, so a page and its style sheet that arrive together do not spend the value twice`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        val html = phone.body("/accedi/conferma?token=${phone.link(email)}")
        val css = checkNotNull(Regex("""href="(/css/app-[0-9a-f]+\.css)"""").find(html)).groupValues[1]
        phone.forgetSession()
        val before = rows(email).single().validatorHash!!.toList()
        val value = checkNotNull(phone.deviceTokens()).second

        val response = phone.get(css)

        assertEquals(200, response.response.status)
        assertEquals(before, rows(email).single().validatorHash!!.toList(), "the row did not change")
        assertEquals(value, checkNotNull(phone.deviceTokens()).second, "the cookie did not change")
        assertEquals(emptyList(), response.response.cookies.toList(), "and nothing was set")
    }

    @Test
    @CapturesOutput
    fun `t15 c05 a cookie with an old value for a selector that exists is a copy, the row and the cookie go, nobody enters and the log has a WARN with no data`(output: CapturedOutput) {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        val stolen = checkNotNull(phone.deviceCookie)
        phone.forgetSession()
        phone.get("/profilo")
        val (selector, newValue) = checkNotNull(phone.deviceTokens())
        // The margin of 60 seconds is over: from then on the value before the last one is a copy.
        pastTheMargin(email)

        // Someone has the old cookie (a copy), and uses it after the person has used theirs.
        val thief = phone()
        thief.jar["ricordami"] = Cookie("ricordami", stolen.value)
        val answer = thief.get("/profilo")

        assertEquals(302, answer.response.status)
        assertEquals("/accedi", answer.response.getHeader("Location"))
        assertNull(thief.deviceCookie, "the cookie is cancelled")
        assertEquals(0, rows(email).size, "the row goes: the browser that had the new value is unlinked too")
        phone.forgetSession()
        assertEquals("/accedi", phone.get("/profilo").response.getHeader("Location"))
        val warnings = output.all.lines().filter { it.contains("Remember-me:") }
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("WARN"), warnings.single())
        for (secret in listOf(selector, newValue, email, checkNotNull(Phone.decode(stolen.value).second))) assertFalse(output.all.contains(secret), "the log has $secret")
    }

    // ---- The margin of 60 seconds for the tabs that the phone reopens together (decision of Matteo, 31 October) ---------------------------

    @Test
    @CapturesOutput
    fun `t15 c05 two requests with the same cookie a few milliseconds apart both enter, the row stays, and the second does not change the value or touch the cookie`(output: CapturedOutput) {
        val email = person()
        val owner = phone()
        owner.enter(email, remember = true)
        val (selector, firstValue) = checkNotNull(owner.deviceTokens())
        // The phone reopens two tabs after the session ended: the same cookie in both.
        val first = phone().also { it.jar["ricordami"] = Cookie("ricordami", checkNotNull(owner.deviceCookie).value) }
        val second = phone().also { it.jar["ricordami"] = Cookie("ricordami", checkNotNull(owner.deviceCookie).value) }

        val answerFirst = first.get("/profilo")
        val answerSecond = second.get("/profilo")

        assertEquals(200, answerFirst.response.status)
        assertEquals(200, answerSecond.response.status, "the second tab enters too: a new session, like any re-entry")
        assertNotNull(first.session, "a session for the first")
        assertNotNull(second.session, "and one of its own for the second")
        val (_, secondValue) = checkNotNull(first.deviceTokens())
        assertNotEquals(firstValue, secondValue, "the first changed the value")
        assertEquals(emptyList(), answerSecond.response.cookies.filter { it.name == "ricordami" }, "the second does not touch the cookie: the browser has the new one from the first answer")
        val row = rows(email).single()
        assertEquals(selector, row.selector, "the row stays")
        assertEquals(sha256(secondValue).toList(), row.validatorHash!!.toList(), "the value is the one that the first gave: the second did not change it again")
        assertEquals(sha256(firstValue).toList(), row.previousHash!!.toList(), "and the row keeps the hash of the one before, never the value")
        assertNotNull(row.rotatedAt)
        assertEquals(emptyList(), output.all.lines().filter { it.contains("Remember-me:") }, "nothing in the log: it is not a theft")
        // The browser that has the new value enters as usual, and the one that has the old one still can for a moment.
        first.forgetSession()
        assertEquals(200, first.get("/profilo").response.status)
    }

    @Test
    @CapturesOutput
    fun `t15 c05 the value before the last one is good for 60 seconds from the change, and after them it is a copy as before`(output: CapturedOutput) {
        val email = person()
        val owner = phone()
        owner.enter(email, remember = true)
        val old = checkNotNull(owner.deviceCookie).value
        owner.forgetSession()
        owner.get("/profilo")

        pastTheMargin(email, 59)
        val inside = phone().also { it.jar["ricordami"] = Cookie("ricordami", old) }
        assertEquals(200, inside.get("/profilo").response.status, "59 seconds after the change")
        assertEquals(1, rows(email).size)

        pastTheMargin(email, 61)
        val outside = phone().also { it.jar["ricordami"] = Cookie("ricordami", old) }
        val answer = outside.get("/profilo")

        assertEquals(302, answer.response.status, "61 seconds after the change")
        assertEquals("/accedi", answer.response.getHeader("Location"))
        assertNull(outside.deviceCookie, "the cookie is cancelled")
        assertEquals(0, rows(email).size, "and the row goes")
        val warnings = output.all.lines().filter { it.contains("Remember-me:") }
        assertEquals(1, warnings.size, warnings.toString())
        assertTrue(warnings.single().contains("WARN"))
    }

    @Test
    @CapturesOutput
    fun `t15 c05 a value from two changes ago is a copy at once, inside the margin of the last change as well`(output: CapturedOutput) {
        val email = person()
        val owner = phone()
        owner.enter(email, remember = true)
        val oldest = checkNotNull(owner.deviceCookie).value
        // Two re-entries: the value changes twice, and the row keeps only the hash of the one before the last.
        repeat(2) {
            owner.forgetSession()
            assertEquals(200, owner.get("/profilo").response.status)
        }
        assertEquals(1, rows(email).size)

        val thief = phone().also { it.jar["ricordami"] = Cookie("ricordami", oldest) }
        val answer = thief.get("/profilo")

        assertEquals(302, answer.response.status, "no margin for a value that is two changes old")
        assertNull(thief.deviceCookie)
        assertEquals(0, rows(email).size)
        assertEquals(1, output.all.lines().count { it.contains("Remember-me:") && it.contains("WARN") })
    }

    @Test
    fun `t15 c05 a cookie with a selector that does not exist, or that is malformed, is cancelled and the person stays without access, with no error`() {
        for (value in listOf(encode("AAAAAAAAAAAAAAAAAAAAAA", "qualsiasi-valore"), "non-e-base64!!", encode("solo-un-pezzo", "").substringBefore(":"), "", Base64.getEncoder().encodeToString("a:b:c".toByteArray()))) {
            val phone = phone()
            phone.jar["ricordami"] = Cookie("ricordami", value)

            val answer = phone.get("/profilo")

            assertEquals(302, answer.response.status, value)
            assertEquals("/accedi", answer.response.getHeader("Location"), value)
            assertNull(phone.deviceCookie, "the cookie is cancelled: $value")
            // The access page tells that the access on this phone was closed, once, and is a page like the others.
            val access = phone.get("/accedi")
            assertEquals(200, access.response.status)
        }
    }

    @Test
    fun `t15 c05 a cookie that is rejected is told on the access page, a person who never had one is not`() {
        val phone = phone()
        phone.jar["ricordami"] = Cookie("ricordami", encode("AAAAAAAAAAAAAAAAAAAAAA", "x"))
        phone.get("/profilo")

        val told = phone.body("/accedi")

        assertTrue(told.contains("L’accesso su questo telefono è stato chiuso. Per rientrare, chiedi un nuovo link."), told)
        assertFalse(phone.body("/accedi").contains("L’accesso su questo telefono è stato chiuso"), "once")
        assertFalse(phone().also { it.get("/profilo") }.body("/accedi").contains("è stato chiuso"))
    }

    // ---- The form that was open for hours (criterion 8) ------------------------------------------------------------------------------

    @Test
    fun `t15 c08 a form with a CSRF code that expired, from a person who is inside by the cookie, keeps them inside, and the page is reloaded with htmx, and without it the home, with the notice`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        phone.forgetSession()

        val withHtmx = phone.post("/esci", csrf = "codice-scaduto") { header("HX-Request", "true") }
        assertEquals(200, withHtmx.response.status)
        assertEquals("true", withHtmx.response.getHeader("HX-Refresh"), "the page is reloaded")
        assertNull(withHtmx.response.getHeader("HX-Redirect"), "never the access page")

        val without = phone.post("/esci", csrf = "codice-scaduto")
        assertEquals(303, without.response.status)
        assertEquals("/", without.response.getHeader("Location"), "the home, not the access page")
        val home = phone.get("/")
        assertEquals(200, home.response.status)
        assertTrue(home.response.contentAsString.contains("La pagina era aperta da troppo tempo. Riprova."))
        assertFalse(home.response.contentAsString.contains("chiedi un nuovo link"))
        // Nothing changed: the person is still inside, with the same device.
        assertEquals(1, rows(email).size)
        assertEquals(200, phone.get("/profilo").response.status)
    }

    @Test
    fun `t15 c08 the same form from a person with no cookie goes to the access page, as before`() {
        val phone = phone()

        val answer = phone.post("/esci", csrf = "codice-scaduto")

        assertEquals(302, answer.response.status)
        assertEquals("/accedi", answer.response.getHeader("Location"))
    }

    // ---- The invitation (criterion 9) --------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c09 a person who is inside, with the session or with the cookie, and has an invitation goes from the access page to Accetta l'invito, and without an invitation to the home`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)

        assertEquals("/", phone.get("/accedi").response.getHeader("Location"), "no invitation: the home")

        invitations.invite(EmailAddress.parse(email)!!)
        val withSession = phone.get("/accedi")
        assertEquals(302, withSession.response.status)
        assertEquals("/invito", withSession.response.getHeader("Location"), "with the session")

        phone.forgetSession()
        val withCookie = phone.get("/accedi")
        assertEquals("/invito", withCookie.response.getHeader("Location"), "with the cookie")
        assertEquals(200, phone.get("/invito").response.status)
        dsl.execute("delete from teacher_invitation where email = ?", email)
    }

    // ---- One row per browser, ten per account (criterion 10) ---------------------------------------------------------------------------

    @Test
    fun `t15 c10 a browser that touches the box again has one row only, the one of its old cookie goes`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        val first = checkNotNull(phone.deviceTokens()).first
        phone.forgetSession()

        phone.enter(email, remember = true)

        val row = rows(email).single()
        assertNotEquals(first, row.selector, "a new row, and the old one is gone")
        assertEquals(row.selector, checkNotNull(phone.deviceTokens()).first)
    }

    @Test
    fun `t15 c10 an account has at most ten devices, and the eleventh takes away the oldest`() {
        val email = person()
        val phones = (1..11).map { phone() }
        val selectors = mutableListOf<String>()
        for (phone in phones) {
            phone.enter(email, remember = true)
            selectors += checkNotNull(phone.deviceTokens()).first
        }

        val kept = rows(email).map { it.selector }
        assertEquals(10, kept.size)
        assertFalse(selectors.first() in kept, "the oldest went")
        assertEquals(selectors.drop(1).toSet(), kept.toSet())
        // The phone of the oldest, with its cookie and no session, is out.
        phones.first().forgetSession()
        assertEquals("/accedi", phones.first().get("/profilo").response.getHeader("Location"))
        phones.last().forgetSession()
        assertEquals(200, phones.last().get("/profilo").response.status)
    }

    // ---- An account that is gone, a database that fails (criteria 11, 12) -------------------------------------------------------------------

    @Test
    fun `t15 c11 the cookie of an account that was deleted is cancelled and the account does not come back to life`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        val id = accountId(email)
        // The deletion of an account takes its rows (TALITA-7); here only what matters to this test.
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(id)).execute()
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).execute()
        phone.forgetSession()

        val answer = phone.get("/profilo")

        assertEquals("/accedi", answer.response.getHeader("Location"))
        assertNull(phone.deviceCookie, "the cookie is cancelled")
        assertEquals(0, dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.eq(email)), "the reader of the cookie reads by id and creates nothing")
    }

    @Test
    fun `t15 c12 a database that fails during the re-entry does not take the cookie away, and the next touch tries again`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        phone.forgetSession()
        val cookie = checkNotNull(phone.deviceCookie).value
        // A constraint that refuses the update of the rotation, for the rows that are changed from now on.
        dsl.execute("alter table remembered_device add constraint test_cannot_change check (false) not valid")

        assertFailsWith<Exception> { phone.get("/profilo") }

        assertEquals(cookie, phone.deviceCookie?.value, "the cookie is still the person's")
        assertEquals(1, rows(email).size, "and so is the row")
        dsl.execute("alter table remembered_device drop constraint test_cannot_change")
        assertEquals(200, phone.get("/profilo").response.status, "the next touch enters")
    }

    // ---- Out (criterion 13) ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c13 Esci takes away the row of this browser and its cookie, and the other devices stay inside`() {
        val email = person()
        val here = phone()
        val there = phone()
        here.enter(email, remember = true)
        there.enter(email, remember = true)
        assertEquals(2, rows(email).size)
        val hereSelector = checkNotNull(here.deviceTokens()).first

        val out = here.submit("/profilo", "/esci")

        assertEquals(303, out.response.status)
        assertEquals("/accedi", out.response.getHeader("Location"))
        assertNull(here.deviceCookie, "the cookie is cancelled")
        assertEquals(listOf(checkNotNull(there.deviceTokens()).first), rows(email).map { it.selector }, "only the row of the other device is left")
        assertFalse(rows(email).any { it.selector == hereSelector })
        here.forgetSession()
        assertEquals("/accedi", here.get("/profilo").response.getHeader("Location"), "this browser does not enter by itself any more")
        there.forgetSession()
        assertEquals(200, there.get("/profilo").response.status, "the other one does")
    }

    @Test
    fun `t15 c13 Esci from a browser that did not touch the box takes nothing away from the devices`() {
        val email = person()
        val kept = phone()
        kept.enter(email, remember = true)
        val without = phone()
        without.enter(email)

        without.submit("/profilo", "/esci")

        assertEquals(1, rows(email).size)
    }

    @Test
    fun `t15 c13 a failed link does not take away the cookie of a device that is good`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        val cookie = checkNotNull(phone.deviceCookie).value

        phone.submit("/accedi/conferma?token=link-che-non-vale", "/accedi/conferma", mapOf("token" to "link-che-non-vale"))

        assertEquals(cookie, phone.deviceCookie?.value)
        assertEquals(1, rows(email).size)
    }
}
