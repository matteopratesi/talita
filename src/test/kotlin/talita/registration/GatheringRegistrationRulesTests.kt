package talita.registration

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.LockWait
import talita.TestcontainersConfiguration
import talita.gathering.DatesResult
import talita.gathering.Fees
import talita.gathering.FeesResult
import talita.gathering.Gathering
import talita.gathering.GatheringInfo
import talita.gathering.GatheringState
import talita.gathering.Gatherings
import talita.gathering.RegistrationUsage
import talita.gathering.body
import talita.gathering.errorOf
import talita.gathering.status
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the registrations ask of the gathering (criteria 29-32, rules R1-R4 of TALITA-4): who can do what in every state with a clock that does not move,
 * the fees that registrations use, the days that registrations have, and the text that stays as it was accepted. The forms are the ones of
 * TALITA-4, with the sentences of the design of TALITA-5. Names and dates are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class GatheringRegistrationRulesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val gatherings: Gatherings,
    @Autowired private val usage: RegistrationUsage,
    @Autowired private val arrivals: talita.gathering.ArrivalUsage,
) {

    private val kit = RegistrationTestKit(mockMvc, tokens, dsl, clock)
    private val rows = RegistrationRows(dsl, clock)

    private fun text(html: String): String = talita.gathering.text(html).replace(' ', ' ')

    private fun parentAccount(): Long {
        val email = talita.Addresses.unique("genitore")
        kit.kit.parent(email)
        return kit.accountId(email)
    }

    private fun saveFees(setup: Setup, full: String = "60", daily: String = "25", lunch: String = "10") =
        setup.marta.submit("/convivenza/${setup.id}/quote", "/convivenza/${setup.id}/quote", mapOf("fullFee" to full, "dailyFee" to daily, "lunchOnlyFee" to lunch))

    private fun saveDates(setup: Setup, start: LocalDate, end: LocalDate) =
        setup.marta.submit("/convivenza/${setup.id}/date", "/convivenza/${setup.id}/date", mapOf("title" to "Convivenza di settembre 2027", "startDate" to start.toString(), "endDate" to end.toString(), "place" to "Casa per ritiri di esempio"))

    // ---- R1: the states (criterion 29) ------------------------------------------------------------------------------------------------

    private fun info(state: GatheringState, start: LocalDate, end: LocalDate, today: LocalDate): GatheringInfo {
        val gathering = Gathering(1, "codice", "Convivenza di settembre 2027", start, end, "Casa per ritiri di esempio", Fees(6000, 2500, 1000), null, null, state)
        return GatheringInfo(
            1, "codice", gathering.title, start, end, gathering.days, gathering.place, gathering.fees, emptyList(), state, gathering.isOver(today), gathering.registrationsPossible(today),
        )
    }

    @Test
    fun `t5 c29 with a fixed clock the parent registers and changes while the registrations are open and the last day has not passed, and the day after only the teachers can`() {
        val start = LocalDate.of(2027, 9, 17)
        val end = LocalDate.of(2027, 9, 19)

        // A draft: nothing, for anybody.
        val draft = info(GatheringState.DRAFT, start, end, LocalDate.of(2027, 9, 1))
        assertEquals(listOf(false, false, false), listOf(Rules.parentCanChange(draft), Rules.parentCanChangeHealth(draft), Rules.teacherCanChange(draft)))
        // Open, on the last day: the parent still registers and changes (the last day is not passed).
        val lastDay = info(GatheringState.OPEN, start, end, end)
        assertEquals(listOf(true, true, true), listOf(Rules.parentCanChange(lastDay), Rules.parentCanChangeHealth(lastDay), Rules.teacherCanChange(lastDay)))
        // Open, from the 20th of September (the day of Rome): it is over, though nobody closed it.
        val over = info(GatheringState.OPEN, start, end, LocalDate.of(2027, 9, 20))
        assertTrue(over.over)
        assertEquals(listOf(false, false, true), listOf(Rules.parentCanChange(over), Rules.parentCanChangeHealth(over), Rules.teacherCanChange(over)))
        // Closed and not over: the parent looks and changes the notes; over: not even them.
        val closed = info(GatheringState.CLOSED, start, end, LocalDate.of(2027, 9, 18))
        assertEquals(listOf(false, true, true), listOf(Rules.parentCanChange(closed), Rules.parentCanChangeHealth(closed), Rules.teacherCanChange(closed)))
        val closedOver = info(GatheringState.CLOSED, start, end, LocalDate.of(2027, 9, 20))
        assertEquals(listOf(false, false, true), listOf(Rules.parentCanChange(closedOver), Rules.parentCanChangeHealth(closedOver), Rules.teacherCanChange(closedOver)))
    }

    @Test
    fun `t5 c29 the pages say the same, open and not over the parent changes, and a gathering that is over is not changed by anyone but the teachers`() {
        val setup = kit.start()
        val owner = kit.parent("genitore-stati")
        assertEquals(303, kit.register(owner, setup, kit.form(kit.child(0, "Luca"))).status())
        val id = kit.registrations(setup.id).single().id!!
        assertEquals(200, owner.get("/iscrizioni/$id/modifica").status())

        kit.makeOver(setup.id)
        assertEquals(409, owner.get("/iscrizioni/$id/modifica").status())
        assertEquals(200, setup.marta.get("/iscrizioni/$id/correggi").status())
        assertEquals(200, setup.marta.get("/iscrizioni/nuova?convivenza=${setup.id}").status())
    }

    // ---- R2: the fees (criterion 30) ----------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c30 the form of the fees says how many registrations are recalculated, only the ones that are not withdrawn, and says nothing when there are none`() {
        val setup = kit.start()
        val a = parentAccount()
        val none = text(setup.marta.get("/convivenza/${setup.id}/quote").body())
        assertFalse(none.contains("Hai già"), none)

        rows.register(setup.id, a, "Luca", "Rossi")
        assertTrue(text(setup.marta.get("/convivenza/${setup.id}/quote").body()).contains("Hai già 1 iscrizione: se cambi le quote, la sua quota dovuta si ricalcola. Quello che è già stato pagato non cambia."))
        rows.register(setup.id, a, "Anna", "Rossi", LocalDate.of(2019, 1, 9), status = RegistrationStatus.maybe)
        rows.register(setup.id, a, "Zoe", "Zani", LocalDate.of(2014, 9, 9), status = RegistrationStatus.withdrawn)
        val page = text(setup.marta.get("/convivenza/${setup.id}/quote").body())
        assertTrue(page.contains("Hai già 2 iscrizioni: se cambi le quote, la quota dovuta di ognuna si ricalcola. Quello che è già stato pagato non cambia."), page)
        assertFalse(page.contains("Hai già 3"), "the withdrawn do not count")
    }

    @Test
    fun `t5 c30 changing the fees changes what is due for every registration that is not withdrawn, nothing of it is saved`() {
        val setup = kit.start()
        val owner = kit.parent("genitore-quote")
        assertEquals(303, kit.register(owner, setup, kit.form(kit.child(0, "Luca"), kit.child(1, "Anna", presence = "days", more = arrayOf("day.sat" to "full", "day.sun" to "lunch")))).status())
        assertTrue(text(owner.get("/iscrizioni").body()).contains("Totale 95 €"))

        assertEquals(303, saveFees(setup, full = "80", daily = "30", lunch = "5").status())

        val page = text(owner.get("/iscrizioni").body())
        assertTrue(page.contains("Quota 80 €"), page)
        assertTrue(page.contains("Quota 35 €"), page)
        assertTrue(page.contains("Totale 115 €"), page)
        assertTrue(text(setup.marta.get("/iscrizioni").body()).contains("Quota delle confermate 115 €"))
        // The tables of the registrations have no amount at all: it is worked out every time (a column with `fee` or `cents` in them would be saved).
        assertTrue(dsl.meta().getTables("registration").single().fields().none { it.name.contains("cents") || it.name.contains("fee") || it.name.contains("quota") })
    }

    @Test
    fun `t5 c30 a fee that registrations use cannot be emptied, the error says how many use it and to write 0, and zero is allowed`() {
        val setup = kit.start()
        val a = parentAccount()
        rows.register(setup.id, a, "Luca", "Rossi", days = mapOf(setup.friday to "full"))
        rows.register(setup.id, a, "Anna", "Rossi", LocalDate.of(2019, 1, 9), days = mapOf(setup.saturday to "full", setup.sunday to "lunch"))
        rows.register(setup.id, a, "Sole", "Verdi", LocalDate.of(2017, 2, 3), days = mapOf(setup.sunday to "full"))
        rows.register(setup.id, a, "Marco", "Verdi", LocalDate.of(2015, 6, 1))
        rows.register(setup.id, a, "Zoe", "Zani", LocalDate.of(2014, 9, 9), status = RegistrationStatus.withdrawn, days = mapOf(setup.friday to "lunch"))

        val daily = saveFees(setup, daily = "")
        assertEquals(422, daily.status())
        assertEquals("La usano 3 iscrizioni: non puoi lasciarla vuota. Se diventa gratuita, scrivi 0.", errorOf(daily.body(), "quota-giornaliera"))
        assertEquals(null, errorOf(daily.body(), "quota-pranzo"))
        val lunch = saveFees(setup, lunch = "")
        assertEquals(422, lunch.status())
        assertEquals("La usa 1 iscrizione: non puoi lasciarla vuota. Se diventa gratuita, scrivi 0.", errorOf(lunch.body(), "quota-pranzo"))
        val both = saveFees(setup, daily = "", lunch = "")
        assertEquals(422, both.status())
        assertTrue(text(both.body()).contains("Controlla i 2 campi segnati in rosso."))
        assertEquals(2500, gatherings.find(setup.id)!!.fees.dailyCents, "nothing was changed")
        assertEquals(1000, gatherings.find(setup.id)!!.fees.lunchOnlyCents)
        // Zero is a fee: «Gratuita».
        assertEquals(303, saveFees(setup, daily = "0", lunch = "0").status())
        assertEquals(0, gatherings.find(setup.id)!!.fees.dailyCents)
    }

    @Test
    fun `t5 c30 a fee that only registrations that are withdrawn or of every day had can be emptied`() {
        val setup = kit.start()
        val a = parentAccount()
        rows.register(setup.id, a, "Luca", "Rossi")
        rows.register(setup.id, a, "Zoe", "Zani", LocalDate.of(2014, 9, 9), status = RegistrationStatus.withdrawn, days = mapOf(setup.friday to "full", setup.sunday to "lunch"))

        assertEquals(303, saveFees(setup, daily = "", lunch = "").status())

        val fees = gatherings.find(setup.id)!!.fees
        assertNull(fees.dailyCents)
        assertNull(fees.lunchOnlyCents)
        assertEquals(FeesResult.NotFound, gatherings.saveFees(-1, Fees(6000, null, null), usage))
    }

    // ---- R3: the days (criterion 31) ----------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c31 a day can be added, and the registrations of every day include it`() {
        val setup = kit.start()
        val owner = kit.parent("genitore-giorni")
        assertEquals(303, kit.register(owner, setup, kit.form(kit.child(0, "Luca"))).status())
        val more = setup.sunday.plusDays(1)

        val form = setup.marta.get("/convivenza/${setup.id}/date").body()
        assertTrue(text(form).contains("I giorni di presenza sono tutti quelli dal primo all’ultimo, al massimo 7. Un giorno scelto da un’iscrizione non si può togliere. Se aggiungi un giorno, chi viene «tutti i giorni» viene anche quel giorno."), text(form))
        assertEquals(303, saveDates(setup, setup.friday, more).status())

        assertTrue(text(owner.get("/iscrizioni").body()).contains("Tutti i giorni (${talita.gathering.GatheringFormat.range(setup.friday, more)})"))
        assertEquals(mapOf(more to 1), usage.dayUsers(dsl, setup.id, listOf(more)))
    }

    @Test
    fun `t5 c31 without registrations the form says the days that are chosen cannot be taken away, and does not talk of adding`() {
        val setup = kit.start()

        val text = text(setup.marta.get("/convivenza/${setup.id}/date").body())

        assertTrue(text.contains("I giorni di presenza sono tutti quelli dal primo all’ultimo, al massimo 7. Un giorno scelto da un’iscrizione non si può togliere."), text)
        assertFalse(text.contains("Se aggiungi un giorno"))
    }

    @Test
    fun `t5 c31 a day that a registration has cannot be taken away, with one sentence for each day under the field that loses it`() {
        val setup = kit.start()
        val a = parentAccount()
        // Six registrations of every day, one of Saturday only, one withdrawn that had Sunday.
        repeat(6) { rows.register(setup.id, a, "Bambino$it", "Rossi", LocalDate.of(2016, 4, 4).plusDays(it.toLong())) }
        rows.register(setup.id, a, "Anna", "Verdi", LocalDate.of(2019, 1, 9), days = mapOf(setup.saturday to "full"))
        rows.register(setup.id, a, "Zoe", "Zani", LocalDate.of(2014, 9, 9), status = RegistrationStatus.withdrawn, days = mapOf(setup.sunday to "lunch"))

        // The first day goes: 6 of every day.
        val first = saveDates(setup, setup.saturday, setup.sunday)
        assertEquals(422, first.status())
        assertEquals("Non puoi togliere venerdì ${talita.gathering.GatheringFormat.date(setup.friday)}: l’hanno scelto 6 iscrizioni.", errorOf(first.body(), "primo-giorno"))
        assertEquals(null, errorOf(first.body(), "ultimo-giorno"))
        // The last day goes too: one sentence for each, each under its field.
        val both = saveDates(setup, setup.saturday, setup.saturday)
        assertEquals(422, both.status())
        assertEquals("Non puoi togliere venerdì ${talita.gathering.GatheringFormat.date(setup.friday)}: l’hanno scelto 6 iscrizioni.", errorOf(both.body(), "primo-giorno"))
        assertEquals("Non puoi togliere domenica ${talita.gathering.GatheringFormat.date(setup.sunday)}: l’hanno scelto 6 iscrizioni.", errorOf(both.body(), "ultimo-giorno"))
        assertEquals(setup.friday, gatherings.find(setup.id)!!.startDate, "nothing was changed")
        // With only one registration that has the day, the sentence is in the singular.
        dsl.deleteFrom(talita.jooq.tables.references.REGISTRATION_DAY).execute()
        dsl.deleteFrom(talita.jooq.tables.references.HEALTH_NOTE).execute()
        dsl.deleteFrom(talita.jooq.tables.references.REGISTRATION).where(talita.jooq.tables.references.REGISTRATION.STATUS.ne(RegistrationStatus.withdrawn)).execute()
        rows.register(setup.id, a, "Anna", "Verdi", LocalDate.of(2019, 1, 9), days = mapOf(setup.saturday to "full"))
        val one = saveDates(setup, setup.sunday, setup.sunday)
        assertEquals(422, one.status())
        assertEquals("Non puoi togliere sabato ${talita.gathering.GatheringFormat.date(setup.saturday)}: l’ha scelto 1 iscrizione.", errorOf(one.body(), "primo-giorno"))
    }

    @Test
    fun `t5 c31 with registrations that are only withdrawn, or that did not choose the day, the day is taken away`() {
        val setup = kit.start()
        val a = parentAccount()
        rows.register(setup.id, a, "Zoe", "Zani", LocalDate.of(2014, 9, 9), status = RegistrationStatus.withdrawn)
        rows.register(setup.id, a, "Anna", "Verdi", LocalDate.of(2019, 1, 9), days = mapOf(setup.saturday to "full"))

        assertEquals(303, saveDates(setup, setup.saturday, setup.saturday).status())
        assertEquals(setup.saturday, gatherings.find(setup.id)!!.startDate)
        assertEquals(setup.saturday, gatherings.find(setup.id)!!.endDate)
    }

    @Test
    fun `t5 c31 a registration that is being made and the change of the dates wait for each other`() {
        val setup = kit.start()
        val a = parentAccount()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val sharing = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val registering = pool.submit {
                dsl.transaction { configuration ->
                    val tx = DSL.using(configuration)
                    // The registration that is being made holds the row of the gathering for sharing, and writes its row before it ends.
                    tx.selectFrom(GATHERING).where(GATHERING.ID.eq(setup.id)).forShare().fetch()
                    sharing.countDown()
                    finish.await(20, TimeUnit.SECONDS)
                    RegistrationRows(tx, clock).register(setup.id, a, "Luca", "Rossi")
                }
            }
            assertTrue(sharing.await(10, TimeUnit.SECONDS))
            val changing = LockWait.awaitBlocked(dsl, "the change of the dates should wait for the registration that is being made") {
                pool.submit<DatesResult> { gatherings.saveDates(setup.id, "Convivenza di settembre 2027", setup.saturday, setup.sunday, "Casa per ritiri di esempio", usage, arrivals) }
            }
            finish.countDown()
            registering.get(20, TimeUnit.SECONDS)
            val result = changing.get(20, TimeUnit.SECONDS)
            assertTrue(result is DatesResult.DaysInUse, "after the wait the change sees the registration: $result")
            assertEquals(setup.friday, gatherings.find(setup.id)!!.startDate)
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- R4: the custodians (criterion 32) -------------------------------------------------------------------------------------------------

    @Test
    fun `t5 c32 the text that was accepted stays as it was when a custodian leaves, and the form of the custodians says so`() {
        val setup = kit.start()
        val owner = kit.parent("genitore-affidatari")
        assertEquals(303, kit.register(owner, setup, kit.form(kit.child(0, "Luca"))).status())
        val id = kit.registrations(setup.id).single().id!!
        val accepted = kit.registrations(setup.id).single().custodyText!!
        assertTrue(accepted.contains("Marta Bianchi e Paolo Verdi"))

        val form = text(setup.marta.get("/convivenza/${setup.id}/affidatari").body())
        assertTrue(form.contains("I loro nomi compaiono nel testo dell’affidamento che i genitori accettano con l’iscrizione. Non compaiono nella pagina per i genitori. Le iscrizioni già fatte conservano i nomi che hanno accettato."), form)

        // Paolo leaves the custodians, and then the title, the dates and the place change: the detail still shows what was accepted.
        assertEquals(303, setup.marta.submit("/convivenza/${setup.id}/affidatari", "/convivenza/${setup.id}/affidatari", mapOf("custodians" to setup.martaId.toString())).status())
        dsl.update(GATHERING).set(GATHERING.TITLE, "Un altro titolo").set(GATHERING.PLACE, "Un’altra casa").execute()
        assertEquals(accepted, kit.registrations(setup.id).single().custodyText)
        val detail = text(setup.marta.get("/iscrizioni/$id").body())
        assertTrue(detail.contains("alla responsabilità di Marta Bianchi e Paolo Verdi per la durata della convivenza «Convivenza di settembre 2027»"), detail)
        assertFalse(text(setup.marta.get("/convivenza/${setup.id}").body()).contains("Paolo Verdi"), "he is not a custodian any more")
        // A new registration has the new custodians.
        val other = kit.parent("genitore-nuovo")
        assertEquals(303, kit.register(other, setup, kit.form(kit.child(0, "Anna", "Rossi", "9", "1", "2019"))).status())
        assertFalse(kit.registrations(setup.id).last().custodyText!!.contains("Paolo Verdi"))
        // And even when the person is not a teacher any more, the text is the same.
        talita.account.TestAccounts.removeTeacher(dsl, kit.emailOf(setup.paoloId))
        assertEquals(accepted, kit.registrations(setup.id).first().custodyText)
        assertTrue(text(setup.marta.get("/iscrizioni/$id").body()).contains("Paolo Verdi"))
        assertEquals(GatheringStatus.`open`, kit.kit.gatherings.statusOf(setup.id))
    }
}
