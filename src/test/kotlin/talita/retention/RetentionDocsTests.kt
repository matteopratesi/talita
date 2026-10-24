package talita.retention

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The documents of the retention (criterion 33): `docs/02` and `docs/03` have what the comment «Conservazione · dettaglio» of the analysis asks for (the new rows, the
 * question that was open and is closed, the table of the times, the rights, the backups, the paper), `docs/05` has the entry of the ticket and the design guide has the pieces
 * that the ticket adds, with the mockups in the repository. No page here: a text that is not written fails the build.
 */
class RetentionDocsTests {

    private fun doc(path: String): String = Files.readString(Path.of(path)).replace(Regex("""\s+"""), " ")

    @Test
    fun `t7 c33 the data model has the day of creation and the register of the deletions with their purpose, and the question about the times is closed`() {
        val data = doc("docs/02-modello-dati.md")

        for (part in listOf(
            "**giorno di creazione** (`account.created_on`)", "**nuova (TALITA-7)**: cancellare gli account rimasti vuoti 30 giorni dopo la creazione",
            "Registro delle cancellazioni (`retention_log`)", "**l'id interno del figlio o dell'account**, per 37 giorni", "rifare la cancellazione se si ripristina un backup più vecchio della richiesta",
            "l'account di chi ha letto resta, con il solo indirizzo, finché restano le sue letture", "la tabella dei tempi è una sola, in `docs/03-privacy.md`",
            "`on delete set null`", "«un maestro che non è più tra i maestri»", "`V7__retention.sql`", "Il recapito segue l'iscrizione (un anno)", "i maestri tolti sono account come gli altri",
            "**Chiuso con TALITA-7**",
        )) {
            assertTrue(data.contains(part), "docs/02 does not say: $part")
        }
        assertFalse(data.contains("se debba andare via prima, lo fissa TALITA-7"), "the question of the phone is closed")
        assertFalse(data.contains("Quanto si tiene e come si cancella lo dice TALITA-7"), "the account has its times")
    }

    @Test
    fun `t7 c33 the privacy document has the table of the times, the measures of the job, the rights, the requirements for the backups and the paper`() {
        val privacy = doc("docs/03-privacy.md")

        for (part in listOf(
            "**La tabella è questa** (TALITA-7", "`RetentionPolicy`", "| Segnalazioni (`health_note`) | **un mese dopo la fine**", "**un anno dopo la fine**; prima, su richiesta, solo se ritirata senza presenze né pagamenti",
            "**30 giorni dopo la creazione**", "**30 giorni dopo la scadenza**", "**37 giorni** (rotazione dei backup più 7)", "**entro 30 giorni** dalla cancellazione", "Carta: manleve firmate e registro stampato",
            "**un job** ogni notte alle 4:15 (ora di Roma) e a ogni avvio", "lock consultivo di Postgres", "**righe cancellate davvero** (`DELETE`)", "niente `VACUUM FULL`", "**registro delle cancellazioni** (`retention_log`)",
            "## Backup: requisiti per TALITA-8", "**Backup logici** (`pg_dump`)", "Niente copie della cartella dei dati né del WAL", "**Nessun backup più vecchio di 30 giorni**", "**Il backup del giorno parte dopo il job delle 4:15**",
            "**Ripristino dal backup più recente**", "**Il log del proxy senza query string**", "**Quando il genitore chiede di cancellare**", "**cancella i dati dei figli, o l'account, quando vuole**", "art. 12.3",
            "**Nessuna mail** prima di cancellare un account", "**se un anno basta per la manleva**", "Dalla data di cancellazione (un mese dopo la fine) la classe non le legge più",
        )) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        assertFalse(privacy.contains("Ancora da fissare (TALITA-7)"), "nothing is left to decide")
        assertFalse(privacy.contains("Tempi di TALITA-7:"), "the list is replaced by the table")
        assertTrue(privacy.indexOf("## Backup: requisiti per TALITA-8") < privacy.indexOf("## Carta"), "the requirements for the backups come before the paper")
        assertTrue(privacy.indexOf("## Carta") < privacy.indexOf("## Conservazione"), "the paper comes before the times")
    }

    @Test
    fun `t7 c33 the decision log has the entry of TALITA-7 after the earlier ones, with the answers of Matteo and the differences from the analysis`() {
        val log = doc("docs/05-decisioni.md")

        val entry = log.substringAfter("## 2026-10-24: scelte di TALITA-7")
        assertTrue(log.contains("## 2026-10-24: scelte di TALITA-7"))
        assertTrue(log.indexOf("## 2026-10-24: scelte di TALITA-6") < log.indexOf("## 2026-10-24: scelte di TALITA-7"), "the entries are in order")
        for (part in listOf(
            "**Il ticket non si spezza**", "**Valori approvati**", "30 giorni dopo la creazione", "**Prima del rilascio**", "**Pacchetto `talita.retention`**", "**`V7__retention.sql`**", "**Differenza dall'analisi**",
            "**la cartacea è quella senza accettazione**", "**«Cancellate» dipende dalla data e non dalle righe**", "**Aggiungere una regola è aggiungere un bean**", "`pg_advisory_xact_lock`", "`talita.retention.automatic=false`",
            "**Il controllo dei blocchi sta nella transazione**", "`Teachers.lockAndCheck`", "**Una protezione in più**", "**Le proposte del design fuori dai criteri, approvate, ci sono**", "**«Cancella» e «Togli»**",
            "**«Account cancellato.» non ripete cosa resta**", "**Axe non gira con JavaScript spento**", "**F. Cosa resta fuori", "`ErasureService.deleteRegistrations`",
        )) {
            assertTrue(entry.contains(part), "the entry of TALITA-7 misses: $part")
        }
    }

    @Test
    fun `t7 c33 the design guide has the pieces that the ticket adds, and the mockups are in the repository`() {
        val guide = doc("docs/design/README.md")

        for (part in listOf(
            "`docs/design/TALITA-7/` sono i mockup della conservazione dei dati", "| Pericolo in contorno |", "`border border-danger bg-surface text-danger hover:bg-danger-soft active:bg-danger-soft`",
            "**Riga di un figlio nel Profilo**", "border-t border-line py-4 first:border-t-0 first:pt-2", "**Riga di conservazione**", "**Scheda «Conservazione dei dati»**", "**Riquadro neutro con icona**",
            "**Dialogo con elenchi** (`erasureDialog`", "`max-h-[92dvh] overflow-y-auto`", "«Cancelliamo subito»", "«Non si può annullare.»", "**Variante neutra**", "**«I tuoi dati»** (TALITA-7",
            "**«Cancella» e «Togli»**", "**Il tempo del verbo**", "«Si cancellano il 20 ottobre 2027»", "**una seconda frase**", "`archive`", "`shield-check`",
        )) {
            assertTrue(guide.contains(part), "the guide does not say: $part")
        }
        assertTrue(Files.isRegularFile(Path.of("docs/design/TALITA-7/index.html")))
        assertTrue(Files.isRegularFile(Path.of("docs/design/TALITA-7/talita-7.css")))
        assertEquals(27, Files.list(Path.of("docs/design/TALITA-7")).use { it.count() }, "the 25 pages of the mockup, the index and the style")
        // The pages of the mockup have no inline style, no script and nothing from outside: the repository is public and the guide says so.
        val pages = Files.list(Path.of("docs/design/TALITA-7")).use { stream -> stream.filter { it.toString().endsWith(".html") }.toList() }
        assertEquals(26, pages.size)
        for (page in pages) {
            val html = Files.readString(page)
            assertFalse(Regex("""\sstyle=""").containsMatchIn(html), "$page has an inline style")
            assertFalse(html.contains("<script"), "$page has a script")
            assertFalse(Regex("""(src|href)="https?://""").containsMatchIn(html), "$page loads something from outside")
        }
    }
}
