package talita.retention

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.GatheringFormat
import talita.gathering.body
import talita.gathering.heading
import talita.gathering.status
import talita.gathering.text
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.RETENTION_LOG
import talita.registration.Notes
import talita.registration.RegistrationRows
import talita.registration.RegistrationTestKit
import talita.registration.Setup
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the pages say about the retention (criteria 20, 29-32): «Le tue iscrizioni» of the parent that only look, the card «Conservazione dei dati» of the teachers, the
 * notes that are gone (the same card for every registration, no reading written), the registrations that are gone (an empty state that says when and why), and the notices of
 * privacy, whose numbers come from the policy. The gathering of the kit ended some days ago: how many decides which dates have passed. Names and numbers are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RetentionPagesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val policy: RetentionPolicy,
    @Autowired private val texts: RetentionTexts,
) {

    private val kit = RegistrationTestKit(mockMvc, tokens, dsl, clock)
    private val rows = RegistrationRows(dsl, clock)

    @BeforeEach
    fun clean() {
        dsl.deleteFrom(RETENTION_LOG).execute()
    }

    private fun words(html: String): String = text(html).replace(' ', ' ')

    /** The gathering of the kit, closed, that ended [days] days ago (the last day is [days] days before today). */
    private fun endedDaysAgo(days: Long): Setup {
        val setup = kit.start()
        val today = kit.kit.gatherings.today()
        dsl.update(GATHERING).set(GATHERING.START_DATE, today.minusDays(days + 2)).set(GATHERING.END_DATE, today.minusDays(days)).set(GATHERING.STATUS, GatheringStatus.closed)
            .where(GATHERING.ID.eq(setup.id)).execute()
        return setup
    }

    private fun end(setup: Setup): LocalDate = dsl.select(GATHERING.END_DATE).from(GATHERING).where(GATHERING.ID.eq(setup.id)).fetchSingle(GATHERING.END_DATE)!!

    private fun notesDate(setup: Setup): String = GatheringFormat.date(policy.notesDeletionDate(end(setup)))

    private fun registrationsDate(setup: Setup): String = GatheringFormat.date(policy.registrationsDeletionDate(end(setup)))

    private data class Parent(val browser: MockBrowser, val id: Long)

    private fun parent(name: String = "genitore"): Parent {
        val email = Addresses.unique(name)
        val browser = kit.kit.parent(email)
        return Parent(browser, kit.accountId(email))
    }

    // ---- «Le tue iscrizioni», only looking (criterion 20) -----------------------------------------------------------------------------

    @Test
    fun `t7 c20 the card of a finished gathering says until when it is kept and when the notes go, and has nothing to change`() {
        val setup = endedDaysAgo(3)
        val a = parent()
        val luca = rows.register(setup.id, a.id, "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        val anna = rows.register(setup.id, a.id, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.withdrawn)

        val html = a.browser.get("/iscrizioni").body()
        val page = words(html)

        assertTrue(page.contains("Affidamento accettato il"), page)
        assertEquals(2, Regex("""Conservata fino al ${registrationsDate(setup)} come prova dell’affidamento""").findAll(page).count(), "on every card of a finished gathering")
        assertTrue(page.contains("Segnalazioni Scritte. Si cancellano il ${notesDate(setup)}."), page)
        assertTrue(page.contains("Segnalazioni Nessuna"), "Anna has none, and the date has not passed")
        assertFalse(Regex("""Modifica l’iscrizione|Ritira l’iscrizione|Rimetti l’iscrizione""").containsMatchIn(page), "a finished gathering is only looked at")
        assertTrue(page.contains("Togli le segnalazioni"), "the notes can still be taken away while they exist")
        assertTrue(Regex("""<svg[^>]*>.*?</svg>\s*<span class="min-w-0">Conservata""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(html), "with the icon of the archive")
        assertTrue(luca > 0 && anna > 0)
    }

    @Test
    fun `t7 c20 from the day of the date the notes are cancelled, the same for every registration, and after the year the registration is not there any more`() {
        val setup = endedDaysAgo(40)
        val a = parent()
        rows.register(setup.id, a.id, "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        rows.register(setup.id, a.id, "Anna", "Rossi", LocalDate.of(2019, 1, 9))

        val page = words(a.browser.get("/iscrizioni").body())

        val gone = "Cancellate il ${notesDate(setup)}, un mese dopo la fine della convivenza."
        assertEquals(2, Regex("""Segnalazioni ${Regex.escape(gone)}""").findAll(page).count(), "it does not say whether there were any")
        assertFalse(page.contains("Togli le segnalazioni"), "nothing left to take away")
        assertFalse(page.contains("Arachidi"))

        // A year after the end the registrations are not shown, and the page is the empty state of the parent.
        val old = endedDaysAgo(400)
        val b = parent("genitore-b")
        rows.register(old.id, b.id, "Sara", "Verdi")
        val empty = words(b.browser.get("/iscrizioni").body())
        assertTrue(empty.contains("Nessuna iscrizione"), empty)
        assertFalse(empty.contains("Sara"), "past its date the registration is not shown, even before the job has deleted it")
    }

    @Test
    fun `t7 c20 a registration of a child that was detached from the account is only looked at, with the date`() {
        val setup = kit.start()
        val a = parent()
        val anna = rows.register(setup.id, a.id, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.withdrawn)
        dsl.execute("insert into checkin (registration_id, day, recorded_at, recorded_by_account_id) values (?, ?, now(), ?)", anna, setup.friday, setup.martaId)
        dsl.execute("update child set account_id = null where id = (select child_id from registration where id = ?)", anna)

        val page = words(a.browser.get("/iscrizioni").body())

        assertTrue(page.contains("Anna Rossi Ritirata"), page)
        assertTrue(page.contains("Conservata fino al ${registrationsDate(setup)} come prova dell’affidamento"), page)
        assertFalse(page.contains("Rimetti l’iscrizione") || page.contains("Modifica l’iscrizione"), "the child is not the parent's any more: nothing to change")
    }

    // ---- «Conservazione dei dati» for the teachers (criterion 29) ---------------------------------------------------------------------

    @Test
    fun `t7 c29 the detail of the gathering says when the data go, with the date and the reason, and that the dates follow the end while it is not finished`() {
        val setup = kit.start()
        val html = setup.marta.get("/convivenza/${setup.id}").body()
        val page = words(html)
        val end = end(setup)

        assertTrue(page.contains("Conservazione dei dati Talità cancella da sola i dati di questa convivenza, ogni notte. Qui vedi quando."), page)
        assertTrue(page.contains("Segnalazioni Si cancellano il ${GatheringFormat.date(policy.notesDeletionDate(end))} Un mese dopo la fine della convivenza, perché servono se durante l’evento succede qualcosa."), page)
        assertTrue(
            page.contains(
                "Iscrizioni, manleve e registro arrivi Si cancellano il ${GatheringFormat.date(policy.registrationsDeletionDate(end))} " +
                    "Un anno dopo la fine della convivenza: servono come prova dell’affidamento. Vanno via anche le presenze, i pagamenti e il registro delle letture delle segnalazioni.",
            ),
            page,
        )
        assertTrue(page.contains("Su carta Da distruggere a partire dal ${GatheringFormat.date(policy.registrationsDeletionDate(end))} Vale per le manleve firmate e per il registro stampato."), page)
        assertTrue(page.contains("Le date seguono la fine della convivenza: se la cambi, cambiano anche queste."))
        // The card is the last of the page, after the information for the parents, and it has no button and no form.
        assertTrue(page.indexOf("Informazioni per i genitori") < page.indexOf("Conservazione dei dati"))
        val card = Regex("""<section[^>]*aria-labelledby="conservazione"[\s\S]*?</section>""").find(html)!!.value
        assertFalse(card.contains("<form") || card.contains("<button") || card.contains("<a "), "information, not an action")
        // If the end is moved, the dates move with it.
        dsl.update(GATHERING).set(GATHERING.END_DATE, end.plusDays(1)).where(GATHERING.ID.eq(setup.id)).execute()
        assertTrue(words(setup.marta.get("/convivenza/${setup.id}").body()).contains("Si cancellano il ${GatheringFormat.date(policy.notesDeletionDate(end.plusDays(1)))}"))
    }

    @Test
    fun `t7 c29 after the dates the card says Cancellate in the past tense, and without the note, whatever the database holds`() {
        val setup = endedDaysAgo(40)
        rows.register(setup.id, parent().id, "Luca", "Rossi", notes = Notes("Arachidi", null, null))

        val page = words(setup.marta.get("/convivenza/${setup.id}").body())

        assertTrue(page.contains("Segnalazioni Cancellate il ${notesDate(setup)} Un mese dopo la fine della convivenza."), "the notes are still in the table, but past their date: «Cancellate»")
        assertTrue(page.contains("Iscrizioni, manleve e registro arrivi Si cancellano il ${registrationsDate(setup)}"), "the registrations still wait for their date")
        assertFalse(page.contains("Le date seguono la fine della convivenza"), "after the end the dates do not change any more")

        val older = endedDaysAgo(400)
        val past = words(older.marta.get("/convivenza/${older.id}").body())
        assertTrue(past.contains("Iscrizioni, manleve e registro arrivi Cancellate il ${registrationsDate(older)} Un anno dopo la fine della convivenza, con le presenze, i pagamenti e il registro delle letture."), past)
        assertTrue(past.contains("Vedi le iscrizioni Cancellate il ${registrationsDate(older)}"), "and the row of the registrations says so: $past")
    }

    @Test
    fun `t7 c29 a draft has no card of retention, because it has no registrations`() {
        val setup = kit.start(status = GatheringStatus.draft)

        val page = words(setup.marta.get("/convivenza/${setup.id}").body())

        assertFalse(page.contains("Conservazione dei dati"), page)
    }

    // ---- Notes that are gone (criterion 30) -------------------------------------------------------------------------------------------------

    @Test
    fun `t7 c30 the detail of a registration and the card of Arrivi say that the notes were deleted, the same for all, with no button and no reading`() {
        val setup = endedDaysAgo(40)
        val a = parent()
        val withNotes = rows.register(setup.id, a.id, "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        val without = rows.register(setup.id, a.id, "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        val main = "Segnalazioni cancellate il ${notesDate(setup)}, un mese dopo la fine."
        val second = "Talità le cancella da sola, per tutte le iscrizioni. Il registro delle letture resta fino al ${registrationsDate(setup)}."

        for (id in listOf(withNotes, without)) {
            val detail = setup.marta.get("/iscrizioni/$id").body()
            val card = Regex("""<section id="segnalazioni"[\s\S]*?</section>""").find(detail)!!.value
            assertTrue(words(card).contains("Segnalazioni $main $second"), words(card))
            assertFalse(card.contains("Mostra le segnalazioni") || card.contains("<form") || card.contains("<button"), "no button")
            assertFalse(detail.contains("Arachidi"))
        }
        // The same card for the two, in the page of the detail and in the card of Arrivi.
        val one = Regex("""<section id="segnalazioni"[\s\S]*?</section>""").find(setup.marta.get("/iscrizioni/$withNotes").body())!!.value.replace(Regex("""/iscrizioni/\d+"""), "")
        val other = Regex("""<section id="segnalazioni"[\s\S]*?</section>""").find(setup.marta.get("/iscrizioni/$without").body())!!.value.replace(Regex("""/iscrizioni/\d+"""), "")
        assertEquals(one, other, "it does not say whether there were any")
        val card = words(Regex("""<section id="segnalazioni"[\s\S]*?</section>""").find(setup.marta.get("/arrivi/$withNotes").body())!!.value)
        assertTrue(card.contains("Segnalazioni $main $second"), card)
        assertFalse(card.contains("Mostra le segnalazioni"))

        // A request made by hand for «Mostra» after the date is the same page, 200, with no reading and no error.
        val before = dsl.fetchCount(HEALTH_NOTE_ACCESS)
        val answer = setup.marta.submit("/iscrizioni/$withNotes", "/iscrizioni/$withNotes/mostra-segnalazioni", emptyMap())
        assertEquals(200, answer.status())
        assertTrue(words(answer.body()).contains(main), words(answer.body()))
        val arrivi = setup.marta.submit("/arrivi/$withNotes", "/arrivi/$withNotes/segnalazioni", emptyMap())
        assertEquals(200, arrivi.status())
        assertTrue(words(arrivi.body()).contains(main))
        assertEquals(before, dsl.fetchCount(HEALTH_NOTE_ACCESS), "nothing was read, so nothing was written in the register")
    }

    @Test
    fun `t7 c30 before the date the cards are the ones of TALITA-5 and 6, with the button and no new text`() {
        val setup = endedDaysAgo(3)
        val luca = rows.register(setup.id, parent().id, "Luca", "Rossi", notes = Notes("Arachidi", null, null))

        val detail = words(setup.marta.get("/iscrizioni/$luca").body())
        val arrivi = words(setup.marta.get("/arrivi/$luca").body())

        for (page in listOf(detail, arrivi)) {
            assertTrue(page.contains("Mostra le segnalazioni"), page)
            assertFalse(page.contains("cancellate il"), page)
        }
    }

    // ---- Registrations that are gone (criterion 31) ----------------------------------------------------------------------------------

    @Test
    fun `t7 c31 Iscrizioni and Arrivi and the prints of a gathering whose registrations are gone say when and why, and the detail of one is a page that does not exist`() {
        val setup = endedDaysAgo(400)
        val luca = rows.register(setup.id, parent().id, "Luca", "Rossi")
        val date = registrationsDate(setup)

        val list = setup.marta.get("/iscrizioni?convivenza=${setup.id}")
        val listWords = words(list.body())
        assertEquals(200, list.status())
        assertEquals("Iscrizioni", heading(list.body()))
        assertTrue(listWords.contains("Convivenza di settembre 2027 · "), listWords)
        assertTrue(
            listWords.contains(
                "Iscrizioni cancellate Le iscrizioni di questa convivenza, con le manleve, sono state cancellate il $date, un anno dopo la fine: servivano come prova dell’affidamento. " +
                    "Restano le date e le informazioni della convivenza, senza nomi. Vai alla convivenza",
            ),
            listWords,
        )
        assertTrue(Regex("""<a href="/convivenza/${setup.id}"[^>]*>[\s\S]*?Vai alla convivenza</a>""").containsMatchIn(list.body()))
        assertFalse(listWords.contains("Inserisci un’iscrizione"), "no new registration for a gathering whose data are gone")
        assertFalse(listWords.contains("Luca"), listWords)

        for (path in listOf("/arrivi", "/arrivi/registro", "/arrivi/elenco", "/arrivi/foglio")) {
            val page = setup.marta.get(path)
            val said = words(page.body())
            assertEquals(200, page.status(), path)
            assertEquals("Arrivi", heading(page.body()), path)
            assertTrue(
                said.contains(
                    "Arrivi cancellati Gli arrivi, le presenze e i pagamenti di questa convivenza sono stati cancellati il $date, un anno dopo la fine: servivano come registro e come prova " +
                        "dell’affidamento. Restano le date e le informazioni della convivenza, senza nomi. Vai alla convivenza",
                ),
                "$path: $said",
            )
            assertFalse(said.contains("Luca") || said.contains("Registro arrivi Stampa"), path)
        }
        assertEquals(404, setup.marta.get("/iscrizioni/$luca").status())
        assertEquals(404, setup.marta.get("/arrivi/$luca").status())
    }

    @Test
    fun `t7 c31 a gathering that is not past its date has its lists as before`() {
        val setup = endedDaysAgo(40)
        rows.register(setup.id, parent().id, "Luca", "Rossi")

        val list = words(setup.marta.get("/iscrizioni?convivenza=${setup.id}").body())

        assertTrue(list.contains("Luca Rossi"), list)
        assertFalse(list.contains("Iscrizioni cancellate"))
    }

    // ---- What cannot be written after the dates (design part 3, 5: approved proposals) -------------------------------------------------------

    private fun paperForm(setup: Setup, vararg more: Pair<String, String>): Map<String, String> =
        kit.form(kit.child(0, "Elena", "Marino", "1", "10", "2016", "all_days", "confirmed", *more), phone = "333 987 6543") + ("convivenza" to setup.id.toString())

    @Test
    fun `t7 c31 a year after the end nobody can type in a paper form, the page and the post of the form, from the list or from Arrivi, do not exist`() {
        val setup = endedDaysAgo(400)
        val form = paperForm(setup)

        for (path in listOf("/iscrizioni/nuova?convivenza=${setup.id}", "/iscrizioni/nuova?convivenza=${setup.id}&ritorno=arrivi", "/iscrizioni/nuova")) {
            assertEquals(404, setup.marta.get(path).status(), path)
        }
        val csrf = checkNotNull(MockBrowser.csrfIn(setup.marta.get("/profilo").body()))
        assertEquals(404, setup.marta.post("/iscrizioni/nuova", form, csrf).status())
        assertEquals(404, setup.marta.post("/iscrizioni/nuova/quota", form, csrf).status())
        assertEquals(0, dsl.fetchCount(REGISTRATION))
    }

    @Test
    fun `t7 c30 a month after the end the paper form has no fields for the notes, says why, and does not write them even if they arrive`() {
        val setup = endedDaysAgo(40)
        val gone = "Le segnalazioni di questa convivenza sono state cancellate il ${notesDate(setup)}: non si possono più scrivere."

        val page = setup.marta.get("/iscrizioni/nuova?convivenza=${setup.id}")
        val html = page.body()

        assertEquals(200, page.status())
        assertTrue(words(html).contains(gone), words(html))
        assertFalse(html.contains("children[0].allergies") || html.contains("healthConsent"), "no field for the notes, no box of the consent")

        // A request made by hand with the notes writes the registration and not the notes.
        val answer = setup.marta.submit("/iscrizioni/nuova?convivenza=${setup.id}", "/iscrizioni/nuova", paperForm(setup, "allergies" to "Arachidi", "healthConsent" to "true"))
        assertEquals(303, answer.status())
        assertEquals(1, dsl.fetchCount(REGISTRATION))
        assertEquals(0, dsl.fetchCount(talita.jooq.tables.references.HEALTH_NOTE))
    }

    @Test
    fun `t7 c30 before the date the paper form has the fields for the notes as it had`() {
        val setup = endedDaysAgo(3)

        val html = setup.marta.get("/iscrizioni/nuova?convivenza=${setup.id}").body()

        assertTrue(html.contains("children[0].allergies"), "the fields are there")
        assertFalse(words(html).contains("sono state cancellate il"))
    }

    @Test
    fun `t7 c30 after the date the notes of a paper registration cannot be written or corrected, the address leads back to the detail with a notice, and the link is not there`() {
        val setup = endedDaysAgo(3)
        val paper = rows.register(setup.id, null, "Elena", "Marino", enteredBy = setup.martaId)
        val before = words(setup.marta.get("/iscrizioni/$paper/correggi").body())
        assertTrue(before.contains("Aggiungi le segnalazioni"), "before the date the link is there")

        // The gathering ended 40 days ago: the notes are past their date.
        val older = endedDaysAgo(40)
        val old = rows.register(older.id, null, "Elena", "Marino", enteredBy = older.martaId)
        val sentence = "Le segnalazioni sono state cancellate il ${notesDate(older)}: non si possono più scrivere né correggere."

        assertFalse(words(older.marta.get("/iscrizioni/$old/correggi").body()).contains("Aggiungi le segnalazioni"), "the link is gone")
        val shown = older.marta.get("/iscrizioni/$old/correggi-segnalazioni")
        assertEquals(303, shown.status())
        assertEquals("/iscrizioni/$old", shown.response.getHeader("Location"))
        val flash = (older.marta.cookie as org.springframework.mock.web.MockHttpSession).getAttribute("talita.flash") as talita.ui.Alert
        assertEquals(sentence, flash.text)
        assertEquals(talita.ui.AlertVariant.WARNING, flash.variant)
        val saved = older.marta.submit("/iscrizioni/$old", "/iscrizioni/$old/correggi-segnalazioni", mapOf("allergies" to "Polline", "healthConsent" to "true"))
        assertEquals(303, saved.status())
        assertEquals("/iscrizioni/$old", saved.response.getHeader("Location"))
        assertEquals(0, dsl.fetchCount(talita.jooq.tables.references.HEALTH_NOTE), "nothing was written")
    }

    // ---- The notices of privacy (criterion 32) ------------------------------------------------------------------------------------------

    @Test
    fun `t7 c32 the complete notice and the short one say the times of the policy, and nothing is left to decide`() {
        val complete = words(MockBrowser(mockMvc).get("/informativa").body())

        for (row in texts.completeRows()) {
            assertTrue(complete.contains(row.what), row.what)
            assertTrue(complete.contains(row.time), row.time)
        }
        assertTrue(complete.contains(texts.completeIntro))
        for (paragraph in texts.rights) assertTrue(complete.contains(paragraph), paragraph)
        assertFalse(complete.contains("Da fissare con TALITA-7"), "the term of the accounts is fixed")
        assertTrue(complete.contains("30 giorni") && complete.contains("un anno") && complete.contains("un mese"))
        // The link of the profile goes to the card.
        assertTrue(Regex("""<section id="per-quanto-tempo"""").containsMatchIn(MockBrowser(mockMvc).get("/informativa").body()))

        val setup = kit.start()
        val a = parent()
        val form = kit.form(kit.child(0, "Luca", "Rossi"))
        val second = words(a.browser.submit(setup.form, setup.form, form).body())
        assertTrue(second.contains(texts.briefTime()), second)
        assertTrue(second.contains(texts.briefRights), second)
    }

    @Test
    fun `t7 c32 a policy with other periods gives other texts everywhere, so a number is written in one place only`() {
        val other = RetentionTexts(RetentionPolicy(notes = Period.ofMonths(2), registrations = Period.ofYears(2), invitations = Period.ofDays(45), emptyAccounts = Period.ofDays(60), backups = Period.ofDays(14)))
        val standard = texts

        assertTrue(other.briefTime().contains("2 mesi") && other.briefTime().contains("2 anni") && other.briefTime().contains("45 giorni") && other.briefTime().contains("60 giorni") && other.briefTime().contains("14 giorni"), other.briefTime())
        assertFalse(other.briefTime().contains("un mese") || other.briefTime().contains("un anno") || other.briefTime().contains("30 giorni"), other.briefTime())
        assertTrue(standard.briefTime().contains("un mese") && standard.briefTime().contains("un anno") && standard.briefTime().contains("30 giorni"))
        // The attendance by day and the payments go away with the registration, and both notices say so with the period of the policy (the review of TALITA-6).
        assertTrue(other.briefTime().contains("le presenze segnate e i pagamenti si conservano per 2 anni dopo la fine della convivenza"), other.briefTime())
        assertTrue(standard.briefTime().contains("le presenze segnate e i pagamenti si conservano per un anno dopo la fine della convivenza"), standard.briefTime())
        val attendance = { rows: List<TimeRow> -> rows.single { it.what.contains("presenze") && it.what.contains("pagamenti") }.time }
        assertTrue(attendance(other.completeRows()).startsWith("2 anni dopo la fine della convivenza"), attendance(other.completeRows()))
        assertTrue(attendance(standard.completeRows()).startsWith("Un anno dopo la fine della convivenza"), attendance(standard.completeRows()))
        val rows = other.completeRows().joinToString(" ") { it.time }
        assertTrue(rows.contains("2 mesi dopo la fine") && rows.contains("2 anni") && rows.contains("45 giorni") && rows.contains("60 giorni") && rows.contains("14 giorni"), rows)
        assertTrue(other.notesDeleted(LocalDate.of(2027, 9, 19)).main.contains("2 mesi dopo la fine"))
        assertEquals("Segnalazioni cancellate il 20 novembre 2027, 2 mesi dopo la fine.", other.notesDeleted(LocalDate.of(2027, 9, 19)).main)
    }

    @Test
    fun `t7 c32 the periods are said in Italian, singular and plural right`() {
        assertEquals("un mese", texts.words(Period.ofMonths(1)))
        assertEquals("2 mesi", texts.words(Period.ofMonths(2)))
        assertEquals("un anno", texts.words(Period.ofYears(1)))
        assertEquals("3 anni", texts.words(Period.ofYears(3)))
        assertEquals("un giorno", texts.words(Period.ofDays(1)))
        assertEquals("30 giorni", texts.words(Period.ofDays(30)))
        assertEquals("un anno e 6 mesi", texts.words(Period.of(1, 6, 0)))
        assertEquals("un anno, 2 mesi e 5 giorni", texts.words(Period.of(1, 2, 5)))
    }

    // ---- The words and the markup (criterion 34) ----------------------------------------------------------------------------------------

    @Test
    fun `t7 c34 the new templates have none of the words that the guide forbids, no style, no script and no handler`() {
        val files = Files.walk(Path.of("src/main/jte")).use { stream -> stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".jte") }.toList() }
        val forbidden = listOf("Koin" + "è", "Koin" + "e", "centro", "comunità", "fratelli")
        for (file in files) {
            val body = Files.readString(file)
            for (word in forbidden) assertFalse(body.contains(word, ignoreCase = true), "$file has «$word»")
            assertFalse(Regex("""\sstyle=""").containsMatchIn(body), "$file has an inline style")
            assertFalse(Regex("""<script(?![^>]*\ssrc=)""").containsMatchIn(body), "$file has an inline script")
            assertFalse(Regex("""\son[a-z]+=""").containsMatchIn(body), "$file has a handler")
            assertFalse(body.contains("hx-on"), "$file has hx-on")
        }
        // The sentences of the dialogs and of the card are built in Kotlin: none has a word of the technical jargon either.
        val sources = listOf("YourData.kt", "RetentionTexts.kt").map { Files.readString(Path.of("src/main/kotlin/talita/retention/$it")) }
        for (source in sources) {
            val sentences = Regex(""""([^"\n]{12,})"""").findAll(source).map { it.groupValues[1] }.filter { it.contains(' ') && !it.startsWith("talita.") && !it.startsWith("/") }.toList()
            for (sentence in sentences) {
                for (word in listOf("database", "server", "sessione", "utente", "login", "errore 500")) assertFalse(sentence.contains(word, ignoreCase = true), "«$sentence» has «$word»")
                for (word in forbidden) assertFalse(sentence.contains(word, ignoreCase = true), "«$sentence» has «$word»")
            }
        }
    }
}
