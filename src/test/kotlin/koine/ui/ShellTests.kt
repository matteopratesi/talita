package koine.ui

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import jakarta.servlet.RequestDispatcher
import koine.TestcontainersConfiguration
import org.junit.jupiter.api.Test
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
import kotlin.test.assertTrue

/** The pages of the shell as the server renders them: titles, texts, current entry, error pages. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ShellTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val navigation: Navigation,
    @Autowired private val engine: TemplateEngine,
) {

    private fun html(path: String): String = mockMvc.get(path).andReturn().response.contentAsString

    private fun heading(html: String) = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()

    private fun title(html: String) = Regex("""<title>(.*?)</title>""").find(html)?.groupValues?.get(1)

    /** The address of the entry marked as the current one, or none. */
    private fun currentEntries(html: String) = Regex("""<a href="([^"]*)" aria-current="page"""").findAll(html).map { it.groupValues[1] }.toList()

    /** The "Riprova" link of the error page (the pop-up messages have a "Riprova" button, not a link). */
    private val retryLink = Regex("""Riprova\s*</a>""")

    private fun error(status: Int, configure: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {}): MvcResult =
        mockMvc.get("/error") {
            requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
            configure()
        }.andReturn()

    @Test
    fun `the home page has its title, heading, notice and the list of the sections`() {
        val html = html("/")

        assertEquals("Koinè", title(html))
        assertEquals("Ti diamo il benvenuto", heading(html))
        assertTrue(html.contains("Koinè raccoglie l’anagrafe delle comunità e l’organizzazione delle convivenze."))
        assertTrue(html.contains("<strong>Koinè è in costruzione.</strong> Le sezioni arriveranno una alla volta."))
        assertTrue(html.contains(">Sezioni</h2>"))
        for (section in listOf("/comunita", "/persone", "/convivenze")) {
            assertTrue(html.contains("""<a href="$section" class="flex min-h-16"""), "the list of the home does not link $section")
        }
        assertEquals(listOf("/"), currentEntries(html))
    }

    @Test
    fun `every section has its placeholder page with its text and as the current entry`() {
        val expected = mapOf(
            "/comunita" to ("Comunità" to "Qui troverai l’elenco delle comunità, con i fratelli e lo storico di ciascuna."),
            "/persone" to ("Persone" to "Qui potrai cercare una persona e aprire la sua scheda."),
            "/convivenze" to ("Convivenze" to "Qui troverai le convivenze, con le iscrizioni e le quote."),
        )
        for ((path, texts) in expected) {
            val (name, sentence) = texts
            val html = html(path)

            assertEquals("$name · Koinè", title(html), path)
            assertEquals(name, heading(html), path)
            assertTrue(html.contains(">In preparazione</h2>"), path)
            assertTrue(html.contains(sentence), path)
            assertEquals(listOf(path), currentEntries(html), path)
        }
    }

    @Test
    fun `the navigation is a list built on every request, in order, with at most one current entry`() {
        assertEquals(listOf("Home", "Comunità", "Persone", "Convivenze"), navigation.items(null).map { it.label })
        assertEquals(listOf("/", "/comunita", "/persone", "/convivenze"), navigation.items(null).map { it.href })
        assertEquals(emptyList(), navigation.items(null).filter { it.current })
        assertEquals(listOf("Persone"), navigation.items(Section.PERSONE).filter { it.current }.map { it.label })
        // A new list every time, so that the login can filter it by role and scope.
        assertFalse(navigation.items(null) === navigation.items(null))
    }

    @Test
    fun `the page not found is the one of the design, inside the shell, with no current entry`() {
        val result = error(404)
        val html = result.response.contentAsString

        assertEquals(404, result.response.status)
        assertEquals("text/html;charset=UTF-8", result.response.contentType)
        assertEquals("Pagina non trovata · Koinè", title(html))
        assertEquals("Pagina non trovata", heading(html))
        assertTrue(html.contains("L’indirizzo potrebbe essere sbagliato, oppure la pagina non esiste più."))
        assertTrue(html.contains("""<a href="/" class="mt-6 inline-flex"""))
        assertTrue(html.contains("""aria-label="Navigazione principale""""))
        assertEquals(emptyList(), currentEntries(html))
    }

    @Test
    fun `an address that nobody asked for, opened by hand on the error path, is a page that does not exist`() {
        val result = mockMvc.get("/error").andReturn()

        assertEquals(404, result.response.status)
        assertEquals("Pagina non trovata", heading(result.response.contentAsString))
    }

    @Test
    fun `the error page says something went wrong, with no technical detail, and offers to try again`() {
        val result = error(500) {
            requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/comunita")
            requestAttr(RequestDispatcher.ERROR_EXCEPTION, IllegalStateException("dettaglio-tecnico"))
            requestAttr(RequestDispatcher.ERROR_MESSAGE, "dettaglio-tecnico")
        }
        val html = result.response.contentAsString

        assertEquals(500, result.response.status)
        assertEquals("Qualcosa non ha funzionato · Koinè", title(html))
        assertEquals("Qualcosa non ha funzionato", heading(html))
        assertTrue(html.contains("Non è colpa tua: c’è stato un problema in Koinè. Riprova tra qualche minuto. Se il problema continua, avvisa il Centro."))
        assertTrue(html.contains("""<a href="/comunita" class="inline-flex min-h-12 w-full"""), "Riprova leads to the same address")
        assertTrue(html.contains("""<a href="/" class="inline-flex min-h-12 w-full"""), "Vai alla home")
        assertEquals(emptyList(), currentEntries(html))
        for (detail in listOf("dettaglio-tecnico", "IllegalStateException", "Whitelabel", "at koine")) {
            assertFalse(html.contains(detail), "'$detail' is on the error page")
        }
    }

    @Test
    fun `the query of the failed address is kept when trying again`() {
        val html = error(500) {
            requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/persone")
            requestAttr(RequestDispatcher.FORWARD_QUERY_STRING, "q=ilaria")
        }.response.contentAsString

        assertTrue(html.contains("""<a href="/persone?q=ilaria" """))
    }

    @Test
    fun `there is no try again after something other than a read, nor towards another host`() {
        val afterPost = mockMvc.post("/error") {
            requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
            requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/comunita")
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
            "La connessione è troppo lenta", "Koinè non ha risposto in tempo. Riprova quando hai più campo.",
            "Qualcosa non ha funzionato", "Riprova tra qualche minuto. Se il problema continua, avvisa il Centro.",
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
