package talita.ui

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import jakarta.servlet.RequestDispatcher
import talita.TestcontainersConfiguration
import talita.account.CurrentAccount
import talita.account.MockSignIn
import talita.account.TeacherName
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pages of the shell as the server renders them: titles, texts, current entry, error pages. The
 * person is inside (see [MockSignIn]) unless the test says otherwise.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ShellTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val navigation: Navigation,
    @Autowired private val engine: TemplateEngine,
    @Autowired tokens: OneTimeTokenService,
    @Autowired dsl: DSLContext,
) {

    // A teacher: the pages of the shell are those of the four sections (a parent has two, see RolesTests).
    private val signedInSession = MockSignIn(mockMvc, tokens, dsl).session(teacher = true)

    private fun html(path: String): String = mockMvc.get(path) { session = signedInSession }.andReturn().response.contentAsString

    private fun heading(html: String) = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()

    private fun title(html: String) = Regex("""<title>(.*?)</title>""").find(html)?.groupValues?.get(1)

    /** The address of the entry marked as the current one, or none. */
    private fun currentEntries(html: String) = Regex("""<a href="([^"]*)" aria-current="page"""").findAll(html).map { it.groupValues[1] }.toList()

    /** The "Riprova" link of the error page (the pop-up messages have a "Riprova" button, not a link). */
    private val retryLink = Regex("""Riprova\s*</a>""")

    private fun error(status: Int, configure: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {}): MvcResult =
        mockMvc.get("/error") {
            session = signedInSession
            requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
            configure()
        }.andReturn()

    @Test
    fun `the home page has its title, heading, notice and the list of the sections`() {
        val html = html("/")

        assertEquals("Talità", title(html))
        assertEquals("Ti diamo il benvenuto", heading(html))
        assertTrue(html.contains("Talità raccoglie le iscrizioni alla convivenza dei bambini e aiuta i maestri a organizzarla."))
        assertTrue(html.contains("<strong>Talità è in costruzione.</strong> Le sezioni arriveranno una alla volta."))
        assertTrue(html.contains(">Sezioni</h2>"))
        for (section in listOf("/convivenza", "/iscrizioni", "/arrivi")) {
            assertTrue(html.contains("""<a href="$section" class="flex min-h-16"""), "the list of the home does not link $section")
        }
        assertEquals(listOf("/"), currentEntries(html))
    }

    @Test
    fun `t2 c14 the header has the T mark and the name, and the tab has the favicon of Talita`() {
        val html = html("/")

        assertTrue(html.contains("""<path d="M10.5 9h11M16 9v14" fill="none" stroke="#fff""""), "the mark in the header is not the T")
        assertTrue(Regex("""<a href="/" [^>]*>\s*<svg[\s\S]*?</svg>\s*Talità\s*</a>""").containsMatchIn(html), "the header has no mark followed by the name")

        val favicon = Regex("""<link rel="icon" href="([^"]+)" type="image/svg\+xml">""").find(html)?.groupValues?.get(1)
        assertNotNull(favicon, "no favicon in the page")
        val response = mockMvc.get(favicon).andReturn().response
        assertEquals(200, response.status)
        assertTrue(response.contentAsString.contains("""fill="#2a4ba0""""))
        assertTrue(response.contentAsString.contains("M10.5 9h11M16 9v14"), "the favicon is not the T")
    }

    @Test
    fun `t2 c18 no page or pop-up message names the other project, the Centro, the communities or the brothers`() {
        // Written in pieces: this file is under src/ and must not contain the words of the other project.
        val forbidden = listOf("koi" + "nè", "cen" + "tro", "comuni" + "tà", "fra" + "telli")
        val pages = listOf("/", "/convivenza", "/iscrizioni", "/arrivi").associateWith { html(it) } +
            mapOf("404" to error(404).response.contentAsString, "500" to error(500).response.contentAsString)

        for ((name, html) in pages) {
            // The whole page: visible text, title, attributes read aloud and the three <template> of the pop-up messages.
            for (word in forbidden) {
                assertFalse(html.contains(word, ignoreCase = true), "'$word' is in the page $name")
            }
        }
        assertTrue(pages.values.all { it.contains("""<template id="messaggio-rete-assente">""") }, "the pop-up messages are not in every page")
    }

    @Test
    fun `every section has its placeholder page with its text and as the current entry`() {
        val expected = mapOf(
            "/convivenza" to ("Convivenza" to "Qui troverai le date, i maestri e la pagina con le informazioni per i genitori."),
            "/iscrizioni" to ("Iscrizioni" to "Qui troverai i bambini iscritti e potrai inserire le iscrizioni arrivate sul modulo cartaceo."),
            "/arrivi" to ("Arrivi" to "Qui potrai segnare chi è arrivato e stampare le liste."),
        )
        for ((path, texts) in expected) {
            val (name, sentence) = texts
            val html = html(path)

            assertEquals("$name · Talità", title(html), path)
            assertEquals(name, heading(html), path)
            assertTrue(html.contains(">In preparazione</h2>"), path)
            assertTrue(html.contains(sentence), path)
            assertEquals(listOf(path), currentEntries(html), path)
        }
    }

    @Test
    fun `the navigation is a list built on every request, in order, with at most one current entry, and the role decides which entries`() {
        val teacher = CurrentAccount(1, "maestra", TeacherName("Elisa", "Conti"))
        val parent = CurrentAccount(2, "genitore")

        assertEquals(listOf("Home", "Convivenza", "Iscrizioni", "Arrivi"), navigation.items(null, teacher).map { it.label })
        assertEquals(listOf("/", "/convivenza", "/iscrizioni", "/arrivi"), navigation.items(null, teacher).map { it.href })
        assertEquals(emptyList(), navigation.items(null, teacher).filter { it.current })
        assertEquals(listOf("Iscrizioni"), navigation.items(Section.ISCRIZIONI, teacher).filter { it.current }.map { it.label })
        // A parent has two entries, a person with no account none of the sections of the teachers.
        assertEquals(listOf("Home", "Iscrizioni"), navigation.items(null, parent).map { it.label })
        assertEquals(listOf("Home", "Iscrizioni"), navigation.items(null, null).map { it.label })
        // A new list every time, so that the role of this moment is the one that counts.
        assertFalse(navigation.items(null, teacher) === navigation.items(null, teacher))
    }

    @Test
    fun `the page not found is the one of the design, inside the shell, with no current entry`() {
        val result = error(404)
        val html = result.response.contentAsString

        assertEquals(404, result.response.status)
        assertEquals("text/html;charset=UTF-8", result.response.contentType)
        assertEquals("Pagina non trovata · Talità", title(html))
        assertEquals("Pagina non trovata", heading(html))
        assertTrue(html.contains("L’indirizzo potrebbe essere sbagliato, oppure la pagina non esiste più."))
        assertTrue(html.contains("""<a href="/" class="mt-6 inline-flex"""))
        assertTrue(html.contains("""aria-label="Navigazione principale""""))
        assertEquals(emptyList(), currentEntries(html))
    }

    @Test
    fun `t3 c07 the error pages for someone without access have no navigation and lead to the access page`() {
        for (status in listOf(404, 500)) {
            val html = mockMvc.get("/error") {
                requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/accedi/qualcosa")
            }.andReturn().response.contentAsString

            assertFalse(html.contains("<nav"), "$status has a navigation")
            assertFalse(html.contains("""href="/profilo""""), "$status has the profile")
            assertTrue(html.contains("""<a href="/accedi" """), "$status does not lead to the access page")
            assertTrue(html.contains(">Vai all’accesso</a>"), "$status")
            assertFalse(html.contains("Vai alla home"), "$status")
            // The mark is there, and it is not a link.
            assertTrue(html.contains("""<path d="M10.5 9h11M16 9v14""""))
            assertFalse(Regex("""<a href="/"[^>]*>\s*<svg""").containsMatchIn(html), "$status")
        }
        // «Riprova» of the 500 stays, for a read.
        val withRetry = mockMvc.get("/error") {
            requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
            requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/accedi/qualcosa")
        }.andReturn().response.contentAsString
        assertTrue(retryLink.containsMatchIn(withRetry))
    }

    @Test
    fun `t3 c21 the header of someone who is inside has the mark and the profile pill with the initial, and the pages of the shell have no other profile`() {
        val html = html("/")

        assertTrue(Regex("""<a href="/profilo"[^>]*class="group ml-auto""").containsMatchIn(html))
        assertTrue(Regex("""<span aria-hidden="true" class="grid size-8 [^"]*">[A-Z]{2}</span>\s*<span class="max-\[20rem\]:sr-only">Profilo</span>""").containsMatchIn(html), html)
        assertEquals(1, Regex("""href="/profilo"""").findAll(html).count())
        // On the page of the profile the pill is the current entry, and it is not on any other.
        assertTrue(Regex("""<a href="/profilo" aria-current="page"""").containsMatchIn(html("/profilo")))
        assertEquals(listOf("/"), currentEntries(html))
        val profile = html("/profilo")
        assertEquals(listOf("/profilo"), currentEntries(profile), "only the pill is the current one on the profile page")
        assertFalse(Regex("""<nav[\s\S]*?</nav>""").find(profile)!!.value.contains("aria-current"), "an entry of the navigation is current on the profile page")
    }

    @Test
    fun `an address that nobody asked for, opened by hand on the error path, is a page that does not exist`() {
        val result = mockMvc.get("/error") { session = signedInSession }.andReturn()

        assertEquals(404, result.response.status)
        assertEquals("Pagina non trovata", heading(result.response.contentAsString))
    }

    @Test
    fun `the error page says something went wrong, with no technical detail, and offers to try again`() {
        val result = error(500) {
            requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/convivenza")
            requestAttr(RequestDispatcher.ERROR_EXCEPTION, IllegalStateException("dettaglio-tecnico"))
            requestAttr(RequestDispatcher.ERROR_MESSAGE, "dettaglio-tecnico")
        }
        val html = result.response.contentAsString

        assertEquals(500, result.response.status)
        assertEquals("Qualcosa non ha funzionato · Talità", title(html))
        assertEquals("Qualcosa non ha funzionato", heading(html))
        assertTrue(html.contains("Non è colpa tua: c’è stato un problema in Talità. Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza."))
        assertTrue(html.contains("""<a href="/convivenza" class="inline-flex min-h-12 w-full"""), "Riprova leads to the same address")
        assertTrue(html.contains("""<a href="/" class="inline-flex min-h-12 w-full"""), "Vai alla home")
        assertEquals(emptyList(), currentEntries(html))
        for (detail in listOf("dettaglio-tecnico", "IllegalStateException", "Whitelabel", "at talita")) {
            assertFalse(html.contains(detail), "'$detail' is on the error page")
        }
    }

    @Test
    fun `the query of the failed address is kept when trying again`() {
        val html = error(500) {
            requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/iscrizioni")
            requestAttr(RequestDispatcher.FORWARD_QUERY_STRING, "q=ilaria")
        }.response.contentAsString

        assertTrue(html.contains("""<a href="/iscrizioni?q=ilaria" """))
    }

    @Test
    fun `there is no try again after something other than a read, nor towards another host`() {
        val afterPost = mockMvc.post("/error") {
            session = signedInSession
            with(csrf())
            requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
            requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/convivenza")
        }.andReturn().response.contentAsString
        assertFalse(retryLink.containsMatchIn(afterPost), "no retry after a POST")
        assertTrue(afterPost.contains("Vai alla home"))

        for (foreign in listOf("//evil.example/x", "/\\evil.example", "https://evil.example/")) {
            val html = error(500) { requestAttr(RequestDispatcher.ERROR_REQUEST_URI, foreign) }.response.contentAsString
            assertFalse(retryLink.containsMatchIn(html), "$foreign leaves the app and must not become a link")
            assertFalse(html.contains("evil.example"), foreign)
        }
    }

    @Test
    fun `the other statuses of the client show the page not found and keep their status`() {
        val result = error(405)

        assertEquals(405, result.response.status)
        assertEquals("Pagina non trovata", heading(result.response.contentAsString))
    }

    @Test
    fun `the pop-up messages are templates in the layout with the texts of the design`() {
        val html = html("/")

        for (text in listOf(
            "Nessuna connessione", "Controlla di avere campo o il Wi-Fi e riprova.",
            "La connessione è troppo lenta", "Talità non ha risposto in tempo. Riprova quando hai più campo.",
            "Qualcosa non ha funzionato", "Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza.",
        )) {
            assertTrue(html.contains(text), "missing in the layout: $text")
        }
        for (id in listOf("messaggio-rete-assente", "messaggio-rete-lenta", "messaggio-errore-server")) {
            assertTrue(html.contains("""<template id="$id">"""), id)
        }
        assertTrue(html.contains("""aria-label="Chiudi""""))
        assertTrue(html.contains("""id="messaggi" aria-live="polite""""))
    }

    @Test
    fun `the htmx configuration of the layout switches off the history cache and the indicator styles`() {
        val html = html("/")

        assertTrue(html.contains("""<meta name="htmx-config" content='{"historyCacheSize":0,"includeIndicatorStyles":false,"timeout":20000}'>"""))
        assertTrue(html.contains("""<body hx-boost="true" class="min-h-dvh"""))
        // The last touch wins between page changes only; a request for a fragment is never synchronized.
        assertFalse(html.contains("hx-sync"), "hx-sync would tie together requests that have nothing to do with each other")
    }

    @Test
    fun `the viewport lets people zoom and covers the notch`() {
        val viewport = Regex("""<meta name="viewport" content="([^"]*)">""").find(html("/"))?.groupValues?.get(1).orEmpty()

        assertEquals("width=device-width, initial-scale=1, viewport-fit=cover", viewport)
    }

    @Test
    fun `every icon is drawn by the icon template`() {
        for (icon in Icon.entries) {
            val output = StringOutput()
            engine.render("components/icon.jte", mapOf("icon" to icon, "classes" to "size-6"), output)

            val svg = output.toString()
            assertTrue(Regex("""<(path|circle|rect)\b""").containsMatchIn(svg), "$icon has no drawing in components/icon.jte")
            assertTrue(svg.contains("""aria-hidden="true""""), "$icon is not hidden from screen readers")
        }
    }
}
