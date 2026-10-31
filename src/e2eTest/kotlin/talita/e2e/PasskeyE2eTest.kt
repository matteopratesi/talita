package talita.e2e

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.Cookie
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.options.RequestOptions
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import talita.Addresses
import talita.ClockConfiguration
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.account.Phone
import talita.account.TestAccounts
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.PASSKEY
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.jooq.tables.references.RETENTION_LOG
import talita.jooq.tables.references.TEACHER
import talita.mail.Mailbox
import talita.registration.Notes
import talita.retention.RetentionJob
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.function.Predicate
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The port of the application of the passkey tests: the browser only accepts a passkey for the name of the site it is on, so the site is `http://localhost:<this port>` and it never changes. */
private const val PASSKEY_E2E_PORT = 18417

/**
 * An application of its own for the tests of the passkeys: `talita.public-url` is `http://localhost:<port>`, so that the `rpId` is `localhost` and the origin is the one that Chromium is on (the other
 * e2e tests use the address of the tests, which a browser on `localhost` would refuse). It shares the database with the others, which run one after the other.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = ["server.port=$PASSKEY_E2E_PORT", "talita.public-url=http://localhost:$PASSKEY_E2E_PORT"],
)
@Import(TestcontainersConfiguration::class)
annotation class PasskeyE2eApplication

/**
 * The passkeys and the confirmation on a real browser, with the **virtual authenticator of Chromium** (the DevTools protocol, `WebAuthn.addVirtualAuthenticator`: a phone that is inside the browser and
 * answers with the fingerprint that the test says), on a phone and on a desktop (TALITA-15, criterion 36): add, enter, cancel and take away; the confirmation before the notes from the detail, from
 * the arrivals and from the correction of a paper form (it opens with the verification, it reads nothing without it or when it is cancelled, a second card inside the ten minutes asks for nothing).
 * Never a real phone and never a real passkey. axe on every state and no sideways scrolling at 320 px. Names and notes are synthetic.
 */
@PasskeyE2eApplication
@ExtendWith(OutputCaptureExtension::class)
class PasskeyE2eTest {

    /** A phone that is inside the browser: it holds the passkeys that the page makes, and [verified] says whether the person puts their finger (or their face) on it. */
    private class VirtualPhone(private val session: CDPSession, private val id: String) {

        fun verified(value: Boolean) {
            session.send("WebAuthn.setUserVerified", JsonObject().apply { addProperty("authenticatorId", id); addProperty("isUserVerified", value) })
        }

        /** With [automatic] false the phone never answers: the page stays in the line «In attesa della conferma…», which is what a person sees while they look for their finger. */
        fun answers(automatic: Boolean) {
            session.send("WebAuthn.setAutomaticPresenceSimulation", JsonObject().apply { addProperty("authenticatorId", id); addProperty("enabled", automatic) })
        }

        fun credentials(): Int = session.send("WebAuthn.getCredentials", JsonObject().apply { addProperty("authenticatorId", id) }).getAsJsonArray("credentials").size()
    }

    private fun E2e.phoneOf(on: Page = page): VirtualPhone {
        val session = browserContext.newCDPSession(on)
        session.send("WebAuthn.enable")
        val options = JsonObject().apply {
            addProperty("protocol", "ctap2")
            addProperty("transport", "internal")
            addProperty("hasResidentKey", true)
            addProperty("hasUserVerification", true)
            addProperty("isUserVerified", true)
            addProperty("automaticPresenceSimulation", true)
        }
        val id = session.send("WebAuthn.addVirtualAuthenticator", JsonObject().apply { add("options", options) }).get("authenticatorId").asString
        return VirtualPhone(session, id)
    }

    private fun Page.exact(name: String) = getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name).setExact(true))

    private fun E2e.passkeys(): Int = dsl().fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(accountId(checkNotNull(signedInAs))))

    private fun E2e.readings(): Int = dsl().fetchCount(HEALTH_NOTE_ACCESS)

    private fun BrowserContext.loseTheSession() = clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))

    /** Adds a passkey from the profile and waits for the notice. */
    private fun E2e.addPasskey(on: Page = page) {
        on.navigate(url("/profilo"))
        on.waitForSettledContent()
        press(on.locator("#passkey-contenuto").buttonNamed("Aggiungi una passkey su questo telefono"))
        assertThat(on.locator("#passkey-contenuto [role=status]").first()).containsText("Passkey aggiunta.")
    }

    // ---- Add, enter, cancel and take away (criteria 19-25) ------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t15 c36 c19 c20 c22 c25 a passkey is added from the profile, the person comes in with it from the access page without a mail, and Togli takes it away`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        e2e.open("/profilo")
        val card = page.locator("#passkey-contenuto")
        assertThat(card).containsText("Non hai ancora aggiunto nessuna passkey.")
        assertThat(card.buttonNamed("Aggiungi una passkey su questo telefono")).isVisible()
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "profile without passkeys (${e2e.profile.label})")

        e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono"))

        assertThat(card.locator("[role=status]").first()).containsText("Passkey aggiunta.")
        assertThat(card.locator("li")).hasCount(1)
        assertThat(card.locator("li").first()).containsText("Aggiunta il ")
        assertThat(card.locator("li").first()).containsText("Solo su un dispositivo")
        assertEquals(1, e2e.passkeys())
        assertAccessible(page, "profile with a passkey (${e2e.profile.label})")

        // Out, and back in with the passkey: no address and no mail, and the person is on the home.
        e2e.touch(page.exact("Esci"))
        assertThat(page).hasURL(e2e.url("/accedi"))
        val enter = page.exact("Entra con una passkey")
        assertThat(enter).isVisible()
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "access page with the passkey (${e2e.profile.label})")
        e2e.touch(enter)
        page.waitForURL(e2e.url("/"))
        e2e.open("/iscrizioni")
        assertThat(page.locator("h1")).hasText("Iscrizioni")

        // Togli: the dialog, the notice, and the passkey is gone for the server (the phone still has it, which does not let anybody in).
        e2e.open("/profilo")
        e2e.press(card.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Togli la passkey aggiunta il ")))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("Con questa passkey non potrai più entrare in Talità.")
        assertAccessible(page, "dialog of Togli for a passkey (${e2e.profile.label})")
        e2e.touch(dialog.buttonNamed("Togli la passkey"))
        assertThat(card.locator("[role=status]").first()).containsText("Passkey tolta.")
        assertEquals(0, e2e.passkeys())
        assertThat(card).containsText("Non hai ancora aggiunto nessuna passkey.")
        assertEquals(1, phone.credentials(), "the phone still has it")
        e2e.touch(page.exact("Esci"))
        assertThat(page).hasURL(e2e.url("/accedi"))
        e2e.touch(page.exact("Entra con una passkey"))
        assertThat(page.locator("[data-passkey-esito=non-valida]")).isVisible()
        assertThat(page).hasURL(e2e.url("/accedi"))
    }

    @E2eTest(teacher = false)
    fun `t15 c36 c24 a cancelled phone is not an error, nothing is saved and nobody comes in`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        e2e.open("/profilo")
        val card = page.locator("#passkey-contenuto")

        // Adding: the person does not put their finger on the phone.
        phone.verified(false)
        e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono"))
        assertThat(card.locator("[data-passkey-esito=annullata]")).isVisible()
        assertThat(card.locator("[role=alert]:visible")).hasCount(0)
        assertEquals(0, e2e.passkeys())
        assertNull(e2e.dsl().select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(e2e.accountId(checkNotNull(e2e.signedInAs)))).fetchSingle(ACCOUNT.PASSKEY_HANDLE), "D2: a cancelled first registration leaves no handle")
        assertAccessible(page, "profile after a cancelled addition (${e2e.profile.label})")
        phone.verified(true)
        e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono"))
        assertThat(card.locator("[role=status]").first()).containsText("Passkey aggiunta.")
        assertEquals(1, e2e.passkeys())

        // Entering: the same, and the page stays as it is with the way of the link.
        e2e.touch(page.exact("Esci"))
        assertThat(page).hasURL(e2e.url("/accedi"))
        phone.verified(false)
        e2e.touch(page.exact("Entra con una passkey"))
        assertThat(page.locator("[data-passkey-esito=annullata]")).isVisible()
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.exact("Ricevi il link")).isVisible()
        assertAccessible(page, "access page after a cancelled passkey (${e2e.profile.label})")
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/accedi"))
    }

    // ---- The confirmation before the notes (criteria 37-45) ---------------------------------------------------------------------------------------

    @E2eTest
    fun `t15 c36 c37 c38 c39 c40 c42 the detail opens the notes with the verification, nothing without it, and a second card inside the ten minutes asks for nothing`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val parent = e2e.someParent()
        val anna = e2e.rows().register(w.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, "Antistaminico in caso di reazione"))
        val luca = e2e.rows().register(w.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), notes = Notes("Latte", null, null))
        e2e.addPasskey()
        val email = checkNotNull(e2e.signedInAs)

        // The first passkey is not a confirmation: the first reading after it asks. The card says that the phone will ask.
        e2e.open("/iscrizioni/$anna")
        val card = page.locator("#segnalazioni")
        assertThat(card).containsText("Prima di mostrarle, il telefono ti chiede l’impronta, il volto o il codice di sblocco.")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "detail before the confirmation (${e2e.profile.label})")

        // Cancelled: the card stays closed, an informative notice says so, and no reading is written.
        phone.verified(false)
        e2e.press(card.buttonNamed("Mostra le segnalazioni"))
        assertThat(card.locator("[data-conferma-esito=segnalazioni-annullata]")).isVisible()
        assertThat(card.locator("[data-stato]")).isHidden()
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertEquals(0, e2e.readings())
        assertAccessible(page, "detail after a cancelled confirmation (${e2e.profile.label})")

        // Verified: the notes open, the reading is written once, and the card is the same one.
        phone.verified(true)
        e2e.press(card.buttonNamed("Mostra le segnalazioni"))
        assertThat(card).containsText("Arachidi")
        assertThat(card).containsText("Lettura registrata a tuo nome")
        assertEquals(1, e2e.readings())
        assertAccessible(page, "detail with the notes after the confirmation (${e2e.profile.label})")

        // Another card inside the ten minutes: a tap, no phone (it would answer «no», and the notes open all the same).
        phone.verified(false)
        e2e.open("/iscrizioni/$luca")
        assertThat(page.locator("#segnalazioni")).containsText(Pattern.compile("Hai già confermato con la passkey: fino alle \\d{2}:\\d{2} le apri senza confermare di nuovo\\."))
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Latte")
        assertEquals(2, e2e.readings())

        // The same for the card of the arrivals, which asks for nothing in the window.
        e2e.open("/arrivi/$anna")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        assertEquals(3, e2e.readings())

        // A new session (the link, not a confirmation): the window is not there, and with the phone that says «no» nothing opens.
        e2e.browserContext.loseTheSession()
        e2e.signIn(page, email, teacher = false)
        e2e.open("/arrivi/$luca")
        assertThat(page.locator("#segnalazioni")).containsText("Prima di mostrarle, il telefono ti chiede")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni [data-conferma-esito=segnalazioni-annullata]")).isVisible()
        assertThat(page.locator("main")).not().containsText("Latte")
        assertEquals(3, e2e.readings())
        phone.verified(true)
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Latte")
        assertEquals(4, e2e.readings())
        assertAccessible(page, "card of the arrivals with the notes (${e2e.profile.label})")
    }

    @E2eTest
    fun `t15 c36 c39 c43 the correction of a paper form says Serve la conferma with no fields, and after the verification the page opens with them`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val paper = e2e.rows().register(w.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = w.meId, notes = Notes("Latte", null, null), notesOnPaper = true)
        e2e.addPasskey()
        val email = checkNotNull(e2e.signedInAs)
        e2e.browserContext.loseTheSession()
        e2e.signIn(page, email, teacher = false)

        e2e.open("/iscrizioni/$paper/correggi-segnalazioni")

        assertThat(page.locator("h2").first()).hasText("Serve la conferma")
        assertThat(page.locator("main")).containsText("Prima di aprirla, il telefono ti chiede l’impronta, il volto o il codice di sblocco.")
        assertThat(page.getByLabel("Allergie")).hasCount(0)
        assertThat(page.locator("main")).not().containsText("Latte")
        assertEquals(0, e2e.readings())
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "correction that asks for the confirmation (${e2e.profile.label})")

        phone.verified(false)
        e2e.press(page.buttonNamed("Conferma con la passkey"))
        assertThat(page.locator("[data-conferma-esito=pagina-annullata]")).isVisible()
        assertThat(page.getByLabel("Allergie")).hasCount(0)
        assertEquals(0, e2e.readings())

        phone.verified(true)
        // The page reloads by itself as soon as the phone has verified the person, which on a phone can happen while Playwright is still finishing its tap: the click is sent to the button as it is.
        page.buttonNamed("Conferma con la passkey").dispatchEvent("click")
        assertThat(page.getByLabel("Allergie")).hasValue("Latte")
        assertEquals(1, e2e.readings())
        assertAccessible(page, "correction after the confirmation (${e2e.profile.label})")
    }

    @E2eTest
    fun `t15 c36 c45 c46 a teacher with a passkey confirms to add another and to take one away, and a teacher without one is invited to add one`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))

        // No passkey yet: the profile and the card of the arrivals have the discreet line, which does not block anything.
        e2e.open("/profilo")
        assertThat(page.locator("#passkey-contenuto")).containsText("Proteggi le segnalazioni con una passkey.")
        e2e.open("/arrivi/$anna")
        assertThat(page.locator("#scheda")).containsText("Proteggi le segnalazioni con una passkey.")
        assertAccessible(page, "card of the arrivals with the invitation (${e2e.profile.label})")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")

        // The first passkey is free. The second asks for the confirmation first: «no» from the phone and nothing is asked of it; «yes» and the phone says that it has the passkey of Talità already
        // (it is the only phone that the browser has), which is the answer of a phone to a passkey that it holds: the confirmation went through, and nothing is saved twice.
        e2e.addPasskey()
        assertEquals(1, e2e.passkeys())
        val card = page.locator("#passkey-contenuto")
        assertThat(card).containsText("Prima il telefono ti chiede di confermare con una passkey che hai già.")
        phone.verified(false)
        e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono"))
        assertThat(card.locator("[data-conferma-esito=aggiungi-annullata]")).isVisible()
        assertEquals(1, e2e.passkeys())
        phone.verified(true)
        e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono"))
        assertThat(card.locator("[data-passkey-esito=gia-presente]")).isVisible()
        assertEquals(1, e2e.passkeys())
        assertAccessible(page, "profile of a teacher after the confirmation to add (${e2e.profile.label})")

        // Togli asks for it too: with the window shut and «no» from the phone nothing is taken away, with «yes» the passkey goes and the line that invites to add one comes back.
        val email = checkNotNull(e2e.signedInAs)
        e2e.browserContext.loseTheSession()
        e2e.signIn(page, email, teacher = false)
        e2e.open("/profilo")
        phone.verified(false)
        e2e.press(card.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Togli la passkey aggiunta il ")).first())
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Per toglierla, il telefono ti chiede prima l’impronta, il volto o il codice di sblocco.")
        e2e.touch(page.openDialog().buttonNamed("Togli la passkey"))
        assertThat(card.locator("[data-conferma-esito=togli-annullata]")).isVisible()
        assertEquals(1, e2e.passkeys())
        phone.verified(true)
        e2e.press(card.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Togli la passkey aggiunta il ")).first())
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Togli la passkey"))
        assertThat(card.locator("[role=status]").first()).containsText("Passkey tolta.")
        assertEquals(0, e2e.passkeys())
        assertThat(card).containsText("Proteggi le segnalazioni con una passkey.")
        assertFalse(page.content().contains("Arachidi"), "no note anywhere in the profile")
    }

    // ---- QA of TALITA-15: what the other tests leave to the unit tests, seen on a real browser (the options of WebAuthn, the clock, the people who must not see each other, the log) ----------------------------

    private fun E2e.clock(): MutableClock = application.getBean(MutableClock::class.java)

    /** A POST of a form of the page [on] as htmx sends it, with the CSRF code of the page: what a person who writes the request by hand sends. */
    private fun E2e.post(on: Page, path: String, form: Map<String, String> = emptyMap()): APIResponse {
        val data = FormData.create().set("_csrf", checkNotNull(on.locator("input[name=_csrf]").first().getAttribute("value")))
        form.forEach { (name, value) -> data.set(name, value) }
        return on.request().post(url(path), RequestOptions.create().setForm(data).setHeader("HX-Request", "true").setMaxRedirects(0))
    }

    /** A `fetch` from inside the page, with the headers that `passkeys.js` sends (JSON and the CSRF code of the page): the status and the text of the answer. */
    private fun Page.fetchJson(method: String, path: String, body: String? = null): Pair<Int, String> {
        @Suppress("UNCHECKED_CAST")
        val answer = evaluate(
            """async ([method, path, body]) => {
                const field = document.querySelector('input[name="_csrf"]');
                const headers = { Accept: 'application/json', 'X-CSRF-TOKEN': field ? field.value : '' };
                if (body) headers['Content-Type'] = 'application/json';
                const response = await fetch(path, { method, headers, credentials: 'same-origin', body: body || undefined });
                return { status: response.status, text: await response.text() };
            }""",
            listOf(method, path, body),
        ) as Map<String, Any?>
        return (answer["status"] as Number).toInt() to answer["text"] as String
    }

    /** A wrong signature in the answer of the phone to the confirmation: what a refused verification looks like to the server. */
    private fun Page.tamperAssertion() {
        route("**/verifica-passkey") { route ->
            val body = checkNotNull(route.request().postData())
            val changed = Regex("\"signature\":\"(.)").replace(body) { match -> "\"signature\":\"" + (if (match.groupValues[1] == "A") "B" else "A") }
            route.resume(Route.ResumeOptions().setPostData(changed))
        }
    }

    private fun E2e.passkeysOf(email: String): List<ByteArray> =
        dsl().select(PASSKEY.CREDENTIAL_ID).from(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(accountId(email))).fetch(PASSKEY.CREDENTIAL_ID).map { it!! }

    private fun reference(credentialId: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(credentialId)

    /** The person of the test is a teacher with a passkey in the virtual phone, in a new session that has not confirmed anything: the link, not the passkey, has just made them come in. */
    private fun E2e.teacherWithPasskeyAndNoConfirmation(on: Page = page): String {
        addPasskey(on)
        val email = checkNotNull(signedInAs)
        on.context().clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))
        signIn(on, email, teacher = false)
        return email
    }

    @E2eTest(teacher = false, profiles = [Profile.DESKTOP])
    fun `t15 c36 c20 c21 the options of a registration, what is kept, the second passkey and the most ten`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        e2e.open("/profilo")
        val card = page.locator("#passkey-contenuto")
        val email = checkNotNull(e2e.signedInAs)

        val first = page.waitForResponse(Predicate<Response> { it.url().endsWith("/webauthn/register/options") }, Runnable { e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono")) })
        val options = JsonParser.parseString(first.text()).asJsonObject
        assertEquals("none", options["attestation"].asString)
        val selection = options["authenticatorSelection"].asJsonObject
        assertEquals("required", selection["userVerification"].asString, "the person is always verified")
        assertEquals("required", selection["residentKey"].asString, "a passkey that the phone finds by itself")
        assertEquals("localhost", options["rp"].asJsonObject["id"].asString)
        assertEquals("Talità", options["rp"].asJsonObject["name"].asString)
        val user = options["user"].asJsonObject
        assertEquals("Talità", user["name"].asString, "the phone shows the name of the app")
        assertEquals("Talità", user["displayName"].asString)
        assertEquals(64, Base64.getUrlDecoder().decode(user["id"].asString).size, "the identifier of the account in the passkey is a random handle")
        assertFalse(first.text().contains(email), "never the address")
        assertEquals(0, options["excludeCredentials"].asJsonArray.size())
        assertThat(card.locator("[role=status]").first()).containsText("Passkey aggiunta.")

        // What was kept: the key, the counter, the flags, the transports and the day. The table has no column for the attestation, the label, the model or the last use.
        val row = e2e.dsl().selectFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(e2e.accountId(email))).fetchSingle()
        assertTrue(row.uvInitialized == true, "the person was verified when the passkey was made")
        assertEquals(LocalDate.now(e2e.clock()), row.createdOn)
        val columns = e2e.dsl().fetch("select column_name from information_schema.columns where table_name = 'passkey'").map { it.get(0, String::class.java) }.toSet()
        assertEquals(setOf("credential_id", "account_id", "public_key", "signature_count", "uv_initialized", "backup_eligible", "backup_state", "transports", "created_on"), columns)
        val handle = e2e.dsl().select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(e2e.accountId(email))).fetchSingle(ACCOUNT.PASSKEY_HANDLE)!!
        assertEquals(64, handle.size)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(handle), user["id"].asString, "the handle of the options is the one of the account")

        // The same phone is asked for a second passkey of the same account: the first one is left out of the options, and the phone says that it has it.
        val second = page.waitForResponse(Predicate<Response> { it.url().endsWith("/webauthn/register/options") }, Runnable { e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono")) })
        val excluded = JsonParser.parseString(second.text()).asJsonObject["excludeCredentials"].asJsonArray
        assertEquals(1, excluded.size())
        assertEquals(reference(row.credentialId!!), excluded[0].asJsonObject["id"].asString)
        assertThat(card.locator("[data-passkey-esito=gia-presente]")).isVisible()
        assertEquals(1, e2e.passkeysOf(email).size)
        assertEquals(1, phone.credentials())

        // At most ten: with ten in the table the button is gone, and a request made by hand is the answer «piena».
        repeat(9) { i ->
            e2e.dsl().insertInto(PASSKEY).set(PASSKEY.CREDENTIAL_ID, ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }).set(PASSKEY.ACCOUNT_ID, row.accountId).set(PASSKEY.PUBLIC_KEY, row.publicKey)
                .set(PASSKEY.SIGNATURE_COUNT, 0L).set(PASSKEY.UV_INITIALIZED, true).set(PASSKEY.BACKUP_ELIGIBLE, false).set(PASSKEY.BACKUP_STATE, false).set(PASSKEY.CREATED_ON, LocalDate.now(e2e.clock()).minusDays(i + 1L)).execute()
        }
        e2e.open("/profilo")
        assertThat(card.buttonNamed("Aggiungi una passkey su questo telefono")).hasCount(0)
        assertThat(card).containsText("Hai già 10 passkey, il massimo.")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "profile with the most passkeys")
        val (status, text) = page.fetchJson("POST", "/webauthn/register/options")
        assertEquals(409, status)
        assertEquals("""{"stato":"piena"}""", text)
        assertEquals(10, e2e.passkeysOf(email).size)
    }

    @E2eTest
    fun `t15 c36 c22 c40 the passkey leads where the link would, with a new session and no mail, and counts as the confirmation`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        e2e.addPasskey()
        val email = checkNotNull(e2e.signedInAs)
        val mails = Mailbox.sentTo(email).size
        e2e.touch(page.exact("Esci"))
        assertThat(page).hasURL(e2e.url("/accedi"))

        // The page that was asked for in this browser is where the passkey leads, as the link would have done.
        page.navigate(e2e.url("/iscrizioni/$anna"))
        page.waitForSettledContent()
        assertThat(page).hasURL(e2e.url("/accedi"))
        val before = page.context().cookies().firstOrNull { it.name == "JSESSIONID" }?.value
        e2e.touch(page.exact("Entra con una passkey"))
        page.waitForURL(e2e.url("/iscrizioni/$anna"))
        assertNotEquals(before, page.context().cookies().firstOrNull { it.name == "JSESSIONID" }?.value, "a new id of the session")
        assertEquals(null, page.context().cookies().firstOrNull { it.name == "ricordami" }, "the passkey is the way in: no cookie of the box")
        assertEquals(mails, Mailbox.sentTo(email).size, "no mail for the passkey")

        // The phone has just verified the person: the card opens at a tap, and says until when.
        val card = page.locator("#segnalazioni")
        assertThat(card).containsText(Pattern.compile("Hai già confermato con la passkey: fino alle \\d{2}:\\d{2} le apri senza confermare di nuovo\\."))
        phone.verified(false)
        e2e.press(card.buttonNamed("Mostra le segnalazioni"))
        assertThat(card).containsText("Arachidi")

        // The link is not a confirmation.
        e2e.browserContext.loseTheSession()
        e2e.signIn(page, email, teacher = false)
        e2e.open("/iscrizioni/$anna")
        assertThat(page.locator("#segnalazioni")).containsText("Prima di mostrarle, il telefono ti chiede")
        assertThat(page.locator("#segnalazioni")).not().containsText("Hai già confermato")
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t15 c36 c40 the confirmation holds ten minutes from the moment and the use does not lengthen it`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val parent = e2e.someParent()
        val anna = e2e.rows().register(w.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        val luca = e2e.rows().register(w.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), notes = Notes("Latte", null, null))
        val marco = e2e.rows().register(w.id, parent, "Marco", "Rossi", LocalDate.of(2014, 5, 5), notes = Notes("Polline", null, null))
        e2e.teacherWithPasskeyAndNoConfirmation()

        // Confirmed now: the card of the next child says until when, which is ten minutes from now (a time of Rome).
        e2e.open("/iscrizioni/$anna")
        val sessionBefore = page.context().cookies().first { it.name == "JSESSIONID" }.value
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        assertNotEquals(sessionBefore, page.context().cookies().first { it.name == "JSESSIONID" }.value, "the id of the session changes with the confirmation")
        val clock = e2e.clock()
        val expected = DateTimeFormatter.ofPattern("HH:mm").withZone(ClockConfiguration.ZONE)
        e2e.open("/iscrizioni/$luca")
        val sentence = Pattern.compile("Hai già confermato con la passkey: fino alle (\\d{2}:\\d{2}) le apri senza confermare di nuovo\\.")
        val until = sentence.matcher(page.locator("#segnalazioni").innerText()).let { check(it.find()) { "the card does not say until when" }; it.group(1) }
        val tenMinutes = LocalTime.parse(expected.format(clock.instant().plus(Duration.ofMinutes(10))))
        val apart = Math.floorMod(Duration.between(LocalTime.parse(until), tenMinutes).toMinutes(), 24 * 60L)
        assertTrue(apart <= 1 || apart >= 24 * 60 - 1, "until $until is not ten minutes from now ($tenMinutes)")

        // A second tab of the same browser is inside the window as well: it asks for nothing.
        val secondTab = page.context().newPage()
        secondTab.navigate(e2e.url("/iscrizioni/$luca"))
        secondTab.waitForSettledContent()
        assertThat(secondTab.locator("#segnalazioni")).containsText("fino alle $until")
        secondTab.close()

        // Five minutes later, and eight and a half: the same time, and the card opens with no phone (it would say «no»).
        phone.verified(false)
        clock.advance(Duration.ofMinutes(5))
        e2e.open("/iscrizioni/$luca")
        assertThat(page.locator("#segnalazioni")).containsText("fino alle $until")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Latte")
        clock.advance(Duration.ofMinutes(3).plusSeconds(30))
        e2e.open("/arrivi/$marco")
        assertThat(page.locator("#scheda")).containsText("fino alle $until")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Polline")
        assertEquals(3, e2e.readings())

        // After the ten minutes the card says that the phone will ask, and with a phone that says «no» nothing opens and nothing is written.
        clock.advance(Duration.ofMinutes(2))
        e2e.open("/iscrizioni/$luca")
        assertThat(page.locator("#segnalazioni")).containsText("Prima di mostrarle, il telefono ti chiede")
        assertThat(page.locator("#segnalazioni")).not().containsText("Hai già confermato")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni [data-conferma-esito=segnalazioni-annullata]")).isVisible()
        assertEquals(3, e2e.readings())
        phone.verified(true)
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Latte")
        assertEquals(4, e2e.readings())
    }

    @E2eTest
    fun `t15 c36 c42 a refused verification and no network open nothing, and a confirmation that arrived stays valid`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, "Antistaminico in caso di reazione"))
        e2e.teacherWithPasskeyAndNoConfirmation()
        e2e.open("/iscrizioni/$anna")
        val card = page.locator("#segnalazioni")

        // A wrong signature: 403, the card says that the confirmation did not work, with the way out, and nothing is opened or written.
        page.tamperAssertion()
        e2e.press(card.buttonNamed("Mostra le segnalazioni"))
        assertThat(card.locator("[data-conferma-esito=segnalazioni-rifiutata]")).isVisible()
        assertThat(card.locator("[data-conferma-esito=segnalazioni-rifiutata]")).containsText("La conferma non è riuscita.")
        assertThat(card.locator("[data-conferma-esito=segnalazioni-rifiutata]")).containsText("le segnalazioni le può aprire un altro maestro")
        assertThat(card.locator("[data-stato]")).isHidden()
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertEquals(0, e2e.readings())
        assertThat(card.buttonNamed("Mostra le segnalazioni")).isEnabled()
        page.focusIs("document.activeElement.matches('[data-conferma-esito=segnalazioni-rifiutata]')")
        assertAccessible(page, "card after a refused confirmation (${e2e.profile.label})")
        page.unroute("**/verifica-passkey")

        // A challenge that is older than five minutes by the time the answer arrives: the same.
        page.route("**/verifica-passkey") { route ->
            e2e.clock().advance(Duration.ofMinutes(6))
            route.resume()
        }
        e2e.press(card.buttonNamed("Mostra le segnalazioni"))
        assertThat(card.locator("[data-conferma-esito=segnalazioni-rifiutata]")).isVisible()
        assertEquals(0, e2e.readings())
        page.unroute("**/verifica-passkey")

        // The refusal says nothing of why, to anybody who asks by hand: an answer that is not one, and a challenge that was never given.
        val (status, text) = page.fetchJson("POST", "/verifica-passkey", """{"id":"x","rawId":"x","type":"public-key","response":{}}""")
        assertEquals(403, status)
        assertEquals("""{"stato":"rifiutata"}""", text)

        // No network: the message of the guide, nothing opens, nothing is written, and the button works again when the network is back.
        e2e.browserContext.setOffline(true)
        e2e.press(card.buttonNamed("Mostra le segnalazioni"))
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(0, page.messages().locator("button:has-text('Riprova')").count(), "a touch is not repeated for the person")
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertEquals(0, e2e.readings())
        assertAccessible(page, "card of the notes without a network (${e2e.profile.label})")
        e2e.browserContext.setOffline(false)

        // The verification arrives and the request that follows is lost: nothing was read, and the confirmation still holds, so the next tap opens with no phone (it would say «no»).
        page.route("**/mostra-segnalazioni") { it.abort() }
        val lost = java.util.concurrent.atomic.AtomicBoolean(false)
        page.onRequestFailed { lost.set(true) }
        e2e.press(card.buttonNamed("Mostra le segnalazioni"))
        page.waitForCondition { lost.get() }
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(0, e2e.readings())
        page.unroute("**/mostra-segnalazioni")
        phone.verified(false)
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        assertEquals(1, e2e.readings())
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t15 c36 c39 c43 c45 without JavaScript or by hand nothing is read, written or changed without the confirmation`(e2e: E2e) {
        val page = e2e.page
        e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, "Antistaminico in caso di reazione"))
        val paper = e2e.rows().register(w.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = w.meId, notes = Notes("Latte", null, null), notesOnPaper = true)
        val email = e2e.teacherWithPasskeyAndNoConfirmation()

        // Without JavaScript there is nobody to ask the phone: the pages say that the confirmation wants it and that another teacher can open the notes, and nothing is read.
        val plain = e2e.newPage(signedIn = false) { setJavaScriptEnabled(false) }
        e2e.signIn(plain, email, teacher = false)
        plain.navigate(e2e.url("/iscrizioni/$anna"))
        plain.waitForSettledContent()
        e2e.touch(plain.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        plain.waitForLoadState()
        plain.waitForSettledContent()
        assertThat(plain.locator("#segnalazioni")).containsText("Serve la conferma.")
        assertThat(plain.locator("#segnalazioni")).containsText("la conferma vuole JavaScript")
        assertThat(plain.locator("#segnalazioni")).containsText("le può aprire un altro maestro")
        assertThat(plain.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni")).hasCount(0)
        assertThat(plain.locator("main")).not().containsText("Arachidi")
        plain.navigate(e2e.url("/arrivi/$anna"))
        plain.waitForSettledContent()
        e2e.touch(plain.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        plain.waitForLoadState()
        plain.waitForSettledContent()
        assertThat(plain.locator("#segnalazioni")).containsText("la conferma vuole JavaScript")
        assertThat(plain.locator("main")).not().containsText("Arachidi")
        plain.navigate(e2e.url("/iscrizioni/$paper/correggi-segnalazioni"))
        plain.waitForSettledContent()
        assertThat(plain.locator("main")).containsText("Serve la conferma")
        assertThat(plain.locator("main")).containsText("la conferma vuole JavaScript")
        assertThat(plain.getByLabel("Allergie")).hasCount(0)
        assertThat(plain.locator("main")).not().containsText("Latte")
        plain.navigate(e2e.url("/profilo"))
        plain.waitForSettledContent()
        e2e.touch(plain.locator("#passkey-contenuto").getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Togli la passkey aggiunta il ")).first())
        plain.waitForLoadState()
        plain.waitForSettledContent()
        e2e.touch(plain.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Togli la passkey").setExact(true)))
        plain.waitForLoadState()
        plain.waitForSettledContent()
        assertThat(plain.locator("#passkey-contenuto")).containsText("Serve la conferma.")
        assertThat(plain.locator("#passkey-contenuto")).containsText("la conferma vuole JavaScript")
        assertEquals(1, e2e.passkeysOf(email).size, "the passkey is still there")
        assertEquals(0, e2e.readings())

        // By hand, from a session that has not confirmed: the same refusals, with the status of a conflict, and nothing changes.
        e2e.open("/profilo")
        val reference = reference(e2e.passkeysOf(email).single())
        val detail = e2e.post(page, "/iscrizioni/$anna/mostra-segnalazioni")
        assertEquals(409, detail.status())
        assertTrue(detail.text().contains("Serve la conferma."))
        assertFalse(detail.text().contains("Arachidi"))
        val arrivals = e2e.post(page, "/arrivi/$anna/segnalazioni")
        assertEquals(409, arrivals.status())
        assertTrue(arrivals.text().contains("Serve la conferma."))
        assertFalse(arrivals.text().contains("Arachidi"))
        val correction = page.request().get(e2e.url("/iscrizioni/$paper/correggi-segnalazioni"))
        assertFalse(correction.text().contains("Latte"))
        assertEquals(0, e2e.readings())
        val removal = e2e.post(page, "/profilo/passkey/togli", mapOf("passkey" to reference))
        assertEquals(409, removal.status())
        assertTrue(removal.text().contains("Serve la conferma."))
        assertEquals(1, e2e.passkeysOf(email).size)
        val (addStatus, addText) = page.fetchJson("POST", "/webauthn/register/options")
        assertEquals(409, addStatus)
        assertEquals("""{"stato":"serve-conferma"}""", addText)
        // The removal of Spring is closed for everybody.
        assertEquals(403, page.fetchJson("DELETE", "/webauthn/register/$reference").first)
        assertEquals(1, e2e.passkeysOf(email).size)
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t15 c36 c44 the server decides at the tap whatever the page said when it was drawn`(e2e: E2e) {
        val page = e2e.page
        e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val parent = e2e.someParent()
        val anna = e2e.rows().register(w.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        val luca = e2e.rows().register(w.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), notes = Notes("Latte", null, null))
        val email = e2e.teacherWithPasskeyAndNoConfirmation()
        val mine = e2e.accountId(email)

        // The page was drawn when the teacher had a passkey (the button holds its request for the phone), then the passkey goes: the tap finds that nothing is needed.
        e2e.open("/iscrizioni/$anna")
        assertThat(page.locator("#segnalazioni")).containsText("Prima di mostrarle, il telefono ti chiede")
        val removed = e2e.dsl().selectFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(mine)).fetchSingle()
        e2e.dsl().deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(mine)).execute()
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        assertEquals(1, e2e.readings())

        // The other way: the page was drawn with no passkey (the line that invites to add one), a passkey is added, and the tap says Serve la conferma, with the same button, and no reading.
        e2e.open("/iscrizioni/$luca")
        assertThat(page.locator("#segnalazioni")).not().containsText("Prima di mostrarle, il telefono ti chiede")
        e2e.dsl().insertInto(PASSKEY).set(PASSKEY.CREDENTIAL_ID, removed.credentialId).set(PASSKEY.ACCOUNT_ID, mine).set(PASSKEY.PUBLIC_KEY, removed.publicKey).set(PASSKEY.SIGNATURE_COUNT, 0L)
            .set(PASSKEY.UV_INITIALIZED, true).set(PASSKEY.BACKUP_ELIGIBLE, false).set(PASSKEY.BACKUP_STATE, false).set(PASSKEY.CREATED_ON, LocalDate.now(e2e.clock())).execute()
        // This session confirmed nothing, and the ten minutes of the first reading never existed (there was no confirmation): the server asks.
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Serve la conferma.")
        assertThat(page.locator("#segnalazioni")).not().containsText("Latte")
        assertEquals(1, e2e.readings())
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t15 c36 c17 c26 c38 nobody takes away or confirms with the passkey of another`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        val owner = e2e.teacherWithPasskeyAndNoConfirmation()
        val ownerReference = reference(e2e.passkeysOf(owner).single())

        // A parent and another teacher, each in their own browser, send the reference of that passkey: it is answered as one that does not exist, and the passkey is where it was.
        for (teacher in listOf(false, true)) {
            val intruder = e2e.newPage(signedIn = true, teacher = teacher)
            intruder.navigate(e2e.url("/profilo"))
            intruder.waitForSettledContent()
            assertThat(intruder.locator("#passkey-contenuto")).containsText("Non hai ancora aggiunto nessuna passkey.")
            val reply = e2e.post(intruder, "/profilo/passkey/togli", mapOf("passkey" to ownerReference))
            assertEquals(200, reply.status())
            assertTrue(reply.text().contains("Questa passkey era già stata tolta."), "answered as a passkey that does not exist")
            assertFalse(reply.text().contains("Passkey tolta."))
            assertEquals(1, e2e.passkeysOf(owner).size)
            assertEquals(403, intruder.fetchJson("DELETE", "/webauthn/register/$ownerReference").first, "the removal of Spring is closed")
            assertEquals(1, e2e.passkeysOf(owner).size)

            // The confirmation: the intruder has nothing to confirm (no passkey), and an answer by hand is refused and opens nothing.
            val (optionsStatus, optionsText) = intruder.fetchJson("POST", "/verifica-passkey/opzioni")
            assertEquals(200, optionsStatus)
            assertEquals("""{"serve":false}""", optionsText)
            assertEquals(403, intruder.fetchJson("POST", "/verifica-passkey", """{"id":"x","rawId":"x","type":"public-key","response":{}}""").first)
        }

        // Without being inside the two addresses of the confirmation are not there: no answer, and the access page.
        val anonymous = e2e.newPage(signedIn = false)
        anonymous.navigate(e2e.url("/accedi"))
        for (path in listOf("/verifica-passkey/opzioni", "/verifica-passkey")) {
            val reply = anonymous.request().post(e2e.url(path), RequestOptions.create().setMaxRedirects(0))
            assertTrue(reply.status() in 300..399 || reply.status() == 401 || reply.status() == 403, "$path answered ${reply.status()} to somebody who is not inside")
            assertFalse(reply.text().contains("serve"), "nothing of the confirmation for somebody who is not inside")
        }

        // The assertion of the passkey of another account, with a challenge that was given to this one, is refused: another teacher, with a passkey of their own on the same phone, tries to
        // confirm with the first one.
        e2e.open("/profilo")
        e2e.touch(page.exact("Esci"))
        val other = e2e.signIn(page, teacher = true)
        e2e.addPasskey()
        page.context().clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))
        e2e.signIn(page, other, teacher = false)
        e2e.open("/iscrizioni/$anna")
        assertThat(page.locator("#segnalazioni")).containsText("Prima di mostrarle, il telefono ti chiede")
        val status = page.evaluate(
            """async (borrowed) => {
                const toBuffer = (text) => { let b = text.replace(/-/g, '+').replace(/_/g, '/'); while (b.length % 4) b += '='; return Uint8Array.from(atob(b), (c) => c.charCodeAt(0)).buffer; };
                const toText = (buffer) => btoa(String.fromCharCode(...new Uint8Array(buffer))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
                const csrf = document.querySelector('input[name="_csrf"]').value;
                const headers = { Accept: 'application/json', 'X-CSRF-TOKEN': csrf };
                const given = await (await fetch('/verifica-passkey/opzioni', { method: 'POST', headers })).json();
                const credential = await navigator.credentials.get({ publicKey: { challenge: toBuffer(given.options.challenge), rpId: given.options.rpId, userVerification: 'required', allowCredentials: [{ type: 'public-key', id: toBuffer(borrowed) }] } });
                const r = credential.response;
                const body = { id: credential.id, rawId: toText(credential.rawId), type: credential.type, clientExtensionResults: {}, response: { authenticatorData: toText(r.authenticatorData), clientDataJSON: toText(r.clientDataJSON), signature: toText(r.signature), userHandle: r.userHandle ? toText(r.userHandle) : null } };
                const answer = await fetch('/verifica-passkey', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
                return answer.status;
            }""",
            ownerReference,
        ) as Number
        assertEquals(403, status.toInt(), "the passkey of another account does not confirm")
        phone.verified(false)
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni [data-conferma-esito=segnalazioni-annullata]")).isVisible()
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertEquals(0, e2e.readings())
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t15 c36 c47 a parent never confirms, and a teacher who is a parent reads their own child with no confirmation`(e2e: E2e) {
        val page = e2e.page
        e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val someone = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        val email = checkNotNull(e2e.signedInAs)
        val own = e2e.rows().register(w.id, e2e.accountId(email), "Elena", "Serra", LocalDate.of(2017, 3, 3), notes = Notes("Latte", null, "Ventolin"))
        e2e.teacherWithPasskeyAndNoConfirmation()

        // The pages of the parent: no confirmation (nobody asks for it) and no row in the register.
        e2e.open("/iscrizioni/$own/segnalazioni")
        assertThat(page.getByLabel("Allergie")).hasValue("Latte")
        assertThat(page.locator("main")).not().containsText("Serve la conferma")
        assertEquals(0, e2e.readings())
        // The page of the teachers, for the same child, is the one that asks.
        e2e.open("/iscrizioni/$own")
        assertThat(page.locator("#segnalazioni")).containsText("Prima di mostrarle, il telefono ti chiede")
        // And the pages of the parent give nothing of the child of somebody else, not even to a teacher.
        val other = page.request().get(e2e.url("/iscrizioni/$someone/segnalazioni"))
        assertEquals(404, other.status())
        assertFalse(other.text().contains("Arachidi"))
        assertEquals(0, e2e.readings())

        // A parent with a passkey: nothing to confirm, ever, and the notes of their own child open at once.
        val parent = e2e.newPage(signedIn = false)
        val parentEmail = e2e.signIn(parent, teacher = false)
        e2e.phoneOf(parent)
        e2e.addPasskey(parent)
        parent.context().clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))
        e2e.signIn(parent, parentEmail, teacher = false)
        val child = e2e.rows().register(w.id, e2e.accountId(parentEmail), "Giulia", "Blu", LocalDate.of(2018, 2, 2), notes = Notes("Polline", null, null))
        parent.navigate(e2e.url("/iscrizioni/$child/segnalazioni"))
        parent.waitForSettledContent()
        assertThat(parent.getByLabel("Allergie")).hasValue("Polline")
        assertEquals("""{"serve":false}""", parent.fetchJson("POST", "/verifica-passkey/opzioni").second)
        // The pages of the teachers are closed to a parent (404 or the access page), with or without a passkey.
        val teacherPage = parent.request().get(e2e.url("/iscrizioni/$child"))
        assertFalse(teacherPage.text().contains("Polline"))
        assertEquals(0, e2e.readings())
    }

    @E2eTest(signedIn = false, profiles = [Profile.DESKTOP])
    fun `t15 c36 c6 c7 the box lasts 7 days for a teacher and 30 for a parent, a session ends after 12 hours`(e2e: E2e) {
        val page = e2e.page
        val clock = e2e.clock()
        val today = LocalDate.now(clock)
        val teacherEmail = Addresses.unique("maestro")
        TestAccounts.makeTeacher(e2e.dsl(), teacherEmail, "Paolo", "Verdi")
        fun enter(on: Page, email: String) {
            on.navigate(e2e.url("/accedi/conferma?token=" + e2e.application.getBean(OneTimeTokenService::class.java).generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue))
            on.waitForSettledContent()
            on.getByLabel("Ricordami su questo telefono").check()
            e2e.touch(on.exact("Entra"))
            on.waitForURL(e2e.url("/"))
        }
        enter(page, teacherEmail)
        val parentPage = e2e.newPage(signedIn = false)
        val parentEmail = Addresses.unique("persona")
        enter(parentPage, parentEmail)

        // The profile says the last day, with the length of the role: a teacher 6 days after today, a parent 29.
        e2e.open("/profilo")
        assertThat(page.locator("#dispositivi-contenuto")).containsText("Rientri senza link fino al ${talita.gathering.GatheringFormat.date(today.plusDays(6))}.")
        assertThat(page.locator("#dispositivi-contenuto")).containsText("per 7 giorni dal giorno della scelta")
        parentPage.navigate(e2e.url("/profilo"))
        assertThat(parentPage.locator("#dispositivi-contenuto")).containsText("Rientri senza link fino al ${talita.gathering.GatheringFormat.date(today.plusDays(29))}.")

        // 12 hours: a session that is 11 hours and 50 minutes old goes on with the same id; at 12 hours and a minute the next request closes it, and the cookie brings the person back in with a new id, in the same request.
        val sessionBefore = checkNotNull(page.context().cookies().firstOrNull { it.name == "JSESSIONID" }).value
        clock.advance(Duration.ofHours(11).plusMinutes(50))
        e2e.open("/profilo")
        assertEquals(sessionBefore, page.context().cookies().first { it.name == "JSESSIONID" }.value, "11 hours and 50 minutes: the same session")
        clock.advance(Duration.ofMinutes(11))
        e2e.open("/profilo")
        assertThat(page).hasURL(e2e.url("/profilo"))
        assertNotEquals(sessionBefore, page.context().cookies().first { it.name == "JSESSIONID" }.value, "12 hours and a minute: a new session")
        // A person without the cookie: the access page, and the notice of the 12 hours. (A third browser, which entered without the box, 12 hours ago.)
        val without = e2e.newPage(signedIn = false)
        e2e.signIn(without, Addresses.unique("persona"), teacher = false)
        clock.advance(Duration.ofHours(12).plusMinutes(1))
        without.navigate(e2e.url("/iscrizioni"))
        without.waitForSettledContent()
        assertThat(without).hasURL(e2e.url("/accedi"))
        assertThat(without.locator("main [role=status][data-fuoco]")).containsText("comunque dopo 12 ore dall’entrata")

        // The days: the rows are aged in the table (the clock of the tests only goes forward and is not moved by days). A teacher who touched the box 6 days ago is inside on this last day, and out
        // when it was 7; a parent is inside at 29 days and out at 30.
        fun age(email: String, days: Long) {
            e2e.dsl().update(REMEMBERED_DEVICE).set(REMEMBERED_DEVICE.CREATED_ON, LocalDate.now(clock).minusDays(days)).where(REMEMBERED_DEVICE.ACCOUNT_ID.eq(e2e.accountId(email))).execute()
        }
        fun outsideThe(on: Page) {
            on.context().clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))
            on.navigate(e2e.url("/iscrizioni"))
            on.waitForSettledContent()
        }
        age(teacherEmail, 6)
        outsideThe(page)
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        age(teacherEmail, 7)
        outsideThe(page)
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.locator("main [role=status][data-fuoco]")).containsText("L’accesso su questo telefono è stato chiuso.")
        age(parentEmail, 29)
        outsideThe(parentPage)
        assertThat(parentPage).hasURL(e2e.url("/iscrizioni"))
        age(parentEmail, 30)
        outsideThe(parentPage)
        assertThat(parentPage).hasURL(e2e.url("/accedi"))
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t15 c36 c19 c24 a browser without passkeys sees no button, and without a network nothing is saved`(e2e: E2e) {
        val page = e2e.page
        val email = checkNotNull(e2e.signedInAs)
        // A browser without passkeys (the old ones, and the ones with the script off in effect): the access page is the one of TALITA-3 and the profile says so.
        val bare = e2e.newPage(signedIn = false, teacher = false)
        bare.addInitScript("delete window.PublicKeyCredential;")
        e2e.signIn(bare, Addresses.unique("persona"), teacher = false)
        bare.navigate(e2e.url("/profilo"))
        bare.waitForSettledContent()
        assertThat(bare.locator("#passkey-contenuto")).containsText("Questo browser non sa usare le passkey.")
        assertThat(bare.locator("#passkey-contenuto").buttonNamed("Aggiungi una passkey su questo telefono")).hasCount(0)
        assertTrue(bare.noHorizontalScroll())
        assertAccessible(bare, "profile of a browser without passkeys")
        e2e.touch(bare.exact("Esci"))
        assertThat(bare).hasURL(e2e.url("/accedi"))
        bare.waitForSettledContent()
        assertThat(bare.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Entra con una passkey"))).hasCount(0)
        assertFalse(bare.locator("main").innerText().contains("oppure"), "no divider «oppure» for a browser without passkeys")
        assertFalse(bare.locator("main").innerText().contains("Se hai una passkey"), "no sentence about passkeys")
        assertThat(bare.exact("Ricevi il link")).isVisible()

        // Without a network: adding, and entering, say «Nessuna connessione» and nothing is saved; the page stays as it is.
        val phone = e2e.phoneOf()
        e2e.open("/profilo")
        val card = page.locator("#passkey-contenuto")
        e2e.browserContext.setOffline(true)
        e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono"))
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(0, e2e.passkeys())
        assertEquals(0, phone.credentials())
        assertThat(card.buttonNamed("Aggiungi una passkey su questo telefono")).isEnabled()
        e2e.browserContext.setOffline(false)
        e2e.addPasskey()
        e2e.touch(page.exact("Esci"))
        assertThat(page).hasURL(e2e.url("/accedi"))
        e2e.browserContext.setOffline(true)
        e2e.touch(page.exact("Entra con una passkey"))
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.exact("Entra con una passkey")).isEnabled()
        e2e.browserContext.setOffline(false)
        assertEquals(1, e2e.passkeysOf(email).size)
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t15 c36 c27 c41 nothing in line in the pages, and the confirmation offers the phone with the QR code`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        val paper = e2e.rows().register(w.id, null, "Nicolò", "Ferri", LocalDate.of(2015, 6, 2), enteredBy = w.meId, notes = Notes("Latte", null, null), notesOnPaper = true)
        e2e.teacherWithPasskeyAndNoConfirmation()

        fun noInline(path: String) {
            val html = page.request().get(e2e.url(path)).text()
            assertFalse(Regex("<script(?![^>]*\\bsrc=)[^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(html), "$path has a script in line")
            assertFalse(Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html), "$path has a handler in the markup")
            assertFalse(Regex("<style[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html), "$path has a style element")
            assertFalse(Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html), "$path has a style attribute")
        }
        for (path in listOf("/profilo", "/informativa", "/iscrizioni/$anna", "/arrivi/$anna", "/iscrizioni/$paper/correggi-segnalazioni")) noInline(path)
        e2e.open("/profilo")
        e2e.touch(page.exact("Esci"))
        noInline("/accedi")
        e2e.open("/accedi")
        assertThat(page.locator("script[src*='passkeys']")).hasCount(1)

        // The options of the confirmation: the passkeys of the account only, each with its transports and `hybrid`, and the person verified.
        e2e.signIn(page, checkNotNull(e2e.signedInAs), teacher = false)
        e2e.open("/iscrizioni/$anna")
        val answer = page.waitForResponse(Predicate<Response> { it.url().endsWith("/verifica-passkey/opzioni") }, Runnable { e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni")) })
        val body = JsonParser.parseString(answer.text()).asJsonObject
        assertTrue(body["serve"].asBoolean)
        val options = body["options"].asJsonObject
        assertEquals("required", options["userVerification"].asString)
        assertEquals("localhost", options["rpId"].asString)
        val allowed = options["allowCredentials"].asJsonArray
        assertEquals(1, allowed.size())
        val transports = allowed[0].asJsonObject["transports"].asJsonArray.map { it.asString }
        assertTrue("hybrid" in transports, "the phone of the QR code is offered: $transports")
        assertTrue("internal" in transports, "and the transports that the phone said: $transports")
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        assertEquals(1, phone.credentials())
        e2e.cspViolations.clear()
    }

    @E2eTest(signedIn = false, teacher = false)
    fun `t15 c36 c29 c23 a deleted account takes devices and passkeys with it and the passkey left in the phone enters nobody`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val email = Addresses.unique("persona")
        fun enterWithTheBox(on: Page) {
            on.navigate(e2e.url("/accedi/conferma?token=" + e2e.application.getBean(OneTimeTokenService::class.java).generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue))
            on.waitForSettledContent()
            on.getByLabel("Ricordami su questo telefono").check()
            e2e.touch(on.exact("Entra"))
            on.waitForURL(e2e.url("/"))
        }
        // A parent with a device (the box) and a passkey, and the same account on another phone.
        enterWithTheBox(page)
        e2e.addPasskey()
        val elsewhere = e2e.newPage(signedIn = false)
        enterWithTheBox(elsewhere)
        val id = e2e.accountId(email)
        assertEquals(2, e2e.dsl().fetchCount(REMEMBERED_DEVICE, REMEMBERED_DEVICE.ACCOUNT_ID.eq(id)))
        assertEquals(1, e2e.dsl().fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(id)))
        val accounts = e2e.dsl().fetchCount(ACCOUNT)

        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella il mio account"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("I dispositivi collegati: da tutti i telefoni e i computer esci da Talità.")
        assertThat(dialog).containsText("La passkey: resta nel telefono, ma non fa più entrare.")
        assertAccessible(page, "dialog of the account with devices and a passkey (${e2e.profile.label})")
        e2e.touch(dialog.buttonNamed("Cancella il mio account"))
        assertThat(page).hasURL(e2e.url("/accedi"))

        assertEquals(0, e2e.dsl().fetchCount(REMEMBERED_DEVICE, REMEMBERED_DEVICE.ACCOUNT_ID.eq(id)), "the devices went with the account")
        assertEquals(0, e2e.dsl().fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(id)), "and the passkeys")
        assertEquals(null, page.context().cookies().firstOrNull { it.name == "ricordami" }, "the cookie of this browser is gone")
        assertEquals(accounts - 1, e2e.dsl().fetchCount(ACCOUNT))
        // The other phone: the cookie finds no account, the page is the access page, and the account does not come back.
        elsewhere.context().clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))
        elsewhere.navigate(e2e.url("/iscrizioni"))
        elsewhere.waitForSettledContent()
        assertThat(elsewhere).hasURL(e2e.url("/accedi"))
        assertEquals(accounts - 1, e2e.dsl().fetchCount(ACCOUNT), "the account does not come back with the cookie")
        // The passkey is still in the phone, and enters nobody: the page says to use the link, and no account is made.
        page.waitForSettledContent()
        assertEquals(1, phone.credentials())
        e2e.touch(page.exact("Entra con una passkey"))
        assertThat(page.locator("[data-passkey-esito=non-valida]")).isVisible()
        assertThat(page.locator("[data-passkey-esito=non-valida]")).containsText("Questa passkey non funziona.")
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertEquals(accounts - 1, e2e.dsl().fetchCount(ACCOUNT), "a passkey never makes an account")
    }

    /**
     * The QA of the fix after the review (commit 154958c, criterion 30): a teacher whose address stays for the readings, who came back with a link and registered a child, is not signed
     * out by the job that makes the request again every night (and at every start), and a device that was born after the request goes with its sessions once, by the first run. The
     * request itself still closes the other phones (t7 c25, t15 c29).
     */
    @E2eTest(teacher = false)
    fun `t15 c30 a teacher who stays for the readings and came back to register a child is not signed out by the job night after night, and a device born after the request goes once with its sessions`(e2e: E2e) {
        val page = e2e.page
        val job = e2e.application.getBean(RetentionJob::class.java)
        val gathering = e2e.openGathering()
        e2e.dsl().deleteFrom(RETENTION_LOG).execute()
        val email = checkNotNull(e2e.signedInAs)
        TestAccounts.makeTeacher(e2e.dsl(), email, "Giulia", "Serra")
        val me = e2e.accountId(email)
        val anna = e2e.rows().register(gathering.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        e2e.dsl().insertInto(HEALTH_NOTE_ACCESS).set(HEALTH_NOTE_ACCESS.REGISTRATION_ID, anna).set(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID, me)
            .set(HEALTH_NOTE_ACCESS.ACCESSED_AT, OffsetDateTime.now(e2e.clock())).execute()

        fun generation(): Int = e2e.dsl().select(ACCOUNT.SESSION_GENERATION).from(ACCOUNT).where(ACCOUNT.ID.eq(me)).fetchSingle(ACCOUNT.SESSION_GENERATION)!!
        fun devices(): Int = e2e.dsl().fetchCount(REMEMBERED_DEVICE, REMEMBERED_DEVICE.ACCOUNT_ID.eq(me))
        fun enterWithTheBox(on: Page) {
            val link = e2e.application.getBean(OneTimeTokenService::class.java).generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue
            on.navigate(e2e.url("/accedi/conferma?token=$link"))
            on.waitForSettledContent()
            on.getByLabel("Ricordami su questo telefono").check()
            e2e.touch(on.exact("Entra"))
            on.waitForURL(e2e.url("/"))
        }
        fun inside(on: Page): Boolean {
            on.navigate(e2e.url("/iscrizioni"))
            on.waitForSettledContent()
            return on.url() == e2e.url("/iscrizioni")
        }

        // The person has the box on another phone, and asks to delete the account: the other phone is out at its next request, the devices are gone, the address stays for the readings.
        val elsewhere = e2e.newPage(signedIn = false, teacher = false)
        enterWithTheBox(elsewhere)
        assertEquals(1, devices())
        assertTrue(inside(elsewhere))
        val beforeTheRequest = generation()
        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella il mio account"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Cancella il mio account"))
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertTrue(e2e.dsl().fetchExists(ACCOUNT, ACCOUNT.ID.eq(me)), "the address stays for the readings")
        assertFalse(e2e.dsl().fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(me)), "and the person is not a teacher any more")
        assertEquals(0, devices())
        assertEquals(beforeTheRequest + 1, generation(), "the request closes the other sessions")
        assertFalse(inside(elsewhere), "the other phone is out: no session, and the cookie of the box finds nothing")

        // The person comes back with a link and registers a child of their own, by the form: the account stays with that, for the 37 days that the register remembers the request.
        e2e.signIn(page, email, teacher = false)
        e2e.open("/info/${gathering.code}/iscrizione")
        page.settled()
        page.locator("#telefono").fill("333 123 4567")
        page.fillBlock(0, "Marco", "Verdi", "12", "5", "2017")
        page.locator("input[name='children[0].presence'][value='all_days']").check()
        e2e.press(page.locator("main").buttonNamed("Controlla e accetta"))
        page.locator("#altro-genitore").check()
        page.locator("#accetto").check()
        page.locator("#convinzioni").check()
        e2e.press(page.locator("main").buttonNamed("Accetta e iscrivi"))
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ACCOUNT_ID.eq(me)))
        val afterTheRequest = generation()

        // Two runs of the job, the night and the start: nothing to take away, so the person stays inside and the number of the sessions does not move.
        job.run()
        job.run()
        assertEquals(afterTheRequest, generation(), "the job does not close the sessions of a person who has nothing taken away")
        assertTrue(e2e.dsl().fetchExists(ACCOUNT, ACCOUNT.ID.eq(me)))
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ACCOUNT_ID.eq(me)), "what the person did after the request stays")
        assertTrue(inside(page), "still inside after two runs")

        // A device born after the request: the first run takes it away and the sessions end once, the next run has nothing to do.
        val phone = e2e.newPage(signedIn = false, teacher = false)
        enterWithTheBox(phone)
        assertEquals(1, devices())
        assertTrue(inside(phone))
        job.run()
        assertEquals(0, devices(), "the device that was born after the request goes")
        assertEquals(afterTheRequest + 1, generation(), "and the sessions end, once")
        assertFalse(inside(page), "the page that was inside is out")
        assertFalse(inside(phone), "and so is the phone of the device, which the cookie of the box cannot bring back")
        // The page that was refused kept «Iscrizioni» as the page to go back to: with its cookies cleared the link leads to the home, as for anybody who comes in.
        page.context().clearCookies()
        e2e.signIn(page, email, teacher = false)
        job.run()
        assertEquals(afterTheRequest + 1, generation(), "the next run closes nothing")
        assertTrue(inside(page), "and the person who came in again stays inside")
    }

    /**
     * The QA of the fix after the review (commit 154958c, criterion 21): the options of a first registration are asked and the phone is cancelled; they are asked again 4 minutes and 30 seconds
     * later (another tap, the same session) and the phone answers a minute after that. The first handle would be gone at the fifth minute, but the options that the phone answers are good until
     * 9:30: the registration succeeds, with the same handle for the account.
     */
    @E2eTest(teacher = false, profiles = [Profile.DESKTOP])
    fun `t15 c21 a registration that is asked again after four and a half minutes and answered a minute later succeeds, with a single handle`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        e2e.open("/profilo")
        val card = page.locator("#passkey-contenuto")
        val email = checkNotNull(e2e.signedInAs)
        val me = e2e.accountId(email)

        val (status, first) = page.fetchJson("POST", "/webauthn/register/options")
        assertEquals(200, status)
        val handle = JsonParser.parseString(first).asJsonObject["user"].asJsonObject["id"].asString
        assertNull(e2e.dsl().select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(me)).fetchSingle(ACCOUNT.PASSKEY_HANDLE), "a phone that is cancelled leaves nothing")
        e2e.clock().advance(Duration.ofMinutes(4).plusSeconds(30))

        // The page asks again by itself (the tap) and the answer of the phone reaches the server a minute later: the clock is moved just before the registration is sent.
        val again = page.waitForResponse(Predicate<Response> { it.url().endsWith("/webauthn/register/options") }, Runnable {
            page.route("**/webauthn/register") { route ->
                e2e.clock().advance(Duration.ofMinutes(1))
                route.resume()
            }
            e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono"))
        })
        assertEquals(handle, JsonParser.parseString(again.text()).asJsonObject["user"].asJsonObject["id"].asString, "the same handle for the same account")
        assertThat(card.locator("[role=status]").first()).containsText("Passkey aggiunta.")
        page.unroute("**/webauthn/register")
        assertEquals(1, e2e.passkeys())
        assertEquals(1, phone.credentials())
        assertEquals(handle, Base64.getUrlEncoder().withoutPadding().encodeToString(e2e.dsl().select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(me)).fetchSingle(ACCOUNT.PASSKEY_HANDLE)!!), "and it is the one that was saved")
        assertEquals(1, e2e.dsl().fetchCount(ACCOUNT, ACCOUNT.ID.eq(me).and(ACCOUNT.PASSKEY_HANDLE.isNotNull)), "one handle for the account")
    }

    @E2eTest(profiles = [Profile.DESKTOP])
    fun `t15 c36 c33 the log has no selector, value, credential, handle, challenge, address or session id`(e2e: E2e, output: CapturedOutput) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        val secrets = mutableSetOf<String>()
        fun remember(vararg values: String?) = values.filterNotNull().filter { it.length >= 6 }.forEach { secrets += it }

        // The box, with a parent in her own browsers: a link, the cookie, a re-entry, a copy of the cookie that is used after the value changed, and junk.
        val parent = Addresses.unique("persona")
        remember(parent)
        val owner = e2e.newPage(signedIn = false)
        val link = e2e.application.getBean(OneTimeTokenService::class.java).generate(GenerateOneTimeTokenRequest(parent, Duration.ofMinutes(15))).tokenValue
        remember(link)
        owner.navigate(e2e.url("/accedi/conferma?token=$link"))
        owner.waitForSettledContent()
        owner.getByLabel("Ricordami su questo telefono").check()
        e2e.touch(owner.exact("Entra"))
        owner.waitForURL(e2e.url("/"))
        val copy = checkNotNull(owner.context().cookies().first { it.name == "ricordami" }).value
        remember(copy, Phone.decode(copy).first, Phone.decode(copy).second, owner.context().cookies().first { it.name == "JSESSIONID" }.value)
        owner.context().clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))
        owner.navigate(e2e.url("/iscrizioni"))
        owner.waitForSettledContent()
        val rotated = checkNotNull(owner.context().cookies().first { it.name == "ricordami" }).value
        remember(rotated, Phone.decode(rotated).second, owner.context().cookies().first { it.name == "JSESSIONID" }.value)
        val thief = e2e.newPage(signedIn = false)
        thief.context().addCookies(listOf(Cookie("ricordami", copy).setUrl(e2e.url("/"))))
        thief.navigate(e2e.url("/iscrizioni"))
        thief.waitForSettledContent()
        val junk = e2e.newPage(signedIn = false)
        junk.context().addCookies(listOf(Cookie("ricordami", "sceltaCheNonEsiste1234:valoreCheNonEsiste").setUrl(e2e.url("/"))))
        junk.navigate(e2e.url("/iscrizioni"))
        junk.waitForSettledContent()

        // The passkey of a teacher: add it, the confirmation with a wrong signature and with a good answer, the way in with it, and a passkey that was taken away.
        val email = checkNotNull(e2e.signedInAs)
        remember(email)
        e2e.addPasskey()
        val credential = e2e.passkeysOf(email).single()
        val handle = e2e.dsl().select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(e2e.accountId(email))).fetchSingle(ACCOUNT.PASSKEY_HANDLE)!!
        remember(reference(credential), Base64.getUrlEncoder().withoutPadding().encodeToString(handle), Base64.getEncoder().encodeToString(handle), Base64.getEncoder().encodeToString(credential))
        page.context().clearCookies(BrowserContext.ClearCookiesOptions().setName("JSESSIONID"))
        e2e.signIn(page, email, teacher = false)
        remember(page.context().cookies().first { it.name == "JSESSIONID" }.value)
        e2e.open("/iscrizioni/$anna")
        page.tamperAssertion()
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni [data-conferma-esito=segnalazioni-rifiutata]")).isVisible()
        page.unroute("**/verifica-passkey")
        remember(JsonParser.parseString(page.fetchJson("POST", "/verifica-passkey/opzioni").second).asJsonObject["options"]?.asJsonObject?.get("challenge")?.asString)
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        e2e.open("/profilo")
        e2e.touch(page.exact("Esci"))
        e2e.touch(page.exact("Entra con una passkey"))
        page.waitForURL(e2e.url("/"))
        remember(page.context().cookies().first { it.name == "JSESSIONID" }.value)
        e2e.dsl().deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(e2e.accountId(email))).execute()
        e2e.open("/profilo")
        e2e.touch(page.exact("Esci"))
        e2e.touch(page.exact("Entra con una passkey"))
        assertThat(page.locator("[data-passkey-esito=non-valida]")).isVisible()
        phone.verified(true)

        val log = output.all
        val leaked = secrets.filter { log.contains(it) }
        assertEquals(emptyList<String>(), leaked.map { "${it.take(4)}… (${it.length})" }, "the log has what must never be in it")
        assertTrue(log.contains("Remember-me: a cookie with a value that is not the last one was presented: the device was unlinked."), "the copy of the cookie is a line of the log, with no data")
        assertTrue(log.contains("Passkey: a confirmation was refused"), "and so is a confirmation that was refused")
        // And no line that is a WARN or worse carries the address of a person.
        assertFalse(Regex("(WARN|ERROR).*@example\\.org").containsMatchIn(log))
        e2e.cspViolations.clear()
    }

    /**
     * D1 of the QA of TALITA-15. A page that was left open past the end of the session (two hours without use) and a tap that goes through `passkeys.js` (the confirmation before the notes, «Aggiungi
     * una passkey», «Entra con una passkey»): the script follows the redirect of the server, gets an HTML page where it waits for JSON, and the person sees «Qualcosa non ha funzionato» and nothing
     * else: with the box they are still inside but the page stays as it was (and the next tap does the same, because the code CSRF of the page is the old one), without it they should be on the access
     * page, as for any other request. The other requests of the app (htmx) end in `HX-Redirect` or `HX-Refresh` (criterion 8). Switch it on when the script treats a redirected answer as «the access is
     * gone» (the access page, or a reload of the page when the cookie of the box is good).
     */
    @E2eTest
    fun `t15 c36 c8 D1 a tap through the script of the passkeys on a page left open past the session does not end in an error`(e2e: E2e) {
        val page = e2e.page
        e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        val address = checkNotNull(e2e.signedInAs)

        // «Aggiungi una passkey» (the first one) on a profile left open past the session, without the box: the access page, and nothing is saved.
        e2e.open("/profilo")
        e2e.browserContext.loseTheSession()
        e2e.press(page.locator("#passkey-contenuto").buttonNamed("Aggiungi una passkey su questo telefono"))
        page.waitForURL(e2e.url("/accedi"))
        assertEquals(0, page.messages().count(), "no error message on the way to the access page from the profile")
        assertEquals(0, e2e.passkeys(), "nothing is saved")
        e2e.signIn(page, address, teacher = false)
        val email = e2e.teacherWithPasskeyAndNoConfirmation()

        // Without the box, «Mostra le segnalazioni»: the access page, as for every other request.
        e2e.open("/iscrizioni/$anna")
        e2e.browserContext.loseTheSession()
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        page.waitForURL(e2e.url("/accedi"))
        assertEquals(0, page.messages().count(), "no error message on the way to the access page")

        // With the box: still inside, no error, the page is loaded again, and what the person does next works.
        e2e.signIn(page, email, teacher = false)
        e2e.open("/profilo")
        page.navigate(e2e.url("/accedi/conferma?token=" + e2e.application.getBean(OneTimeTokenService::class.java).generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue))
        page.waitForSettledContent()
        page.getByLabel("Ricordami su questo telefono").check()
        e2e.touch(page.exact("Entra"))
        page.waitForURL(e2e.url("/"))
        e2e.open("/iscrizioni/$anna")
        page.evaluate("window.__marker = 'the old page'")
        e2e.browserContext.loseTheSession()
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        page.waitUntil("window.__marker === undefined")
        page.waitForSettledContent()
        assertEquals(0, page.messages().count(), "no error message for a person who is still inside")
        assertTrue(!page.url().endsWith("/accedi"), "the person is not sent out")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        assertEquals(0, page.messages().count())

        // With the box, «Aggiungi una passkey» (the confirmation comes first, the person has one): the same.
        e2e.open("/profilo")
        page.evaluate("window.__marker = 'the old page'")
        e2e.browserContext.loseTheSession()
        e2e.press(page.locator("#passkey-contenuto").buttonNamed("Aggiungi una passkey su questo telefono"))
        page.waitUntil("window.__marker === undefined")
        page.waitForSettledContent()
        assertEquals(0, page.messages().count(), "no error message for a person who is still inside, on the profile")
        assertTrue(!page.url().endsWith("/accedi"), "the person is not sent out of the profile")
        assertThat(page.locator("h1")).hasText("Profilo")

        // The access page left open for hours: «Entra con una passkey» comes back to the access page with the notice, no error, and the page that comes back works.
        e2e.touch(page.exact("Esci"))
        page.waitForSettledContent()
        page.evaluate("window.__marker = 'the old page'")
        e2e.browserContext.loseTheSession()
        e2e.touch(page.exact("Entra con una passkey"))
        page.waitUntil("window.__marker === undefined")
        page.waitForSettledContent()
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.locator("main")).containsText("La pagina era aperta da troppo tempo. Riprova.")
        assertEquals(0, page.messages().count(), "no error message on the access page")
        assertAccessible(page, "access page after a passkey tap on an expired page (${e2e.profile.label})")
        e2e.touch(page.exact("Entra con una passkey"))
        page.waitForURL(e2e.url("/"))
    }

    /**
     * D3 of the QA of TALITA-15: the way to the card «Passkey» of the profile (the line that invites a teacher to add one, and «Vai alle passkey» after «Esci da tutti gli altri dispositivi») ends with the
     * focus on the card, which has `tabindex="-1"` for it, and not on `body`.
     */
    @E2eTest
    fun `t15 c36 c46 D3 the links to the card Passkey of the profile put the focus on the card`(e2e: E2e) {
        val page = e2e.page
        e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))

        // From the card of a child in the arrivals: the line that invites a teacher with no passkey.
        e2e.open("/arrivi/$anna")
        e2e.press(page.locator("#scheda").linkNamed("Aggiungi una passkey"))
        assertThat(page).hasURL(Pattern.compile("/profilo#passkey$"))
        page.waitForSettledContent()
        assertEquals("passkey", page.evaluate("document.activeElement && document.activeElement.id"), "the focus is on the card")

        // From the notice after «Esci da tutti gli altri dispositivi», which sends a person who has passkeys to the card.
        e2e.addPasskey()
        e2e.open("/profilo")
        val block = page.locator("#dispositivi-contenuto")
        e2e.press(block.buttonNamed("Esci da tutti gli altri dispositivi"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Esci da tutti gli altri dispositivi"))
        e2e.press(block.linkNamed("Vai alle passkey"))
        page.focusIs("document.activeElement.id === 'passkey'")
    }

    /**
     * D2 of the QA of TALITA-15: two tabs of the same person that add their first passkey together are given the same handle (the provisional one that the server holds for five minutes, or the
     * one that the first has saved), both passkeys are saved and the account has one handle only.
     */
    @E2eTest(teacher = false, profiles = [Profile.DESKTOP])
    fun `t15 c36 c21 D2 two tabs that add the first passkey together are given the same handle and the account keeps one`(e2e: E2e) {
        val first = e2e.page
        val second = e2e.browserContext.newPage().also { it.setDefaultTimeout(10_000.0); e2e.watchCsp(it) }
        e2e.phoneOf(first)
        e2e.phoneOf(second)
        val given = java.util.concurrent.CopyOnWriteArrayList<String>()
        for (tab in listOf(first, second)) {
            tab.onResponse { if (it.url().endsWith("/webauthn/register/options")) given += JsonParser.parseString(it.text()).asJsonObject["user"].asJsonObject["id"].asString }
            tab.navigate(e2e.url("/profilo"))
            tab.waitForSettledContent()
        }
        val email = checkNotNull(e2e.signedInAs)
        assertNull(e2e.dsl().select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(e2e.accountId(email))).fetchSingle(ACCOUNT.PASSKEY_HANDLE), "no handle before the first passkey")

        // Both taps, one after the other and without waiting for the first to end.
        for (tab in listOf(first, second)) tab.locator("#passkey-contenuto").buttonNamed("Aggiungi una passkey su questo telefono").click()
        for (tab in listOf(first, second)) assertThat(tab.locator("#passkey-contenuto [role=status]").first()).containsText("Passkey aggiunta.")

        assertEquals(2, e2e.passkeys())
        assertEquals(2, given.size, "each tab asked for the options once")
        assertEquals(given[0], given[1], "the same handle for both tabs")
        val handles = e2e.dsl().select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(e2e.accountId(email))).fetch(ACCOUNT.PASSKEY_HANDLE)
        assertEquals(1, handles.size)
        assertEquals(given[0], Base64.getUrlEncoder().withoutPadding().encodeToString(handles.single()!!), "the handle of the account is the one of the options")
    }

    /**
     * What the QA of TALITA-15 found different from the mockups, seen on the screen: the waiting lines of the confirmation (the dialog of Togli, Aggiungi when the person has a passkey, and the notes
     * of a child) say that «Dal computer, il browser può chiederti di usare il telefono.» only to a precise pointer (a phone does not show it), and «Se cambi o perdi il telefono» has the icon of information.
     */
    @E2eTest
    fun `t15 c36 c41 the waiting lines of the confirmation add the sentence of the computer only for a precise pointer, and the lost phone has the icon of information`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        val w = e2e.arrivalsGathering()
        val anna = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, null))
        val computer = "Dal computer, il browser può chiederti di usare il telefono."
        e2e.addPasskey()
        e2e.open("/profilo")
        val card = page.locator("#passkey-contenuto")
        val icon = card.locator("summary").filter(Locator.FilterOptions().setHasText("Se cambi o perdi il telefono")).locator("svg").first().innerHTML()
        assertTrue(icon.contains("M12 16v-4") && icon.contains("M12 8h.01"), "the icon of information: $icon")

        // The phone does not answer, so that the waiting line stays on the screen.
        phone.answers(false)
        e2e.press(card.buttonNamed("Aggiungi una passkey su questo telefono"))
        val adding = card.locator("[data-passkey] [data-stato]")
        assertThat(adding).containsText("In attesa della conferma…")
        assertThat(adding).containsText("Prima conferma con una passkey che hai già: impronta, volto o codice di sblocco.")
        if (e2e.profile.isPhone) assertThat(adding.getByText(computer)).isHidden() else assertThat(adding.getByText(computer)).isVisible()
        assertEquals(1, e2e.passkeys())

        e2e.open("/profilo")
        e2e.press(card.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Togli la passkey aggiunta il ")))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Togli la passkey"))
        val taking = page.openDialog().locator("[data-stato]")
        assertThat(taking).containsText("In attesa della conferma…")
        assertThat(taking).containsText("Conferma con l’impronta, il volto o il codice di sblocco.")
        if (e2e.profile.isPhone) assertThat(taking.getByText(computer)).isHidden() else assertThat(taking.getByText(computer)).isVisible()
        assertEquals(1, e2e.passkeys(), "nothing is taken away while the phone has not answered")

        e2e.open("/iscrizioni/$anna")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        val reading = page.locator("#segnalazioni [data-stato]")
        assertThat(reading).containsText("Conferma con l’impronta, il volto o il codice di sblocco.")
        if (e2e.profile.isPhone) assertThat(reading.getByText(computer)).isHidden() else assertThat(reading.getByText(computer)).isVisible()
        assertThat(page.locator("main")).not().containsText("Arachidi")
        assertEquals(0, e2e.readings())
    }
}
