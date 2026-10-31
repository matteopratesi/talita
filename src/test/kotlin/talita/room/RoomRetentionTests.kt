package talita.room

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import talita.CapturesOutput
import talita.TestcontainersConfiguration
import talita.checkin.words
import talita.gathering.GatheringFormat
import talita.gathering.body
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.HEALTH_NOTE
import talita.jooq.tables.references.REGISTRATION
import talita.jooq.tables.references.RETENTION_LOG
import talita.registration.HealthNotes
import talita.registration.Notes
import talita.retention.ChildErasure
import talita.retention.Deleted
import talita.retention.ErasureService
import talita.retention.RetentionJob
import talita.retention.RetentionPolicy
import talita.retention.RetentionRule
import talita.retention.RetentionTestKit
import talita.retention.RetentionTexts
import talita.teacher.Teachers
import java.time.Clock
import java.time.LocalDate
import java.time.Period
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Who sleeps where goes at the end of the gathering (criterion 20, decision of Matteo on question 4: the rooms are tied to the house, so they are emptied when the gathering ends): the rule
 * of the job from the day after the last day, with the register and the log of numbers, the rooms that stay empty, what the pages say before and after, the words that follow the policy, and
 * the deletion of the data of a child that takes it out of every room. The database is real, the clock is fixed at a moment of Rome in the tests of the job. Names and dates are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomRetentionTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val job: RetentionJob,
    @Autowired private val policy: RetentionPolicy,
    @Autowired private val texts: RetentionTexts,
    @Autowired private val rules: List<RetentionRule>,
) {

    private val retention = RetentionTestKit(dsl, clock)
    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    @BeforeEach
    fun clean() {
        retention.reset()
        retention.removeAccountsWithoutRole()
    }

    /** The «Convivenza di settembre 2027» with three rooms, three children and two teachers in them, and the same on the year after (not finished at the dates of the tests). */
    private inner class Old {
        val gathering = retention.september2027()
        val m = retention.teacher("maestro-m", first = "Marta", last = "Bianchi")
        val n = retention.teacher("maestro-n", first = "Paolo", last = "Verdi")
        val parent = retention.account("genitore-a")
        val one = kit.room(gathering, "Camera 1", 4)
        val two = kit.room(gathering, "Stanza azzurra", 2)
        val luca = retention.registration(gathering, parent, "Luca", "Rossi", notes = Notes("Arachidi", null, null))
        val anna = retention.registration(gathering, parent, "Anna", "Rossi", birth = LocalDate.of(2018, 1, 9))
        val nicolo = retention.paper(gathering, m, "Nicolò", "Ferri")
        val next = retention.september2028()
        val nextRoom = kit.room(next, "Camera 1", 4)
        val sara = retention.registration(next, parent, "Sara", "Neri")

        init {
            kit.put(luca, gathering, one)
            kit.put(anna, gathering, one)
            kit.put(nicolo, gathering, two)
            kit.putTeacher(gathering, m, one)
            kit.putTeacher(gathering, n, two)
            kit.put(sara, next, nextRoom)
            kit.putTeacher(next, m, nextRoom)
        }
    }

    // ---- The rule of the job (criterion 20) ------------------------------------------------------------------------------------------------------

    @Test
    fun `t11 c20 the rule of the rooms is among the rules of the job, after the notes and before the registrations, and has the standard period of zero`() {
        assertEquals(listOf("notes", "rooms", "registrations"), rules.map { it.name }.take(3))
        assertEquals(Period.ZERO, policy.rooms)
        // The day after the last day, and not a month after it: the decision of Matteo on question 4.
        assertEquals(LocalDate.of(2027, 9, 20), policy.roomsDeletionDate(LocalDate.of(2027, 9, 19)))
        assertFalse(policy.roomsExpired(LocalDate.of(2027, 9, 19), LocalDate.of(2027, 9, 19)), "the last day the rooms are as they are")
        assertTrue(policy.roomsExpired(LocalDate.of(2027, 9, 19), LocalDate.of(2027, 9, 20)))
        assertEquals(LocalDate.of(2027, 10, 20), policy.notesDeletionDate(LocalDate.of(2027, 9, 19)), "the notes go a month after, as before")
    }

    @Test
    fun `t11 c20 the job deletes who slept where from the day after the end, children and teachers, and leaves the rooms, empty, and everything else as it was`() {
        val w = Old()
        assertEquals(5, kit.peopleCount(w.gathering), "three children and two teachers of 2027")
        assertEquals(2, kit.peopleCount(w.next), "and a child and a teacher of 2028, that the rule does not touch")

        // The last day, at the end of the night: nothing is deleted, nothing is written in the register.
        val last = job.run(retention.clockAt("2027-09-19T23:59"))
        assertEquals(emptyList(), last.deleted.filter { it.rule == "rooms" })
        assertEquals(5, kit.peopleCount(w.gathering))

        // The first run of the day after the end: everybody is out of the rooms of 2027, and only of them.
        val report = job.run(retention.clockAt("2027-09-20T00:30"))
        assertTrue(report.succeeded, "${report.failed}")
        assertEquals(listOf(Deleted("rooms", 5)), report.deleted.filter { it.rule == "rooms" })
        assertEquals(0, kit.peopleCount(w.gathering))
        assertEquals(listOf("Camera 1" to 4, "Stanza azzurra" to 2), kit.roomsOf(w.gathering), "the rooms stay, with their name and beds, for the copy of the year after")
        assertEquals(2, kit.peopleCount(w.next), "the gathering of the year after is not finished: its people stay")
        assertEquals(4, retention.registrations(), "the registrations stay: their date is another one")
        assertEquals(1, retention.notes(), "the notes go a month after the end")
        assertEquals(listOf("rooms" to 5), retention.log(), "one row of the register, with the number of the rows")
        // Run again the same day: nothing is left, so no row and no line.
        val again = job.run(retention.clockAt("2027-09-20T04:15"))
        assertEquals(emptyList(), again.deleted.filter { it.rule == "rooms" })
        assertEquals(listOf("rooms" to 5), retention.log())
        // A month after the end the notes go too, and the register has its two rules.
        job.run(retention.clockAt("2027-10-20T04:15"))
        assertEquals(listOf("rooms" to 5, "notes" to 1), retention.log())
    }

    @Test
    @CapturesOutput
    fun `t11 c20 the register and the log say the rule and a number of rows and nothing of a person or of a room`(output: CapturedOutput) {
        val w = Old()

        job.run(retention.clockAt("2027-09-20T04:15"))

        val lines = output.all.lines().filter { it.contains("Retention: rule") }.map { it.substringAfter(": ") }
        assertEquals(listOf("Retention: rule rooms deleted 5 rows."), lines)
        for (word in listOf("Luca", "Anna", "Nicolò", "Marta", "Paolo", "Camera 1", "Stanza azzurra", "example.org")) assertFalse(output.all.contains(word), "the log says $word")
        assertEquals(1, dsl.fetchCount(RETENTION_LOG, RETENTION_LOG.RULE.eq("rooms")))
    }

    @Test
    fun `t11 c20 a draft is not touched, as for every other rule, and a gathering that did not end is not either`() {
        val draft = retention.gatherings.make(title = "Convivenza in bozza", start = LocalDate.of(2027, 9, 10), end = LocalDate.of(2027, 9, 12), status = GatheringStatus.draft)
        val room = kit.room(draft, "Camera 1", 4)
        val m = retention.teacher("maestro-m")
        kit.putTeacher(draft, m, room)

        val report = job.run(retention.clockAt("2028-01-01T04:15"))
        assertEquals(emptyList(), report.deleted.filter { it.rule == "rooms" })
        assertEquals(1, kit.peopleCount(draft))
    }

    // ---- What the pages say (criterion 20) ----------------------------------------------------------------------------------------------------

    /** A gathering of the real days: finished [days] days ago, with a room and two people in it, and a teacher who has entered. */
    private fun recent(days: Long): Triple<talita.checkin.CheckinSetup, Long, Long> {
        val setup = kit.start()
        val room = kit.room(setup.id, "Camera 1", 4)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        kit.put(luca, setup.id, room)
        kit.putTeacher(setup.id, setup.paoloId, room)
        kit.endedDaysAgo(setup.id, days)
        return Triple(setup, room, luca)
    }

    @Test
    fun `t11 c20 once the job has deleted who slept where the page says it with the date of the end and that the rooms stay, and the print and the card of Arrivi say it too`() {
        val (setup, room, luca) = recent(1)
        val base = "/convivenza/${setup.id}/camere"
        val before = words(setup.marta.get(base).body())
        assertTrue(before.contains("La convivenza è finita: le camere non si cambiano più.") && before.contains("Luca Rossi"), "before the job: the people, with no actions")

        job.run()

        val html = setup.marta.get(base).body()
        val text = words(html)
        val date = GatheringFormat.date(policy.roomsDeletionDate(LocalDate.now(clock).minusDays(1)))
        assertTrue(text.contains("Chi dormiva dove è stato cancellato il $date, alla fine della convivenza."), text)
        assertTrue(text.contains("Le camere restano, senza nomi: le puoi copiare nella convivenza dell’anno dopo."), text)
        val summary = words(Regex("""<section id="riepilogo".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value)
        assertTrue(summary.contains("Camere 1 Posti letto 4") && !summary.contains("Persone in camera") && !summary.contains("Stampa"), summary)
        assertTrue(text.contains("Camera 1 4 posti") && !text.contains("Vuota"), text)
        assertFalse(text.contains("Luca Rossi") || text.contains("Paolo Verdi"), "nobody is named")
        assertFalse(text.contains("Senza camera"), "no list of who has no room")
        assertFalse(html.contains("<select") || html.contains("Cambia") || html.contains("Aggiungi"), "no actions")
        assertTrue(room > 0 && luca > 0)
        // The print and the card of the child: the same sentence, and nothing to print.
        assertTrue(words(setup.marta.get("$base/stampa").body()).contains("Niente da stampare"))
        assertTrue(words(setup.marta.card(luca)).contains("Chi dormiva dove è stato cancellato il $date."))
        assertEquals(0, kit.peopleCount(setup.id))
        // A request by hand is still refused: the gathering is over.
        assertEquals(409, setup.marta.submit("/profilo", "$base/scegli", mapOf("persona" to "b$luca", "camera" to room.toString())).response.status)
    }

    private fun talita.account.MockBrowser.card(id: Long): String = get("/arrivi/$id").body()

    @Test
    fun `t11 c20 the card of the retention of the detail has the line Chi dorme dove after the notes, with the date of the end, and says it is deleted from that day`() {
        val setup = kit.start()
        kit.room(setup.id, "Camera 1", 4)
        val end = setup.tomorrow
        val date = GatheringFormat.date(policy.roomsDeletionDate(end))
        val html = setup.marta.get("/convivenza/${setup.id}").body()
        val card = words(Regex("""<section[^>]*aria-labelledby="conservazione".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value)

        assertTrue(card.contains("Chi dorme dove Si cancella il $date Alla fine della convivenza, quando le camere si svuotano: serve durante l’evento, anche se di notte succede qualcosa. Le camere restano, senza nomi, per copiarle l’anno dopo."), card)
        assertTrue(card.indexOf("Segnalazioni") < card.indexOf("Chi dorme dove") && card.indexOf("Chi dorme dove") < card.indexOf("Iscrizioni, manleve e registro arrivi"), "between the notes and the registrations")
        // The same day as the end of the gathering plus one: from the date it is said in the past, whatever the job did.
        kit.endedDaysAgo(setup.id, 1)
        val done = words(Regex("""<section[^>]*aria-labelledby="conservazione".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(setup.marta.get("/convivenza/${setup.id}").body())!!.value)
        assertTrue(done.contains("Chi dorme dove È stato cancellato il ${GatheringFormat.date(LocalDate.now(clock))} Alla fine della convivenza. Le camere restano, senza nomi, per copiarle l’anno dopo."), done)
        assertFalse(done.contains("Si cancella il ${GatheringFormat.date(LocalDate.now(clock))} Alla fine"), "in the past tense")
    }

    @Test
    fun `t11 c20 c13 the card of the retention of a gathering that is over and has no rooms has no line Chi dorme dove, which would say that something was deleted and that rooms stay`() {
        val setup = kit.start()
        fun card(): String = words(Regex("""<section[^>]*aria-labelledby="conservazione".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(setup.marta.get("/convivenza/${setup.id}").body())!!.value)

        // Going on and with no rooms yet: the teachers can still write them, and the line says what will happen to who sleeps where.
        assertTrue(card().contains("Chi dorme dove Si cancella il"), card())
        // Over and with no rooms: nothing was written, so nothing was deleted and no room stays for the copy. The other lines are as they were.
        kit.endedDaysAgo(setup.id, 1)
        val over = card()
        assertFalse(over.contains("Chi dorme dove") || over.contains("Le camere restano") || over.contains("cancellato il"), over)
        assertTrue(over.contains("Segnalazioni") && over.contains("Iscrizioni, manleve e registro arrivi") && over.contains("Su carta"), over)
        assertTrue(over.indexOf("Segnalazioni") < over.indexOf("Iscrizioni, manleve e registro arrivi") && over.indexOf("Iscrizioni, manleve e registro arrivi") < over.indexOf("Su carta"), "the order of the others")
        // With a room, even empty as the job leaves it, the line is there and says what happened.
        kit.room(setup.id, "Camera 1", 4)
        assertTrue(card().contains("Chi dorme dove È stato cancellato il ${GatheringFormat.date(LocalDate.now(clock))} Alla fine della convivenza. Le camere restano, senza nomi, per copiarle l’anno dopo."), card())
    }

    @Test
    fun `t11 c20 the notice of the time and the words of the pages follow the policy, so the period is written in one place`() {
        val standard = texts
        val month = RetentionTexts(RetentionPolicy(rooms = Period.ofMonths(1)))
        val end = LocalDate.of(2027, 9, 19)

        assertEquals("alla fine della convivenza", standard.roomsWhen)
        assertEquals("un mese dopo la fine della convivenza", month.roomsWhen)
        assertEquals("Chi dormiva dove è stato cancellato il 20 settembre 2027, alla fine della convivenza.", standard.roomsDeleted(end).main)
        assertEquals("Chi dormiva dove è stato cancellato il 20 ottobre 2027, un mese dopo la fine della convivenza.", month.roomsDeleted(end).main)
        assertEquals("Le camere restano, senza nomi: le puoi copiare nella convivenza dell’anno dopo.", standard.roomsDeleted(end).second)
        assertEquals("Chi dormiva dove è stato cancellato il 20 settembre 2027.", standard.roomsDeletedOn(end))
        assertEquals("Chi dormiva dove è stato cancellato il 20 ottobre 2027.", month.roomsDeletedOn(end))
        // The row of the complete notice has the words of the policy too.
        val row = standard.completeRows().single { it.what == "Chi dorme in quale camera" }
        assertEquals("Alla fine della convivenza. Le camere restano, senza nomi, per la convivenza dell’anno dopo.", row.time)
        assertTrue(month.completeRows().single { it.what == "Chi dorme in quale camera" }.time.startsWith("Un mese dopo la fine della convivenza."))
        // Between the notes and the registrations, as in the card of the detail.
        assertEquals(listOf("Segnalazioni su allergie, intolleranze e farmaci", "Chi dorme in quale camera"), standard.completeRows().take(2).map { it.what })
    }

    @Test
    fun `t11 c20 the complete notice has the line of the rooms in the card of the times, and the notice says who sees the rooms and what they are made of`() {
        val html = mockMvc.get("/informativa").andReturn().response.contentAsString
        val times = words(Regex("""<section id="per-quanto-tempo".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value)

        assertTrue(times.contains("Chi dorme in quale camera Alla fine della convivenza. Le camere restano, senza nomi, per la convivenza dell’anno dopo."), times)
        assertTrue(times.indexOf("Segnalazioni su allergie") < times.indexOf("Chi dorme in quale camera") && times.indexOf("Chi dorme in quale camera") < times.indexOf("Iscrizione, accettazione"), times)
        val text = words(html)
        assertTrue(text.contains("La camera in cui dorme ogni bambino la vedono solo i maestri."), "the notice says who sees the rooms")
        assertTrue(text.contains("la camera in cui dorme ogni bambino, che non dice mai il sesso né il perché di una scelta."), "and what they are made of")
    }

    // ---- The deletion on request (criterion 20, t7 c19) -------------------------------------------------------------------------------------

    private fun service(at: String): ErasureService {
        val fixed = retention.clockAt(at)
        return ErasureService(dsl, fixed, policy, HealthNotes(dsl, fixed, policy), Teachers(dsl, fixed))
    }

    @Test
    fun `t11 c20 the deletion of the data of a child takes it out of every room at once, the ones that stay as the proof and the ones that go with the registration`() {
        val gathering = retention.september2027()
        val june = retention.gatherings.make(title = "Convivenza di giugno 2027", start = LocalDate.of(2027, 6, 18), end = LocalDate.of(2027, 6, 20), status = GatheringStatus.closed)
        val parent = retention.account("genitore-a")
        val m = retention.teacher("maestro-m")
        val oneJune = kit.room(june, "Camera 1", 4)
        val one = kit.room(gathering, "Camera 1", 4)
        val lucaJune = retention.registration(june, parent, "Luca", "Rossi", status = RegistrationStatus.withdrawn)
        val luca = retention.registerAgain(gathering, lucaJune)
        // The one of June was withdrawn and has no presence: it goes. The one of September stays until its date (the proof of the custody) and, before the job, is in a room.
        dsl.update(REGISTRATION).set(REGISTRATION.STATUS, RegistrationStatus.confirmed).where(REGISTRATION.ID.eq(luca)).execute()
        kit.put(luca, gathering, one)
        kit.put(lucaJune, june, oneJune)
        kit.putTeacher(gathering, m, one)
        val anna = retention.registration(gathering, parent, "Anna", "Rossi", birth = LocalDate.of(2018, 1, 9))
        kit.put(anna, gathering, one)

        val done = assertIs<ChildErasure.Done>(service("2027-09-20T01:00").eraseChild(parent, retention.childOf(luca)))

        assertFalse(done.childWent, "the registration of September stays")
        assertNull(kit.roomOfChild(luca), "the registration that stays is taken out of its room")
        assertNull(kit.roomOfChild(lucaJune), "the one that goes takes its room with it")
        assertEquals(1, dsl.fetchCount(REGISTRATION, REGISTRATION.ID.eq(luca)), "the registration stays")
        assertEquals(one, kit.roomOfChild(anna), "a sibling is as it was: only the data of this child are deleted")
        assertEquals(one, kit.roomOfTeacher(gathering, m), "the teachers are not touched")
        assertEquals(0, dsl.fetchCount(HEALTH_NOTE))
        // The row of the register has the rooms in its number: the room row of the registration that stays is a row that was deleted.
        val rows = dsl.select(RETENTION_LOG.ROW_COUNT).from(RETENTION_LOG).where(RETENTION_LOG.RULE.eq("child-request")).fetchSingle(RETENTION_LOG.ROW_COUNT)!!
        assertTrue(rows >= 2, "registration of June, room of September, at least: $rows")
    }

    @Test
    fun `t11 c20 a teacher who is taken away from the teachers, or whose account is deleted, is out of the rooms`() {
        val w = Old()
        val other = retention.teacher("maestro-o", first = "Giulia", last = "Serra")
        kit.putTeacher(w.gathering, other, w.two.let { w.one })
        val clock = retention.clockAt("2027-09-20T01:00")

        // The teacher is taken away from the teachers: the account stays, the name is deleted, and with it the room.
        assertIs<talita.teacher.RemovalResult.Removed>(Teachers(dsl, clock).remove(w.n))
        assertNull(kit.roomOfTeacher(w.gathering, w.n), "taken away from the teachers, out of the room")
        assertEquals(w.one, kit.roomOfTeacher(w.gathering, w.m))
        // The account of a teacher deleted on request: the same.
        assertIs<talita.retention.AccountErasure.Done>(service("2027-09-20T01:00").eraseAccount(other))
        assertNull(kit.roomOfTeacher(w.gathering, other))
        assertEquals(w.one, kit.roomOfTeacher(w.gathering, w.m), "the others stay")
    }
}
