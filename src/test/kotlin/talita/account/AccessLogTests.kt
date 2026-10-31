package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.PASSKEY
import talita.jooq.tables.references.REMEMBERED_DEVICE
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the log never says (TALITA-15, criterion 33): at level INFO and above it has no selector, no value of the cookie, no fingerprint, no id of a passkey, no user handle, no challenge, no address and no id of an
 * account, **whatever happens**: the way in with the box, the rotation, the cookie that is copied (a WARN, without data), a passkey that is added, used and refused, the confirmation, what is taken away. The test goes
 * through all of it with a phone and with a soft authenticator and looks at everything that the application wrote.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class AccessLogTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
) {

    private val origin = TestcontainersConfiguration.PUBLIC_URL
    private val rpId = "talita.example.org"
    private val made = mutableListOf<String>()

    @AfterEach
    fun clean() {
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.`in`(ids)).execute()
        dsl.execute("delete from teacher where account_id = any(?)", ids.toTypedArray())
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
    }

    private fun phone() = Phone(mockMvc, tokens)

    @Test
    fun `t15 c33 the log has no selector, value, fingerprint, id of a passkey, user handle, challenge, address or id of an account, from the way in to the passkeys and the confirmation`(output: CapturedOutput) {
        val secrets = mutableMapOf<String, String>()
        val email = Addresses.unique("log-maestro").also { made += it }
        val id = TestAccounts.makeTeacher(dsl, email)
        secrets["the address"] = email
        val phone = phone()

        // The way in with the box, and the rotation of the value when the person comes back without a session.
        phone.enter(email, remember = true)
        val (selector, firstValue) = checkNotNull(phone.deviceTokens())
        val copiedCookie = checkNotNull(phone.deviceCookie)
        phone.forgetSession()
        assertEquals(200, phone.get("/profilo").response.status)
        val (_, secondValue) = checkNotNull(phone.deviceTokens())
        assertTrue(firstValue != secondValue, "the value rotates")
        secrets["the selector"] = selector
        secrets["the first value"] = firstValue
        secrets["the second value"] = secondValue

        // The passkeys: added, used to come in, refused, and the confirmation (asked, answered well and refused).
        val soft = SoftPasskey(origin, rpId)
        assertEquals(200, PasskeyKit.add(phone, soft).response.status)
        secrets["the id of the passkey"] = Bytes(soft.credentialId).toBase64UrlString()
        val handle = dsl.select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.PASSKEY_HANDLE)!!
        secrets["the user handle"] = Bytes(handle).toBase64UrlString()
        val signing = phone()
        assertEquals(200, PasskeyKit.signIn(signing, soft).response.status)
        assertEquals(401, PasskeyKit.signIn(phone(), soft) { s, c -> s.get(c, userVerified = false) }.response.status)
        val csrf = phone.csrfOf("/profilo")
        @Suppress("UNCHECKED_CAST")
        val challenge = ((PasskeyKit.json(PasskeyKit.confirmationOptions(phone, csrf))["options"] as Map<String, Any?>)["challenge"]) as String
        secrets["the challenge"] = challenge
        assertEquals(200, phone.postJson("/verifica-passkey", SoftPasskey.json.writeValueAsString(soft.get(Bytes.fromBase64(challenge))), csrf).response.status)
        val viaLink = phone().also { it.enter(email) }
        assertEquals(403, PasskeyKit.confirm(viaLink, soft) { s, c -> s.get(c, userVerified = false) }.response.status)
        // What is taken away: the passkey and the device.
        val reference = Passkeys.reference(soft.credentialId)
        PasskeyKit.confirm(phone, soft)
        PasskeyKit.htmxPost(phone, "/profilo", "/profilo/passkey/togli", mapOf("passkey" to reference))
        phone.submit("/profilo", "/profilo/dispositivi/esci-altri", mapOf("azione" to "esci-altri")) { header("HX-Request", "true") }
        // The cookie that was copied, with the first value, after the 60 seconds of the margin: a WARN and no data (it unlinks the device, which is the point).
        dsl.execute("update remembered_device set rotated_at = rotated_at - interval '61 seconds' where account_id = ?", id)
        phone().also { it.jar[DeviceRememberMeServices.COOKIE] = copiedCookie }.get("/profilo")
        phone().also { it.enter(email) }.let { it.submit("/profilo", "/esci") }

        val log = output.all
        for ((name, secret) in secrets) assertFalse(log.contains(secret), "the log has $name")
        // No id of an account next to the word that names it.
        assertFalse(Regex("""(?i)account\D{0,12}\b$id\b""").containsMatchIn(log), "the log has the id of an account")
        // And the warnings that there are carry no data: the copied cookie is one of them.
        val warnings = log.lines().filter { " WARN " in it && "talita" in it }
        assertTrue(warnings.any { "Remember-me" in it }, "the copied cookie leaves a warning: $warnings")
    }
}
