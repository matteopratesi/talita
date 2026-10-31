package talita.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.mail.Mailbox
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The way in with the link, in a real browser, on a phone and on a desktop (criteria 7-29): the
 * access page and its states, "Controlla la posta", the confirmation, the link that does not work,
 * the profile and the exit, the session that ends, the network that goes away.
 */
@E2eApplication
class LoginE2eTest {

    private fun Page.field() = getByLabel("Indirizzo email")

    private fun Page.button(name: String) = getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName(name).setExact(true))

    private fun Page.link(name: String) = getByRole(AriaRole.LINK, Page.GetByRoleOptions().setName(name))

    /** Writes the address and taps "Ricevi il link". */
    private fun askForLink(e2e: E2e, address: String, page: Page = e2e.page) {
        page.field().fill(address)
        e2e.touch(page.button("Ricevi il link"))
    }

    /** The path of the link that came by mail for [address], on the app under test. */
    private fun linkPath(address: String): String {
        val link = Mailbox.waitForMail(address).links().single()
        assertTrue(link.startsWith(TestcontainersConfiguration.PUBLIC_URL), link)
        return link.removePrefix(TestcontainersConfiguration.PUBLIC_URL)
    }

    // ---- The access page (criteria 7 and 8) -----------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c07 c08 whoever opens the home without access finds the page of access, with the mark, no navigation and a field for the address`(e2e: E2e) {
        val page = e2e.page

        e2e.open("/")

        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page).hasTitle("Accedi · Talità")
        assertThat(page.locator("h1")).hasText("Accedi a Talità")
        // Only the mark, which is not a link: it would lead to where the person already is. No navigation, no profile.
        assertThat(page.locator("header")).containsText("Talità")
        assertEquals(0, page.locator("header a").count())
        assertEquals(0, page.locator("nav").count())
        assertEquals(0, page.getByText("Profilo", Page.GetByTextOptions().setExact(true)).count())
        // The column is narrow and centered.
        val main = page.locator("main > div").box()
        assertTrue(main.width <= 448 + 1, "the column is ${main.width} px wide")
        // A visible label, a field for an address, the right keyboard, one primary button as wide as the card.
        assertThat(page.field()).isVisible()
        assertEquals("email", page.field().attr("type"))
        assertEquals("email", page.field().attr("autocomplete"))
        assertEquals("none", page.field().attr("autocapitalize"))
        assertEquals("off", page.field().attr("autocorrect"))
        assertEquals("false", page.field().attr("spellcheck"))
        assertEquals(1, page.locator("main button[type=submit]").count())
        val card = page.locator("main form").box()
        val button = page.button("Ricevi il link").box()
        assertTrue(button.width >= card.width - 2 * 25, "the button is ${button.width} px wide in a card of ${card.width}")
        assertTrue(button.height >= 48, "the button is ${button.height} px tall")
        // The keyboard does not rise on its own (it would cover the introduction on a phone).
        assertNotEquals("email", page.evaluate("document.activeElement.id"))
        assertAccessible(page, "access page (${e2e.profile.label})")
    }

    @E2eTest(signedIn = false)
    fun `c07 every address that is not public leads to the access page, an address that does not exist too`(e2e: E2e) {
        for (path in listOf("/iscrizioni", "/profilo", "/maestri", "/pagina-che-non-esiste")) {
            e2e.open(path)
            assertThat(e2e.page).hasURL(e2e.url("/accedi"))
        }
    }

    @E2eTest(signedIn = false)
    fun `c07 the error pages that anyone can reach lead to the access page and not to the home`(e2e: E2e) {
        val page = e2e.page

        val response = e2e.open("/accedi/non-esiste")

        assertEquals(404, response.status())
        assertThat(page.locator("h1")).hasText("Pagina non trovata")
        assertEquals(0, page.locator("nav").count())
        assertThat(page.link("Vai all’accesso")).hasAttribute("href", "/accedi")
        assertAccessible(page, "404 without access (${e2e.profile.label})")
    }

    // ---- Asking for the link (criteria 9-11) ------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c09 a wrong address leaves the page as it is with the error, the field filled in and the focus on it, and no new entry in the history`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/accedi")
        val entries = page.evaluate("history.length") as Number

        askForLink(e2e, "nome@example")

        assertThat(page.getByText("Scrivi un indirizzo email completo, per esempio nome@example.org.")).isVisible()
        assertThat(page.field()).hasValue("nome@example")
        assertThat(page.field()).hasAttribute("aria-invalid", "true")
        assertThat(page.field()).hasAttribute("aria-describedby", "email-aiuto email-errore")
        assertThat(page).hasURL(e2e.url("/accedi"))
        page.waitUntil("document.activeElement && document.activeElement.id === 'email'")
        assertEquals(entries.toInt(), (page.evaluate("history.length") as Number).toInt(), "the failed attempt added an entry to the history")
        // A single error: no notice above the form.
        assertEquals(0, page.locator("main [data-fuoco]").count())
        assertEquals(0, page.getByText("Qualcosa non ha funzionato").count())
        assertAccessible(page, "access page with a wrong address (${e2e.profile.label})")

        // Written right, it goes on.
        val address = Addresses.unique("corretto")
        askForLink(e2e, address)
        assertThat(page.locator("h1")).hasText("Controlla la posta")
    }

    @E2eTest(signedIn = false)
    fun `c10 c11 c12 c13 from the address to the home with the link of the mail, an address with capitals and spaces too`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("percorso")
        e2e.open("/accedi")

        askForLink(e2e, "  " + address.replaceFirstChar { it.uppercase() }.replace("example.org", "Example.ORG") + " ")

        // «Controlla la posta» repeats the address (in lowercase), and never puts it in the address of the page.
        assertThat(page).hasURL(e2e.url("/accedi/controlla-la-posta"))
        assertThat(page).hasTitle("Controlla la posta · Talità")
        assertThat(page.locator("h1")).hasText("Controlla la posta")
        assertThat(page.locator("main")).containsText("Abbiamo mandato un link a $address. Toccalo per entrare.")
        assertThat(page.locator("main")).containsText("Apri la mail da questo telefono o computer: se la apri su un altro, entri lì.")
        assertThat(page.locator("main")).containsText("Non la trovi? Guarda anche nello spam o nella posta indesiderata.")
        assertThat(page.locator("main")).containsText("Il link vale 15 minuti e si usa una volta sola.")
        assertFalse(page.url().contains(address), "the address is in the URL")
        assertThat(page.link("Chiedi un altro link")).hasAttribute("href", "/accedi")
        assertAccessible(page, "check the mail (${e2e.profile.label})")

        // The mail, from Mailpit: for the lowercase address, with a link of the site.
        val path = linkPath(address)
        e2e.open(path)

        // Opening the link does not enter: the page asks for a tap, and says nothing about the person.
        assertThat(page.locator("h1")).hasText("Conferma l’accesso")
        assertThat(page).hasTitle("Conferma l’accesso · Talità")
        assertThat(page.locator("main")).containsText("Tocca «Entra» per accedere a Talità da qui.")
        assertThat(page.locator("main")).containsText("Non hai chiesto tu questo link? Chiudi la pagina: finché non tocchi «Entra», nessuno entra.")
        assertEquals(0, page.getByText(address).count())
        assertAccessible(page, "confirm access (${e2e.profile.label})")
        page.reload()
        assertThat(page.locator("h1")).hasText("Conferma l’accesso")

        e2e.touch(page.button("Entra"))

        // In: the home, with the profile in the header, and the navigation.
        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertThat(page.link("Profilo")).isVisible()
        assertThat(page.mainNavigation()).isVisible()
        // The profile says with which address the person entered.
        e2e.touch(page.link("Profilo"))
        assertThat(page.locator("main")).containsText(address)
    }

    // ---- The link that does not work (criterion 14) ---------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c14 a link that was used says Il link non vale più and offers to ask for another`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("riusato")
        e2e.open("/accedi")
        askForLink(e2e, address)
        val path = linkPath(address)
        e2e.open(path)
        e2e.touch(page.button("Entra"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")

        // The same link on another device: it was used already.
        val other = e2e.newPage(signedIn = false)
        other.navigate(e2e.url(path))
        e2e.touch(other.button("Entra"))

        assertThat(other).hasURL(e2e.url("/accedi/link-scaduto"))
        assertThat(other).hasTitle("Il link non vale più · Talità")
        assertThat(other.locator("h1")).hasText("Il link non vale più")
        assertThat(other.locator("main")).containsText("Può essere già stato usato, oppure sono passati più di 15 minuti.")
        assertThat(other.locator("main")).containsText("Se hai già usato il link su un altro telefono, sei già dentro lì.")
        assertAccessible(other, "link no longer valid (${e2e.profile.label})")
        e2e.touch(other.link("Chiedi un nuovo link"))
        assertThat(other).hasURL(e2e.url("/accedi"))

        // In the browser where the person is inside, the same link leads home and never says that it does not work.
        page.navigate(e2e.url(path))
        e2e.touch(page.button("Entra"))
        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
    }

    @E2eTest(signedIn = false)
    fun `c19 a link asked on one device and opened on another lets in the second, and the first stays outside`(e2e: E2e) {
        val first = e2e.page
        val address = Addresses.unique("due-dispositivi")
        e2e.open("/accedi")
        askForLink(e2e, address)
        assertThat(first.locator("h1")).hasText("Controlla la posta")

        val second = e2e.newPage(signedIn = false)
        second.navigate(e2e.url(linkPath(address)))
        e2e.touch(second.button("Entra"))

        assertThat(second).hasURL(e2e.url("/"))
        assertThat(second.link("Profilo")).isVisible()
        first.navigate(e2e.url("/iscrizioni"))
        assertThat(first).hasURL(e2e.url("/accedi"))
    }

    @E2eTest(signedIn = false)
    fun `c19 the page that was asked for without access is where the person lands after the link`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("ritorno")
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/accedi"))
        askForLink(e2e, address)
        assertThat(page.locator("h1")).hasText("Controlla la posta")

        page.navigate(e2e.url(linkPath(address)))
        e2e.touch(page.button("Entra"))

        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
    }

    // ---- The limits and the mail that does not leave (criteria 15 and 16) ---------------------------------------------

    @E2eTest(signedIn = false)
    fun `c16 the fourth link for the same address in a few minutes does not start and the page tells it`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("troppi")
        repeat(3) {
            e2e.open("/accedi")
            askForLink(e2e, address)
            assertThat(page.locator("h1")).hasText("Controlla la posta")
        }
        e2e.open("/accedi")

        askForLink(e2e, address)

        val notice = page.locator("main [role=alert]")
        assertThat(notice).containsText("Hai chiesto più link in pochi minuti. Controlla la posta, anche nello spam")
        assertThat(page.field()).hasValue(address)
        assertThat(page).hasURL(e2e.url("/accedi"))
        // The focus goes to the notice, which a screen reader reads.
        page.waitUntil("document.activeElement && document.activeElement.hasAttribute('data-fuoco')")
        assertAccessible(page, "too many links (${e2e.profile.label})")
        assertEquals(3, Mailbox.sentTo(address).size)
    }

    // ---- Profile and exit (criteria 21 and 22) ---------------------------------------------------------------------------

    @E2eTest
    fun `c21 the profile is two taps away from every page, with the address and the way out`(e2e: E2e) {
        val page = e2e.page
        val address = checkNotNull(e2e.signedInAs)

        for (shellPage in shellPages) {
            e2e.open(shellPage.path)

            e2e.touch(page.link("Profilo"))

            assertThat(page).hasURL(e2e.url("/profilo"))
            assertThat(page.locator("h1")).hasText("Profilo")
            assertThat(page.button("Esci")).isVisible()
        }
        assertThat(page.locator("main")).containsText(address)
        assertThat(page.locator("main")).containsText("Se usi un telefono o un computer di altri, esci quando hai finito.")
        // On the profile page the pill is the current entry.
        assertThat(page.link("Profilo")).hasAttribute("aria-current", "page")
        assertAccessible(page, "profile (${e2e.profile.label})")
    }

    @E2eTest
    fun `c21 the pill of the profile fits next to the mark, also on the narrowest phone and in the header of the desktop`(e2e: E2e) {
        for (width in listOf(1280, 360, 320, 300)) {
            val page = e2e.newPage { setViewportSize(width, 700) }
            page.navigate(e2e.url("/"))

            val pill = page.link("Profilo")
            assertThat(pill).isVisible()
            val brand = page.locator("header a[href='/']").box()
            val box = pill.box()
            val header = page.locator("header").box()
            assertTrue(brand.x + brand.width <= box.x + 0.5, "$width px: the pill overlaps the mark ($brand, $box)")
            assertTrue(box.x + box.width <= header.x + header.width + 0.5, "$width px: the pill leaves the header ($box, $header)")
            assertTrue(box.height >= 44 - 0.5, "$width px: the pill is ${box.height} px tall")
            // Under 320 px the word goes away (a screen reader still has it), not the circle.
            val word = page.locator("header a[href='/profilo'] span").last().box()
            assertEquals(width >= 320, word.width > 2, "$width px: the word «Profilo» is ${word.width} px wide")
        }
    }

    @E2eTest
    fun `c22 the exit leads to the access page with the confirmation, once, and the back button does not show the pages of before`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/iscrizioni")
        e2e.touch(page.link("Profilo"))

        e2e.touch(page.button("Esci"))

        assertThat(page).hasURL(e2e.url("/accedi"))
        val notice = page.locator("main [role=status]")
        assertThat(notice).containsText("Accesso chiuso. Per rientrare, chiedi un nuovo link.")
        page.waitUntil("document.activeElement && document.activeElement.hasAttribute('data-fuoco')")
        assertAccessible(page, "access page after the exit (${e2e.profile.label})")
        // Once: a reload does not say it again.
        page.reload()
        assertEquals(0, page.getByText("Accesso chiuso").count())
        // Back: not the pages of before, which are not kept.
        page.goBack()
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertEquals(0, page.locator("nav").count(), "the navigation of someone who is inside is on the screen")
        page.goBack()
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertEquals(0, page.locator("nav").count())
        assertEquals(0, page.getByText("Ti diamo il benvenuto").count())
    }

    // ---- The session that ends, the code that expires (criteria 23 and 24) --------------------------------------------------------

    @E2eTest
    fun `c23 after two hours without use a touch leads to the access page as a whole page, with a notice, never a message of failure`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.evaluate("window.__marker = 'prima'")
        // The server treats the session as it does after two hours: one second of rest is enough.
        e2e.page.request().get(e2e.url("/test/sessione-breve"))
        page.waitForTimeout(1500.0)

        e2e.touch(page.navLink("Iscrizioni"))

        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.locator("h1")).hasText("Accedi a Talità")
        // A whole page: the old one is gone, and what was put in it with it.
        assertEquals(null, page.evaluate("window.__marker"))
        assertThat(page.locator("main")).containsText("L’accesso si chiude da solo dopo 2 ore che non usi Talità, e comunque dopo 12 ore dall’entrata. Per rientrare, chiedi un nuovo link.")
        assertEquals(0, page.messages().count())
        assertEquals(0, page.locator("nav").count())
        assertAccessible(page, "access page after a session that ended (${e2e.profile.label})")
    }

    @E2eTest
    fun `c23 the same for a piece of a page that htmx asks for, which does not put the access page inside the old one`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")
        page.addToContent("""<button id="pezzo" type="button" hx-get="/test/frammento" hx-target="#bersaglio">Carica</button><div id="bersaglio">Contenuto di prima</div>""")
        e2e.page.request().get(e2e.url("/test/sessione-breve"))
        page.waitForTimeout(1500.0)

        e2e.touch(page.locator("#pezzo"))

        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.locator("h1")).hasText("Accedi a Talità")
        assertEquals(0, page.locator("#bersaglio").count())
        assertEquals(0, page.messages().count())
        assertEquals(1, page.locator("h1").count())
    }

    @E2eTest(signedIn = false)
    fun `c24 a page of access left open for too long comes back with the field filled in, a notice and a new code, and then works`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("scaduta")
        e2e.open("/accedi")
        // The session of the page is gone (the server restarted, or two hours went by): the code in the form is no longer known.
        e2e.browserContext.clearCookies()

        askForLink(e2e, address)

        val notice = page.locator("main [role=alert]")
        assertThat(notice).hasText("La pagina era aperta da troppo tempo. Riprova.")
        assertThat(page.field()).hasValue(address)
        assertEquals(0, page.getByText("Non puoi vedere questa pagina").count())
        assertEquals(0, page.messages().count())
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertAccessible(page, "access page with the code expired (${e2e.profile.label})")

        e2e.touch(page.button("Ricevi il link"))

        assertThat(page.locator("h1")).hasText("Controlla la posta")
    }

    @E2eTest(signedIn = false)
    fun `c24 the page of the link left open for too long comes back with the notice, and its link is still good`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("conferma-scaduta")
        e2e.open("/accedi")
        askForLink(e2e, address)
        page.navigate(e2e.url(linkPath(address)))
        e2e.browserContext.clearCookies()

        e2e.touch(page.button("Entra"))

        assertThat(page.locator("main [role=alert]")).hasText("La pagina era aperta da troppo tempo. Riprova.")
        assertThat(page.locator("h1")).hasText("Conferma l’accesso")

        e2e.touch(page.button("Entra"))

        assertThat(page).hasURL(e2e.url("/"))
    }

    // ---- Headers (criterion 25) ----------------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c25 the pages have the Content-Security-Policy and the browser has nothing to say about it`(e2e: E2e) {
        val response = e2e.open("/accedi")

        val policy = checkNotNull(response.headerValue("content-security-policy"))
        assertTrue(policy.contains("default-src 'self'") && policy.contains("script-src 'self'") && policy.contains("style-src 'self'"), policy)
        assertFalse(policy.contains("unsafe-inline") || policy.contains("unsafe-eval"), policy)
        assertEquals("same-origin", response.headerValue("referrer-policy"))
        assertEquals("nosniff", response.headerValue("x-content-type-options"))
        // The whole flow of the other tests runs with the console watched: a violation of the policy fails them.
        askForLink(e2e, Addresses.unique("csp"))
        assertThat(e2e.page.locator("h1")).hasText("Controlla la posta")
    }

    // ---- No network (criterion 27) ------------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c27 without network the field stays filled in with the message Nessuna connessione, and the button works again when the network is back`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("rete")
        e2e.open("/accedi")
        page.field().fill(address)
        e2e.browserContext.setOffline(true)

        e2e.touch(page.button("Ricevi il link"))

        val message = page.messages()
        assertThat(message).hasCount(1)
        assertThat(message).containsText("Nessuna connessione")
        // An invio: nothing to «Riprova», the button is enough.
        assertEquals(0, message.locator("[data-azione=riprova]").count())
        assertThat(page.field()).hasValue(address)
        assertThat(page.button("Ricevi il link")).isEnabled()
        assertAccessible(page, "no network on the access page (${e2e.profile.label})")

        e2e.browserContext.setOffline(false)
        e2e.touch(page.button("Ricevi il link"))

        assertThat(page.locator("h1")).hasText("Controlla la posta")
        assertEquals(0, page.messages().count())
    }

    @E2eTest(signedIn = false)
    fun `c27 without network the confirmation stays as it is and the link is still good, and a double tap lets in once`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("doppio")
        e2e.open("/accedi")
        askForLink(e2e, address)
        page.navigate(e2e.url(linkPath(address)))
        e2e.browserContext.setOffline(true)

        e2e.touch(page.button("Entra"))

        assertThat(page.messages()).hasCount(1)
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(0, page.messages().locator("[data-azione=riprova]").count())
        assertThat(page.locator("h1")).hasText("Conferma l’accesso")
        assertThat(page.button("Entra")).isEnabled()

        e2e.browserContext.setOffline(false)
        // Two taps at once, as a finger that bounces does: the second finds the button disabled.
        page.evaluate("() => { const b = document.querySelector('main button[type=submit]'); b.click(); b.click(); }")

        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertEquals(0, page.getByText("Il link non vale più").count())
    }

    // ---- Without JavaScript (the forms are real forms) ---------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `without JavaScript the whole way in and out works with real forms`(e2e: E2e) {
        val page = e2e.newPage(signedIn = false) { setJavaScriptEnabled(false) }
        val address = Addresses.unique("senza-js")
        // Without JavaScript Playwright cannot tell that an element is still while the page enters: it is let to finish.
        page.navigate(e2e.url("/accedi"))
        page.waitForSettledContent()

        page.field().fill(address)
        page.button("Ricevi il link").click()
        assertThat(page.locator("h1")).hasText("Controlla la posta")
        page.navigate(e2e.url(linkPath(address)))
        page.waitForSettledContent()
        page.button("Entra").click()
        assertThat(page).hasURL(e2e.url("/"))
        page.link("Profilo").click()
        page.waitForSettledContent()
        page.button("Esci").click()

        assertThat(page).hasURL(e2e.url("/accedi"))
        page.waitForSettledContent()
        assertThat(page.locator("main [role=status]")).containsText("Accesso chiuso. Per rientrare, chiedi un nuovo link.")
        // Wrong address without JavaScript: the same page, with the error.
        page.field().fill("nome")
        page.button("Ricevi il link").click()
        assertThat(page.getByText("Scrivi un indirizzo email completo, per esempio nome@example.org.")).isVisible()
        assertEquals("nome", page.field().inputValue())
    }
}
