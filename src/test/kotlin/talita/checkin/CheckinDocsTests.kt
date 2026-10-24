package talita.checkin

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The documents of the check-in (criterion 31): `docs/02` and `docs/03` have what the comment «Dati personali · dettaglio» of the analysis asks for, `docs/05` has the entry of
 * the ticket (with the answers of Matteo) and the design guide has the pieces that the ticket adds. No page here: a text that is not written fails the build.
 */
class CheckinDocsTests {

    private fun doc(path: String): String = Files.readString(Path.of(path)).replace(Regex("""\s+"""), " ")

    @Test
    fun `t6 c31 the data model has the presences and the payments with their purpose, who sees them and when they go`() {
        val data = doc("docs/02-modello-dati.md")

        for (part in listOf(
            "Presenza (`checkin`, `V6__checkin.sql`)", "registro della convivenza (chi c'era ogni giorno), riepilogo del giorno, elenco per la struttura del giorno",
            "maestro che ha segnato la presenza (account)", "rendere conto delle correzioni fra maestri",
            "Pagamento (`payment`)", "quota pagata (la somma dei pagamenti), saldo, totale incassato", "Non cambiano quando cambiano le quote o i giorni dell'iscrizione (R2)",
            "ci vanno gli affidatari", "si chiama dalla scheda del bambino e si stampa nel foglio per i maestri, mai nel registro",
            "con TALITA-6 anche da «Mostra le segnalazioni» della scheda del check-in, che passa dalla stessa classe",
            "La struttura che ospita la convivenza vede solo l'elenco stampato, con nome e cognome", "Le presenze e i pagamenti vanno via con l'iscrizione (`on delete cascade`)",
            "la parrocchia del bambino **non si raccoglie** e le note del registro **non si raccolgono nell'app**", "note libere (nel registro, nella scheda del check-in o altrove)",
        )) {
            assertTrue(data.contains(part), "docs/02 does not say: $part")
        }
        assertFalse(data.contains("| Check-in | arrivo per giorno, quota pagata |"), "the old row of the check-in is replaced")
        assertFalse(data.contains("Comunità del bambino e note del registro (TALITA-6)"), "the two questions of TALITA-6 are closed")
    }

    @Test
    fun `t6 c31 the privacy document has the interested, the basis, the recipient on paper, the measures, the paper and the times of the check-in`() {
        val privacy = doc("docs/03-privacy.md")

        for (part in listOf(
            "presenze per giorno e pagamenti (TALITA-6)", "l'account che lo ha fatto (TALITA-6)",
            "**Presenze e pagamenti** (TALITA-6)", "esecuzione della richiesta dell'interessato (art. 6.1.b), come per l'iscrizione",
            "**La struttura che ospita la convivenza** (TALITA-6) non è un fornitore dell'app: riceve **su carta** l'elenco per la struttura, con **solo nome e cognome**",
            "**Check-in e stampe** (TALITA-6)", "nessun nome negli indirizzi web né nei log", "**solo in quella risposta**", "nessun service worker",
            "## Carta", "**Registro arrivi**", "**Elenco per la struttura**", "**Foglio per i maestri**", "**Niente recapiti, niente segnalazioni**", "**Segnalazioni su carta: no.**", "**non c'è nessuna deroga** alla regola 4",
            "**presenze e pagamenti** (TALITA-6): vanno via **con l'iscrizione**", "il foglio per i maestri si distrugge alla fine della convivenza", "le stampe di Arrivi da Safari su iPhone",
        )) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        assertTrue(privacy.indexOf("## Carta") < privacy.indexOf("## Conservazione"), "the paper comes before the times")
    }

    @Test
    fun `t6 c31 the decision log has the entry of TALITA-6 after the earlier ones, with the answers of Matteo and what is not in the ticket`() {
        val log = doc("docs/05-decisioni.md")

        val entry = log.substringAfter("## 2026-10-24: scelte di TALITA-6")
        assertTrue(log.contains("## 2026-10-24: scelte di TALITA-6"))
        assertTrue(log.indexOf("## 2026-10-24: camere, cucina e solleciti nel primo rilascio (TALITA-1)") < log.indexOf("## 2026-10-24: scelte di TALITA-6"), "the entries are in order")
        assertTrue(log.indexOf("## 2026-10-23: scelte di TALITA-5") < log.indexOf("## 2026-10-24: scelte di TALITA-6"))
        for (part in listOf(
            "**Segnalazioni mai su un foglio stampato**", "(domanda 6: no)", "**La parrocchia del bambino non si raccoglie**", "**Niente note libere nell'app**", "**Un solo ticket e una PR**",
            "**Differenza dall'analisi**", "`Registrations.lock`", "**Il form porta un codice della richiesta**", "**Zero in parole**", "`Pagination`", "**Safari su iPhone lo prova Matteo prima del rilascio**",
            "**Differenze dal codice di TALITA-5**", "**Dove il design taceva**",
        )) {
            assertTrue(entry.contains(part), "the entry of TALITA-6 misses: $part")
        }
        // The kitchen is not in this ticket, and it is not said to be out of the first release: it is in TALITA-12.
        assertTrue(entry.contains("la cucina e il menù non sono in questo ticket: sono in TALITA-12"), "the kitchen is in TALITA-12")
        assertFalse(entry.contains("fuori dal primo rilascio"), "nothing in this entry puts a function out of the first release")
    }

    @Test
    fun `t6 c31 the design guide has the pieces that the ticket adds, and the mockups are in the repository`() {
        val guide = doc("docs/design/README.md")

        for (part in listOf(
            "`docs/design/TALITA-6/` sono i mockup di Arrivi", "con `Shell.wide`", "**Azione rapida**", "**«Togli»**", "**«Chiama»**", "**«Stampa»**", "**Riga con azione rapida**",
            "**Tabelle stampabili**", "`.blocco-stampa`", "**Una pagina di carta è un blocco**", "### 4.20 Selettore di giorno", "### 4.21 Riepilogo con numeri", "### 4.22 Ricerca in una lista",
            "### Arrivi (TALITA-6)", "`components/selectField.jte`", "«Arrivi» è la voce corrente in `/arrivi`", "**Le risposte 409 si sostituiscono come le 422**", "«Pagata Niente»",
        )) {
            assertTrue(guide.contains(part), "the guide does not say: $part")
        }
        assertEquals(2, Regex("""### Arrivi \(TALITA-6\)""").findAll(guide).count(), "one section for htmx and one for the texts")
        assertTrue(Files.isRegularFile(Path.of("docs/design/TALITA-6/index.html")))
        assertTrue(Files.isRegularFile(Path.of("docs/design/TALITA-6/talita-6.css")))
        assertEquals(84, Files.list(Path.of("docs/design/TALITA-6")).use { it.count() }, "the 82 pages of the mockup, the index and the style")
    }
}
