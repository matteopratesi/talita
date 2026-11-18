package talita.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import talita.TestcontainersConfiguration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.full.primaryConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `docs/06-api.md` is kept aligned with the code (TALITA-21, criterion 26): the routes of `/api/…` are read from the handler mapping of Spring and the fields of the requests from the classes that the
 * handlers name, and the document must have every one of them and nothing else. Plus the rules that the document gives to whoever uses the APIs, and the words that must not be in it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ApiDocsTests(
    @Autowired @Qualifier("requestMappingHandlerMapping") private val mapping: RequestMappingHandlerMapping,
) {

    private val doc = Files.readString(Path.of("docs/06-api.md"))

    /** `METHOD /api/...` of every route of the application under `/api/`, and for the ones that read a body the names of the fields. */
    private fun routes(): Map<String, Set<String>?> = buildMap {
        for ((info, handler) in mapping.handlerMethods) {
            val patterns = info.pathPatternsCondition?.patternValues.orEmpty().filter { it.startsWith("/api/") }
            if (patterns.isEmpty()) continue
            val fields = handler.method.getAnnotation(ApiRequest::class.java)?.type?.primaryConstructor?.parameters?.map { it.name!! }?.toSet()
            for (method in info.methodsCondition.methods) for (pattern in patterns) put("${method.name} ${ApiRequests.model(pattern)}", fields)
        }
    }

    private val headings = Regex("""(?m)^### ([A-Z]+) (/api/\S+)\s*$""")

    private fun documented(): Map<String, String> {
        val found = headings.findAll(doc).toList()
        val keys = found.map { "${it.groupValues[1]} ${it.groupValues[2]}" }
        assertEquals(keys.distinct(), keys, "a route has more than one section in docs/06-api.md")
        return found.mapIndexed { index, match ->
            val end = if (index + 1 < found.size) found[index + 1].range.first else doc.length
            val section = doc.substring(match.range.last, end).substringBefore("\n## ")
            "${match.groupValues[1]} ${match.groupValues[2]}" to section
        }.toMap()
    }

    @Test
    fun `t21 c26 the document has every route of the APIs and no route that does not exist`() {
        val code = routes().keys
        val written = documented().keys

        assertEquals(code.sorted(), written.sorted(), "routes of the code against routes of docs/06-api.md")
    }

    @Test
    fun `t21 c26 the document lists the fields of every request and no field that does not exist`() {
        val sections = documented()

        for ((route, fields) in routes()) {
            val section = sections.getValue(route)
            val listed = section.substringAfter("**Campi", "").lines().drop(1).dropWhile { it.isBlank() }.takeWhile { it.startsWith("- `") }
                .map { Regex("""^- `([A-Za-z]+)`""").find(it)!!.groupValues[1] }.toSet()
            assertEquals(fields ?: emptySet(), listed, "fields of $route")
        }
    }

    @Test
    fun `t21 c26 the document says how to enter, the errors and the rules for who uses the APIs`() {
        for (part in listOf(
            "Authorization: Bearer", "**30 giorni**", "**uno solo** per maestro", "`401`", "`WWW-Authenticate: Bearer`", "Token mancante o non valido.", "`400`", "`404`", "`405`", "`409`", "`413`", "`415`", "`422`", "`429`", "`Retry-After`", "`500`",
            "**Leggi prima di scrivere.**", "**un campo che manca è un `400`**", "**Mai nomi di persone nei testi.**", "**Mai aprire il file del token**", "**Cosa resta nell'app**", "aprire, chiudere e riaprire le iscrizioni", "mandare i promemoria",
            "Come le usa Claude Code dal PC di Matteo",
        )) {
            assertTrue(doc.contains(part), "docs/06-api.md does not say: $part")
        }
    }

    @Test
    fun `t21 c26 the APIs are described for any client, and the name of a tool is only in the paragraph that says how one tool uses them`() {
        val before = doc.substringBefore("## Come le usa Claude Code dal PC di Matteo")

        assertFalse(before.contains("Claude"), "the document is for any client")
        assertTrue(doc.contains("qualunque client"))
    }

    private fun read(path: String): String = Files.readString(Path.of(path))

    @Test
    fun `t21 c28 the documents of the project say what the ticket adds`() {
        val data = read("docs/02-modello-dati.md")
        for (part in listOf("Token per le API (`api_token`, `V16__api_tokens.sql`)", "**SHA-256 del token**", "far usare le API di configurazione a un programma sul computer del maestro", "Registro delle richieste alle API (`api_request`, `V16__api_tokens.sql`)", "**nessun dato personale**", "non dice quale token né quale maestro")) {
            assertTrue(data.contains(part), "docs/02 does not say: $part")
        }
        val privacy = read("docs/03-privacy.md")
        for (part in listOf(
            "## Le API di configurazione (TALITA-21)", "**Non danno e non ricevono dati personali.**", "**Un programma che le usa non è un fornitore**", "**Il controllo dei nomi risponde anche alle API**", "`/info/{codice}`",
            "**Il proxy non serve `/api/**` in chiaro**", "`delete from api_token;`", "deve rispettare sette requisiti", "| Token per le API (`api_token`, TALITA-21) |", "| Registro delle richieste alle API (`api_request`, TALITA-21) |",
        )) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        val environment = read("docs/04-ambiente.md")
        for (part in listOf("## Le API di configurazione e il token", "`~/.config/talita/`", "`dev.header`", "`dev.url`", "`prod.header`", "`prod.url`", "umask 077", "curl -sS -H @", "`deny`", "«Revoca»")) {
            assertTrue(environment.contains(part), "docs/04 does not say: $part")
        }
        assertTrue(read("docs/05-decisioni.md").contains("## 2026-11-18: scelte di TALITA-21"))
        assertTrue(read("CLAUDE.md").contains("`docs/06-api.md`"))
        assertTrue(read("docs/design/README.md").contains("### 4.27 Token per le API (TALITA-21)"))
        // The APIs are for any client: the name of the tool is not in the notice, the data model, the privacy document or the guide of the design.
        for (path in listOf("docs/02-modello-dati.md", "docs/03-privacy.md", "docs/design/README.md")) assertFalse(read(path).contains("Claude"), "$path names a tool")
    }
}
