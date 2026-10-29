package talita.room

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The documents of the rooms (criterion 22): `docs/02` and `docs/03` have what the comment «Dati personali · dettaglio» of the analysis asks for, with the deletion at the end of the
 * gathering (the decision of Matteo on question 4) and not a month after it; `docs/05` has the entry of the ticket; the design guide has the pieces that the ticket adds, and the mockups
 * are in the repository, with no style, no script and no word that says the sex. No page here: a text that is not written fails the build.
 */
class RoomDocsTests {

    private fun doc(path: String): String = Files.readString(Path.of(path)).replace(Regex("""\s+"""), " ")

    @Test
    fun `t11 c22 the data model has the rooms and who sleeps where with their purpose, who sees them and what is never collected`() {
        val data = doc("docs/02-modello-dati.md")

        for (part in listOf(
            "| Camera (`room`, `V9__rooms.sql`) | nome e posti letto | **Nessun dato personale**: è la casa.", "mai chi ci dorme (un controllo rifiuta le parole sul sesso",
            "Chi dorme dove (`room_child`, `room_teacher`)", "preparare le camere prima di arrivare e sapere dove dorme ogni bambino, di notte e in un'emergenza", "il sesso non si raccoglie e il perché di una scelta non si scrive",
            "**Si cancella alla fine della convivenza**", "dal giorno dopo l'ultimo giorno", "l'età al primo giorno accanto al nome nelle camere", "nelle camere e nella loro stampa",
            "**Le camere (TALITA-11) le vedono solo i maestri**", "il motivo per cui un bambino dorme in una camera o vicino a un maestro", "`room_child` (iscrizione, convivenza, camera)",
        )) {
            assertTrue(data.contains(part), "docs/02 does not say: $part")
        }
        assertFalse(data.contains("un mese dopo la fine della convivenza (TALITA-11"), "the rooms do not go a month after the end")
    }

    @Test
    fun `t11 c22 the privacy document has the people, the basis, the measures, the paper and the time of the rooms, with the answer about the outside AI and the DPIA`() {
        val privacy = doc("docs/03-privacy.md")

        for (part in listOf(
            "e la camera in cui dormono (TALITA-11)", "- **Camere** (TALITA-11, chi dorme in quale camera): per i bambini è il servizio chiesto dal genitore con l'iscrizione", "art. 6.1.b", "legittimo interesse",
            "chi dorme in quale camera lo vedono e lo stampano **solo i maestri**", "**Niente sesso, niente motivo della scelta, niente note**",
            "**Nessun dato dei bambini verso un'IA esterna o un altro servizio, nemmeno con le sigle**", "art. 4.5, cons. 26", "art. 28", "artt. 44-46", "Niente esportazione in file.",
            "| **Camere** (TALITA-11) |", "**Niente età, presenze, recapiti né segnalazioni**", "si distrugge alla fine della convivenza, come il foglio per i maestri",
            "| Chi dorme dove (`room_child`, `room_teacher`, TALITA-11) | **alla fine della convivenza**", "le camere (nome e posti) restano, senza nomi, per copiarle l'anno dopo",
            "chi dorme dove va via il 20 settembre 2027", "**Comprende le camere** (TALITA-11)", "WP248", "467/2018", "«larga scala»",
        )) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        assertFalse(privacy.contains("Chi dorme dove (`room_child`, `room_teacher`, TALITA-11) | **un mese dopo"), "the table says the end of the gathering")
    }

    @Test
    fun `t11 c22 the decision log has the entry of TALITA-11 after the earlier ones, with the decision of Matteo on the time and the differences from the analysis`() {
        val log = doc("docs/05-decisioni.md")

        val entry = log.substringAfter("## 2026-10-29: scelte di TALITA-11")
        assertTrue(log.contains("## 2026-10-29: scelte di TALITA-11"), "the log has no entry of TALITA-11")
        assertTrue(log.indexOf("## 2026-10-24: scelte di TALITA-7") < log.indexOf("## 2026-10-29: scelte di TALITA-11"), "after the entry of TALITA-7")
        for (part in listOf(
            "decisione di Matteo, non la raccomandazione", "«Le camere sono legate alla struttura, per cui si svuotano alla fine della convivenza»", "**`V9__rooms.sql`, non `V8`**",
            "`WithdrawalListener`", "`RoomSummaries`", "`RoomsRule`", "`@Order(15)`", "`rooms = Period.ZERO`", "**Posti per notte: no.**", "`insert … on conflict do update`",
            "una camera che non c'è o è di un'altra convivenza è 404", "`Sab 18 · Dom 19`", "**La presenza accanto al nome**", "Safari su iPhone", "**G. Cosa resta fuori (per dopo, con un ticket se serve)**",
        )) {
            assertTrue(entry.contains(part), "the entry of TALITA-11 does not say: $part")
        }
    }

    @Test
    fun `t11 c22 the design guide has the list of the rooms with the choice, the notice of the beds and the print, and the mockups are in the repository`() {
        val guide = doc("docs/design/README.md")

        for (part in listOf(
            "`docs/design/TALITA-11/` sono i mockup delle camere", "| Camere (TALITA-11)", "`bed-double`", "- **Select** (`room/roomSelect.jte`, TALITA-11)", "**Il select non parte da solo**",
            "**Persona con «Cambia»**", "**Riga con scelta**", "**Un dialogo c'è solo se l'azione ha qualcosa da perdere**", "**Stampa a blocchi** (TALITA-11", "**Ogni camera è un blocco che non si spezza**",
            "### Camere (TALITA-11)", "**Un blocco che risponde intero a ogni azione**", "**Il fuoco non salta**", "`focus({ preventScroll: true })`", "«5 persone per 4 posti.»", "«Senza camera»",
            "«Convivenza» è la voce corrente anche in `/convivenza/{id}/camere`",
        )) {
            assertTrue(guide.contains(part), "the design guide does not say: $part")
        }
        val folder = Path.of("docs/design/TALITA-11")
        val files = Files.list(folder).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(22, files.size, "twenty pages, the index and the stylesheet: $files")
        assertTrue("index.html" in files && "talita-11.css" in files)
        for (name in files.filter { it.endsWith(".html") }) {
            val page = Files.readString(folder.resolve(name))
            assertFalse(Regex("""<[^>]+\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(page), "$name has a style attribute")
            assertFalse(Regex("""<script""", RegexOption.IGNORE_CASE).containsMatchIn(page), "$name has a script")
            assertFalse(Regex("""\s(?:src|href)\s*=\s*["'](?:https?:)?//""").containsMatchIn(page), "$name loads something from outside")
            assertFalse(page.contains("un mese dopo la fine della convivenza, come le segnalazioni"), "$name still says a month for the rooms")
        }
        // «Camera dei maschi» is the wrong value that shows the error of the name, written on purpose in a mockup and in no other: nothing else says the sex of who sleeps there.
        val sex = Regex("""masch|femmin|\bsesso\b""", RegexOption.IGNORE_CASE)
        for (name in files.filter { it.endsWith(".html") && it != "camera-nuova-errori.html" }) {
            val page = Files.readString(folder.resolve(name)).replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
            assertFalse(sex.containsMatchIn(page), "$name says the sex")
        }
    }
}
