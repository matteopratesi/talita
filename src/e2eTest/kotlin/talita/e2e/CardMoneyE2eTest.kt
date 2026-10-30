package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.checkin.CheckinFormat
import talita.checkin.PaymentAmount
import talita.jooq.tables.references.CHECKIN
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.PAYMENT
import talita.registration.Notes
import talita.registration.RegistrationFormat
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The money, the telephone and the notes on health in the card of a child, on a real browser, on a phone and on a desktop, with axe (criteria 10-13, 16-18, 21): the
 * payment that is written and registered with a tap, the error under the field, the same form sent twice, the dialog that takes a payment away, the number that can
 * be called, and the notes that are shown only in the answer to the touch that wrote their reading. Names, amounts and notes are synthetic.
 */
@E2eApplication
class CardMoneyE2eTest {

    private data class Kids(val anna: Long, val luca: Long)

    /** Anna comes all the days (60 euros, with notes on health), Luca the day of today (25) and tomorrow for lunch (10). They have the same parent. */
    private fun E2e.kids(w: ArrivalsWorld): Kids {
        val parent = someParent()
        val rows = rows()
        return Kids(
            anna = rows.register(w.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9), notes = Notes("Arachidi", null, "Antistaminico nello zaino.")),
            luca = rows.register(w.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), days = mapOf(w.today to "full", w.tomorrow to "lunch")),
        )
    }

    private fun euro(cents: Int): String = RegistrationFormat.money(cents)

    /** What a person reads in the block, on one line, with the non-breaking spaces as plain ones. */
    private fun Locator.said(): String = innerText().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    private fun said(cents: Int): String = euro(cents).replace(' ', ' ')

    private fun E2e.payments(registration: Long): Int = dsl().fetchCount(PAYMENT, PAYMENT.REGISTRATION_ID.eq(registration))

    private fun E2e.readings(registration: Long): Int = dsl().fetchCount(HEALTH_NOTE_ACCESS, HEALTH_NOTE_ACCESS.REGISTRATION_ID.eq(registration))

    // ---- The money (criteria 10, 11, 12) -----------------------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c10 c11 c12 the card registers a payment with a tap, with the missing amount already written, the error under the field and the same form sent twice is one payment`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val kids = e2e.kids(w)
        e2e.open("/arrivi/${kids.anna}")

        assertThat(page.locator("main h2:visible")).hasText(arrayOf("Giorni", "Camera", "Recapito del genitore", "Segnalazioni", "Quota e pagamenti", "Manleva", "Iscrizione", "Altri figli dello stesso genitore"))
        val money = page.locator("#pagamenti")
        assertTrue(money.said().contains("Dovuta ${said(6000)} Pagata Niente Saldo Da pagare ${said(6000)}"), money.said())
        val field = page.locator("#importo")
        assertThat(field).hasValue("60")
        assertThat(field).hasAttribute("inputmode", "decimal")
        assertThat(field).hasAttribute("enterkeyhint", "done")
        assertThat(page.getByLabel("Importo")).isVisible()
        assertTrue(page.noHorizontalScroll())
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "card with the form of a payment (${e2e.profile.label})")

        field.fill("25")
        e2e.press(money.buttonNamed("Registra il pagamento"))

        val notice = page.locator("#scheda [role=status]").first()
        assertThat(notice).hasText("Pagamento registrato.")
        page.focusIs("document.activeElement.getAttribute('role') === 'status' && document.activeElement.textContent.includes('Pagamento registrato.')")
        assertTrue(money.said().contains("Dovuta ${said(6000)} Pagata ${said(2500)} Saldo Da pagare ${said(3500)}"), money.said())
        assertTrue(money.said().contains("${said(2500)} Giulia Serra · "), money.said())
        assertThat(page.locator("#importo")).hasValue("35")
        assertEquals(1, e2e.payments(kids.anna))
        assertEquals(0, e2e.payments(kids.luca), "the other child of the family is not touched")
        assertAccessible(page, "card after a payment (${e2e.profile.label})")

        // A wrong amount: the error is under the field, the field says it is wrong and still has what was written, and the focus is on it.
        page.locator("#importo").fill("12,345")
        e2e.press(money.buttonNamed("Registra il pagamento"))
        assertThat(page.locator("#importo-errore")).containsText(PaymentAmount.FORMAT)
        assertThat(page.locator("#importo")).hasAttribute("aria-invalid", "true")
        assertThat(page.locator("#importo")).hasValue("12,345")
        page.focusIs("document.activeElement.id === 'importo'")
        assertEquals(1, e2e.payments(kids.anna))
        assertEquals(0, page.messages().count(), "the error of the field is the answer, not a failure")
        assertAccessible(page, "card with a wrong amount (${e2e.profile.label})")

        // The same form sent twice at the same moment (a double tap): one payment, and the answer is the confirmation.
        page.locator("#importo").fill("10")
        page.evaluate("() => { const form = document.querySelector('#pagamenti form[action\$=\"/pagamenti\"]'); form.requestSubmit(); form.requestSubmit(); }")
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Pagamento registrato.")
        assertTrue(money.said().contains("Pagata ${said(3500)} Saldo Da pagare ${said(2500)}"), money.said())
        assertEquals(2, e2e.payments(kids.anna))
    }

    @E2eTest
    fun `t6 c21 without a network the payment is not registered, the form stays as it was written, and the next tap works once`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val kids = e2e.kids(w)
        e2e.open("/arrivi/${kids.anna}")
        val money = page.locator("#pagamenti")
        page.locator("#importo").fill("18,50")

        e2e.browserContext.setOffline(true)
        e2e.press(money.buttonNamed("Registra il pagamento"))

        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(0, page.messages().locator("button:has-text('Riprova')").count(), "a touch is not repeated for the person")
        assertThat(page.locator("#importo")).hasValue("18,50")
        assertThat(money.buttonNamed("Registra il pagamento")).isEnabled()
        assertEquals(0, e2e.payments(kids.anna))
        assertAccessible(page, "card of a payment without a network (${e2e.profile.label})")

        e2e.browserContext.setOffline(false)
        e2e.press(money.buttonNamed("Registra il pagamento"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Pagamento registrato.")
        assertEquals(1, e2e.payments(kids.anna))
        assertTrue(money.said().contains("Pagata ${said(1850)}"), money.said())
    }

    @E2eTest
    fun `t6 c10 the balance is said in words, Da pagare, Pagata, Pagato in più and Gratuita, and a withdrawn registration owes nothing and keeps its payments`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val rows = e2e.rows()
        val luca = rows.register(w.id, e2e.someParent(), "Luca", "Rossi", LocalDate.of(2016, 4, 4))
        val anna = rows.register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        val chiara = rows.register(w.id, e2e.someParent(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), status = talita.jooq.enums.RegistrationStatus.withdrawn)
        val sofia = rows.register(w.id, e2e.someParent(), "Sofia", "Conti", LocalDate.of(2017, 7, 7), status = talita.jooq.enums.RegistrationStatus.withdrawn)
        val money = page.locator("#pagamenti")

        fun balance(id: Long): String {
            e2e.open("/arrivi/$id")
            return money.said()
        }
        // 60 euros due: nothing paid, part paid, all paid, ten more.
        assertTrue(balance(luca).contains("Dovuta ${said(6000)} Pagata Niente Saldo Da pagare ${said(6000)}"), money.said())
        e2e.payment(luca, 3500, w.martaId)
        e2e.payment(luca, 2500, w.paoloId)
        assertTrue(balance(luca).contains("Dovuta ${said(6000)} Pagata ${said(6000)} Saldo Pagata Pagamenti"), money.said())
        e2e.payment(luca, 1000, w.paoloId)
        assertTrue(balance(luca).contains("Pagata ${said(7000)} Saldo Pagato ${said(1000)} in più"), money.said())
        // Withdrawn: nothing is due, the payments stay, and what was paid is more than what is due.
        assertTrue(balance(sofia).contains("Dovuta Non dovuta Pagata Niente Saldo Niente da pagare"), money.said())
        e2e.payment(chiara, 6000, w.paoloId)
        assertTrue(balance(chiara).contains("Dovuta Non dovuta Pagata ${said(6000)} Saldo Pagato ${said(6000)} in più"), money.said())
        // Nothing due and nothing paid is «Gratuita»: the fees of the gathering are zero.
        e2e.dsl().update(talita.jooq.tables.references.GATHERING).set(talita.jooq.tables.references.GATHERING.FULL_FEE_CENTS, 0).where(talita.jooq.tables.references.GATHERING.ID.eq(w.id)).execute()
        assertTrue(balance(anna).contains("Dovuta Gratuita Pagata Niente Saldo Gratuita"), money.said())
        assertTrue(balance(luca).contains("Dovuta Gratuita Pagata ${said(7000)} Saldo Pagato ${said(7000)} in più"), money.said())
        e2e.open("/arrivi")
        assertEquals(0, page.locator("#riga-$anna").getByText("Da pagare").count(), "a registration that owes nothing has no «Da pagare» in its row")
        assertAccessible(page, "list with free registrations (${e2e.profile.label})")
    }

    // ---- Taking a payment away (criterion 13) ----------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c13 Togli opens a dialog with the first focus on Annulla, Esc and Annulla change nothing, and the verb of the title takes the payment away`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val kids = e2e.kids(w)
        e2e.payment(kids.luca, 2500, w.paoloId)
        e2e.payment(kids.luca, 1000, w.meId)
        e2e.open("/arrivi/${kids.luca}")
        val money = page.locator("#pagamenti")
        assertTrue(money.said().contains("Dovuta ${said(3500)} Pagata ${said(3500)} Saldo Pagata"), money.said())

        e2e.press(money.linkNamed("Togli il pagamento di ${euro(2500)}"))

        val dialog = page.openDialog()
        assertThat(dialog).isVisible()
        assertThat(dialog.locator("h2")).hasText("Togliere il pagamento di ${euro(2500)}?")
        assertTrue(dialog.said().contains("Il pagamento di ${said(2500)} registrato da Paolo Verdi il "), dialog.said())
        assertTrue(dialog.said().contains("sparisce da Luca Rossi. Il saldo e il totale incassato cambiano. Per rimetterlo lo devi registrare di nuovo."), dialog.said())
        page.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        page.waitForSettledDialog()
        assertAccessible(page, "dialog Togli il pagamento (${e2e.profile.label})")

        // Esc closes it and changes nothing; the focus goes back to the button that opened it.
        page.keyboard().press("Escape")
        assertThat(page.openDialog()).hasCount(0)
        assertEquals(2, e2e.payments(kids.luca))
        page.focusIs("document.activeElement.getAttribute('data-apri') !== null && document.activeElement.getAttribute('aria-label').startsWith('Togli il pagamento di')")
        e2e.press(money.linkNamed("Togli il pagamento di ${euro(2500)}"))
        e2e.touch(page.openDialog().buttonNamed("Annulla"))
        assertThat(page.openDialog()).hasCount(0)
        assertEquals(2, e2e.payments(kids.luca))

        e2e.press(money.linkNamed("Togli il pagamento di ${euro(2500)}"))
        e2e.touch(page.openDialog().buttonNamed("Togli il pagamento"))

        assertThat(page.locator("#scheda [role=status]").first()).hasText("Pagamento tolto.")
        assertThat(page.openDialog()).hasCount(0)
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertEquals(1, e2e.payments(kids.luca))
        assertTrue(money.said().contains("Dovuta ${said(3500)} Pagata ${said(1000)} Saldo Da pagare ${said(2500)}"), money.said())
        assertEquals(0, money.linkNamed("Togli il pagamento di ${euro(2500)}").count())
        assertAccessible(page, "card after a payment was taken away (${e2e.profile.label})")
    }

    // ---- The telephone (criterion 16) ----------------------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c16 Chiama has the number to dial, an icon and a target of at least 44 px, and the page does not scroll sideways at 320 px`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val child = e2e.rows().register(w.id, e2e.someParent(), "Anna", "Rossi", LocalDate.of(2019, 1, 9), phone = "+39 333-123.4567")
        e2e.open("/arrivi/$child")

        val section = page.locator("section[aria-labelledby=recapito-titolo]")
        assertThat(section).containsText("+39 333-123.4567")
        val call = section.linkNamed("Chiama +39 333-123.4567")
        assertThat(call).hasAttribute("href", "tel:+393331234567")
        assertThat(call).hasAttribute("hx-boost", "false")
        assertEquals(1, call.locator("svg[aria-hidden=true]").count(), "an icon, only for the eyes")
        val box = call.box()
        assertTrue(box.width >= 44 && box.height >= 44, "the target is ${box.width}x${box.height}")
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "no sideways scrolling at 320 px")
        assertTrue(call.box().width >= 44 && call.box().height >= 44)
        page.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)
        assertAccessible(page, "card with the number to call (${e2e.profile.label})")
    }

    // ---- The notes on health (criteria 17, 21) ----------------------------------------------------------------------------------

    @E2eTest
    fun `t6 c17 the notes are shown only in the answer to the touch that wrote their reading, the focus goes to them, and the next action closes them`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val kids = e2e.kids(w)
        e2e.open("/arrivi/${kids.anna}")
        val section = page.locator("#segnalazioni")
        assertThat(section).containsText("Sono dati sulla salute. Ogni volta che le mostri, resta scritto chi le ha lette e quando.")
        assertFalse(page.content().contains("Arachidi"), "nothing of the notes in the closed card")
        assertAccessible(page, "card with the notes closed (${e2e.profile.label})")

        e2e.press(section.buttonNamed("Mostra le segnalazioni"))

        assertThat(section).containsText("Lettura registrata: Giulia Serra, ")
        assertThat(section).containsText("Allergie")
        assertThat(section).containsText("Arachidi")
        assertThat(section).containsText("Antistaminico nello zaino.")
        assertThat(section).containsText("Consenso dato dal genitore il ")
        page.focusIs("document.activeElement.id === 'segnalazioni'")
        assertEquals(1, e2e.readings(kids.anna))
        assertAccessible(page, "card with the notes open (${e2e.profile.label})")

        // Another action of the card: the notes are closed again, and nothing was read.
        e2e.press(page.buttonNamed("Segna presente Anna Rossi, ${CheckinFormat.longDay(w.today)}"))
        assertThat(page.locator("#scheda [role=status]").first()).hasText("Presenza segnata.")
        assertFalse(page.content().contains("Arachidi"), "closed again")
        assertThat(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni")).isVisible()
        assertEquals(1, e2e.readings(kids.anna))
        assertEquals(1, e2e.dsl().fetchCount(CHECKIN, CHECKIN.REGISTRATION_ID.eq(kids.anna)))

        // A child with nothing written: «Nessuna segnalazione», and the reading is written all the same.
        e2e.open("/arrivi/${kids.luca}")
        e2e.press(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Nessuna segnalazione.")
        assertEquals(1, e2e.readings(kids.luca))
        // «Nascondi le segnalazioni» goes back to the card as it is, and reads nothing.
        e2e.touch(page.locator("#segnalazioni").linkNamed("Nascondi le segnalazioni"))
        assertThat(page.locator("#segnalazioni").buttonNamed("Mostra le segnalazioni")).isVisible()
        assertEquals(1, e2e.readings(kids.luca))
    }

    @E2eTest
    fun `t6 c17 c21 without a network nothing is shown and no reading is written, and when it is back the notes open with their reading`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        val kids = e2e.kids(w)
        e2e.open("/arrivi/${kids.anna}")
        val section = page.locator("#segnalazioni")

        e2e.browserContext.setOffline(true)
        e2e.press(section.buttonNamed("Mostra le segnalazioni"))

        assertThat(page.messages()).containsText("Nessuna connessione")
        assertEquals(0, page.messages().locator("button:has-text('Riprova')").count())
        assertFalse(page.content().contains("Arachidi"), "nothing shown without the reading")
        assertEquals(0, e2e.readings(kids.anna))
        assertThat(section.buttonNamed("Mostra le segnalazioni")).isEnabled()
        assertAccessible(page, "card with the notes asked for without a network (${e2e.profile.label})")

        e2e.browserContext.setOffline(false)
        e2e.press(section.buttonNamed("Mostra le segnalazioni"))
        assertThat(page.locator("#segnalazioni")).containsText("Arachidi")
        assertEquals(1, e2e.readings(kids.anna))
    }
}
