package talita.account

import org.springframework.http.MediaType
import org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.api.PublicKeyCredential
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey
import org.springframework.test.web.servlet.MvcResult

/**
 * What the tests of the passkeys do over HTTP, as the script of the passkeys does it: add a passkey from the profile, enter from the access page, ask for a confirmation and answer it. A [Phone]
 * is the browser and a [SoftPasskey] the phone that answers the requests of the browser. The CSRF code goes in the header, as the script sends it.
 */
object PasskeyKit {

    @Suppress("UNCHECKED_CAST")
    fun json(result: MvcResult): Map<String, Any?> = SoftPasskey.json.readValue(result.response.contentAsString, Map::class.java) as Map<String, Any?>

    private fun bytes(text: Any?): Bytes = Bytes.fromBase64(text as String)

    /** The phone [soft] adds a passkey from the profile of [phone]: the two requests of the script. The answer of the second, or of the first if it was refused. */
    fun add(phone: Phone, soft: SoftPasskey = SoftPasskey("https://talita.example.org", "talita.example.org")): MvcResult {
        val csrf = phone.csrfOf("/profilo")
        val started = phone.postJson("/webauthn/register/options", csrf = csrf)
        if (started.response.status != 200) return started
        val options = json(started)
        @Suppress("UNCHECKED_CAST")
        val user = options["user"] as Map<String, Any?>
        val credential = soft.create(bytes(options["challenge"]), bytes(user["id"]))
        return phone.postJson("/webauthn/register", SoftPasskey.json.writeValueAsString(mapOf("publicKey" to RelyingPartyPublicKey(credential, ""))), csrf)
    }

    /** The phone [soft] enters from the access page of [phone]. [answer] makes what the phone sends from the challenge: a good one by default. */
    fun signIn(phone: Phone, soft: SoftPasskey, answer: (SoftPasskey, Bytes) -> PublicKeyCredential<AuthenticatorAssertionResponse> = { s, c -> s.get(c) }): MvcResult {
        val csrf = phone.csrfOf("/accedi")
        val started = phone.postJson("/webauthn/authenticate/options", csrf = csrf)
        check(started.response.status == 200) { "the options of the access: ${started.response.status}" }
        return phone.postJson("/login/webauthn", SoftPasskey.json.writeValueAsString(answer(soft, bytes(json(started)["challenge"]))), csrf)
    }

    /** The first request of a confirmation: the options, or `{"serve":false}`. */
    fun confirmationOptions(phone: Phone, csrf: String = phone.csrfOf("/profilo")): MvcResult = phone.postJson("/verifica-passkey/opzioni", csrf = csrf)

    /**
     * The whole confirmation: asks for the options and, if a confirmation is needed, answers with [soft] (what [answer] makes from the challenge: a good answer by default). Returns the answer of the
     * second request, or the one of the first if there was nothing to confirm.
     */
    fun confirm(phone: Phone, soft: SoftPasskey, answer: (SoftPasskey, Bytes) -> PublicKeyCredential<AuthenticatorAssertionResponse> = { s, c -> s.get(c) }): MvcResult {
        val csrf = phone.csrfOf("/profilo")
        val started = confirmationOptions(phone, csrf)
        check(started.response.status == 200) { "the options of the confirmation: ${started.response.status}" }
        val options = json(started)
        if (options["serve"] != true) return started
        @Suppress("UNCHECKED_CAST")
        val inner = options["options"] as Map<String, Any?>
        return phone.postJson("/verifica-passkey", SoftPasskey.json.writeValueAsString(answer(soft, bytes(inner["challenge"]))), csrf)
    }

    /** A POST of an htmx form of a page (a piece of a page): the page first, for the CSRF code. */
    fun htmxPost(phone: Phone, page: String, action: String, fields: Map<String, String> = emptyMap()): MvcResult = phone.submit(page, action, fields) { header("HX-Request", "true") }

    val JSON_TYPE: MediaType = MediaType.APPLICATION_JSON
}
