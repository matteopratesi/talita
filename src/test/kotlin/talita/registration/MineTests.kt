package talita.registration

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.body
import talita.gathering.errorOf
import talita.gathering.heading
import talita.gathering.location
import talita.gathering.status
import talita.gathering.title
import talita.gathering.valueOf
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationAttendance
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.REGISTRATION
import talita.ui.Alert
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registrations of the parent (criteria 11-17, 19, 29): the list with its cards, the change, another child, the withdrawal and the way back, what
 * closed registrations and a finished gathering allow, the notes on health, and above all that nobody sees or touches what is not theirs. The parent
 * has entered with the link; names, dates and numbers are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class MineTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val reader: talita.gathering.GatheringReader,
) {

    private val kit = RegistrationTestKit(mockMvc, tokens, dsl, clock)

    private fun text(html: String): String = talita.gathering.text(html).replace(' ', ' ')

    private fun flash(browser: MockBrowser): String? = ((browser.cookie as MockHttpSession).getAttribute("talita.flash") as? Alert)?.text

    /** The card of a registration in the page `/iscrizioni`, as text. */
    private fun card(html: String, id: Long): String =
        text(Regex("""<article[^>]*aria-labelledby="scheda-$id"[\s\S]*?</article>""").find(html)?.value ?: error("no card for $id"))

    private fun registered(parent: MockBrowser, setup: Setup, vararg children: Map<String, String>, phone: String = "333 123 4567"): List<Long> {
        val before = kit.registrations(setup.id).map { it.id!! }.toSet()
        assertEquals(303, kit.register(parent, setup, kit.form(*children, phone = phone)).status())
        return kit.registrations(setup.id).map { it.id!! }.filter { it !in before }
    }

    private fun firstNameOf(registration: Long): String =
        dsl.select(CHILD.FIRST_NAME).from(REGISTRATION).join(CHILD).on(CHILD.ID.eq(REGISTRATION.CHILD_ID)).where(REGISTRATION.ID.eq(registration)).fetchSingle(CHILD.FIRST_NAME)!!

    private fun birthOf(registration: Long): java.time.LocalDate =
        dsl.select(CHILD.BIRTH_DATE).from(REGISTRATION).join(CHILD).on(CHILD.ID.eq(REGISTRATION.CHILD_ID)).where(REGISTRATION.ID.eq(registration)).fetchSingle(CHILD.BIRTH_DATE)!!

    private fun accountOf(registration: Long): Long = dsl.select(REGISTRATION.ACCOUNT_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(registration)).fetchSingle(REGISTRATION.ACCOUNT_ID)!!

    // ---- The list (criteria 11 and 29) -----------------------------------------------------------------------------------------------

    @Test
    fun `t5 c11 a parent who has registered sees their cards, with the days, the state, the quota, the acceptance, the phone and the total`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (luca, anna) = registered(
            parent, setup,
            kit.child(0, "Luca", "Rossi", presence = "all_days"),
            kit.child(1, "Anna", "Rossi", "9", "1", "2019", "days", "maybe", "day.sat" to "full", "day.sun" to "lunch", "allergies" to "Arachidi", "healthConsent" to "true"),
        )

        val page = parent.get("/iscrizioni")
        val html = page.body()

        assertEquals(200, page.status())
        assertEquals("Iscrizioni", heading(html))
        assertEquals("Iscrizioni · Talità", title(html))
        assertTrue(Regex("""<a href="/iscrizioni" aria-current="page"""").containsMatchIn(html))
        val words = text(html)
        assertTrue(words.contains("Convivenza di settembre 2027 ${talita.gathering.GatheringFormat.range(setup.friday, setup.sunday)} Iscrizioni aperte"), words)
        val accepted = RegistrationFormat.moment(kit.registrations(setup.id).first().custodyAcceptedAt!!)
        val lucaCard = card(html, luca)
        assertTrue(lucaCard.startsWith("Luca Rossi Confermata Quando Tutti i giorni (${talita.gathering.GatheringFormat.range(setup.friday, setup.sunday)}) Quota 60 € Telefono 333 123 4567 Segnalazioni Nessuna Affidamento accettato il $accepted"), lucaCard)
        val annaCard = card(html, anna)
        assertTrue(annaCard.startsWith("Anna Rossi In forse Quando Giornata: sabato ${setup.saturday.dayOfMonth}. Solo pranzo: domenica ${setup.sunday.dayOfMonth}. Quota 35 € Telefono 333 123 4567 Segnalazioni Scritte"), annaCard)
        assertFalse(words.contains("Arachidi"), "the text of the notes is not on the card")
        assertTrue(lucaCard.endsWith("Modifica Segnalazioni Ritira l’iscrizione"), lucaCard)
        assertTrue(words.contains("Totale 95 €"), words)
        assertTrue(Regex("""<a href="/info/${setup.code}/iscrizione"[^>]*>[\s\S]*?Iscrivi un altro figlio</a>""").containsMatchIn(html))
        // The cards are in the order they were made.
        assertTrue(html.indexOf("scheda-$luca") < html.indexOf("scheda-$anna"))
        // The order in which they were made, not the order of the names.
        assertTrue(Regex("""<h3 id="scheda-$luca"[^>]*>Luca Rossi</h3>""").containsMatchIn(html))
    }

    @Test
    fun `t5 c11 the gatherings come from the most recent, and a parent who has no registration sees an empty state and no gathering`() {
        val setup = kit.start()
        val parent = kit.parent()
        registered(parent, setup, kit.child(0, "Luca"))
        val next = kit.nextGathering(setup)
        registered(parent, next, kit.child(0, "Luca"))

        val html = parent.get("/iscrizioni").body()
        assertTrue(html.indexOf("Convivenza dell’anno dopo") in 1 until html.indexOf("Convivenza di settembre 2027"), "the gathering that starts last comes first")

        val other = kit.parent("altro")
        val empty = other.get("/iscrizioni").body()
        assertTrue(empty.contains(">Nessuna iscrizione</h2>"))
        assertTrue(text(empty).contains("Quando iscrivi un bambino, lo trovi qui con i giorni, la quota e lo stato. Per iscriverlo apri il link che ti hanno mandato i maestri."))
        assertFalse(text(empty).contains("Convivenza di settembre 2027"), "no gathering for who has no registration: the code of the teachers is the key")
        assertFalse(empty.contains("Iscrivi un altro figlio"))
        val home = text(other.get("/").body())
        assertTrue(home.contains("Non hai ancora iscritto nessun bambino. Per farlo, apri il link che ti hanno mandato i maestri."))
        assertFalse(home.contains("Le tue iscrizioni"))
    }

    @Test
    fun `t5 c11 the home of a parent lists the gatherings with the number of children and the sum`() {
        val setup = kit.start()
        val parent = kit.parent()
        registered(parent, setup, kit.child(0, "Luca"), kit.child(1, "Anna", presence = "days", more = arrayOf("day.sat" to "lunch")))

        val home = text(parent.get("/").body())

        assertTrue(home.contains("Le tue iscrizioni Convivenza di settembre 2027 Iscrizioni aperte 2 bambini · 70 €"), home)
        assertFalse(home.contains("in costruzione"))
        assertTrue(home.contains("Cosa trovi qui"))
        assertTrue(Regex("""<a href="/iscrizioni" class="flex min-h-16[^"]*">[\s\S]*?Convivenza di settembre 2027""").containsMatchIn(parent.get("/").body()))
        // All withdrawn: «Nessuna iscrizione attiva»; a free gathering: «Gratuita».
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.withdrawn).execute()
        assertTrue(text(parent.get("/").body()).contains("Convivenza di settembre 2027 Iscrizioni aperte Nessuna iscrizione attiva"))
        dsl.update(GATHERING).set(GATHERING.FULL_FEE_CENTS, 0).set(GATHERING.DAILY_FEE_CENTS, 0).set(GATHERING.LUNCH_ONLY_FEE_CENTS, 0).execute()
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.confirmed).execute()
        assertTrue(text(parent.get("/").body()).contains("2 bambini · Gratuita"))
    }

    // ---- Modification (criteria 12 and 13) --------------------------------------------------------------------------------------------

    @Test
    fun `t5 c12 a parent changes the days, the state, the phone and the data of the child, and the acceptance stays`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (luca, anna) = registered(parent, setup, kit.child(0, "Luca"), kit.child(1, "Anna", "Rossi", "9", "1", "2019"))
        val before = kit.registrations(setup.id).first { it.id == luca }

        val page = parent.get("/iscrizioni/$luca/modifica")
        val html = page.body()
        assertEquals(200, page.status())
        assertEquals("Modifica l’iscrizione", heading(html))
        assertTrue(text(html).contains("Luca Rossi · Convivenza di settembre 2027"))
        assertTrue(text(html).contains("L’affidamento che hai accettato il ${RegistrationFormat.day(before.custodyAcceptedAt!!)} non cambia. Per allergie, intolleranze e farmaci usa «Segnalazioni» nella scheda dell’iscrizione."))
        assertEquals("Luca", valueOf(html, "b0-nome"))
        assertEquals("333 123 4567", valueOf(html, "telefono"))
        assertTrue(Regex("""name="children\[0]\.presence" value="all_days" checked""").containsMatchIn(html))
        assertTrue(text(html).contains("Il telefono vale per tutte le tue iscrizioni a questa convivenza: se lo cambi qui, cambia anche per gli altri bambini."))
        assertFalse(html.contains("Allergie, intolleranze, farmaci"), "the notes are not in the modification: they have their page")
        assertTrue(text(html).contains("Salva le modifiche"))

        val form = kit.form(
            kit.child(0, "Luca", "Rossi", "5", "4", "2016", "days", "maybe", "day.fri" to "full", "day.sun" to "lunch"),
            phone = "+39 333 999 8888",
        )
        val saved = parent.submit("/iscrizioni/$luca/modifica", "/iscrizioni/$luca/modifica", form)
        assertEquals(303, saved.status())
        assertEquals("/iscrizioni", saved.location())
        assertEquals("Iscrizione salvata.", flash(parent))
        val after = kit.registrations(setup.id)
        val changed = after.first { it.id == luca }
        assertEquals(RegistrationAttendance.days, changed.attendance)
        assertEquals(RegistrationStatus.maybe, changed.status)
        assertEquals(mapOf(setup.friday to "full", setup.sunday to "lunch"), kit.daysOf(luca))
        assertEquals("+39 333 999 8888", changed.phone)
        assertEquals("+39 333 999 8888", after.first { it.id == anna }.phone, "the phone is one for all the registrations of the parent at this gathering")
        assertEquals(java.time.LocalDate.of(2016, 4, 5), birthOf(luca))
        // What was accepted does not change: not the text, not the moment, not the address, not the version.
        assertEquals(before.custodyText, changed.custodyText)
        assertEquals(before.custodyAcceptedAt, changed.custodyAcceptedAt)
        assertEquals(before.custodyAcceptedByEmail, changed.custodyAcceptedByEmail)
        assertEquals(before.custodyTextVersion, changed.custodyTextVersion)
        // And back to every day: the days are gone.
        assertEquals(303, parent.submit("/iscrizioni/$luca/modifica", "/iscrizioni/$luca/modifica", kit.form(kit.child(0, "Luca", presence = "all_days"))).status())
        assertEquals(RegistrationAttendance.all_days, kit.registrations(setup.id).first { it.id == luca }.attendance)
        assertEquals(emptyMap(), kit.daysOf(luca))
    }

    @Test
    fun `t5 c12 the modification has the errors of the first step, and the same child as another one of the parent is said`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (luca, anna) = registered(parent, setup, kit.child(0, "Luca"), kit.child(1, "Anna", "Rossi", "9", "1", "2019"))
        val path = "/iscrizioni/$luca/modifica"

        val wrong = parent.submit(path, path, kit.form(kit.child(0, "", "Rossi", "31", "2", "2016"), phone = "12"))
        assertEquals(422, wrong.status())
        assertEquals("Scrivi un numero di telefono completo, per esempio 333 123 4567.", errorOf(wrong.body(), "telefono"))
        assertEquals("Scrivi il nome.", errorOf(wrong.body(), "b0-nome"))
        assertEquals("Scrivi una data vera, per esempio 4 4 2016.", errorOf(wrong.body(), "b0-nascita"))
        assertTrue(text(wrong.body()).contains("Controlla i 3 campi segnati in rosso."))
        assertEquals("Luca", firstNameOf(luca), "nothing was saved")

        val same = parent.submit(path, path, kit.form(kit.child(0, "anna", "ROSSI", "9", "1", "2019", "all_days")))
        assertEquals(422, same.status())
        assertEquals("anna ROSSI è già iscritto a questa convivenza: puoi cambiarne l’iscrizione da «Iscrizioni».", errorOf(same.body(), "b0-nome"))
        assertNotEquals(anna, luca)
    }

    @Test
    fun `t5 c12 the quota of the modification changes with the days, as a piece`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (luca) = registered(parent, setup, kit.child(0, "Luca"))

        val answer = parent.submit("/iscrizioni/$luca/modifica", "/iscrizioni/$luca/modifica/quota", kit.form(kit.child(0, "Luca", presence = "days", more = arrayOf("day.fri" to "full", "day.sat" to "full"))) + ("x" to "y")) { header("HX-Request", "true") }

        assertEquals(200, answer.status())
        assertTrue(Regex("""<div hx-swap-oob="innerHTML:#quota-0">.*50.*€.*</div>""").containsMatchIn(answer.body()), answer.body())
    }

    @Test
    fun `t5 c13 another child is registered with the phone already written, and a new acceptance for that child`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (luca) = registered(parent, setup, kit.child(0, "Luca"), phone = "+39 333 111 2222")

        val page = parent.get(setup.form).body()
        assertEquals("+39 333 111 2222", valueOf(page, "telefono"))
        assertTrue(text(page).contains("Vale per tutte le tue iscrizioni a questa convivenza."))
        assertFalse(text(page).contains("I tuoi figli"), "Luca is registered here already: he is not offered again")

        val (anna) = registered(parent, setup, kit.child(0, "Anna", "Rossi", "9", "1", "2019", "all_days"), phone = "+39 333 111 2222")

        val rows = kit.registrations(setup.id)
        assertEquals(2, rows.size)
        assertTrue(rows.first { it.id == anna }.custodyText!!.contains("affido Anna Rossi, nato/a il 9 gennaio 2019"))
        assertFalse(rows.first { it.id == anna }.custodyText!!.contains("Luca"), "the acceptance is for that child")
        assertTrue(rows.first { it.id == luca }.custodyText!!.contains("affido Luca Rossi"))
        assertEquals(setOf(luca, anna), rows.map { it.id!! }.toSet())
    }

    // ---- Withdrawal (criterion 14) -----------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c14 withdrawing keeps the card as Ritirata with the quota not due, and takes the notes away at once`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (luca, anna) = registered(
            parent, setup, kit.child(0, "Luca", more = arrayOf("allergies" to "Arachidi", "healthConsent" to "true")), kit.child(1, "Anna", "Rossi", "9", "1", "2019"),
        )
        assertEquals(1, kit.healthNoteCount())

        val list = parent.get("/iscrizioni").body()
        assertTrue(list.contains("""<dialog id="ritira-$luca""""), "the dialog of the withdrawal")
        assertTrue(text(Regex("""<dialog id="ritira-$luca"[\s\S]*?</dialog>""").find(list)!!.value).startsWith("Ritirare l’iscrizione? L’iscrizione di Luca Rossi alla Convivenza di settembre 2027 risulterà ritirata: la quota non è dovuta e i giorni non contano più. Le segnalazioni, se ci sono, vengono cancellate subito. Resta fra le tue iscrizioni con lo stato «Ritirata»: finché le iscrizioni sono aperte puoi rimetterla «Confermata» o «In forse»."))
        assertTrue(Regex("""<a href="/iscrizioni/$luca/ritira" data-apri="ritira-$luca"""").containsMatchIn(list))

        // Without JavaScript the link is a page with the same words.
        val confirmation = parent.get("/iscrizioni/$luca/ritira")
        assertEquals(200, confirmation.status())
        assertEquals("Ritirare l’iscrizione?", heading(confirmation.body()))
        assertTrue(text(confirmation.body()).contains("risulterà ritirata: la quota non è dovuta e i giorni non contano più."))

        val done = parent.submit("/iscrizioni", "/iscrizioni/$luca/ritira", mapOf("from" to "confirmed"))
        assertEquals(303, done.status())
        assertEquals("/iscrizioni", done.location())
        assertEquals("Iscrizione ritirata.", flash(parent))
        assertEquals(RegistrationStatus.withdrawn, kit.registrations(setup.id).first { it.id == luca }.status)
        assertEquals(0, kit.healthNoteCount(), "the notes are deleted at once")
        val html = parent.get("/iscrizioni").body()
        val withdrawn = card(html, luca)
        assertTrue(withdrawn.startsWith("Luca Rossi Ritirata Quando"), withdrawn)
        assertTrue(withdrawn.contains("Quota Non dovuta"), withdrawn)
        assertTrue(withdrawn.contains("Segnalazioni Nessuna"))
        assertTrue(withdrawn.endsWith("Rimetti l’iscrizione"), withdrawn)
        assertTrue(text(html).contains("Totale 60 €"), "the withdrawn ones do not count")
        assertEquals(2, kit.registrationCount(setup.id), "the card stays")
        // A second tap, or a second answer: the same confirmation, never an error.
        val again = parent.submit("/iscrizioni", "/iscrizioni/$luca/ritira", mapOf("from" to "confirmed"))
        assertEquals(303, again.status())
        assertEquals("Iscrizione ritirata.", flash(parent))
        assertEquals(RegistrationStatus.confirmed, kit.registrations(setup.id).first { it.id == anna }.status)
        // All withdrawn: there is no total.
        assertEquals(303, parent.submit("/iscrizioni", "/iscrizioni/$anna/ritira", emptyMap()).status())
        assertFalse(text(parent.get("/iscrizioni").body()).contains("Totale"))
    }

    @Test
    fun `t5 c14 a withdrawn registration is put back as Confermata or In forse, with the acceptance as it was and no notes`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (luca) = registered(parent, setup, kit.child(0, "Luca", more = arrayOf("allergies" to "Arachidi", "healthConsent" to "true")))
        val accepted = kit.registrations(setup.id).single()
        assertEquals(303, parent.submit("/iscrizioni", "/iscrizioni/$luca/ritira", emptyMap()).status())

        val page = parent.get("/iscrizioni/$luca/modifica").body()
        assertEquals("Rimetti l’iscrizione", heading(page))
        assertTrue(text(page).contains("L’iscrizione è ritirata. Scegli «Confermata» o «In forse» e tocca «Rimetti l’iscrizione»: l’affidamento che hai accettato il ${RegistrationFormat.day(accepted.custodyAcceptedAt!!)} resta valido. Le segnalazioni si cancellano con il ritiro: se servono, scrivile di nuovo da «Segnalazioni»."))
        assertTrue(Regex("""name="children\[0]\.status" value="confirmed" checked""").containsMatchIn(page), "Confermata is chosen")
        assertTrue(text(page).contains("Rimetti l’iscrizione Ripristino… Annulla"))

        val back = parent.submit("/iscrizioni/$luca/modifica", "/iscrizioni/$luca/modifica", kit.form(kit.child(0, "Luca", status = "maybe")))
        assertEquals(303, back.status())
        assertEquals("Iscrizione rimessa.", flash(parent))
        val row = kit.registrations(setup.id).single()
        assertEquals(RegistrationStatus.maybe, row.status)
        assertEquals(accepted.custodyText, row.custodyText)
        assertEquals(accepted.custodyAcceptedAt, row.custodyAcceptedAt)
        assertEquals(0, kit.healthNoteCount(), "the notes are written again if they are needed")
    }

    // ---- Closed registrations and a finished gathering (criteria 15, 19 and 29) ------------------------------------------------------

    @Test
    fun `t5 c15 with the registrations closed the parent sees the cards with no actions but the notes, and what is asked by hand changes nothing`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (luca, anna) = registered(parent, setup, kit.child(0, "Luca"), kit.child(1, "Anna", "Rossi", "9", "1", "2019"))
        assertEquals(303, parent.submit("/iscrizioni", "/iscrizioni/$anna/ritira", emptyMap()).status())
        val csrf = MockBrowser.csrfIn(parent.get(setup.form).body())!!
        val snapshot = kit.registrations(setup.id).map { it.status to it.updatedAt }
        kit.setState(setup.id, GatheringStatus.closed)

        val html = parent.get("/iscrizioni").body()
        assertTrue(text(html).contains("Le iscrizioni sono chiuse. Per cambiare un’iscrizione o iscrivere un altro bambino, chiedi ai maestri."))
        assertTrue(text(html).contains("Iscrizioni chiuse"))
        assertFalse(html.contains("Iscrivi un altro figlio"))
        assertFalse(html.contains("""<dialog id="ritira-"""))
        assertTrue(card(html, luca).endsWith("Segnalazioni"), "the notes can change until the last day: " + card(html, luca))
        assertFalse(Regex("""href="/iscrizioni/$luca/modifica"""").containsMatchIn(html))
        assertFalse(card(html, anna).contains("Rimetti") || card(html, anna).contains("Modifica"), "a withdrawn one cannot be put back")
        assertFalse(card(html, anna).contains("Segnalazioni Scritte"))

        for ((method, path) in listOf("GET" to "/iscrizioni/$luca/modifica", "POST" to "/iscrizioni/$luca/modifica", "POST" to "/iscrizioni/$luca/ritira", "POST" to "/iscrizioni/$anna/modifica", "GET" to "/iscrizioni/$luca/ritira")) {
            val answer = if (method == "GET") parent.get(path) else parent.post(path, kit.form(kit.child(0, "Luca", presence = "days", more = arrayOf("day.fri" to "full"))), csrf)
            assertEquals(409, answer.status(), "$method $path")
            assertTrue(text(answer.body()).contains("Le iscrizioni si sono chiuse mentre stavi cambiando l’iscrizione: non è stato cambiato niente. Per cambiarla, chiedi ai maestri."), "$method $path")
            assertEquals("Iscrizioni", heading(answer.body()))
        }
        assertEquals(snapshot, kit.registrations(setup.id).map { it.status to it.updatedAt }, "nothing changed")
        // A new registration, asked by hand, goes nowhere: the page says the registrations are closed.
        assertEquals(303, parent.get(setup.form).status())
        assertEquals(409, parent.post(setup.form, kit.form(kit.child(0, "Paolo")), csrf).status())
        assertEquals(2, kit.registrationCount(setup.id))
    }

    @Test
    fun `t5 c15 a gathering that is over is only looked at, with the notice that it is over and nothing changed by hand`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (luca) = registered(parent, setup, kit.child(0, "Luca"))
        val csrf = MockBrowser.csrfIn(parent.get(setup.form).body())!!
        kit.makeOver(setup.id)

        val html = parent.get("/iscrizioni").body()
        assertTrue(text(html).contains("La convivenza è finita."))
        assertTrue(text(html).contains("Finita"))
        assertFalse(html.contains("Iscrivi un altro figlio"))
        assertTrue(card(html, luca).endsWith("Affidamento accettato il ${RegistrationFormat.moment(kit.registrations(setup.id).single().custodyAcceptedAt!!)}"), card(html, luca))
        for (path in listOf("/iscrizioni/$luca/modifica", "/iscrizioni/$luca/segnalazioni")) {
            val answer = parent.get(path)
            assertEquals(409, answer.status(), path)
            assertTrue(text(answer.body()).contains("La convivenza è finita: non è stato cambiato niente."), path)
        }
        val withdrawal = parent.post("/iscrizioni/$luca/ritira", emptyMap(), csrf)
        assertEquals(409, withdrawal.status())
        assertEquals(RegistrationStatus.confirmed, kit.registrations(setup.id).single().status)
    }

    // ---- Nobody else's (criterion 16) ----------------------------------------------------------------------------------------------------

    private val ownerOnly = listOf("/modifica", "/segnalazioni", "/segnalazioni/togli", "/ritira")

    @Test
    fun `t5 c16 the registration of a parent is the page of an id that does not exist for another parent and for a teacher who is not the parent, on every address`() {
        val setup = kit.start()
        val owner = kit.parent("genitore-a")
        val (luca) = registered(owner, setup, kit.child(0, "Luca", more = arrayOf("allergies" to "Arachidi", "healthConsent" to "true")))
        val snapshot = dsl.selectFrom(REGISTRATION).fetch().map { it.status to it.updatedAt to it.phone }
        val health = dsl.selectFrom(HEALTH_NOTE).fetch().map { it.allergies }
        val other = kit.parent("genitore-b")
        val teacher = setup.marta
        val fields = kit.form(kit.child(0, "Intruso", presence = "all_days"), phone = "333 000 0000") + mapOf("allergies" to "Altro", "healthConsent" to "true")

        for (path in ownerOnly) {
            val address = "/iscrizioni/$luca$path"
            // The owner reaches every address.
            assertEquals(200, owner.get(address).status(), "owner GET $path")
            for ((who, browser) in listOf("another parent" to other, "a teacher" to teacher)) {
                if (path == "/ritira" && who == "a teacher") continue // a teacher withdraws any registration: the pages of the teachers (below)
                val read = browser.get(address)
                assertEquals(404, read.status(), "$who GET $path")
                val write = browser.submit(setup.form, address, fields)
                assertEquals(404, write.status(), "$who POST $path")
            }
            // Nobody inside: the access page, whatever the address.
            val anonymous = MockBrowser(mockMvc)
            assertEquals("/accedi", anonymous.get(address).location(), "nobody GET $path")
            assertEquals("/accedi", anonymous.post(address, fields).location(), "nobody POST $path")
        }
        assertEquals(404, other.submit(setup.form, "/iscrizioni/$luca/modifica/quota", fields) { header("HX-Request", "true") }.status())
        assertEquals(snapshot, dsl.selectFrom(REGISTRATION).fetch().map { it.status to it.updatedAt to it.phone }, "nothing changed")
        assertEquals(health, dsl.selectFrom(HEALTH_NOTE).fetch().map { it.allergies })
        // The pages of the other parent show nothing of this one.
        assertFalse(text(other.get("/iscrizioni").body()).contains("Luca"))
        assertFalse(text(teacher.get("/").body()).contains("Luca"))
    }

    @Test
    fun `t5 c16 every address with the id of a registration goes through the one filter`() {
        val source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/kotlin/talita/registration/MineController.kt"))
        val handlers = Regex("""@(?:Get|Post)Mapping\("/iscrizioni/\{id""").findAll(source).count()
        val filtered = Regex("""own\.require\(account, id\)""").findAll(source).count()

        assertEquals(9, handlers, "the addresses of the parent with an id")
        assertEquals(handlers, filtered, "every one of them asks the filter first")
        assertFalse(source.substringAfter("class MineController").contains("registrations.find("), "no address reads a registration without the filter")
    }

    // ---- The same child at the same time (criterion 17) ----------------------------------------------------------------------------------

    @Test
    fun `t5 c17 two parents who register the same child have two registrations and neither sees the other`() {
        val setup = kit.start()
        val a = kit.parent("genitore-a")
        val b = kit.parent("genitore-b")
        val (first) = registered(a, setup, kit.child(0, "Luca"), phone = "333 111 1111")
        // The second parent writes the same name and date of birth, with other capitals.
        assertEquals(303, kit.register(b, setup, kit.form(kit.child(0, "luca", "ROSSI"), phone = "333 222 2222")).status())

        assertEquals(2, kit.registrationCount(setup.id))
        assertEquals(2, kit.childCount(), "each parent has their own child")
        val html = text(b.get("/iscrizioni").body())
        assertTrue(html.contains("luca ROSSI"))
        assertFalse(html.contains("Luca Rossi"), "the child of the other parent is not shown")
        assertFalse(html.contains("333 111 1111"))
        // The page of the registration of the other is not found, and the step of B did not say that the child was already there.
        assertEquals(404, b.get("/iscrizioni/$first/modifica").status())
        val second = kit.registrations(setup.id).single { it.id != first }
        assertNotEquals(accountOf(first), accountOf(second.id!!))
    }

    // ---- The notes on health (criterion 19) ----------------------------------------------------------------------------------------------

    @Test
    fun `t5 c19 the parent writes, changes and empties the notes from their page, with the consent, and no reading is written`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (anna) = registered(parent, setup, kit.child(0, "Anna", "Rossi", "9", "1", "2019"))
        val path = "/iscrizioni/$anna/segnalazioni"

        val page = parent.get(path)
        val html = page.body()
        assertEquals(200, page.status())
        assertEquals("Segnalazioni", heading(html))
        assertTrue(text(html).contains("Anna Rossi · Convivenza di settembre 2027"))
        assertTrue(text(html).contains("Allergie, intolleranze e farmaci. Sono facoltative: se non le scrivi, l’iscrizione vale lo stesso, ma i maestri non le sapranno."))
        assertTrue(text(html).contains("Le leggono solo i maestri, quando serve, e ogni lettura resta registrata. Le cancelliamo un mese dopo la fine della convivenza, e le puoi togliere quando vuoi."))
        assertTrue(text(html).contains("Segnalazioni di Anna"))
        assertFalse(text(html).contains("Togli le segnalazioni"), "nothing to take away yet")
        assertFalse(Regex("""id="b0-consenso"[^>]*checked""").containsMatchIn(html))

        val without = parent.submit(path, path, mapOf("allergies" to "Arachidi"))
        assertEquals(422, without.status())
        assertEquals("Per salvare le segnalazioni serve il tuo consenso: spunta la casella. Se non vuoi darlo, svuota i campi: l’iscrizione vale lo stesso.", errorOf(without.body(), "b0-consenso"))
        assertEquals("Arachidi", valueOf(without.body(), "b0-allergie"))
        assertEquals(0, kit.healthNoteCount())
        assertEquals("Usa al massimo 1.000 caratteri: ne hai scritti 1.001.", errorOf(parent.submit(path, path, mapOf("medications" to "x".repeat(1001), "healthConsent" to "true")).body(), "b0-farmaci"))

        val saved = parent.submit(path, path, mapOf("allergies" to "Arachidi", "medications" to "Antistaminico", "healthConsent" to "true"))
        assertEquals(303, saved.status())
        assertEquals("/iscrizioni", saved.location())
        assertEquals("Segnalazioni salvate.", flash(parent))
        val note = dsl.selectFrom(HEALTH_NOTE).fetchSingle()
        assertEquals("Arachidi", note.allergies)
        assertEquals("Antistaminico", note.medications)
        assertNull(note.intolerances)
        assertEquals(accountOf(anna), note.consentByAccountId)
        assertFalse(note.consentOnPaper!!)

        val again = parent.get(path).body()
        assertEquals("Arachidi", valueOf(again, "b0-allergie"))
        assertTrue(Regex("""id="b0-consenso"[^>]*checked""").containsMatchIn(again))
        assertTrue(text(again).contains("Consenso dato il ${RegistrationFormat.moment(note.consentAt!!)}."))
        assertTrue(text(again).contains("Togli le segnalazioni"))
        assertEquals(0, kit.readingCount(), "the parent's own readings are not written")

        val changed = parent.submit(path, path, mapOf("allergies" to "Polline", "healthConsent" to "true"))
        assertEquals(303, changed.status())
        assertEquals("Polline", dsl.selectFrom(HEALTH_NOTE).fetchSingle().allergies)
        assertNull(dsl.selectFrom(HEALTH_NOTE).fetchSingle().medications)
        // Three empty fields are taking them away.
        val emptied = parent.submit(path, path, mapOf("healthConsent" to "true"))
        assertEquals(303, emptied.status())
        assertEquals("Segnalazioni tolte.", flash(parent))
        assertEquals(0, kit.healthNoteCount())
        assertEquals(0, kit.readingCount())
    }

    @Test
    fun `t5 c19 the notes can be changed with the registrations closed, until the last day, and taken away always`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (anna) = registered(parent, setup, kit.child(0, "Anna", more = arrayOf("allergies" to "Arachidi", "healthConsent" to "true")))
        kit.setState(setup.id, GatheringStatus.closed)
        val path = "/iscrizioni/$anna/segnalazioni"

        val page = parent.get(path)
        assertEquals(200, page.status())
        assertTrue(text(page.body()).contains("Le iscrizioni sono chiuse, ma le segnalazioni puoi cambiarle fino alla fine della convivenza."))
        assertEquals(303, parent.submit(path, path, mapOf("allergies" to "Polline", "healthConsent" to "true")).status())
        assertEquals("Polline", dsl.selectFrom(HEALTH_NOTE).fetchSingle().allergies)

        // After the end: the page is not there, but the notes can be taken away, from the card, with a dialog.
        kit.makeOver(setup.id)
        assertEquals(409, parent.get(path).status())
        val csrf = MockBrowser.csrfIn(parent.get("/iscrizioni").body())!!
        assertEquals(409, parent.post(path, mapOf("allergies" to "Altro", "healthConsent" to "true"), csrf).status())
        assertEquals("Polline", dsl.selectFrom(HEALTH_NOTE).fetchSingle().allergies)
        val list = parent.get("/iscrizioni").body()
        assertTrue(card(list, anna).endsWith("Togli le segnalazioni"), card(list, anna))
        assertTrue(text(Regex("""<dialog id="togli-$anna"[\s\S]*?</dialog>""").find(list)!!.value).startsWith("Togliere le segnalazioni? Cancelliamo subito le segnalazioni su allergie, intolleranze e farmaci di Anna Rossi e il tuo consenso: i maestri non potranno più leggerle. Puoi scriverle di nuovo quando vuoi."))
        assertEquals("Togliere le segnalazioni?", heading(parent.get("$path/togli").body()))

        val revoked = parent.post("$path/togli", mapOf("from" to "health"), csrf)
        assertEquals(303, revoked.status())
        assertEquals("Segnalazioni tolte.", flash(parent))
        assertEquals(0, kit.healthNoteCount())
        // Taking away what is not there is the same confirmation.
        assertEquals(303, parent.post("$path/togli", mapOf("from" to "health"), csrf).status())
    }

    @Test
    fun `t5 c19 a withdrawn registration has no page of notes, and the dialog of the revocation is of danger`() {
        val setup = kit.start()
        val parent = kit.parent()
        val (anna) = registered(parent, setup, kit.child(0, "Anna", more = arrayOf("allergies" to "Arachidi", "healthConsent" to "true")))
        val html = parent.get("/iscrizioni/$anna/segnalazioni").body()
        assertTrue(Regex("""<button type="submit"[^>]*bg-danger[^>]*>[\s\S]*?Togli le segnalazioni""").containsMatchIn(html), "the dialog has the button of danger")
        assertTrue(Regex("""<a href="/iscrizioni/$anna/segnalazioni/togli" data-apri="togli-$anna"[^>]*bg-danger""").containsMatchIn(html))

        assertEquals(303, parent.submit("/iscrizioni", "/iscrizioni/$anna/ritira", emptyMap()).status())
        val withdrawn = parent.get("/iscrizioni/$anna/segnalazioni")
        assertEquals(303, withdrawn.status())
        assertEquals("/iscrizioni", withdrawn.location())
        assertEquals("L’iscrizione è ritirata: non ci sono segnalazioni da cambiare.", flash(parent))
        assertEquals(0, kit.healthNoteCount())
    }

    // ---- What each state allows (criterion 29, the parent's part) ---------------------------------------------------------------------------

    @Test
    fun `t5 c29 the rules of the states say what the parent and the teachers can do with a draft, open, closed and over`() {
        val setup = kit.start()
        val expected = mapOf(
            // state: the parent changes, the parent changes the notes, the teachers correct
            GatheringStatus.draft to listOf(false, false, false),
            GatheringStatus.`open` to listOf(true, true, true),
            GatheringStatus.closed to listOf(false, true, true),
        )
        for ((state, rules) in expected) {
            kit.setState(setup.id, state)
            val gathering = checkNotNull(reader.byId(setup.id))
            assertEquals(rules, listOf(Rules.parentCanChange(gathering), Rules.parentCanChangeHealth(gathering), Rules.teacherCanChange(gathering)), state.literal)
        }
        // Over, open or closed: the parent changes nothing and the teachers still correct.
        for (state in listOf(GatheringStatus.`open`, GatheringStatus.closed)) {
            kit.setState(setup.id, state)
            kit.makeOver(setup.id)
            val over = checkNotNull(reader.byId(setup.id))
            assertEquals(listOf(false, false, true), listOf(Rules.parentCanChange(over), Rules.parentCanChangeHealth(over), Rules.teacherCanChange(over)), "over, ${state.literal}")
        }
    }
}
