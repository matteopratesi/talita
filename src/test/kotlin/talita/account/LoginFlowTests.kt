package talita.account

import jakarta.servlet.http.Cookie
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import talita.Addresses
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.ONE_TIME_TOKENS
import talita.mail.MailQuota
import talita.mail.Mailbox
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Access with the link, as a browser does it, against the whole application (criteria 7-24): the
 * pages, the mail that Mailpit receives, the session. Every test uses its own addresses.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class LoginFlowTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: MutableClock,
    @Autowired private val quota: MailQuota,
) {

    private val signIn = MockSignIn(mockMvc, tokens, dsl)

    private fun browser() = MockBrowser(mockMvc)

    private fun body(result: MvcResult) = result.response.contentAsString

    private fun location(result: MvcResult) = result.response.getHeader("Location")

    private fun title(html: String) = Regex("""<title>(.*?)</title>""").find(html)?.groupValues?.get(1)

    private fun heading(html: String) = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()

    /** Asks for a link on the page of access. */
    private fun requestLink(browser: MockBrowser, typed: String): MvcResult = browser.submit("/accedi", "/accedi", mapOf("email" to typed))

    /** The link in the mail that arrived for [address], as the token. */
    private fun tokenFromMail(address: String): String {
        val link = Mailbox.waitForMail(address).links().single()
        assertTrue(link.startsWith(TestcontainersConfiguration.PUBLIC_URL + "/accedi/conferma?token="), link)
        return link.substringAfter("token=")
    }

    /** The page that the browser gets for [path], as text. */
    private fun MockBrowser.page(path: String): String = body(get(path))

    /** Where the browser is sent when it asks for [path]: the `Location` of the answer. */
    private fun MockBrowser.redirectOf(path: String): String? = location(get(path))

    private fun tokenCount(): Int = dsl.fetchCount(ONE_TIME_TOKENS)

    private fun accountCount(email: String): Int = dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.eq(email))

    // ---- B1. Who can open what (criteria 7 and 8) ------------------------------------------------------

    @Test
    fun `t3 c07 a person without access who opens an address that is not public goes to the access page`() {
        for (path in listOf("/", "/iscrizioni", "/profilo", "/maestri", "/convivenza", "/arrivi", "/pagina-che-non-esiste", "/ott/generate", "/login", "/actuator/info")) {
            val result = mockMvc.get(path).andReturn()

            assertEquals(302, result.response.status, path)
            assertEquals("/accedi", location(result), path)
        }
    }

    @Test
    fun `t3 c07 the access pages, the health, the error page and the static files open without access, with the cache of today`() {
        val access = body(mockMvc.get("/accedi").andReturn())
        for (path in listOf("/accedi", "/accedi/controlla-la-posta", "/accedi/link-scaduto", "/actuator/health", "/error")) {
            val status = mockMvc.get(path).andReturn().response.status
            assertTrue(status == 200 || path == "/error", "$path answered $status")
        }

        val css = Regex("""href="(/css/app-[0-9a-f]+\.css)"""").find(access)?.groupValues?.get(1)
        assertNotNull(css)
        val response = mockMvc.get(css).andReturn().response
        assertEquals(200, response.status)
        assertTrue(response.getHeader("Cache-Control").orEmpty().contains("max-age=31536000"), response.getHeader("Cache-Control"))
    }

    @Test
    fun `t3 c07 the public pages have the mark and no navigation`() {
        for (path in listOf("/accedi", "/accedi/controlla-la-posta", "/accedi/link-scaduto", "/accedi/conferma?token=non-importa")) {
            val html = body(mockMvc.get(path).andReturn())

            assertTrue(html.contains("""<path d="M10.5 9h11M16 9v14""""), "$path has no mark")
            assertFalse(html.contains("<nav"), "$path has a navigation")
            assertFalse(html.contains("""href="/profilo""""), "$path has the profile pill")
            // The mark is not a link: it would lead to the page where the person already is.
            assertFalse(Regex("""<a href="/"[^>]*>\s*<svg""").containsMatchIn(html), "$path has the mark as a link")
        }
    }

    @Test
    fun `t3 c08 the page of access has a field with a visible label, for an address, and one primary button`() {
        val html = body(mockMvc.get("/accedi").andReturn())

        assertEquals("Accedi · Talità", title(html))
        assertEquals("Accedi a Talità", heading(html))
        assertTrue(html.contains("""<label for="email" class="block font-semibold">Indirizzo email</label>"""))
        val field = Regex("""<input id="email"[^>]*>""").find(html)?.value
        assertNotNull(field)
        for (attribute in listOf("""name="email"""", """type="email"""", """autocomplete="email"""", """autocapitalize="none"""", """autocorrect="off"""", """spellcheck="false"""", """aria-describedby="email-aiuto"""")) {
            assertTrue(field.contains(attribute), "the field has no $attribute: $field")
        }
        // The errors are those of Talità, the same for everyone: not the bubble of the browser.
        assertTrue(Regex("""<form [^>]*novalidate""").containsMatchIn(html))
        assertFalse(field.contains("required") || field.contains("maxlength") || field.contains("autofocus"), field)
        assertEquals(1, Regex("""<button type="submit"""").findAll(html).count())
        assertTrue(html.contains("Ricevi il link"))
        assertTrue(html.contains("Ti mandiamo un link per entrare, senza password. Se è la prima volta, va bene lo stesso: non devi creare niente."))
        assertTrue(html.contains("Usa sempre lo stesso indirizzo: è il tuo accesso a Talità."))
    }

    @Test
    fun `t3 c08 the access pages use no word that the guide forbids`() {
        // «utente», «login», «sessione», «server», «database»: the guide gives other words (6, Tono). In pieces: this file is under src/.
        val forbidden = listOf("ute" + "nte", "lo" + "gin", "ses" + "sione", "ser" + "ver", "data" + "base", "campo obbli" + "gatorio", "koi" + "nè", "cen" + "tro", "comuni" + "tà", "fra" + "telli")
        val token = signIn.link(Addresses.unique("parole"))
        val pages = listOf("/accedi", "/accedi/controlla-la-posta", "/accedi/link-scaduto", "/accedi/conferma?token=$token")

        for (path in pages) {
            val text = Regex("""<main.*?</main>""", RegexOption.DOT_MATCHES_ALL).find(body(mockMvc.get(path).andReturn()))!!.value
            for (word in forbidden) assertFalse(text.contains(word, ignoreCase = true), "'$word' is in $path")
        }
    }

    @Test
    fun `t3 c08 a person who is already inside and opens the page of access goes to the home`() {
        val session = signIn.session()

        val result = mockMvc.get("/accedi") { this.session = session }.andReturn()

        assertEquals(302, result.response.status)
        assertEquals("/", location(result))
    }

    // ---- B2. Asking for the link (criteria 9-11) -----------------------------------------------------------

    @Test
    fun `t3 c09 a wrong address sends the page back, filled in, with the error and nothing starts`() {
        val before = tokenCount()
        val typed = listOf("", "nome", "nome@", "nome@example", "nome cognome" + "@example.org", "città" + "@example.org", "a".repeat(250) + "@example.org")

        for (value in typed) {
            val result = requestLink(browser(), value)
            val html = body(result)

            assertEquals(422, result.response.status, "'$value'")
            assertTrue(html.contains("Scrivi un indirizzo email completo, per esempio nome@example.org."), "'$value'")
            assertTrue(html.contains("""aria-invalid="true""""), "'$value'")
            assertTrue(html.contains("""aria-describedby="email-aiuto email-errore""""), "'$value'")
            assertTrue(html.contains("""value="${value.replace("&", "&amp;")}""""), "the field is not filled in as it was written: '$value'")
            // A single error: no notice above the form.
            assertFalse(html.contains("data-fuoco"), "'$value'")
        }
        assertEquals(before, tokenCount(), "a link was made for an address that is wrong")
    }

    @Test
    fun `t3 c10 a valid address, with or without an account, gets the same answer, the page Controlla la posta, and the mail with the link`() {
        val existing = Addresses.unique("conto")
        signIn.session(existing)
        val new = Addresses.unique("nuovo")
        assertEquals(0, accountCount(new))

        val withAccount = requestLink(browser(), existing)
        val withoutAccount = requestLink(browser(), new)

        for (result in listOf(withAccount, withoutAccount)) {
            assertEquals(303, result.response.status)
            assertEquals("/accedi/controlla-la-posta", location(result))
        }
        // Nothing in the address of the page: not the address, not the link.
        assertFalse(location(withAccount)!!.contains(existing) || location(withoutAccount)!!.contains(new))
        assertEquals(0, accountCount(new), "asking for a link made an account")
        for (address in listOf(existing, new)) {
            val mail = Mailbox.waitForMail(address)
            assertEquals("Il tuo link per entrare in Talità", mail.subject)
            assertEquals(1, mail.links().size)
        }
    }

    @Test
    fun `t3 c10 Controlla la posta repeats the address once, says to open the mail on this device, to look in the spam and how to ask again`() {
        val address = Addresses.unique("posta")
        val browser = browser()
        requestLink(browser, address)

        val first = browser.page("/accedi/controlla-la-posta")
        val reload = browser.page("/accedi/controlla-la-posta")

        assertEquals("Controlla la posta · Talità", title(first))
        assertEquals("Controlla la posta", heading(first))
        assertTrue(first.contains("""Abbiamo mandato un link a <span class="font-semibold wrap-anywhere text-ink">$address</span>. Toccalo per entrare."""), first)
        for (page in listOf(first, reload)) {
            assertTrue(page.contains("Apri la mail da questo telefono o computer: se la apri su un altro, entri lì."))
            assertTrue(page.contains("Non la trovi? Guarda anche nello spam o nella posta indesiderata."))
            assertTrue(page.contains("Il link vale 15 minuti e si usa una volta sola."))
            assertTrue(page.contains("Hai sbagliato indirizzo o la mail non arriva?"))
            assertTrue(Regex("""<a href="/accedi"[^>]*>Chiedi un altro link</a>""").containsMatchIn(page))
        }
        // A reload, or a page opened by hand, does not know the address.
        assertTrue(reload.contains("Abbiamo mandato un link al tuo indirizzo. Toccalo per entrare."))
        assertFalse(reload.contains(address))
        assertFalse(body(mockMvc.get("/accedi/controlla-la-posta").andReturn()).contains(address))
    }

    @Test
    fun `t3 c11 an address written with capitals and spaces gets the mail in lowercase and is the same account as the lowercase one`() {
        val clean = Addresses.unique("maiuscole")
        val typed = "  " + clean.replaceFirstChar { it.uppercase() }.replace("example.org", "Example.ORG") + "  "

        val first = requestLink(browser(), typed)
        assertEquals(303, first.response.status)
        val firstToken = tokenFromMail(clean)

        val device = browser()
        assertEquals(303, device.confirm(firstToken).response.status)
        // Who writes it in lowercase, afterwards, enters the same account.
        val second = browser()
        requestLink(second, clean)
        val secondToken = Mailbox.sentTo(clean).last().links().single().substringAfter("token=")
        assertNotEquals(firstToken, secondToken)
        assertEquals(303, second.confirm(secondToken).response.status)

        assertEquals(1, accountCount(clean))
        assertEquals(0, dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.ne(DSL.lower(ACCOUNT.EMAIL))))
    }

    // ---- B3. The link (criteria 12-14) -----------------------------------------------------------------

    @Test
    fun `t3 c12 opening the link, even many times as the mail programs do, shows the confirmation and does not use the link`() {
        val address = Addresses.unique("programma")
        requestLink(browser(), address)
        val token = tokenFromMail(address)
        val before = tokenCount()

        repeat(3) {
            val result = mockMvc.get("/accedi/conferma") { param("token", token) }.andReturn()
            val html = body(result)
            assertEquals(200, result.response.status)
            assertEquals("Conferma l’accesso", heading(html))
            assertTrue(html.contains("Tocca «Entra» per accedere a Talità da qui."))
            assertTrue(html.contains("Non hai chiesto tu questo link? Chiudi la pagina: finché non tocchi «Entra», nessuno entra."))
            assertTrue(Regex("""<form method="post" action="/accedi/conferma"""").containsMatchIn(html))
            assertTrue(html.contains("""<input type="hidden" name="token" value="$token">"""))
            assertTrue(html.contains("""name="_csrf""""))
            assertFalse(html.contains(address), "the confirmation shows the address")
        }

        assertEquals(before, tokenCount(), "opening the link used it")
        // And no session of someone who is inside: only the tap lets in.
        assertEquals(302, mockMvc.get("/profilo").andReturn().response.status)
        assertEquals(303, browser().confirm(token).response.status)
    }

    @Test
    fun `t3 c13 the tap on Entra with an address without an account makes the account, lets in and leads to the home`() {
        val address = Addresses.unique("primo-accesso")
        requestLink(browser(), address)
        val token = tokenFromMail(address)
        assertEquals(0, accountCount(address))
        // A link that was never used makes nothing, however many times the page is opened.
        mockMvc.get("/accedi/conferma") { param("token", token) }
        assertEquals(0, accountCount(address))

        val browser = browser()
        val tap = browser.confirm(token)

        assertEquals(303, tap.response.status)
        assertEquals("/", location(tap))
        assertEquals(1, accountCount(address))
        val home = browser.page("/")
        assertTrue(home.contains("Ti diamo il benvenuto"))
        assertTrue(home.contains("""href="/profilo""""))
    }

    @Test
    fun `t3 c14 a link that was used, expired or invented says Il link non vale più, with the button to ask for another`() {
        val address = Addresses.unique("scaduto")
        val used = signIn.link(address)
        assertEquals(303, browser().confirm(used).response.status)
        val expired = signIn.link(address)
        val invented = "0b8b3d7e-1111-4222-8333-444455556666"

        clock.advance(Duration.ofMinutes(16))

        for ((name, token) in mapOf("used" to used, "expired" to expired, "invented" to invented)) {
            val tap = browser().confirm(token)
            assertEquals(303, tap.response.status, name)
            assertEquals("/accedi/link-scaduto", location(tap), name)
        }
        val page = body(mockMvc.get("/accedi/link-scaduto").andReturn())
        assertEquals("Il link non vale più", heading(page))
        assertTrue(page.contains("Può essere già stato usato, oppure sono passati più di 15 minuti."))
        assertTrue(page.contains("Succede: chiedine uno nuovo, ci vuole un momento. Se hai già usato il link su un altro telefono, sei già dentro lì."))
        assertTrue(Regex("""<a href="/accedi"[^>]*>Chiedi un nuovo link</a>""").containsMatchIn(page))
        // A request with no link at all is a link that does not work, too.
        val without = browser().submit("/accedi", "/accedi/conferma", emptyMap())
        assertEquals(303, without.response.status)
        assertEquals("/accedi/link-scaduto", location(without))
    }

    @Test
    fun `t3 c14 in a browser where the person is already inside the link that does not work leads to the home`() {
        val address = Addresses.unique("gia-dentro")
        val browser = browser()
        val token = signIn.link(address)
        assertEquals(303, browser.confirm(token).response.status)

        // The link was used a moment ago (a second tap, the same link in another tab).
        val again = browser.confirm(token)

        assertEquals(303, again.response.status)
        assertEquals("/", location(again))
        // And the page of the expired link, opened by hand, does the same.
        assertEquals("/", browser.redirectOf("/accedi/link-scaduto"))
    }

    @Test
    fun `t3 c14 two links asked one after the other are worth one time each`() {
        val address = Addresses.unique("due-link")
        requestLink(browser(), address)
        requestLink(browser(), address)
        val links = Mailbox.sentTo(address).map { it.links().single().substringAfter("token=") }
        assertEquals(2, links.size)

        assertEquals("/", location(browser().confirm(links[0])))
        assertEquals("/", location(browser().confirm(links[1])))
        assertEquals("/accedi/link-scaduto", location(browser().confirm(links[0])))
        assertEquals("/accedi/link-scaduto", location(browser().confirm(links[1])))
    }

    /**
     * The same link confirmed by two browsers at the same moment (CVE-2026-22751, `JdbcOneTimeTokenService`, fixed in Spring Security
     * 7.0.5): both have the page of the link and a valid CSRF code, both send the tap together. One lets in, the other finds a link that
     * is already used, and the person has one account. If this ever fails it is a defect of the code, not of the test.
     */
    @Test
    fun `t3 c14 the same link confirmed by two browsers at the same moment lets in one only, with one account`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(15) { round ->
                val address = Addresses.unique("insieme-$round")
                val token = signIn.link(address)
                val browsers = listOf(browser(), browser())
                val csrfs = browsers.map { MockBrowser.csrfIn(body(it.get("/accedi/conferma?token=$token")))!! }
                val ready = CountDownLatch(2)
                val start = CountDownLatch(1)

                val answers = browsers.indices.map { index ->
                    pool.submit<String?> {
                        ready.countDown()
                        start.await()
                        location(browsers[index].post("/accedi/conferma", mapOf("token" to token), csrfs[index]))
                    }
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                val locations = answers.map { it.get(30, TimeUnit.SECONDS) }

                assertEquals(listOf("/", "/accedi/link-scaduto"), locations.sortedBy { it }, "round $round: $locations")
                assertEquals(1, accountCount(address), "round $round: accounts of the address")
                assertEquals(0, dsl.fetchCount(ONE_TIME_TOKENS, ONE_TIME_TOKENS.USERNAME.eq(address)), "round $round: the link is still in the table")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t3 c13 and a link used in a browser where somebody else is inside lets in with the address of the link`() {
        val first = Addresses.unique("primo")
        val second = Addresses.unique("secondo")
        val browser = browser()
        browser.confirm(signIn.link(first))

        val tap = browser.confirm(signIn.link(second))

        assertEquals("/", location(tap))
        assertTrue(browser.page("/profilo").contains(second))
        assertFalse(browser.page("/profilo").contains(first))
    }

    // ---- B4. Limits and mail that does not leave (criteria 16 and 17) -------------------------------------

    @Test
    fun `t3 c16 the fourth link in fifteen minutes for the same address does not start, and says to check the mail`() {
        val address = Addresses.unique("troppi")
        repeat(3) { assertEquals(303, requestLink(browser(), address).response.status) }
        assertEquals(3, Mailbox.sentTo(address).size)
        val before = tokenCount()

        val fourth = requestLink(browser(), address)

        assertEquals(429, fourth.response.status)
        val html = body(fourth)
        assertTrue(html.contains("Hai chiesto più link in pochi minuti. Controlla la posta, anche nello spam: l’ultimo dovrebbe essere arrivato. Se non c’è, riprova fra qualche minuto."))
        assertTrue(html.contains("""role="alert""""))
        assertTrue(html.contains("""value="$address""""), "the field is not filled in")
        assertEquals(before, tokenCount())
        assertEquals(3, Mailbox.sentTo(address).size)
        // Another address is not stopped, and after fifteen minutes this one is free again.
        assertEquals(303, requestLink(browser(), Addresses.unique("altro")).response.status)
        clock.advance(Duration.ofMinutes(16))
        assertEquals(303, requestLink(browser(), address).response.status)
    }

    @Test
    fun `t3 c16 after 150 mails in 24 hours no other leaves, and the page says it without technical words`() {
        // Fills what is left of the day: in this application context other tests have sent some.
        var reserved = 0
        while (quota.tryAcquire()) reserved++
        val address = Addresses.unique("limite")
        val before = tokenCount()

        val result = requestLink(browser(), address)

        assertEquals(429, result.response.status)
        assertTrue(body(result).contains("Oggi Talità ha già mandato tante mail e non può mandarne altre. Riprova domani. Se è urgente, avvisa chi organizza la convivenza."))
        assertEquals(before, tokenCount(), "a link was left that nobody has")
        assertTrue(Mailbox.sentTo(address).isEmpty())
        // The request did not count against the address: after the day the same address works at once.
        clock.advance(Duration.ofHours(25))
        assertEquals(303, requestLink(browser(), address).response.status)
        assertTrue(reserved <= 150, "reserved $reserved")
    }

    @Test
    fun `t3 c17 the address of Spring to make links does nothing, no link and no mail`() {
        val address = Addresses.unique("ott")
        val before = tokenCount()
        val signedIn = signIn.session()

        for (session in listOf(null, signedIn)) {
            val csrf = MockBrowser.csrfIn(body(mockMvc.get("/profilo") { session?.let { this.session = it } }.andReturn()))
            val result = mockMvc.post("/ott/generate") {
                session?.let { this.session = it }
                param("username", address)
                csrf?.let { param("_csrf", it) }
            }.andReturn()
            assertNotEquals(200, result.response.status)
            assertFalse(location(result).orEmpty().contains("controlla"), "it behaved as the page of access")
        }

        assertEquals(before, tokenCount(), "a link was made")
        assertEquals(0, accountCount(address))
        assertTrue(Mailbox.sentTo(address).isEmpty(), "a mail was sent")
    }

    @Test
    fun `t3 c18 the link starts with the public address of the site, whatever Host or X-Forwarded-Host say`() {
        val address = Addresses.unique("host")

        val result = browser().submit("/accedi", "/accedi", mapOf("email" to address)) {
            header("Host", "attacco.example.net")
            header("X-Forwarded-Host", "attacco.example.net")
            header("X-Forwarded-Proto", "http")
            header("Forwarded", "host=attacco.example.net;proto=http")
        }

        assertEquals(303, result.response.status)
        val link = Mailbox.waitForMail(address).links().single()
        assertTrue(link.startsWith(TestcontainersConfiguration.PUBLIC_URL + "/accedi/conferma?token="), link)
        assertFalse(link.contains("attacco"), link)
    }

    // ---- B5. Where to, after the link (criteria 19 and 20) ---------------------------------------------------

    @Test
    fun `t3 c19 a link asked on one device and opened on another lets in the second and leaves the first outside`() {
        val address = Addresses.unique("due-dispositivi")
        val phone = browser()
        val other = browser()
        requestLink(phone, address)

        assertEquals("/", location(other.confirm(tokenFromMail(address))))

        assertEquals(200, other.get("/profilo").response.status)
        assertEquals(302, phone.get("/profilo").response.status)
    }

    @Test
    fun `t3 c19 a page that was asked for without access is the one that the person finds after the link, in the same browser, and never another site`() {
        val address = Addresses.unique("ritorno")
        val browser = browser()
        val asked = browser.get("/iscrizioni") { header("Host", "attacco.example.net") }
        assertEquals("/accedi", location(asked))
        // The page of access, the same browser: the link is asked and confirmed here.
        requestLink(browser, address)

        val tap = browser.confirm(tokenFromMail(address))

        assertEquals("/iscrizioni", location(tap))
        assertEquals(200, browser.get("/iscrizioni").response.status)
        // The way back is used once: the next login leads to the home.
        val next = browser.confirm(signIn.link(address))
        assertEquals("/", location(next))
    }

    @Test
    fun `t3 c19 a page with a search is brought back with its search, a file or a piece of a page is not`() {
        val browser = browser()

        browser.get("/iscrizioni?cerca=ilaria")
        val tap = browser.confirm(signIn.link(Addresses.unique("ricerca")))
        assertEquals("/iscrizioni?cerca=ilaria", location(tap))

        // A piece of a page that htmx asks for is not a page to go back to.
        val other = browser()
        val fragment = other.get("/iscrizioni") { header("HX-Request", "true") }
        assertEquals(200, fragment.response.status)
        assertEquals("/accedi", fragment.response.getHeader("HX-Redirect"))
        assertEquals("/", location(other.confirm(signIn.link(Addresses.unique("frammento")))))
    }

    @Test
    fun `t3 c20 at the login the session changes id and nothing of the old one is kept`() {
        val address = Addresses.unique("sessione")
        val browser = browser()
        requestLink(browser, address)
        val before = browser.cookie!!.id

        browser.confirm(tokenFromMail(address))

        assertNotEquals(before, browser.cookie!!.id, "the id of the session is the same after the login")
    }

    // ---- B6. Profile and exit (criteria 21 and 22) ---------------------------------------------------------------

    @Test
    fun `t3 c21 the profile shows the address with which the person is inside and the way out`() {
        val address = Addresses.unique("profilo")
        val session = signIn.session(address, teacher = true)

        for (path in listOf("/", "/convivenza", "/iscrizioni", "/arrivi", "/profilo")) {
            val html = body(mockMvc.get(path) { this.session = session }.andReturn())
            assertTrue(Regex("""<a href="/profilo"[^>]*>[\s\S]*?Profilo[\s\S]*?</a>""").containsMatchIn(html), "$path has no profile pill")
            assertEquals(1, Regex("""href="/profilo"""").findAll(html).count(), path)
        }
        val profile = body(mockMvc.get("/profilo") { this.session = session }.andReturn())
        assertEquals("Profilo · Talità", title(profile))
        assertTrue(profile.contains(">Il tuo accesso</h2>"))
        assertTrue(profile.contains("""<dd class="font-medium wrap-anywhere">$address</dd>"""))
        assertTrue(profile.contains("""<form method="post" action="/esci""""))
        assertTrue(Regex("""Esci\s*</span>""").containsMatchIn(profile))
        assertTrue(profile.contains("Se usi un telefono o un computer di altri, esci quando hai finito."))
        // The pill of the profile is the current entry on the profile page, and only there.
        assertTrue(Regex("""<a href="/profilo" aria-current="page"""").containsMatchIn(profile))
        assertFalse(Regex("""<a href="/profilo" aria-current""").containsMatchIn(body(mockMvc.get("/") { this.session = session }.andReturn())))
        // The initials: those of name and surname of a teacher (the helper makes Elisa Conti), in capital.
        assertTrue(Regex(""">EC</span>""").containsMatchIn(profile))
    }

    @Test
    fun `t3 c22 the exit ends the session, leads to the access page with the confirmation once, and the old pages are not shown again`() {
        val browser = browser()
        browser.confirm(signIn.link(Addresses.unique("uscita")))
        val oldSession = browser.cookie!!
        val page = browser.get("/profilo")
        assertEquals(200, page.response.status)
        // The pages of someone who is inside are never kept by the browser: back does not show them again.
        val cacheControl = page.response.getHeader("Cache-Control").orEmpty()
        assertTrue(cacheControl.contains("no-store"), cacheControl)

        val exit = browser.submit("/profilo", "/esci", emptyMap())

        assertEquals(303, exit.response.status)
        assertEquals("/accedi", location(exit))
        val after = browser.page("/accedi")
        assertTrue(after.contains("Accesso chiuso. Per rientrare, chiedi un nuovo link."))
        assertTrue(after.contains("""role="status""""))
        assertFalse(browser.page("/accedi").contains("Accesso chiuso"), "the confirmation came back")
        // The old session is worth nothing.
        val old = mockMvc.get("/profilo") { session = oldSession }.andReturn()
        assertEquals(302, old.response.status)
        assertEquals("/accedi", location(old))
    }

    @Test
    fun `t3 c22 the access page after the exit never says Accesso chiuso to who did not go out`() {
        assertFalse(body(mockMvc.get("/accedi").andReturn()).contains("Accesso chiuso"))
    }

    // ---- B7. Session over, CSRF code expired (criteria 23 and 24) ------------------------------------------------------

    @Test
    fun `t3 c23 a session that the server no longer knows sends to the access page as a whole page, from a link and from a piece of page`() {
        for (htmx in listOf(false, true)) {
            val browser = browser()
            val result = browser.get("/iscrizioni") {
                cookie(Cookie("JSESSIONID", "sessione-che-non-esiste"))
                with { request ->
                    request.setRequestedSessionId("sessione-che-non-esiste")
                    request.isRequestedSessionIdValid = false
                    request
                }
                if (htmx) header("HX-Request", "true")
            }

            if (htmx) {
                // htmx reloads the page whole: not a 302 that it would follow inside a piece of page, not an error.
                assertEquals(200, result.response.status)
                assertEquals("/accedi", result.response.getHeader("HX-Redirect"))
            } else {
                assertEquals(302, result.response.status)
                assertEquals("/accedi", location(result))
            }
            // The access page then tells, once, that the access closed on its own.
            assertTrue(browser.page("/accedi").contains("L’accesso si chiude da solo dopo 2 ore che non usi Talità, e comunque dopo 12 ore dall’entrata. Per rientrare, chiedi un nuovo link."), "htmx=$htmx")
            assertFalse(browser.page("/accedi").contains("si chiude da solo"), "htmx=$htmx")
        }
        // Whoever never was inside is not told that the access closed.
        val first = mockMvc.get("/iscrizioni").andReturn()
        assertEquals("/accedi", location(first))
        assertFalse(body(mockMvc.get("/accedi").andReturn()).contains("si chiude da solo"))
    }

    @Test
    fun `t3 c22 the back button, which asks the server for a page of someone who went out, gets an answer that makes the browser load the address`() {
        val result = mockMvc.get("/profilo") {
            header("HX-Request", "true")
            header("HX-History-Restore-Request", "true")
        }.andReturn()

        // Not a 200 with an empty body (htmx would put it in the page) and not an HX-Redirect (it does not read it here).
        assertEquals(401, result.response.status)
        assertEquals(null, result.response.getHeader("HX-Redirect"))
        assertEquals("", body(result))
        // Loaded as a whole page, the address leads to the access page.
        assertEquals("/accedi", location(mockMvc.get("/profilo").andReturn()))
    }

    @Test
    fun `t3 c24 a form of access with the CSRF code expired comes back to its own page, filled in, with a notice and a new code`() {
        val address = Addresses.unique("scaduta")
        val before = tokenCount()
        val browser = browser()

        // A form sent without the code that its session knows (a page left open for hours): not a 403 page, the same page again.
        val lost = browser.post("/accedi", mapOf("email" to address))
        assertEquals(303, lost.response.status)
        assertEquals("/accedi", location(lost))

        val html = browser.page("/accedi")
        assertEquals("Accedi a Talità", heading(html))
        assertTrue(html.contains("La pagina era aperta da troppo tempo. Riprova."))
        assertTrue(html.contains("""role="alert""""))
        assertTrue(html.contains("data-fuoco"))
        assertTrue(html.contains("""value="$address""""), "the field is not filled in")
        assertNotNull(MockBrowser.csrfIn(html), "the page has no new code")
        assertFalse(html.contains("Non puoi vedere questa pagina"))
        assertEquals(before, tokenCount())
        assertTrue(Mailbox.sentTo(address).isEmpty())
        // Once: the notice and the address are not there at the next visit.
        val again = browser.page("/accedi")
        assertFalse(again.contains("La pagina era aperta da troppo tempo"))
        assertFalse(again.contains(address))
        // And the page works: with its new code, the link is asked.
        assertEquals(303, requestLink(browser, address).response.status)
    }

    @Test
    fun `t3 c24 the tap on Entra with the CSRF code expired comes back to the same link with a notice, and the link is still good`() {
        val token = signIn.link(Addresses.unique("scaduta-conferma"))
        val browser = browser()

        val lost = browser.post("/accedi/conferma", mapOf("token" to token))

        assertEquals(303, lost.response.status)
        assertEquals("/accedi/conferma?token=$token", location(lost))
        val html = browser.page("/accedi/conferma?token=$token")
        assertEquals("Conferma l’accesso", heading(html))
        assertTrue(html.contains("La pagina era aperta da troppo tempo. Riprova."))
        assertTrue(html.contains("""<input type="hidden" name="token" value="$token">"""))
        assertEquals("/", location(browser.confirm(token)), "the link was used by the page that expired")
        // Without the link there is nothing to come back to: the access page.
        assertEquals("/accedi", location(browser().post("/accedi/conferma")))
    }

    @Test
    fun `t3 c24 a form of someone who is not inside any more leads to the access page, not to a page that says forbidden`() {
        val plain = mockMvc.post("/esci").andReturn()
        assertEquals(302, plain.response.status)
        assertEquals("/accedi", location(plain))

        val fromHtmx = mockMvc.post("/esci") { header("HX-Request", "true") }.andReturn()
        assertEquals(200, fromHtmx.response.status)
        assertEquals("/accedi", fromHtmx.response.getHeader("HX-Redirect"))
    }

    // ---- B8. Headers (criterion 25) ------------------------------------------------------------------------------------

    @Test
    fun `t3 c25 every response has the Content-Security-Policy, the referrer and the type of content, without unsafe-inline or unsafe-eval`() {
        val session = signIn.session()
        val responses = listOf(
            mockMvc.get("/accedi").andReturn(),
            mockMvc.get("/accedi/controlla-la-posta").andReturn(),
            mockMvc.get("/") { this.session = session }.andReturn(),
            mockMvc.get("/profilo") { this.session = session }.andReturn(),
            mockMvc.get("/pagina-che-non-esiste") { this.session = session }.andReturn(),
            mockMvc.get("/css/inesistente.css").andReturn(),
        )
        val expected = setOf(
            "default-src 'self'", "script-src 'self'", "style-src 'self'", "object-src 'none'",
            "base-uri 'none'", "frame-ancestors 'none'", "form-action 'self'",
        )

        for (result in responses) {
            val policy = result.response.getHeader("Content-Security-Policy")
            assertNotNull(policy, "no Content-Security-Policy for ${result.request.requestURI}")
            assertEquals(expected, policy.split(";").map { it.trim() }.toSet(), result.request.requestURI)
            assertFalse(policy.contains("unsafe-inline") || policy.contains("unsafe-eval"))
            assertEquals("same-origin", result.response.getHeader("Referrer-Policy"))
            assertEquals("nosniff", result.response.getHeader("X-Content-Type-Options"))
        }
    }
}
