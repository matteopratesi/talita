package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.FormData
import com.microsoft.playwright.options.RequestOptions
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.TestAccounts
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.enums.ReminderKind
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REMINDER_BATCH
import talita.jooq.tables.references.RETENTION_LOG
import talita.mail.MailKind
import talita.mail.MailQuota
import talita.mail.Mailbox
import talita.reminder.ReminderType
import talita.reminder.Reminders
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The QA of TALITA-13 (reminders by email), written from the criteria of the ticket and not from the code: what a person sees and what leaves the application, in a real browser on a
 * phone and on a desktop, with the mails read from Mailpit. It adds to `ReminderE2eTest` the example of the criteria taken as a whole (who receives what, and nobody else), the
 * people who stop and come back, two teachers at the same moment and a double tap, the wait of 168 hours (also across the change of the hour, and at the exact edge), the card
 * when the registrations are not open, the failures of the network, the page for stopping with a code that does not exist and with a session that ended, the account that is
 * deleted, the quota at the edge of 40, and the requests made by hand by a parent or by someone who is not inside. Names, titles, dates and addresses are synthetic.
 */
@E2eApplication
class ReminderQaE2eTest {

    private val rome: ZoneId = ZoneId.of("Europe/Rome")

    private data class Who(val id: Long, val email: String)

    /**
     * The example of the criteria (the description of TALITA-13), made straight in the tables: the gathering that is open and last year's, that is closed.
     * **A** is the person of the test (Luca confirmed, Anna in forse, and the avviso asked: A has registered, so no avviso); **B** has Sara at last year's gathering and has asked for the
     * avviso; **C** is an empty account; **D** is like B without the avviso; **F** withdrew a registration at the open gathering and has asked for the avviso; **S** has one child in forse and has
     * stopped; **G** has asked for the avviso and has no children; Nicolò Ferri is a paper registration in forse (no account, no address). Marta and Paolo are the teachers.
     */
    private class Example(
        val gathering: OpenGathering,
        val previous: Long,
        val a: Who,
        val b: Who,
        val c: Who,
        val d: Who,
        val f: Who,
        val s: Who,
        val g: Who,
        val marta: Who,
        val paolo: Who,
        /** Other accounts with one child in forse, besides A. */
        val others: List<Who>,
    ) {
        /** All the people who are not A or B and must receive nothing of the two sends of the example. */
        val silent: List<Who> get() = listOf(c, d, f, s, g, marta, paolo)
    }

    private fun E2e.clock(): Clock = application.getBean(Clock::class.java)

    private fun E2e.now(): OffsetDateTime = OffsetDateTime.now(clock())

    private fun E2e.account(name: String, notice: OffsetDateTime? = null, stopped: OffsetDateTime? = null): Who {
        val email = Addresses.unique("qa13-$name")
        val id = dsl().insertInto(ACCOUNT).set(ACCOUNT.EMAIL, email).set(ACCOUNT.CREATED_ON, today())
            .set(ACCOUNT.NOTICE_CONSENT_AT, notice).set(ACCOUNT.REMINDERS_OFF_AT, stopped).returningResult(ACCOUNT.ID).fetchSingle().value1()!!
        return Who(id, email)
    }

    private fun E2e.emailOf(id: Long): String = dsl().select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.EMAIL)!!

    private fun E2e.example(status: GatheringStatus = GatheringStatus.`open`, extraInForse: Int = 0): Example {
        application.getBean(MailQuota::class.java).release(MailKind.REMINDER, 1000)
        val gathering = openGathering(status = status)
        val previous = GatheringSupport(dsl(), clock()).make(
            title = "Convivenza dell’anno scorso", start = today().minusDays(400), end = today().minusDays(398), status = GatheringStatus.closed,
            custodians = listOf(gathering.martaId, gathering.paoloId),
        )
        val parent = checkNotNull(signedInAs) { "the person of the test is inside" }
        val a = Who(accountId(parent), parent)
        dsl().update(ACCOUNT).set(ACCOUNT.NOTICE_CONSENT_AT, now()).where(ACCOUNT.ID.eq(a.id)).execute()
        rows().register(gathering.id, a.id, "Luca", "Rossi", email = a.email)
        rows().register(gathering.id, a.id, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.maybe, email = a.email)

        val b = account("b", notice = now().minusDays(30))
        rows().register(previous, b.id, "Sara", "Neri", LocalDate.of(2017, 6, 2), email = b.email)
        val c = account("c")
        val d = account("d")
        rows().register(previous, d.id, "Marco", "Gialli", LocalDate.of(2016, 3, 11), email = d.email)
        val f = account("f", notice = now().minusDays(20))
        rows().register(previous, f.id, "Gina", "Verdi", LocalDate.of(2016, 5, 5), email = f.email)
        rows().register(gathering.id, f.id, "Gino", "Verdi", LocalDate.of(2018, 5, 5), status = RegistrationStatus.withdrawn, email = f.email)
        val s = account("s", stopped = now().minusDays(2))
        rows().register(gathering.id, s.id, "Mia", "Blu", LocalDate.of(2018, 7, 7), status = RegistrationStatus.maybe, email = s.email)
        val g = account("g", notice = now().minusDays(10))
        rows().register(gathering.id, null, "Nicolò", "Ferri", LocalDate.of(2018, 8, 8), status = RegistrationStatus.maybe, enteredBy = gathering.martaId, paperReceived = true)
        val others = (1..extraInForse).map { index ->
            account("altro-$index").also { rows().register(gathering.id, it.id, "Bimbo$index", "Prova", LocalDate.of(2018, 3, 3), status = RegistrationStatus.maybe, email = it.email) }
        }
        return Example(gathering, previous, a, b, c, d, f, s, g, Who(gathering.martaId, emailOf(gathering.martaId)), Who(gathering.paoloId, emailOf(gathering.paoloId)), others)
    }

    private fun Page.visit(e2e: E2e, path: String) {
        navigate(e2e.url(path))
        settled()
    }

    private fun E2e.narrow(page: Page, what: String) {
        page.setViewportSize(320, 800)
        assertTrue(page.noHorizontalScroll(), "$what scrolls sideways at 320 px")
        page.setViewportSize(if (profile.isPhone) 360 else 1280, 800)
    }

    private fun E2e.sendFromTheDialog(page: Page, button: String, confirm: String) {
        press(page.locator("#promemoria").linkNamed(button))
        page.waitForSettledDialog()
        touch(page.openDialog().buttonNamed(confirm))
    }

    private fun stopLinkOf(text: String): String = checkNotNull(Regex("""https?://\S+/promemoria/smetti\?codice=[A-Za-z0-9_-]{16}""").find(text)?.value) { text }

    private fun E2e.pathOf(link: String): String = link.removePrefix(TestcontainersConfiguration.PUBLIC_URL)

    private fun E2e.batches(gathering: Long): Int = dsl().fetchCount(REMINDER_BATCH, REMINDER_BATCH.GATHERING_ID.eq(gathering))

    private fun E2e.insertBatch(gathering: Long, kind: ReminderKind, at: OffsetDateTime, recipients: Int = 1) {
        dsl().insertInto(REMINDER_BATCH).set(REMINDER_BATCH.GATHERING_ID, gathering).set(REMINDER_BATCH.KIND, kind).set(REMINDER_BATCH.SENT_AT, at)
            .set(REMINDER_BATCH.RECIPIENTS, recipients.toShort()).set(REMINDER_BATCH.FAILED, 0).execute()
    }

    /** A moment as the pages write it: «5 aprile 2031 alle 22:30», the hour of Rome. */
    private fun moment(at: OffsetDateTime): String = DateTimeFormatter.ofPattern("d MMMM yyyy 'alle' HH:mm", Locale.ITALIAN).format(at.atZoneSameInstant(rome))

    private fun csrfOf(page: Page): String = page.locator("input[name=_csrf]").first().inputValue()

    private fun E2e.sendByHand(page: Page, gathering: Long, type: String, csrf: String, htmx: Boolean = true) = page.request().post(
        url("/convivenza/$gathering/promemoria"),
        RequestOptions.create().setForm(FormData.create().set("_csrf", csrf).set("tipo", type)).setMaxRedirects(0).also { if (htmx) it.setHeader("HX-Request", "true") },
    )

    // ---- The example, as a whole (criteria 1, 2, 4, 8) ----------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c01 c02 c04 c08 the people of the example are counted, the two sends reach them and nobody else, and the avviso is the text of the design`(e2e: E2e) {
        val x = e2e.example()
        val teacher = e2e.newPage(teacher = true)
        teacher.visit(e2e, "/convivenza/${x.gathering.id}")
        val card = teacher.locator("#promemoria")

        // A (a mail even with Luca confirmed and Anna in forse) is the only one in forse: S has stopped, Nicolò has no address. B is the only avviso: A has registered, F withdrew a registration of this
        // gathering, D did not ask, C is empty, G has no children.
        assertThat(card.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*1 persona(?!e).*", Pattern.DOTALL))
        assertThat(card.locator("#promemoria-notice")).hasText(Pattern.compile(".*Lo riceverebbero\\s*1 persona(?!e).*", Pattern.DOTALL))
        assertThat(card).not().containsText("Rossi")
        assertThat(card).not().containsText("example.org")
        e2e.narrow(teacher, "the card of the example")

        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        assertThat(card.locator("[role=status]")).containsText("Promemoria mandato a 1 persona.")
        assertThat(card.locator("#promemoria-maybe")).containsText("Potrai rimandarlo dal ")

        e2e.press(card.linkNamed("Manda il promemoria a chi ha chiesto l’avviso"))
        teacher.waitForSettledDialog()
        assertThat(teacher.openDialog()).containsText("Mandare l’avviso?")
        assertThat(teacher.openDialog()).containsText(
            "Mandiamo una mail a 1 persona: chi ha già iscritto i figli a una convivenza e ha chiesto un avviso. Con la mail riceveranno il titolo e le date della convivenza, " +
                "il link della pagina informativa e il modo per smettere. Non ci sono nomi né dati dei bambini. Una mail partita non si ritira, e non potrai mandare un altro avviso per 7 giorni.",
        )
        assertAccessible(teacher, "the dialog of the avviso (${e2e.profile.label})")
        e2e.touch(teacher.openDialog().buttonNamed("Manda l’avviso"))
        assertThat(card.locator("#promemoria-notice")).containsText("Potrai rimandarlo dal ")
        assertThat(card.locator("[role=status]")).containsText("Promemoria mandato a 1 persona.")
        assertAccessible(teacher, "the card after the two sends (${e2e.profile.label})")

        // The mails: one for A (in forse) and one for B (avviso), and nothing for the others.
        val toB = Mailbox.waitForMail(x.b.email)
        val toA = Mailbox.waitForMail(x.a.email)
        assertEquals(1, Mailbox.sentTo(x.b.email).size)
        assertEquals(1, Mailbox.sentTo(x.a.email).size, "A has registered, so no avviso, with the avviso asked or not")
        for (person in x.silent) assertTrue(Mailbox.sentTo(person.email).isEmpty(), "${person.email} received a mail")
        for (person in x.silent) assertNull(e2e.codeOf(person.id), "${person.email} was not written to, so no code")
        assertEquals(stopLinkOf(toA.text).substringAfter("codice="), e2e.codeOf(x.a.id), "the link of the mail of A is the code that A has")
        assertEquals("Iscrizione in forse per «Convivenza di settembre 2027»", toA.subject)
        assertEquals("Iscrizioni aperte per «Convivenza di settembre 2027»", toB.subject)
        assertEquals(listOf(x.a.email), toA.to)
        assertEquals(listOf(x.b.email), toB.to)
        assertEquals("Talità", toB.fromName)
        assertEquals("", toB.html, "text only")
        assertEquals(listOf("0"), toB.headers["x-mailjet-trackopen"])
        assertEquals(listOf("0"), toB.headers["x-mailjet-trackclick"])

        // The text of the avviso, as the design writes it (comment 10938), with the title, the dates, the address of the information page and the way to stop.
        val dates = "${x.gathering.friday.dayOfMonth}–${x.gathering.sunday.dayOfMonth} ${x.gathering.friday.month.getDisplayName(TextStyle.FULL, Locale.ITALIAN)} ${x.gathering.friday.year}"
        val codeOfB = e2e.dsl().select(ACCOUNT.REMINDER_CODE).from(ACCOUNT).where(ACCOUNT.ID.eq(x.b.id)).fetchSingle(ACCOUNT.REMINDER_CODE)
        assertNotNull(codeOfB)
        val stop = "${TestcontainersConfiguration.PUBLIC_URL}/promemoria/smetti?codice=$codeOfB"
        val expected = """
            Ciao,

            hai chiesto di ricevere un avviso quando si aprono le iscrizioni di una nuova convivenza dei bambini: ora sono aperte le iscrizioni per «Convivenza di settembre 2027» ($dates).

            Trovi le informazioni e il modo per iscrivere i bambini in questa pagina:

            ${TestcontainersConfiguration.PUBLIC_URL}/info/${x.gathering.code}

            Non vuoi più ricevere queste mail? Apri questa pagina e tocca «Non mandarmi più promemoria»:

            $stop

            Ricevi questa mail perché hai chiesto l’avviso. Per i link di accesso a Talità non cambia niente.

            Questa mail è automatica: non risponderle. Se hai bisogno di aiuto, scrivi a chi organizza la convivenza.

            Talità
        """.trimIndent()
        assertEquals(expected, toB.text.replace("\r\n", "\n").trim())
        assertEquals(listOf("<$stop>"), toB.headers["list-unsubscribe"])

        // «In forse»: confirm or think about it, nothing to withdraw; the same way to stop. Nothing of the children, of the dates of birth, of the telephone, of the place or the fees.
        val text = toA.text
        assertTrue(text.startsWith("Ciao,"), text)
        assertTrue(text.contains("c’è almeno un’iscrizione in forse per «Convivenza di settembre 2027» ($dates)."), text)
        assertTrue(text.contains("Conferma l’iscrizione"), text)
        assertTrue(text.contains("${TestcontainersConfiguration.PUBLIC_URL}/iscrizioni"), text)
        assertTrue(text.contains("Ci penso ancora un po’"), text)
        assertTrue(text.contains("Non mandarmi più promemoria"), text)
        assertTrue(text.contains("Ricevi questa mail perché hai un’iscrizione in forse."), text)
        assertFalse(text.lowercase().contains("ritira"), "no invitation to withdraw")
        assertEquals(listOf("<${stopLinkOf(text)}>"), toA.headers["list-unsubscribe"])
        // (Without the addresses: the code to stop is random, and a word could be in it by chance.)
        val withoutLinks = listOf(text, toB.text).map { it.replace(Regex("""https?://\S+"""), "") }
        for (word in listOf("Luca", "Anna", "Rossi", "Sara", "Neri", "Nicolò", "Ferri", "Gina", "Verdi", "2019", "2017", "333", "Casa per ritiri", "€")) {
            assertFalse(withoutLinks.any { it.contains(word) }, "a mail has «$word»")
        }

        // The register: one row for each kind, with numbers and nothing else; and what the pages of the example said.
        val rows = e2e.dsl().selectFrom(REMINDER_BATCH).where(REMINDER_BATCH.GATHERING_ID.eq(x.gathering.id)).fetch()
        assertEquals(setOf(ReminderKind.maybe, ReminderKind.notice), rows.map { it.kind }.toSet())
        assertTrue(rows.all { it.recipients?.toInt() == 1 && it.failed?.toInt() == 0 })
    }

    // ---- Who stops and who comes back (criteria 9, 10) --------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c09 c10 who stops from the page of the mail leaves the count of the teachers, a code that does not exist has the same page and changes nothing, and who comes back is counted again`(e2e: E2e) {
        val x = e2e.example()
        val reminders = e2e.application.getBean(Reminders::class.java)
        reminders.send(x.gathering.id, ReminderType.MAYBE)
        reminders.send(x.gathering.id, ReminderType.NOTICE)
        val stopOfA = stopLinkOf(Mailbox.waitForMail(x.a.email).text)
        val stopOfB = stopLinkOf(Mailbox.waitForMail(x.b.email).text)
        // The reminders of this example are over for now: the teachers' card is looked at as if nothing had been sent.
        e2e.dsl().deleteFrom(REMINDER_BATCH).execute()
        val teacher = e2e.newPage(teacher = true)

        // Someone who is not inside opens the link of a code that does not exist, and taps: the page of the tap is the same, and no account changes.
        val stranger = e2e.newPage(signedIn = false)
        stranger.visit(e2e, "/promemoria/smetti?codice=AAAAAAAAAAAAAAAA")
        assertThat(stranger.locator("h1")).hasText("Promemoria di Talità")
        assertThat(stranger.locator("main")).not().containsText("example.org")
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")
        val unknown = stranger.locator("main").innerText()
        assertEquals(
            0, e2e.dsl().fetchCount(ACCOUNT, ACCOUNT.ID.`in`(x.a.id, x.b.id, x.c.id, x.d.id, x.f.id, x.g.id).and(ACCOUNT.REMINDERS_OFF_AT.isNotNull)),
            "a code that does not exist stopped nobody",
        )

        // A stops with a tap, without entering: the page is the same as for the code that does not exist.
        stranger.visit(e2e, e2e.pathOf(stopOfA))
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")
        assertEquals(unknown, stranger.locator("main").innerText(), "the same words for a code that exists and one that does not")
        assertNotNull(e2e.dsl().select(ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(x.a.id)).fetchSingle(ACCOUNT.REMINDERS_OFF_AT))
        assertNull(e2e.dsl().select(ACCOUNT.NOTICE_CONSENT_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(x.a.id)).fetchSingle(ACCOUNT.NOTICE_CONSENT_AT), "the avviso goes with the stop")
        // B stops too, from the same page.
        stranger.visit(e2e, e2e.pathOf(stopOfB))
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")

        // The teachers' card: nobody is left in forse (A stopped; S was stopped already) and nobody for the avviso: the zero in words, the reason, and no button.
        teacher.visit(e2e, "/convivenza/${x.gathering.id}")
        val card = teacher.locator("#promemoria")
        assertThat(card.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*Nessuno.*Nessuno da avvisare: non c’è nessuna iscrizione in forse con un indirizzo email\\..*", Pattern.DOTALL))
        assertThat(card.locator("#promemoria-notice")).hasText(Pattern.compile(".*Lo riceverebbero\\s*Nessuno.*Nessuno da avvisare: nessun genitore ha ancora chiesto l’avviso\\..*", Pattern.DOTALL))
        assertEquals(0, card.locator("a, button").count(), "no button with nobody to write to")
        assertAccessible(teacher, "the card with nobody to write to (${e2e.profile.label})")
        e2e.narrow(teacher, "the card with nobody to write to")

        // A comes back from the profile: counted again, with the button. The avviso is not back: it has to be asked for again.
        e2e.open("/profilo")
        e2e.press(e2e.page.locator("#email").buttonNamed("Riprendi i promemoria"))
        assertThat(e2e.page.locator("#email [role=status]")).containsText("Da ora ricevi di nuovo i promemoria.")
        assertNull(e2e.dsl().select(ACCOUNT.NOTICE_CONSENT_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(x.a.id)).fetchSingle(ACCOUNT.NOTICE_CONSENT_AT))
        teacher.reload()
        assertThat(teacher.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*1 persona(?!e).*", Pattern.DOTALL))
        assertThat(teacher.locator("#promemoria-maybe").linkNamed("Manda il promemoria a chi ha un’iscrizione in forse")).isVisible()
        assertThat(teacher.locator("#promemoria-notice")).hasText(Pattern.compile(".*Lo riceverebbero\\s*Nessuno.*", Pattern.DOTALL))
    }

    // ---- The codes to stop are durable before a mail leaves (criteria 4, 9, 7) ------------------------------------------------------------------------

    private fun E2e.codeOf(id: Long): String? = dsl().select(ACCOUNT.REMINDER_CODE).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.REMINDER_CODE)

    private fun E2e.stoppedAt(id: Long): OffsetDateTime? = dsl().select(ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchSingle(ACCOUNT.REMINDERS_OFF_AT)

    /** The mails that [address] has, once there are at least [count] of them (the mails leave before the answer, but Mailpit is another process). */
    private fun mailsOf(address: String, count: Int) = Mailbox.waitForMails(address, count)

    @E2eTest(teacher = false)
    fun `t13 c04 c09 when the register fails after the mails left the teacher is told, the link of each mail still stops its account from the page, and a second send keeps the codes`(e2e: E2e) {
        val x = e2e.example(extraInForse = 1)
        val other = x.others.single()
        val teacher = e2e.newPage(teacher = true)
        teacher.visit(e2e, "/convivenza/${x.gathering.id}")
        val card = teacher.locator("#promemoria")
        assertThat(card.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*2 persone.*", Pattern.DOTALL))
        assertNull(e2e.codeOf(x.a.id), "nobody has a code before the first reminder")

        // The row of the register fails after the mails left (a statement that fails: a connection lost, a timeout). The mails are in the mailboxes; the transaction is rolled back.
        e2e.dsl().execute("create function qa13_forced_failure() returns trigger language plpgsql as 'begin raise exception ''forced by the QA''; end'")
        e2e.dsl().execute("create trigger qa13_forced_failure before insert on reminder_batch for each row execute function qa13_forced_failure()")
        try {
            e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
            assertThat(teacher.messages().filter(Locator.FilterOptions().setHasText("Qualcosa non ha funzionato"))).hasCount(1)
            assertEquals(0, card.locator("[role=status], [role=alert]").count(), "the card did not say that it was sent")
            assertEquals(0, e2e.batches(x.gathering.id), "the register was rolled back: nothing says that the mails went")
        } finally {
            e2e.dsl().execute("drop trigger if exists qa13_forced_failure on reminder_batch")
            e2e.dsl().execute("drop function if exists qa13_forced_failure()")
        }

        // The mails left, and the code of each link is the one of its account (committed before the mails left, not taken away by the rollback).
        val firstToA = mailsOf(x.a.email, 1).single()
        val firstToOther = mailsOf(other.email, 1).single()
        val linkOfA = stopLinkOf(firstToA.text)
        val linkOfOther = stopLinkOf(firstToOther.text)
        val codeOfA = linkOfA.substringAfter("codice=")
        val codeOfOther = linkOfOther.substringAfter("codice=")
        assertNotEquals(codeOfA, codeOfOther)
        assertEquals(codeOfA, e2e.codeOf(x.a.id), "the link of the mail of A is the code that A has")
        assertEquals(codeOfOther, e2e.codeOf(other.id), "the link of the mail of the other is the code that they have")
        assertEquals(listOf("<$linkOfA>"), firstToA.headers["list-unsubscribe"])
        for (person in x.silent) assertNull(e2e.codeOf(person.id), "${person.email} was not written to, so no code")

        // Someone who is not inside opens the link of A and taps: the page says it, and A is really stopped. Nobody else is.
        val stranger = e2e.newPage(signedIn = false)
        stranger.visit(e2e, e2e.pathOf(linkOfA))
        assertThat(stranger.locator("h1")).hasText("Promemoria di Talità")
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")
        assertNotNull(e2e.stoppedAt(x.a.id), "the tap on the link of a mail that left stops the account, as the page says")
        assertNull(e2e.stoppedAt(other.id), "and only that account")
        e2e.narrow(stranger, "the page for stopping")
        assertAccessible(stranger, "the page for stopping after a send that failed (${e2e.profile.label})")

        // The teachers were told nothing about the row: the card still has the button, and (accepted, docs/05) one can send again at once. A stopped; the other has the same code in the second mail.
        teacher.reload()
        teacher.waitForSettledContent()
        assertThat(card.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*1 persona(?!e).*", Pattern.DOTALL))
        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        assertThat(card.locator("[role=status]")).containsText("Promemoria mandato a 1 persona.")
        val toOther = mailsOf(other.email, 2)
        assertEquals(2, toOther.size)
        assertEquals(linkOfOther, stopLinkOf(toOther.last().text), "the second mail has the same link as the first")
        assertEquals(codeOfOther, e2e.codeOf(other.id), "the code did not change")
        assertEquals(1, Mailbox.sentTo(x.a.email).size, "A had stopped: no second mail")
        assertEquals(1, e2e.batches(x.gathering.id), "now the register has its row")
        // The link of the first mail of the other still works after the second send: it is the same.
        stranger.visit(e2e, e2e.pathOf(linkOfOther))
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")
        assertNotNull(e2e.stoppedAt(other.id))
    }

    // ---- Two teachers at the same moment, and a double tap (criterion 5) ----------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c05 two teachers who confirm at the same moment send once, and the other page is told what was done`(e2e: E2e) {
        val x = e2e.example(extraInForse = 3)
        val first = e2e.newPage(teacher = true)
        val second = e2e.newPage(teacher = true)
        for (page in listOf(first, second)) {
            page.visit(e2e, "/convivenza/${x.gathering.id}")
            e2e.press(page.locator("#promemoria").linkNamed("Manda il promemoria a chi ha un’iscrizione in forse"))
            page.waitForSettledDialog()
            assertThat(page.openDialog()).containsText("Mandiamo una mail a 4 persone")
        }

        e2e.touch(first.openDialog().buttonNamed("Manda il promemoria"))
        e2e.touch(second.openDialog().buttonNamed("Manda il promemoria"))

        val texts = listOf(first, second).map { page ->
            val notice = page.locator("#promemoria [role=status]")
            assertThat(notice).hasText(Pattern.compile("Promemoria mandato a 4 persone\\.|Questo promemoria è già stato mandato il .+, a 4 persone\\."))
            notice.innerText().trim()
        }
        assertEquals(1, texts.count { it == "Promemoria mandato a 4 persone." }, "one page did the send: $texts")
        assertEquals(1, texts.count { it.startsWith("Questo promemoria è già stato mandato il ") }, "the other one was told what was done: $texts")
        for (page in listOf(first, second)) {
            assertThat(page.locator("#promemoria-maybe")).containsText("Potrai rimandarlo dal ")
            assertEquals(0, page.locator("#promemoria-maybe").linkNamed("Manda il promemoria a chi ha un’iscrizione in forse").count())
        }
        assertEquals(1, e2e.dsl().fetchCount(REMINDER_BATCH, REMINDER_BATCH.GATHERING_ID.eq(x.gathering.id).and(REMINDER_BATCH.KIND.eq(ReminderKind.maybe))), "one row of the register")
        for (person in listOf(x.a) + x.others) {
            Mailbox.waitForMail(person.email)
            assertEquals(1, Mailbox.sentTo(person.email).size, "${person.email} has one mail")
        }
    }

    @E2eTest(teacher = false)
    fun `t13 c05 a double tap on the button of the dialog sends once`(e2e: E2e) {
        val x = e2e.example(extraInForse = 1)
        val teacher = e2e.newPage(teacher = true)
        teacher.visit(e2e, "/convivenza/${x.gathering.id}")
        e2e.press(teacher.locator("#promemoria").linkNamed("Manda il promemoria a chi ha un’iscrizione in forse"))
        teacher.waitForSettledDialog()
        // Two touches at once, in the same instant of the browser: nothing could be quicker. The button is waiting after the first, or the second one is sent after the answer.
        teacher.openDialog().buttonNamed("Manda il promemoria").evaluate("b => { b.click(); b.click(); }")

        assertThat(teacher.locator("#promemoria [role=status]")).containsText("Promemoria mandato a 2 persone.")
        assertEquals(1, e2e.dsl().fetchCount(REMINDER_BATCH, REMINDER_BATCH.GATHERING_ID.eq(x.gathering.id)))
        for (person in listOf(x.a) + x.others) {
            Mailbox.waitForMail(person.email)
            assertEquals(1, Mailbox.sentTo(person.email).size, "${person.email} has one mail")
        }
    }

    // ---- The wait is of exactly 168 hours (criterion 5) ----------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c05 the next one can go 168 hours after the last, not a minute before and not a minute after, and a request made by hand before sends nothing`(e2e: E2e) {
        val x = e2e.example()
        val teacher = e2e.newPage(teacher = true)
        val lastAt = e2e.now().minusHours(168).plusMinutes(2)
        e2e.insertBatch(x.gathering.id, ReminderKind.maybe, lastAt)

        teacher.visit(e2e, "/convivenza/${x.gathering.id}")
        val block = teacher.locator("#promemoria-maybe")
        assertThat(block).containsText("Potrai rimandarlo dal ${moment(lastAt.plusHours(168))}.")
        assertThat(block).containsText("Ultimo invio")
        assertEquals(0, block.locator("a, button").count(), "no button two minutes before the 168 hours")
        e2e.narrow(teacher, "the wait")
        // A request made by hand now sends nothing, and says so.
        val early = e2e.sendByHand(teacher, x.gathering.id, "maybe", csrfOf(teacher))
        assertEquals(200, early.status())
        assertTrue(early.text().contains("Questo promemoria è già stato mandato il "), early.text())
        assertTrue(Mailbox.sentTo(x.a.email).isEmpty(), "nothing left")
        assertEquals(1, e2e.batches(x.gathering.id))

        // Two minutes after the 168 hours: the button is back and the send works.
        e2e.dsl().update(REMINDER_BATCH).set(REMINDER_BATCH.SENT_AT, e2e.now().minusHours(168).minusMinutes(2)).where(REMINDER_BATCH.GATHERING_ID.eq(x.gathering.id)).execute()
        teacher.reload()
        teacher.waitForSettledContent()
        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        assertThat(teacher.locator("#promemoria [role=status]")).containsText("Promemoria mandato a 1 persona.")
        assertNotNull(Mailbox.waitForMail(x.a.email))
        assertEquals(2, e2e.batches(x.gathering.id))
    }

    @E2eTest(teacher = false)
    fun `t13 c05 across the change of the hour the page says the hour that the 168 hours end, in spring and in autumn`(e2e: E2e) {
        val x = e2e.example()
        val teacher = e2e.newPage(teacher = true)
        // Dates far in the future, so that the test does not depend on the day it runs (the register is read as it is): 30 March 2031 and 26 October 2031 are the changes of the hour.
        val spring = ZonedDateTime.of(2031, 3, 29, 21, 30, 0, 0, rome).toOffsetDateTime()
        val autumn = ZonedDateTime.of(2031, 10, 25, 21, 30, 0, 0, rome).toOffsetDateTime()

        e2e.insertBatch(x.gathering.id, ReminderKind.maybe, spring, recipients = 3)
        teacher.visit(e2e, "/convivenza/${x.gathering.id}")
        assertThat(teacher.locator("#promemoria-maybe")).containsText("29 marzo 2031 alle 21:30 · a 3 persone")
        assertThat(teacher.locator("#promemoria-maybe")).containsText("Potrai rimandarlo dal 5 aprile 2031 alle 22:30.")

        e2e.dsl().deleteFrom(REMINDER_BATCH).execute()
        e2e.insertBatch(x.gathering.id, ReminderKind.maybe, autumn, recipients = 3)
        teacher.reload()
        assertThat(teacher.locator("#promemoria-maybe")).containsText("25 ottobre 2031 alle 21:30 · a 3 persone")
        assertThat(teacher.locator("#promemoria-maybe")).containsText("Potrai rimandarlo dal 1 novembre 2031 alle 20:30.")
    }

    // ---- The card when the registrations are not open (criterion 2) ---------------------------------------------------------------------------------

    private fun closedCard(e2e: E2e, status: GatheringStatus, finished: Boolean) {
        val x = e2e.example(status = status)
        if (finished) {
            val today = e2e.today()
            e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, today.minusDays(3)).set(GATHERING.END_DATE, today.minusDays(1)).where(GATHERING.ID.eq(x.gathering.id)).execute()
        }
        val teacher = e2e.newPage(teacher = true)
        teacher.visit(e2e, "/convivenza/${x.gathering.id}")
        val card = teacher.locator("#promemoria")
        assertThat(card.locator("h2")).hasText("Promemoria")
        assertThat(card).hasText("Promemoria I promemoria si mandano con le iscrizioni aperte.")
        assertEquals(0, card.locator("a, button, h3, form, dialog").count(), "no blocks, no buttons, no dialogs")
        assertAccessible(teacher, "the card without open registrations (${e2e.profile.label})")
        e2e.narrow(teacher, "the card without open registrations")

        // The page of confirmation goes back to the detail, and a request made by hand sends nothing.
        teacher.visit(e2e, "/convivenza/${x.gathering.id}/promemoria?tipo=maybe")
        assertThat(teacher).hasURL(e2e.url("/convivenza/${x.gathering.id}"))
        val byHand = e2e.sendByHand(teacher, x.gathering.id, "maybe", csrfOf(teacher))
        assertEquals(409, byHand.status())
        assertTrue(byHand.text().contains("I promemoria si mandano con le iscrizioni aperte."), byHand.text())
        assertEquals(0, e2e.batches(x.gathering.id))
        assertTrue(Mailbox.sentTo(x.a.email).isEmpty())
    }

    @E2eTest(teacher = false)
    fun `t13 c02 in a draft the card says that the reminders go with the registrations open, and has no button`(e2e: E2e) = closedCard(e2e, GatheringStatus.draft, finished = false)

    @E2eTest(teacher = false)
    fun `t13 c02 with the registrations closed the card says that the reminders go with the registrations open, and has no button`(e2e: E2e) =
        closedCard(e2e, GatheringStatus.closed, finished = false)

    @E2eTest(teacher = false)
    fun `t13 c02 when the gathering is over the card says that the reminders go with the registrations open, and has no button`(e2e: E2e) =
        closedCard(e2e, GatheringStatus.`open`, finished = true)

    // ---- The network (criterion 6, design part 1 point 3) ------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c06 without a network the dialog closes, nothing is sent, the message has no Riprova and the card is as it was, and with the network back the send works`(e2e: E2e) {
        val x = e2e.example()
        val teacher = e2e.newPage(teacher = true)
        teacher.visit(e2e, "/convivenza/${x.gathering.id}")
        val card = teacher.locator("#promemoria")

        // On a small phone the dialog shows its title and both buttons without scrolling.
        teacher.setViewportSize(320, 568)
        e2e.press(card.linkNamed("Manda il promemoria a chi ha un’iscrizione in forse"))
        teacher.waitForSettledDialog()
        val confirmBox = teacher.openDialog().buttonNamed("Manda il promemoria").box()
        val cancelBox = teacher.openDialog().buttonNamed("Annulla").box()
        assertTrue(teacher.openDialog().locator("h2").box().y >= 0, "the title is on screen")
        assertTrue(confirmBox.y + confirmBox.height <= 568.5 && cancelBox.y + cancelBox.height <= 568.5, "the buttons are on screen: $confirmBox $cancelBox")
        assertTrue(teacher.noHorizontalScroll())
        e2e.touch(teacher.openDialog().buttonNamed("Annulla"))
        teacher.setViewportSize(if (e2e.profile.isPhone) 360 else 1280, 800)

        // The network is gone when the person confirms (only the send: the page is already open).
        teacher.route("**/convivenza/*/promemoria") { route: Route ->
            if (route.request().method() == "POST") {
                route.abort()
            } else {
                route.resume()
            }
        }
        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        val message = teacher.messages().filter(Locator.FilterOptions().setHasText("Nessuna connessione"))
        assertThat(message).hasCount(1)
        assertThat(message.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Riprova"))).hasCount(0)
        assertThat(teacher.locator("dialog[open]")).hasCount(0)
        assertEquals(0, card.locator("[role=status], [role=alert]").count(), "the card did not change")
        assertEquals(0, e2e.batches(x.gathering.id))
        assertTrue(Mailbox.sentTo(x.a.email).isEmpty())

        // The page is slow: after the time of htmx (shortened here) the message says so, and nothing changes.
        teacher.unrouteAll()
        teacher.evaluate("htmx.config.timeout = 700")
        teacher.route("**/convivenza/*/promemoria") { route: Route ->
            // The POST is never answered: the route is left open.
            if (route.request().method() != "POST") {
                route.resume()
            }
        }
        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        assertThat(teacher.messages().filter(Locator.FilterOptions().setHasText("La connessione è troppo lenta"))).hasCount(1)
        assertEquals(0, e2e.batches(x.gathering.id))

        // The network is back: the same button sends, and the message is gone.
        teacher.unrouteAll()
        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        assertThat(card.locator("[role=status]")).containsText("Promemoria mandato a 1 persona.")
        assertThat(teacher.messages()).hasCount(0)
        assertEquals(1, e2e.batches(x.gathering.id))
        assertNotNull(Mailbox.waitForMail(x.a.email))
    }

    @E2eTest(teacher = false)
    fun `t13 c09 without a network the tap on the page for stopping changes nothing, and the page stays as it was`(e2e: E2e) {
        val x = e2e.example()
        e2e.application.getBean(Reminders::class.java).send(x.gathering.id, ReminderType.MAYBE)
        val link = stopLinkOf(Mailbox.waitForMail(x.a.email).text)
        val stranger = e2e.newPage(signedIn = false)
        stranger.visit(e2e, e2e.pathOf(link))

        stranger.route("**/promemoria/smetti") { route: Route ->
            if (route.request().method() == "POST") {
                route.abort()
            } else {
                route.resume()
            }
        }
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        val message = stranger.messages().filter(Locator.FilterOptions().setHasText("Nessuna connessione"))
        assertThat(message).hasCount(1)
        assertThat(message.getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName("Riprova"))).hasCount(0)
        assertThat(stranger.locator("h1")).hasText("Promemoria di Talità")
        assertNull(e2e.dsl().select(ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(x.a.id)).fetchSingle(ACCOUNT.REMINDERS_OFF_AT), "nothing changed")
        // The button is touchable again, and with the network back it stops.
        stranger.unrouteAll()
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")
        assertNotNull(e2e.dsl().select(ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(x.a.id)).fetchSingle(ACCOUNT.REMINDERS_OFF_AT))
    }

    // ---- A page left open too long (criterion 9) -------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c09 a page for stopping that was open too long says so and changes nothing, and a second tap stops`(e2e: E2e) {
        val x = e2e.example()
        e2e.application.getBean(Reminders::class.java).send(x.gathering.id, ReminderType.MAYBE)
        val link = stopLinkOf(Mailbox.waitForMail(x.a.email).text)
        val stranger = e2e.newPage(signedIn = false)
        stranger.visit(e2e, e2e.pathOf(link))
        // The session of the page is gone (the phone was left for hours): the code of the form is not good any more.
        stranger.context().clearCookies()

        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("[role=alert]").first()).containsText("La pagina era aperta da troppo tempo. Riprova.")
        assertThat(stranger.locator("h1")).hasText("Promemoria di Talità")
        assertNull(e2e.dsl().select(ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(x.a.id)).fetchSingle(ACCOUNT.REMINDERS_OFF_AT), "nothing changed")
        assertAccessible(stranger, "the page for stopping after the session ended (${e2e.profile.label})")

        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")
        assertNotNull(e2e.dsl().select(ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(x.a.id)).fetchSingle(ACCOUNT.REMINDERS_OFF_AT))
    }

    // ---- The account that is deleted (criterion 13) ------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c13 the account that is deleted takes with it the avviso and the code, the register of the sends stays without anyone in it, and who comes back has nothing`(e2e: E2e) {
        val x = e2e.example()
        val reminders = e2e.application.getBean(Reminders::class.java)
        reminders.send(x.gathering.id, ReminderType.MAYBE)
        Mailbox.waitForMail(x.a.email)
        assertNotNull(e2e.dsl().select(ACCOUNT.REMINDER_CODE).from(ACCOUNT).where(ACCOUNT.ID.eq(x.a.id)).fetchSingle(ACCOUNT.REMINDER_CODE), "A has a code")
        assertNotNull(e2e.dsl().select(ACCOUNT.NOTICE_CONSENT_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(x.a.id)).fetchSingle(ACCOUNT.NOTICE_CONSENT_AT), "A asked for the avviso")
        // The gathering is over and closed: the account can be deleted (with the registrations open the profile explains why not).
        val today = e2e.today()
        e2e.dsl().deleteFrom(RETENTION_LOG).execute()
        e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, today.minusDays(5)).set(GATHERING.END_DATE, today.minusDays(3)).set(GATHERING.STATUS, GatheringStatus.closed)
            .where(GATHERING.ID.eq(x.gathering.id)).execute()

        e2e.open("/profilo")
        e2e.page.settled()
        e2e.press(e2e.page.locator("main").linkNamed("Cancella il mio account"))
        e2e.page.waitForSettledDialog()
        e2e.touch(e2e.page.openDialog().buttonNamed("Cancella il mio account"))
        assertThat(e2e.page).hasURL(e2e.url("/accedi"))

        assertFalse(e2e.dsl().fetchExists(ACCOUNT, ACCOUNT.ID.eq(x.a.id)), "the account is gone, with its avviso and its code")
        assertEquals(1, e2e.batches(x.gathering.id), "the register of the sends stays with the gathering")
        val row = e2e.dsl().selectFrom(REMINDER_BATCH).where(REMINDER_BATCH.GATHERING_ID.eq(x.gathering.id)).fetchSingle()
        assertEquals(ReminderKind.maybe, row.kind)
        assertEquals(1, row.recipients?.toInt())
        // The old mail of the reminder: its link still opens the page, and the tap says the same words (nobody to stop any more).
        val stranger = e2e.newPage(signedIn = false)
        val old = stopLinkOf(Mailbox.sentTo(x.a.email).last().text)
        stranger.visit(e2e, e2e.pathOf(old))
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")
        // Who comes back is a new account, with no stop, no avviso and no code.
        e2e.signIn(stranger, x.a.email, teacher = false)
        val back = e2e.dsl().select(ACCOUNT.ID, ACCOUNT.NOTICE_CONSENT_AT, ACCOUNT.REMINDERS_OFF_AT, ACCOUNT.REMINDER_CODE).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(x.a.email)).fetchSingle()
        assertTrue(back[ACCOUNT.ID] != x.a.id, "a new account")
        assertNull(back[ACCOUNT.NOTICE_CONSENT_AT])
        assertNull(back[ACCOUNT.REMINDERS_OFF_AT])
        assertNull(back[ACCOUNT.REMINDER_CODE])
    }

    // ---- The quota at its edge (criterion 7) ---------------------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c07 with 41 people the day does not allow it and says that 40 can go, with 40 it sends them all, and then a login link still leaves`(e2e: E2e) {
        val x = e2e.example(extraInForse = 40)
        val teacher = e2e.newPage(teacher = true)
        teacher.visit(e2e, "/convivenza/${x.gathering.id}")
        val card = teacher.locator("#promemoria")
        assertThat(card.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*41 persone.*", Pattern.DOTALL))

        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        assertThat(card.locator("[role=alert]")).containsText("Oggi si possono mandare ancora 40 promemoria, ma questo ne richiede 41. Riprova domani.")
        assertEquals(0, e2e.batches(x.gathering.id), "nothing was sent")
        assertAccessible(teacher, "the quota notice (${e2e.profile.label})")
        assertTrue(Mailbox.sentTo(x.a.email).isEmpty())
        assertEquals(40, e2e.application.getBean(MailQuota::class.java).remaining(MailKind.REMINDER), "the places were not used")
        assertEquals(
            0, e2e.dsl().fetchCount(ACCOUNT, ACCOUNT.ID.`in`((listOf(x.a) + x.others).map { it.id }).and(ACCOUNT.REMINDER_CODE.isNotNull)),
            "a send refused for the quota wrote no code: nothing is prepared for a mail that does not leave",
        )

        // One of them stops: 40 people, the whole quota of the day, and they all get their mail (the answer comes well before the 20 seconds of htmx).
        e2e.dsl().update(ACCOUNT).set(ACCOUNT.REMINDERS_OFF_AT, e2e.now()).where(ACCOUNT.ID.eq(x.others.last().id)).execute()
        teacher.reload()
        teacher.waitForSettledContent()
        assertThat(card.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*40 persone.*", Pattern.DOTALL))
        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        assertThat(card.locator("[role=status]")).containsText("Promemoria mandato a 40 persone.")
        val receivers = listOf(x.a) + x.others.dropLast(1)
        for (person in receivers) {
            Mailbox.waitForMail(person.email)
            assertEquals(1, Mailbox.sentTo(person.email).size, "${person.email} has one mail")
        }
        assertTrue(Mailbox.sentTo(x.others.last().email).isEmpty(), "the one who stopped has none")
        assertNull(e2e.codeOf(x.others.last().id), "and no code: it is written only for who is going to receive a mail")
        for (person in receivers) assertEquals(stopLinkOf(Mailbox.sentTo(person.email).single().text).substringAfter("codice="), e2e.codeOf(person.id), "the link of the mail is the code of the account")
        assertEquals(40, e2e.dsl().select(REMINDER_BATCH.RECIPIENTS).from(REMINDER_BATCH).where(REMINDER_BATCH.GATHERING_ID.eq(x.gathering.id)).fetchSingle(REMINDER_BATCH.RECIPIENTS)?.toInt())
        assertEquals(0, e2e.application.getBean(MailQuota::class.java).remaining(MailKind.REMINDER))

        // The day has no places left for reminders: the avviso (B) is refused with the words for «finished»; a link of access leaves all the same.
        e2e.press(card.linkNamed("Manda il promemoria a chi ha chiesto l’avviso"))
        teacher.waitForSettledDialog()
        e2e.touch(teacher.openDialog().buttonNamed("Manda l’avviso"))
        assertThat(card.locator("[role=alert]")).containsText("Oggi Talità ha già mandato tutti i promemoria che può mandare. Riprova domani.")
        assertTrue(Mailbox.sentTo(x.b.email).isEmpty())
        val stranger = e2e.newPage(signedIn = false)
        e2e.enterWithTheMail(Addresses.unique("qa13-accesso"), stranger)
        assertThat(stranger.locator("h1")).hasText("Ti diamo il benvenuto")
        e2e.application.getBean(MailQuota::class.java).release(MailKind.REMINDER, 1000)
    }

    // ---- What a parent and someone who is not inside can do by hand (criterion 11) ------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c11 a parent and someone who is not inside, with a request made by hand, get the 403 and the access page, and nothing is sent`(e2e: E2e) {
        val x = e2e.example()
        e2e.open("/profilo")
        val csrf = csrfOf(e2e.page)

        // The parent: the pages of the teachers are the 403, and the sends made by hand (with or without htmx) answer 403 and send nothing.
        for (type in listOf("maybe", "notice")) {
            val response = e2e.sendByHand(e2e.page, x.gathering.id, type, csrf)
            assertEquals(403, response.status(), "send $type by hand")
            val plain = e2e.sendByHand(e2e.page, x.gathering.id, type, csrf, htmx = false)
            assertEquals(403, plain.status(), "send $type by hand, without htmx")
        }
        e2e.open("/convivenza/${x.gathering.id}/promemoria?tipo=notice")
        assertThat(e2e.page.locator("h1")).hasText("Non puoi vedere questa pagina")
        e2e.open("/convivenza/${x.gathering.id}")
        assertThat(e2e.page.locator("h1")).hasText("Non puoi vedere questa pagina")
        assertThat(e2e.page.locator("main")).not().containsText("Promemoria")
        assertEquals(0, e2e.batches(x.gathering.id))
        assertTrue(Mailbox.sentTo(x.a.email).isEmpty())
        assertTrue(Mailbox.sentTo(x.b.email).isEmpty())

        // Nobody inside: the access page for a page, and the redirect that htmx follows for a request of a piece.
        val stranger = e2e.newPage(signedIn = false)
        val page = stranger.request().get(e2e.url("/convivenza/${x.gathering.id}/promemoria?tipo=maybe"), RequestOptions.create().setMaxRedirects(0))
        assertEquals(302, page.status())
        assertTrue(page.headers()["location"]!!.endsWith("/accedi"), "${page.headers()}")
        val piece = stranger.request().post(
            e2e.url("/convivenza/${x.gathering.id}/promemoria"),
            RequestOptions.create().setForm(FormData.create().set("tipo", "maybe")).setHeader("HX-Request", "true").setMaxRedirects(0),
        )
        assertEquals("/accedi", piece.headers()["hx-redirect"], "${piece.status()} ${piece.headers()}")
        assertEquals(0, e2e.batches(x.gathering.id))
        assertTrue(Mailbox.sentTo(x.a.email).isEmpty())
    }

    @E2eTest(teacher = false)
    fun `t13 c11 the avviso, the stop and the return of a parent change only the account of the parent, even with the address of another in the request`(e2e: E2e) {
        val x = e2e.example()
        val everyone = listOf(x.a, x.b, x.c, x.d, x.f, x.g, x.s)
        fun snapshot() = e2e.dsl().select(ACCOUNT.ID, ACCOUNT.NOTICE_CONSENT_AT, ACCOUNT.REMINDERS_OFF_AT).from(ACCOUNT).where(ACCOUNT.ID.`in`(everyone.map { it.id })).fetch()
            .associate { it[ACCOUNT.ID]!! to ((it[ACCOUNT.NOTICE_CONSENT_AT] != null) to (it[ACCOUNT.REMINDERS_OFF_AT] != null)) }
        e2e.open("/profilo")
        val csrf = csrfOf(e2e.page)
        val start = snapshot()
        assertEquals(true to false, start[x.a.id], "A asked for the avviso")
        assertEquals(true to false, start[x.b.id])
        assertEquals(false to false, start[x.d.id])
        assertEquals(false to true, start[x.s.id])

        // A names D (and S, and B) in every way a request could, and each time only A changes: the box, the stop, the return.
        fun byHand(path: String, vararg fields: Pair<String, String>) = e2e.page.request().post(
            e2e.url(path),
            RequestOptions.create().setForm(
                FormData.create().set("_csrf", csrf).set("account", x.d.id.toString()).set("accountId", x.s.id.toString()).set("id", x.b.id.toString()).set("email", x.d.email)
                    .set("codice", "AAAAAAAAAAAAAAAA").also { form -> fields.forEach { (name, value) -> form.set(name, value) } },
            ).setHeader("HX-Request", "true"),
        )
        assertEquals(200, byHand("/profilo/avviso").status(), "the box unticked")
        var now = snapshot()
        assertEquals(false to false, now[x.a.id], "A took the avviso away")
        for (other in everyone.filter { it !== x.a }) assertEquals(start[other.id], now[other.id], "only A changes (${other.email})")

        assertEquals(200, byHand("/profilo/avviso", "notice" to "true").status(), "the box ticked")
        now = snapshot()
        assertEquals(true to false, now[x.a.id])
        for (other in everyone.filter { it !== x.a }) assertEquals(start[other.id], now[other.id], "only A changes (${other.email})")

        assertEquals(200, byHand("/profilo/promemoria/stop").status())
        now = snapshot()
        assertEquals(false to true, now[x.a.id], "A stopped, and lost the avviso")
        for (other in everyone.filter { it !== x.a }) assertEquals(start[other.id], now[other.id], "only A changes (${other.email})")

        // A has stopped: the box is refused (409) and nothing is written; S is still stopped when A takes the reminders back.
        assertEquals(409, byHand("/profilo/avviso", "notice" to "true").status())
        assertEquals(200, byHand("/profilo/promemoria/riprendi").status())
        now = snapshot()
        assertEquals(false to false, now[x.a.id], "A is back, without the avviso")
        for (other in everyone.filter { it !== x.a }) assertEquals(start[other.id], now[other.id], "only A changes (${other.email})")
    }

    // ---- The story of a parent, from the box to the mail and back (criteria 3, 4, 8, 9, 10) ------------------------------------------------------

    @E2eTest(teacher = false)
    fun `t13 c03 c04 c08 c09 a parent asks for the avviso in Iscrizioni, the teachers count one person, the mail comes, and its two links open the information page and the page for stopping`(e2e: E2e) {
        e2e.application.getBean(MailQuota::class.java).release(MailKind.REMINDER, 1000)
        val gathering = e2e.openGathering()
        val previous = GatheringSupport(e2e.dsl(), e2e.clock()).make(
            title = "Convivenza dell’anno scorso", start = e2e.today().minusDays(400), end = e2e.today().minusDays(398), status = GatheringStatus.closed,
            custodians = listOf(gathering.martaId, gathering.paoloId),
        )
        val me = checkNotNull(e2e.signedInAs)
        e2e.rows().register(previous, e2e.accountId(me), "Sara", "Neri", LocalDate.of(2017, 6, 2), email = me)
        val teacher = e2e.newPage(teacher = true)
        teacher.visit(e2e, "/convivenza/${gathering.id}")
        val card = teacher.locator("#promemoria")
        assertThat(card.locator("#promemoria-notice")).hasText(Pattern.compile(".*Lo riceverebbero\\s*Nessuno.*", Pattern.DOTALL))

        // The parent has a child of last year and no registration to this gathering: the box is in Iscrizioni, and asking for the avviso is a gesture with a button.
        e2e.open("/iscrizioni")
        e2e.page.settled()
        val avviso = e2e.page.locator("#avviso-card")
        e2e.touch(avviso.getByLabel("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza"))
        e2e.press(avviso.buttonNamed("Salva la scelta"))
        assertThat(avviso.locator("[role=status]")).containsText("Scelta salvata. Ti avviseremo quando i maestri apriranno le iscrizioni di una nuova convivenza.")

        // The teachers count one person, and send the avviso.
        teacher.reload()
        teacher.waitForSettledContent()
        assertThat(card.locator("#promemoria-notice")).hasText(Pattern.compile(".*Lo riceverebbero\\s*1 persona(?!e).*", Pattern.DOTALL))
        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha chiesto l’avviso", "Manda l’avviso")
        assertThat(card.locator("[role=status]")).containsText("Promemoria mandato a 1 persona.")

        // The links of the mail are the real ones: the information page of the gathering (public), and the page for stopping.
        val mail = Mailbox.waitForMail(me)
        assertEquals("Iscrizioni aperte per «Convivenza di settembre 2027»", mail.subject)
        val stranger = e2e.newPage(signedIn = false)
        stranger.visit(e2e, e2e.pathOf(mail.links().first { it.contains("/info/") }))
        assertThat(stranger.locator("h1")).hasText("Convivenza di settembre 2027")
        stranger.visit(e2e, e2e.pathOf(stopLinkOf(mail.text)))
        e2e.touch(stranger.buttonNamed("Non mandarmi più promemoria"))
        assertThat(stranger.locator("h1")).hasText("Non riceverai più promemoria")

        // The parent finds the stop in the profile, with the box gone, and in Iscrizioni the card sends to the profile.
        e2e.open("/profilo")
        e2e.page.settled()
        assertThat(e2e.page.locator("#email")).containsText("Hai smesso di ricevere i promemoria il ")
        assertEquals(0, e2e.page.locator("#email").getByLabel("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza").count())
        e2e.open("/iscrizioni")
        e2e.page.settled()
        assertThat(e2e.page.locator("#avviso-card")).containsText("Hai smesso di ricevere i promemoria il ")
        assertThat(e2e.page.locator("#avviso-card").linkNamed("Vai al profilo")).isVisible()
        teacher.reload()
        assertThat(teacher.locator("#promemoria-notice")).hasText(Pattern.compile(".*Lo riceverebbero\\s*Nessuno.*", Pattern.DOTALL))
    }

    @E2eTest(teacher = false)
    fun `t13 c08 c10 the mail in forse leads to Iscrizioni, where the parent confirms as it says, and then the teachers do not count them any more`(e2e: E2e) {
        e2e.application.getBean(MailQuota::class.java).release(MailKind.REMINDER, 1000)
        val gathering = e2e.openGathering()
        val me = checkNotNull(e2e.signedInAs)
        val meId = e2e.accountId(me)
        e2e.rows().register(gathering.id, meId, "Luca", "Rossi", email = me)
        val anna = e2e.rows().register(gathering.id, meId, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.maybe, email = me)
        val teacher = e2e.newPage(teacher = true)
        teacher.visit(e2e, "/convivenza/${gathering.id}")
        val card = teacher.locator("#promemoria")
        e2e.sendFromTheDialog(teacher, "Manda il promemoria a chi ha un’iscrizione in forse", "Manda il promemoria")
        assertThat(card.locator("[role=status]")).containsText("Promemoria mandato a 1 persona.")

        // The mail is opened on another phone: the link of the mail leads to the access page, and the person enters with the address that the mail names.
        val mail = Mailbox.waitForMail(me)
        val link = mail.links().first { it.endsWith("/iscrizioni") }
        val other = e2e.newPage(signedIn = false)
        other.navigate(e2e.url(e2e.pathOf(link)))
        other.waitForURL(e2e.url("/accedi"))
        // (The link that the mail of the access would carry, made by the service of the tokens: the page «Entra» and the tap are the real ones.)
        val token = e2e.application.getBean(OneTimeTokenService::class.java).generate(GenerateOneTimeTokenRequest(me, Duration.ofMinutes(15))).tokenValue
        other.navigate(e2e.url("/accedi/conferma?token=$token"))
        other.waitForSettledContent()
        e2e.touch(other.buttonNamed("Entra"))
        // The page that the person asked for, before the access page, is where the access leads: the page «Iscrizioni» of the mail.
        other.waitForURL(e2e.url("/iscrizioni"))
        other.settled()
        assertThat(other.locator("main")).containsText("Anna Rossi")

        // «Conferma l’iscrizione»: the page of the registration of Anna, the state «Confermata», saved. Nobody is told to withdraw anywhere on the way.
        other.visit(e2e, "/iscrizioni/$anna/modifica")
        assertThat(other.locator("input[type=radio][value=maybe]")).isChecked()
        e2e.touch(other.locator("input[type=radio][value=confirmed]"))
        e2e.press(other.buttonNamed("Salva le modifiche"))
        other.waitForURL(e2e.url("/iscrizioni"))
        assertEquals(RegistrationStatus.confirmed, e2e.dsl().select(REGISTRATION.STATUS).from(REGISTRATION).where(REGISTRATION.ID.eq(anna)).fetchSingle(REGISTRATION.STATUS))

        // Nobody is in forse any more: the teachers' card counts nobody (and keeps the wait of the send that was made).
        teacher.reload()
        teacher.waitForSettledContent()
        assertThat(card.locator("#promemoria-maybe")).hasText(Pattern.compile(".*Lo riceverebbero\\s*Nessuno.*Potrai rimandarlo dal .*", Pattern.DOTALL))
    }

    @E2eTest(teacher = false)
    fun `t13 c03 an account without children does not see the box, in Iscrizioni or in the profile, and a request made by hand is the page that does not exist`(e2e: E2e) {
        val gathering = e2e.openGathering()
        e2e.open("/profilo")
        e2e.page.settled()
        assertEquals(0, e2e.page.locator("#email").count(), "no card in the profile")
        assertThat(e2e.page.locator("main")).not().containsText("Avvisami per email")
        e2e.open("/iscrizioni")
        e2e.page.settled()
        assertEquals(0, e2e.page.locator("#avviso-card").count(), "no card in Iscrizioni")
        e2e.open("/profilo")
        val csrf = csrfOf(e2e.page)
        for (path in listOf("/profilo/avviso", "/iscrizioni/avviso")) {
            val response = e2e.page.request().post(
                e2e.url(path),
                RequestOptions.create().setForm(FormData.create().set("_csrf", csrf).set("notice", "true")).setHeader("HX-Request", "true"),
            )
            assertEquals(404, response.status(), "ticking the box by hand at $path")
        }
        assertNull(e2e.dsl().select(ACCOUNT.NOTICE_CONSENT_AT).from(ACCOUNT).where(ACCOUNT.ID.eq(e2e.accountId(checkNotNull(e2e.signedInAs)))).fetchSingle(ACCOUNT.NOTICE_CONSENT_AT))
        assertEquals(0, e2e.batches(gathering.id))
    }

    @E2eTest(teacher = false)
    fun `t13 c03 a teacher who is also a parent finds the box in the profile and not in Iscrizioni, and a teacher with no children finds none`(e2e: E2e) {
        val gathering = e2e.openGathering()
        val me = checkNotNull(e2e.signedInAs)
        TestAccounts.makeTeacher(e2e.dsl(), me, "Elisa", "Conti")
        e2e.open("/profilo")
        e2e.page.settled()
        assertEquals(0, e2e.page.locator("#email").count(), "no children: no card")

        e2e.rows().register(gathering.id, e2e.accountId(me), "Giulia", "Blu", email = me)
        e2e.open("/profilo")
        e2e.page.settled()
        val card = e2e.page.locator("#email")
        assertThat(card.locator("h2")).hasText("Email da Talità")
        assertThat(card.getByLabel("Avvisami per email quando si aprono le iscrizioni di una nuova convivenza")).not().isChecked()
        assertAccessible(e2e.page, "the profile of a teacher with a child (${e2e.profile.label})")
        e2e.open("/iscrizioni")
        e2e.page.settled()
        assertEquals(0, e2e.page.locator("#avviso-card").count(), "the page of the teachers has no such card: it is in the profile")
    }
}
