package talita.gathering

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The documents of the menu (TALITA-12, criterion 13): `docs/05` has the entry of the ticket with the decision of Matteo and the exception to «documenti caricati», `docs/02` the
 * row of the menu and the exception under «Mai», `docs/03` the minimization, the measures (how the file is served) and the keeping, `docs/01` the file of the menu in the first
 * release with the meals and the kitchen still out, `docs/04` the limits of the requests, and the guide the field for a file, the card with a file and the dialog. The mockups of
 * the design are in their folder, with no style, no script and no resource from outside. No page here: a text that is not written fails the build.
 */
class MenuDocsTests {

    private fun doc(path: String): String = Files.readString(Path.of(path)).replace(Regex("""\s+"""), " ")

    @Test
    fun `t12 c13 the decision log has the entry of TALITA-12 with the decision of Matteo, the exception to uploaded documents and the choices of the analysis`() {
        val log = doc("docs/05-decisioni.md")

        assertTrue(log.contains("## 2026-10-29: scelte di TALITA-12"))
        val entry = log.substringAfter("## 2026-10-29: scelte di TALITA-12").substringBefore(" ## 20")
        for (part in listOf(
            "**Decisione di Matteo, 24/10/2026**", "Keep it simple per ora mettiamolo solo come caricamento di un file per averlo sempre disponibile",
            "l'analisi lunga e il design vecchio (commenti 10931-10935) non valgono per questo ticket**", "**il menù lo vedono solo i maestri**", "**niente «copia il menù dell'anno prima»**",
            "**L'eccezione a «niente documenti caricati»**", "«documenti caricati» è ancora fra i «Mai»", "**L'eccezione è stretta e riguarda una cosa sola**: il file del menù",
            "**non deve contenere dati personali**", "**solo dai maestri**", "Le difese sono cinque", "Nel file niente nomi, allergie o altri dati dei bambini",
            "**`V10__menu.sql`**", "la V9 è di TALITA-11", "**10 MB sono una costante sola**, `MenuFile.MAX_BYTES`", "gathering_menu_content_size",
            "**Il tipo si riconosce dai primi byte**", "**Lo scarico è sempre `attachment`**", "default-src 'none'; sandbox", "`spring.servlet.multipart.max-file-size`", "`server.tomcat.max-swallow-size`",
            "**Oltre i 20 MB: differenza dall'analisi, trovata con il server vero.**", "`TooLargeUploadFilter`", "**Per TALITA-8**", "almeno 20 MB", "Safari su iPhone", "**Fuori** (per un ticket futuro)",
            "pasti e menù per pasto, coperti",
        )) {
            assertTrue(entry.contains(part), "docs/05 does not say: $part")
        }
    }

    @Test
    fun `t12 c13 the data model has the row of the menu and the exception under Mai`() {
        val data = doc("docs/02-modello-dati.md")

        for (part in listOf(
            "Menù della convivenza (`gathering_menu`, `V10__menu.sql`)", "**Nessun dato personale, e il file non deve contenerne**", "**È l'eccezione stretta a «documenti caricati»**",
            "un file solo per convivenza, solo per i maestri", "mai nella pagina informativa né nelle pagine dei genitori", "Chi l'ha caricato non si salva: non serve",
            "non va nella scheda «Conservazione dei dati» di TALITA-7",
            "- documenti caricati, con **un'eccezione stretta** (TALITA-12): il file del menù della convivenza",
        )) {
            assertTrue(data.contains(part), "docs/02 does not say: $part")
        }
        assertFalse(data.contains("- documenti caricati; - firme"), "the line of the uploaded documents says the exception")
    }

    @Test
    fun `t12 c13 the privacy document has the minimization, the measures that say how the file is served, and the keeping of the menu`() {
        val privacy = doc("docs/03-privacy.md")

        val section = privacy.substringAfter("## Il file del menù ").substringBefore(" ## Conservazione")
        for (part in listOf(
            "**un solo file** per convivenza", "l'eccezione stretta a «documenti caricati»", "**Minimizzazione.**", "**non deve contenere dati personali**", "L'app **non legge il file**",
            "**Misure, come si serve il file.**", "Solo per i maestri", "**Mai** nella pagina informativa", "**sempre `attachment`**", "`X-Content-Type-Options: nosniff`",
            "`Content-Security-Policy: default-src 'none'; sandbox`", "**mai il nome né il contenuto**", "**Conservazione.**", "**non va nella scheda «Conservazione dei dati»** di TALITA-7",
            "**L'informativa non cambia**",
        )) {
            assertTrue(section.contains(part), "docs/03 (the file of the menu) does not say: $part")
        }
        assertTrue(privacy.contains("nessun documento caricato (con la sola eccezione del file del menù"), "the line of the minimization says the exception")
        assertTrue(privacy.indexOf("## Carta") < privacy.indexOf("## Il file del menù") && privacy.indexOf("## Il file del menù") < privacy.indexOf("## Conservazione"))
    }

    @Test
    fun `t12 c13 the context has the file of the menu in the first release, with the meals and the kitchen still out`() {
        val context = doc("docs/01-contesto.md")

        for (part in listOf(
            "**Cucina e menù entra solo come file** (TALITA-12", "Keep it simple per ora mettiamolo solo come caricamento di un file", "Lo vedono solo i maestri, non i genitori né la pagina informativa",
            "Restano fuori, per un ticket futuro: pasti e menù per pasto, coperti calcolati dalle presenze", "- caricamento di documenti, tranne il file del menù",
        )) {
            assertTrue(context.contains(part), "docs/01 does not say: $part")
        }
    }

    @Test
    fun `t12 c13 the environment guide has the limits of the requests and what the proxy must allow`() {
        val environment = doc("docs/04-ambiente.md")

        val section = environment.substringAfter("## Il file del menù e il limite delle richieste").substringBefore(" ## Build, sviluppo e test")
        for (part in listOf("**Nessuna variabile nuova**", "**Limite del file: 10 MB**", "`spring.servlet.multipart.max-file-size`", "**20 MB**", "`server.tomcat.max-swallow-size`", "**100 MB**", "**almeno 20 MB**", "`MenuOversizeTests`")) {
            assertTrue(section.contains(part), "docs/04 does not say: $part")
        }
        val config = Files.readString(Path.of("src/main/resources/application.yaml"))
        assertTrue(Regex("""max-file-size: 20MB""").containsMatchIn(config) && Regex("""max-request-size: 20MB""").containsMatchIn(config) && Regex("""max-swallow-size: 100MB""").containsMatchIn(config), "the configuration says what the guide says")
    }

    @Test
    fun `t12 c13 the design guide has the field for a file, the notice above the field, the card with a file, the dialog, the form outside htmx and the texts`() {
        val guide = doc("docs/design/README.md")

        for (part in listOf(
            "`docs/design/TALITA-12/` sono i mockup del menù della convivenza", "**Campo per il file** (`components/fileField.jte`, TALITA-12", "`file:min-h-12`", "**Nessun `required` e `novalidate` sul form**",
            "**Avviso sopra il campo** (TALITA-12)", "**Scheda con un file** (TALITA-12", "**Niente anteprima né miniatura**", "il bottone dice «Scarica», mai «Apri»",
            "**«Togliere il menù?»** (TALITA-12)", "`dialogo-togli-menu`", "### Il file del menù (TALITA-12)", "**Un form fuori da htmx**", "**Un link che scarica**", "`form[data-carica]`",
            "| Dimensione di un file |", "3 settembre alle 21:40", "### Il menù (TALITA-12)", "«Scegli il file del menù.»",
        )) {
            assertTrue(guide.contains(part), "the design guide does not say: $part")
        }
        assertTrue(guide.contains("`/convivenza/{id}/menu` (caricare e sostituire il menù) e in `/convivenza/{id}/menu/togli`"), "the entry Convivenza is the current one in the pages of the menu")
        // The four icons that the ticket adds are in the table of 2.8 (the test of the guide checks every icon of the code; here the use that is written for them).
        for (row in listOf("| `upload` |", "| `image` |", "| `utensils` |", "| Esporta, scarica un file | `download` |")) assertTrue(guide.contains(row), row)
    }

    @Test
    fun `t12 c13 the mockups are in their folder, nine pages and the index, with no style, no script and nothing from outside`() {
        val folder = Path.of("docs/design/TALITA-12")
        val files = Files.list(folder).use { stream -> stream.map { it.name }.sorted().toList() }

        assertEquals(
            listOf(
                "arrivi-menu.html", "convivenza-menu-togli.html", "convivenza-menu-vuoto.html", "convivenza-menu.html", "index.html", "menu-carica.html", "menu-caricamento.html",
                "menu-errore-grande.html", "menu-errore-tipo.html", "menu-sostituisci.html", "talita-12.css",
            ),
            files,
        )
        for (page in files.filter { it.endsWith(".html") }) {
            val html = Files.readString(folder.resolve(page))
            assertFalse(Regex("""\sstyle\s*=""").containsMatchIn(html), "$page has a style attribute")
            assertFalse(html.contains("<script", ignoreCase = true), "$page has a script")
            assertFalse(Regex("""\son[a-z]+\s*=""").containsMatchIn(html), "$page has a handler")
            // The only address in the pages is the namespace of the SVG and the example address of the public page of the gathering.
            val addresses = Regex("""https?://[^\s"'<)]+""").findAll(html).map { it.value }.filter { it != "http://www.w3.org/2000/svg" && !it.startsWith("https://talita.example.org/") }.toList()
            assertEquals(emptyList<String>(), addresses, "$page links to something outside")
            assertFalse(Regex("""(href|src)="(//|https?:)""").containsMatchIn(html.replace("https://talita.example.org", "")), "$page loads or leads outside")
        }
    }
}
