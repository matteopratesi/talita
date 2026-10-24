package talita.e2e

import com.microsoft.playwright.APIResponse
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.RequestOptions
import talita.Addresses
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.CHILD
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.RETENTION_LOG
import talita.registration.Notes
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parent deletes the data of a child and the account, in a real browser, on a phone and on a desktop, with axe (criteria 16-19, 21-23, 25, 26, 34): the card «I tuoi dati»
 * of the profile, the dialogs with their words and their focus, the notice that says what stays and until when, «Iscrizioni» that only looks, the account that goes and the
 * access page that says so, what happens when the registrations are open (an explanation and no dialog), and without a network, with an error and without JavaScript.
 * Names, dates and numbers are synthetic.
 */
@E2eApplication
class ErasureE2eTest {

    /** The gathering of the tests of the registrations, finished [daysAgo] days ago and closed: the registrations of the parent stay a year, and the notes a month. */
    private fun E2e.finished(daysAgo: Long = 3): OpenGathering {
        dsl().deleteFrom(RETENTION_LOG).execute()
        val gathering = openGathering()
        val today = today()
        dsl().update(GATHERING).set(GATHERING.START_DATE, today.minusDays(daysAgo + 2)).set(GATHERING.END_DATE, today.minusDays(daysAgo)).set(GATHERING.STATUS, GatheringStatus.closed)
            .where(GATHERING.ID.eq(gathering.id)).execute()
        return gathering
    }

    /** The person of the test has Luca (confirmed, with notes on health) and Anna (withdrawn) at the gathering. */
    private fun E2e.twoChildren(gathering: OpenGathering): Pair<Long, Long> {
        val me = accountId(checkNotNull(signedInAs))
        val luca = rows().register(gathering.id, me, "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        val anna = rows().register(gathering.id, me, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.withdrawn)
        return luca to anna
    }

    private fun E2e.until(gathering: OpenGathering): String {
        val end = dsl().select(GATHERING.END_DATE).from(GATHERING).where(GATHERING.ID.eq(gathering.id)).fetchSingle(GATHERING.END_DATE)!!
        return talita.gathering.GatheringFormat.date(talita.retention.RetentionPolicy.STANDARD.registrationsDeletionDate(end))
    }

    // ---- A child (criteria 16, 18, 19, 20) ------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c16 c18 c19 the profile has the data, the dialog says what goes and what stays, and the confirmation says until when`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        val (luca, anna) = e2e.twoChildren(gathering)

        e2e.open("/profilo")

        assertThat(page).hasTitle("Profilo · Talità")
        assertThat(page.locator("h1")).hasText("Profilo")
        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Il tuo accesso", "I tuoi dati"))
        assertThat(page.locator("main")).containsText("Teniamo nome, cognome e data di nascita dei tuoi figli per precompilare l’iscrizione dell’anno dopo. Li cancelli quando vuoi.")
        assertThat(page.locator("main").linkNamed("Per quanto tempo teniamo i dati")).hasAttribute("href", "/informativa#per-quanto-tempo")
        assertThat(page.locator("main").linkNamed("Cancella i dati di Luca Rossi")).isVisible()
        assertThat(page.locator("main").linkNamed("Cancella i dati di Anna Rossi")).isVisible()
        assertThat(page.locator("main").linkNamed("Cancella il mio account")).isVisible()
        assertThat(page.locator("dialog[open]")).hasCount(0)
        assertTrue(page.locator("main").linkNamed("Cancella i dati di Luca Rossi").box().height >= 47.9, "a button of 48 px")
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "profile with the data (${e2e.profile.label})")

        // The dialog: the title, what goes now, what stays, the copies of safety, «Non si può annullare.». The first focus is on «Annulla».
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog.locator("h2")).hasText("Cancellare i dati di Luca?")
        assertThat(dialog).containsText("Cancelliamo subito")
        assertThat(dialog).containsText("Luca dalla tua lista di figli: non comparirà più quando iscrivi un bambino.")
        assertThat(dialog).containsText("Le segnalazioni su allergie, intolleranze e farmaci di Luca e il consenso che hai dato.")
        assertThat(dialog).containsText("L’iscrizione di Luca Rossi alla «Convivenza di settembre 2027», con il suo nome, la data di nascita e l’affidamento che hai accettato: resta fino al ${e2e.until(gathering)}, come prova dell’affidamento.")
        assertThat(dialog).containsText("Anche le copie di sicurezza, che sono cifrate, perdono questi dati entro 30 giorni.")
        assertThat(dialog).containsText("Non si può annullare.")
        assertThat(dialog.locator("h3")).hasText(arrayOf("Cancelliamo subito", "Resta"))
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        assertTrue(dialog.box().height <= page.viewport().height * 0.93 + 1, "the dialog fits the screen and scrolls inside its panel when it does not")
        assertAccessible(page, "dialog of a child (${e2e.profile.label})")

        // «Annulla» closes it and nothing changes.
        e2e.touch(dialog.buttonNamed("Annulla"))
        assertThat(page.locator("dialog[open]")).hasCount(0)
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE))

        // The confirmation: a change of page, with the notice that says until when, and Luca is not among the children any more.
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Cancella i dati di Luca"))

        assertThat(page).hasURL(e2e.url("/profilo"))
        val notice = page.locator("[role=status]")
        assertThat(notice).containsText("Dati di Luca cancellati.")
        assertThat(notice).containsText("Resta fino al ${e2e.until(gathering)} l’iscrizione di Luca alla «Convivenza di settembre 2027», come prova dell’affidamento. La trovi in «Iscrizioni».")
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertThat(page.locator("main").linkNamed("Cancella i dati di Luca Rossi")).hasCount(0)
        assertThat(page.locator("main").linkNamed("Cancella i dati di Anna Rossi")).isVisible()
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE), "the notes went")
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ID.eq(luca)), "the registration stays")
        assertAccessible(page, "profile after the deletion of a child (${e2e.profile.label})")

        // The registration is in «Iscrizioni», only to look at, with the date.
        e2e.touch(notice.linkNamed("Vai alle iscrizioni"))
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        val card = page.locator("#scheda-$luca")
        assertThat(card).hasText("Luca Rossi")
        assertThat(page.locator("article").first()).containsText("Conservata fino al ${e2e.until(gathering)} come prova dell’affidamento")
        assertThat(page.locator("article").first()).containsText("Segnalazioni")
        assertThat(page.locator("main").linkNamed("Modifica l’iscrizione di Luca Rossi")).hasCount(0)
        assertThat(page.locator("main").linkNamed("Ritira l’iscrizione di Luca Rossi")).hasCount(0)
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "registrations, only to look at (${e2e.profile.label})")

        // Anna goes with everything: nothing stays, and the notice says so.
        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Anna Rossi"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("Non resta niente.")
        e2e.touch(page.openDialog().buttonNamed("Cancella i dati di Anna"))
        assertThat(page.locator("[role=status]")).containsText("Dati di Anna cancellati.")
        assertThat(page.locator("[role=status]")).containsText("Non resta niente.")
        assertEquals(0, e2e.dsl().fetchCount(CHILD, CHILD.FIRST_NAME.eq("Anna")))
        assertEquals(0, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ID.eq(anna)))
        assertThat(page.locator("main")).containsText("Non ci sono figli collegati")
    }

    // ---- What blocks (criteria 17, 24) -----------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c17 c24 with the registrations open the explanation takes the place of the button, leads to the registrations and there is no dialog`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        val luca = e2e.rows().register(gathering.id, e2e.accountId(checkNotNull(e2e.signedInAs)), "Luca", "Rossi")

        e2e.open("/profilo")

        assertThat(page.locator("main")).containsText("Per cancellare i dati di Luca, ritira prima la sua iscrizione alla «Convivenza di settembre 2027».")
        assertThat(page.locator("main").linkNamed("Cancella i dati di Luca Rossi")).hasCount(0)
        assertThat(page.locator("main")).containsText("Per cancellare il tuo account, prima:")
        assertThat(page.locator("main")).containsText("ritira l’iscrizione di Luca alla «Convivenza di settembre 2027».")
        assertThat(page.locator("main").linkNamed("Cancella il mio account")).hasCount(0)
        assertThat(page.locator("dialog")).hasCount(0)
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "profile with the explanations (${e2e.profile.label})")

        e2e.press(page.locator("main").linkNamed("Vai alle iscrizioni, per ritirare quella di Luca Rossi").first())
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ID.eq(luca)))

        // With the registrations closed it is the teachers who have to do it: the same place, other words, no link.
        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(gathering.id)).execute()
        e2e.open("/profilo")
        assertThat(page.locator("main")).containsText("Le iscrizioni sono chiuse. Per cancellare i dati di Luca, chiedi ai maestri di ritirare la sua iscrizione alla «Convivenza di settembre 2027».")
        assertThat(page.locator("main").linkNamed("Vai alle iscrizioni, per ritirare quella di Luca Rossi")).hasCount(0)
        assertAccessible(page, "profile with the registrations closed (${e2e.profile.label})")
    }

    // ---- The account (criteria 23, 25, 26) --------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c23 c25 c26 the account goes with a dialog that says what stays, the access page says it once, and the old link opens a new account that is empty`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        val (luca, _) = e2e.twoChildren(gathering)
        val email = checkNotNull(e2e.signedInAs)
        val me = e2e.accountId(email)
        // A link that was asked before the deletion and not used yet: it is still good, and opens a new account.
        val oldLink = e2e.application.getBean(org.springframework.security.authentication.ott.OneTimeTokenService::class.java)
            .generate(org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest(email, java.time.Duration.ofMinutes(15))).tokenValue

        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella il mio account"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog.locator("h2")).hasText("Cancellare il tuo account?")
        assertThat(dialog).containsText("Il tuo indirizzo email e il tuo accesso a Talità.")
        assertThat(dialog).containsText("I dati di Anna Rossi: il nome, la data di nascita e l’iscrizione ritirata.")
        assertThat(dialog).containsText("Le segnalazioni di Luca Rossi e il consenso che hai dato.")
        assertThat(dialog).containsText("e il tuo indirizzo email scritto lì dentro: resta fino al ${e2e.until(gathering)}, come prova dell’affidamento.")
        assertThat(dialog).containsText("Non si può annullare.")
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        assertAccessible(page, "dialog of the account (${e2e.profile.label})")

        e2e.touch(dialog.buttonNamed("Cancella il mio account"))

        // The access page, as a whole page, with the notice once.
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.locator("h1")).hasText("Accedi a Talità")
        assertThat(page.locator("[role=status]")).containsText("Account cancellato.")
        assertThat(page.locator("[role=status]")).containsText("Se ti serve, puoi rientrare con lo stesso indirizzo: troverai un account nuovo e vuoto.")
        assertThat(page.locator("main")).not().containsText(e2e.until(gathering))
        assertThat(page.locator("nav[aria-label='Navigazione principale']")).hasCount(0)
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "access page after the deletion (${e2e.profile.label})")
        assertFalse(e2e.dsl().fetchExists(ACCOUNT, ACCOUNT.ID.eq(me)), "the account is gone")
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ID.eq(luca)), "the registration stays, without the account")
        assertEquals(1, e2e.dsl().fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("account-request")))

        // The session is over: a page of the person is the access page.
        e2e.open("/iscrizioni")
        assertThat(page).hasURL(e2e.url("/accedi"))
        assertThat(page.locator("[role=status]")).hasCount(0)

        // The old link opens a new account, and it is empty: none of the data of before.
        page.navigate(e2e.url("/accedi/conferma?token=$oldLink"))
        page.waitForSettledContent()
        e2e.touch(page.buttonNamed("Entra"))
        // The person asked for «Iscrizioni» before being sent to the access page: the login leads back to it.
        page.waitForURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("main")).containsText("Nessuna iscrizione")
        assertThat(page.locator("main")).not().containsText("Luca")
        assertTrue(e2e.accountId(email) != me, "a new account, not the old one")
    }

    // ---- Without a network, with an error, without JavaScript (criteria 21, 22) -------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c22 without a network the dialog closes, nothing changes, and the message has no «Riprova», and touching again deletes`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        val (luca, _) = e2e.twoChildren(gathering)
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        e2e.open("/profilo")

        // The request does not get there: the dialog closes first (otherwise the message would stay under its backdrop), and the page is as it was.
        page.route("**/profilo/figli/*/cancella") { route: Route -> route.abort() }
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Cancella i dati di Luca"))
        assertThat(page.messages()).containsText("Nessuna connessione")
        assertThat(page.messages().locator("button:has-text('Riprova')")).hasCount(0)
        assertThat(page.locator("dialog[open]")).hasCount(0)
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE), "nothing changed")
        assertEquals(me, e2e.dsl().select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.FIRST_NAME.eq("Luca")).fetchSingle(CHILD.ACCOUNT_ID), "and Luca is still among the children of the parent")
        page.unroute("**/profilo/figli/*/cancella")

        assertAccessible(page, "message without a network (${e2e.profile.label})")

        // Touching again, now that it works, deletes: it is a form, so it is enough to touch (a server that fails on a form of a page is the error page of the app, tried by the other tests).
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Cancella i dati di Luca"))
        assertThat(page.locator("[role=status]")).containsText("Dati di Luca cancellati.")
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ID.eq(luca)))
    }

    @E2eTest(teacher = false)
    fun `c21 without JavaScript the button is a page with the same words, and the answer is the profile with the notice`(e2e: E2e) {
        val gathering = e2e.finished()
        val address = Addresses.unique("e2e-senza-js")
        val plain = e2e.newPage(signedIn = false, teacher = false) { setJavaScriptEnabled(false) }
        e2e.signIn(plain, address, teacher = false)
        val me = e2e.accountId(address)
        val luca = e2e.rows().register(gathering.id, me, "Luca", "Rossi", notes = Notes("Arachidi", null, null))

        plain.navigate(e2e.url("/profilo"))
        plain.settled()
        plain.locator("main").linkNamed("Cancella i dati di Luca Rossi").click()
        plain.waitForURL(Pattern.compile(".*/profilo/figli/\\d+/cancella"))

        assertThat(plain.locator("h1")).hasText("Cancellare i dati di Luca?")
        assertThat(plain.locator("main")).containsText("Luca dalla tua lista di figli: non comparirà più quando iscrivi un bambino.")
        assertThat(plain.locator("main")).containsText("Non si può annullare.")
        assertThat(plain.locator("main").linkNamed("Annulla")).hasAttribute("href", "/profilo")
        plain.settled()
        plain.locator("main").buttonNamed("Cancella i dati di Luca").click()
        plain.waitForURL(e2e.url("/profilo"))

        assertThat(plain.locator("[role=status]")).containsText("Dati di Luca cancellati.")
        assertEquals(0, e2e.dsl().fetchCount(HEALTH_NOTE))
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ID.eq(luca)))
    }

    // ---- Added by the QA of TALITA-7: permissions, a page left open, another phone, the keyboard, the register that stays ------------------------

    private fun E2e.childOf(registration: Long): Long =
        dsl().select(REGISTRATION.CHILD_ID).from(REGISTRATION).where(REGISTRATION.ID.eq(registration)).fetchSingle(REGISTRATION.CHILD_ID)!!

    private fun E2e.requestsFor(child: Long): Int = dsl().fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("child-request").and(RETENTION_LOG.TARGET_ID.eq(child)))

    /** A request made by hand, as an old page or a script would: with the code of CSRF of the page that is open, and without the dialog. The answer is not followed. */
    private fun E2e.byHand(path: String): APIResponse {
        val csrf = page.locator("input[name=_csrf]").first().inputValue()
        return page.request().post(url(path), RequestOptions.create().setForm(FormData.create().set("_csrf", csrf)).setMaxRedirects(0))
    }

    /** Another page in its own context (another phone) that is inside with the same address, and shows [path]. */
    private fun E2e.anotherPhone(path: String = "/profilo"): com.microsoft.playwright.Page {
        val phone = newPage(signedIn = false, teacher = false)
        signIn(phone, checkNotNull(signedInAs), teacher = false)
        phone.navigate(url(path))
        phone.waitForLoadState(LoadState.LOAD)
        phone.settled()
        return phone
    }

    @E2eTest(teacher = false)
    fun `c21 a parent who asks to delete the child of someone else, with a link or by hand, gets the page that does not exist and nothing changes`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        val owner = e2e.someParent()
        val luca = e2e.rows().register(gathering.id, owner, "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        val lucaChild = e2e.childOf(luca)
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        e2e.rows().register(gathering.id, me, "Marco", "Verdi")
        val requests = e2e.dsl().fetchCount(RETENTION_LOG)

        // The profile has only the children of this person.
        e2e.open("/profilo")
        assertThat(page.locator("main").linkNamed("Cancella i dati di Marco Verdi")).isVisible()
        assertThat(page.locator("main")).not().containsText("Luca")
        assertThat(page.locator("main")).not().containsText("Rossi")

        // A link (the page without JavaScript, or the address typed): the page of an id that does not exist.
        val response = e2e.open("/profilo/figli/$lucaChild/cancella")
        assertEquals(404, response.status())
        assertThat(page).hasTitle("Pagina non trovata · Talità")
        assertThat(page.locator("h1")).hasText("Pagina non trovata")
        assertThat(page.locator("main")).not().containsText("Luca")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "page that does not exist for the child of someone else (${e2e.profile.label})")

        // By hand: the same, and nothing changes.
        e2e.open("/profilo")
        assertEquals(404, e2e.byHand("/profilo/figli/$lucaChild/cancella").status())
        assertEquals(owner, e2e.dsl().select(CHILD.ACCOUNT_ID).from(CHILD).where(CHILD.ID.eq(lucaChild)).fetchSingle(CHILD.ACCOUNT_ID), "the child is still of the other parent")
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE), "the notes are still there")
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ID.eq(luca)))
        assertEquals(requests, e2e.dsl().fetchCount(RETENTION_LOG), "nothing in the register")
    }

    @E2eTest(signedIn = false)
    fun `c21 c26 without access the pages that delete lead to the access page`(e2e: E2e) {
        val page = e2e.page
        for (path in listOf("/profilo/figli/1/cancella", "/profilo/account/cancella", "/profilo")) {
            e2e.open(path)
            assertThat(page).hasURL(e2e.url("/accedi"))
            assertThat(page.locator("h1")).hasText("Accedi a Talità")
        }
    }

    @E2eTest(teacher = false)
    fun `c16 c21 a teacher who is also a parent has in the profile only their own children, and for the child of someone else the page does not exist`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        TestAccounts.makeTeacher(e2e.dsl(), checkNotNull(e2e.signedInAs), "Elisa", "Conti")
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        e2e.rows().register(gathering.id, me, "Giulia", "Blu")
        val luca = e2e.rows().register(gathering.id, e2e.someParent(), "Luca", "Rossi")
        val lucaChild = e2e.childOf(luca)

        e2e.open("/profilo")

        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Il tuo accesso", "Il tuo nome", "I tuoi dati"))
        assertThat(page.locator("main").linkNamed("Cancella i dati di Giulia Blu")).isVisible()
        assertThat(page.locator("main")).not().containsText("Luca")
        // The dialog of the account has the words of both roles, and the child who is theirs.
        e2e.press(page.locator("main").linkNamed("Cancella il mio account"))
        page.waitForSettledDialog()
        val dialog = page.openDialog()
        assertThat(dialog).containsText("Il tuo nome e cognome dall’elenco dei maestri: non gestirai più la convivenza né vedrai i bambini.")
        assertThat(dialog).containsText("L’iscrizione di Giulia Blu alla «Convivenza di settembre 2027»")
        assertThat(dialog).containsText("e il tuo indirizzo email scritto lì dentro: resta fino al ${e2e.until(gathering)}")
        assertThat(dialog).not().containsText("Luca")
        assertAccessible(page, "dialog of the account of a teacher who is a parent (${e2e.profile.label})")
        e2e.touch(dialog.buttonNamed("Annulla"))

        // A teacher has no way to delete the data of the children of the others: for them too it is the page that does not exist.
        assertEquals(404, e2e.open("/profilo/figli/$lucaChild/cancella").status())
        e2e.open("/profilo")
        assertEquals(404, e2e.byHand("/profilo/figli/$lucaChild/cancella").status())
        assertEquals(1, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.ID.eq(luca)))
        assertEquals(0, e2e.requestsFor(lucaChild))
    }

    @E2eTest(teacher = false)
    fun `c22 a second request for the same child, from a page left open on another phone, is the profile with a notice that it was done already, and the register has one row`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        val (luca, _) = e2e.twoChildren(gathering)
        val child = e2e.childOf(luca)
        e2e.open("/profilo")
        val other = e2e.anotherPhone()
        // Both phones have the dialog open.
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()
        e2e.press(other.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        other.waitForSettledDialog()

        e2e.touch(page.openDialog().buttonNamed("Cancella i dati di Luca"))
        assertThat(page.locator("[role=status]")).containsText("Dati di Luca cancellati.")

        // The other phone still shows the dialog: the answer is the profile with a notice of information, and no error.
        assertThat(other.openDialog()).isVisible()
        e2e.touch(other.openDialog().buttonNamed("Cancella i dati di Luca"))
        assertThat(other).hasURL(e2e.url("/profilo"))
        assertThat(other.locator("main [role=status]")).containsText("Questi dati erano già stati cancellati.")
        assertThat(other.locator("main")).not().containsText("Dati di Luca cancellati.")
        assertEquals(0, other.messages().count(), "no message of error")
        assertThat(other.locator("h1")).hasText("Profilo")
        assertThat(other.locator("main").linkNamed("Cancella i dati di Luca Rossi")).hasCount(0)
        assertEquals(1, e2e.requestsFor(child), "one row in the register for the child")
        assertAccessible(other, "profile after a second request (${e2e.profile.label})")
    }

    @E2eTest(teacher = false)
    fun `c22 two touches in a row on the confirmation delete once, and the answer is the profile`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        val (_, anna) = e2e.twoChildren(gathering)
        val child = e2e.childOf(anna)
        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Anna Rossi"))
        page.waitForSettledDialog()

        page.evaluate("() => { const b = document.querySelector('dialog[open] button[type=submit]'); b.click(); b.click(); }")

        assertThat(page.locator("main [role=status]")).containsText(Pattern.compile("Dati di Anna cancellati\\.|Questi dati erano già stati cancellati\\."))
        assertThat(page.locator("h1")).hasText("Profilo")
        assertEquals(0, page.messages().count())
        assertEquals(1, e2e.requestsFor(child), "one row in the register")
        assertEquals(0, e2e.dsl().fetchCount(CHILD, CHILD.FIRST_NAME.eq("Anna")))
    }

    @E2eTest(teacher = false)
    fun `c17 c22 a request that became impossible while the page was open changes nothing, and the profile says what to do first`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        val (luca, _) = e2e.twoChildren(gathering)
        val child = e2e.childOf(luca)
        val requests = e2e.dsl().fetchCount(RETENTION_LOG)
        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()

        // Meanwhile Luca is registered for a gathering that has still to happen.
        val next = GatheringSupport(e2e.dsl(), e2e.application.getBean(Clock::class.java))
            .make(title = "Convivenza di novembre 2027", start = e2e.today().plusDays(30), status = GatheringStatus.open, code = GatheringSupport.nextCode(), custodians = listOf(gathering.martaId))
        e2e.dsl().execute(
            "insert into registration (gathering_id, child_id, account_id, phone, attendance, status, custody_text, custody_text_version, custody_accepted_at, custody_accepted_by_email, " +
                "custody_religion_consent, created_at, updated_at) select ?, child_id, account_id, phone, attendance, status, custody_text, custody_text_version, custody_accepted_at, " +
                "custody_accepted_by_email, custody_religion_consent, created_at, updated_at from registration where id = ?",
            next, luca,
        )

        e2e.touch(page.openDialog().buttonNamed("Cancella i dati di Luca"))

        val alert = page.locator("main [role=alert]")
        assertThat(alert).containsText("Non è stato cancellato niente. Le cose da fare prima sono scritte qui sotto.")
        assertThat(page.locator("main")).containsText("Per cancellare i dati di Luca, ritira prima la sua iscrizione alla «Convivenza di novembre 2027».")
        assertThat(page.locator("main").linkNamed("Cancella i dati di Luca Rossi")).hasCount(0)
        assertThat(page.locator("dialog[open]")).hasCount(0)
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE), "the notes are still there")
        assertEquals(2, e2e.dsl().fetchCount(REGISTRATION, REGISTRATION.CHILD_ID.eq(child)))
        assertEquals(requests, e2e.dsl().fetchCount(RETENTION_LOG), "nothing in the register")
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "profile after a request that was refused (${e2e.profile.label})")
    }

    @E2eTest(teacher = false)
    fun `c26 another phone with the session of an account that was deleted goes to the access page as a whole page, from a touch on the navigation and from a dialog left open, never with an error`(e2e: E2e) {
        val page = e2e.page
        e2e.dsl().deleteFrom(RETENTION_LOG).execute()
        e2e.open("/profilo")
        val dialogLeftOpen = e2e.anotherPhone()
        val navigating = e2e.anotherPhone()
        e2e.press(dialogLeftOpen.locator("main").linkNamed("Cancella il mio account"))
        dialogLeftOpen.waitForSettledDialog()

        // The first phone deletes the account.
        e2e.press(page.locator("main").linkNamed("Cancella il mio account"))
        page.waitForSettledDialog()
        e2e.touch(page.openDialog().buttonNamed("Cancella il mio account"))
        assertThat(page).hasURL(e2e.url("/accedi"))

        // A touch on the navigation of the second phone: the access page, whole (no navigation, no message), not a piece of it inside the old page.
        e2e.touch(navigating.navLink("Iscrizioni"))
        navigating.waitForURL(e2e.url("/accedi"))
        assertThat(navigating.locator("h1")).hasText("Accedi a Talità")
        assertThat(navigating.mainNavigation()).hasCount(0)
        assertEquals(0, navigating.messages().count(), "no message of error")
        assertThat(navigating.locator("#email")).isVisible()

        // The dialog that was left open on the third phone: a second tap on «Cancella il mio account» ends in the same place.
        e2e.touch(dialogLeftOpen.openDialog().buttonNamed("Cancella il mio account"))
        dialogLeftOpen.waitForURL(e2e.url("/accedi"))
        assertThat(dialogLeftOpen.locator("h1")).hasText("Accedi a Talità")
        assertThat(dialogLeftOpen.mainNavigation()).hasCount(0)
        assertEquals(0, dialogLeftOpen.messages().count(), "no message of error")
        assertEquals(1, e2e.dsl().fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("account-request")), "the register has one row for the account")
        assertAccessible(navigating, "access page on another phone (${e2e.profile.label})")
    }

    @E2eTest(teacher = false, profiles = [Profile.DESKTOP])
    fun `c18 Escape closes the dialog, the focus goes back to the button that opened it, and nothing is deleted`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        e2e.twoChildren(gathering)
        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()

        page.keyboard().press("Escape")

        assertThat(page.locator("dialog[open]")).hasCount(0)
        page.focusIs("document.activeElement.getAttribute('aria-label') === 'Cancella i dati di Luca Rossi'")
        assertEquals(1, e2e.dsl().fetchCount(HEALTH_NOTE), "nothing was deleted")
        assertEquals(2, e2e.dsl().fetchCount(CHILD, CHILD.ACCOUNT_ID.eq(e2e.accountId(checkNotNull(e2e.signedInAs)))), "both children are still among the children of the parent")
    }

    @E2eTest(teacher = false)
    fun `c19 c20 a withdrawn registration that has presences stays as the register of the gathering, and its card is only to look at while the gathering goes on`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.openGathering()
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        val luca = e2e.rows().register(gathering.id, me, "Luca", "Rossi", status = RegistrationStatus.withdrawn)
        e2e.dsl().insertInto(CHECKIN).set(CHECKIN.REGISTRATION_ID, luca).set(CHECKIN.DAY, gathering.friday).set(CHECKIN.RECORDED_AT, OffsetDateTime.now())
            .set(CHECKIN.RECORDED_BY_ACCOUNT_ID, gathering.martaId).execute()
        val until = e2e.until(gathering)

        // Withdrawn registrations do not block: the dialog says that the registration stays as the register of the gathering.
        e2e.open("/iscrizioni")
        assertThat(page.locator("main").linkNamed("Rimetti l’iscrizione di Luca Rossi")).isVisible()
        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()
        assertThat(page.openDialog()).containsText("L’iscrizione ritirata di Luca Rossi alla «Convivenza di settembre 2027», con il suo nome, la data di nascita e l’affidamento che hai accettato, più le presenze e i pagamenti segnati dai maestri: resta fino al $until, come registro della convivenza.")
        e2e.touch(page.openDialog().buttonNamed("Cancella i dati di Luca"))
        val notice = page.locator("main [role=status]")
        assertThat(notice).containsText("Dati di Luca cancellati.")
        assertThat(notice).containsText("Resta fino al $until l’iscrizione ritirata di Luca alla «Convivenza di settembre 2027», come registro della convivenza. La trovi in «Iscrizioni».")
        assertEquals(1, e2e.dsl().fetchCount(CHECKIN, CHECKIN.REGISTRATION_ID.eq(luca)), "the presence stays")

        // The card is still in «Iscrizioni», only to look at: no way to put it back, and the date.
        e2e.touch(notice.linkNamed("Vai alle iscrizioni"))
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("article").first()).containsText("Luca Rossi")
        assertThat(page.locator("article").first()).containsText("Conservata fino al $until come registro della convivenza")
        assertThat(page.locator("main").linkNamed("Rimetti l’iscrizione di Luca Rossi")).hasCount(0)
        assertThat(page.locator("main").linkNamed("Modifica l’iscrizione di Luca Rossi")).hasCount(0)
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "card of a registration that stays while the gathering goes on (${e2e.profile.label})")
    }

    // The first focus is on «Annulla», at the bottom of the dialog: on a small phone, where the dialog is taller than the screen, the browser used to scroll the panel to it,
    // so the title and «Cancelliamo subito» were above the edge and the person saw «Resta», «Non si può annullare.» and the two buttons first (QA TALITA-7, defect D1).
    // Now the panel opens at its top and the focus stays on «Annulla».
    @E2eTest(teacher = false, profiles = [Profile.PHONE])
    fun `c18 on a small phone the dialog opens showing its title and what is deleted now, not scrolled to the bottom`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        val me = e2e.accountId(checkNotNull(e2e.signedInAs))
        e2e.rows().register(gathering.id, me, "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        for ((width, height) in listOf(375 to 667, 360 to 640, 320 to 568)) {
            page.setViewportSize(width, height)
            e2e.open("/profilo")
            e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
            page.waitForSettledDialog()
            page.assertDialogOpensAtItsTop("${width}x$height: dialog of the child")
            page.keyboard().press("Escape")
        }

        // Two registrations: the dialog of a child and the one of the account, on a tall phone, are still taller than the screen.
        e2e.rows().register(gathering.id, me, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.withdrawn)
        page.setViewportSize(375, 812)
        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()
        page.assertDialogOpensAtItsTop("375x812: dialog of a child with two registrations")
        page.keyboard().press("Escape")
        e2e.press(page.locator("main").linkNamed("Cancella il mio account"))
        page.waitForSettledDialog()
        page.assertDialogOpensAtItsTop("375x812: dialog of the account with two registrations")
        page.keyboard().press("Escape")
    }

    @E2eTest(teacher = false, profiles = [Profile.DESKTOP])
    fun `c18 on the desktop the dialog opens showing its title and what is deleted now, with the focus on Annulla`(e2e: E2e) {
        val page = e2e.page
        val gathering = e2e.finished()
        e2e.twoChildren(gathering)
        e2e.open("/profilo")
        e2e.press(page.locator("main").linkNamed("Cancella i dati di Luca Rossi"))
        page.waitForSettledDialog()
        page.assertDialogOpensAtItsTop("desktop: dialog of a child")
        page.keyboard().press("Escape")
        e2e.press(page.locator("main").linkNamed("Cancella il mio account"))
        page.waitForSettledDialog()
        page.assertDialogOpensAtItsTop("desktop: dialog of the account")
    }

    /** The open dialog starts at its top: the title and «Cancelliamo subito» are in the screen, and the first focus is still on «Annulla». */
    private fun Page.assertDialogOpensAtItsTop(label: String) {
        val dialog = openDialog()
        fun onScreen(selector: String): Boolean = dialog.locator(selector).first().box().let { it.y >= 0 && it.y + it.height <= viewport().height }
        assertEquals(0, (dialog.evaluate("d => Math.round(d.scrollTop)") as Number).toInt(), "$label: the panel is at its top when it opens")
        assertTrue(onScreen("h2"), "$label: the title of the dialog is on the screen when it opens")
        assertTrue(onScreen("h3"), "$label: «Cancelliamo subito» is on the screen when the dialog opens")
        focusIs("document.activeElement.textContent.trim() === 'Annulla'")
    }
}
