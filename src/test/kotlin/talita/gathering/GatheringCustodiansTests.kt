package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.EmailAddress
import talita.account.MockBrowser
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.GATHERING_CUSTODIAN
import talita.jooq.tables.references.TEACHER
import talita.teacher.Invitations
import talita.teacher.Person
import talita.teacher.act
import java.time.Clock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The custodians of a gathering (criterion 8) and what they do to «Togli» in «Maestri» (criterion 40): a teacher who is a custodian of a
 * gathering that is not over is not taken away, in the page and in a request made by hand, and the last custodian of an open or closed
 * gathering is not taken away from it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class GatheringCustodiansTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
    @Autowired private val invitations: Invitations,
    @Autowired private val service: Gatherings,
    @Autowired private val teachers: talita.teacher.Teachers,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings

    private val me = Person(Addresses.unique("aff-io"), "Elisa", "Conti")
    private val paolo = Person(Addresses.unique("aff-paolo"), "Paolo", "Verdi")
    private val marta = Person(Addresses.unique("aff-marta"), "Marta", "Bianchi")

    private fun MockBrowser.saveCustodians(id: Long, vararg accounts: Long) =
        submit("/convivenza/$id/affidatari", "/convivenza/$id/affidatari", emptyMap()) { accounts.forEach { param("custodians", it.toString()) } }

    private fun names(html: String): List<String> =
        Regex("""<label class="flex min-h-14[^>]*>[\s\S]*?<span class="min-w-0 flex-1 font-semibold wrap-anywhere">([^<]*)</span>""").findAll(html).map { it.groupValues[1] }.toList()

    // ---- Choosing them (criterion 8) --------------------------------------------------------------------------------------------

    @Test
    fun `t4 c08 the form lists the teachers by surname and name, with Sei tu on one's own, and not who has only an invitation`() {
        val (browser, _) = kit.start(me, paolo, marta)
        invitations.invite(EmailAddress.parse(Addresses.unique("aff-invitato"))!!)
        val id = gatherings.make(status = GatheringStatus.draft)

        val html = browser.get("/convivenza/$id/affidatari").body()

        assertEquals(listOf("Marta Bianchi", "Elisa Conti", "Paolo Verdi"), names(html))
        assertEquals(1, Regex(""">Sei tu</span>""").findAll(html).count())
        assertTrue(Regex("""Elisa Conti</span>\s*<span[^>]*>Sei tu</span>""").containsMatchIn(html))
        assertEquals(3, Regex("""type="checkbox" name="custodians"""").findAll(html).count(), "only the teachers, not the invited")
        assertFalse(html.contains("aff-invitato"))
        assertTrue(html.contains("I loro nomi compaiono nel testo dell’affidamento che i genitori accettano con l’iscrizione. Non compaiono nella pagina per i genitori."))
        assertTrue(Regex("""Non vedi un maestro\? Invitalo dalla pagina <a href="/maestri"[^>]*>«Maestri»</a>: lo trovi qui dopo che ha accettato l’invito\.""").containsMatchIn(html))
        assertEquals(0, Regex("""checked""").findAll(html).count())
    }

    @Test
    fun `t4 c08 the chosen ones are saved and found again by surname and name, in the form and in the detail`() {
        val (browser, meId) = kit.start(me, paolo, marta)
        val id = gatherings.make(status = GatheringStatus.draft)

        val saved = browser.saveCustodians(id, kit.accountId(paolo.email), meId)

        assertEquals(303, saved.status())
        assertEquals(setOf(kit.accountId(paolo.email), meId), gatherings.custodiansOf(id).toSet())
        val form = browser.get("/convivenza/$id/affidatari").body()
        assertEquals(2, Regex("""checked""").findAll(form).count())
        assertTrue(Regex("""value="${kit.accountId(marta.email)}" class""").containsMatchIn(form), "Marta is there, not checked")
        val detail = browser.get("/convivenza/$id").body()
        assertTrue(text(detail).contains("Maestri affidatari salvati."))
        assertTrue(text(detail).contains("Elisa Conti Sei tu Paolo Verdi"), text(detail))
        assertFalse(text(detail).contains("Marta Bianchi"))

        // Changing the choice: the others are taken away, the new one is added.
        assertEquals(303, browser.saveCustodians(id, kit.accountId(marta.email)).status())
        assertEquals(listOf(kit.accountId(marta.email)), gatherings.custodiansOf(id))
    }

    @Test
    fun `t4 c08 a draft can be saved with no custodian, and a closed gathering that is over too`() {
        val (browser, meId) = kit.start(me, paolo)
        val draft = gatherings.make(status = GatheringStatus.draft, custodians = listOf(meId))

        assertEquals(303, browser.saveCustodians(draft).status())
        assertEquals(emptyList(), gatherings.custodiansOf(draft))

        gatherings.reset()
        val over = gatherings.make(status = GatheringStatus.closed, start = gatherings.today().minusDays(5), end = gatherings.today().minusDays(3), custodians = listOf(meId))
        assertEquals(303, browser.saveCustodians(over).status())
        assertEquals(emptyList(), gatherings.custodiansOf(over))
    }

    @Test
    fun `t4 c08 the last custodian of an open or closed gathering that is not over is not taken away, the save refuses and says why`() {
        val (browser, meId) = kit.start(me, paolo)

        for (status in listOf(GatheringStatus.`open`, GatheringStatus.closed)) {
            gatherings.reset()
            val id = gatherings.make(status = status, custodians = listOf(meId))

            val refused = browser.saveCustodians(id)

            assertEquals(422, refused.status(), "$status")
            assertTrue(text(refused.body()).contains("Scegli almeno un maestro: il testo dell’affidamento ha bisogno di almeno un nome."), "$status")
            assertEquals(1, Regex("""aria-invalid="true"""").findAll(refused.body()).count(), "$status: the first box carries it")
            assertTrue(Regex("""<fieldset aria-describedby="affidatari-aiuto affidatari-errore"""").containsMatchIn(refused.body()))
            assertEquals(listOf(meId), gatherings.custodiansOf(id), "$status: nothing was taken away")
            // With another one chosen the change goes through, taking the first away.
            assertEquals(303, browser.saveCustodians(id, kit.accountId(paolo.email)).status(), "$status")
            assertEquals(listOf(kit.accountId(paolo.email)), gatherings.custodiansOf(id), "$status")
        }
    }

    @Test
    fun `t4 c08 a person who is no longer a teacher is left out of the choice, and the last custodian rule still holds`() {
        val (browser, meId) = kit.start(me, paolo)
        val id = gatherings.make(status = GatheringStatus.`open`, custodians = listOf(meId))
        val gone = kit.accountId(paolo.email)
        dsl.deleteFrom(TEACHER).where(TEACHER.ACCOUNT_ID.eq(gone)).execute()

        // Only the one who is not a teacher any more is chosen: that is no one.
        val result = browser.saveCustodians(id, gone)

        assertEquals(422, result.status())
        assertEquals(listOf(meId), gatherings.custodiansOf(id))
    }

    // ---- «Togli» (criterion 40) ----------------------------------------------------------------------------------------------------

    @Test
    fun `t4 c40 in Maestri a custodian of a gathering that is not over has the explanation and the way to the custodians in place of Togli`() {
        val (browser, meId) = kit.start(me, paolo)
        val paoloId = kit.accountId(paolo.email)
        val id = gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.draft, custodians = listOf(paoloId, meId))

        val html = browser.get("/maestri").body()

        assertTrue(html.contains("Affidatario di «Convivenza di settembre 2027»: prima va tolto dagli affidatari."), "another teacher")
        assertTrue(html.contains("Sei affidatario di «Convivenza di settembre 2027»: per toglierti, prima togliti dagli affidatari."), "oneself")
        assertTrue(Regex("""<a href="/convivenza/$id/affidatari"[^>]*>Togli dagli affidatari</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="/convivenza/$id/affidatari"[^>]*>Togliti dagli affidatari</a>""").containsMatchIn(html))
        assertFalse(Regex("""data-apri="togli-\d""").containsMatchIn(html), "no button Togli and no dialog")
        assertFalse(html.contains("action=\"/maestri/togli/conferma\""))
    }

    @Test
    fun `t4 c40 the same for a gathering that is open, and for one that is closed with the end not past`() {
        val (browser, meId) = kit.start(me, paolo)
        val paoloId = kit.accountId(paolo.email)

        for (status in listOf(GatheringStatus.`open`, GatheringStatus.closed)) {
            gatherings.reset()
            gatherings.make(status = status, custodians = listOf(paoloId, meId))
            val html = browser.get("/maestri").body()
            assertTrue(html.contains("Affidatario di «Convivenza di settembre 2027»: prima va tolto dagli affidatari."), "$status")
            assertFalse(html.contains("""data-apri="togli-"""), "$status")
        }
    }

    @Test
    fun `t4 c40 a request made by hand to take the custodian away changes nothing and the block says why, with 409`() {
        val (browser, meId) = kit.start(me, paolo)
        val paoloId = kit.accountId(paolo.email)
        gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.`open`, custodians = listOf(paoloId, meId))

        val other = browser.act("/maestri/togli", mapOf("maestro" to paoloId.toString()))

        assertEquals(409, other.status())
        assertTrue(Regex("""role="alert" tabindex="-1" data-fuoco[^>]*>[\s\S]*?Non puoi togliere Paolo Verdi: è affidatario di «Convivenza di settembre 2027»\. Toglilo prima dagli affidatari\.""").containsMatchIn(other.body()), other.body())
        assertTrue(talita.account.TestAccounts.isTeacher(dsl, paolo.email))

        val self = browser.act("/maestri/togli", mapOf("maestro" to meId.toString()))
        assertEquals(409, self.status())
        assertTrue(text(self.body()).contains("Non puoi toglierti: sei affidatario di «Convivenza di settembre 2027». Togliti prima dagli affidatari."))
        assertEquals(2, dsl.fetchCount(TEACHER))
        assertEquals(2, dsl.fetchCount(GATHERING_CUSTODIAN))

        // Without JavaScript the page of confirmation is not offered either: back to the list.
        assertEquals(303, browser.submit("/maestri", "/maestri/togli/conferma", mapOf("maestro" to paoloId.toString())).status())
    }

    @Test
    fun `t4 c40 with two gatherings that are not over the row has a sentence and a button for each, and the button names the gathering`() {
        val (browser, meId) = kit.start(me, paolo)
        val paoloId = kit.accountId(paolo.email)
        val current = gatherings.make(title = "Convivenza di settembre 2027", status = GatheringStatus.`open`, custodians = listOf(paoloId, meId))
        // A closed gathering whose end has not passed cannot exist beside an open one that has just been made, so it is made closed first.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(current)).execute()
        val other = gatherings.make(title = "Convivenza di giugno 2027", status = GatheringStatus.draft, custodians = listOf(paoloId))

        val html = browser.get("/maestri").body()

        assertTrue(html.contains("Affidatario di «Convivenza di settembre 2027»: prima va tolto dagli affidatari."))
        assertTrue(html.contains("Affidatario di «Convivenza di giugno 2027»: prima va tolto dagli affidatari."))
        assertTrue(html.contains("""aria-label="Togli dagli affidatari di «Convivenza di settembre 2027»""""))
        assertTrue(html.contains("""aria-label="Togli dagli affidatari di «Convivenza di giugno 2027»""""))
        assertTrue(html.contains("""href="/convivenza/$current/affidatari"""") && html.contains("""href="/convivenza/$other/affidatari""""))
        val blocked = browser.act("/maestri/togli", mapOf("maestro" to paoloId.toString()))
        assertTrue(text(blocked.body()).contains("è affidatario di «Convivenza di giugno 2027» e di «Convivenza di settembre 2027»") || text(blocked.body()).contains("è affidatario di «Convivenza di settembre 2027» e di «Convivenza di giugno 2027»"), text(blocked.body()))
    }

    @Test
    fun `t4 c40 the title of the gathering in the explanation is text, never markup`() {
        val (browser, meId) = kit.start(me, paolo)
        gatherings.make(title = "<b>Convivenza</b> <script>x()</script>", status = GatheringStatus.draft, custodians = listOf(kit.accountId(paolo.email), meId))

        val html = browser.get("/maestri").body()

        assertFalse(html.contains("<b>Convivenza</b>"))
        assertFalse(html.contains("<script>x()"))
        assertTrue(html.contains("«&lt;b&gt;Convivenza&lt;/b&gt;"))
    }

    @Test
    fun `t4 c40 a custodian of gatherings that are over only is taken away with Togli, and the links with them go, the gatherings stay`() {
        val (browser, _) = kit.start(me, paolo)
        val paoloId = kit.accountId(paolo.email)
        val over = gatherings.today().minusDays(10)
        val id = gatherings.make(title = "Convivenza di settembre 2026", status = GatheringStatus.closed, start = over.minusDays(2), end = over, custodians = listOf(paoloId))

        val page = browser.get("/maestri").body()
        assertTrue(Regex("""data-apri="togli-\d"""").containsMatchIn(page), "the button is there")
        assertFalse(page.contains("prima va tolto dagli affidatari"))
        // The dialog adds one line about the gathering that is over.
        assertTrue(page.contains("Il suo nome sparisce anche dagli affidatari di «Convivenza di settembre 2026», che è finita."))

        val removed = browser.act("/maestri/togli", mapOf("maestro" to paoloId.toString()))

        assertEquals(200, removed.status())
        assertTrue(text(removed.body()).contains("Paolo Verdi non è più tra i maestri."))
        assertEquals(1, dsl.fetchCount(TEACHER))
        assertEquals(emptyList(), gatherings.custodiansOf(id), "the link with the gathering went with the name")
        assertEquals(1, dsl.fetchCount(GATHERING, GATHERING.ID.eq(id)), "the gathering stays")
    }

    @Test
    fun `t4 c40 the line of the dialog for oneself and for more than one gathering that is over`() {
        val (browser, meId) = kit.start(me, paolo)
        val paoloId = kit.accountId(paolo.email)
        val over = gatherings.today().minusDays(10)
        gatherings.make(title = "Convivenza di settembre 2025", status = GatheringStatus.closed, start = over.minusDays(400), end = over.minusDays(398), custodians = listOf(meId, paoloId))
        gatherings.make(title = "Convivenza di settembre 2026", status = GatheringStatus.closed, start = over.minusDays(2), end = over, custodians = listOf(meId, paoloId))

        val page = browser.get("/maestri").body()

        assertTrue(page.contains("Il tuo nome sparisce anche dagli affidatari di «Convivenza di settembre 2026» e di «Convivenza di settembre 2025», che sono finite."), page)
        assertTrue(page.contains("Il suo nome sparisce anche dagli affidatari di «Convivenza di settembre 2026» e di «Convivenza di settembre 2025», che sono finite."))
        // The page without JavaScript carries the same line.
        val confirmation = browser.submit("/maestri", "/maestri/togli/conferma", mapOf("maestro" to paoloId.toString()))
        assertEquals(200, confirmation.status())
        assertTrue(confirmation.body().contains("Il suo nome sparisce anche dagli affidatari di «Convivenza di settembre 2026» e di «Convivenza di settembre 2025», che sono finite."))
    }

    @Test
    fun `t4 c40 the day after the end the custodian can be taken away, with the clock of Rome`() {
        val (browser, meId) = kit.start(me, paolo)
        val paoloId = kit.accountId(paolo.email)
        // The last day is today: the gathering is not over, and Paolo cannot be taken away.
        gatherings.make(status = GatheringStatus.closed, start = gatherings.today().minusDays(2), end = gatherings.today(), custodians = listOf(paoloId, meId))

        assertEquals(409, browser.act("/maestri/togli", mapOf("maestro" to paoloId.toString())).status())
        assertEquals(1, teachers.custodianships()[paoloId]?.unfinished?.size)

        // The last day was yesterday: it is over.
        dsl.update(GATHERING).set(GATHERING.START_DATE, gatherings.today().minusDays(3)).set(GATHERING.END_DATE, gatherings.today().minusDays(1)).execute()
        assertEquals(0, teachers.custodianships()[paoloId]?.unfinished?.size ?: 0)
        assertEquals(200, browser.act("/maestri/togli", mapOf("maestro" to paoloId.toString())).status())
    }

    @Test
    fun `t4 c40 a teacher who is taken away at the same moment as they are chosen as a custodian is either refused or never chosen, and no custodian is left without being a teacher`() {
        val (_, meId) = kit.start(me, paolo)
        val paoloId = kit.accountId(paolo.email)
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(25) { round ->
                gatherings.reset()
                dsl.deleteFrom(TEACHER).where(TEACHER.ACCOUNT_ID.eq(paoloId)).execute()
                talita.account.TestAccounts.makeTeacher(dsl, paolo.email, "Paolo", "Verdi")
                val id = gatherings.make(status = GatheringStatus.draft, custodians = listOf(meId))
                val start = CountDownLatch(1)

                val choose = pool.submit<CustodiansResult> { start.await(); service.saveCustodians(id, setOf(meId, paoloId)) }
                val remove = pool.submit<talita.teacher.RemovalResult> { start.await(); teachers.remove(paoloId) }
                start.countDown()
                val saved = choose.get()
                val removal = remove.get()

                assertEquals(CustodiansResult.Saved, saved, "round $round")
                val custodians = gatherings.custodiansOf(id).toSet()
                val stillTeacher = dsl.fetchExists(TEACHER, TEACHER.ACCOUNT_ID.eq(paoloId))
                when (removal) {
                    is talita.teacher.RemovalResult.Removed -> {
                        // The removal came first: Paolo was never chosen (or the choice came first and was taken away with him: not for a draft).
                        assertFalse(stillTeacher, "round $round")
                        assertFalse(paoloId in custodians, "round $round: a custodian who is not a teacher")
                    }

                    is talita.teacher.RemovalResult.IsCustodian -> {
                        assertTrue(stillTeacher, "round $round")
                        assertTrue(paoloId in custodians, "round $round")
                    }

                    else -> error("round $round: $removal")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }
}
