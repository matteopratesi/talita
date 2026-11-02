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
            // The times are in the table of TALITA-7 (it replaced the list of the times of TALITA-6): presences and payments go with the registration, the sheet at the end.
            "| Presenze, pagamenti, registro delle letture | con l'iscrizione (cascata)", "il foglio per i maestri alla fine della convivenza (TALITA-6)", "le stampe di Arrivi da Safari su iPhone",
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

    // ---- TALITA-19: the arrival is for every day (criterion 20) ---------------------------------------------------------------------

    @Test
    fun `t19 c20 the data model and the privacy document have the rule of the arrival for every day, in the row of the presence and in the measures of the check-in`() {
        val data = doc("docs/02-modello-dati.md")
        val row = data.substringAfter("| Presenza (`checkin`, `V6__checkin.sql`) |").substringBefore("| Presenza (`checkin`) |")
        for (part in listOf(
            "il segno dell'arrivo scrive anche le righe dei giorni dopo, di fila, con il maestro e il momento dell'arrivo",
            "una riga di un giorno dopo oggi diventa presenza quel giorno, se nessuno la toglie",
            "al ritiro e al cambio dei giorni le righe dopo oggi che non valgono più si cancellano", "Lo scopo non cambia", "non c'è nessun campo nuovo", "| TALITA-6, 19 |",
        )) {
            assertTrue(row.contains(part), "the row «Presenza» of docs/02 does not say: $part")
        }

        val privacy = doc("docs/03-privacy.md")
        val measures = privacy.substringAfter("**il segno dell'arrivo vale anche per i giorni dopo** (TALITA-19)").substringBefore("- **le due informative lo dicono**")
        for (part in listOf("righe dei giorni dopo a cui il bambino è iscritto, di fila", "diventa presenza quel giorno, se nessuno la toglie", "le righe dopo oggi che non valgono più si cancellano", "nessun dato nuovo")) {
            assertTrue(measures.contains(part), "the measures of the check-in in docs/03 do not say: $part")
        }
    }

    @Test
    fun `t19 c20 the decision log has the entry of TALITA-19 with the three choices of Matteo, the definition of present and no new field`() {
        val log = doc("docs/05-decisioni.md")

        assertTrue(log.contains("## 2026-11-02: scelte di TALITA-19"))
        assertTrue(log.indexOf("## 2026-10-31: scelte di TALITA-16") < log.indexOf("## 2026-11-02: scelte di TALITA-19"), "the entries are in order")
        val entry = log.substringAfter("## 2026-11-02: scelte di TALITA-19")
        for (part in listOf(
            "**Nessuna migrazione e nessun campo nuovo**", "**L'arrivo vale per i giorni iscritti di fila.**", "**I giorni dopo oggi sono presenze solo dal loro giorno.**",
            "**«Togli» toglie il giorno e i giorni dopo di fila che hanno una riga**", "**Cosa vuol dire «presente» (criterio 7)**", "`ArrivalsData.present(registration, day)`",
            "l'iscrizione non è ritirata, c'è la riga di `checkin` di quell'iscrizione e di quel giorno, e il giorno non è dopo oggi", "**Nessun campo nuovo**", "`FuturePresences`", "`CorrectionListener`",
            "**La nota di riga cambia il criterio 16**", "**I test di TALITA-6 che cambiano**",
        )) {
            assertTrue(entry.contains(part), "the entry of TALITA-19 misses: $part")
        }
    }

    @Test
    fun `t19 c20 the design guide has the rule and the words of the arrival for every day, with the legend of the register and the icon, and the mockups are in the repository`() {
        val guide = doc("docs/design/README.md")

        for (part in listOf(
            "`docs/design/TALITA-19/` sono i mockup del segno dell'arrivo su più giorni", "| Giorno dopo oggi già segnato: «Sarà presente» (TALITA-19) | `calendar-check` |",
            "«Sarà presente»", "«Conta come presente da domenica.»", "«Togli vale anche per sabato e domenica.»", "«Segna presente e Togli valgono anche per i giorni dopo.»", "`CheckinFormat.daysInSentence`",
            "«con l’arrivo, venerdì 17 alle 18:47»", "«Presenza segnata per venerdì, sabato e domenica.»", "«Presenza tolta per sabato e domenica.»", "«Tolta anche per domenica.»", "«Segnata anche per domenica.»",
            "**Quando la riga ha la nota.**", "**La regola sta scritta una volta sola**", "**Nessun bersaglio nuovo.**", PrintPresenter.LEGEND,
        )) {
            assertTrue(guide.contains(part), "the guide does not say: $part")
        }
        assertEquals(2, Regex("""### Arrivi \(TALITA-19\)""").findAll(guide).count(), "one section for htmx and one for the texts")
        assertTrue(Files.isRegularFile(Path.of("docs/design/TALITA-19/index.html")))
        assertTrue(Files.isRegularFile(Path.of("docs/design/TALITA-19/talita-19.css")))
        assertEquals(22, Files.list(Path.of("docs/design/TALITA-19")).use { it.count() }, "the 20 pages of the mockup, the index and the style")
    }

    @Test
    fun `t19 c20 the complete notice says that the mark of the arrival is for the days after it too, until a teacher takes it away`() {
        val notice = doc("src/main/jte/registration/informativa.jte")

        assertTrue(
            notice.contains(
                "le presenze, giorno per giorno (il giorno e l’ora in cui un maestro segna che un bambino c’è; il segno dell’arrivo vale anche per i giorni dopo a cui il bambino è iscritto, " +
                    "finché un maestro non lo toglie)",
            ),
        )
    }
}
