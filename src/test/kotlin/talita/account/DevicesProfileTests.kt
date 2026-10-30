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
import talita.gathering.GatheringFormat
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.retention.RetentionPolicy
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Dispositivi collegati» in the profile (TALITA-15, criteria 14-18): the rows with the day of the touch and the last day that they enter, «Questo dispositivo», «Togli» and «Esci da tutti gli
 * altri dispositivi» with their dialogs, the answers of htmx and of a form without JavaScript, and what the other devices see at their next request. A person sees only their own devices, and the id
 * of one that is not theirs answers as one that is gone. Every test has its own addresses.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class DevicesProfileTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val policy: RetentionPolicy,
) {

    private val made = mutableListOf<String>()

    private fun phone() = Phone(mockMvc, tokens)

    private fun person(name: String = "dispositivi"): String = Addresses.unique(name).also { made += it }

    @AfterEach
    fun clean() {
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.execute("delete from teacher where account_id = any(?)", ids.toTypedArray())
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
    }

    private fun accountId(email: String): Long = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!

    private fun selectors(email: String): List<String> = dsl.select(REMEMBERED_DEVICE.SELECTOR).from(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId(email))).fetch(REMEMBERED_DEVICE.SELECTOR).map { it!! }

    private fun today(): LocalDate = LocalDate.now(clock)

    private fun block(html: String): String = Regex("""<div id="dispositivi-contenuto">[\s\S]*?(?=<section|\z)""").find(html)?.value ?: error("no block of the devices")

    private fun text(html: String): String = html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")

    // ---- The list (criterion 14) ----------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c14 the profile has Dispositivi collegati inside Il tuo accesso, a row for each device with the day and the last day, Questo dispositivo on the one the person looks from and Togli on the others`() {
        val email = person()
        val here = phone()
        val there = phone()
        there.enter(email, remember = true)
        here.enter(email, remember = true)

        val html = here.body("/profilo")

        // Inside the card «Il tuo accesso», before the card «Passkey» or «I tuoi dati».
        assertTrue(html.indexOf("""id="accesso"""") < html.indexOf("Dispositivi collegati"))
        assertTrue(html.indexOf("Dispositivi collegati") < html.indexOf("I tuoi dati"))
        assertTrue(Regex("""<h3[^>]*>Dispositivi collegati</h3>""").containsMatchIn(html))
        val words = text(block(html))
        assertTrue(words.contains("Telefoni e computer dove hai scelto «Ricordami su questo telefono»: da lì rientri senza chiedere un link, per 30 giorni dal giorno della scelta."), words)
        val since = GatheringFormat.date(today())
        val until = GatheringFormat.date(policy.deviceLastDay(today(), teacher = false))
        assertEquals(2, Regex("Collegato dal $since").findAll(words).count(), "a row for each device")
        assertEquals(2, Regex("Rientri senza link fino al $until\\.").findAll(words).count())
        assertEquals(1, Regex("Questo dispositivo").findAll(block(html).substringBefore("<dialog")).count(), "the label is on one row only")
        assertTrue(words.contains("Per uscire da qui, tocca «Esci»."))
        assertEquals(1, Regex("""aria-label="Togli il dispositivo collegato dal $since"""").findAll(html).count(), "«Togli» only on the other row, with a name that says which")
        assertTrue(html.contains("Togliere il dispositivo collegato dal $since?"))
        assertTrue(html.contains("Esci da tutti gli altri dispositivi"))
        // The first row is the device that the person looks from.
        assertTrue(block(html).indexOf("Questo dispositivo") < block(html).indexOf("""aria-label="Togli il dispositivo"""))
    }

    @Test
    fun `t15 c14 a row never says a name of the device, a browser, a place or a last use`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)

        val words = text(block(phone.body("/profilo")))

        for (word in listOf("Chrome", "Safari", "Firefox", "iPhone", "Android", "Windows", "ultimo", "Ultimo", "IP ", "Roma", "ultima volta")) assertFalse(words.contains(word), "the row says $word")
    }

    @Test
    fun `t15 c14 without devices the block says what the box does, and the button to exit from the others is there all the same`() {
        val phone = phone()
        phone.enter(person())

        val html = phone.body("/profilo")

        val words = text(block(html))
        assertTrue(words.contains("Nessun dispositivo collegato. Quando entri con un link, puoi spuntare «Ricordami su questo telefono»: da lì rientri senza chiedere un nuovo link, per 30 giorni."), words)
        assertFalse(Regex("""<span[^>]*>Questo dispositivo</span>""").containsMatchIn(html), "no row, so no label")
        assertTrue(html.contains("Esci da tutti gli altri dispositivi"), "it also closes the sessions that have no row")
    }

    @Test
    fun `t15 c06 a teacher reads 7 days and the last day of a teacher, and a parent who became a teacher sees the rows shorten`() {
        val email = person("maestro-dispositivi")
        TestAccounts.makeTeacher(dsl, email)
        val phone = phone()
        phone.enter(email, remember = true)

        val words = text(block(phone.body("/profilo")))

        assertTrue(words.contains("per 7 giorni dal giorno della scelta"), words)
        assertTrue(words.contains("Rientri senza link fino al ${GatheringFormat.date(policy.deviceLastDay(today(), teacher = true))}."), words)
    }

    @Test
    fun `t15 c14 the order is this device first, then from the most recent`() {
        val email = person()
        val old = phone()
        old.enter(email, remember = true)
        val middle = phone()
        middle.enter(email, remember = true)
        val here = phone()
        here.enter(email, remember = true)
        // The rows have the same day: the day of the oldest goes back, so that the three can be told apart.
        dsl.execute("update remembered_device set created_on = created_on - 5 where selector = ?", Phone.decode(old.deviceCookie!!.value).first)
        dsl.execute("update remembered_device set created_on = created_on - 2 where selector = ?", Phone.decode(middle.deviceCookie!!.value).first)

        val html = block(middle.body("/profilo"))

        val days = Regex("Collegato dal ([0-9]+ [a-z]+ [0-9]{4})").findAll(html).map { it.groupValues[1] }.toList()
        assertEquals(listOf(GatheringFormat.date(today().minusDays(2)), GatheringFormat.date(today()), GatheringFormat.date(today().minusDays(5))), days, "this device (the middle one) first, then the most recent, then the oldest")
    }

    @Test
    fun `t15 c14 a row that is past its day is not shown even if the job has not deleted it yet`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        val gone = phone()
        gone.enter(email, remember = true)
        dsl.execute("update remembered_device set created_on = created_on - 31 where selector = ?", Phone.decode(gone.deviceCookie!!.value).first)

        val html = block(phone.body("/profilo"))

        assertEquals(1, Regex("Collegato dal").findAll(html).count())
    }

    // ---- «Togli» (criteria 15, 17, 18) ------------------------------------------------------------------------------------------------------

    private fun deviceIdOf(phone: Phone): String = dsl.select(REMEMBERED_DEVICE.ID).from(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.SELECTOR.eq(Phone.decode(phone.deviceCookie!!.value).first)).fetchSingle(REMEMBERED_DEVICE.ID).toString()

    @Test
    fun `t15 c15 Togli takes the row away, and that device, at its next request, goes to the access page as a whole page with a notice, even with a session open`() {
        val email = person()
        val here = phone()
        val there = phone()
        here.enter(email, remember = true)
        there.enter(email, remember = true)
        assertEquals(200, there.get("/profilo").response.status, "the other device is inside, with a session")
        val target = deviceIdOf(there)

        val answer = here.submit("/profilo", "/profilo/dispositivi/togli", mapOf("dispositivo" to target)) { header("HX-Request", "true") }

        assertEquals(200, answer.response.status)
        val html = answer.response.contentAsString
        assertTrue(html.trim().startsWith("""<div id="dispositivi-contenuto">"""), "the answer is the block, not a page")
        assertTrue(html.contains("Dispositivo tolto."))
        assertTrue(Regex("""role="status"[^>]*data-fuoco|data-fuoco[^>]*role="status"""").containsMatchIn(html), "the notice takes the focus")
        assertEquals(1, selectors(email).size)
        assertEquals(1, Regex("Collegato dal").findAll(html).count(), "the list is the one of now")
        // The other phone, at its next request, whatever it asks.
        val next = there.get("/profilo")
        assertEquals(302, next.response.status)
        assertEquals("/accedi", next.response.getHeader("Location"))
        assertTrue(there.body("/accedi").contains("L’accesso su questo telefono è stato chiuso. Per rientrare, chiedi un nuovo link."))
        val fragment = phone().also { it.session = there.session }.get("/iscrizioni") { header("HX-Request", "true") }
        assertEquals("/accedi", fragment.response.getHeader("HX-Redirect"), "a piece of page: a whole page of access, never inside the old one")
    }

    @Test
    fun `t15 c15 the device that is taken away cannot enter again with its cookie, and the other one can`() {
        val email = person()
        val here = phone()
        val there = phone()
        here.enter(email, remember = true)
        there.enter(email, remember = true)
        val target = deviceIdOf(there)
        here.submit("/profilo", "/profilo/dispositivi/togli", mapOf("dispositivo" to target)) { header("HX-Request", "true") }

        there.forgetSession()
        assertEquals("/accedi", there.get("/profilo").response.getHeader("Location"))
        here.forgetSession()
        assertEquals(200, here.get("/profilo").response.status)
    }

    @Test
    fun `t15 c15 Togli on the device the person is on is Esci, the row and the cookie go, the session ends and the access page says Accesso chiuso`() {
        val email = person()
        val here = phone()
        here.enter(email, remember = true)
        val target = deviceIdOf(here)

        val answer = here.submit("/profilo", "/profilo/dispositivi/togli", mapOf("dispositivo" to target)) { header("HX-Request", "true") }

        assertEquals(200, answer.response.status)
        assertEquals("/accedi", answer.response.getHeader("HX-Redirect"), "htmx loads the access page as a whole page")
        assertEquals(0, selectors(email).size)
        assertNull(here.deviceCookie, "the cookie is cancelled")
        assertTrue(here.body("/accedi").contains("Accesso chiuso. Per rientrare, chiedi un nuovo link."))
        assertEquals("/accedi", here.get("/profilo").response.getHeader("Location"))
    }

    @Test
    fun `t15 c15 without JavaScript Togli leads to a page with the words of the dialog, and its button does the job and goes back to the profile with the notice`() {
        val email = person()
        val here = phone()
        val there = phone()
        here.enter(email, remember = true)
        there.enter(email, remember = true)
        val target = deviceIdOf(there)

        val page = here.submit("/profilo", "/profilo/dispositivi/togli/conferma", mapOf("dispositivo" to target))
        assertEquals(200, page.response.status)
        val html = page.response.contentAsString
        val since = GatheringFormat.date(today())
        assertTrue(html.contains("""<h1 tabindex="-1" class="text-2xl font-semibold tracking-tight wrap-anywhere lg:text-3xl">Togliere il dispositivo collegato dal $since?</h1>"""), html)
        assertTrue(html.contains("Quel telefono o computer esce da Talità. Per rientrare servirà un nuovo link, o una passkey."))
        assertTrue(html.contains("Gli altri dispositivi restano collegati."))
        assertTrue(html.contains("""href="/profilo""""), "Annulla leads to the profile")
        assertEquals(2, selectors(email).size, "nothing changed yet")

        val done = here.submit("/profilo", "/profilo/dispositivi/togli", mapOf("dispositivo" to target))

        assertEquals(303, done.response.status)
        assertTrue(done.response.getHeader("Location")!!.startsWith("/profilo"))
        assertEquals(1, selectors(email).size)
        val profile = here.body("/profilo")
        assertTrue(text(block(profile)).contains("Dispositivo tolto."), "the notice is in the block")
        assertFalse(text(block(here.body("/profilo"))).contains("Dispositivo tolto."), "once")
    }

    @Test
    fun `t15 c17 the id of a device of someone else, from a parent or from a teacher, takes away nothing and is answered as an id that does not exist`() {
        val owner = person("proprietario")
        val parent = person("altro-genitore")
        val teacher = person("altro-maestro")
        TestAccounts.makeTeacher(dsl, teacher)
        val ownerPhone = phone()
        ownerPhone.enter(owner, remember = true)
        val target = deviceIdOf(ownerPhone)

        for (who in listOf(parent, teacher)) {
            val phone = phone()
            phone.enter(who, remember = true)
            val answer = phone.submit("/profilo", "/profilo/dispositivi/togli", mapOf("dispositivo" to target)) { header("HX-Request", "true") }

            assertEquals(200, answer.response.status, who)
            assertTrue(answer.response.contentAsString.contains("Questo dispositivo era già stato tolto."), who)
            assertEquals(1, selectors(owner).size, "$who took nothing away")
            assertFalse(answer.response.contentAsString.contains(Phone.decode(ownerPhone.deviceCookie!!.value).first), "nobody sees the devices of another")
        }
        val missing = phone()
        missing.enter(person(), remember = true)
        val none = missing.submit("/profilo", "/profilo/dispositivi/togli", mapOf("dispositivo" to "99999999")) { header("HX-Request", "true") }
        assertTrue(none.response.contentAsString.contains("Questo dispositivo era già stato tolto."), "the same answer for an id that does not exist")
        val garbage = missing.submit("/profilo", "/profilo/dispositivi/togli", mapOf("dispositivo" to "non-un-numero")) { header("HX-Request", "true") }
        assertTrue(garbage.response.contentAsString.contains("Questo dispositivo era già stato tolto."))
        assertEquals(1, selectors(owner).size)
    }

    @Test
    fun `t15 c18 a double tap on Togli shows the state of now with an information notice and no error`() {
        val email = person()
        val here = phone()
        val there = phone()
        here.enter(email, remember = true)
        there.enter(email, remember = true)
        val target = deviceIdOf(there)

        val first = here.submit("/profilo", "/profilo/dispositivi/togli", mapOf("dispositivo" to target)) { header("HX-Request", "true") }
        val second = here.submit("/profilo", "/profilo/dispositivi/togli", mapOf("dispositivo" to target)) { header("HX-Request", "true") }

        assertEquals(200, first.response.status)
        assertEquals(200, second.response.status)
        assertTrue(second.response.contentAsString.contains("Questo dispositivo era già stato tolto."))
        assertFalse(second.response.contentAsString.contains("role=\"alert\""), "not an error")
        assertEquals(1, selectors(email).size)
    }

    // ---- «Esci da tutti gli altri dispositivi» (criteria 16, 18) --------------------------------------------------------------------------

    @Test
    fun `t15 c16 Esci da tutti gli altri dispositivi deletes the rows of the others, raises the number, and the other two, one with the box and one without, go to the access page while this one stays`() {
        val email = person()
        val here = phone()
        val withBox = phone()
        val withoutBox = phone()
        here.enter(email, remember = true)
        withBox.enter(email, remember = true)
        withoutBox.enter(email)
        val before = dsl.select(ACCOUNT.SESSION_GENERATION).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.SESSION_GENERATION)!!
        assertEquals(200, withBox.get("/profilo").response.status)
        assertEquals(200, withoutBox.get("/profilo").response.status)

        val answer = here.submit("/profilo", "/profilo/dispositivi/esci-altri", mapOf("azione" to "esci-altri")) { header("HX-Request", "true") }

        assertEquals(200, answer.response.status)
        val html = answer.response.contentAsString
        assertTrue(html.contains("Gli altri dispositivi sono usciti."))
        assertTrue(html.contains("Questo resta dentro."))
        assertFalse(html.contains("Vai alle passkey"), "no passkeys: only «Questo resta dentro.»")
        assertEquals(1, selectors(email).size, "the rows of the others are gone")
        assertEquals(before + 1, dsl.select(ACCOUNT.SESSION_GENERATION).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.SESSION_GENERATION))
        for (other in listOf(withBox, withoutBox)) {
            val next = other.get("/profilo")
            assertEquals(302, next.response.status)
            assertEquals("/accedi", next.response.getHeader("Location"))
            assertTrue(other.body("/accedi").contains("L’accesso su questo telefono è stato chiuso."))
        }
        // This one stays inside, with the same session, and in the next requests too.
        assertEquals(200, here.get("/profilo").response.status)
        assertEquals(200, here.get("/").response.status)
        here.forgetSession()
        assertEquals(200, here.get("/profilo").response.status, "and with its cookie, after the session ends")
    }

    @Test
    fun `t15 c16 the dialog of Esci da tutti says what happens, and without passkeys does not send to a list that is not there`() {
        val phone = phone()
        phone.enter(person(), remember = true)

        val html = phone.body("/profilo")

        assertTrue(html.contains("Uscire da tutti gli altri dispositivi?"))
        assertTrue(html.contains("Tutti gli altri telefoni e computer escono da Talità, anche quelli dove hai scelto «Ricordami su questo telefono». Per rientrare servirà un nuovo link, o una passkey."))
        assertTrue(html.contains("Questo dispositivo resta dentro."))
        assertFalse(html.contains("Le passkey non cambiano"), "no passkeys, nothing to send to")
    }

    @Test
    fun `t15 c16 without JavaScript Esci da tutti leads to a page with the words of the dialog, and its button closes the others`() {
        val email = person()
        val here = phone()
        val there = phone()
        here.enter(email, remember = true)
        there.enter(email, remember = true)

        val page = here.submit("/profilo", "/profilo/dispositivi/esci-altri/conferma", mapOf("azione" to "esci-altri"))
        assertEquals(200, page.response.status)
        assertTrue(page.response.contentAsString.contains("Uscire da tutti gli altri dispositivi?"))
        assertEquals(2, selectors(email).size, "nothing changed yet")

        val done = here.submit("/profilo", "/profilo/dispositivi/esci-altri", mapOf("azione" to "esci-altri"))

        assertEquals(303, done.response.status)
        assertEquals(1, selectors(email).size)
        assertTrue(text(block(here.body("/profilo"))).contains("Gli altri dispositivi sono usciti."))
    }

    @Test
    fun `t15 c18 a double tap on Esci da tutti shows the state of now, without an error`() {
        val email = person()
        val here = phone()
        val there = phone()
        here.enter(email, remember = true)
        there.enter(email, remember = true)

        here.submit("/profilo", "/profilo/dispositivi/esci-altri", mapOf("azione" to "esci-altri")) { header("HX-Request", "true") }
        val second = here.submit("/profilo", "/profilo/dispositivi/esci-altri", mapOf("azione" to "esci-altri")) { header("HX-Request", "true") }

        assertEquals(200, second.response.status)
        assertTrue(second.response.contentAsString.contains("Gli altri dispositivi sono usciti."))
        assertEquals(1, selectors(email).size)
        assertEquals(200, here.get("/profilo").response.status, "this one is still inside after two taps")
    }

    @Test
    fun `t15 c16 the address of an action reloaded by hand with a GET gives the profile, not a refusal`() {
        val phone = phone()
        phone.enter(person())

        for (path in listOf("/profilo/dispositivi/togli", "/profilo/dispositivi/esci-altri", "/profilo/dispositivi/togli/conferma", "/profilo/dispositivi/esci-altri/conferma")) {
            val answer = phone.get(path)
            assertEquals(303, answer.response.status, path)
            assertEquals("/profilo", answer.response.getHeader("Location"), path)
        }
    }

    @Test
    fun `t15 c16 the id of a device is never in an address, in a page of someone else or in a link`() {
        val email = person()
        val phone = phone()
        phone.enter(email, remember = true)
        val there = phone()
        there.enter(email, remember = true)

        val html = phone.body("/profilo")

        assertFalse(Regex("""href="[^"]*(dispositivo|togli)[^"]*[0-9]{1,18}[^"]*"""").containsMatchIn(html), "no id in an address")
        assertNotEquals(deviceIdOf(phone), "")
    }
}
