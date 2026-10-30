package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FilePayload
import talita.Addresses
import talita.gathering.GatheringSupport
import talita.gathering.MenuSamples
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.GATHERING_MENU
import talita.jooq.tables.references.REMINDER_BATCH
import talita.jooq.tables.references.ROOM
import talita.mail.MailKind
import talita.mail.MailQuota
import talita.mail.Mailbox
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The last pass of the QA of TALITA-13 (reminders by email), after the merge of `main` with the rooms (TALITA-11) and the menu (TALITA-12): the three cards live in the same detail of the
 * gathering, so what is checked here is that they do not get in each other's way. The ids of the page do not repeat, a menu is uploaded and a room is added through their own pages and the
 * detail still has the five cards in order and the right numbers, a reminder is sent from the dialog and the answer replaces only its card (the other cards are the same elements, the
 * page is not loaded again), and then the dialogs, the script and the CSRF code of the menu still work. And the complete notice has the sentence on the stop that comes from a mail that
 * was forwarded, readable at 320 px. Names, titles and addresses are synthetic.
 */
@E2eApplication
class ReminderBesideRoomsAndMenuQaE2eTest {

    private fun E2e.clock(): Clock = application.getBean(Clock::class.java)

    private data class Who(val id: Long, val email: String)

    private fun E2e.account(name: String, notice: OffsetDateTime? = null): Who {
        val email = Addresses.unique("qa13-$name")
        val id = dsl().insertInto(ACCOUNT).set(ACCOUNT.EMAIL, email).set(ACCOUNT.CREATED_ON, today()).set(ACCOUNT.NOTICE_CONSENT_AT, notice).returningResult(ACCOUNT.ID).fetchSingle().value1()!!
        return Who(id, email)
    }

    /** The cards of the detail that this test is about, in the order of the page (the others are there too: Stato, the link, the quotes…). */
    private fun Page.cards(): List<String> =
        (evaluate("[...document.querySelectorAll('main section[aria-labelledby]')].map(s => s.getAttribute('aria-labelledby'))") as List<*>)
            .map { it as String }.filter { it in setOf("iscrizioni", "promemoria-t", "camere", "menu", "quando", "conservazione") }

    private val order = listOf("iscrizioni", "promemoria-t", "camere", "menu", "quando", "conservazione")

    /** The ids that appear more than once in the page: two cards that use the same one are the first thing that goes wrong when they are put together. */
    private fun Page.repeatedIds(): List<String> =
        (evaluate("() => { const seen = new Set(); const twice = []; for (const e of document.querySelectorAll('[id]')) { if (seen.has(e.id)) twice.push(e.id); seen.add(e.id); } return twice; }") as List<*>)
            .map { it as String }

    /** The references of the accessibility (a label, a description, a control) that lead to an element that is not in the page. */
    private fun Page.brokenReferences(): List<String> =
        (evaluate(
            "() => { const out = []; for (const attr of ['aria-labelledby', 'aria-describedby', 'aria-controls']) " +
                "for (const e of document.querySelectorAll('[' + attr + ']')) for (const id of e.getAttribute(attr).split(/\\s+/)) " +
                "if (id && !document.getElementById(id)) out.push(attr + '=' + id); return out; }",
        ) as List<*>).map { it as String }

    private fun Page.healthy(what: String) {
        assertEquals(emptyList<String>(), repeatedIds(), "$what: ids that repeat")
        assertEquals(emptyList<String>(), brokenReferences(), "$what: references that lead nowhere")
        assertTrue(noHorizontalScroll(), "$what scrolls sideways")
    }

    private fun E2e.narrow(page: Page, what: String) {
        page.setViewportSize(320, 800)
        page.waitForSettledContent()
        assertTrue(page.noHorizontalScroll(), "$what scrolls sideways at 320 px")
        page.setViewportSize(if (profile.isPhone) 360 else 1280, 800)
    }

    private fun Page.menuCard(): Locator = locator("section[aria-labelledby=menu]")

    private fun Page.roomsCard(): Locator = locator("section[aria-labelledby=camere]")

    private fun Page.reminders(): Locator = locator("#promemoria")

    private fun E2e.sendFromTheDialog(page: Page, button: String, confirm: String) {
        press(page.reminders().linkNamed(button))
        page.waitForSettledDialog()
        touch(page.openDialog().buttonNamed(confirm))
    }

    // ---- The three cards in one detail ---------------------------------------------------------------------------------------------------------------------

    @E2eTest
    fun `t13 t11 t12 a menu is uploaded and a room is added, then a reminder is sent in the same page, and the cards stay, the dialogs and the forms of the others still work`(e2e: E2e) {
        val page = e2e.page
        e2e.application.getBean(MailQuota::class.java).release(MailKind.REMINDER, 1000)
        val w = e2e.arrivalsGathering()
        val previous = GatheringSupport(e2e.dsl(), e2e.clock()).make(
            title = "Convivenza dell’anno scorso", start = e2e.today().minusDays(400), end = e2e.today().minusDays(398), status = GatheringStatus.closed,
            custodians = listOf(w.martaId, w.paoloId),
        )
        // Two accounts with a child in forse (the first reminder), one who asked for the avviso and has a child of last year (the second), and one with nothing (nobody writes to them).
        val inDoubt = (1..2).map { n ->
            e2e.account("forse-$n").also { e2e.rows().register(w.id, it.id, "Bimbo$n", "Prova", LocalDate.of(2018, 3, 3), status = RegistrationStatus.maybe, email = it.email) }
        }
        val notice = e2e.account("avviso", notice = OffsetDateTime.now(e2e.clock()).minusDays(30)).also {
            e2e.rows().register(previous, it.id, "Sara", "Neri", LocalDate.of(2017, 6, 2), email = it.email)
        }
        val nothing = e2e.account("niente")
        val detail = "/convivenza/${w.id}"

        // The detail as the teacher finds it: five cards in order (and the retention after them), the reminders with their numbers, and nothing that repeats.
        e2e.open(detail)
        assertEquals(order, page.cards(), "the detail at the start")
        assertThat(page.reminders().locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*2 persone.*Ultimo invio\\s*Mai.*", Pattern.DOTALL))
        assertThat(page.reminders().locator("#promemoria-notice")).hasText(Pattern.compile(".*Lo riceverebbero\\s*1 persona.*Ultimo invio\\s*Mai.*", Pattern.DOTALL))
        assertThat(page.menuCard()).containsText("Nessun menù caricato.")
        assertThat(page.roomsCard()).containsText("Nessuna camera")
        page.healthy("detail at the start")
        assertAccessible(page, "detail with the three cards, at the start (${e2e.profile.label})")

        // The menu, through its own page: the answer is the detail, with the notice, and the reminders are as they were.
        e2e.touch(page.menuCard().linkNamed("Carica il menù"))
        assertThat(page).hasURL(e2e.url("$detail/menu"))
        page.getByLabel("File del menù").setInputFiles(FilePayload("menu-settembre-2027.pdf", "application/pdf", MenuSamples.pdf()))
        e2e.touch(page.buttonNamed("Carica il menù"))
        assertThat(page).hasURL(e2e.url(detail))
        assertThat(page.locator("main [data-fuoco]")).hasText("Menù caricato.")
        assertThat(page.menuCard()).containsText("menu-settembre-2027.pdf")
        assertEquals(order, page.cards(), "after the menu")
        assertThat(page.reminders().locator("#promemoria-maybe")).containsText("2 persone")
        page.healthy("detail after the menu")

        // A room, through its own pages (the card of the rooms leads to the page, the page has the form).
        e2e.touch(page.roomsCard().locator("a").first())
        assertThat(page).hasURL(e2e.url("$detail/camere"))
        e2e.touch(page.locator("main").linkNamed("Aggiungi una camera"))
        page.getByLabel("Nome della camera").fill("Camera 1")
        page.getByLabel("Posti letto").fill("4")
        e2e.press(page.locator("main").buttonNamed("Salva la camera"))
        assertThat(page.locator("main")).containsText("Camera salvata.")
        assertEquals(1, e2e.dsl().fetchCount(ROOM, ROOM.GATHERING_ID.eq(w.id)))
        e2e.open(detail)
        assertEquals(order, page.cards(), "after the room")
        assertThat(page.roomsCard()).containsText("1 camera · 4 posti")
        assertThat(page.menuCard()).containsText("menu-settembre-2027.pdf")
        page.healthy("detail after the room")
        assertAccessible(page, "detail with the three cards, full (${e2e.profile.label})")
        e2e.narrow(page, "the detail with the three cards, full")

        // The send. The elements of the other cards are marked, and so is the document: the answer must replace only the card of the reminders, and the page is not loaded again.
        page.evaluate(
            "() => { window.__same = 'si'; for (const id of ['menu', 'camere', 'quando', 'iscrizioni']) document.getElementById(id).dataset.keep = 'si'; " +
                "document.querySelector('main h1').dataset.keep = 'si'; }",
        )
        e2e.sendFromTheDialog(page, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        assertThat(page.reminders().locator("[role=status]")).containsText("Promemoria mandato a 2 persone.")
        assertEquals("si", page.evaluate("window.__same"), "the page was not loaded again")
        assertEquals(
            listOf("si", "si", "si", "si", "si"),
            page.evaluate("['menu', 'camere', 'quando', 'iscrizioni'].map(id => document.getElementById(id).dataset.keep).concat(document.querySelector('main h1').dataset.keep)"),
            "the other cards are the same elements as before the send",
        )
        assertThat(page).hasURL(e2e.url(detail))
        assertEquals(order, page.cards(), "after the send")
        assertThat(page.reminders().locator("#promemoria-maybe")).hasText(Pattern.compile(".*Ultimo invio\\s*\\d+ \\w+ \\d{4} alle \\d\\d:\\d\\d · a 2 persone.*", Pattern.DOTALL))
        assertThat(page.menuCard()).containsText("menu-settembre-2027.pdf")
        assertThat(page.roomsCard()).containsText("1 camera · 4 posti")
        page.healthy("detail after the send")
        assertEquals(1, e2e.dsl().fetchCount(REMINDER_BATCH, REMINDER_BATCH.GATHERING_ID.eq(w.id)))
        for (who in inDoubt) assertEquals(1, Mailbox.waitForMail(who.email).let { Mailbox.sentTo(who.email).size }, "the mail of ${who.email}")
        assertEquals(0, Mailbox.sentTo(notice.email).size, "the avviso did not go with the other")
        assertEquals(0, Mailbox.sentTo(nothing.email).size)
        assertAccessible(page, "detail after the first send (${e2e.profile.label})")
        e2e.narrow(page, "the detail after the first send")

        // The new card still opens its dialog (the script finds the new link), and sends the second kind, with the code of the page.
        e2e.sendFromTheDialog(page, "Manda il promemoria a chi ha chiesto l’avviso", "Manda l’avviso")
        assertThat(page.reminders().locator("[role=status]")).containsText("Promemoria mandato a 1 persona.")
        assertEquals("si", page.evaluate("window.__same"), "the page was not loaded again after the second")
        assertEquals(order, page.cards(), "after the second send")
        assertEquals(1, Mailbox.waitForMail(notice.email).let { Mailbox.sentTo(notice.email).size })
        assertEquals(2, e2e.dsl().fetchCount(REMINDER_BATCH, REMINDER_BATCH.GATHERING_ID.eq(w.id)))
        assertThat(page.reminders().locator("#promemoria-maybe")).hasText(Pattern.compile(".*Ultimo invio\\s*\\d+ \\w+ \\d{4} alle \\d\\d:\\d\\d · a 2 persone.*", Pattern.DOTALL))
        assertThat(page.reminders().locator("#promemoria-notice")).hasText(Pattern.compile(".*Ultimo invio\\s*\\d+ \\w+ \\d{4} alle \\d\\d:\\d\\d · a 1 persona.*", Pattern.DOTALL))
        page.healthy("detail after the second send")

        // After the swaps the dialog of the menu still opens (one dialog at a time, the one of the menu), «Annulla» takes nothing away, and «Togli» works with the code of the page.
        e2e.press(page.menuCard().linkNamed("Togli il menù"))
        page.waitForSettledDialog()
        assertEquals(1, page.locator("dialog[open]").count(), "one dialog is open")
        assertThat(page.openDialog().locator("h2")).hasText("Togliere il menù?")
        e2e.touch(page.openDialog().buttonNamed("Annulla"))
        assertEquals(0, page.locator("dialog[open]").count())
        assertEquals(1, e2e.dsl().fetchCount(GATHERING_MENU, GATHERING_MENU.GATHERING_ID.eq(w.id)), "Annulla takes nothing away")
        e2e.press(page.menuCard().linkNamed("Togli il menù"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Togli il menù"))
        assertThat(page).hasURL(e2e.url(detail))
        assertThat(page.locator("main [data-fuoco]")).hasText("Menù tolto.")
        assertThat(page.menuCard()).containsText("Nessun menù caricato.")
        assertEquals(0, e2e.dsl().fetchCount(GATHERING_MENU, GATHERING_MENU.GATHERING_ID.eq(w.id)))
        assertEquals(order, page.cards(), "after Togli")

        // The send is in the register and the page shows it again after the load: the two cards live together, and nothing was lost.
        assertThat(page.reminders().locator("#promemoria-maybe")).containsText("a 2 persone")
        assertThat(page.reminders().locator("#promemoria-notice")).containsText("a 1 persona")
        assertThat(page.roomsCard()).containsText("1 camera · 4 posti")
        page.healthy("detail after Togli")
        assertAccessible(page, "detail at the end (${e2e.profile.label})")
        e2e.narrow(page, "the detail at the end")
    }

    // ---- The cards in a draft, with the registrations closed and when the gathering is over: the three are there, and the reminders only say that they go with the registrations open -------------

    @E2eTest
    fun `t13 t11 t12 in a draft, closed and over the card of the reminders has only its sentence and no button, next to the rooms and the menu, and nothing repeats`(e2e: E2e) {
        val page = e2e.page
        for ((label, state) in listOf(
            "a draft" to Triple(GatheringStatus.draft, 30L, 32L),
            "closed" to Triple(GatheringStatus.closed, -1L, 1L),
            "over" to Triple(GatheringStatus.closed, -10L, -8L),
        )) {
            val w = e2e.arrivalsGathering(first = state.second, last = state.third, status = state.first)
            e2e.rows().register(w.id, e2e.someParent(), "Bimbo", "Prova", LocalDate.of(2018, 3, 3), status = RegistrationStatus.maybe)
            e2e.dsl().insertInto(GATHERING_MENU)
                .set(GATHERING_MENU.GATHERING_ID, w.id).set(GATHERING_MENU.FILE_NAME, "menu-settembre-2027.pdf").set(GATHERING_MENU.MEDIA_TYPE, "application/pdf")
                .set(GATHERING_MENU.CONTENT, MenuSamples.pdf()).set(GATHERING_MENU.UPLOADED_AT, OffsetDateTime.now(e2e.clock())).execute()
            e2e.dsl().insertInto(ROOM).set(ROOM.GATHERING_ID, w.id).set(ROOM.NAME, "Camera 1").set(ROOM.BEDS, 4.toShort()).execute()

            e2e.open("/convivenza/${w.id}")

            // A draft has no card of the retention (it has nothing to keep yet): the five cards of the detail, in order.
            assertEquals(if (label == "a draft") order - "conservazione" else order, page.cards(), "$label: the cards")
            assertThat(page.reminders().locator("#promemoria-t")).hasText("Promemoria")
            assertThat(page.reminders()).containsText("I promemoria si mandano con le iscrizioni aperte.")
            assertEquals(0, page.reminders().locator("a, button, form, dialog").count(), "$label: the card of the reminders has no button")
            assertThat(page.menuCard()).containsText("menu-settembre-2027.pdf")
            assertThat(page.roomsCard()).containsText("1 camera · 4 posti")
            page.healthy("detail, $label")
            assertAccessible(page, "detail, $label, with the three cards (${e2e.profile.label})")
            e2e.narrow(page, "the detail, $label")
        }
    }

    // ---- The complete notice (criterion 14: the sentence on the stop) -----------------------------------------------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `t13 c14 the complete notice says that the stop comes from the profile or from the link of the mail, also forwarded, and that Talità does not know who asked, and it reads well at 320 px`(e2e: E2e) {
        val page = e2e.page

        e2e.open("/informativa")

        val sentence = page.locator("section[aria-labelledby=dati] li:has-text('Di chi iscrive i bambini')")
        assertThat(sentence).hasCount(1)
        assertThat(sentence).containsText(
            "Di chi iscrive i bambini: indirizzo email, numero di telefono, data e ora in cui accetta l’affidamento, la data e l’ora della richiesta di avviso, se l’ha chiesta, " +
                "e la data e l’ora dello stop ai promemoria, se c’è.",
        )
        assertThat(sentence).containsText(
            "Lo stop si chiede dal profilo o dal link «Non mandarmi più promemoria» che c’è in ogni mail: il link funziona anche se la mail viene inoltrata, " +
                "e in quel caso può averlo chiesto chi l’ha ricevuta, e Talità non sa chi è.",
        )
        // The old wording is gone, and the sentence does not say that the stop is always the parent's.
        assertThat(page.locator("main")).not().containsText("se l’ha chiesto la data e l’ora della richiesta di avviso o dello stop")
        // The two other places that speak of the reminders: the row of the times and the rights.
        assertThat(page.locator("#per-quanto-tempo")).containsText("La richiesta di avviso e lo stop ai promemoria")
        assertThat(page.locator("#per-quanto-tempo")).containsText("Finché non li cambi o non cancelli l’account.")
        assertThat(page.locator("section[aria-labelledby=diritti]")).containsText("I promemoria li fermi con un tocco dalla mail o dal profilo")

        // Readable: the item is inside the screen (also at 320 px), nothing scrolls sideways, and its text is not cut by a box that is smaller than its content.
        for (width in listOf(if (e2e.profile.isPhone) 360 else 1280, 320)) {
            page.setViewportSize(width, 800)
            page.waitForSettledContent()
            val box = sentence.box()
            assertTrue(box.x >= 0 && box.x + box.width <= width + 0.5, "$width px: the sentence is inside the screen (${box.x}, ${box.width})")
            assertTrue(page.noHorizontalScroll(), "$width px: the notice scrolls sideways")
            assertEquals(true, sentence.evaluate("e => e.scrollWidth <= e.clientWidth + 1"), "$width px: the sentence is cut")
            assertEquals(true, sentence.evaluate("e => getComputedStyle(e).overflow === 'visible'"), "$width px: the item hides its overflow")
            assertAccessible(page, "complete notice at $width px (${e2e.profile.label})")
        }
        assertEquals(0, page.locator("script").count(), "no script in the notice")
        assertEquals(0, e2e.browserContext.cookies().size, "no cookie")
    }

    // ---- The profile and Iscrizioni of a parent, with the card of the email next to the other cards of main ----------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c03 the card Email da Talità sits in the profile between the access and I tuoi dati, and in Iscrizioni after the registrations, and nothing repeats`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.openGathering()
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        e2e.rows().register(w.id, me, "Luca", "Rossi", email = checkNotNull(e2e.signedInAs))

        e2e.open("/profilo")
        page.settled()
        val sections = (page.evaluate("[...document.querySelectorAll('main section[aria-labelledby]')].map(s => s.getAttribute('aria-labelledby'))") as List<*>).map { it as String }
        assertEquals(listOf("accesso", "email-t", "dati-titolo"), sections.filter { it in setOf("accesso", "email-t", "dati-titolo") }, "the profile")
        assertThat(page.locator("#email h2")).hasText("Email da Talità")
        page.healthy("profile of a parent")
        assertAccessible(page, "profile of a parent (${e2e.profile.label})")
        e2e.narrow(page, "the profile of a parent")

        e2e.open("/iscrizioni")
        page.settled()
        val last = page.evaluate("[...document.querySelectorAll('main section[aria-labelledby], main section[id]')].map(s => s.id || s.getAttribute('aria-labelledby')).slice(-1)[0]") as String
        assertTrue(last.startsWith("avviso"), "the card of the avviso is the last one in Iscrizioni, and it is $last")
        assertThat(page.locator("#avviso-card h2")).hasText("Avviso per la prossima convivenza")
        page.healthy("Iscrizioni of a parent")
        assertAccessible(page, "Iscrizioni of a parent (${e2e.profile.label})")
        e2e.narrow(page, "Iscrizioni of a parent")
    }
}
