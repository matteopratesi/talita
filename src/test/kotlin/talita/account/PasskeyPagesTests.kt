package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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
import talita.jooq.tables.references.PASSKEY
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.retention.RetentionTestKit
import talita.registration.Notes
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pages that carry the passkeys (TALITA-15): no script in line, no style in line and no handler written in the markup (criterion 27, with the Content-Security-Policy of always), the file of the script served by
 * the application and written as the criterion says (base64url by hand, the CSRF code in the header), and what happens when the person cancels on the phone or the network is missing (criterion 24): the server has
 * nothing to undo, because nothing was kept, and the pages carry the words for each outcome, hidden, for the script to show.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PasskeyPagesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val origin = TestcontainersConfiguration.PUBLIC_URL
    private val rpId = "talita.example.org"
    private val kit = RetentionTestKit(dsl, clock)
    private val made = mutableListOf<String>()

    @BeforeEach
    fun start() {
        kit.reset()
    }

    @AfterEach
    fun clean() {
        kit.reset()
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.`in`(ids)).execute()
        dsl.execute("delete from teacher where account_id = any(?)", ids.toTypedArray())
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
    }

    private fun phone() = Phone(mockMvc, tokens)

    private fun teacherWithPasskey(): Pair<Phone, SoftPasskey> {
        val email = Addresses.unique("maestro-pagine").also { made += it }
        TestAccounts.makeTeacher(dsl, email)
        val phone = phone()
        phone.enter(email)
        val soft = SoftPasskey(origin, rpId)
        assertEquals(200, PasskeyKit.add(phone, soft).response.status)
        return phone to soft
    }

    private fun scriptSources(html: String): List<String> = Regex("""<script\b([^>]*)>""").findAll(html).map { it.groupValues[1] }.toList()

    // ---- No script in line (criterion 27) ------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c27 the pages with passkeys have no script in line, no style in line and no handler in the markup, and one script file of the application`() {
        val (phone, _) = teacherWithPasskey()
        val gathering = kit.september2027()
        val child = kit.registration(gathering, kit.account("genitore"), "Elena", "Marino", notes = Notes("Arachidi", null, null))
        val paper = kit.paper(gathering, null, notes = Notes("Latte", null, null))
        val parent = phone().also { it.enter(Addresses.unique("genitore-pagine").also { email -> made += email }) }

        val pages = mapOf(
            "access" to phone().get("/accedi").response.contentAsString,
            "profile of a teacher with a passkey" to phone.get("/profilo").response.contentAsString,
            "profile of a parent" to parent.get("/profilo").response.contentAsString,
            "detail of a registration" to phone.get("/iscrizioni/$child").response.contentAsString,
            "card of the arrivals" to phone.get("/arrivi/$child").response.contentAsString,
            "correction that asks for the confirmation" to phone.get("/iscrizioni/$paper/correggi-segnalazioni").response.contentAsString,
            "the notice" to phone().get("/informativa").response.contentAsString,
        )

        for ((name, html) in pages) {
            for (attributes in scriptSources(html)) assertTrue(Regex("""\bsrc="/[^"]+"""").containsMatchIn(attributes), "$name: a script that is not a file of the application: <script$attributes>")
            assertFalse(Regex("""<script(?![^>]*\bsrc=)[^>]*>""").containsMatchIn(html), "$name has a script in line")
            assertFalse(Regex("""\sstyle=""").containsMatchIn(html), "$name has a style in line")
            assertFalse(Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name has a handler written in the markup")
            assertFalse(html.contains("javascript:"), "$name has a javascript: address")
        }
        assertTrue(Regex("""/js/passkeys[^"]*\.js""").containsMatchIn(pages.getValue("access")), "the access page loads the script of the passkeys")
        assertEquals(1, Regex("""/js/passkeys[^"]*\.js""").findAll(pages.getValue("profile of a teacher with a passkey")).count(), "the script is loaded once")
    }

    @Test
    fun `t15 c27 the Content-Security-Policy allows only the application, with no unsafe-inline and no unsafe-eval, and the script is served by the application`() {
        val csp = phone().get("/accedi").response.getHeader("Content-Security-Policy").orEmpty()

        assertTrue(csp.contains("script-src 'self'"), csp)
        assertFalse(csp.contains("unsafe-inline") || csp.contains("unsafe-eval"), csp)
        assertFalse(Regex("""https?://""").containsMatchIn(csp), "no other address in the policy: $csp")
        val source = Regex("""src="(/js/passkeys[^"]*\.js)"""").find(phone().get("/accedi").response.contentAsString)!!.groupValues[1]
        val script = phone().get(source)
        assertEquals(200, script.response.status)
        assertTrue(script.response.contentType.orEmpty().contains("javascript"), script.response.contentType)
    }

    @Test
    fun `t15 c27 the script converts base64url by hand, sends the CSRF code, has no call that iOS 16 lacks and no address of anybody else`() {
        val source = Regex("""src="(/js/passkeys[^"]*\.js)"""").find(phone().get("/accedi").response.contentAsString)!!.groupValues[1]
        val script = phone().get(source).response.contentAsString

        assertTrue(script.contains("""replace(/-/g, "+")""") && script.contains("""replace(/_/g, "/")"""), "base64url by hand")
        assertTrue(script.contains("X-CSRF-TOKEN"), "the CSRF code in the header")
        assertFalse(Regex("""\.parse(Creation|Request)OptionsFromJSON\s*\(""").containsMatchIn(script), "a call that iOS 16 does not have")
        assertFalse(Regex("""\.toJSON\s*\(""").containsMatchIn(script), "toJSON does not exist on iOS 16 either")
        assertFalse(Regex("""https?://""").containsMatchIn(script), "no address in the script")
        assertFalse(Regex("""\b(eval|new Function)\s*\(""").containsMatchIn(script), "no eval")
        assertFalse(Regex("""console\.(log|debug|info)""").containsMatchIn(script), "nothing is written in the console: not a challenge, an id or an option")
    }

    // ---- Cancelled and without network (criterion 24) --------------------------------------------------------------------------------------

    @Test
    fun `t15 c24 a request for the options that the phone never answers leaves nothing, no passkey, nobody inside, no confirmation`() {
        val (phone, soft) = teacherWithPasskey()
        PasskeyKit.confirm(phone, soft)
        val before = dsl.fetchCount(PASSKEY)
        // The person cancels on the phone while adding: the options were given, the answer never comes.
        assertEquals(200, phone.postJson("/webauthn/register/options", csrf = phone.csrfOf("/profilo")).response.status)
        assertEquals(before, dsl.fetchCount(PASSKEY), "nothing is saved while adding")
        // And while coming in: the options were given, the answer never comes.
        val visitor = phone()
        assertEquals(200, visitor.postJson("/webauthn/authenticate/options", csrf = visitor.csrfOf("/accedi")).response.status)
        assertEquals(302, visitor.get("/profilo").response.status, "nobody is inside")
        // And while confirming.
        val other = phone().also { it.enter(made.last()) }
        assertEquals(true, PasskeyKit.json(PasskeyKit.confirmationOptions(other))["serve"])
        assertEquals(true, PasskeyKit.json(PasskeyKit.confirmationOptions(other))["serve"], "asking again does not change anything: still to confirm")
    }

    @Test
    fun `t15 c24 the pages carry the words for a cancelled phone and for no network, hidden, so that the script shows them and the text is never written in JavaScript`() {
        val access = phone().get("/accedi").response.contentAsString
        assertTrue(access.contains("""data-passkey-esito="annullata"""") && access.contains("""data-passkey-esito="non-valida""""), "the access page")
        assertTrue(Regex("""<div hidden data-passkey-esito="annullata"[^>]*>""").containsMatchIn(access), "hidden")
        val (teacher, _) = teacherWithPasskey()
        val profile = teacher.get("/profilo").response.contentAsString
        for (outcome in listOf("aggiungi-annullata", "aggiungi-rifiutata", "togli-annullata", "togli-rifiutata")) {
            assertTrue(Regex("""data-conferma-esito="$outcome"""").containsMatchIn(profile), "the profile has $outcome")
        }
        val script = phone().get(Regex("""src="(/js/passkeys[^"]*\.js)"""").find(access)!!.groupValues[1]).response.contentAsString
        // The words are in the page: the script does not carry a sentence of the notices.
        for (sentence in listOf("Hai annullato", "La conferma non è arrivata", "La conferma non è riuscita", "Passkey aggiunta")) assertFalse(script.contains(sentence), "the script says: $sentence")
        // Without a network the script asks the page for the pop-up of the guide: it does not write its own.
        assertTrue(script.contains("talita:messaggio"), "the event that asks app.js for the message")
    }
}
