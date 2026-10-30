package talita.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.EmailAddress
import talita.account.LinkOutcome
import talita.account.LoginLinks
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.REMINDER_BATCH
import talita.mail.MailKind
import talita.mail.MailQuota
import talita.mail.Mailbox
import talita.reminder.ReminderType
import talita.reminder.Reminders
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reminders by email in a real browser, on a phone and on a desktop, with axe and at 320 px, with the mails read from Mailpit (TALITA-13, criterion 15): a teacher sends «in forse» from the
 * card with its dialog, and the mail is the text of the design; a parent asks for the avviso in «Iscrizioni» and in the profile, stops from the page of the mail (nobody inside, opening it
 * changes nothing) and takes the reminders back from the profile; the day's quota finishes and a link of access still leaves (and the other way round); and without JavaScript. Names,
 * addresses and dates are synthetic.
 */
@E2eApplication
class ReminderE2eTest {

    /** The person of the test is a parent with Luca (confirmed) and Anna (in forse) at the open gathering; [extra] other accounts have one child in forse. */
    private data class World(val gathering: OpenGathering, val parent: String, val parentId: Long, val others: List<Pair<Long, String>>)

    private fun E2e.world(extra: Int = 2): World {
        application.getBean(MailQuota::class.java).release(MailKind.REMINDER, 1000)
        val gathering = openGathering()
        val parent = checkNotNull(signedInAs) { "the person of the test is inside" }
        val parentId = accountId(parent)
        rows().register(gathering.id, parentId, "Luca", "Rossi", email = parent)
        rows().register(gathering.id, parentId, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.maybe, email = parent)
        val others = (1..extra).map { index ->
            val email = Addresses.unique("e2e-promemoria-$index")
            val id = dsl().insertInto(ACCOUNT).set(ACCOUNT.EMAIL, email).set(ACCOUNT.CREATED_ON, today()).returningResult(ACCOUNT.ID).fetchSingle().value1()!!
            rows().register(gathering.id, id, "Bimbo$index", "Prova", LocalDate.of(2018, 3, 3), status = RegistrationStatus.maybe, email = email)
            id to email
        }
        return World(gathering, parent, parentId, others)
    }

    private fun Page.visit(e2e: E2e, path: String) {
        navigate(e2e.url(path))
        settled()
    }

    private fun E2e.consentOf(id: Long): OffsetDateTime? = dsl().select(ACCOUNT.NOTICE_CONSENT_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.NOTICE_CONSENT_AT)

    private fun E2e.stopOf(id: Long): OffsetDateTime? = dsl().select(ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.REMINDERS_OFF_AT)

    /** At 320 px nothing scrolls sideways, then the size of the profile again. */
    private fun E2e.narrow(page: Page, what: String) {
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "$what scrolls sideways at 320 px")
        page.setViewportSize(if (profile.isPhone) 360 else 1280, 800)
    }

    // ---- A teacher sends «in forse» (criteria 2, 4, 5, 8) ----------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c02 c04 c05 c08 a teacher sees the card, sends in forse from the dialog, reads the answer, and the mail has the text of the design`(e2e: E2e) {
        val w = e2e.world()
        val teacher = e2e.newPage(teacher = true)

        teacher.visit(e2e, "/convivenza/${w.gathering.id}")

        val card = teacher.locator("#promemoria")
        assertThat(card.locator("#promemoria-t")).hasText("Promemoria")
        assertThat(card).containsText("Due email che mandi tu, con un tocco. Il testo è fisso: non ci sono nomi né dati dei bambini, e in ogni mail c’è il modo per smettere.")
        assertThat(card.locator("#promemoria-maybe")).containsText("Iscrizioni in forse")
        assertThat(card.locator("#promemoria-maybe")).containsText("Per chi ha un’iscrizione in forse: riceve un invito a confermarla.")
        assertThat(card.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*3 persone.*Ultimo invio\\s*Mai.*", Pattern.DOTALL))
        assertThat(card.locator("#promemoria-notice")).containsText("Avviso per le nuove iscrizioni")
        assertThat(card.locator("#promemoria-notice")).containsText("Nessuno da avvisare: nessun genitore ha ancora chiesto l’avviso.")
        assertThat(card).not().containsText("Luca")
        assertThat(card).not().containsText(w.parent)
        assertTrue(teacher.noHorizontalScroll())
        assertAccessible(teacher, "the card of the reminders (${e2e.profile.label})")
        e2e.narrow(teacher, "the detail with the reminders")

        // The dialog says to how many, to whom, what they get, and that a mail that has left cannot be taken back; the first focus is on «Annulla».
        e2e.press(card.linkNamed("Manda il promemoria a chi ha un’iscrizione in forse"))
        teacher.waitForSettledDialog()
        assertThat(teacher.openDialog()).containsText("Mandare il promemoria?")
        assertThat(teacher.openDialog()).containsText("Mandiamo una mail a 3 persone: chi ha un’iscrizione in forse. Con la mail riceveranno un invito a confermare l’iscrizione, il titolo e le date della convivenza e il modo per smettere.")
        assertThat(teacher.openDialog()).containsText("Una mail partita non si ritira, e non potrai mandare un altro promemoria di questo tipo per 7 giorni.")
        assertThat(teacher.openDialog()).not().containsText("ritirarla")
        teacher.focusIs("document.activeElement.textContent.trim() === 'Annulla'")
        assertAccessible(teacher, "the dialog that sends (${e2e.profile.label})")

        // «Annulla» sends nothing.
        e2e.touch(teacher.openDialog().buttonNamed("Annulla"))
        assertEquals(0, e2e.dsl().fetchCount(REMINDER_BATCH))

        // The tap: the card is replaced, with the notice, the last send and the wait; the rest of the page stays.
        e2e.press(card.linkNamed("Manda il promemoria a chi ha un’iscrizione in forse"))
        teacher.waitForSettledDialog()
        e2e.touch(teacher.openDialog().buttonNamed("Manda il promemoria"))
        assertThat(card.locator("[role=status]")).containsText("Promemoria mandato a 3 persone.")
        teacher.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertThat(card.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Ultimo invio\\s*\\d+ \\w+ \\d{4} alle \\d\\d:\\d\\d · a 3 persone.*Potrai rimandarlo dal \\d+ \\w+ \\d{4} alle \\d\\d:\\d\\d\\..*", Pattern.DOTALL))
        assertEquals(0, card.locator("#promemoria-maybe").linkNamed("Manda il promemoria a chi ha un’iscrizione in forse").count(), "no button during the wait")
        assertThat(teacher.locator("h1")).hasText("Convivenza di settembre 2027")
        assertAccessible(teacher, "the card after the send (${e2e.profile.label})")
        e2e.narrow(teacher, "the card after the send")

        // The register: one batch, of the kind in forse, with the numbers.
        val batch = e2e.dsl().selectFrom(REMINDER_BATCH).fetchSingle()
        assertEquals(3, batch.recipients?.toInt())
        assertEquals(0, batch.failed?.toInt())

        // The mail, read from Mailpit: the subject and the text of the design with the decision of 24 October, no names, the link to stop and its header.
        val mail = Mailbox.waitForMail(w.parent)
        assertEquals("Iscrizione in forse alla Convivenza di settembre 2027", mail.subject)
        assertTrue(mail.text.contains("c’è almeno un’iscrizione in forse alla Convivenza di settembre 2027 ("), mail.text)
        assertTrue(mail.text.contains("Conferma l’iscrizione: entra in Talità, apri la pagina «Iscrizioni» e cambia lo stato in «Confermata»."))
        assertTrue(mail.text.contains("Ci penso ancora un po’: non devi fare niente. L’iscrizione resta in forse finché le iscrizioni sono aperte."))
        assertTrue(mail.text.contains("${TestcontainersConfiguration.PUBLIC_URL}/iscrizioni"))
        assertFalse(mail.text.lowercase().contains("ritira"), "no invitation to withdraw")
        for (name in listOf("Luca", "Anna", "Rossi")) assertFalse(mail.text.contains(name), "the mail has the name $name")
        val stop = Regex("""https?://\S+/promemoria/smetti\?codice=[A-Za-z0-9_-]{16}""").find(mail.text)?.value
        assertNotNull(stop, mail.text)
        assertEquals(listOf("<$stop>"), mail.headers["list-unsubscribe"])
        for ((_, email) in w.others) assertEquals(1, Mailbox.waitForMail(email).let { Mailbox.sentTo(email).size })
    }

    @E2eTest(teacher = false)
    fun `c05 a teacher who opens the page again during the wait finds the last send and no button, and one who has no JavaScript sends from the page of confirmation`(e2e: E2e) {
        val w = e2e.world(extra = 0)
        val teacher = e2e.newPage(teacher = true, customize = { setJavaScriptEnabled(false) })

        teacher.visit(e2e, "/convivenza/${w.gathering.id}")
        e2e.touch(teacher.locator("#promemoria").linkNamed("Manda il promemoria a chi ha un’iscrizione in forse"))
        teacher.waitForURL(e2e.url("/convivenza/${w.gathering.id}/promemoria?tipo=maybe"))
        // With JavaScript off Playwright cannot tell that the page is still entering (160 ms): it is let to finish.
        teacher.settled()
        assertThat(teacher.locator("h1")).hasText("Mandare il promemoria?")
        assertThat(teacher.locator("main")).containsText("Mandiamo una mail a 1 persona: chi ha un’iscrizione in forse.")
        assertEquals(0, e2e.dsl().fetchCount(REMINDER_BATCH), "opening the page of confirmation sends nothing")
        e2e.touch(teacher.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, Page.GetByRoleOptions().setName("Manda il promemoria")))
        teacher.waitForURL(e2e.url("/convivenza/${w.gathering.id}"))
        teacher.settled()
        assertThat(teacher.locator("[role=status]").first()).containsText("Promemoria mandato a 1 persona.")
        assertEquals(1, Mailbox.waitForMail(w.parent).let { Mailbox.sentTo(w.parent).size })

        // The notice is shown once; the card says when the last one left and from when the next can go.
        teacher.reload()
        assertEquals(0, teacher.getByText("Promemoria mandato a 1 persona.").count(), "the notice is shown once")
        assertThat(teacher.locator("#promemoria-maybe")).containsText("Potrai rimandarlo dal ")
        assertEquals(0, teacher.locator("#promemoria-maybe").linkNamed("Manda il promemoria a chi ha un’iscrizione in forse").count())
        // A request made again by hand during the wait sends nothing.
        val again = e2e.application.getBean(Reminders::class.java).send(w.gathering.id, ReminderType.MAYBE)
        assertTrue(again is talita.reminder.SendResult.TooSoon, "$again")
        assertEquals(1, Mailbox.sentTo(w.parent).size)
    }

    // ---- The avviso and the stop for a parent (criteria 3, 9, 10) ----------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c03 the box of the avviso in Iscrizioni and in the profile is not ticked, is saved with a button, and says since when`(e2e: E2e) {
        val w = e2e.world(extra = 0)
        val page = e2e.page

        e2e.open("/iscrizioni")
        page.settled()
        val card = page.locator("#avviso-card")
        assertThat(card.locator("h2")).hasText("Avviso per la prossima convivenza")
        val box = card.getByLabel("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza")
        assertThat(box).not().isChecked()
        assertThat(card).containsText("Quando le iscrizioni di una nuova convivenza sono aperte, i maestri possono mandarti una mail con il link per iscrivere i bambini. Nella mail non ci sono i nomi dei bambini. Puoi smettere quando vuoi, da qui o dalla mail.")
        assertTrue(card.locator("label").first().box().height >= 47.5, "the row of the box is a target of 48 px at least")
        assertTrue(card.linkNamed("Gestisci le email").isVisible)
        assertAccessible(page, "the avviso in Iscrizioni (${e2e.profile.label})")
        e2e.narrow(page, "Iscrizioni with the avviso")

        // Ticking is not saving: the consent is a gesture, with a button.
        e2e.touch(box)
        assertNull(e2e.consentOf(w.parentId), "ticking the box does not save it")
        e2e.press(card.buttonNamed("Salva la scelta"))
        assertThat(card.locator("[role=status]")).containsText("Scelta salvata. Ti avviseremo quando i maestri apriranno le iscrizioni di una nuova convivenza.")
        page.focusIs("document.activeElement.getAttribute('role') === 'status'")
        assertThat(card).containsText("Hai chiesto l’avviso il ")
        assertNotNull(e2e.consentOf(w.parentId), "the date and the hour are saved")
        assertAccessible(page, "the avviso saved (${e2e.profile.label})")

        // The profile has the same card, complete, between the access and the data, with the box ticked as it was saved.
        e2e.open("/profilo")
        page.settled()
        val email = page.locator("#email")
        assertThat(email.locator("h2")).hasText("Email da Talità")
        assertThat(email).containsText("Oltre ai link per entrare, Talità ti scrive solo per ricordarti di confermare un’iscrizione in forse e, se lo chiedi, per avvisarti quando si aprono le iscrizioni di una nuova convivenza.")
        assertThat(email.getByLabel("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza")).isChecked()
        assertThat(email).containsText("Non voglio più promemoria")
        assertThat(email).containsText("Non riceverai né i promemoria né l’avviso, e la tua richiesta di avviso si cancella. I link per entrare in Talità ti arrivano sempre.")
        assertAccessible(page, "the profile with the card of the email (${e2e.profile.label})")
        e2e.narrow(page, "the profile with the card of the email")

        // Unticked and saved: the consent is gone.
        e2e.touch(email.getByLabel("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza"))
        e2e.press(email.buttonNamed("Salva la scelta"))
        assertThat(email.locator("[role=status]")).containsText("Scelta salvata. Non ti avviseremo.")
        assertNull(e2e.consentOf(w.parentId))
        assertThat(email).not().containsText("Hai chiesto l’avviso")
    }

    @E2eTest(teacher = false)
    fun `c09 c10 the link of the mail opens a page that changes nothing, the tap stops the reminders, and the profile takes them back`(e2e: E2e) {
        val w = e2e.world(extra = 0)
        val page = e2e.page
        // The parent has asked for the avviso, and a reminder in forse goes to them (the same code, the same mail, as the teachers' button).
        e2e.dsl().update(ACCOUNT).set(ACCOUNT.NOTICE_CONSENT_AT, OffsetDateTime.now()).where(ACCOUNT.ID.eq(w.parentId)).execute()
        e2e.application.getBean(Reminders::class.java).send(w.gathering.id, ReminderType.MAYBE)
        val mail = Mailbox.waitForMail(w.parent)
        val link = checkNotNull(Regex("""https?://\S+/promemoria/smetti\?codice=[A-Za-z0-9_-]{16}""").find(mail.text)?.value)
        val path = link.removePrefix(TestcontainersConfiguration.PUBLIC_URL)

        // Nobody inside, as whoever opens the mail on another phone: the page asks for a tap, and opening it changes nothing.
        val stranger = e2e.newPage(signedIn = false)
        stranger.visit(e2e, path)
        assertThat(stranger).hasTitle("Promemoria di Talità · Talità")
        assertThat(stranger.locator("h1")).hasText("Promemoria di Talità")
        assertThat(stranger.locator("main")).containsText("Ti scriviamo solo per ricordarti di confermare un’iscrizione in forse o, se l’hai chiesto, per avvisarti quando si aprono le iscrizioni di una nuova convivenza.")
        assertThat(stranger.locator("main")).containsText("Hai aperto questa pagina per sbaglio? Chiudila: finché non tocchi il bottone, non cambia niente.")
        assertThat(stranger.locator("main")).not().containsText(w.parent)
        assertEquals(0, stranger.locator("nav").count(), "no navigation: nobody is inside")
        assertTrue(stranger.noHorizontalScroll())
        assertAccessible(stranger, "the page for stopping (${e2e.profile.label})")
        e2e.narrow(stranger, "the page for stopping")
        stranger.reload()
        assertNull(e2e.stopOf(w.parentId), "opening the page twice changed nothing")
        assertNotNull(e2e.consentOf(w.parentId))

        // The tap.
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")
        assertThat(stranger).hasTitle("Promemoria fermati · Talità")
        assertThat(stranger.locator("main")).containsText("Da ora Talità non ti manda più promemoria né avvisi. I link per entrare ti arrivano comunque, quando li chiedi.")
        assertThat(stranger.locator("main")).containsText("Se cambi idea, puoi riprendere i promemoria dal tuo profilo.")
        assertThat(stranger.locator("main")).not().containsText(w.parent)
        assertAccessible(stranger, "the page after the tap (${e2e.profile.label})")
        assertNotNull(e2e.stopOf(w.parentId))
        assertNull(e2e.consentOf(w.parentId), "the avviso goes with the stop")

        // The parent finds it in the profile, and takes the reminders back with a tap. The avviso has to be asked for again.
        e2e.open("/profilo")
        page.settled()
        val email = page.locator("#email")
        assertThat(email).containsText("Hai smesso di ricevere i promemoria il ")
        assertThat(email).containsText("I link per entrare in Talità ti arrivano comunque.")
        assertThat(email).containsText("Per riprenderli basta un tocco: poi potrai chiedere di nuovo l’avviso.")
        assertEquals(0, email.getByLabel("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza").count(), "no box while the reminders are stopped")
        assertAccessible(page, "the profile of who stopped (${e2e.profile.label})")
        e2e.press(email.buttonNamed("Riprendi i promemoria"))
        assertThat(email.locator("[role=status]")).containsText("Da ora ricevi di nuovo i promemoria.")
        assertNull(e2e.stopOf(w.parentId))
        assertThat(email.getByLabel("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza")).not().isChecked()

        // The stop from the profile, with the box ticked: the avviso is taken away too.
        e2e.touch(email.getByLabel("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza"))
        e2e.press(email.buttonNamed("Salva la scelta"))
        assertThat(email.locator("[role=status]")).containsText("Scelta salvata. Ti avviseremo")
        assertNotNull(e2e.consentOf(w.parentId))
        e2e.press(email.buttonNamed("Non mandarmi più promemoria"))
        assertThat(email.locator("[role=status]")).containsText("Non ti mandiamo più promemoria. La tua richiesta di avviso è stata cancellata.")
        assertNotNull(e2e.stopOf(w.parentId))
        assertNull(e2e.consentOf(w.parentId))

        // The login link still comes to who stopped: it is not a reminder.
        assertEquals(LinkOutcome.SENT, e2e.application.getBean(LoginLinks::class.java).request(EmailAddress.parse(w.parent)!!))
        e2e.page.waitUntilMail { Mailbox.sentTo(w.parent).any { it.subject.contains("link per entrare") } }
    }

    /** Waits (up to 10 seconds) until [condition] holds: the mail leaves when the request is over, and Mailpit shows it a moment later. */
    private fun Page.waitUntilMail(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "the mail did not arrive in 10 seconds" }
            waitForTimeout(100.0)
        }
    }

    // ---- The quota of the day (criterion 7) ---------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c07 when the places of the day are fewer than the people the page says how many can go, and with the places of the reminders used a login link still leaves`(e2e: E2e) {
        val w = e2e.world()
        val quota = e2e.application.getBean(MailQuota::class.java)
        val taken = quota.remaining(MailKind.REMINDER) - 2
        assertTrue(quota.tryAcquire(MailKind.REMINDER, taken))
        try {
            val teacher = e2e.newPage(teacher = true)
            teacher.visit(e2e, "/convivenza/${w.gathering.id}")
            val card = teacher.locator("#promemoria")
            e2e.press(card.linkNamed("Manda il promemoria a chi ha un’iscrizione in forse"))
            teacher.waitForSettledDialog()
            e2e.touch(teacher.openDialog().buttonNamed("Manda il promemoria"))

            assertThat(card.locator("[role=alert]")).containsText("Oggi si possono mandare ancora 2 promemoria, ma questo ne richiede 3. Riprova domani.")
            assertEquals(0, e2e.dsl().fetchCount(REMINDER_BATCH), "nothing was sent")
            assertTrue(Mailbox.sentTo(w.parent).isEmpty())
            assertAccessible(teacher, "the card with the quota notice (${e2e.profile.label})")
            e2e.narrow(teacher, "the card with the quota notice")

            // All the places used: the words for «finished». A link of access leaves anyway.
            assertTrue(quota.tryAcquire(MailKind.REMINDER, 2))
            e2e.press(card.linkNamed("Manda il promemoria a chi ha un’iscrizione in forse"))
            teacher.waitForSettledDialog()
            e2e.touch(teacher.openDialog().buttonNamed("Manda il promemoria"))
            assertThat(card.locator("[role=alert]")).containsText("Oggi Talità ha già mandato tutti i promemoria che può mandare. Riprova domani.")

            val stranger = e2e.newPage(signedIn = false)
            e2e.enterWithTheMail(Addresses.unique("e2e-quota-link"), stranger)
            assertThat(stranger.locator("h1")).hasText("Ti diamo il benvenuto")
        } finally {
            quota.release(MailKind.REMINDER, 1000)
        }
    }

    @E2eTest(teacher = false)
    fun `c07 with the places of the links used a reminder still leaves`(e2e: E2e) {
        val w = e2e.world(extra = 0)
        val quota = e2e.application.getBean(MailQuota::class.java)
        val taken = quota.remaining(MailKind.ACCESS)
        assertTrue(quota.tryAcquire(MailKind.ACCESS, taken))
        try {
            val teacher = e2e.newPage(teacher = true)
            teacher.visit(e2e, "/convivenza/${w.gathering.id}")
            val card = teacher.locator("#promemoria")
            e2e.press(card.linkNamed("Manda il promemoria a chi ha un’iscrizione in forse"))
            teacher.waitForSettledDialog()
            e2e.touch(teacher.openDialog().buttonNamed("Manda il promemoria"))

            assertThat(card.locator("[role=status]")).containsText("Promemoria mandato a 1 persona.")
            assertEquals(1, Mailbox.waitForMail(w.parent).let { Mailbox.sentTo(w.parent).size })
        } finally {
            quota.release(MailKind.ACCESS, taken)
        }
    }

    // ---- Who cannot (criterion 11) ---------------------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c11 a parent who opens the address of the reminders of the teachers is told that the page is not theirs, and nothing is sent`(e2e: E2e) {
        val w = e2e.world(extra = 0)

        e2e.open("/convivenza/${w.gathering.id}/promemoria?tipo=maybe")

        assertThat(e2e.page.locator("h1")).hasText("Non puoi vedere questa pagina")
        assertAccessible(e2e.page, "the page of 403 (${e2e.profile.label})")
        assertEquals(0, e2e.dsl().fetchCount(REMINDER_BATCH))
        assertTrue(Mailbox.sentTo(w.parent).isEmpty())
    }
}
