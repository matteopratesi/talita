package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.gathering.Fees
import talita.gathering.FeesResult
import talita.gathering.Gatherings
import talita.gathering.RegistrationUsage
import talita.gathering.body
import talita.gathering.errorOf
import talita.gathering.location
import talita.gathering.status
import talita.gathering.valueOf
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.PAYMENT
import talita.registration.RegistrationFormat
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The money in the card of a child (criteria 10-15): what is due, what was paid and the balance in words, the form that registers a payment, the same form sent
 * twice, the dialog that takes a payment away, and what stays when the fees or the days change. The services have their tests in [CheckinServicesTests]; here is
 * what the page shows and sends. Names and amounts are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class CardMoneyTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val gatherings: Gatherings,
    @Autowired private val usage: RegistrationUsage,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    private fun amounts(registration: Long): List<Int> = dsl.select(PAYMENT.AMOUNT_CENTS).from(PAYMENT).where(PAYMENT.REGISTRATION_ID.eq(registration)).orderBy(PAYMENT.ID).fetch(PAYMENT.AMOUNT_CENTS).map { it!! }

    private inner class Scenario(first: Long = -1, last: Long = 1) {
        val setup = kit.start(first, last)
        val parent = kit.account()
        private val firstDay = setup.today.plusDays(first)

        /** Anna comes all the days (60 euros), Luca the first day and, for lunch, the second (35), Chiara was withdrawn. */
        val anna = kit.rows.register(setup.id, parent, "Anna", "Rossi", LocalDate.of(2019, 1, 9))
        val luca = kit.rows.register(setup.id, parent, "Luca", "Rossi", LocalDate.of(2016, 4, 4), days = mapOf(firstDay to "full", firstDay.plusDays(1) to "lunch"))
        val chiara = kit.rows.register(setup.id, kit.account(), "Chiara", "Fontana", LocalDate.of(2016, 8, 8), status = RegistrationStatus.withdrawn)
    }

    // ---- What is due, what was paid, the balance (criterion 10) -------------------------------------------------------------------

    @Test
    fun `t6 c10 the card says what is due, what was paid and the balance in words, from still to pay to paid more`() {
        val s = Scenario()
        val nothing = words(s.setup.marta.card(s.anna))
        assertTrue(nothing.contains("Quota e pagamenti Dovuta ${euro(6000)} Pagata Niente Saldo Da pagare ${euro(6000)} Pagamenti Nessun pagamento registrato."), nothing)

        kit.payment(s.anna, 3500, s.setup.paoloId)
        assertTrue(words(s.setup.marta.card(s.anna)).contains("Dovuta ${euro(6000)} Pagata ${euro(3500)} Saldo Da pagare ${euro(2500)} Pagamenti"))

        kit.payment(s.anna, 2500, s.setup.martaId)
        assertTrue(words(s.setup.marta.card(s.anna)).contains("Dovuta ${euro(6000)} Pagata ${euro(6000)} Saldo Pagata Pagamenti"))

        kit.payment(s.anna, 1000, s.setup.martaId)
        assertTrue(words(s.setup.marta.card(s.anna)).contains("Dovuta ${euro(6000)} Pagata ${euro(7000)} Saldo Pagato ${euro(1000)} in più Pagamenti"))
        // Luca owes what his days cost (25 and 10), whatever Anna paid.
        assertTrue(words(s.setup.marta.card(s.luca)).contains("Dovuta ${euro(3500)} Pagata Niente Saldo Da pagare ${euro(3500)}"))
    }

    @Test
    fun `t6 c10 a gathering with no fees is free, and a fee that cannot be worked out is said and not guessed`() {
        val free = kit.start(full = 0, daily = 0, lunch = 0)
        val child = kit.rows.register(free.id, kit.account(), "Anna", "Rossi")
        assertTrue(words(free.marta.card(child)).contains("Quota e pagamenti Dovuta Gratuita Pagata Niente Saldo Gratuita"), words(free.marta.card(child)))
        // Something paid all the same on a free gathering is more than was due.
        kit.payment(child, 500, free.martaId)
        assertTrue(words(free.marta.card(child)).contains("Dovuta Gratuita Pagata ${euro(500)} Saldo Pagato ${euro(500)} in più"))

        // No fee for single days: what Luca owes cannot be worked out, and the card does not make it up.
        val open = kit.start(daily = null)
        val luca = kit.rows.register(open.id, kit.account(), "Luca", "Rossi", days = mapOf(open.today to "full"))
        assertTrue(words(open.marta.card(luca)).contains("Dovuta Da definire Pagata Niente Saldo Quota da definire"), words(open.marta.card(luca)))
    }

    @Test
    fun `t6 c10 a withdrawn registration owes nothing and its payments stay, with the balance that says so`() {
        val s = Scenario()
        assertTrue(words(s.setup.marta.card(s.chiara)).contains("Dovuta Non dovuta Pagata Niente Saldo Niente da pagare"), words(s.setup.marta.card(s.chiara)))

        kit.payment(s.chiara, 6000, s.setup.martaId)

        val html = s.setup.marta.card(s.chiara)
        assertTrue(words(html).contains("Dovuta Non dovuta Pagata ${euro(6000)} Saldo Pagato ${euro(6000)} in più Pagamenti ${euro(6000)} Marta Bianchi"), words(html))
        // The form is still there: a payment can be corrected on a registration that was withdrawn.
        assertTrue(html.contains("""name="importo""""))
    }

    @Test
    fun `t6 c10 each payment has its amount, the teacher who registered it and when, in the order they came, and a teacher who left is said so`() {
        val s = Scenario()
        val first = OffsetDateTime.now(clock).minusHours(3)
        val second = first.plusHours(1)
        kit.payment(s.anna, 2500, s.setup.paoloId, first)
        kit.payment(s.anna, 1000, s.setup.martaId, second)
        kit.payment(s.anna, 500, s.setup.giuliaId, second.plusMinutes(5))
        kit.removeTeacher(s.setup.giuliaId)

        val text = words(s.setup.marta.card(s.anna))

        assertTrue(
            text.contains(
                "Pagamenti ${euro(2500)} Paolo Verdi · ${CheckinFormat.moment(first)} Togli " +
                    "${euro(1000)} Marta Bianchi · ${CheckinFormat.moment(second)} Togli " +
                    "${euro(500)} un maestro che non è più tra i maestri · ${CheckinFormat.moment(second.plusMinutes(5))} Togli",
            ),
            text,
        )
    }

    @Test
    fun `t6 c10 the money of the family is said in each card, and the notice tells to register a part for each child`() {
        val s = Scenario()
        kit.payment(s.luca, 3500, s.setup.martaId)

        val text = words(s.setup.marta.card(s.anna))

        assertTrue(text.contains("Altri figli dello stesso genitore Luca Rossi Confermata Pagata Se il genitore ha pagato per tutti, registra a ognuno la sua parte."), text)
    }

    // ---- Registering a payment (criterion 11) ----------------------------------------------------------------------------------

    @Test
    fun `t6 c11 the form has the amount that is still missing already written, with the words that say so, and a code of the request that changes every time`() {
        val s = Scenario()
        val html = s.setup.marta.card(s.anna)

        assertEquals("60", valueOf(html, "importo"))
        assertTrue(words(html).contains("Registra un pagamento Importo Abbiamo scritto quanto manca: cambia l’importo se hai ricevuto una cifra diversa."), words(html))
        assertTrue(Regex("""<input id="importo"[^>]*inputmode="decimal"[^>]*enterkeyhint="done"""").containsMatchIn(html), "the keyboard of the amounts")
        assertTrue(html.contains("""<form method="post" action="/arrivi/${s.anna}/pagamenti" hx-post="/arrivi/${s.anna}/pagamenti" hx-target="#scheda" hx-swap="outerHTML""""))
        assertTrue(Regex("""<h3[^>]*>Registra un pagamento</h3>""").containsMatchIn(html))
        assertFalse(requestCodeIn(html) == requestCodeIn(s.setup.marta.card(s.anna)), "a new code is drawn for every page")
        // With something paid, what is missing is what is written; with nothing missing, the field is empty and the help says what to write.
        kit.payment(s.anna, 3500, s.setup.martaId)
        assertEquals("25", valueOf(s.setup.marta.card(s.anna), "importo"))
        kit.payment(s.anna, 2500, s.setup.martaId)
        val paid = s.setup.marta.card(s.anna)
        assertEquals("", valueOf(paid, "importo"))
        assertTrue(words(paid).contains("Importo Scrivi quanto hai ricevuto, per esempio 25 o 12,50."), words(paid))
    }

    @Test
    fun `t6 c11 a payment is registered with its amount, the moment and the teacher, and the card says so and shows the new balance`() {
        val s = Scenario()
        val before = OffsetDateTime.now(clock).minusMinutes(1)

        val answer = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "25", "richiesta" to UUID.randomUUID().toString()))

        assertEquals(200, answer.status())
        val html = answer.body()
        assertTrue(html.trim().startsWith("<div id=\"scheda\">"), html.take(200))
        assertEquals(listOf(2500), amounts(s.anna))
        val row = dsl.selectFrom(PAYMENT).where(PAYMENT.REGISTRATION_ID.eq(s.anna)).fetchSingle()
        assertEquals(s.setup.martaId, row.recordedByAccountId)
        assertTrue(row.recordedAt!!.isAfter(before) && !row.recordedAt!!.isAfter(OffsetDateTime.now(clock).plusMinutes(1)), "the moment is now")
        assertTrue(Regex("""role="status" tabindex="-1" data-fuoco=""""").containsMatchIn(html), "the confirmation is announced and takes the focus")
        assertTrue(words(html).contains("Pagamento registrato."), words(html))
        assertTrue(words(html).contains("Dovuta ${euro(6000)} Pagata ${euro(2500)} Saldo Da pagare ${euro(3500)} Pagamenti ${euro(2500)} Marta Bianchi · ${CheckinFormat.moment(row.recordedAt!!)} Togli"), words(html))
        // The field has what is missing now, ready for the next instalment.
        assertEquals("35", valueOf(html, "importo"))
        // The other children of the family are not touched.
        assertEquals(emptyList(), amounts(s.luca))
    }

    @Test
    fun `t6 c11 an amount is read with a comma or a dot, from one cent to a thousand euros, and the spaces do not count`() {
        val s = Scenario()
        val cases = listOf("0,01" to 1, "12,50" to 1250, "12.5" to 1250, "7" to 700, " 1 000 " to 100000, "999,99" to 99999)

        for ((typed, cents) in cases) {
            val answer = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to typed, "richiesta" to UUID.randomUUID().toString()))
            assertEquals(200, answer.status(), "`$typed`")
        }

        assertEquals(cases.map { it.second }, amounts(s.anna))
    }

    @Test
    fun `t6 c11 a wrong amount is the form again with what was typed and the error under the field, with 422, and nothing is written`() {
        val s = Scenario()
        val cases = listOf(
            "" to PaymentAmount.EMPTY,
            "abc" to PaymentAmount.FORMAT,
            "12,345" to PaymentAmount.FORMAT,
            "1,2,3" to PaymentAmount.FORMAT,
            "-5" to PaymentAmount.FORMAT,
            "0" to PaymentAmount.ZERO,
            "0,00" to PaymentAmount.ZERO,
            "1000,01" to PaymentAmount.TOO_MUCH,
            "5000" to PaymentAmount.TOO_MUCH,
        )

        for ((typed, error) in cases) {
            val answer = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to typed, "richiesta" to UUID.randomUUID().toString()))

            assertEquals(422, answer.status(), "`$typed`")
            val html = answer.body()
            assertTrue(html.trim().startsWith("<div id=\"scheda\">"), "`$typed`: the block of the card")
            assertEquals(error, errorOf(html, "importo"), "`$typed`")
            assertEquals(typed.filterNot { it.isWhitespace() }, valueOf(html, "importo"), "`$typed`: the amount is still there")
            assertTrue(Regex("""<input id="importo"[^>]*aria-invalid="true"[^>]*>""").containsMatchIn(html), "`$typed`: the field says it is wrong")
            assertFalse(words(html).contains("Pagamento registrato."), "`$typed`")
            // The rest of the card is whole: the days, the manleva, the family.
            assertTrue(words(html).contains("Giorni") && words(html).contains("Manleva") && words(html).contains("Altri figli dello stesso genitore"), "`$typed`")
        }
        assertEquals(0, kit.paymentCount())
    }

    @Test
    fun `t6 c11 what is typed is a text and never markup, and a very long one is cut`() {
        val s = Scenario()

        val html = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "\"><script>alert(1)</script>" + "9".repeat(200), "richiesta" to UUID.randomUUID().toString())).body()

        assertFalse(html.contains("<script>alert"), "the text typed is not markup")
        assertTrue(html.contains("&lt;script"), "the angle bracket is written as text, not as the start of a tag: " + Regex("""<input id="importo"[^>]*>""").find(html)?.value)
        assertTrue((valueOf(html, "importo") ?: "").length <= 30, "what is kept of it is short")
        assertEquals(0, kit.paymentCount())
    }

    @Test
    fun `t6 c11 a form without JavaScript leads back to the card with the confirmation, or shows the whole page again with the error`() {
        val s = Scenario()

        val plain = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "10", "richiesta" to UUID.randomUUID().toString()), viaHtmx = false)

        assertEquals(303, plain.status())
        assertEquals("/arrivi/${s.anna}?giorno=${s.setup.today}", plain.location())
        assertEquals(listOf(1000), amounts(s.anna))
        assertTrue(words(s.setup.marta.get(plain.location()!!).body()).contains("Pagamento registrato."), "the confirmation is on the page the redirect leads to")
        assertFalse(words(s.setup.marta.get(plain.location()!!).body()).contains("Pagamento registrato."), "and only once")

        val wrong = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "dieci", "richiesta" to UUID.randomUUID().toString()), viaHtmx = false)
        assertEquals(422, wrong.status())
        assertTrue(wrong.body().contains("<html"), "a whole page, with the shell")
        assertEquals("dieci", valueOf(wrong.body(), "importo"))
        assertEquals(PaymentAmount.FORMAT, errorOf(wrong.body(), "importo"))
    }

    @Test
    fun `t6 c11 a request with no code, or with a code that is not one, is refused and nothing is written`() {
        val s = Scenario()

        assertEquals(400, s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "10")).status())
        assertEquals(400, s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "10", "richiesta" to "non-un-codice")).status())
        assertEquals(400, s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "10", "richiesta" to "")).status())

        assertEquals(0, kit.paymentCount())
    }

    // ---- The same form twice (criterion 12) ------------------------------------------------------------------------------------

    @Test
    fun `t6 c12 the same form sent twice is one payment and the second answer shows the state, and the same code on another child writes nothing`() {
        val s = Scenario()
        val html = s.setup.marta.card(s.anna)
        val code = requestCodeIn(html)

        val first = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "25", "richiesta" to code))
        val second = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "25", "richiesta" to code))
        // The form of a page that was left open on another phone, with the same code: the same payment.
        val other = s.setup.paolo.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "25", "richiesta" to code))

        assertEquals(listOf(200, 200, 200), listOf(first.status(), second.status(), other.status()))
        assertEquals(listOf(2500), amounts(s.anna))
        assertTrue(words(second.body()).contains("Pagamento registrato."), "the second answer is the same confirmation")
        assertTrue(words(second.body()).contains("Pagata ${euro(2500)} Saldo Da pagare ${euro(3500)}"), "and shows the state, not a second payment")
        // The code is for one registration: for another one nothing is written, and the person is told to try again.
        val elsewhere = s.setup.marta.touch(s.setup, "/arrivi/${s.luca}/pagamenti", mapOf("importo" to "25", "richiesta" to code))
        assertEquals(409, elsewhere.status())
        assertTrue(words(elsewhere.body()).contains("Non è stato possibile registrare il pagamento. Riprova."), words(elsewhere.body()))
        assertEquals(emptyList(), amounts(s.luca))
        assertEquals(1, kit.paymentCount())
    }

    @Test
    fun `t6 c12 a new tap after a failure registers the payment once, with the code that the form still has`() {
        val s = Scenario()
        val code = requestCodeIn(s.setup.marta.card(s.anna))
        // The first touch is refused (the amount is wrong): the answer is the form again, with a new code, and the amount can be mended.
        val wrong = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "2x5", "richiesta" to code))
        assertEquals(422, wrong.status())
        val again = requestCodeIn(wrong.body())

        val fixed = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "25", "richiesta" to again))
        val repeated = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "25", "richiesta" to again))

        assertEquals(200, fixed.status())
        assertEquals(200, repeated.status())
        assertEquals(listOf(2500), amounts(s.anna))
    }

    // ---- Taking a payment away (criterion 13) ----------------------------------------------------------------------------------

    @Test
    fun `t6 c13 each payment has a dialog that says what goes, from whom and what changes, and the link works without JavaScript`() {
        val s = Scenario()
        val at = OffsetDateTime.now(clock).minusHours(2)
        val id = kit.payment(s.anna, 2500, s.setup.paoloId, at)
        val html = s.setup.marta.card(s.anna)

        assertTrue(Regex("""<dialog id="togli-pagamento-$id"[^>]*aria-labelledby="togli-pagamento-$id-titolo"""").containsMatchIn(html))
        val dialog = words(Regex("""<dialog id="togli-pagamento-$id"[\s\S]*?</dialog>""").find(html)!!.value)
        assertTrue(
            dialog.contains(
                "Togliere il pagamento di ${euro(2500)}? Il pagamento di ${euro(2500)} registrato da Paolo Verdi il ${CheckinFormat.moment(at)} sparisce da Anna Rossi. " +
                    "Il saldo e il totale incassato cambiano. Per rimetterlo lo devi registrare di nuovo. Togli il pagamento Tolgo… Annulla",
            ),
            dialog,
        )
        assertTrue(html.contains("""<form method="post" action="/arrivi/${s.anna}/pagamenti/$id/togli" hx-post="/arrivi/${s.anna}/pagamenti/$id/togli" hx-target="#scheda" hx-swap="outerHTML""""))
        // The button of the row says whose payment it is, and opens the dialog; without JavaScript it is a link to a page with the same words.
        val link = Regex("""<a href="/arrivi/${s.anna}/pagamenti/$id/togli\?giorno=${s.setup.today}" data-apri="togli-pagamento-$id" aria-label="Togli il pagamento di ${RegistrationFormat.money(2500)}"""").find(html)
        assertTrue(link != null, "the link of the row")

        val page = s.setup.marta.get("/arrivi/${s.anna}/pagamenti/$id/togli?giorno=${s.setup.today}")
        assertEquals(200, page.status())
        assertTrue(words(page.body()).contains("Togliere il pagamento di ${euro(2500)}?"), words(page.body()))
        assertTrue(page.body().contains("""action="/arrivi/${s.anna}/pagamenti/$id/togli""""))
        assertEquals(listOf(2500), amounts(s.anna), "asking is not taking away")
    }

    @Test
    fun `t6 c13 taking a payment away makes it go with its data, and the balance, the card and the totals of the list change`() {
        val s = Scenario()
        val wrong = kit.payment(s.anna, 6000, s.setup.martaId)
        kit.payment(s.luca, 1000, s.setup.paoloId)
        assertTrue(words(s.setup.marta.get("/arrivi").body()).contains("Incassato in tutta la convivenza ${euro(7000)}"))

        val answer = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti/$wrong/togli", emptyMap())

        assertEquals(200, answer.status())
        val html = answer.body()
        assertTrue(html.trim().startsWith("<div id=\"scheda\">"), html.take(200))
        assertTrue(words(html).contains("Pagamento tolto."), words(html))
        assertEquals(emptyList(), amounts(s.anna))
        assertEquals(listOf(1000), amounts(s.luca), "only that one")
        assertTrue(words(html).contains("Dovuta ${euro(6000)} Pagata Niente Saldo Da pagare ${euro(6000)} Pagamenti Nessun pagamento registrato."), words(html))
        assertFalse(html.contains("togli-pagamento-$wrong"), "no dialog for a payment that is gone")
        assertEquals("60", valueOf(html, "importo"), "the field has what is missing again")
        assertTrue(words(s.setup.marta.get("/arrivi").body()).contains("Incassato in tutta la convivenza ${euro(1000)} Paolo Verdi ${euro(1000)}"))
    }

    @Test
    fun `t6 c13 a payment that another teacher took away a moment before is the state as it is now, with no error`() {
        val s = Scenario()
        val id = kit.payment(s.anna, 2500, s.setup.paoloId)
        assertEquals(200, s.setup.paolo.touch(s.setup, "/arrivi/${s.anna}/pagamenti/$id/togli", emptyMap()).status())

        val late = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti/$id/togli", emptyMap())

        assertEquals(200, late.status())
        assertTrue(words(late.body()).contains("Quel pagamento era già stato tolto."), words(late.body()))
        assertTrue(words(late.body()).contains("Pagata Niente Saldo Da pagare ${euro(6000)}"), "the state now")
        assertTrue(Regex("""<div class="[^"]*bg-brand-soft[^"]*" role="status"""").containsMatchIn(late.body()), "an information, not an error")
        // The page of confirmation of a payment that is gone leads to the card, with the same sentence.
        val page = s.setup.marta.get("/arrivi/${s.anna}/pagamenti/$id/togli?giorno=${s.setup.today}")
        assertEquals(303, page.status())
        assertEquals("/arrivi/${s.anna}?giorno=${s.setup.today}", page.location())
        assertTrue(words(s.setup.marta.get(page.location()!!).body()).contains("Quel pagamento era già stato tolto."))
    }

    @Test
    fun `t6 c13 a payment of another registration is not found, also with the dialog of the right one, and a payment id that is not a number is no page`() {
        val s = Scenario()
        val lucas = kit.payment(s.luca, 1000, s.setup.martaId)

        assertEquals(404, s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti/$lucas/togli", emptyMap()).status())
        assertEquals(404, s.setup.marta.get("/arrivi/${s.anna}/pagamenti/$lucas/togli").status())
        assertEquals(listOf(1000), amounts(s.luca))
        assertEquals(404, s.setup.marta.get("/arrivi/${s.anna}/pagamenti/abc/togli").status())
        assertEquals(404, s.setup.marta.touch(s.setup, "/arrivi/${s.luca + 100000}/pagamenti/$lucas/togli", emptyMap()).status())
    }

    @Test
    fun `t6 c13 taking a payment away without JavaScript leads back to the card with the confirmation, once`() {
        val s = Scenario()
        val id = kit.payment(s.anna, 2500, s.setup.martaId)

        val plain = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti/$id/togli", emptyMap(), viaHtmx = false)

        assertEquals(303, plain.status())
        assertEquals("/arrivi/${s.anna}?giorno=${s.setup.today}", plain.location())
        assertEquals(emptyList(), amounts(s.anna))
        assertTrue(words(s.setup.marta.get(plain.location()!!).body()).contains("Pagamento tolto."))
        assertFalse(words(s.setup.marta.get(plain.location()!!).body()).contains("Pagamento tolto."))
    }

    // ---- When the fees or the days change (criterion 14) ---------------------------------------------------------------------

    @Test
    fun `t6 c14 when the fees change the card shows the same payments and only what is due and the balance change`() {
        val s = Scenario()
        kit.payment(s.anna, 3500, s.setup.martaId)
        assertTrue(words(s.setup.marta.card(s.anna)).contains("Dovuta ${euro(6000)} Pagata ${euro(3500)} Saldo Da pagare ${euro(2500)}"))

        assertEquals(FeesResult.Saved, gatherings.saveFees(s.setup.id, Fees(7000, 2500, 1000), usage))

        val text = words(s.setup.marta.card(s.anna))
        assertTrue(text.contains("Dovuta ${euro(7000)} Pagata ${euro(3500)} Saldo Da pagare ${euro(3500)} Pagamenti ${euro(3500)} Marta Bianchi"), text)
        assertEquals(listOf(3500), amounts(s.anna))
        // And the amount that the form proposes is what is missing now.
        assertEquals("35", valueOf(s.setup.marta.card(s.anna), "importo"))
    }

    // ---- Before the start and after the end (criterion 15) -----------------------------------------------------------------------

    @Test
    fun `t6 c15 before the start of the gathering a payment is registered and taken away from the card, and the notice says so`() {
        val s = Scenario(first = 3, last = 5)
        val before = words(s.setup.marta.card(s.anna))
        assertTrue(before.contains("I pagamenti si possono già registrare."), before)

        val paid = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "20", "richiesta" to UUID.randomUUID().toString()))
        assertEquals(200, paid.status())
        assertEquals(listOf(2000), amounts(s.anna))
        val id = dsl.select(PAYMENT.ID).from(PAYMENT).where(PAYMENT.REGISTRATION_ID.eq(s.anna)).fetchSingle(PAYMENT.ID)!!
        assertEquals(200, s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti/$id/togli", emptyMap()).status())
        assertEquals(emptyList(), amounts(s.anna))
    }

    @Test
    fun `t6 c15 after the end a payment is still registered and taken away from the card, and the notice says that the gathering is over`() {
        val s = Scenario(first = -5, last = -3)
        val over = words(s.setup.marta.card(s.anna))
        assertTrue(over.contains("La convivenza è finita. Puoi ancora correggere presenze e pagamenti, per esempio quando trascrivi il registro di carta."), over)

        val paid = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "60", "richiesta" to UUID.randomUUID().toString()))

        assertEquals(200, paid.status())
        assertEquals(listOf(6000), amounts(s.anna))
        assertTrue(words(paid.body()).contains("Pagamento registrato."))
        assertTrue(words(paid.body()).contains("La convivenza è finita."), "the notice stays in the answer")
    }

    @Test
    fun `t6 c15 a payment for a registration of another gathering than the one of the arrivals is refused and writes nothing`() {
        val s = Scenario()
        // The gathering of the arrivals is the one that is not a draft and starts last: once this one is closed and a later one is the one, its registrations have no card.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(s.setup.id)).execute()
        kit.kit.gatherings.make(title = "La convivenza dopo", start = s.setup.today.plusDays(60), status = GatheringStatus.`open`, custodians = listOf(s.setup.martaId))

        val answer = s.setup.marta.touch(s.setup, "/arrivi/${s.anna}/pagamenti", mapOf("importo" to "10", "richiesta" to UUID.randomUUID().toString()))

        assertEquals(404, answer.status())
        assertEquals(0, kit.paymentCount())
    }
}
