package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.CapturesOutput
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.GatheringFormat
import talita.gathering.body
import talita.gathering.location
import talita.gathering.status
import talita.jooq.enums.RegistrationStatus
import talita.registration.RegistrationFormat
import talita.ui.Alert
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pages of the arrival that is for every day (TALITA-19, criteria 8, 15-17 and 19): the card with the four states of a day, the notices after a touch, the list with its
 * note, the notes of the rows and the sentences for the screen reader, and the prints that count only the days that have come. Friday, Saturday and Sunday, Luca, Sara and
 * Nicolò as in [ArrivalWeekend]; the days of the week are those of the day the test runs. Names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ArrivalDaysPagesTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    private fun weekend(first: Long = 0) = ArrivalWeekend(kit, first)

    private fun htmx(): MockHttpServletRequestDsl.() -> Unit = { header("HX-Request", "true") }

    private fun flash(browser: MockBrowser): String? = ((browser.cookie as MockHttpSession).getAttribute("talita.flash") as? Alert)?.text

    /** A touch of a row of the list, as the page sends it. */
    private fun touch(w: ArrivalWeekend, id: Long, day: LocalDate = w.setup.today, action: String = "presenze", viaHtmx: Boolean = true): MvcResult =
        w.setup.marta.submit("/arrivi?giorno=${w.setup.today}", "/arrivi/$id/$action", mapOf("giorno" to day.toString()), if (viaHtmx) htmx() else ({}))

    /** A touch of the card, as the page sends it (`da=scheda`). */
    private fun fromCard(w: ArrivalWeekend, id: Long, action: String, day: LocalDate = w.setup.today, viaHtmx: Boolean = true): MvcResult =
        w.setup.marta.submit("/arrivi?giorno=${w.setup.today}", "/arrivi/$id/$action", mapOf("giorno" to day.toString(), "da" to "scheda"), if (viaHtmx) htmx() else ({}))

    private fun card(w: ArrivalWeekend, id: Long): String = w.setup.marta.get("/arrivi/$id").body()

    /** The block of a day in the card «Giorni». */
    private fun dayBlock(html: String, day: LocalDate): String =
        Regex("""<li class="py-3\.5[\s\S]*?</li>""").findAll(html).map { it.value }.single { it.contains(">${RegistrationFormat.dayLabel(day)}<") }

    /** The row of a registration in the list, from its `<li>` to the next one. */
    private fun row(html: String, id: Long): String = Regex("""<li id="riga-$id"[\s\S]*?</li>""").find(html)!!.value

    private fun at(marked: OffsetDateTime): String = CheckinFormat.time(marked)

    private fun longDay(day: LocalDate) = CheckinFormat.longDay(day)

    // ---- The card (criterion 15) ------------------------------------------------------------------------------------------------

    @Test
    fun `t19 c15 a row of a day that has not come is Sarà presente, with Togli and its hint and no Segna presente, and the days before it are Presente, with who and when`() {
        val w = weekend(first = 1)
        // Luca arrived on Friday evening, a moment of yesterday: the three rows have the teacher and the moment of that arrival, whatever day they are for.
        val evening = OffsetDateTime.now(clock).minusDays(1)
        for (day in w.days) kit.presence(w.luca, day, w.setup.martaId, evening)
        val time = at(evening)

        val html = card(w, w.luca)

        // Friday: the arrival itself. «Togli» takes Friday, Saturday and Sunday, and says so before the touch.
        val friday = dayBlock(html, w.friday)
        assertTrue(friday.contains("text-success") && friday.contains("Presente"), friday)
        assertTrue(words(friday).contains("Presente Segnata da Marta Bianchi alle $time"), words(friday))
        assertTrue(friday.contains("""aria-label="Togli la presenza di Luca Rossi, da ${longDay(w.friday)} in poi""""), friday)
        assertTrue(friday.contains("""<p class="mt-2 text-sm text-ink-muted">Togli vale anche per ${CheckinFormat.daysInSentence(listOf(w.saturday, w.sunday))}.</p>"""), friday)
        // Saturday, today: «Presente», and the card says where the row comes from.
        val saturday = dayBlock(html, w.saturday)
        assertTrue(saturday.contains("text-success"), saturday)
        assertTrue(words(saturday).contains("Presente Segnata da Marta Bianchi con l’arrivo, ${CheckinFormat.shortDay(w.friday)} alle $time"), words(saturday))
        assertTrue(saturday.contains("""aria-label="Togli la presenza di Luca Rossi, da ${longDay(w.saturday)} in poi""""), saturday)
        assertTrue(saturday.contains(">Togli vale anche per ${GatheringFormat.weekday(w.sunday)}.</p>"), saturday)
        // Sunday has not come: «Sarà presente», in blue, with the icon of the calendar, who and when, «Togli» and from when it counts; no «Segna presente».
        val sunday = dayBlock(html, w.sunday)
        assertTrue(sunday.contains("text-brand") && !sunday.contains("text-success"), sunday)
        assertTrue(words(sunday).contains("Sarà presente Segnata da Marta Bianchi con l’arrivo, ${CheckinFormat.shortDay(w.friday)} alle $time"), words(sunday))
        assertFalse(words(sunday).contains("Presente Segnata"), "a day that has not come is never «Presente»")
        assertTrue(sunday.contains("""<path d="m9 15 2 2 4-4"/>"""), "the icon of the calendar with a check")
        assertTrue(sunday.contains("""aria-label="Togli la presenza di Luca Rossi, ${longDay(w.sunday)}""""), "the last day takes only itself away")
        assertTrue(sunday.contains(">Conta come presente da ${GatheringFormat.weekday(w.sunday)}.</p>"), sunday)
        assertFalse(sunday.contains("Segna presente"), sunday)
        assertFalse(friday.contains("calendar") || friday.contains("""<path d="m9 15 2 2 4-4"/>"""), "only the day that has not come has the icon of the calendar")
    }

    @Test
    fun `t19 c15 a day after today with no row says from when it can be marked, a day that is not after today with no row has Segna presente, and the hint counts only what Togli takes`() {
        val w = weekend(first = 1)
        // Nicolò (Friday and Sunday) was marked on Friday only; today is Saturday, which he does not include.
        kit.presence(w.nicolo, w.friday, w.setup.paoloId)

        val html = card(w, w.nicolo)

        val friday = dayBlock(html, w.friday)
        assertTrue(words(friday).contains("Presente Segnata da Paolo Verdi il ${CheckinFormat.dayAndMonth(w.setup.today)} alle "), words(friday))
        assertFalse(friday.contains("Togli vale anche"), "Saturday has no row: Togli takes one day, and there is no line about it")
        assertFalse(friday.contains("""<p class="mt-2 text-sm text-ink-muted">"""), friday)
        val saturday = dayBlock(html, w.saturday)
        assertTrue(saturday.contains("""aria-label="Segna presente Nicolò Bianchi, ${longDay(w.saturday)}""""), saturday)
        assertTrue(words(saturday).contains("Non previsto"), saturday)
        val sunday = dayBlock(html, w.sunday)
        assertTrue(words(sunday).contains("Si potrà segnare da quel giorno."), sunday)
        assertFalse(sunday.contains("Segna presente") || sunday.contains("Togli") || sunday.contains("Sarà presente"), sunday)
    }

    @Test
    fun `t19 c15 the card of a registration that was withdrawn has the rows of the past with Togli, and no button and no promise for the days after today`() {
        val w = weekend(first = 1)
        val copy = kit.rows.register(w.setup.id, kit.account(), "Elena", "Marino", status = RegistrationStatus.withdrawn)
        kit.presence(copy, w.friday, w.setup.martaId)
        kit.presence(copy, w.saturday, w.setup.martaId)

        val html = card(w, copy)

        assertTrue(dayBlock(html, w.friday).contains("Togli"), "the rows of the past can be taken away, to move them to the other copy")
        assertTrue(dayBlock(html, w.saturday).contains("Togli"))
        val sunday = dayBlock(html, w.sunday)
        assertFalse(sunday.contains("Segna presente") || sunday.contains("Togli") || sunday.contains("Sarà presente") || sunday.contains("Si potrà"), sunday)
    }

    // ---- The notices after a touch of the card (criteria 1-5, 9, 10, 16) ---------------------------------------------------------------

    @Test
    fun `t19 c16 marking on the card says the days that were marked, and the days are the ones of the rows that came out`() {
        val w = weekend()

        val marked = fromCard(w, w.luca, "presenze")

        assertEquals(200, marked.status())
        val html = marked.body()
        assertEquals(w.days, kit.presenceDays(w.luca))
        assertTrue(Regex("""role="status" tabindex="-1" data-fuoco=""""").containsMatchIn(html), "the notice is announced and takes the focus")
        assertTrue(words(html).contains("Presenza segnata per ${CheckinFormat.daysInSentence(w.days)}."), words(html))
        assertTrue(words(html).contains("Presente Segnata da Marta Bianchi alle "), "today")
        assertTrue(words(html).contains("Sarà presente Segnata da Marta Bianchi con l’arrivo, ${CheckinFormat.shortDay(w.friday)} alle ") || words(html).contains("Sarà presente Segnata da Marta Bianchi alle "), words(html))
        assertEquals(2, Regex("Sarà presente").findAll(words(html)).count(), "Saturday and Sunday have not come")
        // One day only: the sentence of always.
        val one = fromCard(w, w.nicolo, "presenze")
        assertEquals(listOf(w.friday), kit.presenceDays(w.nicolo))
        assertTrue(words(one.body()).contains("Presenza segnata."), words(one.body()))
        assertFalse(words(one.body()).contains("Presenza segnata per"), "one day, no days in the sentence")
    }

    @Test
    fun `t19 c16 a day that was not foreseen, and a child in forse, say the days and the notice of today`() {
        val w = weekend(first = 1)
        val elena = kit.rows.register(w.setup.id, kit.account(), "Elena", "Marino", days = mapOf(w.friday to "full", w.saturday to "full"), status = RegistrationStatus.maybe)

        // Nicolò stays on Saturday, which he did not include: Saturday and Sunday are marked, with the notice of attention.
        val notForeseen = fromCard(w, w.nicolo, "presenze", day = w.saturday)

        assertEquals(200, notForeseen.status())
        val html = notForeseen.body()
        assertEquals(listOf(w.saturday, w.sunday), kit.presenceDays(w.nicolo))
        val head = "Presenza segnata per ${CheckinFormat.daysInSentence(listOf(w.saturday, w.sunday))}."
        assertTrue(words(html).contains("$head ${CheckinFormat.longDayCapital(w.saturday)} non era previsto per Nicolò Bianchi: la quota dovuta non cambia finché non correggi l’iscrizione. Correggi l’iscrizione"), words(html))
        assertTrue(html.contains("""href="/iscrizioni/${w.nicolo}/correggi""""))
        assertTrue(Regex("""<div class="[^"]*bg-warning-soft[^"]*" role="alert"""").containsMatchIn(html))
        // «Presenti non previsti» counts Nicolò on Saturday, and not on Sunday, which has not come.
        assertTrue(words(w.setup.marta.get("/arrivi?giorno=${w.saturday}").body()).contains("Presenti non previsti 1"))
        assertTrue(words(w.setup.marta.get("/arrivi?giorno=${w.sunday}").body()).contains("Presenti non previsti Nessuno"))

        // Elena is in forse, and has Friday and Saturday: marked on Friday she is confirmed, and the notice says both things.
        val confirmed = fromCard(w, elena, "presenze", day = w.friday)
        assertEquals("confirmed", kit.statusOf(elena))
        assertTrue(
            words(confirmed.body()).contains("Presenza segnata per ${CheckinFormat.daysInSentence(listOf(w.friday, w.saturday))}. L’iscrizione di Elena Marino era in forse: adesso è confermata."),
            words(confirmed.body()),
        )
    }

    @Test
    fun `t19 c16 Togli on the card says the days that went, a day after today can be taken away alone, and a day with no row is already taken away`() {
        val w = weekend(first = 1)
        for (day in w.days) kit.presence(w.luca, day, w.setup.martaId)

        // Sunday alone: it has not come, and Saturday stays.
        val sunday = fromCard(w, w.luca, "presenze/togli", day = w.sunday)
        assertEquals(200, sunday.status())
        assertEquals(listOf(w.friday, w.saturday), kit.presenceDays(w.luca))
        assertTrue(words(sunday.body()).contains("Presenza tolta."), words(sunday.body()))
        assertFalse(words(sunday.body()).contains("Presenza tolta per"))
        // Taking away Sunday again, which has no row: the same answer as for any day, informative, with a 200.
        val again = fromCard(w, w.luca, "presenze/togli", day = w.sunday)
        assertEquals(200, again.status())
        assertTrue(words(again.body()).contains("La presenza era già stata tolta."), words(again.body()))
        // From Friday: the three rows, and the notice says the days.
        kit.presence(w.luca, w.sunday, w.setup.martaId)
        val all = fromCard(w, w.luca, "presenze/togli", day = w.friday)
        assertEquals(emptyList(), kit.presenceDays(w.luca))
        assertTrue(words(all.body()).contains("Presenza tolta per ${CheckinFormat.daysInSentence(w.days)}."), words(all.body()))
        assertTrue(all.body().contains("""aria-label="Segna presente Luca Rossi, ${longDay(w.friday)}""""), "Friday is to be marked again")
    }

    @Test
    fun `t19 c16 without JavaScript the card leads back to the card with the notice of the days`() {
        val w = weekend()

        val plain = fromCard(w, w.luca, "presenze", viaHtmx = false)

        assertEquals(303, plain.status())
        assertEquals("/arrivi/${w.luca}?giorno=${w.setup.today}", plain.location())
        val page = w.setup.marta.get(plain.location()!!).body()
        assertTrue(words(page).contains("Presenza segnata per ${CheckinFormat.daysInSentence(w.days)}."), "the notice of the days is on the page the redirect leads to")
        val gone = fromCard(w, w.luca, "presenze/togli", day = w.saturday, viaHtmx = false)
        assertEquals(303, gone.status())
        assertTrue(words(w.setup.marta.get(gone.location()!!).body()).contains("Presenza tolta per ${CheckinFormat.daysInSentence(listOf(w.saturday, w.sunday))}."))
    }

    // ---- The list (criteria 8, 16, 17) ---------------------------------------------------------------------------------------------

    @Test
    fun `t19 c17 the rule of the touches is written once over the children expected, where a touch can be made and a day follows, and nowhere else`() {
        val w = weekend(first = 1)

        val saturday = w.setup.marta.get("/arrivi?giorno=${w.saturday}").body()
        val friday = w.setup.marta.get("/arrivi?giorno=${w.friday}").body()
        val sunday = w.setup.marta.get("/arrivi?giorno=${w.sunday}").body()

        val rule = "Segna presente e Togli valgono anche per i giorni dopo."
        assertEquals(1, Regex(Regex.escape(rule)).findAll(words(saturday)).count(), "today, a day follows")
        assertTrue(words(saturday).contains("Attesi ${CheckinFormat.shortDay(w.saturday)} 2 $rule"), words(saturday))
        assertTrue(words(friday).contains(rule), "a past day can be touched too, and Saturday and Sunday follow it")
        assertFalse(words(sunday).contains(rule), "a day that has not come has nothing to touch")
        // The last day of the gathering: the touches are for that day only, and nothing says otherwise.
        val last = kit.start(first = -2, last = 0)
        kit.rows.register(last.id, kit.account(), "Luca", "Rossi")
        assertFalse(words(last.marta.get("/arrivi").body()).contains(rule), "the last day has no day after it")
    }

    @Test
    fun `t19 c08 c17 on a day that has not come the list has nobody present, the notice says who will be there, and the rows are links`() {
        val w = weekend(first = 1)
        checkinsOf(w)

        val html = w.setup.marta.get("/arrivi?giorno=${w.sunday}").body()

        val text = words(html)
        assertTrue(
            text.contains(
                "${CheckinFormat.longDayCapital(w.sunday)} non è ancora arrivato: le presenze si segnano da quel giorno. Oggi è ${longDay(w.setup.today)}. " +
                    "Chi è già arrivato e resta sarà presente anche ${GatheringFormat.weekday(w.sunday)}, se non lo togli dalla sua scheda. Vai a oggi",
            ),
            text,
        )
        assertTrue(text.contains("Presenti Nessuno su 2 attesi"), text)
        assertFalse(html.contains("bg-success-soft"), "nobody is green")
        assertFalse(Regex(""">(?:<svg[\s\S]*?</svg>)?Presente</span>""").containsMatchIn(html), "no row has the label Presente")
        assertFalse(html.contains("Segna presente") || html.contains("Togli"), "the rows are read only")
    }

    /** Luca arrived on Friday (the three rows); Sara has Friday and Saturday. */
    private fun checkinsOf(w: ArrivalWeekend) {
        for (day in w.days) kit.presence(w.luca, day, w.setup.martaId)
        kit.presence(w.sara, w.friday, w.setup.martaId)
        kit.presence(w.sara, w.saturday, w.setup.martaId)
    }

    @Test
    fun `t19 c08 c16 the list of today has who arrived with no other touch, and the button of Togli says from which day it takes away`() {
        val w = weekend(first = 1)
        checkinsOf(w)

        val html = w.setup.marta.get("/arrivi").body()

        // Luca and Sara are present today, and the summary counts them with nobody touched today.
        assertTrue(words(html).contains("Presenti 2 su 2 attesi"), words(html))
        for (id in listOf(w.luca, w.sara)) assertTrue(Regex("""<li id="riga-$id"[^>]*bg-success-soft""").containsMatchIn(html), "$id is present")
        // Luca's «Togli» takes today and Sunday; Sara's takes Saturday only (Sunday is not hers).
        assertTrue(row(html, w.luca).contains("""<span class="sr-only"> la presenza di Luca Rossi, da ${longDay(w.saturday)} in poi</span>"""), row(html, w.luca))
        assertTrue(row(html, w.sara).contains("""<span class="sr-only"> la presenza di Sara Neri, ${longDay(w.saturday)}</span>"""), row(html, w.sara))
        // Nicolò has nothing: Saturday is not his, so he is among the ones who were not expected.
        assertTrue(words(html).contains("Non previsti ${CheckinFormat.shortDay(w.saturday)} 1"), words(html))
    }

    @Test
    fun `t19 c16 a touch on Segna presente writes the days and the row does not grow, the sentence for the screen reader has the days, and a child with other rows has a note`() {
        val w = weekend()

        val first = touch(w, w.luca)

        assertEquals(200, first.status())
        val html = first.body()
        assertEquals(w.days, kit.presenceDays(w.luca))
        assertTrue(html.contains("""<p hx-swap-oob="innerHTML:#esito">Presenza segnata per ${CheckinFormat.daysInSentence(w.days)}: Luca Rossi.</p>"""), html.takeLast(300))
        assertFalse(html.contains("Segnata anche per"), "the row does not grow at the first touch")
        assertFalse(Regex("""<p class="[^"]*basis-full""").containsMatchIn(html), "no note on the row")
        assertTrue(Regex("""^\s*<li id="riga-${w.luca}"[^>]*bg-success-soft""").containsMatchIn(html))
        // A child who has other rows: today is Saturday, Luca has the row of Friday (he was taken away from Saturday on by mistake), and Saturday is marked again.
        val today = weekend(first = 1)
        kit.presence(today.luca, today.friday, today.setup.martaId)
        val again = touch(today, today.luca)
        val text = words(again.body())
        assertEquals(listOf(today.friday, today.saturday, today.sunday), kit.presenceDays(today.luca))
        assertTrue(text.contains("Segnata anche per ${GatheringFormat.weekday(today.sunday)}."), text)
        assertTrue(again.body().contains("""<p hx-swap-oob="innerHTML:#esito">Presenza segnata per ${CheckinFormat.daysInSentence(listOf(today.saturday, today.sunday))}: Luca Rossi.</p>"""), again.body().takeLast(300))
        assertTrue(Regex("""<p class="[^"]*bg-brand-soft[^"]*"""").containsMatchIn(again.body()), "an informative note, not a warning")
    }

    @Test
    fun `t19 c16 a touch on Togli takes the days away, the row goes back to Segna presente with a note, and Togli of the last day has no note`() {
        val w = weekend(first = 1)
        for (day in w.days) kit.presence(w.luca, day, w.setup.martaId)

        val gone = touch(w, w.luca, action = "presenze/togli")

        assertEquals(200, gone.status())
        val html = gone.body()
        assertEquals(listOf(w.friday), kit.presenceDays(w.luca))
        assertTrue(words(html).contains("Tolta anche per ${GatheringFormat.weekday(w.sunday)}."), words(html))
        assertTrue(html.contains("""<p hx-swap-oob="innerHTML:#esito">Presenza tolta per ${CheckinFormat.daysInSentence(listOf(w.saturday, w.sunday))}: Luca Rossi.</p>"""), html.takeLast(300))
        assertTrue(html.contains("Segna presente") && !html.contains("bg-success-soft"), "the row is to be marked again")
        // A day with nothing after it: one day, no note.
        kit.presence(w.luca, w.saturday, w.setup.martaId)
        val last = touch(w, w.luca, action = "presenze/togli")
        assertTrue(last.body().contains("""<p hx-swap-oob="innerHTML:#esito">Presenza tolta: Luca Rossi.</p>"""), last.body().takeLast(300))
        assertFalse(words(last.body()).contains("Tolta anche per"))
    }

    @Test
    fun `t19 c16 without JavaScript a touch leads back to the row, and the sentence is at the top only when the row has a note`() {
        val w = weekend(first = 1)
        for (day in w.days) kit.presence(w.luca, day, w.setup.martaId)

        // Taking away several days: the row has a note, so the sentence is at the top of the list.
        val gone = touch(w, w.luca, action = "presenze/togli", viaHtmx = false)
        assertEquals(303, gone.status())
        assertEquals("/arrivi?giorno=${w.setup.today}#riga-${w.luca}", gone.location())
        assertEquals("Presenza tolta per ${CheckinFormat.daysInSentence(listOf(w.saturday, w.sunday))}: Luca Rossi.", flash(w.setup.marta))
        assertTrue(words(w.setup.marta.get("/arrivi?giorno=${w.setup.today}").body()).contains("Presenza tolta per"), "shown once at the top")
        assertNull(flash(w.setup.marta))
        // A first touch on a child with no rows: the row has no note, and nothing is said at the top.
        val first = touch(w, w.sara, viaHtmx = false)
        assertEquals(303, first.status())
        assertNull(flash(w.setup.marta), "a touch that goes as the person thought says nothing at the top")
    }

    // ---- The prints (criteria 8, 17) ------------------------------------------------------------------------------------------------

    @Test
    fun `t19 c17 the register has the X only for the days that have come, with the same presents as the list, and the list for the venue of a day has them`() {
        val w = weekend(first = 1)
        checkinsOf(w)

        val html = w.setup.marta.get("/arrivi/registro").body()

        // Luca: Friday and Saturday have the X, Sunday has not come. Sara: Friday and Saturday.
        val lucaRow = Regex("""<tr[^>]*>(?:(?!</tr>)[\s\S])*Luca Rossi[\s\S]*?</tr>""").find(html)!!.value
        assertEquals(2, Regex("""class="casella[^"]*"[^>]*>(?:(?!</td>)[\s\S])*X""").findAll(lucaRow).count(), lucaRow)
        // The venue: Saturday has Luca and Sara; Sunday, which has not come, has nobody and says so.
        val saturday = words(w.setup.marta.get("/arrivi/elenco?giorno=${w.saturday}").body())
        assertTrue(saturday.contains("Bambini presenti ${longDay(w.saturday)} · 2"), saturday)
        assertTrue(saturday.contains("Neri Sara") && saturday.contains("Rossi Luca"), saturday)
        val sunday = words(w.setup.marta.get("/arrivi/elenco?giorno=${w.sunday}").body())
        assertTrue(sunday.contains("Nessun bambino presente ${longDay(w.sunday)}"), sunday)
        assertFalse(sunday.contains("Rossi Luca"), sunday)
    }

    // ---- The log (criterion 19) -----------------------------------------------------------------------------------------------------

    @Test
    @CapturesOutput
    fun `t19 c19 the log has no name while the teachers mark and take away the arrival on the list and on the card, with or without JavaScript`(output: CapturedOutput) {
        val w = weekend(first = 1)
        val zeffirino = kit.rows.register(w.setup.id, kit.account(), "Zeffirino", "Quagliotti", LocalDate.of(2019, 1, 9), phone = "333 777 8888")
        val ludovica = kit.rows.register(w.setup.id, kit.account(), "Ludovica", "Pellegrini", LocalDate.of(2016, 5, 5), phone = "333 666 9999")

        for (path in listOf("/arrivi", "/arrivi?giorno=${w.sunday}", "/arrivi/$zeffirino", "/arrivi/registro", "/arrivi/elenco", "/arrivi/elenco?giorno=${w.sunday}")) w.setup.marta.get(path)
        touch(w, zeffirino, day = w.friday)
        fromCard(w, zeffirino, "presenze/togli", day = w.saturday)
        fromCard(w, zeffirino, "presenze", day = w.saturday, viaHtmx = false)
        touch(w, ludovica, action = "presenze", viaHtmx = false)
        touch(w, ludovica, day = w.saturday, action = "presenze/togli", viaHtmx = false)
        fromCard(w, ludovica, "presenze/togli", day = w.sunday)

        val log = output.all
        for (secret in listOf("Zeffirino", "Quagliotti", "Ludovica", "Pellegrini", "333 777", "3337778888", "333 666", "3336669999", "Marta", "Bianchi", "2019-01-09")) {
            assertFalse(log.contains(secret), "the log has '$secret': ${log.lines().filter { it.contains(secret) }.take(3)}")
        }
    }
}
