package talita.e2e

import com.google.gson.JsonObject
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.PASSKEY
import talita.registration.Notes
import java.time.LocalDate
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
class PasskeyE2eTest {

    /** A phone that is inside the browser: it holds the passkeys that the page makes, and [verified] says whether the person puts their finger (or their face) on it. */
    private class VirtualPhone(private val session: CDPSession, private val id: String) {

        fun verified(value: Boolean) {
            session.send("WebAuthn.setUserVerified", JsonObject().apply { addProperty("authenticatorId", id); addProperty("isUserVerified", value) })
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
}
