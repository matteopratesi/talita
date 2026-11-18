package talita.e2e

import com.google.gson.JsonObject
import com.microsoft.playwright.CDPSession
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import talita.account.TestAccounts
import talita.Addresses
import talita.jooq.tables.references.API_REQUEST
import talita.jooq.tables.references.API_TOKEN
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.time.ZoneId
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The token for the APIs as QA checks it (TALITA-21), on a phone and on a desktop. It adds to `ApiTokenE2eTest` (the day of work with a teacher who has no passkey): here the teacher has a **passkey** (the
 * virtual authenticator of Chromium), so «Crea un token» asks the phone first and the dialogs say so; the states of the card by the days (last day, past its day), what the register shows after real HTTP
 * calls, «Esci da tutti gli altri dispositivi», the dialogs of the account and of the teachers, and what a parent never reaches. axe and no sideways scrolling at 320 px, 360 px and on the desktop on every state.
 * The tests never depend on the day of the week or on the hour: the days of a token are counted from today. Teachers, addresses and names are synthetic; a token is never written in a message or in a log.
 */
@PasskeyE2eApplication
class ApiTokenQaE2eTest {

    /** A phone that is inside the browser: [verified] says whether the person puts their finger on it. */
    private class VirtualPhone(private val session: CDPSession, private val id: String) {
        fun verified(value: Boolean) {
            session.send("WebAuthn.setUserVerified", JsonObject().apply { addProperty("authenticatorId", id); addProperty("isUserVerified", value) })
        }
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

    private fun E2e.addPasskey() {
        open("/profilo")
        page.waitForSettledContent()
        press(page.locator("#passkey-contenuto").buttonNamed("Aggiungi una passkey su questo telefono"))
        assertThat(page.locator("#passkey-contenuto [role=status]").first()).containsText("Passkey aggiunta.")
    }

    private fun Page.sideways(e2e: E2e, what: String) {
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling")
        setViewportSize(320, 800)
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling at 320 px")
        setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
    }

    private fun E2e.check(what: String) {
        page.sideways(this, "$what (${profile.label})")
        assertAccessible(page, "$what (${profile.label})")
    }

    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun E2e.call(method: String, path: String, token: String?, body: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url(path)))
            .method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            .apply {
                if (token != null) header("Authorization", "Bearer $token")
                if (body != null) header("Content-Type", "application/json")
            }.build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** The value of the token that the last answer showed. It stays in memory: no message of a test has it. */
    private fun Page.shownToken(): String = locator("#token-riga").innerText().substringAfter("Bearer ").trim()

    private fun E2e.accountOfPage(): Long = accountId(checkNotNull(signedInAs))

    private fun E2e.tokenRows(): Int = dsl().fetchCount(API_TOKEN, API_TOKEN.ACCOUNT_ID.eq(accountOfPage()))

    private fun romeToday(): LocalDate = LocalDate.now(ZoneId.of("Europe/Rome"))

    /** Moves the day on which the token of the teacher was made, to count the days from today whatever today is. */
    private fun E2e.madeDaysAgo(days: Long) {
        dsl().update(API_TOKEN).set(API_TOKEN.CREATED_ON, romeToday().minusDays(days)).where(API_TOKEN.ACCOUNT_ID.eq(accountOfPage())).execute()
    }

    // ---- Criteria 1, 2 and 3: the confirmation with the passkey ---------------------------------------------------------------------------------

    @E2eTest
    fun `t21 c02 c01 a teacher with a passkey makes the token only after the phone confirms, the cancelled phone makes nothing, and the value is not kept by the page`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        e2e.addPasskey()
        e2e.open("/profilo")
        page.waitForSettledContent()
        val card = page.locator("#token")
        assertThat(card).containsText("Prima il telefono ti chiede l’impronta, il volto o il codice di sblocco.")
        e2e.check("card with a passkey and no token")

        // The person does not put their finger on the phone: no token, a notice that is not an error, and the way back is open.
        phone.verified(false)
        e2e.press(card.buttonNamed("Crea un token"))
        assertThat(card.locator("[data-conferma-esito=token-annullata]")).isVisible()
        assertThat(card.locator("[data-conferma-esito=token-annullata]")).containsText("Hai annullato la conferma, quindi non è stato creato nessun token.")
        assertEquals(0, e2e.tokenRows(), "a cancelled confirmation makes no token")
        assertEquals(0, page.locator("#token-riga").count())
        e2e.check("card after a cancelled confirmation")

        // The finger is on the phone: the answer is a 200 with `no-store`, and it shows the line once.
        phone.verified(true)
        val answer = page.waitForResponse({ it.url().endsWith("/profilo/token") && it.request().method() == "POST" }) { e2e.press(card.buttonNamed("Crea un token")) }
        assertEquals(200, answer.status())
        assertEquals("no-store", answer.headerValue("cache-control"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        assertThat(page.locator("#token-riga")).containsText("Authorization: Bearer talita_")
        assertEquals("false", page.locator("#token-contenuto").getAttribute("hx-history"))
        assertEquals(1, e2e.tokenRows())
        e2e.check("the token just made")
        val value = page.shownToken()
        assertTrue(Regex("talita_[A-Za-z0-9_-]{43}").matches(value))

        // The value is in no address, no attribute and, after a reload, nowhere in the page.
        assertFalse(page.url().contains("talita_"), "the value is not in the address")
        val inAttribute = page.evaluate("(v) => Array.from(document.querySelectorAll('*')).some(e => Array.from(e.attributes).some(a => a.value.includes(v)))", value.substringAfter("talita_"))
        assertEquals(false, inAttribute, "the value is not in an attribute")
        page.reload()
        page.waitForSettledContent()
        assertEquals(0, page.locator("#token-riga").count())
        assertFalse(page.content().contains(value.substringAfter("talita_")), "after a reload the value is gone")
        assertThat(page.locator("#token")).containsText("Attivo")
        // And it works, by itself, as a client that is not a browser.
        assertEquals(200, e2e.call("GET", "/api/convivenza", value).statusCode())
    }

    @E2eTest
    fun `t21 c03 c02 a new token asks the phone, replaces the old one only if it is made, and the cancelled phone leaves the old token as it was`(e2e: E2e) {
        val page = e2e.page
        val phone = e2e.phoneOf()
        e2e.addPasskey()
        // The first token is made by the service, as a teacher who made it a day ago: a confirmation made on the page would leave the ten minutes open, and the next one would ask nothing.
        val first = checkNotNull(e2e.application.getBean(talita.api.ApiTokens::class.java).create(e2e.accountOfPage())).value
        e2e.open("/profilo")
        page.waitForSettledContent()
        val card = page.locator("#token")
        assertThat(card).containsText("Attivo")

        // The dialog of «Crea un token nuovo» says that the phone asks, and a cancelled phone leaves the old token as it was.
        e2e.press(card.buttonNamed("Crea un token nuovo"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("Il token di adesso smette di funzionare subito.")
        assertThat(dialog).containsText("Per crearlo, il telefono ti chiede l’impronta, il volto o il codice di sblocco.")
        assertAccessible(page, "dialog of a new token with a passkey (${e2e.profile.label})")
        phone.verified(false)
        e2e.touch(dialog.buttonNamed("Crea il token nuovo"))
        assertThat(card.locator("[data-conferma-esito=token-annullata]")).isVisible()
        assertEquals(200, e2e.call("GET", "/api/convivenza", first).statusCode(), "the old token still works after a cancelled confirmation")
        assertEquals(1, e2e.tokenRows())

        // With the finger on the phone the new token is made, and the old one stops at once.
        phone.verified(true)
        page.reload()
        page.waitForSettledContent()
        e2e.press(card.buttonNamed("Crea un token nuovo"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Crea il token nuovo"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        val second = page.shownToken()
        assertTrue(first != second)
        assertEquals(401, e2e.call("GET", "/api/convivenza", first).statusCode())
        assertEquals(200, e2e.call("GET", "/api/convivenza", second).statusCode())
        assertEquals(1, e2e.tokenRows(), "one token for a teacher")

        assertEquals(200, e2e.call("GET", "/api/convivenza", second).statusCode())
    }

    @E2eTest
    fun `t21 c03 a Revoca from a card that shows a token already replaced changes nothing and says so`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")
        page.waitForSettledContent()
        val card = page.locator("#token")
        e2e.press(card.buttonNamed("Crea un token"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        e2e.press(card.linkNamed("Fatto"))

        // A second tab of the same person, with the card that shows the first token.
        val stale = e2e.newPage(signedIn = false)
        stale.context().addCookies(page.context().cookies())
        stale.navigate(e2e.url("/profilo"))
        stale.waitForSettledContent()
        assertThat(stale.locator("#token")).containsText("Attivo")

        // In the first tab the token is replaced.
        e2e.press(card.buttonNamed("Crea un token nuovo"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Crea il token nuovo"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        val current = page.shownToken()

        // In the second tab «Revoca» is about the old one: nothing changes, the notice says why, and the new token still works.
        val staleCard = stale.locator("#token")
        e2e.press(staleCard.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(Pattern.compile("^Revoca il token creato il "))))
        stale.waitForSettledDialog()
        e2e.touch(stale.openDialog().buttonNamed("Revoca il token"))
        assertThat(staleCard).containsText("Quel token era già stato revocato o sostituito.")
        assertThat(staleCard).containsText("Qui sotto trovi quello di adesso.")
        assertEquals(200, e2e.call("GET", "/api/convivenza", current).statusCode(), "the token of now was not taken away")
        assertEquals(1, e2e.tokenRows())
    }

    @E2eTest
    fun `t21 c03 Esci da tutti gli altri dispositivi says that the token goes too, takes it away, and the card says so`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")
        page.waitForSettledContent()
        val card = page.locator("#token")
        e2e.press(card.buttonNamed("Crea un token"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        val value = page.shownToken()
        assertEquals(200, e2e.call("GET", "/api/convivenza", value).statusCode())
        e2e.press(card.linkNamed("Fatto"))
        page.reload()
        page.waitForSettledContent()

        val devices = page.locator("#dispositivi-contenuto")
        assertThat(devices).containsText("Revoca anche il token per le API.")
        e2e.press(devices.buttonNamed("Esci da tutti gli altri dispositivi"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("Il token per le API viene revocato: nessun programma potrà più fare modifiche finché non ne crei uno nuovo.")
        assertAccessible(page, "dialog of Esci da tutti with a token (${e2e.profile.label})")
        e2e.touch(dialog.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(Pattern.compile("Esci da tutti"))).last())
        assertThat(devices).containsText("Anche il token per le API è stato revocato.")
        assertThat(card).containsText("Token revocato.")
        assertThat(card).containsText("Sei uscito da tutti gli altri dispositivi e il token è andato con loro.")
        assertEquals(0, e2e.tokenRows())
        assertEquals(401, e2e.call("GET", "/api/convivenza", value).statusCode())
        e2e.check("after Esci da tutti with a token")
    }

    // Defect found by QA: the block of the devices is drawn with the page, so after «Crea un token» (which only draws the card of the token) the sentence under «Esci da tutti gli altri dispositivi» and the dialog do not
    // say that the token goes too, and the click revokes it all the same. Enable the test when the developer has fixed it.
    @E2eTest
    fun `t21 c03 the dialog of Esci da tutti says that the token goes too also when the token was made a moment ago on the same page`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")
        page.waitForSettledContent()
        val card = page.locator("#token")
        e2e.press(card.buttonNamed("Crea un token"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        e2e.press(card.linkNamed("Fatto"))
        val devices = page.locator("#dispositivi-contenuto")
        assertThat(devices).containsText("Revoca anche il token per le API.")
        e2e.press(devices.buttonNamed("Esci da tutti gli altri dispositivi"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Il token per le API viene revocato")
    }

    // ---- Criterion 4: the days of the token ---------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t21 c04 the card says Attivo, Ultimo giorno or Scaduto by the days and an expired token is a 401 that a new one replaces without a dialog`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")
        page.waitForSettledContent()
        val card = page.locator("#token")
        e2e.press(card.buttonNamed("Crea un token"))
        val value = page.shownToken()
        e2e.press(card.linkNamed("Fatto"))

        val dateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ITALIAN)
        // Day 0: it stops on day 30, which is counted from the day it was made.
        assertThat(card).containsText("Vale fino al ${romeToday().plusDays(30).format(dateFormat)}.")

        e2e.madeDaysAgo(29)
        page.reload(); page.waitForSettledContent()
        assertThat(card).containsText("Attivo")
        assertEquals(200, e2e.call("GET", "/api/convivenza", value).statusCode())
        e2e.check("a token of the day 29")

        e2e.madeDaysAgo(30)
        page.reload(); page.waitForSettledContent()
        assertThat(card).containsText("Ultimo giorno")
        assertThat(card).containsText("Vale fino a oggi. Da domani smette di funzionare: se ti serve ancora, crea un token nuovo.")
        assertEquals(200, e2e.call("GET", "/api/convivenza", value).statusCode(), "the 30th day is the last one that works")
        e2e.check("the last day of a token")

        e2e.madeDaysAgo(31)
        page.reload(); page.waitForSettledContent()
        assertThat(card).containsText("Scaduto")
        assertThat(card).containsText("È scaduto il ${romeToday().minusDays(1).format(dateFormat)}: il programma non può più usarlo.")
        assertEquals(401, e2e.call("GET", "/api/convivenza", value).statusCode())
        e2e.check("an expired token")

        // «Crea un token nuovo» of an expired token is a tap with no dialog; the new token works.
        assertEquals(0, page.openDialog().count())
        e2e.press(card.buttonNamed("Crea un token nuovo"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        assertEquals(200, e2e.call("GET", "/api/convivenza", page.shownToken()).statusCode())
        assertEquals(1, e2e.tokenRows())
    }

    // ---- Criterion 12: the register ------------------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t21 c12 the register of the card is empty at the start, then shows the last requests with the words of the design and says nothing of who made them`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")
        page.waitForSettledContent()
        val card = page.locator("#token")
        val register = page.locator("#token-registro")
        // The register is the same for every token: the test starts from an empty one (the tests of a run share the database).
        e2e.dsl().deleteFrom(API_REQUEST).execute()
        e2e.open("/profilo")
        page.waitForSettledContent()
        assertThat(register.locator("details")).not().hasAttribute("open", Pattern.compile(".*"))
        register.locator("summary").click()
        assertThat(register).containsText("Nessuna richiesta negli ultimi 30 giorni. Quando un programma userà il token, le vedi qui.")
        e2e.check("the empty register")

        e2e.press(card.buttonNamed("Crea un token"))
        val value = page.shownToken()
        // A request that goes well, one that is refused for its data, one that the state refuses and one that does not exist.
        assertEquals(201, e2e.call("POST", "/api/strutture", value, """{"name":"Casa del bosco","place":"Valle sintetica"}""").statusCode())
        assertEquals(422, e2e.call("POST", "/api/strutture", value, """{"name":"","place":""}""").statusCode())
        assertEquals(404, e2e.call("GET", "/api/strutture/999999", value).statusCode())
        assertEquals(401, e2e.call("GET", "/api/strutture", "talita_" + "A".repeat(43)).statusCode())
        assertEquals(0, e2e.dsl().fetchCount(API_REQUEST, API_REQUEST.STATUS.eq(401)), "a 401 leaves no line")

        e2e.open("/profilo")
        page.waitForSettledContent()
        register.locator("summary").click()
        assertThat(register).containsText("POST strutture · 201")
        assertThat(register).containsText("POST strutture · 422")
        assertThat(register).containsText("Rifiutata: i dati non sono validi.")
        assertThat(register).containsText("Le richieste si tengono per 30 giorni.")
        assertThat(register).containsText("Non dicono chi le ha fatte né cosa contenevano.")
        assertThat(register).not().containsText("Casa del bosco")
        assertThat(register).not().containsText("Valle sintetica")
        assertThat(register).not().containsText("Marta")
        e2e.check("the register with lines")
        // Cleans what the test made on the venue (the venues are shared by the tests of a run).
        val venueId = e2e.dsl().select(talita.jooq.tables.references.VENUE.ID).from(talita.jooq.tables.references.VENUE).where(talita.jooq.tables.references.VENUE.NAME.eq("Casa del bosco")).fetchOne(talita.jooq.tables.references.VENUE.ID)
        if (venueId != null) assertEquals(200, e2e.call("DELETE", "/api/strutture/$venueId", value).statusCode())
    }

    // ---- Criterion 5: who has the card ---------------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t21 c05 a parent has no card for the token and the two addresses of the token are a 403 even by hand`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")
        page.waitForSettledContent()
        assertEquals(0, page.locator("#token").count())
        assertEquals(0, page.getByText("Token per le API").count())
        assertEquals(0, page.getByText("Revoca anche il token per le API.").count())
        e2e.check("the profile of a parent")
        val csrf = page.locator("input[name=_csrf]").first().getAttribute("value")!!
        for (path in listOf("/profilo/token", "/profilo/token/revoca")) {
            val response = page.request().post(e2e.url(path), com.microsoft.playwright.options.RequestOptions.create().setForm(com.microsoft.playwright.options.FormData.create().set("_csrf", csrf)))
            assertEquals(403, response.status(), "$path by hand")
        }
        assertEquals(0, e2e.dsl().fetchCount(API_TOKEN, API_TOKEN.ACCOUNT_ID.eq(e2e.accountOfPage())))
        // The APIs: a parent has no token, and the session of a parent does not open them.
        assertEquals(401, page.request().get(e2e.url("/api/convivenza")).status())
    }

    @E2eTest
    fun `t21 c05 a teacher without a passkey makes the token with one tap and the session of the browser does not open the APIs`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")
        page.waitForSettledContent()
        val card = page.locator("#token")
        assertEquals(0, card.locator("[data-stato]").count(), "no sentence about the phone without a passkey")
        e2e.press(card.buttonNamed("Crea un token"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        // The page of the browser has a session: it does not open the APIs, and no answer of them sets a cookie.
        val noToken = page.request().get(e2e.url("/api/convivenza"))
        assertEquals(401, noToken.status())
        assertEquals(null, noToken.headers()["set-cookie"])
        assertEquals("application/json", noToken.headers()["content-type"]!!.substringBefore(";"))
    }

    // ---- The words of the account and of the teachers ----------------------------------------------------------------------------------------

    @E2eTest
    fun `t21 c05 c29 the dialogs of cancelling the account and of taking a teacher away say that the token stops, and the page of the teachers has no sideways scrolling`(e2e: E2e) {
        val page = e2e.page
        // Another teacher, to see the dialog «Togli … dai maestri».
        val other = Addresses.unique("secondo-maestro")
        TestAccounts.makeTeacher(e2e.dsl(), other, "Paolo", "Verdi")
        e2e.open("/maestri")
        page.waitForSettledContent()
        val dialogs = page.locator("dialog").allTextContents().joinToString("\n")
        assertTrue(dialogs.contains("Se hai un token per le API, smette di funzionare."), "the dialog of taking oneself away")
        assertTrue(dialogs.contains("Se ha un token per le API, smette di funzionare."), "the dialog of taking another teacher away")
        e2e.check("the page of the teachers")

        e2e.open("/profilo")
        page.waitForSettledContent()
        val html = page.content()
        assertTrue(html.contains("Il token per le API, se ne hai uno: smette di funzionare.") || page.locator("dialog").allTextContents().joinToString(" ").contains("token per le API"), "the dialog of cancelling the account mentions the token")
    }

    @E2eTest
    fun `t21 c28 the notice of the data says in one sentence what a teacher can make with the token`(e2e: E2e) {
        e2e.open("/profilo")
        e2e.page.waitForSettledContent()
        val text = e2e.page.locator("main").innerText().replace(Regex("\\s+"), " ")
        assertNotNull(text)
        // The three sentences of the card that the design asks for, in the words of the design.
        assertTrue(text.contains("Il token è una chiave che lascia lavorare un programma sulla configurazione di Talità"))
        assertTrue(text.contains("Leggere o scrivere i dati di bambini, genitori e maestri."))
        assertTrue(text.contains("Aprire o chiudere le iscrizioni, o mandare i promemoria: lo fai tu dall’app."))
        // Claude is not named in the interface.
        assertFalse(text.contains("Claude"), "the interface does not name a tool")
    }
}
