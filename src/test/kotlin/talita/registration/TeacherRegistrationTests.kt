package talita.registration

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.account.TestAccounts
import talita.gathering.body
import talita.gathering.errorOf
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.REGISTRATION
import talita.ui.Alert
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registrations as the teachers see them (criteria 20-28, 29): the list with its counts and no word about health, the detail, the notes that come only
 * on request and leave a row in the register, the paper forms, the corrections, the possible duplicates, and what a parent cannot do on these addresses.
 * Teachers have entered with the link; names, dates and numbers are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class TeacherRegistrationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val healthNotes: HealthNotes,
) {

    private val kit = RegistrationTestKit(mockMvc, tokens, dsl, clock)
    private val rows = RegistrationRows(dsl, clock)

    private fun text(html: String): String = talita.gathering.text(html).replace(' ', ' ')

    private fun flash(browser: MockBrowser): String? = ((browser.cookie as MockHttpSession).getAttribute("talita.flash") as? Alert)?.text

    private fun html(browser: MockBrowser, path: String): String = browser.get(path).body()

    private fun htmx(): org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = { header("HX-Request", "true") }

    /** The account of a parent who has entered with the link. */
    private fun parentAccount(name: String = "genitore"): Long {
        val email = talita.Addresses.unique(name)
        kit.kit.parent(email)
        return kit.accountId(email)
    }

    // ---- The list (criteria 20, 24) -------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c20 the list shows the gathering, the counts, the money and a row for each child by surname and name, with the labels`() {
        val setup = kit.start()
        val a = parentAccount()
        val lucaRossi = rows.register(setup.id, a, "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val annaRossi = rows.register(setup.id, a, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.maybe, days = mapOf(setup.saturday to "full", setup.sunday to "lunch"))
        val marcoBianchi = rows.register(setup.id, null, "Marco", "Bianchi", LocalDate.of(2015, 6, 1), enteredBy = setup.martaId, paperReceived = true)
        val soleVerdi = rows.register(setup.id, null, "Sole", "Verdi", LocalDate.of(2017, 2, 3), enteredBy = setup.martaId)
        val withdrawn = rows.register(setup.id, a, "Zoe", "Zani", LocalDate.of(2014, 9, 9), status = RegistrationStatus.withdrawn)
        // Notes on health exist, and the list does not say so.
        val elena = rows.register(setup.id, null, "Elena", "Marino", LocalDate.of(2016, 10, 1), enteredBy = setup.martaId, notes = Notes("Arachidi", null, null), notesOnPaper = true)

        val page = setup.marta.get("/iscrizioni")
        val body = page.body()
        val words = text(body)

        assertEquals(200, page.status())
        assertEquals("Iscrizioni", heading(body))
        assertTrue(words.contains("Convivenza di settembre 2027 · ${talita.gathering.GatheringFormat.range(setup.friday, setup.sunday)}"), words)
        assertTrue(Regex("""<a href="/iscrizioni/nuova\?convivenza=${setup.id}"[^>]*>[\s\S]*?Inserisci un’iscrizione</a>""").containsMatchIn(body))
        assertTrue(words.contains("In breve Confermate 4 In forse 1 Ritirate 1 Quota delle confermate 240 € Quota di chi è in forse 35 €"), words)
        // By surname and name: Bianchi, Marino, Rossi Anna, Rossi Luca, Verdi, Zani.
        val order = Regex("""<a href="/iscrizioni/(\d+)" class="flex min-h-16""").findAll(body).map { it.groupValues[1].toLong() }.toList()
        assertEquals(listOf(marcoBianchi, elena, annaRossi, lucaRossi, soleVerdi, withdrawn), order)
        val firstAge = LocalDate.of(2015, 6, 1).let { RegistrationFormat.ageText(it, setup.friday) }
        assertTrue(words.contains("MB Marco Bianchi Confermata Cartacea ricevuta $firstAge · Tutti i giorni · 60 €"), words)
        assertTrue(words.contains("AR Anna Rossi In forse Accettata online ${RegistrationFormat.ageText(LocalDate.of(2019, 1, 9), setup.friday)} · Sab ${setup.saturday.dayOfMonth} · pranzo Dom ${setup.sunday.dayOfMonth} · 35 €"), words)
        assertTrue(words.contains("SV Sole Verdi Confermata Da ricevere"), words)
        assertTrue(words.contains("ZZ Zoe Zani Ritirata ${RegistrationFormat.ageText(LocalDate.of(2014, 9, 9), setup.friday)} · Tutti i giorni · Non dovuta"), words)
        assertFalse(words.contains("Zoe Zani Ritirata Accettata online"), "a withdrawn one has no label of manleva")
        // Nothing about health: not the text, not the word, not whether there is any.
        assertFalse(body.contains("Arachidi"))
        assertFalse(words.contains("egnalazion") || words.contains("llergi"), words)
        assertTrue(Regex("""<a href="/iscrizioni" aria-current="page"""").containsMatchIn(body))
        assertEquals(listOf("Home", "Convivenza", "Iscrizioni", "Arrivi"), Regex("""<span class="max-w-full truncate">([^<]+)</span>""").findAll(body).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `t5 c20 the list takes the gathering that starts last that is not a draft, the others come from their id, and the empty and the missing cases have their words`() {
        val setup = kit.start()
        val a = parentAccount()
        rows.register(setup.id, a, "Luca", "Rossi")
        val next = kit.nextGathering(setup)
        rows.register(next.id, a, "Anna", "Rossi")

        val latest = text(html(setup.marta, "/iscrizioni"))
        assertTrue(latest.contains("Convivenza dell’anno dopo"), latest)
        assertTrue(latest.contains("Anna Rossi") && !latest.contains("Luca Rossi"))
        val older = text(html(setup.marta, "/iscrizioni?convivenza=${setup.id}"))
        assertTrue(older.contains("Convivenza di settembre 2027") && older.contains("Luca Rossi") && !older.contains("Anna Rossi"), older)
        assertEquals(404, setup.marta.get("/iscrizioni?convivenza=999999").status())
        // The card of the detail of the gathering leads to the list of an older one: counts only, never names.
        assertTrue(Regex("""<a href="/iscrizioni\?convivenza=${setup.id}"[^>]*>[\s\S]*?Vedi le iscrizioni""").containsMatchIn(html(setup.marta, "/convivenza/${setup.id}")))

        // A gathering with no registration: the empty state with the button.
        val empty = kit.start()
        val emptyHtml = html(empty.marta, "/iscrizioni")
        assertTrue(emptyHtml.contains(">Nessuna iscrizione</h2>"))
        assertTrue(text(emptyHtml).contains("Quando i genitori iscrivono i bambini, li trovi qui. Se un genitore ha compilato il modulo di carta, puoi inserirlo tu."))
        assertTrue(Regex("""<a href="/iscrizioni/nuova\?convivenza=${empty.id}"[^>]*>[\s\S]*?Inserisci un’iscrizione</a>""").containsMatchIn(emptyHtml))

        // Only a draft: a notice and no way to register, not even on paper.
        val draft = kit.start(status = GatheringStatus.draft)
        val draftHtml = html(draft.marta, "/iscrizioni")
        assertTrue(text(draftHtml).contains("La convivenza «Convivenza di settembre 2027» è ancora in bozza: le iscrizioni si fanno dopo l’apertura, anche quelle di carta. Apri le iscrizioni dalla pagina della convivenza."))
        assertTrue(Regex("""<a href="/convivenza/${draft.id}"[^>]*>Vai alla convivenza</a>""").containsMatchIn(draftHtml))
        assertFalse(draftHtml.contains("Inserisci un’iscrizione"))

        // No gathering at all.
        kit.kit.gatherings.reset()
        val none = html(draft.marta, "/iscrizioni")
        assertTrue(none.contains(">Nessuna convivenza in programma</h2>"))
        assertTrue(text(none).contains("Le iscrizioni arrivano dopo che hai creato la convivenza e aperto le iscrizioni."))
        assertTrue(Regex("""<a href="/convivenza/nuova"[^>]*>[\s\S]*?Crea la convivenza</a>""").containsMatchIn(none))
    }

    @Test
    fun `t5 c24 two registrations that are not withdrawn with the same name, surname and date of birth are a possible duplicate, twins with other names are not`() {
        val setup = kit.start()
        val a = parentAccount("a")
        val b = parentAccount("b")
        val first = rows.register(setup.id, a, "Nicolò", "Rossi", LocalDate.of(2016, 4, 4))
        val second = rows.register(setup.id, b, " nicolo ", "ROSSI", LocalDate.of(2016, 4, 4))
        val twin = rows.register(setup.id, a, "Marco", "Rossi", LocalDate.of(2016, 4, 4))

        val list = text(html(setup.marta, "/iscrizioni"))
        assertTrue(list.contains("Nicolò Rossi Confermata Accettata online Possibile doppione"), list)
        assertEquals(2, list.split("Possibile doppione").size - 1, "both rows have the label")
        assertFalse(list.contains("Marco Rossi Confermata Accettata online Possibile doppione"), "twins with other names are not duplicates")
        val detail = html(setup.marta, "/iscrizioni/$first")
        assertTrue(text(detail).contains("Possibile doppione. C’è un’altra iscrizione con lo stesso nome e la stessa data di nascita. Se è lo stesso bambino, ritirane una."))
        assertTrue(Regex("""<a href="/iscrizioni/$second"[^>]*aria-label="Apri l’altra iscrizione, fatta il [^"]+"[^>]*>[\s\S]*?Apri l’altra iscrizione</a>""").containsMatchIn(detail))
        assertFalse(text(html(setup.marta, "/iscrizioni/$twin")).contains("Possibile doppione"))
        // One of the two withdrawn: it is not a duplicate any more.
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).where(REGISTRATION.ID.eq(second)).execute()
        assertFalse(text(html(setup.marta, "/iscrizioni")).contains("Possibile doppione"))
        assertFalse(text(html(setup.marta, "/iscrizioni/$first")).contains("Possibile doppione"))
    }

    // ---- The detail and the notes (criteria 21, 22, 23) -----------------------------------------------------------------------------------

    @Test
    fun `t5 c21 the detail shows the child, the age, the phone as a link, the accepted text with its version, the moment and the address, and the button of the notes`() {
        val setup = kit.start()
        val owner = kit.parent("genitore-dettaglio")
        assertEquals(303, kit.register(owner, setup, kit.form(kit.child(0, "Anna", "Rossi", "9", "1", "2019", "days", "maybe", "day.sat" to "full", "day.sun" to "lunch", "allergies" to "Arachidi", "healthConsent" to "true"), phone = "+39 333 123 4567")).status())
        val row = kit.registrations(setup.id).single()
        val id = row.id!!

        val page = setup.marta.get("/iscrizioni/$id")
        val body = page.body()
        val words = text(body)

        assertEquals(200, page.status())
        assertEquals("Anna Rossi", heading(body))
        assertEquals(listOf("Bambino", "Iscrizione", "Segnalazioni", "Manleva", "Letture delle segnalazioni"), Regex("""<h2[^>]*>([^<]*)</h2>""").findAll(body).map { it.groupValues[1] }.filter { it != "Ritirare l’iscrizione?" }.toList(), "the notes come before the manleva")
        assertTrue(words.contains("Convivenza di settembre 2027 · ${talita.gathering.GatheringFormat.range(setup.friday, setup.sunday)}"))
        assertTrue(words.contains("In forse Accettata online"))
        assertTrue(words.contains("Data di nascita 9 gennaio 2019 Età ${RegistrationFormat.ageText(LocalDate.of(2019, 1, 9), setup.friday)} il primo giorno"), words)
        assertTrue(words.contains("Quando Giornata: sabato ${setup.saturday.dayOfMonth}. Solo pranzo: domenica ${setup.sunday.dayOfMonth}. Quota 35 € Telefono +39 333 123 4567"), words)
        assertTrue(Regex("""<a href="tel:\+393331234567"[^>]*>\+39 333 123 4567</a>""").containsMatchIn(body), "the phone calls")
        assertTrue(words.contains("Affidamento Accettato online il ${RegistrationFormat.moment(row.custodyAcceptedAt!!)} Accettato da ${kit.emailOf(row.accountId!!)} Versione del testo affidamento-1"), words)
        assertTrue(words.contains("Testo accettato Come lo ha letto il genitore"))
        assertTrue(words.contains("Affidamento di figli minorenni"), "the text kept is in the page, whole")
        // The notes: only the button and the sentences, nothing of what is written and nothing about whether there is anything.
        assertTrue(words.contains("Sono dati sulla salute. Ogni volta che le mostri, resta scritto chi le ha lette e quando."))
        assertTrue(words.contains("Mostra le segnalazioni Apertura…"))
        assertTrue(words.contains("Per vederle serve la rete: la lettura si registra prima di mostrarle."))
        assertFalse(body.contains("Arachidi"))
        assertTrue(words.contains("Nessuno le ha ancora lette. Dal più recente. Il registro dice chi e quando, non cosa c’era scritto."))
        assertEquals(0, kit.readingCount(), "opening the detail does not read the notes")
        assertTrue(Regex("""<form method="post" action="/iscrizioni/$id/mostra-segnalazioni" hx-post="/iscrizioni/$id/mostra-segnalazioni" hx-target="#segnalazioni" hx-swap="outerHTML"""").containsMatchIn(body))
        assertTrue(Regex("""<dialog id="ritira"""").containsMatchIn(body))
        assertTrue(words.contains("Correggi") && words.contains("Ritira l’iscrizione"))
    }

    @Test
    fun `t5 c22 «Mostra le segnalazioni» writes the reading and then shows the notes, or Nessuna segnalazione, and every reading is in the register`() {
        val setup = kit.start()
        val a = parentAccount()
        val withNotes = rows.register(setup.id, a, "Anna", "Rossi", notes = Notes("Arachidi", null, "Antistaminico in caso di reazione"))
        val without = rows.register(setup.id, a, "Luca", "Rossi", LocalDate.of(2015, 5, 5))
        val paper = rows.register(setup.id, null, "Elena", "Marino", enteredBy = setup.martaId, notes = Notes(null, "Lattosio", null), notesOnPaper = true)

        // With JavaScript: the answer is the card and, out of band, the card of the readings (never cached).
        val shown = setup.marta.submit("/iscrizioni/$withNotes", "/iscrizioni/$withNotes/mostra-segnalazioni", emptyMap(), htmx())
        val piece = shown.body()
        assertEquals(200, shown.status())
        assertTrue(shown.response.getHeader("Cache-Control").orEmpty().contains("no-store"))
        assertTrue(piece.trimStart().startsWith("""<section id="segnalazioni""""), piece.take(100))
        assertFalse(piece.contains("<html"))
        val words = text(piece)
        assertTrue(words.contains("Lettura registrata a tuo nome: "), words)
        assertTrue(words.contains("Allergie Arachidi Farmaci Antistaminico in caso di reazione"), words)
        assertTrue(words.contains("Consenso dato dal genitore il "))
        assertTrue(words.contains("Nascondi le segnalazioni"))
        assertFalse(words.contains("Correggi le segnalazioni"), "the notes of an online registration are the parent's")
        assertTrue(piece.contains("""<section id="letture" hx-swap-oob="true""""))
        assertTrue(words.contains("Marta Bianchi Sei tu"), words)
        assertEquals(1, kit.readingCount())
        val reading = dsl.selectFrom(HEALTH_NOTE_ACCESS).fetchSingle()
        assertEquals(withNotes, reading.registrationId)
        assertEquals(setup.martaId, reading.teacherAccountId)
        assertNotNull(reading.accessedAt)

        // With no notes: the same, and the reading is written all the same.
        val empty = setup.marta.submit("/iscrizioni/$without", "/iscrizioni/$without/mostra-segnalazioni", emptyMap(), htmx())
        assertTrue(text(empty.body()).contains("Nessuna segnalazione."))
        assertEquals(2, kit.readingCount())

        // Without JavaScript the answer is the whole page, once more not cached; reloading it asks again and writes another reading.
        val plain = setup.marta.submit("/iscrizioni/$paper", "/iscrizioni/$paper/mostra-segnalazioni", emptyMap())
        assertEquals(200, plain.status())
        assertEquals("Elena Marino", heading(plain.body()))
        assertTrue(text(plain.body()).contains("Intolleranze Lattosio"))
        assertTrue(text(plain.body()).contains("Il genitore ha dato il consenso sul modulo cartaceo."))
        assertTrue(text(plain.body()).contains("Correggi le segnalazioni"), "the notes of a paper registration can be corrected")
        assertEquals(3, kit.readingCount())
        // The detail afterwards still shows only the button, and the readings with the names.
        val detail = text(html(setup.marta, "/iscrizioni/$withNotes"))
        assertFalse(detail.contains("Arachidi"))
        assertTrue(detail.contains("Letture delle segnalazioni") && detail.contains("Sei tu"), detail)
    }

    @Test
    fun `t5 c22 if the reading cannot be written nothing is shown and the answer is an error, never the notes`() {
        val setup = kit.start()
        val a = parentAccount()
        val id = rows.register(setup.id, a, "Anna", "Rossi", notes = Notes("Arachidi", null, null))
        val page = setup.marta.get("/iscrizioni/$id")
        val csrf = MockBrowser.csrfIn(page.body())!!
        dsl.execute("alter table health_note_access add constraint test_cannot_write check (false) not valid")
        try {
            val failure = assertFailsWith<Exception> { setup.marta.post("/iscrizioni/$id/mostra-segnalazioni", emptyMap(), csrf) }
            assertFalse(generateSequence<Throwable>(failure) { it.cause }.any { (it.message ?: "").contains("Arachidi") }, "the failure does not carry the notes")
        } finally {
            dsl.execute("alter table health_note_access drop constraint test_cannot_write")
        }
        assertEquals(0, kit.readingCount())
        // The service says the same: it throws, and gives back nothing.
        dsl.execute("alter table health_note_access add constraint test_cannot_write check (false) not valid")
        try {
            assertFailsWith<Exception> { healthNotes.readForTeacher(id, setup.martaId) }
        } finally {
            dsl.execute("alter table health_note_access drop constraint test_cannot_write")
        }
        assertEquals(0, kit.readingCount())
    }

    @Test
    fun `t5 c23 only one class of the application uses the tables of the notes on health`() {
        val sources = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/kotlin")).use { stream -> stream.filter { java.nio.file.Files.isRegularFile(it) }.toList() }
        val users = sources.filter {
            val text = java.nio.file.Files.readString(it)
            text.contains("HEALTH_NOTE") || Regex("""tables\.(records\.)?HealthNote""").containsMatchIn(text) || text.contains("talita.jooq.tables.HealthNote")
        }.map { it.fileName.toString() }

        assertEquals(listOf("HealthNotes.kt"), users, "the tables of the notes are read and written by one class, the one that registers every reading")
        // The register has no text and does not say whether there was any (the columns say so).
        assertEquals(setOf("id", "registration_id", "teacher_account_id", "accessed_at"), dsl.meta().getTables("health_note_access").single().fields().map { it.name }.toSet())
    }

    // ---- The paper forms (criterion 25) --------------------------------------------------------------------------------------------------------

    private fun paperForm(setup: Setup, vararg more: Pair<String, String>, first: String = "Elena", last: String = "Marino", presence: String = "all_days", phone: String = "333 987 6543"): Map<String, String> =
        kit.form(kit.child(0, first, last, "1", "10", "2016", presence, "confirmed", *more), phone = phone) + ("convivenza" to setup.id.toString())

    @Test
    fun `t5 c25 a teacher types in a paper form for one child with no text to accept, and the registration has no account and says who typed it in`() {
        val setup = kit.start()

        val page = setup.marta.get("/iscrizioni/nuova?convivenza=${setup.id}")
        val html = page.body()
        val words = text(html)
        assertEquals(200, page.status())
        assertEquals("Inserisci un’iscrizione", heading(html))
        assertTrue(words.contains("Convivenza di settembre 2027 · "))
        assertTrue(words.contains("Per chi ha compilato il modulo di carta. Scrivi i dati come sono sul modulo, un bambino alla volta. L’iscrizione risulta inserita da te."))
        assertTrue(words.contains("Il modulo Telefono per i giorni della convivenza Il numero scritto sul modulo. Lo vedono solo i maestri."))
        assertTrue(words.contains("Manleva cartacea ricevuta Spunta quando hai in mano il modulo firmato. Se non c’è ancora, lascia vuoto: l’iscrizione resta «Da ricevere»."))
        assertTrue(words.contains("Scrivi qui allergie, intolleranze e farmaci come sono sul modulo. Le leggono solo i maestri, e ogni lettura resta registrata."))
        assertTrue(words.contains("Il genitore ha dato il consenso sul modulo cartaceo Spunta la casella solo se sul modulo il genitore ha firmato il consenso alle segnalazioni. Senza consenso non le salviamo."))
        assertFalse(words.contains("Controlla e accetta") || words.contains("affidamento"), "no text to accept")
        assertTrue(words.contains("Inserisci l’iscrizione Inserimento… Annulla"))
        assertTrue(html.contains("""<input type="hidden" name="convivenza" value="${setup.id}""""))

        val answer = setup.marta.submit("/iscrizioni/nuova?convivenza=${setup.id}", "/iscrizioni/nuova", paperForm(setup))
        assertEquals(303, answer.status())
        val row = kit.registrations(setup.id).single()
        assertEquals("/iscrizioni/${row.id}", answer.location())
        assertEquals("Iscrizione inserita.", flash(setup.marta))
        assertNull(row.accountId)
        assertNull(row.custodyText)
        assertEquals(setup.martaId, row.enteredByAccountId)
        assertFalse(row.paperFormReceived!!)
        assertEquals("333 987 6543", row.phone)
        assertEquals(1, kit.childCount())
        assertNull(dsl.select(talita.jooq.tables.references.CHILD.ACCOUNT_ID).from(talita.jooq.tables.references.CHILD).fetchSingle(talita.jooq.tables.references.CHILD.ACCOUNT_ID))
        val detail = text(html(setup.marta, "/iscrizioni/${row.id}"))
        assertTrue(detail.contains("Iscrizione inserita."))
        assertTrue(detail.contains("Confermata Da ricevere"), detail)
        assertTrue(detail.contains("Modulo cartaceo Da ricevere Inserita da Marta Bianchi"), detail)
        assertTrue(detail.contains("Segna come ricevuta"))
        assertTrue(detail.contains("Il modulo firmato resta di carta: qui si segna solo se è arrivato."))
        assertFalse(detail.contains("Testo accettato"))
        // The signed form arrives, and then it does not: both ways, with the words of the design.
        assertEquals(303, setup.marta.submit("/iscrizioni/${row.id}", "/iscrizioni/${row.id}/manleva", mapOf("received" to "true")).status())
        assertEquals("Manleva segnata come ricevuta.", flash(setup.marta))
        assertTrue(kit.registrations(setup.id).single().paperFormReceived!!)
        assertTrue(text(html(setup.marta, "/iscrizioni")).contains("Cartacea ricevuta"))
        assertEquals(303, setup.marta.submit("/iscrizioni/${row.id}", "/iscrizioni/${row.id}/manleva", mapOf("received" to "false")).status())
        assertEquals("Manleva segnata come da ricevere.", flash(setup.marta))
        assertFalse(kit.registrations(setup.id).single().paperFormReceived!!)
        // Or already ticked when it is typed in.
        assertEquals(303, setup.marta.submit("/iscrizioni/nuova?convivenza=${setup.id}", "/iscrizioni/nuova", paperForm(setup, first = "Sole", last = "Verdi") + ("paperReceived" to "true")).status())
        assertTrue(kit.registrations(setup.id).last().paperFormReceived!!)
    }

    @Test
    fun `t5 c25 the notes of a paper form are saved with the consent on paper, and without it they are refused with the words for the teachers`() {
        val setup = kit.start()
        val path = "/iscrizioni/nuova?convivenza=${setup.id}"

        val refused = setup.marta.submit(path, "/iscrizioni/nuova", paperForm(setup, "allergies" to "Arachidi"))
        assertEquals(422, refused.status())
        assertEquals("Per salvare le segnalazioni serve il consenso: spunta la casella. Se il genitore non l’ha dato sul modulo, svuota i campi.", errorOf(refused.body(), "b0-consenso"))
        assertEquals(0, kit.registrationCount(setup.id) + kit.childCount())
        val wrong = setup.marta.submit(path, "/iscrizioni/nuova", paperForm(setup, first = "", phone = "12"))
        assertEquals(422, wrong.status())
        assertEquals("Scrivi il nome.", errorOf(wrong.body(), "b0-nome"))
        assertEquals("Scrivi un numero di telefono completo, per esempio 333 123 4567.", errorOf(wrong.body(), "telefono"))

        assertEquals(303, setup.marta.submit(path, "/iscrizioni/nuova", paperForm(setup, "allergies" to "Arachidi", "healthConsent" to "true")).status())
        val note = dsl.selectFrom(HEALTH_NOTE).fetchSingle()
        assertEquals("Arachidi", note.allergies)
        assertTrue(note.consentOnPaper!!)
        assertNull(note.consentByAccountId)
        assertEquals(0, kit.readingCount(), "typing in the notes shows nothing to anyone")
    }

    @Test
    fun `t5 c25 c29 a teacher can type in a paper form with the registrations closed and when the gathering is over, and not in a draft`() {
        val setup = kit.start()
        val path = "/iscrizioni/nuova?convivenza=${setup.id}"
        val csrf = MockBrowser.csrfIn(setup.marta.get(path).body())!!

        kit.setState(setup.id, GatheringStatus.closed)
        val closed = setup.marta.get(path)
        assertEquals(200, closed.status())
        assertTrue(text(closed.body()).contains("Le iscrizioni sono chiuse: i genitori non possono più iscriversi, ma tu puoi inserire un modulo di carta."))
        assertEquals(303, setup.marta.post("/iscrizioni/nuova", paperForm(setup), csrf).status())

        kit.makeOver(setup.id)
        assertEquals(200, setup.marta.get(path).status())
        assertEquals(303, setup.marta.post("/iscrizioni/nuova", paperForm(setup, first = "Sole", last = "Verdi"), csrf).status())
        assertEquals(2, kit.registrationCount(setup.id))

        kit.setState(setup.id, GatheringStatus.draft)
        assertEquals(404, setup.marta.get(path).status())
        assertEquals(404, setup.marta.post("/iscrizioni/nuova", paperForm(setup, first = "Anna"), csrf).status())
        assertEquals(2, kit.registrationCount(setup.id), "a draft has no registrations, not even on paper")
    }

    // ---- The corrections (criterion 26) ---------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c05 c25 c26 the request for the quota of the paper form and of the correction carries only the days of the block`() {
        val setup = kit.start()
        val id = rows.register(setup.id, null, "Elena", "Marino", enteredBy = setup.martaId, notes = Notes("Arachidi", null, null), notesOnPaper = true)

        val days = setOf("children[0].presence", "children[0].day.fri", "children[0].day.sat", "children[0].day.sun", "_csrf")
        // The paper form says which gathering it is for in a field that the quota needs; the correction does not have it.
        assertEquals(days + "convivenza", kit.quotaRequestFields(setup.marta.get("/iscrizioni/nuova?convivenza=${setup.id}").body(), 1))
        assertEquals(days, kit.quotaRequestFields(setup.marta.get("/iscrizioni/$id/correggi").body(), 1))
    }

    @Test
    fun `t5 c26 a teacher corrects the days, the state, the phone and the data of an online registration and never the acceptance`() {
        val setup = kit.start()
        val owner = kit.parent("genitore-correzioni")
        assertEquals(303, kit.register(owner, setup, kit.form(kit.child(0, "Luca", more = arrayOf("allergies" to "Arachidi", "healthConsent" to "true")))).status())
        val before = kit.registrations(setup.id).single()
        val id = before.id!!
        val path = "/iscrizioni/$id/correggi"

        val page = setup.marta.get(path)
        val html = page.body()
        assertEquals(200, page.status())
        assertEquals("Correggi l’iscrizione", heading(html))
        assertTrue(text(html).contains("Luca Rossi · Convivenza di settembre 2027"))
        assertTrue(text(html).contains("I dati dell’iscrizione Telefono per i giorni della convivenza"))
        assertTrue(text(html).contains("L’affidamento accettato online non si cambia, e nemmeno le segnalazioni: le cambia solo il genitore."))
        assertFalse(html.contains("""name="paperReceived""""), "an online registration has no paper form")
        assertFalse(html.contains("Allergie, intolleranze, farmaci"), "the notes are not in the correction")
        assertTrue(text(html).contains("Salva le correzioni Salvataggio… Annulla"))

        val saved = setup.marta.submit(path, path, kit.form(kit.child(0, "Luca", "Rossi", "5", "4", "2016", "days", "maybe", "day.fri" to "lunch"), phone = "333 555 6666"))
        assertEquals(303, saved.status())
        assertEquals("/iscrizioni/$id", saved.location())
        assertEquals("Iscrizione salvata.", flash(setup.marta))
        val after = kit.registrations(setup.id).single()
        assertEquals(RegistrationStatus.maybe, after.status)
        assertEquals(mapOf(setup.friday to "lunch"), kit.daysOf(id))
        assertEquals("333 555 6666", after.phone)
        assertEquals(before.custodyText, after.custodyText)
        assertEquals(before.custodyAcceptedAt, after.custodyAcceptedAt)
        assertEquals(before.custodyAcceptedByEmail, after.custodyAcceptedByEmail)
        assertEquals("Arachidi", dsl.selectFrom(HEALTH_NOTE).fetchSingle().allergies, "the notes of the parent are not touched")
        assertEquals(0, kit.readingCount())
        // A wrong form comes back with the words of the first step.
        val wrong = setup.marta.submit(path, path, kit.form(kit.child(0, "", "Rossi"), phone = "1"))
        assertEquals(422, wrong.status())
        assertEquals("Scrivi il nome.", errorOf(wrong.body(), "b0-nome"))
        // The notes of an online registration have no page for the teachers.
        assertEquals(404, setup.marta.get("/iscrizioni/$id/correggi-segnalazioni").status())
    }

    @Test
    fun `t5 c26 the correction of a paper registration has the box of the manleva, and the notes are corrected from a page that shows them and writes a reading`() {
        val setup = kit.start()
        val withNotes = rows.register(setup.id, null, "Elena", "Marino", enteredBy = setup.martaId, notes = Notes("Arachidi", null, null), notesOnPaper = true)
        val without = rows.register(setup.id, null, "Sole", "Verdi", LocalDate.of(2017, 2, 3), enteredBy = setup.martaId)

        val page = setup.marta.get("/iscrizioni/$withNotes/correggi").body()
        assertTrue(text(page).contains("Manleva cartacea ricevuta"))
        assertTrue(text(page).contains("Ci sono segnalazioni. Per cambiarle mostrale prima dal dettaglio: la lettura resta registrata."))
        val noNotes = setup.marta.get("/iscrizioni/$without/correggi").body()
        assertTrue(Regex("""<a href="/iscrizioni/$without/correggi-segnalazioni"[^>]*>[\s\S]*?Aggiungi le segnalazioni</a>""").containsMatchIn(noNotes))
        assertEquals(0, kit.readingCount(), "the corrections of the data read nothing")

        val ticked = setup.marta.submit("/iscrizioni/$withNotes/correggi", "/iscrizioni/$withNotes/correggi", kit.form(kit.child(0, "Elena", "Marino", "1", "10", "2016")) + ("paperReceived" to "true"))
        assertEquals(303, ticked.status())
        assertTrue(kit.registrations(setup.id).first { it.id == withNotes }.paperFormReceived!!)

        // The page of the notes shows them, so the reading is written first; saving is a reading too.
        val shown = setup.marta.get("/iscrizioni/$withNotes/correggi-segnalazioni")
        assertEquals(200, shown.status())
        assertEquals("Correggi le segnalazioni", heading(shown.body()))
        assertTrue(text(shown.body()).contains("Elena Marino · Convivenza di settembre 2027"))
        assertTrue(text(shown.body()).contains("Questa pagina mostra le segnalazioni: la lettura è stata registrata a tuo nome."))
        assertTrue(text(shown.body()).contains("Segnalazioni di Elena"))
        assertEquals("Arachidi", talita.gathering.valueOf(shown.body(), "b0-allergie"))
        assertEquals(1, kit.readingCount())
        // The browser asks for the page and then sends it back: the page was the one reading, so one reading for the page and one for the saving.
        val csrf = MockBrowser.csrfIn(shown.body())!!
        val saved = setup.marta.post("/iscrizioni/$withNotes/correggi-segnalazioni", mapOf("allergies" to "Polline", "healthConsent" to "true"), csrf)
        assertEquals(303, saved.status())
        assertEquals("Segnalazioni salvate.", flash(setup.marta))
        assertEquals("Polline", dsl.selectFrom(HEALTH_NOTE).fetchSingle().allergies)
        assertTrue(dsl.selectFrom(HEALTH_NOTE).fetchSingle().consentOnPaper!!)
        assertEquals(2, kit.readingCount())
        assertEquals(303, setup.marta.post("/iscrizioni/$withNotes/correggi-segnalazioni", emptyMap(), csrf).status())
        assertEquals("Segnalazioni tolte.", flash(setup.marta))
        assertEquals(0, kit.healthNoteCount())
        assertEquals(3, kit.readingCount())
        // Without notes the page is empty and reads nothing; without the consent it is refused with the words for the teachers.
        val empty = setup.marta.get("/iscrizioni/$without/correggi-segnalazioni")
        assertEquals(200, empty.status())
        assertEquals(3, kit.readingCount())
        val refused = setup.marta.submit("/iscrizioni/$without/correggi-segnalazioni", "/iscrizioni/$without/correggi-segnalazioni", mapOf("medications" to "Antistaminico"))
        assertEquals(422, refused.status())
        assertEquals("Per salvare le segnalazioni serve il consenso: spunta la casella. Se il genitore non l’ha dato sul modulo, svuota i campi.", errorOf(refused.body(), "b0-consenso"))
    }

    @Test
    fun `t5 c26 c14 a teacher withdraws any registration with the dialog, the notes go at once, and puts it back from the correction`() {
        val setup = kit.start()
        val a = parentAccount()
        val id = rows.register(setup.id, a, "Anna", "Rossi", notes = Notes("Arachidi", null, null))
        val detail = html(setup.marta, "/iscrizioni/$id")
        assertTrue(text(Regex("""<dialog id="ritira"[\s\S]*?</dialog>""").find(detail)!!.value).startsWith("Ritirare l’iscrizione? L’iscrizione di Anna Rossi risulterà ritirata: la quota non è più dovuta e i giorni non contano più. Le segnalazioni, se ci sono, vengono cancellate subito. Resta nell’elenco con lo stato «Ritirata» e puoi rimetterla quando vuoi."))
        assertTrue(Regex("""<a href="/iscrizioni/$id/ritira" data-apri="ritira"""").containsMatchIn(detail))
        assertEquals("Ritirare l’iscrizione?", heading(html(setup.marta, "/iscrizioni/$id/ritira")))

        val done = setup.marta.submit("/iscrizioni/$id", "/iscrizioni/$id/ritira", mapOf("from" to "confirmed"))
        assertEquals(303, done.status())
        assertEquals("/iscrizioni/$id", done.location())
        assertEquals("Iscrizione ritirata.", flash(setup.marta))
        assertEquals(RegistrationStatus.withdrawn, kit.registrations(setup.id).single().status)
        assertEquals(0, kit.healthNoteCount())
        val withdrawn = text(html(setup.marta, "/iscrizioni/$id"))
        assertTrue(withdrawn.contains("Ritirata") && withdrawn.contains("Quota Non dovuta") && withdrawn.contains("Rimetti l’iscrizione"), withdrawn)
        assertEquals(303, setup.marta.submit("/iscrizioni/$id", "/iscrizioni/$id/ritira", mapOf("from" to "confirmed")).status())

        val restore = html(setup.marta, "/iscrizioni/$id/correggi")
        assertEquals("Rimetti l’iscrizione", talita.gathering.heading(restore))
        assertEquals(303, setup.marta.submit("/iscrizioni/$id/correggi", "/iscrizioni/$id/correggi", kit.form(kit.child(0, "Anna", "Rossi", status = "maybe"))).status())
        assertEquals("Iscrizione rimessa.", flash(setup.marta))
        assertEquals(RegistrationStatus.maybe, kit.registrations(setup.id).single().status)
        // The parent of the registration cannot use the pages of the teachers for it: 404 for another parent on the withdrawal.
        assertEquals(404, kit.parent("altro").let { it.submit("/info/${setup.code}/iscrizione", "/iscrizioni/$id/ritira", emptyMap()) }.status())
    }

    // ---- What a parent cannot do (criterion 27) -------------------------------------------------------------------------------------------

    @Test
    fun `t5 c27 a parent who asks for an address of the teachers, even by hand and on their own registration, is refused and nothing changes`() {
        val setup = kit.start()
        val owner = kit.parent("genitore-vietato")
        assertEquals(303, kit.register(owner, setup, kit.form(kit.child(0, "Luca", more = arrayOf("allergies" to "Arachidi", "healthConsent" to "true")))).status())
        val id = kit.registrations(setup.id).single().id!!
        val snapshot = kit.registrations(setup.id).map { it.status to it.updatedAt to it.phone }
        val csrf = MockBrowser.csrfIn(owner.get(setup.form).body())!!

        for (path in listOf("/iscrizioni/$id", "/iscrizioni/nuova", "/iscrizioni/$id/mostra-segnalazioni", "/iscrizioni/$id/correggi", "/iscrizioni/$id/correggi-segnalazioni", "/iscrizioni/$id/manleva")) {
            assertEquals(403, owner.get(path).status(), "GET $path")
            assertEquals(403, owner.post(path, mapOf("received" to "true", "convivenza" to setup.id.toString()), csrf).status(), "POST $path")
        }
        assertEquals(snapshot, kit.registrations(setup.id).map { it.status to it.updatedAt to it.phone })
        assertEquals(0, kit.readingCount(), "nobody read the notes")
        // The list is theirs only, and the teachers' list is not in it.
        val own = text(owner.get("/iscrizioni").body())
        assertFalse(own.contains("In breve") || own.contains("Inserisci un’iscrizione"))
    }

    // ---- A teacher who is also a parent (criterion 28) --------------------------------------------------------------------------------------

    @Test
    fun `t5 c28 a teacher who registers their own children does it from the form of the parents and finds them in the list, and reading their notes is a reading`() {
        val setup = kit.start()
        assertEquals(303, kit.register(setup.marta, setup, kit.form(kit.child(0, "Giulia", "Bianchi", more = arrayOf("allergies" to "Polline", "healthConsent" to "true")))).status())

        val row = kit.registrations(setup.id).single()
        assertEquals(setup.martaId, row.accountId, "the account of the teacher, with the acceptance online")
        assertNotNull(row.custodyText)
        assertEquals("Iscrizione fatta.", flash(setup.marta))
        // After the confirmation the address is the list of the teachers, with the notice, and the child is there like the others.
        val list = setup.marta.get("/iscrizioni")
        val words = text(list.body())
        assertTrue(words.contains("Iscrizione fatta.") && words.contains("In breve"), words)
        assertTrue(words.contains("Giulia Bianchi"))
        assertFalse(words.contains("Polline"))
        // The notes of her own child, from the pages of the teachers, are a reading like any other.
        val show = setup.marta.submit("/iscrizioni/${row.id}", "/iscrizioni/${row.id}/mostra-segnalazioni", emptyMap())
        assertTrue(text(show.body()).contains("Allergie Polline"))
        assertEquals(1, kit.readingCount())
        // From the page of the parent, as the owner, they are not.
        val own = setup.marta.get("/iscrizioni/${row.id}/segnalazioni")
        assertEquals(200, own.status())
        assertEquals("Polline", talita.gathering.valueOf(own.body(), "b0-allergie"))
        assertEquals(1, kit.readingCount(), "the owner reading their own does not write a reading")
        // Another teacher cannot use the pages of the parent for it: 404.
        assertEquals(404, kit.parent("altro").get("/iscrizioni/${row.id}/segnalazioni").status())
    }

    // ---- Who read the notes, when the teacher is not one any more (criteria 21, 22) -----------------------------------------------------------

    @Test
    fun `t5 c22 a teacher who is not one any more is written as a teacher who is no longer among the teachers, in the readings and in who typed in the form`() {
        val setup = kit.start()
        val a = parentAccount()
        val id = rows.register(setup.id, a, "Anna", "Rossi", notes = Notes("Arachidi", null, null))
        val paper = rows.register(setup.id, null, "Elena", "Marino", enteredBy = setup.paoloId)
        assertEquals(200, setup.marta.submit("/iscrizioni/$id", "/iscrizioni/$id/mostra-segnalazioni", emptyMap()).status())
        dsl.insertInto(HEALTH_NOTE_ACCESS).set(HEALTH_NOTE_ACCESS.REGISTRATION_ID, id).set(HEALTH_NOTE_ACCESS.TEACHER_ACCOUNT_ID, setup.paoloId)
            .set(HEALTH_NOTE_ACCESS.ACCESSED_AT, java.time.OffsetDateTime.now(clock).minusHours(2)).execute()
        TestAccounts.removeTeacher(dsl, kit.emailOf(setup.paoloId))

        val detail = text(html(setup.marta, "/iscrizioni/$id"))
        assertTrue(detail.contains("Letture delle segnalazioni"))
        assertTrue(detail.contains("Marta Bianchi Sei tu") || detail.contains("Elisa Conti Sei tu") || detail.contains("Sei tu"), detail)
        assertTrue(detail.contains("Un maestro che non è più tra i maestri"), detail)
        assertTrue(text(html(setup.marta, "/iscrizioni/$paper")).contains("Inserita da un maestro che non è più tra i maestri"))
        assertTrue(detail.indexOf("Sei tu") < detail.indexOf("Un maestro che non è più tra i maestri"), "the most recent first")
    }

    // ---- Every new page (criterion 38) ---------------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c38 the pages of the registrations name nothing of the other project, have no style, no inline script, no handler and no link outside the app`() {
        val setup = kit.start()
        val owner = kit.parent("genitore-pagine")
        assertEquals(303, kit.register(owner, setup, kit.form(kit.child(0, "Anna", "Rossi", "9", "1", "2019", "days", "maybe", "day.sat" to "full", "allergies" to "Arachidi", "healthConsent" to "true"))).status())
        val id = kit.registrations(setup.id).single().id!!
        val paper = rows.register(setup.id, null, "Elena", "Marino", enteredBy = setup.martaId, notes = Notes("Lattosio", null, null), notesOnPaper = true)

        val pages = mutableMapOf<String, String>()
        // The parent: the two steps, the list, the modification, the notes, the pages that stand in for the dialogs.
        pages["step 1"] = owner.get(setup.form).body()
        pages["step 2"] = owner.submit(setup.form, setup.form, kit.form(kit.child(0, "Luca"))).body()
        for (path in listOf("/iscrizioni", "/iscrizioni/$id/modifica", "/iscrizioni/$id/segnalazioni", "/iscrizioni/$id/ritira", "/iscrizioni/$id/segnalazioni/togli", "/", "/informativa")) {
            pages[path] = owner.get(path).body()
        }
        // The teachers: the list, the detail (with the notes shown), the forms and the forms of the gathering.
        for (path in listOf(
            "/iscrizioni", "/iscrizioni/$id", "/iscrizioni/$paper", "/iscrizioni/nuova?convivenza=${setup.id}", "/iscrizioni/$id/correggi", "/iscrizioni/$paper/correggi",
            "/iscrizioni/$paper/correggi-segnalazioni", "/iscrizioni/$id/ritira", "/convivenza/${setup.id}", "/convivenza/${setup.id}/quote", "/convivenza/${setup.id}/date", "/convivenza/${setup.id}/affidatari",
        )) {
            pages["teacher $path"] = setup.marta.get(path).body()
        }
        pages["teacher notes shown"] = setup.marta.submit("/iscrizioni/$id", "/iscrizioni/$id/mostra-segnalazioni", emptyMap()).body()
        pages["teacher notes piece"] = setup.marta.submit("/iscrizioni/$id", "/iscrizioni/$id/mostra-segnalazioni", emptyMap(), htmx()).body()

        // Written in pieces: this file is under src/ and must not contain the words of the other project.
        val forbidden = listOf("koi" + "nè", "cen" + "tro", "comuni" + "tà", "fra" + "telli")
        for ((name, html) in pages) {
            assertTrue(html.length > 200, "$name is empty")
            for (word in forbidden) assertFalse(html.contains(word, ignoreCase = true), "'$word' is in $name")
            assertFalse(Regex("""<script(?![^>]*\ssrc=)""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: inline script")
            assertFalse(Regex("""<[^>]+\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: on* attribute")
            assertFalse(Regex("""<[^>]+\shx-on""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: hx-on")
            assertFalse(Regex("""<[^>]+\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: style attribute")
            assertFalse(Regex("""<style""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: style element")
            for (link in Regex("""\s(?:src|href|action|formaction)\s*=\s*["']([^"']*)["']""").findAll(html).map { it.groupValues[1] }) {
                assertFalse(Regex("""^(?:[a-z][a-z0-9+.-]*:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(link) && !link.startsWith("#") && !link.startsWith("tel:"), "$name: link outside the app $link")
            }
        }
    }

    @Test
    fun `t5 c36 the log has no name, phone, note or address while the teachers read, type in, correct and withdraw`(output: CapturedOutput) {
        val setup = kit.start()
        val id = rows.register(setup.id, parentAccount(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), phone = "333 777 8888", notes = Notes("Arachidi", null, null))

        setup.marta.submit("/iscrizioni/$id", "/iscrizioni/$id/mostra-segnalazioni", emptyMap())
        setup.marta.submit("/iscrizioni/nuova?convivenza=${setup.id}", "/iscrizioni/nuova", paperForm(setup, "allergies" to "Polline", "healthConsent" to "true", first = "Zeffirino", last = "Quagliotti", phone = "333 444 5555"))
        val paper = kit.registrations(setup.id).last().id!!
        setup.marta.submit("/iscrizioni/$paper/correggi", "/iscrizioni/$paper/correggi", kit.form(kit.child(0, "Zeffirino", "Quagliotti", "1", "10", "2016"), phone = "333 666 9999"))
        setup.marta.submit("/iscrizioni/$id", "/iscrizioni/$id/ritira", mapOf("from" to "confirmed"))
        setup.marta.submit("/iscrizioni/nuova?convivenza=${setup.id}", "/iscrizioni/nuova", paperForm(setup, first = "", phone = "12"))

        val log = output.all
        for (secret in listOf("Anna", "Rossi", "Zeffirino", "Quagliotti", "Arachidi", "Polline", "333 777", "333 444", "333 666", "3337778888", "2019", kit.emailOf(setup.martaId))) {
            assertFalse(log.contains(secret), "the log has '$secret': ${log.lines().filter { it.contains(secret) }.take(3)}")
        }
    }

    @Test
    fun `t5 c20 the cards of the gathering and the pages of the teachers use the same counts`() {
        val setup = kit.start()
        val a = parentAccount()
        rows.register(setup.id, a, "Luca", "Rossi")
        rows.register(setup.id, a, "Anna", "Rossi", status = RegistrationStatus.maybe)
        rows.register(setup.id, a, "Zoe", "Zani", status = RegistrationStatus.withdrawn)

        val card = text(html(setup.marta, "/convivenza/${setup.id}"))

        assertTrue(card.contains("Iscrizioni Vedi le iscrizioni 1 confermata · 1 in forse · 1 ritirata"), card)
        assertFalse(card.contains("Luca") || card.contains("Anna Rossi"), "counts, never names")
        kit.setState(setup.id, GatheringStatus.draft)
        assertTrue(text(html(setup.marta, "/convivenza/${setup.id}")).contains("Le iscrizioni arrivano dopo l’apertura: finché la convivenza è in bozza non ce ne sono, nemmeno da carta."))
    }
}
