package talita.reminder

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.ClockConfiguration
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.text
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.ReminderKind
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.REMINDER_BATCH
import talita.mail.MailKind
import talita.mail.MailQuota
import talita.mail.Mailbox
import talita.registration.Notes
import talita.registration.RegistrationFormat
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sending the reminders (TALITA-13, criteria 4, 5 and 8), with the mails read from Mailpit: a mail for each person and the notice of the page with the true number, the register
 * of what was sent, the wait of 7 days, a repeated request that sends nothing, two requests at the same moment that send once, and the mails as they leave with the people of
 * the example (never a name or a datum of the children). The failures (server down, no address, one mail refused, the day over) have their own classes, each an application with
 * its own settings.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension::class)
class ReminderSendTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val reminders: Reminders,
    @Autowired private val quota: MailQuota,
) {

    private val kit = ReminderTestKit(dsl, clock)

    private val browsers = ReminderBrowsers(mockMvc, tokens, dsl)

    /** Every test begins with the example, and with the day's count of reminders as it was at the start: the application of the tests is shared, and so is the count. */
    @BeforeEach
    fun clean() {
        kit.reset()
        quota.release(MailKind.REMINDER, 1000)
    }

    /** The lines of a mail, without the line breaks of the transfer (the mail arrives with CRLF). */
    private fun lines(text: String): List<String> = text.lines().map { it.trim() }

    private fun card(html: String): String = Regex("""<section id="promemoria".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)?.value ?: error("no card in the answer")

    /** The example plus two more people in forse: A, E and F receive «in forse»; B receives the avviso. */
    private fun three(): Triple<ReminderTestKit.Scenario, Person, Person> {
        val s = kit.scenario()
        return Triple(s, kit.inForse(s.gathering, "invio-e"), kit.inForse(s.gathering, "invio-f"))
    }

    // ---- Criterion 4: a mail for each person, the notice, the register -----------------------------------------------------------------------

    @Test
    fun `t13 c04 a teacher sends the reminder in forse, a mail for each person, the page says to how many, and the register has the batch`() {
        val (s, e, f) = three()
        val browser = browsers.enter(s.m.email)
        val before = OffsetDateTime.now(clock).minusSeconds(1)

        val result = browser.send(s.gathering, "maybe")

        assertEquals(200, result.status())
        val html = result.html()
        assertTrue(html.trim().startsWith("<section id=\"promemoria\"") && html.trim().endsWith("</section>"), "the answer is the card and nothing else: ${html.take(80)}")
        assertTrue(html.contains("""role="status""""), "the notice is a confirmation, announced")
        assertTrue(html.contains("data-fuoco"), "and it takes the focus")
        assertTrue(text(html).contains("Promemoria mandato a 3 persone."), text(html))
        for (person in listOf(s.a, e, f)) {
            val received = Mailbox.waitForMail(person.email)
            assertEquals(1, Mailbox.sentTo(person.email).size, "one mail for each person")
            assertEquals("Iscrizione in forse alla Convivenza di settembre 2027", received.subject)
        }
        for (other in listOf(s.b, s.c, s.d, s.m, s.n)) assertTrue(Mailbox.sentTo(other.email).isEmpty(), "a mail went to somebody who is not in forse")
        val batches = kit.batches(s.gathering)
        assertEquals(1, batches.size)
        assertEquals(ReminderKind.maybe, batches.single().kind)
        assertEquals(3, batches.single().recipients?.toInt())
        assertEquals(0, batches.single().failed?.toInt())
        assertTrue(batches.single().sentAt!!.isAfter(before) && batches.single().sentAt!!.isBefore(OffsetDateTime.now(clock).plusSeconds(1)), "the moment of the sending")
        assertEquals(1, dsl.fetchCount(REMINDER_BATCH), "the other kind did not leave")
        // And the card of the answer already says what happened: when, to how many, and from when it can go again. No button.
        assertTrue(text(card(html)).contains("Ultimo invio ${RegistrationFormat.moment(batches.single().sentAt!!)} · a 3 persone"), text(card(html)))
        assertTrue(text(card(html)).contains("Potrai rimandarlo dal "), text(card(html)))
    }

    @Test
    fun `t13 c04 the mail of each person has the address to stop with the code of that account only, and the same address in the header`() {
        val (s, e, _) = three()
        browsers.enter(s.m.email).send(s.gathering, "maybe")

        for (person in listOf(s.a, e)) {
            val received = Mailbox.waitForMail(person.email)
            val code = kit.codeOf(person.id)!!
            assertTrue(Regex("^[A-Za-z0-9_-]{16}$").matches(code), "a code of 16 characters: $code")
            val stop = "https://talita.example.org/promemoria/smetti?codice=$code"
            assertTrue(stop in lines(received.text), "the link to stop in the text")
            assertEquals(listOf("<$stop>"), received.headers["list-unsubscribe"])
            assertEquals(listOf("0"), received.headers["x-mailjet-trackopen"])
            assertEquals(listOf("0"), received.headers["x-mailjet-trackclick"])
            assertEquals("", received.html, "plain text")
            assertTrue("https://talita.example.org/iscrizioni" in lines(received.text), "the link to the registrations")
        }
        assertNotEquals(kit.codeOf(s.a.id), kit.codeOf(e.id), "a code of its own for each account")
        assertNull(kit.codeOf(s.b.id), "nobody else got a code: the code is written when the first reminder is made for the account")
    }

    @Test
    fun `t13 c04 a second reminder to the same person keeps the code that they had`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.m.email)
        browser.send(s.gathering, "maybe")
        val code = kit.codeOf(s.a.id)

        dsl.deleteFrom(REMINDER_BATCH).execute()
        browser.send(s.gathering, "maybe")

        assertEquals(code, kit.codeOf(s.a.id), "the link of the first mail still works")
        assertEquals(2, Mailbox.sentTo(s.a.email).size)
    }

    /** The code that a mail has in the address to stop. */
    private fun stopCodeIn(text: String): String = Regex("""/promemoria/smetti\?codice=([A-Za-z0-9_-]{16})""").find(text)?.groupValues?.get(1) ?: error("no address to stop in the mail")

    /**
     * Runs [block] with a trigger that makes every insert in the register of the sends fail (a statement that fails after the mails left: a connection lost, a timeout), and takes
     * it away at the end. The tests of a class run one at a time, so no other test finds it.
     */
    private fun <T> withTheRegisterFailing(block: () -> T): T {
        dsl.execute("create function t13_forced_failure() returns trigger language plpgsql as 'begin raise exception ''forced by the test''; end'")
        dsl.execute("create trigger t13_forced_failure before insert on reminder_batch for each row execute function t13_forced_failure()")
        try {
            return block()
        } finally {
            dsl.execute("drop trigger if exists t13_forced_failure on reminder_batch")
            dsl.execute("drop function if exists t13_forced_failure()")
        }
    }

    @Test
    fun `t13 c04 when the sending fails after the mails left the code in the mail still stops the account (the codes are committed before the mails leave)`() {
        val s = kit.scenario()

        // The mails leave, and then the transaction fails on the row of the register: it is rolled back, and nothing says that the mails went.
        withTheRegisterFailing { assertFailsWith<Exception> { reminders.send(s.gathering, ReminderType.MAYBE) } }

        val received = Mailbox.waitForMail(s.a.email)
        val code = stopCodeIn(received.text)
        assertTrue(kit.batches(s.gathering).isEmpty(), "the row of the register was rolled back")
        assertEquals(code, kit.codeOf(s.a.id), "the code of the mail that is in the mailbox is the one of the account")
        val tap = MockBrowser(mockMvc).submit("/promemoria/smetti?codice=$code", "/promemoria/smetti", mapOf("codice" to code))
        assertEquals(200, tap.response.status)
        assertNotNull(kit.stopOf(s.a.id), "the link of a mail that left stops the account, as the page says")
    }

    @Test
    fun `t13 c04 the codes are written in a transaction of their own, so a transaction around the sending that is rolled back does not take them away`() {
        val s = kit.scenario()
        class Forced : RuntimeException("forced by the test")

        // `send` called inside a transaction (it is not, from the controller, but nothing in the code says that it cannot be): the savepoint of `dsl.transaction` would not be enough.
        assertFailsWith<Forced> {
            dsl.transaction { _ ->
                assertTrue(reminders.send(s.gathering, ReminderType.MAYBE) is SendResult.Sent)
                throw Forced()
            }
        }

        val code = stopCodeIn(Mailbox.waitForMail(s.a.email).text)
        assertTrue(kit.batches(s.gathering).isEmpty(), "everything of the transaction around was rolled back")
        assertEquals(code, kit.codeOf(s.a.id), "but the code was committed before the mail left")
    }

    @Test
    fun `t13 c04 the avviso goes to B with the address of the information page of the gathering, and the register says notice`() {
        val s = kit.scenario()
        val code = dsl.select(GATHERING.PUBLIC_CODE).from(GATHERING).where(GATHERING.ID.eq(s.gathering)).fetchSingle(GATHERING.PUBLIC_CODE)!!

        val result = browsers.enter(s.m.email).send(s.gathering, "notice")

        assertEquals(200, result.status())
        assertTrue(text(result.html()).contains("Promemoria mandato a 1 persona."), text(result.html()))
        val received = Mailbox.waitForMail(s.b.email)
        assertEquals("Iscrizioni aperte: Convivenza di settembre 2027", received.subject)
        assertTrue("https://talita.example.org/info/$code" in lines(received.text), received.text)
        assertTrue(received.text.contains("hai chiesto di ricevere un avviso quando si aprono le iscrizioni di una nuova convivenza dei bambini"))
        assertFalse(received.subject.contains(code), "the code of the information page is never in the subject")
        assertEquals(listOf(ReminderKind.notice), kit.batches(s.gathering).map { it.kind })
        assertTrue(Mailbox.sentTo(s.a.email).isEmpty() && Mailbox.sentTo(s.d.email).isEmpty() && Mailbox.sentTo(s.c.email).isEmpty())
    }

    @Test
    fun `t13 c04 the number in the notice is the true one at the moment of the sending, which can be other than the one of the dialog`() {
        val (s, e, f) = three()
        val browser = browsers.enter(s.m.email)
        assertTrue(text(browser.get("/convivenza/${s.gathering}").response.contentAsString).contains("Mandiamo una mail a 3 persone"), "the dialog said 3")

        // F confirms while the page of the teacher is open: the people are worked out when the mail leaves, not before.
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, talita.jooq.enums.RegistrationStatus.confirmed).where(REGISTRATION.ACCOUNT_ID.eq(f.id)).execute()
        val result = browser.send(s.gathering, "maybe")

        assertTrue(text(result.html()).contains("Promemoria mandato a 2 persone."), text(result.html()))
        assertTrue(Mailbox.sentTo(f.email).isEmpty(), "F confirmed: no mail")
        assertEquals(2, kit.batches(s.gathering).single().recipients?.toInt())
        assertEquals(1, Mailbox.sentTo(e.email).size)
    }

    @Test
    fun `t13 c04 without JavaScript the page of confirmation sends, and goes back to the detail with the notice, once`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.m.email)

        val result = browser.send(s.gathering, "maybe", htmx = false)

        assertEquals(303, result.status())
        assertEquals("/convivenza/${s.gathering}", result.location())
        val detail = browser.get("/convivenza/${s.gathering}").response.contentAsString
        assertTrue(text(detail).contains("Promemoria mandato a 1 persona."), "the detail shows the notice")
        assertEquals(1, Mailbox.sentTo(s.a.email).size)
        assertFalse(text(browser.get("/convivenza/${s.gathering}").response.contentAsString).contains("Promemoria mandato a 1 persona."), "once")
    }

    // ---- Criterion 5: seven days, a repeated request, two requests together ------------------------------------------------------------

    @Test
    fun `t13 c05 a reminder of a kind sent less than 7 days ago is not sent, the answer is the card with the one that was done, and nothing leaves`() {
        val (s, e, f) = three()
        val browser = browsers.enter(s.m.email)
        browser.send(s.gathering, "maybe")
        val first = kit.batches(s.gathering).single()

        // A double tap, another teacher, or a request made by hand: 200, the notice says it was done, and nothing leaves.
        val again = browser.send(s.gathering, "maybe")

        assertEquals(200, again.status())
        assertTrue(again.html().contains("""role="status""""))
        assertTrue(text(again.html()).contains("Questo promemoria è già stato mandato il ${RegistrationFormat.moment(first.sentAt!!)}, a 3 persone."), text(again.html()))
        assertFalse(card(again.html()).contains("data-apri=\"manda-maybe\""), "no button")
        assertTrue(text(card(again.html())).contains("Potrai rimandarlo dal "))
        for (person in listOf(s.a, e, f)) assertEquals(1, Mailbox.sentTo(person.email).size, "one mail, not two")
        assertEquals(1, kit.batches(s.gathering).size, "one batch, not two")
    }

    @Test
    fun `t13 c05 the wait is 7 days to the minute, and it is for each kind`() {
        val s = kit.scenario()
        val now = OffsetDateTime.now(clock)

        // 6 days, 23 hours and 55 minutes ago: not yet.
        val recent = kit.batch(s.gathering, ReminderKind.maybe, 7, at = now.minusDays(7).plusMinutes(5))
        val tooSoon = reminders.send(s.gathering, ReminderType.MAYBE)
        assertTrue(tooSoon is SendResult.TooSoon, "$tooSoon")
        assertTrue(Mailbox.sentTo(s.a.email).isEmpty())

        // The avviso is another kind: the last one of in forse does not hold it back.
        assertTrue(reminders.send(s.gathering, ReminderType.NOTICE) is SendResult.Sent)

        // 7 days and a minute ago: now.
        dsl.update(REMINDER_BATCH).set(REMINDER_BATCH.SENT_AT, now.minusDays(7).minusMinutes(1)).where(REMINDER_BATCH.ID.eq(recent)).execute()
        val sent = reminders.send(s.gathering, ReminderType.MAYBE)
        assertTrue(sent is SendResult.Sent, "$sent")
        assertEquals(1, Mailbox.sentTo(s.a.email).size)
        assertEquals(2, kit.batches(s.gathering).count { it.kind == ReminderKind.maybe }, "the old row stays: the register is a history")
    }

    @Test
    fun `t13 c05 the next reminder is dated from the last row of the register, exactly 7 days (168 hours) after`() {
        val summer = OffsetDateTime.of(2027, 6, 14, 21, 30, 0, 0, java.time.ZoneOffset.ofHours(2)).atZoneSameInstant(ClockConfiguration.ZONE).toOffsetDateTime()
        assertEquals("21 giugno 2027 alle 21:30", RegistrationFormat.moment(LastBatch(summer, 4, 0).again), "the example of the design")

        // Across the change to the summer time (28 March 2027, at night) the 168 hours end an hour later on the wall: the page says that hour, and the button works from then.
        val winter = OffsetDateTime.of(2027, 3, 24, 21, 30, 0, 0, java.time.ZoneOffset.ofHours(1)).atZoneSameInstant(ClockConfiguration.ZONE).toOffsetDateTime()
        val last = LastBatch(winter, 3, 0)
        assertEquals("31 marzo 2027 alle 22:30", RegistrationFormat.moment(last.again))
        assertEquals(168L, java.time.Duration.between(winter, last.again).toHours())
        assertEquals(3, last.reached)
        assertEquals(2, LastBatch(winter, 3, 1).reached)
    }

    @Test
    fun `t13 c05 two requests at the same moment send once, and the second one is told it was done`() {
        val (s, e, _) = three()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val outcomes = (1..2).map { pool.submit<SendResult> { start.await(); reminders.send(s.gathering, ReminderType.MAYBE) } }
            start.countDown()
            val results = outcomes.map { it.get(60, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it is SendResult.Sent }, "$results")
            assertEquals(1, results.count { it is SendResult.TooSoon }, "$results")
            assertEquals(1, Mailbox.sentTo(s.a.email).size, "a mail, not two")
            assertEquals(1, Mailbox.sentTo(e.email).size)
            assertEquals(1, kit.batches(s.gathering).size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t13 c05 a request that waits for the lock of another one finds the row that it wrote, and sends nothing (two transactions)`() {
        val (s, e, _) = three()
        val pool = Executors.newFixedThreadPool(1)
        try {
            var waiting: java.util.concurrent.Future<SendResult>? = null
            // The first transaction holds the lock of this gathering and this kind, as the sending of another teacher does while its mails leave.
            dsl.transaction { configuration ->
                val holder = DSL.using(configuration)
                holder.fetch("select pg_advisory_xact_lock(?, ?)", Reminders.LOCK_NAMESPACE, Reminders.lockKey(s.gathering, ReminderType.MAYBE))
                waiting = pool.submit<SendResult> { reminders.send(s.gathering, ReminderType.MAYBE) }

                Thread.sleep(800)
                assertFalse(waiting!!.isDone, "the second request goes on while the first holds the lock: two mails for each person")
                assertTrue(Mailbox.sentTo(s.a.email).isEmpty())
                // The first one has sent, and writes its row; it lets go of the lock when it commits.
                holder.insertInto(REMINDER_BATCH)
                    .set(REMINDER_BATCH.GATHERING_ID, s.gathering).set(REMINDER_BATCH.KIND, ReminderKind.maybe).set(REMINDER_BATCH.SENT_AT, OffsetDateTime.now(clock))
                    .set(REMINDER_BATCH.RECIPIENTS, 3.toShort()).set(REMINDER_BATCH.FAILED, 0.toShort()).execute()
            }

            val result = waiting!!.get(30, TimeUnit.SECONDS)

            assertTrue(result is SendResult.TooSoon, "$result: the people and the wait are looked at after the lock, not before")
            assertTrue(Mailbox.sentTo(s.a.email).isEmpty() && Mailbox.sentTo(e.email).isEmpty(), "nothing left")
            assertEquals(1, kit.batches(s.gathering).size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t13 c05 the lock is of the gathering and the kind, another kind does not wait`() {
        val s = kit.scenario()
        val pool = Executors.newFixedThreadPool(1)
        try {
            dsl.transaction { configuration ->
                DSL.using(configuration).fetch("select pg_advisory_xact_lock(?, ?)", Reminders.LOCK_NAMESPACE, Reminders.lockKey(s.gathering, ReminderType.MAYBE))
                val notice = pool.submit<SendResult> { reminders.send(s.gathering, ReminderType.NOTICE) }

                assertTrue(notice.get(30, TimeUnit.SECONDS) is SendResult.Sent, "the avviso goes while in forse is being sent")
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(ReminderType.entries.size, ReminderType.entries.map { Reminders.lockKey(7, it) }.toSet().size, "a key for each kind")
        assertNotEquals(Reminders.lockKey(7, ReminderType.MAYBE), Reminders.lockKey(8, ReminderType.MAYBE))
    }

    // ---- What does not leave --------------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c04 nothing is sent for a gathering that is not open, or for nobody, or for a kind that does not exist`() {
        val s = kit.scenario()
        val browser = browsers.enter(s.m.email)

        // Nobody: the page has no button, a request made by hand sends nothing, and says so.
        dsl.update(ACCOUNT).set(ACCOUNT.REMINDERS_OFF_AT, OffsetDateTime.now(clock)).setNull(ACCOUNT.NOTICE_CONSENT_AT).where(ACCOUNT.ID.eq(s.a.id)).execute()
        val nobody = browser.send(s.gathering, "maybe")
        assertEquals(200, nobody.status())
        assertTrue(text(nobody.html()).contains("Nessuno da avvisare."))
        assertTrue(kit.batches(s.gathering).isEmpty())

        // A kind that is not one: the page that does not exist, and no mail.
        assertEquals(404, browser.send(s.gathering, "sms").status())
        assertEquals(404, browser.send(s.gathering, "").status())
        // A gathering that does not exist.
        assertEquals(404, browser.send(987654321, "maybe").status())

        // Registrations closed: the card says that reminders go with them open, and nothing is sent.
        dsl.update(ACCOUNT).setNull(ACCOUNT.REMINDERS_OFF_AT).where(ACCOUNT.ID.eq(s.a.id)).execute()
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(s.gathering)).execute()
        val closed = browser.send(s.gathering, "maybe")
        assertEquals(409, closed.status())
        assertEquals("Promemoria I promemoria si mandano con le iscrizioni aperte.", text(closed.html()))
        assertTrue(kit.batches(s.gathering).isEmpty())
        assertTrue(Mailbox.sentTo(s.a.email).isEmpty(), "no mail left in any of these cases")
    }

    // ---- Criterion 8: the mails as they leave, with the people of the example ----------------------------------------------------------

    @Test
    fun `t13 c08 the mails that leave have no name, no date of birth, no telephone, no note on health, no place, no fee and no number of children, with Luca and Anna registered`() {
        val s = kit.scenario()
        // Anna has a telephone and notes on health; a third child too, in forse: the mail does not say how many.
        kit.rows.register(
            s.gathering, s.a.id, "Marta", "Rossi", LocalDate.of(2020, 5, 5), phone = "333 987 6543", status = talita.jooq.enums.RegistrationStatus.maybe,
            notes = Notes("Arachidi", "Lattosio", "Antistaminico in caso di reazione"), email = s.a.email,
        )
        browsers.enter(s.m.email).send(s.gathering, "maybe")
        browsers.enter(s.m.email).send(s.gathering, "notice")

        val place = "Casa per ritiri di esempio"
        for ((person, kind) in listOf(s.a to "in forse", s.b to "avviso")) {
            val received = Mailbox.waitForMail(person.email)
            // Without the addresses: the code of a link is random, and could have, by chance, three letters that look like a name.
            val everything = (received.subject + "\n" + received.text).replace(Regex("""https?://\S+"""), "")
            for (secret in listOf(
                "Luca", "Anna", "Marta", "Rossi", "Sara", "Neri", "Nicolò", "Ferri", "2016", "2019", "2020", "2017", "aprile", "333", "987", "6543", "4567",
                "Arachidi", "Lattosio", "Antistaminico", place, "Casa", "€", "quota", "Quota", "allerg", "Allerg", "intolleran", "farmac", "segnalazion",
            )) {
                assertFalse(everything.contains(secret), "the mail $kind has «$secret»: $everything")
            }
            // No count of the children of the person: no «due», «tre», «2», «3» before «figli» or «iscrizioni» (the text says «almeno un’iscrizione», and nothing else).
            assertFalse(Regex("""(?i)\b(\d+|due|tre|quattro)\s+(figli|bambini|iscrizioni)\b""").containsMatchIn(everything), "the mail $kind says how many: $everything")
        }
        assertTrue(Mailbox.waitForMail(s.a.email).text.contains("c’è almeno un’iscrizione in forse alla Convivenza di settembre 2027"))
    }

    // ---- Criterion 12: the log ----------------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c12 the log has the gathering, the kind and the numbers, and never an address, a code or a link`(output: CapturedOutput) {
        val (s, e, f) = three()
        val browser = browsers.enter(s.m.email)

        browser.send(s.gathering, "maybe")
        browser.send(s.gathering, "maybe")
        browser.send(s.gathering, "notice")
        browser.send(s.gathering, "sms")

        assertTrue(output.all.contains("Reminders sent: gathering ${s.gathering}, maybe, 3 of 3."), output.all.lines().filter { it.contains("Reminders") }.joinToString("\n"))
        assertTrue(output.all.contains("Reminders sent: gathering ${s.gathering}, notice, 1 of 1."))
        val codes = listOf(s.a, e, f, s.b).mapNotNull { kit.codeOf(it.id) }
        assertEquals(4, codes.size)
        for (secret in listOf(s.a.email, e.email, f.email, s.b.email, s.m.email, "talita.example.org", "smetti", "codice=") + codes) {
            assertFalse(output.all.contains(secret), "the log has «$secret»")
        }
        for (name in listOf("Luca", "Anna", "Sara", "Nicolò", "Rossi", "Neri")) assertFalse(output.all.contains(name), "the log has the name $name")
    }
}
