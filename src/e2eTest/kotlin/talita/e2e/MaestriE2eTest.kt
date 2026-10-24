package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import org.jooq.DSLContext
import talita.Addresses
import talita.account.EmailAddress
import talita.account.TestAccounts
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.mail.Mailbox
import talita.teacher.Invitations
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * «Maestri» and the name in the profile, in a real browser, on a phone and on a desktop, with axe (criteria 40-47 and 49): the page, the
 * invitations, the dialogs of «Ritira» and «Togli», the last teacher, the notice that takes the focus, the dialog that gives way to the
 * message when a request fails, the pages that stand in for the dialogs without JavaScript, and the card «Il tuo nome».
 */
@E2eApplication
class MaestriE2eTest {

    private fun hasText(text: String) = Locator.FilterOptions().setHasText(text)

    private fun E2e.dsl(): DSLContext = application.getBean(DSLContext::class.java)

    /**
     * Only these teachers, and no invitation: the person of the test is a teacher too, with the name «Elisa Conti» (the session stays,
     * the role is read at every request). The database is shared with the other tests, which leave their own teachers and invitations.
     */
    private fun E2e.onlyTeachers(me: String = checkNotNull(signedInAs), others: List<Triple<String, String, String>> = emptyList()) {
        dsl().deleteFrom(TEACHER_INVITATION).execute()
        dsl().deleteFrom(TEACHER).execute()
        TestAccounts.makeTeacher(dsl(), me, "Elisa", "Conti")
        for ((email, first, last) in others) TestAccounts.makeTeacher(dsl(), email, first, last)
    }

    private fun E2e.invite(address: String) {
        application.getBean(Invitations::class.java).invite(EmailAddress.parse(address)!!)
    }

    private fun Page.row(text: String): Locator = locator("main li").filter(hasText(text))

    private fun Locator.buttonNamed(name: String): Locator = getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name))

    private fun Page.openDialog(): Locator = locator("dialog[open]")

    /** Waits for the sheet or the dialog to be still, so that positions and colors are the final ones. */
    private fun Page.waitForSettledDialog() {
        waitUntil("document.querySelector('dialog[open]') && document.querySelector('dialog[open]').getAnimations().every(a => a.playState === 'finished')")
    }

    /**
     * A tap or a click on a button of a row. Playwright scrolls an element into view only if it is not in the view at all, and a
     * button that shows its top above the bottom bar of the phone is «in the view» but under the bar: so it is centred first, as a
     * person does by scrolling.
     */
    private fun E2e.press(button: Locator) {
        button.evaluate("e => e.scrollIntoView({ block: 'center' })")
        touch(button)
    }

    private fun Page.focusIs(expression: String) = waitUntil("document.activeElement && ($expression)")

    /** A page that was just loaded, with its entrance animation over (Playwright does not tap what is moving, and with JavaScript off it cannot tell). */
    private fun Page.settled() {
        waitForLoadState()
        waitForSettledContent()
    }

    private fun Page.noHorizontalScroll() = (evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt() <= 0

    // ---- The page (criterion 40) ----------------------------------------------------------------------------------------

    @E2eTest
    fun `c40 Maestri is reached from Convivenza, lists the teachers by name and the invitations by address with the expiry, and is accessible`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyTeachers(others = listOf(Triple(Addresses.unique("e2e-collega"), "Paolo", "Ferri")))
        val waiting = Addresses.unique("e2e-in-attesa")
        val expired = Addresses.unique("e2e-scaduto")
        e2e.invite(waiting)
        e2e.invite(expired)
        e2e.dsl().update(TEACHER_INVITATION).set(TEACHER_INVITATION.EXPIRES_AT, OffsetDateTime.now().minusDays(2)).where(TEACHER_INVITATION.EMAIL.eq(expired)).execute()
        e2e.open("/convivenza")
        assertThat(page.locator("main h2#maestri")).hasText("Maestri")

        e2e.touch(page.linkNamed("Maestri"))

        assertThat(page).hasURL(e2e.url("/maestri"))
        assertThat(page).hasTitle("Maestri · Talità")
        assertThat(page.locator("h1")).hasText("Maestri")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Convivenza")
        assertThat(page.locator("main")).containsText("Chi organizza la convivenza insieme a te. I maestri vedono tutta la convivenza, i bambini e le segnalazioni.")
        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Invita un maestro", "I maestri", "Inviti in attesa"))
        assertThat(page.row("Elisa Conti")).containsText("Sei tu")
        assertThat(page.row("Paolo Ferri")).isVisible()
        assertThat(page.row("Paolo Ferri")).not().containsText("Sei tu")
        assertThat(page.row(waiting)).containsText("Scade il")
        assertThat(page.row(expired)).containsText("Scaduto")
        assertThat(page.row(expired)).containsText("Scadeva il")
        assertThat(page.row(waiting).buttonNamed("Manda di nuovo")).isVisible()
        assertThat(page.row(waiting).buttonNamed("Ritira")).isVisible()
        assertTrue(page.noHorizontalScroll(), "the page scrolls sideways")
        // The buttons of a row are big enough for a finger.
        for (button in page.locator("main li button").all()) assertTrue(button.box().height >= 44, "a button of a row is ${button.box().height} px high")
        assertAccessible(page, "Maestri (${e2e.profile.label})")
    }

    // ---- Inviting (criteria 41 and 42) --------------------------------------------------------------------------------------

    @E2eTest
    fun `c41 inviting an address puts the notice in the page with the focus on it, the new row, an empty field, and the mail arrives`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyTeachers()
        e2e.open("/maestri")
        val address = Addresses.unique("e2e-invitato")
        page.addressField().fill(address)

        e2e.touch(page.buttonNamed("Manda l’invito"))

        val notice = page.locator("#maestri-contenuto [role=status]")
        assertThat(notice).hasText("Invito mandato.")
        assertThat(page.row(address)).containsText("Scade il")
        assertThat(page.addressField()).hasValue("")
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertThat(page).hasURL(e2e.url("/maestri"))
        assertThat(page.locator("h1")).hasText("Maestri")
        assertEquals("Ti hanno invitato come maestro su Talità", Mailbox.waitForMail(address).subject)
        assertAccessible(page, "Maestri after an invitation (${e2e.profile.label})")

        // «Manda di nuovo»: the same notice, one row, a second mail.
        e2e.press(page.row(address).buttonNamed("Manda di nuovo"))
        assertThat(page.locator("#maestri-contenuto [role=status]")).hasText("Invito mandato.")
        assertThat(page.row(address)).hasCount(1)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (Mailbox.sentTo(address).size < 2 && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals(2, Mailbox.sentTo(address).size)
    }

    @E2eTest
    fun `c42 a wrong address has its error under the field, with the focus on it, and an address that is a teacher says È già tra i maestri`(e2e: E2e) {
        val page = e2e.page
        val colleague = Addresses.unique("e2e-gia-maestro")
        e2e.onlyTeachers(others = listOf(Triple(colleague, "Paolo", "Ferri")))
        e2e.open("/maestri")

        page.addressField().fill("nome@")
        e2e.touch(page.buttonNamed("Manda l’invito"))

        assertThat(page.getByText("Scrivi un indirizzo email completo, per esempio nome@example.org.")).isVisible()
        assertThat(page.addressField()).hasAttribute("aria-invalid", "true")
        assertThat(page.addressField()).hasValue("nome@")
        page.focusIs("document.activeElement.id === 'email'")
        assertAccessible(page, "Maestri with a wrong address (${e2e.profile.label})")

        page.addressField().fill(colleague)
        e2e.touch(page.buttonNamed("Manda l’invito"))

        assertThat(page.getByText("È già tra i maestri.")).isVisible()
        assertThat(page.getByText("Scrivi un indirizzo email completo")).hasCount(0)
        assertThat(page.addressField()).hasValue(colleague)
        page.focusIs("document.activeElement.id === 'email'")
        assertEquals(0, e2e.dsl().fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(colleague)))
        assertAccessible(page, "Maestri with an address that is a teacher (${e2e.profile.label})")
    }

    // ---- Withdrawing (criterion 43) and the dialogs ----------------------------------------------------------------------------

    @E2eTest
    fun `c43 Ritira opens a dialog with the first focus on Annulla, Esc closes it and changes nothing, the verb of the title withdraws the invitation`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyTeachers()
        val address = Addresses.unique("e2e-ritirato")
        e2e.invite(address)
        e2e.open("/maestri")

        e2e.press(page.row(address).buttonNamed("Ritira"))

        val dialog = page.openDialog()
        assertThat(dialog).isVisible()
        assertThat(dialog.locator("h2")).hasText("Ritirare l’invito?")
        assertThat(dialog).containsText("L’invito a $address non varrà più. La persona potrà entrare in Talità, ma non come maestro.")
        assertThat(dialog.buttonNamed("Ritira l’invito")).isVisible()
        assertThat(dialog.buttonNamed("Annulla")).isVisible()
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        assertThat(dialog).hasAccessibleName("Ritirare l’invito?")
        page.waitForSettledDialog()
        // On the phone it is a sheet at the bottom of the screen, on the desktop a dialog in the middle.
        val box = dialog.box()
        val viewport = page.viewport()
        if (e2e.profile.isPhone) {
            assertTrue(Math.abs(box.y + box.height - viewport.height) < 2, "the sheet does not touch the bottom: ${box.y + box.height} of ${viewport.height}")
            assertTrue(Math.abs(box.width - viewport.width) < 2, "the sheet is ${box.width} px wide on a screen of ${viewport.width}")
        } else {
            assertTrue(Math.abs(box.x + box.width / 2 - viewport.width / 2.0) < 2, "the dialog is not in the middle")
            assertTrue(box.width <= 448 + 1, "the dialog is ${box.width} px wide")
        }
        assertAccessible(page, "dialog Ritira (${e2e.profile.label})")

        // Esc: closed, nothing changed, the focus is back on the button that opened it.
        page.keyboard().press("Escape")
        assertThat(page.openDialog()).hasCount(0)
        assertThat(page.row(address)).isVisible()
        assertEquals(1, e2e.dsl().fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(address)))
        page.focusIs("document.activeElement.getAttribute('data-apri') === 'ritira-0'")

        // Annulla too.
        e2e.press(page.row(address).buttonNamed("Ritira"))
        e2e.touch(page.openDialog().buttonNamed("Annulla"))
        assertThat(page.openDialog()).hasCount(0)
        assertEquals(1, e2e.dsl().fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(address)))

        e2e.press(page.row(address).buttonNamed("Ritira"))
        e2e.touch(page.openDialog().buttonNamed("Ritira l’invito"))

        assertThat(page.locator("#maestri-contenuto [role=status]")).hasText("Invito ritirato.")
        assertThat(page.row(address)).hasCount(0)
        assertThat(page.openDialog()).hasCount(0)
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertEquals(0, e2e.dsl().fetchCount(TEACHER_INVITATION, TEACHER_INVITATION.EMAIL.eq(address)))
        assertThat(page.getByText("Nessun invito in attesa.")).isVisible()
        assertAccessible(page, "Maestri after withdrawing (${e2e.profile.label})")
    }

    // ---- Taking a teacher away (criterion 45) ---------------------------------------------------------------------------------

    @E2eTest
    fun `c45 Togli opens a dialog that names the person, and the verb of the title takes them away, the notice in the page and one teacher left with the reason`(e2e: E2e) {
        val page = e2e.page
        val other = Addresses.unique("e2e-tolto")
        e2e.onlyTeachers(others = listOf(Triple(other, "Giulia", "Serra")))
        e2e.open("/maestri")
        assertThat(page.row("Giulia Serra").buttonNamed("Togli")).isVisible()

        e2e.press(page.row("Giulia Serra").buttonNamed("Togli"))

        val dialog = page.openDialog()
        assertThat(dialog.locator("h2")).hasText("Togliere Giulia Serra dai maestri?")
        assertThat(dialog).containsText("Giulia Serra non potrà più gestire la convivenza né vedere i bambini e le segnalazioni. Il suo nome viene cancellato dall’elenco dei maestri. Potrà ancora entrare in Talità come genitore.")
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        page.waitForSettledDialog()
        assertAccessible(page, "dialog Togli (${e2e.profile.label})")

        e2e.touch(dialog.buttonNamed("Togli dai maestri"))

        assertThat(page.locator("#maestri-contenuto [role=status]")).hasText("Giulia Serra non è più tra i maestri.")
        assertThat(page.row("Giulia Serra")).hasCount(0)
        assertThat(page.openDialog()).hasCount(0)
        assertEquals(0, e2e.dsl().fetchCount(TEACHER, TEACHER.ACCOUNT_ID.eq(e2e.dsl().select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(other)))))
        // The only one left: no «Togli», and the reason where the button was.
        assertThat(page.row("Elisa Conti")).containsText("Serve almeno un maestro: per toglierti, invita prima un altro maestro.")
        assertThat(page.locator("main li").buttonNamed("Togli")).hasCount(0)
        assertTrue(page.noHorizontalScroll())
        assertAccessible(page, "Maestri with one teacher (${e2e.profile.label})")
    }

    @E2eTest
    fun `c45 a teacher who takes themself away arrives at the home as a parent with the notice, and the pages of the teachers are closed to them`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyTeachers(others = listOf(Triple(Addresses.unique("e2e-resta"), "Paolo", "Ferri")))
        e2e.open("/maestri")

        e2e.press(page.row("Sei tu").buttonNamed("Togli"))

        val dialog = page.openDialog()
        assertThat(dialog.locator("h2")).hasText("Toglierti dai maestri?")
        assertThat(dialog).containsText("Non potrai più gestire la convivenza né vedere i bambini e le segnalazioni. Il tuo nome viene cancellato dall’elenco dei maestri. Un altro maestro potrà invitarti di nuovo.")

        e2e.touch(dialog.buttonNamed("Toglimi dai maestri"))

        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("main [role=status]")).hasText("Non sei più tra i maestri.")
        assertThat(page.locator("nav a")).hasCount(2)
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertEquals(403, e2e.open("/maestri").status())
        assertEquals(403, e2e.open("/convivenza").status())
    }

    @E2eTest
    fun `c46 a page opened with two teachers and then left with one answers the last teacher with the reason, and nothing is taken away`(e2e: E2e) {
        val page = e2e.page
        val other = Addresses.unique("e2e-ultimo")
        e2e.onlyTeachers(others = listOf(Triple(other, "Paolo", "Ferri")))
        e2e.open("/maestri")
        e2e.press(page.row("Sei tu").buttonNamed("Togli"))
        assertThat(page.openDialog()).isVisible()
        // Meanwhile the other teacher goes away.
        TestAccounts.removeTeacher(e2e.dsl(), other)

        e2e.touch(page.openDialog().buttonNamed("Toglimi dai maestri"))

        val notice = page.locator("#maestri-contenuto [role=alert]")
        assertThat(notice).hasText("Serve almeno un maestro. Per togliere questa persona, invita prima un altro maestro.")
        assertThat(page.openDialog()).hasCount(0)
        assertThat(page).hasURL(e2e.url("/maestri"))
        assertThat(page.row("Elisa Conti")).containsText("Sei tu")
        assertEquals(1, e2e.dsl().fetchCount(TEACHER))
        page.focusIs("document.activeElement.getAttribute('role') === 'alert'")
        assertAccessible(page, "Maestri with the last teacher (${e2e.profile.label})")
    }

    // ---- A request that fails while a dialog is open --------------------------------------------------------------------------

    @E2eTest
    fun `c45 when the request of a dialog fails the dialog closes, the message is seen above the page, and the page is as it was`(e2e: E2e) {
        val page = e2e.page
        val other = Addresses.unique("e2e-rete")
        e2e.onlyTeachers(others = listOf(Triple(other, "Paolo", "Ferri")))
        e2e.open("/maestri")
        e2e.press(page.row("Paolo Ferri").buttonNamed("Togli"))
        assertThat(page.openDialog()).isVisible()

        e2e.browserContext.setOffline(true)
        e2e.touch(page.openDialog().buttonNamed("Togli dai maestri"))

        val message = page.messages().filter(hasText("Nessuna connessione"))
        assertThat(message).hasCount(1)
        assertThat(page.openDialog()).hasCount(0)
        assertThat(message).isVisible()
        // Nothing covers it: what is at the middle of the message is the message.
        val box = message.box()
        val onTop = page.evaluate(
            "([x, y]) => { const e = document.elementFromPoint(x, y); return !!e && !!e.closest('#messaggi'); }",
            listOf(box.x + box.width / 2, box.y + box.height / 2),
        )
        assertEquals(true, onTop, "the message is covered")
        assertThat(page.row("Paolo Ferri")).isVisible()
        assertEquals(2, e2e.dsl().fetchCount(TEACHER))
        assertAccessible(page, "message with the dialog gone (${e2e.profile.label})")

        e2e.browserContext.setOffline(false)
        e2e.touch(page.messages().locator("[data-azione=chiudi]"))
        assertThat(page.messages()).hasCount(0)
        // And the page still works.
        e2e.press(page.row("Paolo Ferri").buttonNamed("Togli"))
        e2e.touch(page.openDialog().buttonNamed("Togli dai maestri"))
        assertThat(page.row("Paolo Ferri")).hasCount(0)
    }

    // ---- Without JavaScript (criterion 45) ------------------------------------------------------------------------------------

    @E2eTest
    fun `c45 without JavaScript Togli and Ritira go through a page that asks, with the words of the dialog and Annulla as a link, and invitations work`(e2e: E2e) {
        val me = Addresses.unique("e2e-senza-js")
        val other = Addresses.unique("e2e-senza-js-collega")
        val page = e2e.newPage(signedIn = false) { setJavaScriptEnabled(false) }
        e2e.signIn(on = page, email = me, teacher = true)
        e2e.onlyTeachers(me = me, others = listOf(Triple(other, "Paolo", "Ferri")))
        val guest = Addresses.unique("e2e-senza-js-invitato")
        e2e.invite(guest)
        page.navigate(e2e.url("/maestri"))
        page.settled()

        // Togli: the page that asks.
        e2e.press(page.row("Paolo Ferri").buttonNamed("Togli"))
        assertThat(page.locator("h1")).hasText("Togliere Paolo Ferri dai maestri?")
        page.settled()
        assertThat(page.locator("main")).containsText("Paolo Ferri non potrà più gestire la convivenza né vedere i bambini e le segnalazioni.")
        assertThat(page.linkNamed("Annulla")).hasAttribute("href", "/maestri")
        assertThat(page.buttonNamed("Togli dai maestri")).isVisible()
        assertEquals(2, e2e.dsl().fetchCount(TEACHER), "asking took the teacher away")
        e2e.touch(page.linkNamed("Annulla"))
        assertThat(page).hasURL(e2e.url("/maestri"))
        page.settled()
        assertThat(page.row("Paolo Ferri")).isVisible()

        // Ritira: the same.
        e2e.press(page.row(guest).buttonNamed("Ritira"))
        assertThat(page.locator("h1")).hasText("Ritirare l’invito?")
        page.settled()
        assertThat(page.locator("main")).containsText("L’invito a $guest non varrà più.")
        e2e.touch(page.buttonNamed("Ritira l’invito"))
        assertThat(page).hasURL(e2e.url("/maestri"))
        assertThat(page.locator("main [role=status]")).hasText("Invito ritirato.")
        assertThat(page.row(guest)).hasCount(0)
        page.settled()

        // A wrong address: the page comes back with the error, at the address of the form; asking for that address again leads to «Maestri».
        page.addressField().fill("nome@")
        e2e.touch(page.buttonNamed("Manda l’invito"))
        assertThat(page.getByText("Scrivi un indirizzo email completo, per esempio nome@example.org.")).isVisible()
        assertThat(page.addressField()).hasValue("nome@")
        assertThat(page).hasURL(e2e.url("/maestri/invita"))
        page.settled()
        page.navigate(e2e.url("/maestri/invita"))
        assertThat(page).hasURL(e2e.url("/maestri"))
        assertThat(page.locator("h1")).hasText("Maestri")
        page.settled()

        // An invitation, the page comes back with the notice.
        val newGuest = Addresses.unique("e2e-senza-js-nuovo")
        page.addressField().fill(newGuest)
        e2e.touch(page.buttonNamed("Manda l’invito"))
        assertThat(page.locator("main [role=status]")).hasText("Invito mandato.")
        assertThat(page.row(newGuest)).containsText("Scade il")
        assertEquals("Ti hanno invitato come maestro su Talità", Mailbox.waitForMail(newGuest).subject)
        page.settled()

        // Togli, confirmed.
        e2e.press(page.row("Paolo Ferri").buttonNamed("Togli"))
        assertThat(page.locator("h1")).hasText("Togliere Paolo Ferri dai maestri?")
        page.settled()
        e2e.touch(page.buttonNamed("Togli dai maestri"))
        assertThat(page).hasURL(e2e.url("/maestri"))
        assertThat(page.locator("main [role=status]")).hasText("Paolo Ferri non è più tra i maestri.")
        assertThat(page.row("Paolo Ferri")).hasCount(0)
    }

    @E2eTest
    fun `c45 the pages that stand in for the dialogs are accessible, with the same words`(e2e: E2e) {
        val page = e2e.page
        val guest = Addresses.unique("e2e-pagina-conferma")
        e2e.onlyTeachers(others = listOf(Triple(Addresses.unique("e2e-pagina-collega"), "Paolo", "Ferri")))
        e2e.invite(guest)
        e2e.open("/maestri")

        // The form that the button sends without JavaScript, sent here as a whole page (axe needs JavaScript to run).
        page.evaluate("document.querySelector('form[action=\"/maestri/togli/conferma\"]').submit()")

        assertThat(page).hasURL(e2e.url("/maestri/togli/conferma"))
        assertThat(page.locator("h1")).hasText("Toglierti dai maestri?")
        assertThat(page.linkNamed("Annulla")).hasAttribute("href", "/maestri")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Convivenza")
        assertAccessible(page, "page that stands in for the dialog Togli (${e2e.profile.label})")
        assertTrue(page.noHorizontalScroll())
        e2e.touch(page.linkNamed("Annulla"))
        assertThat(page).hasURL(e2e.url("/maestri"))

        page.evaluate("document.querySelector('form[action=\"/maestri/inviti/ritira/conferma\"]').submit()")

        assertThat(page.locator("h1")).hasText("Ritirare l’invito?")
        assertThat(page.locator("main")).containsText("L’invito a $guest non varrà più. La persona potrà entrare in Talità, ma non come maestro.")
        assertAccessible(page, "page that stands in for the dialog Ritira (${e2e.profile.label})")
    }

    // ---- The name in the profile (criterion 47) ------------------------------------------------------------------------------

    @E2eTest
    fun `c47 a teacher corrects the name in the profile, with the errors of the invitation, and the initials follow`(e2e: E2e) {
        val page = e2e.page
        e2e.onlyTeachers()
        e2e.open("/profilo")
        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Il tuo accesso", "Il tuo nome", "I tuoi dati"))
        val first = page.getByLabel("Nome", Page.GetByLabelOptions().setExact(true))
        val last = page.getByLabel("Cognome")
        assertThat(first).hasValue("Elisa")
        assertThat(last).hasValue("Conti")
        assertEquals("given-name", first.attr("autocomplete"))
        assertEquals("family-name", last.attr("autocomplete"))
        assertThat(page.linkNamed("Profilo")).containsText("EC")
        assertAccessible(page, "profile of a teacher (${e2e.profile.label})")

        first.fill("")
        last.fill(" ")
        e2e.touch(page.buttonNamed("Salva il nome"))

        assertThat(page.locator("main [role=alert]")).containsText("Controlla i 2 campi segnati in rosso.")
        assertThat(page.getByText("Scrivi il nome.")).isVisible()
        assertThat(page.getByText("Scrivi il cognome.")).isVisible()
        page.focusIs("document.activeElement.id === 'nome'")
        assertThat(page).hasURL(e2e.url("/profilo"))
        assertEquals("Elisa", e2e.dsl().select(TEACHER.FIRST_NAME).from(TEACHER).fetchAny(TEACHER.FIRST_NAME))
        assertAccessible(page, "profile with two errors (${e2e.profile.label})")

        first.fill("Niccolò")
        last.fill("  D’Angiò ")
        e2e.touch(page.buttonNamed("Salva il nome"))

        assertThat(page.locator("main [role=status]")).hasText("Nome salvato.")
        assertThat(page).hasURL(e2e.url("/profilo"))
        assertThat(page.getByLabel("Nome", Page.GetByLabelOptions().setExact(true))).hasValue("Niccolò")
        assertThat(page.getByLabel("Cognome")).hasValue("D’Angiò")
        assertThat(page.linkNamed("Profilo")).containsText("ND")
        assertAccessible(page, "profile after saving (${e2e.profile.label})")

        e2e.open("/maestri")
        assertThat(page.row("Niccolò D’Angiò")).containsText("Sei tu")
    }

    @E2eTest(teacher = false)
    fun `c47 a parent has no card for the name in the profile`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")

        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Il tuo accesso", "I tuoi dati"))
        assertThat(page.getByText("Il tuo nome")).hasCount(0)
        assertThat(page.getByLabel("Cognome")).hasCount(0)
    }
}
