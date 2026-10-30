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
import org.springframework.http.MediaType
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.security.web.webauthn.management.RelyingPartyPublicKey
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.PASSKEY
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.teacher.Invitations
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The passkeys through the real filters of Spring's WebAuthn, wired by us, with a phone written by hand ([SoftPasskey]) that sends what a browser sends (TALITA-15, criteria 19, 20, 22, 23, 26): adding a
 * passkey from the profile, entering with it from the access page, the notices, the destinations, what is refused, and the addresses that Spring's own removal would have given. The CSRF code goes
 * in the header, as the script of the passkeys sends it. Every test has its own addresses.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class PasskeyAccessTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val invitations: Invitations,
    @Autowired private val passkeys: Passkeys,
) {

    private val origin = TestcontainersConfiguration.PUBLIC_URL
    private val rpId = "talita.example.org"
    private val made = mutableListOf<String>()

    private fun phone() = Phone(mockMvc, tokens)

    private fun person(name: String = "passkey"): String = Addresses.unique(name).also { made += it }

    private fun softPhone(synchronized: Boolean = false) = SoftPasskey(origin, rpId, synchronized)

    @AfterEach
    fun clean() {
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.`in`(ids)).execute()
        dsl.execute("delete from teacher_invitation where email = any(?)", made.toTypedArray())
        dsl.execute("delete from teacher where account_id = any(?)", ids.toTypedArray())
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
    }

    private fun accountId(email: String): Long = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!

    private fun passkeyCount(email: String): Int = dsl.fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(accountId(email)))

    @Suppress("UNCHECKED_CAST")
    private fun json(result: MvcResult): Map<String, Any?> = SoftPasskey.json.readValue(result.response.contentAsString, Map::class.java) as Map<String, Any?>

    private fun bytes(text: Any?): Bytes = Bytes.fromBase64(text as String)

    /** The phone [soft] adds a passkey from the profile of [phone], through the two requests of the script. Returns the answer of the second (or of the first, if it was refused). */
    private fun add(phone: Phone, soft: SoftPasskey = softPhone(), htmx: Boolean = false): MvcResult {
        val csrf = phone.csrfOf("/profilo")
        val started = phone.postJson("/webauthn/register/options", csrf = csrf)
        if (started.response.status != 200) return started
        val options = json(started)
        @Suppress("UNCHECKED_CAST")
        val user = options["user"] as Map<String, Any?>
        val credential = soft.create(bytes(options["challenge"]), bytes(user["id"]))
        return phone.postJson("/webauthn/register", SoftPasskey.json.writeValueAsString(mapOf("publicKey" to RelyingPartyPublicKey(credential, ""))), csrf)
    }

    /** The phone [soft] enters from the access page of [phone], through the two requests of the script. */
    private fun enter(phone: Phone, soft: SoftPasskey, configure: (SoftPasskey, Bytes) -> org.springframework.security.web.webauthn.api.PublicKeyCredential<org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse> = { s, c -> s.get(c) }): MvcResult {
        val csrf = phone.csrfOf("/accedi")
        val started = phone.postJson("/webauthn/authenticate/options", csrf = csrf)
        assertEquals(200, started.response.status)
        val credential = configure(soft, bytes(json(started)["challenge"]))
        return phone.postJson("/login/webauthn", SoftPasskey.json.writeValueAsString(credential), csrf)
    }

    // ---- The card in the profile (criteria 19, 28) -----------------------------------------------------------------------------------

    @Test
    fun `t15 c19 the profile has the card Passkey with the explanation for who is not used to them, the button hidden until the script sees the support, and the sentence for a browser that cannot`() {
        val phone = phone()
        phone.enter(person())

        val html = phone.body("/profilo")

        assertTrue(Regex("""<h2[^>]*>Passkey</h2>""").containsMatchIn(html), "the card")
        assertTrue(html.indexOf("""id="accesso"""") < html.indexOf("""id="passkey""""), "after «Il tuo accesso»")
        assertTrue(html.contains("Con una passkey entri in Talità con l’impronta, il volto o il codice di sblocco del telefono, senza aspettare la mail."))
        assertTrue(html.contains("Non la scrivi e non la ricordi, e non la può usare nessun altro. Il link resta sempre: serve per il primo accesso, per un telefono nuovo e se la passkey non c’è più."))
        assertTrue(html.contains("Non hai ancora aggiunto nessuna passkey."))
        // The button is in the page but hidden: only the script shows it, where the browser can.
        assertTrue(Regex("""<div data-passkey hidden data-ambito[^>]*>\s*<button type="button"[^>]*data-azione="aggiungi-passkey"""").containsMatchIn(html), "the button, hidden")
        assertTrue(html.contains("Aggiungi una passkey su questo telefono"))
        assertTrue(html.contains("""<p data-passkey-no class="mt-4 text-ink-muted">Questo browser non sa usare le passkey. Per entrare, continua con il link: funziona sempre.</p>"""))
        assertTrue(html.contains("Se cambi o perdi il telefono"))
        assertTrue(html.contains("Talità non manda niente ad Apple o Google: le passkey le sincronizza il tuo telefono, in modo cifrato."))
        for (jargon in listOf("chiave pubblica", "WebAuthn", "FIDO", "autenticatore")) assertFalse(html.contains(jargon, ignoreCase = true), jargon)
        // The script of the passkeys is loaded from the app, and nothing is in line.
        assertTrue(Regex("""<script src="/js/passkeys-[0-9a-f]+\.js" defer></script>""").containsMatchIn(html))
    }

    @Test
    fun `t15 c19 the card of a person who has passkeys lists them with the day and Sincronizzata or Solo su un dispositivo, and Togli with a dialog`() {
        val email = person()
        val phone = phone()
        phone.enter(email)
        add(phone, softPhone(synchronized = true))
        add(phone, softPhone(synchronized = false))

        val html = phone.body("/profilo")

        assertEquals(2, Regex("""Aggiunta il \d{1,2} [a-z]+ \d{4}""").findAll(html).count())
        assertTrue(html.contains("Sincronizzata") && html.contains("Solo su un dispositivo"))
        assertTrue(html.contains("Si ritrova anche sugli altri dispositivi del tuo account Apple o Google."))
        assertTrue(html.contains("Resta solo sul dispositivo dove l’hai aggiunta: se lo cambi, non ti segue."))
        assertEquals(2, Regex("""aria-label="Togli la passkey aggiunta il""").findAll(html).count())
        assertTrue(html.contains("Togliere la passkey aggiunta il"))
        assertTrue(html.contains("Con questa passkey non potrai più entrare in Talità."))
        assertFalse(html.contains("Non hai ancora aggiunto nessuna passkey."))
        // Nothing but the day and the label: no name, no model, no last use, no label written by hand.
        for (word in listOf("Chrome", "Safari", "iPhone", "ultimo", "Ultimo", "AAGUID", "attestazione")) assertFalse(html.contains(word), word)
    }

    // ---- Adding a passkey (criterion 20) ----------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c20 adding a passkey from the profile saves it for the account, says Passkey aggiunta once, and the options ask for the person verified, a passkey that the phone finds by itself and no attestation`() {
        val email = person()
        val phone = phone()
        phone.enter(email)
        val csrf = phone.csrfOf("/profilo")

        val started = phone.postJson("/webauthn/register/options", csrf = csrf)

        assertEquals(200, started.response.status)
        val options = json(started)
        assertEquals("none", options["attestation"])
        @Suppress("UNCHECKED_CAST")
        val selection = options["authenticatorSelection"] as Map<String, Any?>
        assertEquals("required", selection["userVerification"])
        assertEquals("required", selection["residentKey"])
        @Suppress("UNCHECKED_CAST")
        val user = options["user"] as Map<String, Any?>
        assertEquals("Talità", user["name"])
        assertEquals("Talità", user["displayName"])
        assertEquals(rpId, (options["rp"] as Map<*, *>)["id"])
        assertEquals(emptyList<Any>(), options["excludeCredentials"])

        val soft = softPhone()
        val credential = soft.create(bytes(options["challenge"]), bytes(user["id"]))
        val done = phone.postJson("/webauthn/register", SoftPasskey.json.writeValueAsString(mapOf("publicKey" to RelyingPartyPublicKey(credential, ""))), csrf)

        assertEquals(200, done.response.status)
        assertEquals(true, json(done)["success"])
        assertEquals(1, passkeyCount(email))
        // The script asks for the block again: it comes with the notice, once.
        val block = phone.get("/profilo/passkey") { header("HX-Request", "true") }
        assertEquals(200, block.response.status)
        val html = block.response.contentAsString
        assertTrue(html.trim().startsWith("""<div id="passkey-contenuto""""), "the block, not a page")
        assertTrue(html.contains("Passkey aggiunta.") && html.contains("Dalla pagina «Accedi» puoi entrare con «Entra con una passkey», senza aspettare la mail."))
        assertFalse(html.contains("prima di aprire le segnalazioni"), "that is for the teachers")
        assertEquals(1, Regex("""Aggiunta il""").findAll(html).count())
        assertFalse(phone.get("/profilo/passkey") { header("HX-Request", "true") }.response.contentAsString.contains("Passkey aggiunta."), "once")
    }

    @Test
    fun `t15 c20 the options of a second registration leave out the passkeys that the account has`() {
        val email = person()
        val phone = phone()
        phone.enter(email)
        val first = softPhone()
        add(phone, first)

        val options = json(phone.postJson("/webauthn/register/options", csrf = phone.csrfOf("/profilo")))

        @Suppress("UNCHECKED_CAST")
        val excluded = options["excludeCredentials"] as List<Map<String, Any?>>
        assertEquals(listOf(Bytes(first.credentialId)), excluded.map { bytes(it["id"]) })
    }

    @Test
    fun `t15 c20 at most ten passkeys, and for the eleventh the answer is the one of the script, piena, with nothing saved`() {
        val email = person()
        val phone = phone()
        phone.enter(email)
        repeat(Passkeys.MAX_PER_ACCOUNT) { assertEquals(200, add(phone).response.status) }

        val refused = phone.postJson("/webauthn/register/options", csrf = phone.csrfOf("/profilo"))

        assertEquals(409, refused.response.status)
        assertEquals("piena", json(refused)["stato"])
        assertEquals(10, passkeyCount(email))
        val html = phone.body("/profilo")
        assertTrue(html.contains("Hai già 10 passkey, il massimo. Per aggiungerne un’altra, togline una."))
        assertFalse(html.contains("""data-azione="aggiungi-passkey""""), "no button, not a button that is off")
    }

    @Test
    fun `t15 c20 a registration of a person not verified, or from another origin, is refused with a 400 and nothing is saved`(output: CapturedOutput) {
        val email = person()
        val phone = phone()
        phone.enter(email)
        val csrf = phone.csrfOf("/profilo")

        for (bad in listOf<(SoftPasskey, Bytes, Bytes) -> org.springframework.security.web.webauthn.api.PublicKeyCredential<org.springframework.security.web.webauthn.api.AuthenticatorAttestationResponse>>(
            { s, c, u -> s.create(c, u, userVerified = false) },
            { s, c, u -> s.create(c, u, origin = "https://altro.example.org") },
        )) {
            val options = json(phone.postJson("/webauthn/register/options", csrf = csrf))
            @Suppress("UNCHECKED_CAST")
            val user = options["user"] as Map<String, Any?>
            val answer = bad(softPhone(), bytes(options["challenge"]), bytes(user["id"]))
            val result = phone.postJson("/webauthn/register", SoftPasskey.json.writeValueAsString(mapOf("publicKey" to RelyingPartyPublicKey(answer, ""))), csrf)

            assertEquals(400, result.response.status)
            assertEquals("rifiutata", json(result)["stato"])
        }
        assertEquals(0, passkeyCount(email))
        assertTrue(output.all.contains("Passkey: a registration was not accepted."))
    }

    @Test
    fun `t15 c20 the registration needs a person who is inside and the CSRF code`() {
        val anonymous = phone()
        assertEquals(302, anonymous.postJson("/webauthn/register/options", csrf = "x").response.status, "no access: the access page")

        val email = person()
        val phone = phone()
        phone.enter(email)
        val withoutCode = phone.postJson("/webauthn/register/options")
        assertEquals(303, withoutCode.response.status, "no CSRF code from a person who is inside: back to the home, nothing changes")
        assertEquals(0, passkeyCount(email))
    }

    // ---- What is not kept (criterion 21) ---------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c21 the row of a registration has no attestation, client data, label, model or last use, and the handle is not the id or the address`() {
        val email = person()
        val phone = phone()
        phone.enter(email)
        val soft = softPhone()
        add(phone, soft)

        val row = dsl.selectFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(accountId(email))).fetchSingle()
        val handle = dsl.select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.PASSKEY_HANDLE)!!

        assertEquals(64, handle.size)
        assertFalse(String(handle, Charsets.ISO_8859_1).contains(email))
        assertEquals(setOf("credential_id", "account_id", "public_key", "signature_count", "uv_initialized", "backup_eligible", "backup_state", "transports", "created_on"), dsl.meta().getTables("passkey").single().fields().map { it.name }.toSet())
        // The public key is not the private one: the private one never left the phone (it is not in the test either).
        assertEquals(0L, row.signatureCount)
        assertEquals(1, passkeys.count(accountId(email)))
    }

    // ---- Entering with a passkey (criteria 22, 23) ------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c22 a phone that finds its passkey enters without the address and without a mail, with a new session, and arrives at the home`() {
        val email = person()
        val here = phone()
        here.enter(email)
        val soft = softPhone()
        add(here, soft)
        val before = talita.mail.Mailbox.sentTo(email).size

        val there = phone()
        val answer = enter(there, soft)

        assertEquals(200, answer.response.status)
        assertEquals(true, json(answer)["authenticated"])
        assertEquals("/", json(answer)["redirectUrl"])
        assertEquals(before, talita.mail.Mailbox.sentTo(email).size, "no mail")
        assertEquals(200, there.get("/profilo").response.status)
        assertTrue(there.body("/profilo").contains(email))
        // The origin of the session is the one of a login: it ends as the others do, after 12 hours.
        assertNotNull(there.session?.getAttribute(SessionOrigins.KEY))
        assertNull((there.session?.getAttribute(SessionOrigins.KEY) as SessionOrigin).deviceId, "no device: the passkey is the way in")
        assertEquals(emptyList(), answer.response.cookies.filter { it.name == "ricordami" }, "no cookie of «Ricordami» after a passkey")
    }

    @Test
    fun `t15 c22 the session has a new id at the entry`() {
        val email = person()
        val here = phone()
        here.enter(email)
        val soft = softPhone()
        add(here, soft)
        val there = phone()
        there.get("/accedi")
        val before = checkNotNull(there.session).id

        enter(there, soft)

        assertNotEquals(before, checkNotNull(there.session).id)
    }

    @Test
    fun `t15 c22 the person arrives where the link would have led them, the page that they asked for before in the same browser, or Accetta l'invito`() {
        val email = person()
        val here = phone()
        here.enter(email)
        val soft = softPhone()
        add(here, soft)

        val there = phone()
        assertEquals("/accedi", there.get("/profilo").response.getHeader("Location"), "the page is kept for after the entry")
        assertEquals("/profilo", json(enter(there, soft))["redirectUrl"])

        invitations.invite(EmailAddress.parse(email)!!)
        val third = phone()
        third.get("/profilo")
        assertEquals("/invito", json(enter(third, soft))["redirectUrl"], "the invitation comes first, as after a link")
    }

    @Test
    fun `t15 c22 the access with a passkey needs the CSRF code and answers nothing without it`() {
        val phone = phone()

        val options = phone.postJson("/webauthn/authenticate/options", csrf = null)
        val login = phone.postJson("/login/webauthn", "{}", csrf = null)

        assertEquals(302, options.response.status, "a POST with no code goes back to the access page")
        assertEquals(302, login.response.status)
        assertEquals("/accedi", options.response.getHeader("Location"))
    }

    @Test
    fun `t15 c22 the two addresses of the access are public, and a person without access sees them answer`() {
        val phone = phone()
        val csrf = phone.csrfOf("/accedi")

        val options = phone.postJson("/webauthn/authenticate/options", csrf = csrf)

        assertEquals(200, options.response.status)
        assertEquals(MediaType.APPLICATION_JSON_VALUE, options.response.contentType?.substringBefore(';'))
        assertTrue(PublicPaths.matches("/login/webauthn") && PublicPaths.matches("/webauthn/authenticate/options"))
    }

    @Test
    fun `t15 c23 a passkey that was taken away, an account that is gone, a person not verified, another origin or another handle do not enter anybody, with a 401 and nothing created`() {
        val email = person()
        val here = phone()
        here.enter(email)
        val soft = softPhone()
        add(here, soft)
        val other = softPhone()
        val otherEmail = person("altro")
        val otherPhone = phone()
        otherPhone.enter(otherEmail)
        add(otherPhone, other)

        for (bad in listOf<(SoftPasskey, Bytes) -> org.springframework.security.web.webauthn.api.PublicKeyCredential<org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse>>(
            { s, c -> s.get(c, userVerified = false) },
            { s, c -> s.get(c, origin = "https://altro.example.org") },
            { s, c -> s.get(c, rp = "altro.example.org") },
            { s, c -> s.get(c, handle = Bytes(passkeys.handleOf(accountId(otherEmail)))) },
        )) {
            val answer = enter(phone(), soft, bad)
            assertEquals(401, answer.response.status)
        }
        // Taken away from the profile: the same phone, the same passkey, no entry.
        assertEquals(true, passkeys.deleteOwn(accountId(email), Passkeys.reference(soft.credentialId)))
        assertEquals(401, enter(phone(), soft).response.status)
        // An account that is gone: nothing is created in its place.
        dsl.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(accountId(otherEmail))).execute()
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(accountId(otherEmail))).execute()
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.EMAIL.eq(otherEmail)).execute()
        assertEquals(401, enter(phone(), other).response.status)
        assertEquals(0, dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.eq(otherEmail)), "the access with a passkey never creates an account")
    }

    // ---- What Spring's own removal would have done (criterion 26) ---------------------------------------------------------------------------

    @Test
    fun `t15 c26 the removal of Spring, DELETE on webauthn register, is closed for everybody`() {
        val email = person()
        val phone = phone()
        phone.enter(email)
        val soft = softPhone()
        add(phone, soft)
        val csrf = phone.csrfOf("/profilo")

        val result = mockMvc.delete("/webauthn/register/${Bytes(soft.credentialId).toBase64UrlString()}") {
            phone.session?.let { session = it }
            header("X-CSRF-TOKEN", csrf)
        }.andReturn()

        assertEquals(403, result.response.status)
        assertEquals(1, passkeyCount(email), "nothing changed")
    }

    @Test
    fun `t15 c26 Togli with the reference of a passkey of someone else, from a parent or from a teacher, changes nothing and answers as for a passkey that is gone`() {
        val owner = person("proprietario")
        val ownerPhone = phone()
        ownerPhone.enter(owner)
        val soft = softPhone()
        add(ownerPhone, soft)
        val reference = Passkeys.reference(soft.credentialId)

        val teacher = person("maestro")
        TestAccounts.makeTeacher(dsl, teacher)
        for (who in listOf(person("genitore"), teacher)) {
            val phone = phone()
            phone.enter(who)
            val answer = phone.submit("/profilo", "/profilo/passkey/togli", mapOf("passkey" to reference)) { header("HX-Request", "true") }

            assertEquals(200, answer.response.status, who)
            assertTrue(answer.response.contentAsString.contains("Questa passkey era già stata tolta."), who)
            assertEquals(1, passkeyCount(owner), "$who took nothing away")
        }
        val garbage = phone()
        garbage.enter(person())
        val none = garbage.submit("/profilo", "/profilo/passkey/togli", mapOf("passkey" to "non-una-passkey")) { header("HX-Request", "true") }
        assertTrue(none.response.contentAsString.contains("Questa passkey era già stata tolta."))
    }

    @Test
    fun `t15 c25 Togli takes away the passkey of the person, which stays in the phone but does not let anybody in, and a double tap is an information notice`() {
        val email = person()
        val phone = phone()
        phone.enter(email)
        val soft = softPhone()
        add(phone, soft)
        val reference = Passkeys.reference(soft.credentialId)

        val first = phone.submit("/profilo", "/profilo/passkey/togli", mapOf("passkey" to reference)) { header("HX-Request", "true") }
        val second = phone.submit("/profilo", "/profilo/passkey/togli", mapOf("passkey" to reference)) { header("HX-Request", "true") }

        assertEquals(200, first.response.status)
        assertTrue(first.response.contentAsString.contains("Passkey tolta.") && first.response.contentAsString.contains("Nel telefono resta, ma non fa più entrare."))
        assertEquals(0, passkeyCount(email))
        assertTrue(second.response.contentAsString.contains("Questa passkey era già stata tolta."))
        assertFalse(Regex("""<div (?!hidden)[^>]*role="alert"""").containsMatchIn(second.response.contentAsString), "not an error (the notices that only the script shows are hidden)")
        assertEquals(401, enter(phone(), soft).response.status)
    }

    @Test
    fun `t15 c25 without JavaScript Togli leads to a page with the words of the dialog, and goes back to the profile with the notice`() {
        val email = person()
        val phone = phone()
        phone.enter(email)
        val soft = softPhone()
        add(phone, soft)
        val reference = Passkeys.reference(soft.credentialId)

        val page = phone.submit("/profilo", "/profilo/passkey/togli/conferma", mapOf("passkey" to reference))
        assertEquals(200, page.response.status)
        val html = page.response.contentAsString
        assertTrue(Regex("""<h1[^>]*>Togliere la passkey aggiunta il \d{1,2} [a-z]+ \d{4}\?</h1>""").containsMatchIn(html), html)
        assertTrue(html.contains("Per entrare ti resta il link, e puoi aggiungere una passkey nuova quando vuoi."))
        assertEquals(1, passkeyCount(email), "nothing changed yet")

        val done = phone.submit("/profilo", "/profilo/passkey/togli", mapOf("passkey" to reference))

        assertEquals(303, done.response.status)
        assertEquals(0, passkeyCount(email))
        assertTrue(phone.body("/profilo").contains("Passkey tolta."))
        for (path in listOf("/profilo/passkey/togli", "/profilo/passkey/togli/conferma")) assertEquals(303, phone.get(path).response.status, path)
    }
}
