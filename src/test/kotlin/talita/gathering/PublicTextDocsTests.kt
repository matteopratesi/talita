package talita.gathering

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The documents of TALITA-16 (criteria 17 and 18, and the words of 20 and 21): the entry of the ticket in `docs/05` with the choices of Matteo, the waits that stay, the
 * check of the names and the limit of the list; the guide with the help and the error; `docs/02` with the new use of the names; `docs/03` with the measure; and the mockups.
 * The test reads the files: a document that loses a sentence fails the build.
 */
class PublicTextDocsTests {

    private fun doc(path: String): String = Files.readString(Path.of(path)).replace(Regex("""[ \t]*\n[ \t]*"""), " ")

    /** The entry of TALITA-16 of the register of decisions, up to the next one. */
    private fun entry(): String = doc("docs/05-decisioni.md").substringAfter("## 2026-10-31: scelte di TALITA-16").substringBefore(" ## 2026-")

    @Test
    fun `t16 c17 the register of decisions has the entry of the ticket with the choices of Matteo, the waits that stay, the names and the limit of the list`() {
        val entry = entry()

        for (part in listOf(
            "**Opzione B**", "anche dei maestri", "Il solo nome o il solo cognome non bastano", "segnaliamo quello che effettivamente blocchiamo",
            "`LockWait.awaitBlocked`", "pg_stat_clear_snapshot()", "`@CapturesOutput`", "`NoMailTests` e `BootstrapStartupTests`", "`awaitCondition`", "`waitForHtmxIdle`", "`Mailbox.waitForMails(indirizzo, n)`",
            "**L'elenco di oggi**", "un server lento simulato", "la prova che qualcosa non succede o che un tempo deve passare", "Nessun test di concorrenza è nell'elenco",
            "`PublicNames`", "`KnownNames`", "**una sola query per tutti i testi di un form**", "`unaccent(lower(…))`", "Un solo errore per campo", "«Marco Polo»", "non c'è «salva lo stesso»",
            "Qui ci sono nome e cognome di una persona e un numero di telefono. Toglili",
            "**Parte 3: la durata della build", "**Parte 4", "per «Campo estivo»", "**Parte 5", "`Mailer.LIST_LIMIT`", "**15 secondi**", "le mail rimaste falliscono subito",
        )) {
            assertTrue(entry.contains(part), "the entry of TALITA-16 in docs/05 does not say: $part")
        }
        assertFalse(entry.contains("<<"), "a placeholder is left in the entry")
    }

    @Test
    fun `t16 c18 the entry says where the time of the build goes and what is the main cause of the passage from about 6 to about 30 minutes`() {
        val duration = entry().substringAfter("**Parte 3: la durata della build").substringBefore("**Parte 4")

        for (part in listOf("unitari", "e2e", "contesti Spring", "container", "browser", "jOOQ", "Tailwind", "La causa principale", "da circa 6 a circa 30 minuti")) {
            assertTrue(duration.contains(part), "the part on the duration of the build does not say: $part")
        }
        assertTrue(Regex("""\d+(,\d+)? (s|secondi|minuti)""").containsMatchIn(duration), "the numbers measured are not there")
    }

    @Test
    fun `t16 c17 the guide has the words of the help and of the error, and of the mails without an article`() {
        val guide = doc("docs/design/README.md")
        val block = guide.substringAfter("### Testi pubblici (TALITA-16)").substringBefore(" ## 7.")

        for (part in listOf(
            "«Non scrivere nome e cognome di bambini o maestri, numeri di telefono né email.»", "Sono costanti (`PublicText`)", "«Serve per aprire le iscrizioni» non sta sotto «Luogo»",
            "mai «nomi» in generale", "il solo nome passa", "«Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link.»", "il nome per primo, sempre al plurale",
        )) {
            assertTrue(block.contains(part), "the guide does not say: $part")
        }
        assertTrue(guide.contains("**Il titolo non ha mai un articolo davanti** (TALITA-16)"))
        assertTrue(guide.contains("«Iscrizione in forse per «Campo estivo»»"))
        // The help of today and the words that the check does not do any more are not in the guide as the rule.
        assertFalse(block.contains("Non scrivere nomi,"))
    }

    @Test
    fun `t16 c17 the data model writes the new use of the names, and the privacy document says it among the measures of the public page and of the reminders`() {
        val model = doc("docs/02-modello-dati.md")
        for (part in listOf(
            "**Dal TALITA-16 il nome e il cognome servono anche a un controllo che protegge lo stesso bambino**", "il nome non esce, non si salva e non va nel log",
            "**dal TALITA-16 anche il controllo dei testi pubblici**", "**né il nome e il cognome di un bambino o di un maestro**",
        )) {
            assertTrue(model.contains(part), "docs/02 does not say: $part")
        }

        val privacy = doc("docs/03-privacy.md")
        for (part in listOf(
            "**né il nome e il cognome di un bambino o di un maestro che l'app conosce**", "una sola domanda al database (`PublicNames`, con `unaccent`)", "non prende il solo nome o il solo cognome",
            "l'informativa non cambia", "il nome e il cognome di un bambino o di un maestro che l'app conosce: un titolo con un nome intero non si salva",
        )) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        assertFalse(privacy.contains("ma un nome non lo può riconoscere"), "docs/03 still says that a name cannot be recognised in the title")
    }

    @Test
    fun `t16 c21 the environment guide says the limit of the whole list of reminders`() {
        val environment = doc("docs/04-ambiente.md")

        assertTrue(environment.contains("un tetto di **15 secondi in tutto** (`Mailer.LIST_LIMIT`, TALITA-16)"))
        assertEquals(15, talita.mail.Mailer.LIST_LIMIT.toSeconds())
    }

    @Test
    fun `t16 c10 the mockups of the ticket are in the design folder, and have no style, no script and nothing from outside`() {
        val folder = Path.of("docs/design/TALITA-16")
        val pages = listOf(
            "index.html", "convivenza-nuova.html", "convivenza-date.html", "convivenza-date-finita.html", "convivenza-informazioni.html",
            "convivenza-nuova-errore-nome.html", "convivenza-informazioni-errore-nome-telefono.html", "convivenza-date-errori.html",
        )
        for (page in pages) {
            val html = Files.readString(folder.resolve(page))
            assertFalse(html.contains("<script"), "$page has a script")
            assertFalse(Regex("""\sstyle\s*=""").containsMatchIn(html), "$page has an inline style")
            assertFalse(Regex("""(src|href)="https?://""").containsMatchIn(html), "$page loads something from outside")
        }
        assertTrue(Files.isRegularFile(folder.resolve("talita-16.css")))
    }
}
