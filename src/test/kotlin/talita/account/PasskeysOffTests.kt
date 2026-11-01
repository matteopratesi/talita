package talita.account

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.PostgresConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The application as the smoke test starts it, with no public address of the site (TALITA-15, criterion 28): there are no passkeys, with no buttons, no pages that offer them and no addresses that
 * answer; the rest of the access works, with the link and with the box «Ricordami su questo telefono», and the log says at the start that the passkeys are off. Only the database is there.
 *
 * The capture of the output is on the **class** on purpose (TALITA-16, criterion 4): the test reads the log of the start of the application ("Passkeys are off"), which is written while the
 * context starts, before the test. This class has a context of its own (the extra property), so nothing else wrote before it.
 */
@Import(PostgresConfiguration::class)
// The extra property is only there so that this class has an application of its own: the test that reads the log of the start (the one of the mail, in `NoMailTests`) would not find it in an application that
// another class started before.
@SpringBootTest(properties = [PostgresConfiguration.PRIVACY_CONTROLLER, PostgresConfiguration.PRIVACY_CONTACT, "talita.test.passkeys-off=true"])
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class PasskeysOffTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val support: PasskeySupport,
) {

    private fun phone() = Phone(mockMvc, tokens)

    @Test
    fun `t15 c28 without the public address the passkeys are off and the log says so at the start`(output: CapturedOutput) {
        assertFalse(support.enabled)
        assertTrue(output.all.contains("Passkeys are off: talita.public-url is not set"), "the log does not say it")
    }

    @Test
    fun `t15 c34 without the public address the complete notice does not speak of passkeys either, and keeps what is about the box and the cookies`() {
        val page = talita.gathering.text(phone().get("/informativa").response.contentAsString)

        assertFalse(page.contains("passkey", ignoreCase = true), Regex(".{0,80}passkey.{0,80}", RegexOption.IGNORE_CASE).find(page)?.value)
        assertTrue(page.contains("Cookie e accesso"))
        assertTrue(page.contains("«Ricordami su questo telefono» non è obbligatorio: il link funziona sempre."))
        assertTrue(page.contains("Di chi sceglie «Ricordami su questo telefono»: il giorno in cui l’ha fatto e un codice casuale che riconosce il dispositivo."))
        // The hour of the last re-entry is kept only for the two tabs and goes the night after: the line of the data and the row of the times say it (without passkeys the line speaks of «rientra»).
        assertTrue(page.contains("Quando rientra senza link teniamo anche l’ora di quel rientro, solo perché due schede aperte insieme non lo scolleghino, e la cancelliamo la notte dopo."))
        assertTrue(page.contains("Talità li cancella da sola, ogni notte, quando scadono. L’ora dell’ultimo rientro senza link si cancella la notte dopo."))
        assertTrue(page.contains("I dispositivi collegati li vedi solo tu, nel tuo profilo."))
        assertTrue(page.contains("Sempre dal «Profilo» togli i dispositivi collegati."))
        assertTrue(page.contains("Dispositivi collegati («Ricordami su questo telefono»)"))
    }

    @Test
    fun `t15 c28 the access page has no block of the passkeys, no script and no notice with the sentence about them`() {
        val html = phone().body("/accedi")

        assertFalse(html.contains("Entra con una passkey"))
        assertFalse(html.contains("data-passkey"))
        assertFalse(html.contains("passkeys"), "no script of the passkeys")
        assertTrue(html.contains("Ricevi il link"))
    }

    @Test
    fun `t15 c28 the profile has no card Passkey and no sentence about them, and the dialogs do not say a passkey`() {
        val phone = phone()
        val email = Addresses.unique("senza-chiavi")
        phone.enter(email, remember = true)

        val html = phone.body("/profilo")

        assertFalse(html.contains("Passkey"), Regex(".{0,80}Passkey.{0,80}", RegexOption.DOT_MATCHES_ALL).find(html)?.value)
        assertFalse(html.contains("passkey"), Regex(".{0,80}passkey.{0,80}", RegexOption.DOT_MATCHES_ALL).find(html)?.value)
        assertFalse(html.contains("""id="passkey""""))
        assertTrue(html.contains("Dispositivi collegati"), "the rest of the access is there")
        assertTrue(html.contains("Per rientrare servirà un nuovo link."), "and the dialogs do not promise a passkey")
        assertFalse(html.contains("o una passkey"))
    }

    @Test
    fun `t15 c28 the addresses of the passkeys answer nothing, with or without the CSRF code`() {
        val phone = phone()
        phone.enter(Addresses.unique("senza-chiavi"))
        val code = phone.csrfOf("/profilo")

        for (path in listOf("/webauthn/register/options", "/webauthn/register")) {
            assertEquals(404, phone.postJson(path, "{}", code).response.status, path)
        }
        val anonymous = phone()
        val anonymousCode = anonymous.csrfOf("/accedi")
        for (path in listOf("/webauthn/authenticate/options", "/login/webauthn")) {
            assertEquals(404, anonymous.postJson(path, "{}", anonymousCode).response.status, path)
        }
    }

    @Test
    fun `t15 c28 the link and the box still work`() {
        val phone = phone()
        val email = Addresses.unique("senza-chiavi")

        phone.enter(email, remember = true)

        assertEquals(200, phone.get("/profilo").response.status)
        assertTrue(phone.deviceCookie != null, "the cookie of the box")
        phone.forgetSession()
        assertEquals(200, phone.get("/profilo").response.status, "and it lets the person in again")
    }
}
