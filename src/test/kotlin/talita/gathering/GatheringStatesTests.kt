package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The steps of the state (criteria 9-15): the dialogs, the list of what is missing, the refused steps, the same step asked twice, and
 * the one gathering that is not closed.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class GatheringStatesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
    @Autowired private val service: Gatherings,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings

    private fun MockBrowser.step(id: Long, action: String, from: String?) =
        submit("/profilo", "/convivenza/$id/$action", if (from == null) emptyMap() else mapOf("from" to from))

    private fun state(id: Long) = gatherings.statusOf(id)

    // ---- Opening (criteria 9 and 10) ------------------------------------------------------------------------------------------

    @Test
    fun `t4 c09 a complete draft has a dialog that says the page becomes visible to who has the link, and confirmed the state is Iscrizioni aperte`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, custodians = listOf(me))

        val detail = browser.get("/convivenza/$id").body()

        assertTrue(Regex("""<a href="/convivenza/$id/apri" data-apri="dialogo-apri"[^>]*>Apri le iscrizioni</a>""").containsMatchIn(detail), "the button opens the dialog")
        assertTrue(Regex("""<dialog id="dialogo-apri"[^>]*>""").containsMatchIn(detail))
        assertTrue(detail.contains(">Aprire le iscrizioni?</h2>"))
        assertTrue(detail.contains("Da adesso la pagina con le informazioni è visibile a chi ha il link e i genitori possono iscrivere i figli. Potrai chiudere le iscrizioni quando vuoi, ma la convivenza non torna in bozza."))
        assertTrue(Regex("""<form method="post" action="/convivenza/$id/apri"[^>]*>[\s\S]*?name="from" value="draft"[\s\S]*?Apri le iscrizioni""").containsMatchIn(detail))
        assertTrue(detail.contains("Apertura…"))
        assertEquals(GatheringStatus.draft, state(id), "a look at the dialog changes nothing")

        val opened = browser.step(id, "apri", "draft")

        assertEquals(303, opened.status())
        assertEquals("/convivenza/$id", opened.location())
        assertEquals(GatheringStatus.`open`, state(id))
        val after = browser.get("/convivenza/$id").body()
        assertTrue(text(after).contains("Iscrizioni aperte."))
        assertTrue(Regex(""">Iscrizioni aperte</span>""").containsMatchIn(after))
        assertTrue(text(after).contains("I genitori possono iscrivere i figli dal link."))
    }

    @Test
    fun `t4 c09 without JavaScript the same words are on a page of confirmation, with Annulla leading back to the gathering`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, custodians = listOf(me))

        val page = browser.get("/convivenza/$id/apri")
        val html = page.body()

        assertEquals(200, page.status())
        assertEquals("Aprire le iscrizioni?", heading(html))
        assertEquals("Aprire le iscrizioni? · Talità", title(html))
        assertTrue(html.contains("Da adesso la pagina con le informazioni è visibile a chi ha il link"))
        assertTrue(Regex("""<form method="post" action="/convivenza/$id/apri"[\s\S]*?name="from" value="draft"""").containsMatchIn(html))
        assertTrue(Regex("""<a href="/convivenza/$id"[^>]*>Annulla</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="/convivenza" aria-current="page"""").containsMatchIn(html))
        assertEquals(GatheringStatus.draft, state(id))
    }

    @Test
    fun `t4 c10 a draft with no place, no full fee or no custodian, or with the end past, does not open and the page lists what is missing with a link to each`() {
        val (browser, _) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, place = null, full = null)

        val detail = browser.get("/convivenza/$id").body()
        // Before the tap the page already says it, and the button is a form that sends straight away: there is no dialog to say no later.
        assertEquals(3, Regex(""">Da completare</span>""").findAll(detail).count())
        assertFalse(detail.contains("<dialog"), "no dialog for a draft that cannot open")
        assertTrue(Regex("""<form method="post" action="/convivenza/$id/apri"[\s\S]*?<button type="submit"[^>]*>[\s\S]*?Apri le iscrizioni""").containsMatchIn(detail))
        assertFalse(Regex("""<button[^>]*\sdisabled[\s=>]""").containsMatchIn(detail), "the button is never disabled to say that something is missing")

        val tried = browser.step(id, "apri", "draft")
        val html = tried.body()

        assertEquals(422, tried.status())
        assertEquals(GatheringStatus.draft, state(id), "the state does not change")
        assertTrue(Regex("""role="alert" tabindex="-1" data-fuoco[^>]*>[\s\S]*?Prima di aprire le iscrizioni:""").containsMatchIn(html))
        assertEquals(
            listOf(
                "/convivenza/$id/date#luogo" to "Scrivi il luogo",
                "/convivenza/$id/quote#quota-intera" to "Scrivi la quota intera",
                "/convivenza/$id/affidatari" to "Scegli almeno un maestro affidatario",
            ),
            Regex("""<li><a href="([^"]+)"[^>]*>([^<]+)</a></li>""").findAll(html).map { it.groupValues[1] to it.groupValues[2] }.toList(),
        )
        // The button stays there, active.
        assertTrue(Regex("""Apri le iscrizioni""").containsMatchIn(html))
    }

    @Test
    fun `t4 c10 a draft whose end has passed lists the dates as the thing to change`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, start = gatherings.today().minusDays(9), end = gatherings.today().minusDays(7), custodians = listOf(me))

        val tried = browser.step(id, "apri", "draft")

        assertEquals(422, tried.status())
        assertEquals(GatheringStatus.draft, state(id))
        assertTrue(tried.body().contains("""<a href="/convivenza/$id/date#ultimo-giorno" class="inline-flex min-h-11 items-center font-semibold text-brand underline underline-offset-2">Cambia le date: l’ultimo giorno è già passato</a>"""))
        assertEquals(422, browser.step(id, "apri", null).status(), "also without the field «from»")
    }

    @Test
    fun `t4 c10 a draft that is complete when the page is drawn and loses a custodian before the confirmation does not open either`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, custodians = listOf(me))
        browser.get("/convivenza/$id")

        dsl.deleteFrom(talita.jooq.tables.references.GATHERING_CUSTODIAN).execute()
        val tried = browser.step(id, "apri", "draft")

        assertEquals(422, tried.status())
        assertTrue(tried.body().contains("Scegli almeno un maestro affidatario"))
        assertEquals(GatheringStatus.draft, state(id))
    }

    // ---- Closing and reopening (criteria 11 and 12) ----------------------------------------------------------------------------

    @Test
    fun `t4 c11 closing asks to confirm with the words of the design, and confirmed the state is Iscrizioni chiuse`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.`open`, custodians = listOf(me))

        val detail = browser.get("/convivenza/$id").body()
        assertTrue(detail.contains(">Chiudere le iscrizioni?</h2>"))
        assertTrue(detail.contains("I genitori non potranno più iscrivere i figli né cambiare le iscrizioni: per farlo dovranno chiedere a un maestro. La pagina con le informazioni resta visibile. Potrai riaprire le iscrizioni finché la convivenza non è finita."))
        assertTrue(Regex("""data-apri="dialogo-chiudi"[^>]*>Chiudi le iscrizioni</a>""").containsMatchIn(detail))

        val closed = browser.step(id, "chiudi", "open")

        assertEquals(303, closed.status())
        assertEquals(GatheringStatus.closed, state(id))
        val after = browser.get("/convivenza/$id").body()
        assertTrue(text(after).contains("Iscrizioni chiuse."))
        assertTrue(text(after).contains("I genitori vedono la pagina, ma non possono iscrivere i figli né cambiare le iscrizioni."))
    }

    @Test
    fun `t4 c11 when the end has passed the dialog of closing says that the registrations will not reopen`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.`open`, start = gatherings.today().minusDays(5), end = gatherings.today().minusDays(3), custodians = listOf(me))

        val detail = browser.get("/convivenza/$id").body()

        assertTrue(detail.contains("La pagina con le informazioni resta visibile. Non potrai riaprirle, perché la convivenza è finita."))
        assertFalse(detail.contains("Potrai riaprire le iscrizioni finché"))
        assertTrue(text(detail).contains("La convivenza è finita ma le iscrizioni sono ancora aperte. Chiudile per poter creare la prossima."))
    }

    @Test
    fun `t4 c12 closed registrations reopen while the end has not passed and no other gathering is a draft or open`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.closed, custodians = listOf(me))

        val detail = browser.get("/convivenza/$id").body()
        assertTrue(detail.contains(">Riaprire le iscrizioni?</h2>"))
        assertTrue(detail.contains("I genitori potranno di nuovo iscrivere i figli e cambiare le iscrizioni. La pagina con le informazioni torna a mostrare il bottone per iscriversi."))
        assertTrue(Regex("""data-apri="dialogo-riapri"[^>]*>Riapri le iscrizioni</a>""").containsMatchIn(detail))

        val reopened = browser.step(id, "riapri", "closed")

        assertEquals(303, reopened.status())
        assertEquals(GatheringStatus.`open`, state(id))
        assertTrue(text(browser.get("/convivenza/$id").body()).contains("Iscrizioni riaperte."))
    }

    @Test
    fun `t4 c12 with the end passed there is no action to reopen, the page says so, and the service refuses`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.closed, start = gatherings.today().minusDays(5), end = gatherings.today().minusDays(3), custodians = listOf(me))

        val detail = browser.get("/convivenza/$id").body()

        assertTrue(text(detail).contains("La convivenza è finita: le iscrizioni non si riaprono."))
        assertFalse(detail.contains("Riapri le iscrizioni"))
        assertFalse(detail.contains("dialogo-riapri"))
        val tried = browser.step(id, "riapri", "closed")
        assertEquals(409, tried.status())
        assertTrue(text(tried.body()).contains("Le iscrizioni non si possono riaprire: la convivenza è finita."))
        assertEquals(GatheringStatus.closed, state(id))
        assertTrue(Regex(""">Iscrizioni chiuse</span>""").containsMatchIn(detail) && Regex(""">Finita</span>""").containsMatchIn(detail))
    }

    @Test
    fun `t4 c12 with another gathering as a draft or open there is no action to reopen, the page names the other as a link, and the request is refused`() {
        val (browser, me) = kit.start()
        val closed = gatherings.make(title = "Convivenza di settembre 2026", status = GatheringStatus.closed, custodians = listOf(me))
        val current = gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.draft)

        val detail = browser.get("/convivenza/$closed").body()

        assertFalse(detail.contains("Riapri le iscrizioni"))
        assertTrue(Regex("""Non puoi riaprire le iscrizioni finché <a href="/convivenza/$current"[^>]*>«Convivenza di settembre 2027»</a> non è chiusa\.""").containsMatchIn(detail), text(detail))
        val tried = browser.step(closed, "riapri", "closed")
        assertEquals(409, tried.status())
        assertTrue(text(tried.body()).contains("Non puoi riaprire le iscrizioni finché «Convivenza di settembre 2027» non è chiusa."))
        assertEquals(GatheringStatus.closed, state(closed))
        assertEquals(GatheringStatus.draft, state(current))
    }

    // ---- The service of the states (criterion 13) -----------------------------------------------------------------------------

    @Test
    fun `t4 c13 the service refuses every step that is not draft to open, open to closed or closed to open, and the state does not change`() {
        val (_, me) = kit.start()

        for ((from, refused) in listOf(
            GatheringStatus.draft to listOf(StateAction.CLOSE, StateAction.REOPEN),
            GatheringStatus.`open` to listOf(StateAction.OPEN),
            GatheringStatus.closed to listOf(StateAction.CLOSE),
        )) {
            for (action in refused) {
                gatherings.reset()
                val id = gatherings.make(status = from, custodians = listOf(me))
                val result = service.change(id, action)
                // A step to the state the gathering is in is «already done» (see c14); the others do not exist.
                assertTrue(result == TransitionResult.NotAllowed || result is TransitionResult.AlreadyDone, "$from → $action gave $result")
                assertEquals(from, state(id), "$from → $action")
            }
        }
        // There is no step back to the draft at all.
        assertEquals(setOf(GatheringState.DRAFT to GatheringState.OPEN, GatheringState.OPEN to GatheringState.CLOSED, GatheringState.CLOSED to GatheringState.OPEN), StateAction.entries.map { it.from to it.to }.toSet())
        assertEquals(TransitionResult.NotFound, service.change(999999, StateAction.OPEN))
    }

    @Test
    fun `t4 c13 a step with another state of departure than the real one is refused, and the interface has no way back to the draft`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.`open`, custodians = listOf(me))

        val byHand = browser.step(id, "chiudi", "draft")

        assertEquals(409, byHand.status())
        assertTrue(text(byHand.body()).contains("Questo passaggio non è possibile."))
        assertEquals(GatheringStatus.`open`, state(id))
        for (status in listOf(GatheringStatus.draft, GatheringStatus.`open`, GatheringStatus.closed)) {
            gatherings.reset()
            val other = gatherings.make(status = status, custodians = listOf(me))
            val html = browser.get("/convivenza/$other").body()
            assertFalse(html.contains("Torna in bozza") || html.contains("Rimetti in bozza"), "$status")
            assertFalse(html.contains("/bozza"), "$status")
        }
        assertEquals(404, browser.get("/convivenza/$id/bozza").status())
    }

    @Test
    fun `t4 c13 a step that makes no sense for the state of the page gets the page that says how things are`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, custodians = listOf(me))

        val closing = browser.step(id, "chiudi", "open")

        assertEquals(409, closing.status())
        assertTrue(Regex("""role="alert" tabindex="-1" data-fuoco[^>]*>[\s\S]*?Questo passaggio non è possibile\.""").containsMatchIn(closing.body()))
        assertEquals(GatheringStatus.draft, state(id))
        // The page of confirmation for a step that is not possible is not shown: back to the detail.
        assertEquals(303, browser.get("/convivenza/$id/chiudi").status())
        assertEquals("/convivenza/$id", browser.get("/convivenza/$id/riapri").location())
    }

    // ---- Twice at the same moment (criterion 14) --------------------------------------------------------------------------------

    @Test
    fun `t4 c14 the same step asked twice changes the state once and the second answer is the same confirmation, never an error`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.`open`, custodians = listOf(me))

        val first = browser.step(id, "chiudi", "open")
        val second = browser.step(id, "chiudi", "open")

        assertEquals(303, first.status())
        assertEquals(303, second.status())
        assertEquals("/convivenza/$id", second.location())
        assertEquals(GatheringStatus.closed, state(id))
        assertTrue(text(browser.get("/convivenza/$id").body()).contains("Iscrizioni chiuse."), "the second one is told what the first was")

        gatherings.reset()
        val draft = gatherings.make(status = GatheringStatus.draft, custodians = listOf(me))
        assertEquals(303, browser.step(draft, "apri", "draft").status())
        assertEquals(303, browser.step(draft, "apri", "draft").status())
        assertEquals(GatheringStatus.`open`, state(draft))
    }

    @Test
    fun `t4 c14 two teachers who close at the same moment close it once, and both are told it is closed`() {
        val (_, me) = kit.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(25) { round ->
                gatherings.reset()
                val id = gatherings.make(status = GatheringStatus.`open`, custodians = listOf(me))
                val start = CountDownLatch(1)

                val results = (1..2).map { pool.submit<TransitionResult> { start.await(); service.change(id, StateAction.CLOSE, GatheringState.OPEN) } }
                start.countDown()
                val done = results.map { it.get() }

                assertEquals(1, done.count { it is TransitionResult.Done }, "round $round: $done")
                assertEquals(1, done.count { it is TransitionResult.AlreadyDone }, "round $round: $done")
                assertEquals(GatheringStatus.closed, state(id))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t4 c15 two teachers who create a gathering at the same moment make one, and the other is told there is one`() {
        val (_, _) = kit.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(15) { round ->
                gatherings.reset()
                val start = CountDownLatch(1)
                val day = gatherings.today().plusDays(30)

                val results = (1..2).map { pool.submit<CreateResult> { start.await(); service.create("Convivenza di settembre 2027", day, day.plusDays(2)) } }
                start.countDown()
                val done = results.map { it.get() }

                assertEquals(1, done.count { it is CreateResult.Created }, "round $round: $done")
                assertEquals(1, done.count { it is CreateResult.AlreadyCurrent }, "round $round: $done")
                assertEquals(1, dsl.fetchCount(GATHERING), "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `t4 c12 reopening a closed gathering at the same moment as another is created leaves one that is not closed`() {
        val (_, me) = kit.start()
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(15) { round ->
                gatherings.reset()
                val closed = gatherings.make(status = GatheringStatus.closed, custodians = listOf(me))
                val start = CountDownLatch(1)
                val day = gatherings.today().plusDays(30)

                val reopen = pool.submit<Any> { start.await(); service.change(closed, StateAction.REOPEN, GatheringState.CLOSED) }
                val create = pool.submit<Any> { start.await(); service.create("Un'altra convivenza", day, day.plusDays(1)) }
                start.countDown()
                reopen.get()
                create.get()

                assertEquals(1, dsl.fetchCount(GATHERING, GATHERING.STATUS.ne(GatheringStatus.closed)), "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
