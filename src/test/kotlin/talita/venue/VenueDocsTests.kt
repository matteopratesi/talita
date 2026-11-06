package talita.venue

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The documents of TALITA-20 (criterion 28), for what each part changes: `docs/02` has the venue and the new source of the place; `docs/03` the public texts and the preview of the link; `docs/01` the
 * venues; `docs/05` the entry of the ticket with the decisions of Matteo, the confirmation on the tax code of the teachers included; the design guide the pieces that the ticket adds; and the mockups
 * are in the repository, with no style, no script and nothing from outside. The test reads the files: a document that loses a sentence fails the build.
 */
class VenueDocsTests {

    private fun doc(path: String): String = Files.readString(Path.of(path)).replace(Regex("""\s+"""), " ")

    @Test
    fun `t20 c28 the data model has the venue with its purpose, its public texts and what is never collected, and the gathering takes its place from it`() {
        val data = doc("docs/02-modello-dati.md")

        for (part in listOf(
            "TALITA-20 le strutture, cioè le case dove si fa la convivenza (`venue`, con `V13__venues.sql`)", "`gathering.venue_id`",
            "| Struttura (`venue`, `V13__venues.sql`) | nome (da 1 a 60 caratteri, unico senza contare maiuscole e spazi ai lati) e luogo (da 1 a 200) | **Nessun dato personale**",
            "**Nome e luogo sono pubblici tutti e due, come il luogo lo era**", "**Niente referente, telefono, email, indirizzo diviso in campi, mappa o foto**",
            "una struttura che una convivenza usa non si toglie (chiave senza cascata)", "**dal TALITA-20 il luogo è quello della struttura**", "Titolo, nome e luogo della struttura e testi pubblici",
            "nel nome o nel luogo di una struttura", "un testo che il genitore ha aperto e non ancora accettato diventa vecchio e non si salva; il nome della struttura non è nel testo",
        )) {
            assertTrue(data.contains(part), "docs/02 does not say: $part")
        }
        // The old words about the place of the gathering are gone: the place is the venue's.
        assertFalse(data.contains("titolo, data di inizio e di fine, luogo, tre quote"), "the gathering has no place of its own")
    }

    @Test
    fun `t20 c28 the privacy document and the context say the five public texts, the preview of the link with the name, and the venues in the first release`() {
        val privacy = doc("docs/03-privacy.md")
        for (part in listOf(
            "un'anteprima con titolo, date, nome della struttura e luogo e nessuna immagine", "**Titolo, nome e luogo della struttura, informazioni e «cosa portare»** (i cinque testi che scrive un maestro",
            "**né il nome e il cognome di un bambino o di un maestro che l'app conosce**",
        )) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        val context = doc("docs/01-contesto.md")
        for (part in listOf(
            "**Le strutture sono nel primo rilascio** (TALITA-20", "la stessa casa torna negli anni", "**nessun referente**: né il nome né il telefono di persone", "date, struttura e luogo, giorni, quote",
        )) {
            assertTrue(context.contains(part), "docs/01 does not say: $part")
        }
    }

    @Test
    fun `t20 c28 the decision log has the entry of TALITA-20 after the earlier ones, with the decisions of Matteo and the confirmation on the tax code of the teachers`() {
        val log = doc("docs/05-decisioni.md")

        val entry = log.substringAfter("## 2026-11-02: scelte di TALITA-20")
        assertTrue(log.contains("## 2026-11-02: scelte di TALITA-20"), "the log has no entry of TALITA-20")
        assertTrue(log.indexOf("## 2026-10-31: scelte di TALITA-16") < log.indexOf("## 2026-11-02: scelte di TALITA-20"), "after the entry of TALITA-16")
        for (part in listOf(
            "**Le decisioni di Matteo (2/11/2026)**", "**Commento 11017: i moduli sono una stampa generata dall'app**", "**Commento 11020**", "**l'età al primo giorno**",
            "**Commento 11022: il codice fiscale dei maestri è confermato, compilato solo nel browser.**", "regola 3", "Talità non lo riceve e non lo salva", "un PDF salvato dal browser e mandato alla struttura",
            "fuori da Talità", "**Commento 11023**",
            "**Parte 1: strutture e luogo (criteri 1-11)**", "**`V13__venues.sql`**", "`venue_name_unique`", "`GatheringVenues`", "`Missing.VENUE`", "`GatheringChecks.publicLine`",
            "**Le due transazioni vanno in fila, nei due ordini**", "`VenueTextTests`", "`VenueRemoval.InUse`", "`VenueMigrationTests`", "`TeacherPaths`", "`Icon.BUILDING_2`",
        )) {
            assertTrue(entry.contains(part), "the entry of TALITA-20 does not say: $part")
        }
    }

    @Test
    fun `t20 c28 the design guide has the venues in the navigation, the icon, the row with more lines, the dialog, the choice of the venue, the answers and the words`() {
        val guide = doc("docs/design/README.md")

        for (part in listOf(
            "`docs/design/TALITA-20/` sono i mockup dell'anagrafica delle strutture", "73 pagine statiche", "`building-2`", "«Convivenza» è la voce corrente anche in `/strutture`",
            "**Riga di elenco con più righe di dettaglio** (`components/listLink.jte`, parametro `more`, TALITA-20)", "- **«Togliere {struttura}?»** (TALITA-20)", "`togli-struttura`",
            "- **La scelta della struttura** (`gathering/datesForm.jte`, TALITA-20)", "«Luogo di adesso: …»", "### Strutture (TALITA-20)", "**«Togli la struttura» è un form normale**",
            "«Usata da 1 convivenza», «Usata da 2 convivenze», «Nessuna convivenza la usa»", "`NAME_HELP`", "«Per usarla, scegli «Casa sul lago» in «Modifica titolo, date e luogo» della convivenza.»",
            "«Quando e dove» ha **Struttura**", "A convivenza finita non dicono né il nome né il luogo",
        )) {
            assertTrue(guide.contains(part), "the design guide does not say: $part")
        }
    }

    @Test
    fun `t20 c28 part 2 the documents say that the rooms are the venue's, the migration, the change of venue with people in the rooms, and that nothing is copied`() {
        val data = doc("docs/02-modello-dati.md")
        for (part in listOf(
            "**Resta con la struttura** (TALITA-20, `V14__venue_rooms.sql`)", "**Camere della struttura (TALITA-20, parte 2).**", "`room.venue_id`", "`(camera, struttura)` e `(convivenza, struttura)`",
            "una convivenza non cambia struttura finché ci sono persone nelle camere di prima", "le camere della convivenza più recente di ogni struttura, fra quelle che ne hanno, diventano della struttura con gli stessi id",
        )) {
            assertTrue(data.contains(part), "docs/02 does not say: $part")
        }
        assertFalse(data.contains("per copiare le camere l'anno dopo"), "the rooms are not copied any more")

        val privacy = doc("docs/03-privacy.md")
        for (part in listOf("**Le camere sono della struttura** (TALITA-20)", "le camere (nome e posti) restano con la struttura, senza nomi, per la convivenza dell'anno dopo", "cambiare la struttura di una convivenza toglie le persone dalle camere di prima")) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        assertFalse(privacy.contains("per copiarle l'anno dopo"), "the rooms are not copied any more")
        assertTrue(doc("docs/01-contesto.md").contains("**Le camere sono della struttura**: si scrivono nella sua pagina, in ogni momento"))

        val entry = doc("docs/05-decisioni.md").substringAfter("## 2026-11-02: scelte di TALITA-20")
        for (part in listOf(
            "**Parte 2: camere della struttura (criteri 12-19)**", "**`V14__venue_rooms.sql`**", "`room_name_in_venue`", "`room_child_gathering_venue`", "`VenueRoomsMigrationTests`", "`RoomAssignments`",
            "**Il cambio di struttura con persone nelle camere**", "`DatesResult.PeopleInRooms`", "`PlaceResult.OtherVenue`", "`talita.venue.VenueRoomsController`", "`RemoveResult.Removed`", "`Rooms.copyPrevious`",
            "**«La convivenza è finita: chi dorme dove non si cambia più.»**", "**Documenti (parte 2)**",
        )) {
            assertTrue(entry.contains(part), "the entry of TALITA-20 does not say: $part")
        }

        val guide = doc("docs/design/README.md")
        for (part in listOf(
            "- **Le camere della struttura** (parte 2) sono un blocco, `#camere-struttura`", "- **«Togliere {camera}?»** (TALITA-20, pagina della struttura)", "- **«Cambiare la struttura?»** (TALITA-20",
            "- **Le camere** (parte 2): nella pagina della struttura la card «Camere»", "`/strutture/{id}/camere/…`",
            "**Il dialogo resta aperto finché non arriva la risposta** e il bottone dice «Cambio…»",
            // The section of the rooms of TALITA-11 says what changed with TALITA-20, and not what is not there any more as if it was.
            "Dal TALITA-20 «Copia le camere» non c'è più", "«Camere copiate.» non c'è più", "«La convivenza è finita: chi dorme dove non si cambia più.» (dal TALITA-20; prima «le camere non si cambiano più»",
            "In questa convivenza non ci sono camere.",
        )) {
            assertTrue(guide.contains(part), "the design guide does not say: $part")
        }
        assertFalse(guide.contains("«Togli la camera» e «Copia le camere» sono `hx-post`"), "«Copia le camere» is not an action of the page any more")
    }

    @Test
    fun `t20 c28 part 3 the documents say the forms of the venue, that the document and the tax code of the teachers stay in the browser, what the venue receives, and the confirmation of Matteo on the tax code`() {
        val data = doc("docs/02-modello-dati.md")
        for (part in listOf(
            "| Modulo della struttura (`venue_form`, `V15__venue_forms.sql`) |", "**quattro scelte**", "**Nessun dato personale e nessun valore**", "Il database salva **quali colonne ci sono, mai cosa ci va dentro**",
            "Le quattro scelte sono spente di partenza", "«scrivi solo il nome del documento»", "l'età al primo giorno accanto al nome nelle camere (TALITA-11) e **nel modulo della struttura, se lo chiede** (TALITA-20)",
            "**mai la data di nascita**", "**i giorni di presenza nel modulo della struttura, se lo chiede** (TALITA-20)", "**il documento d'identità e il codice fiscale non si raccolgono e non si conservano**",
        )) {
            assertTrue(data.contains(part), "docs/02 does not say: $part")
        }
        assertFalse(data.contains("\n- dati dei maestri per la struttura oltre a nome e cognome;\n"), "the old line of «Mai» is rewritten")

        val privacy = doc("docs/03-privacy.md")
        for (part in listOf(
            "**Dal TALITA-20 la struttura può avere un suo modulo**", "l'**età dei bambini il primo giorno** (non la data di nascita) e i **giorni in cui sono presenti**",
            "**può ricevere su carta il documento d'identità e il codice fiscale** dei maestri affidatari", "**Talità non riceve e non conserva**", "| **Modulo della struttura** (TALITA-20) |",
            "**Mai**, dall'app: data di nascita, sesso, luogo di nascita, cittadinanza, documento, codice fiscale, recapiti, segnalazioni, quote, pagamenti, camere né le indicazioni dell'app",
            "**I campi dei maestri sul foglio del modulo** (TALITA-20, criteri 29-31)", "**fuori da ogni `<form>` e senza `name`**", "il controllo ortografico avanzato di Chrome manda il testo a Google",
            "`hx-history=\"false\"`", "`data-solo-carta`", "**lo stampa il browser come testo della cella**", "**la tastiera del telefono può imparare quello che si scrive e un'estensione del browser può leggerlo**",
            "**il PDF salvato dal telefono, e la carta, contengono i dati**", "**il modulo della struttura** (TALITA-20), da verificare con una persona competente", "la **base giuridica** dell'età e dei giorni di presenza",
            "**il codice fiscale dei maestri** (contiene sesso, data e luogo di nascita", "la prova della **stampa con i campi dei maestri da Safari su iPhone**",
        )) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }

        val context = doc("docs/01-contesto.md")
        assertTrue(context.contains("**I moduli che la struttura chiede** (parte 3 dello stesso ticket) sono stampe generate dall'app, da Arrivi"), "docs/01 does not say the forms")
        assertTrue(context.contains("**L'app non raccoglie i dati dei maestri per la struttura**"), "docs/01 does not say the teachers' data")

        val entry = doc("docs/05-decisioni.md").substringAfter("## 2026-11-02: scelte di TALITA-20")
        for (part in listOf(
            "**Parte 3: moduli e dati dei maestri (criteri 20-27 e 29-31)**", "`ArrivalsData.present`", "**`V15__venue_forms.sql`**", "`venue_form_name_in_venue`", "**Un modulo è un nome e quattro scelte, mai un valore**", "`ArrivalForms`", "`VenueFormRef`",
            "`talita.venue.VenueFormsController`", "`PublicText.DOCUMENT_CLOSING`", "`ModulePresenter`", "**Le righe dei bambini non hanno campi.**", "`Pagination.module`", "`ModuleLayout.daysFit`", "«pagina N di M»",
            "**I campi dei maestri sono nella pagina e basta**", "`data-solo-carta`", "`ModuleSheetE2eTest`", "**La conferma sul codice fiscale resta scritta in questa voce**", "**Documenti (parte 3)**",
        )) {
            assertTrue(entry.contains(part), "the entry of TALITA-20 does not say: $part")
        }

        val guide = doc("docs/design/README.md")
        for (part in listOf(
            "- **Le quattro caselle di un modulo** (`venue/formForm.jte`, TALITA-20, parte 3)", "- **I campi dei maestri sul foglio di un modulo** (`checkin/moduleSheet.jte`, TALITA-20, criteri 29-31)",
            "**Riga con azioni e riquadro d'icona** (`venue/roomsBlock.jte` e `venue/formsBlock.jte`, TALITA-20)", "**Scheda con lista e bottone in fondo** (TALITA-20: «Camere» e «Moduli» nella pagina della struttura)",
            "**Stampa del modulo di una struttura** (TALITA-20, parte 3", "**Nella tabella dei maestri i giorni restano finché al nome restano almeno 27 mm**", "- **I moduli della struttura** (parte 3) sono un blocco come le camere, `#moduli-struttura`",
            "**La stampa del modulo non ha htmx e non manda niente**", "- **I moduli** (parte 3): «Moduli» nella pagina della struttura",
        )) {
            assertTrue(guide.contains(part), "the design guide does not say: $part")
        }
    }

    @Test
    fun `t20 c28 the mockups are in the repository, 73 pages with the index and the stylesheet, with no style, no script and nothing from outside`() {
        val folder = Path.of("docs/design/TALITA-20")
        val files = Files.list(folder).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }

        assertEquals(75, files.size, "seventy-three pages, the index and the stylesheet: $files")
        assertTrue("index.html" in files && "talita-20.css" in files)
        assertTrue(files.all { it.endsWith(".html") || it == "talita-20.css" }, "only pages and the stylesheet: $files")
        for (name in files.filter { it.endsWith(".html") }) {
            val page = Files.readString(folder.resolve(name))
            assertFalse(Regex("""<[^>]+\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(page), "$name has a style attribute")
            assertFalse(Regex("""<script""", RegexOption.IGNORE_CASE).containsMatchIn(page), "$name has a script")
            assertFalse(Regex("""\s(?:src|href)\s*=\s*["'](?:https?:)?//""").containsMatchIn(page), "$name loads something from outside")
        }
        // The index says the part of every page.
        val index = Files.readString(folder.resolve("index.html"))
        for (part in listOf("Strutture e luogo (parte 1)", "Camere della struttura (parte 2)", "Moduli della struttura (parte 3)")) {
            assertTrue(index.contains(part), "the index does not say: $part")
        }
    }
}
