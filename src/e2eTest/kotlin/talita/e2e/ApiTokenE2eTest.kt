package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The token for the APIs in a real browser, on a phone and on a desktop (TALITA-21, criterion 29): the card in the profile of a teacher, the answer that makes the token and shows it once, the line to save, the
 * dialogs of «Revoca» and of «Crea un token nuovo», and that **the token really works with an HTTP client on a route, and after «Revoca» the same request is a 401**. axe on every state, and no sideways scrolling
 * at 320 px, 360 px and on the desktop. The token is the one that the page makes for the test; the teacher, the address and the names are synthetic.
 */
@E2eApplication
class ApiTokenE2eTest {

    private fun Page.sideways(e2e: E2e, what: String) {
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling")
        setViewportSize(320, 800)
        assertTrue(noHorizontalScroll(), "$what: no sideways scrolling at 320 px")
        setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
    }

    private fun Page.openDialog(): Locator = locator("dialog[open]")

    private fun Page.waitForSettledDialog() {
        waitUntil("document.querySelector('dialog[open]') && document.querySelector('dialog[open]').getAnimations().every(a => a.playState === 'finished')")
    }

    private fun E2e.press(target: Locator) {
        target.evaluate("e => e.scrollIntoView({ block: 'center' })")
        touch(target)
    }

    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    private fun E2e.call(path: String, token: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(url(path))).GET().apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @E2eTest
    fun `t21 c29 a teacher makes the token, uses it with an HTTP client, makes a new one that replaces it, revokes it and gets a 401`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")
        page.waitForSettledContent()
        val card = page.locator("#token")
        assertThat(card.locator("h2")).hasText("Token per le API")
        assertThat(card).containsText("Non hai nessun token.")
        assertThat(card).containsText("Aprire o chiudere le iscrizioni, o mandare i promemoria: lo fai tu dall’app.")
        page.sideways(e2e, "profile without a token (${e2e.profile.label})")
        assertAccessible(page, "profile without a token (${e2e.profile.label})")

        // Makes the token: the answer shows it once, with the line to save and the way to save it.
        e2e.press(card.buttonNamed("Crea un token"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        val line = card.locator("#token-riga")
        assertThat(line).containsText("Authorization: Bearer talita_")
        assertThat(card.locator("#token-comando")).containsText("umask 077; mkdir -p ~/.config/talita && cat > ~/.config/talita/dev.header")
        assertThat(card).containsText("Non incollarla in chat, email o documenti.")
        page.sideways(e2e, "the token just made (${e2e.profile.label})")
        assertAccessible(page, "the token just made (${e2e.profile.label})")
        val first = line.innerText().substringAfter("Bearer ").trim()
        assertTrue(Regex("talita_[A-Za-z0-9_-]{43}").matches(first))

        // The token works with an HTTP client, on a route of the APIs; it does not open the pages.
        assertEquals(200, e2e.call("/api/convivenza", first).statusCode())
        assertEquals(401, e2e.call("/api/convivenza", null).statusCode())
        assertTrue(e2e.call("/profilo", first).statusCode() in setOf(302, 303), "the token does not open the pages")

        // «Fatto»: the card as it is now, with the token that works and no value in the page.
        e2e.press(card.getByRole(com.microsoft.playwright.options.AriaRole.LINK, Locator.GetByRoleOptions().setName("Fatto")))
        assertThat(card).containsText("Attivo")
        assertThat(card).containsText("Poi smette di funzionare da solo.")
        assertEquals(0, page.locator("text=${first}").count(), "the value is not in the page any more")
        page.sideways(e2e, "the active token (${e2e.profile.label})")
        assertAccessible(page, "the active token (${e2e.profile.label})")

        // A new token replaces it at once.
        e2e.press(card.buttonNamed("Crea un token nuovo"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Il token di adesso smette di funzionare subito.")
        assertAccessible(page, "dialog of a new token (${e2e.profile.label})")
        e2e.touch(page.openDialog().buttonNamed("Crea il token nuovo"))
        assertThat(card.locator("[role=status]").first()).containsText("Token creato.")
        val second = card.locator("#token-riga").innerText().substringAfter("Bearer ").trim()
        assertTrue(first != second)
        assertEquals(401, e2e.call("/api/convivenza", first).statusCode())
        assertEquals(200, e2e.call("/api/convivenza", second).statusCode())

        // «Revoca»: the dialog, the notice, and the same request is a 401.
        e2e.press(card.getByRole(com.microsoft.playwright.options.AriaRole.LINK, Locator.GetByRoleOptions().setName("Fatto")))
        e2e.press(card.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Revoca il token creato il ")))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Il programma non potrà più fare modifiche con questo token. Quello che ha già fatto resta.")
        assertAccessible(page, "dialog of Revoca (${e2e.profile.label})")
        e2e.touch(page.openDialog().buttonNamed("Revoca il token"))
        assertThat(card.locator("[role=status]").first()).containsText("Token revocato.")
        assertEquals(401, e2e.call("/api/convivenza", second).statusCode())
        page.sideways(e2e, "after Revoca (${e2e.profile.label})")
        assertAccessible(page, "after Revoca (${e2e.profile.label})")
        // The register has the requests that the token made, with no word on who made them.
        e2e.open("/profilo")
        val register = page.locator("#token-registro")
        register.locator("summary").click()
        assertThat(register).containsText("GET convivenza · 200")
        assertThat(register).containsText("Andata a buon fine.")
        assertAccessible(page, "the register of the requests (${e2e.profile.label})")
    }
}
