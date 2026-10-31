package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.account.PublicPaths
import talita.jooq.enums.GatheringStatus
import talita.teacher.Person
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The information page for the parents, as the server sends it (criteria 20, 23-31, 39): the three variants, the same 404 for a draft and for an unknown code,
 * no personal data, no cookie, no script, nothing from other addresses, never indexed, never cached, and the lines that a messaging app reads.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class InfoPageTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings

    private val marta = Person(Addresses.unique("info-marta"), "Marta", "Bianchi")
    private val paolo = Person(Addresses.unique("info-paolo"), "Paolo", "Verdi")

    private fun open(path: String): MvcResult = mockMvc.get(path).andReturn()

    private val code = "q7Vd_3KmZ0xRb-Ew"

    /** An open gathering that starts in a month, with every part filled in, and the two teachers as custodians. */
    private fun fullOpen(code: String = this.code, status: GatheringStatus = GatheringStatus.`open`): Long {
        val start = gatherings.today().plusDays(30)
        return gatherings.make(
            title = "Convivenza di settembre 2027", start = start, end = start.plusDays(2), status = status, place = "Casa per ritiri di esempio",
            full = 6000, daily = 2500, lunchOnly = 1000, code = code,
            info = "Possono partecipare i bambini dalla seconda elementare alla seconda media.\n\nCi si ritrova venerdì alle 18:00 davanti alla casa.\nSi finisce domenica dopo il pranzo.",
            whatToBring = "Sacco a pelo e cuscino\nTorcia\nBorraccia",
            custodians = listOf(kit.accountId(marta.email), kit.accountId(paolo.email)),
        )
    }

    private fun main(html: String): String = Regex("""<main[\s\S]*</main>""").find(html)?.value.orEmpty()

    // ---- Which address is public (criterion 39) ---------------------------------------------------------------------------------

    @Test
    fun `t4 c39 the list of the public addresses has the information page, and not the registration nor the gathering of the teachers`() {
        assertEquals(
            listOf("/accedi/**", "/actuator/health", "/error", "/info/*", "/informativa", "/promemoria/smetti", "/webauthn/authenticate/options", "/login/webauthn"),
            PublicPaths.pages,
        )
        assertTrue(PublicPaths.matches("/info/$code"))
        assertTrue(PublicPaths.matches("/info/Q7vd_3KmZ0xRb-Ew"), "any code: the page decides if it exists")
        for (path in listOf("/info/$code/iscrizione", "/info", "/info/$code/altro", "/convivenza", "/convivenza/1", "/convivenza/1/anteprima", "/informazioni/$code")) {
            assertFalse(PublicPaths.matches(path), path)
        }
    }

    @Test
    fun `t4 c39 whoever has no access and opens the registration goes to the access page, the information page is open`() {
        kit.start(marta, paolo)
        fullOpen()

        assertEquals(200, open("/info/$code").status())
        val registration = open("/info/$code/iscrizione")
        assertEquals(302, registration.status())
        assertEquals("/accedi", registration.location())
        // A code that does not exist, or an address under it that nothing serves, asks for access like every other address.
        assertEquals("/accedi", open("/info/$code/altro").location())
    }

    // ---- The three variants (criteria 24, 26, 27) ------------------------------------------------------------------------------------

    @Test
    fun `t4 c24 with the registrations open the page shows title, days, place, fees, text in paragraphs and the list, and the button last`() {
        kit.start(marta, paolo)
        fullOpen()
        val start = gatherings.today().plusDays(30)
        val days = (0L..2L).map { start.plusDays(it) }

        val page = open("/info/$code")
        val html = page.body()

        assertEquals(200, page.status())
        assertEquals("Convivenza di settembre 2027", heading(html))
        assertEquals("Convivenza di settembre 2027 · Talità", title(html))
        val text = text(main(html))
        assertTrue(text.contains("Quando e dove Quando ${GatheringFormat.days(days)} Dove Casa per ritiri di esempio"), text)
        assertTrue(text.contains("Quanto costa Tutti i giorni 60 € Alcuni giorni 25 € per ogni giorno Solo pranzo 10 € per ogni pranzo"), text)
        assertTrue(text.contains("Informazioni Possono partecipare i bambini dalla seconda elementare alla seconda media."))
        assertEquals(2, Regex("""<p class="whitespace-pre-line wrap-anywhere">""").findAll(html).count(), "two paragraphs")
        assertTrue(html.contains("Ci si ritrova venerdì alle 18:00 davanti alla casa.\nSi finisce domenica dopo il pranzo."), "a single line break stays in the paragraph")
        assertEquals(listOf("Sacco a pelo e cuscino", "Torcia", "Borraccia"), Regex("""<li class="pl-1">(.*?)</li>""").findAll(html).map { it.groupValues[1] }.toList())
        assertEquals(listOf("Quando e dove", "Quanto costa", "Informazioni", "Cosa portare"), Regex("""<h2 id="[a-z]+" class="text-lg font-semibold">(.*?)</h2>""").findAll(html).map { it.groupValues[1] }.toList())
        // The button is a link to the registration, the last thing of the page, with the line that tells what comes next.
        assertTrue(Regex("""<a href="/info/$code/iscrizione"[^>]*>Iscrivi i tuoi figli</a>""").containsMatchIn(html))
        assertTrue(text.contains("Per iscriverli entri con il tuo indirizzo email: ti mandiamo un link, senza password."))
        assertTrue(html.indexOf("Iscrivi i tuoi figli") > html.indexOf("Cosa portare"))
        assertFalse(html.contains("Le iscrizioni sono chiuse"))
        // The address, complete, for whoever prints it.
        assertTrue(html.contains("${TestcontainersConfiguration.PUBLIC_URL}/info/$code"))
        assertEquals(1, Regex("""<h1[\s>]""").findAll(html).count())
    }

    @Test
    fun `t4 c24 a part that is empty is not drawn, with its card`() {
        kit.start(marta, paolo)
        gatherings.make(status = GatheringStatus.`open`, code = code, full = 6000, daily = null, lunchOnly = null, info = null, whatToBring = null)

        val html = open("/info/$code").body()

        assertEquals(listOf("Quando e dove", "Quanto costa"), Regex("""<h2 id="[a-z]+" class="text-lg font-semibold">(.*?)</h2>""").findAll(html).map { it.groupValues[1] }.toList())
        assertTrue(text(main(html)).contains("Quanto costa Tutti i giorni 60 €"))
        assertFalse(html.contains("Alcuni giorni") || html.contains("Solo pranzo"), "the fees that are not provided are not there")
        assertFalse(html.contains("Informazioni") || html.contains("Cosa portare"))
        assertFalse(html.contains("<ul class=\"mt-3 list-disc"))
        assertTrue(html.contains("Iscrivi i tuoi figli"))
    }

    @Test
    fun `t4 c24 a fee that is free says Gratuita, never 0 €, and one that is not provided is not there`() {
        kit.start(marta, paolo)
        gatherings.make(status = GatheringStatus.`open`, code = code, full = 0, daily = null, lunchOnly = 0)

        val text = text(main(open("/info/$code").body()))

        assertTrue(text.contains("Quanto costa Tutti i giorni Gratuita Solo pranzo Gratuita"), text)
        assertFalse(text.contains("0 €"))
        assertFalse(text.contains("Alcuni giorni"))
    }

    @Test
    fun `t4 c26 with the registrations closed the page says it, has the same cards and no button`() {
        kit.start(marta, paolo)
        fullOpen(status = GatheringStatus.closed)

        val html = open("/info/$code").body()

        assertTrue(text(html).contains("Le iscrizioni sono chiuse. Per iscrivere un altro bambino o cambiare un’iscrizione già fatta, chiedi ai maestri."))
        assertTrue(text(main(html)).contains("Quando e dove Quando"))
        assertTrue(text(main(html)).contains("Quanto costa Tutti i giorni 60 €"))
        assertTrue(html.contains("Sacco a pelo e cuscino"))
        assertFalse(html.contains("Iscrivi i tuoi figli"))
        assertFalse(html.contains("/iscrizione"))
        assertFalse(html.contains("apri questo indirizzo"), "nothing to print for the registrations")
        // A notice of the page, not the answer to an action: no role.
        assertFalse(Regex("""role="(alert|status)"""").containsMatchIn(html))
    }

    @Test
    fun `t4 c27 once the gathering is over, in any state, the page has the title, the dates and a notice, and nothing else`() {
        kit.start(marta, paolo)
        val end = gatherings.today().minusDays(2)
        for (status in listOf(GatheringStatus.`open`, GatheringStatus.closed)) {
            gatherings.reset()
            gatherings.make(
                title = "Convivenza di settembre 2026", start = end.minusDays(2), end = end, status = status, place = "Casa per ritiri di esempio", full = 6000, code = code,
                info = "Un testo che non deve comparire", whatToBring = "Una cosa che non deve comparire",
            )

            val html = open("/info/$code").body()
            val text = text(main(html))

            assertEquals("Convivenza di settembre 2026", heading(html), "$status")
            assertTrue(text.contains(GatheringFormat.range(end.minusDays(2), end)), "$status")
            assertTrue(text.contains("La convivenza è finita. Per la prossima arriverà un link nuovo."), "$status")
            for (absent in listOf("Casa per ritiri di esempio", "Quando e dove", "Quanto costa", "60 €", "Un testo che non deve comparire", "Una cosa che non deve comparire", "Iscrivi i tuoi figli", "/iscrizione")) {
                assertFalse(html.contains(absent), "$status: «$absent» is on the page of a gathering that is over")
            }
        }
    }

    // ---- The same 404 for a draft and for a code that does not exist (criterion 23) ---------------------------------------------

    @Test
    fun `t4 c23 a draft, a code that does not exist and one that differs by a capital letter get the same page, 404, with and without access, teachers included`() {
        val (teacher, _) = kit.start(marta, paolo)
        gatherings.make(code = code, status = GatheringStatus.draft, title = "Una bozza che nessuno deve vedere")
        val parent = kit.parent()

        for ((who, ask) in listOf<Pair<String, (String) -> MvcResult>>(
            "anonymous" to { path -> open(path) },
            "teacher" to { path -> teacher.get(path) },
            "parent" to { path -> parent.get(path) },
        )) {
            val draft = ask("/info/$code")
            val unknown = ask("/info/zzzzzzzzzzzzzzzz")
            val capital = ask("/info/Q7Vd_3KmZ0xRb-Ew")
            val short = ask("/info/q7Vd_3KmZ0xRb")

            for ((name, result) in listOf("draft" to draft, "unknown" to unknown, "capital" to capital, "short" to short)) {
                assertEquals(404, result.status(), "$who $name")
                assertEquals("Pagina non trovata", heading(result.body()), "$who $name")
                assertEquals(draft.body(), result.body(), "$who $name: the page differs from the one of a draft")
                assertEquals(draft.response.getHeader("X-Robots-Tag"), result.response.getHeader("X-Robots-Tag"), "$who $name")
                assertEquals(draft.response.getHeader("Cache-Control"), result.response.getHeader("Cache-Control"), "$who $name")
                assertEquals(draft.response.getHeaders("Set-Cookie"), result.response.getHeaders("Set-Cookie"), "$who $name")
            }
            val html = draft.body()
            assertFalse(html.contains("Una bozza che nessuno deve vedere"), who)
            assertTrue(text(html).contains("L’indirizzo potrebbe essere sbagliato o incompleto. Se te l’ha mandato un maestro, chiedigli di mandartelo di nuovo."), who)
            assertTrue(html.contains("<title>Pagina non trovata · Talità</title>"), who)
            assertTrue(html.contains("""<meta name="robots" content="noindex, nofollow">""") || who != "anonymous", who)
            assertEquals("noindex, nofollow", draft.response.getHeader("X-Robots-Tag"), who)
        }
    }

    @Test
    fun `t4 c23 without access the 404 is in the public shell and leads to the access page, with access it is in the shell of the app and leads to the home`() {
        val (teacher, _) = kit.start(marta, paolo)

        val anonymous = open("/info/zzzzzzzzzzzzzzzz").body()
        assertFalse(anonymous.contains("<nav"))
        assertFalse(anonymous.contains("<script"))
        assertTrue(anonymous.contains(""">Vai all’accesso</a>"""))
        assertTrue(Regex("""<a href="/accedi" class="mt-6 inline-flex[^>]*>Vai all’accesso</a>""").containsMatchIn(anonymous))
        assertTrue(anonymous.contains("<path d=\"M10.5 9h11M16 9v14"), "the mark")
        assertFalse(Regex("""<a href="/"[^>]*>\s*<svg""").containsMatchIn(anonymous), "the mark is not a link")

        val inside = teacher.get("/info/zzzzzzzzzzzzzzzz").body()
        assertTrue(inside.contains("""aria-label="Navigazione principale""""))
        assertTrue(Regex("""<a href="/" class="mt-6 inline-flex[^>]*>Vai alla home</a>""").containsMatchIn(inside))
        assertFalse(Regex("""aria-current="page"""").containsMatchIn(Regex("""<nav[\s\S]*?</nav>""").find(inside)!!.value), "no entry is current")
    }

    // ---- Nothing personal (criterion 28) ------------------------------------------------------------------------------------------

    @Test
    fun `t4 c28 the page in each variant holds no name, surname or email of a teacher or of another account, in the body or in the headers`() {
        kit.start(marta, paolo)
        val secrets = listOf("Marta", "Bianchi", "Paolo", "Verdi", marta.email, paolo.email, marta.email.substringBefore('@'), paolo.email.substringBefore('@'), "affidatar")
        val end = gatherings.today().minusDays(3)

        for (variant in listOf("open", "closed", "over")) {
            gatherings.reset()
            fullOpen(status = if (variant == "closed") GatheringStatus.closed else GatheringStatus.`open`)
            if (variant == "over") dsl.update(talita.jooq.tables.references.GATHERING).set(talita.jooq.tables.references.GATHERING.START_DATE, end.minusDays(2)).set(talita.jooq.tables.references.GATHERING.END_DATE, end).execute()

            val page = open("/info/$code")
            val everything = page.body() + page.response.headerNames.joinToString("\n") { name -> "$name: ${page.response.getHeaders(name).joinToString()}" }

            assertEquals(200, page.status(), variant)
            for (secret in secrets) assertFalse(everything.contains(secret, ignoreCase = true), "$variant: '$secret' is in the page or in its headers")
            // No numbers about the registrations either: the only digits are those of dates and amounts, and none of them is a count.
            assertFalse(Regex("""\d+\s+(iscrizioni|iscritti|bambini|posti)""", RegexOption.IGNORE_CASE).containsMatchIn(text(main(page.body()))), variant)
        }
    }

    @Test
    fun `t4 c28 the template receives only what the page shows, not the gathering`() {
        // The information page is fed with PublicInfo, a class that has no custodians, no id of the account and no count.
        val fields = PublicInfo::class.java.declaredFields.map { it.name }.toSet()

        assertEquals(setOf("variant", "title", "dates", "days", "place", "fees", "paragraphs", "whatToBring", "registerHref", "address"), fields)
    }

    // ---- No cookie, no script, nothing from other addresses, light (criterion 29) ------------------------------------------------

    @Test
    fun `t4 c29 the page sets no cookie, has no script, no handler, no style, nothing from other addresses and no form, in each variant`() {
        kit.start(marta, paolo)
        val end = gatherings.today().minusDays(3)

        for (variant in listOf("open", "closed", "over", "not found")) {
            gatherings.reset()
            fullOpen(status = if (variant == "closed") GatheringStatus.closed else GatheringStatus.`open`)
            if (variant == "over") dsl.update(talita.jooq.tables.references.GATHERING).set(talita.jooq.tables.references.GATHERING.START_DATE, end.minusDays(2)).set(talita.jooq.tables.references.GATHERING.END_DATE, end).execute()

            val page = open(if (variant == "not found") "/info/zzzzzzzzzzzzzzzz" else "/info/$code")
            val html = page.body()

            assertEquals(emptyList(), page.response.getHeaders("Set-Cookie"), "$variant: a cookie")
            assertNull(page.request.getSession(false), "$variant: a session was started")
            assertFalse(Regex("""<script""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$variant: script")
            assertFalse(Regex("""<[^>]+\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$variant: on* attribute")
            assertFalse(Regex("""<[^>]+\s(hx-[a-z-]+|data-apri|data-azione)""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$variant: htmx or app.js hook")
            assertFalse(Regex("""<[^>]+\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$variant: style attribute")
            assertFalse(html.contains("<form") || html.contains("_csrf") || html.contains("htmx-config"), "$variant: a form or a code")
            assertFalse(html.contains("""id="messaggi"""") || html.contains("id=\"caricamento\"") || html.contains("<template"), "$variant: pop-up messages")
            val links = Regex("""\s(?:src|href|action|poster|data|formaction)\s*=\s*["']([^"']*)["']""").findAll(html).map { it.groupValues[1] }.toList()
            assertTrue(links.isNotEmpty())
            for (link in links) assertFalse(Regex("""^(?:[a-z][a-z0-9+.-]*:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(link) && !link.startsWith("#"), "$variant: link to $link")
        }
    }

    @Test
    fun `t4 c29 the first visit weighs less than 250 KB, the page and what it loads`() {
        kit.start(marta, paolo)
        fullOpen()

        val html = open("/info/$code").body()
        val css = Regex("""<link rel="stylesheet" href="([^"]+)"""").find(html)!!.groupValues[1]
        val font = Regex("""<link rel="preload" href="([^"]+\.woff2)"""").find(html)!!.groupValues[1]
        val icon = Regex("""<link rel="icon" href="([^"]+)"""").find(html)!!.groupValues[1]
        val bytes = listOf("/info/$code", css, font, icon).sumOf { open(it).response.contentAsByteArray.size }

        assertTrue(bytes < 250 * 1024, "the first visit weighs $bytes bytes")
    }

    // ---- Never indexed, never cached (criterion 30) -----------------------------------------------------------------------------

    @Test
    fun `t4 c30 the page carries noindex in the page and in the header, and is never cached`() {
        kit.start(marta, paolo)
        for (status in listOf(GatheringStatus.`open`, GatheringStatus.closed)) {
            gatherings.reset()
            fullOpen(status = status)

            val page = open("/info/$code")

            assertTrue(page.body().contains("""<meta name="robots" content="noindex, nofollow">"""), "$status")
            assertEquals("noindex, nofollow", page.response.getHeader("X-Robots-Tag"), "$status")
            val cache = page.response.getHeader("Cache-Control").orEmpty()
            assertTrue(cache.contains("no-cache") || cache.contains("no-store"), "$status: Cache-Control is '$cache'")
        }
    }

    // ---- The preview of the link in a messaging app (criterion 31) -------------------------------------------------------------

    @Test
    fun `t4 c31 an app that reads the page finds title, og title, og description with the dates and the place, og type website, and no image and no outside address`() {
        kit.start(marta, paolo)
        fullOpen()
        val start = gatherings.today().plusDays(30)

        val html = open("/info/$code").body()
        val head = Regex("""<head>[\s\S]*?</head>""").find(html)!!.value
        val description = "${GatheringFormat.range(start, start.plusDays(2))} · Casa per ritiri di esempio"

        assertTrue(head.contains("<title>Convivenza di settembre 2027 · Talità</title>"))
        assertTrue(head.contains("""<meta property="og:title" content="Convivenza di settembre 2027">"""))
        assertTrue(head.contains("""<meta property="og:description" content="$description">"""))
        assertTrue(head.contains("""<meta name="description" content="$description">"""))
        assertTrue(head.contains("""<meta property="og:type" content="website">"""))
        assertTrue(head.contains("""<meta property="og:site_name" content="Talità">"""))
        assertTrue(head.contains("""<meta property="og:locale" content="it_IT">"""))
        assertFalse(head.contains("og:image") || head.contains("og:url") || head.contains("twitter:"))
        assertFalse(Regex("""https?://""").containsMatchIn(head), "an address outside the app in the head")
    }

    @Test
    fun `t4 c31 closed adds that the registrations are closed, over says it is over and does not say where`() {
        kit.start(marta, paolo)
        val start = gatherings.today().plusDays(30)
        fullOpen(status = GatheringStatus.closed)
        assertTrue(open("/info/$code").body().contains("""<meta property="og:description" content="${GatheringFormat.range(start, start.plusDays(2))} · Casa per ritiri di esempio · iscrizioni chiuse">"""))

        val end = gatherings.today().minusDays(3)
        gatherings.reset()
        gatherings.make(title = "Convivenza di settembre 2026", start = end.minusDays(2), end = end, status = GatheringStatus.closed, code = code, place = "Casa per ritiri di esempio")
        val over = Regex("""<head>[\s\S]*?</head>""").find(open("/info/$code").body())!!.value
        assertTrue(over.contains("""<meta property="og:description" content="${GatheringFormat.range(end.minusDays(2), end)} · Convivenza finita">"""))
        assertFalse(over.contains("Casa per ritiri di esempio"))
    }

    // ---- Texts with markup (criterion 20) ----------------------------------------------------------------------------------------

    @Test
    fun `t4 c20 markup in the title, the place and the texts is text in the page, in the title of the tab and in the lines of the preview of the link`() {
        kit.start(marta, paolo)
        gatherings.make(
            title = "<script>alert(1)</script> & <b>titolo</b> \"x\"", place = "<i>Casa</i> & il suo giardino", status = GatheringStatus.`open`, code = code,
            info = "<script>x()</script>\n\n<b>grassetto</b> &amp; basta", whatToBring = "<img src=x onerror=y()>\n&amp;",
        )

        val html = open("/info/$code").body()

        for (markup in listOf("<script", "<b>titolo", "<b>grassetto", "<i>Casa", "<img src=x")) assertFalse(html.contains(markup), "«$markup» is markup in the page")
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt; &amp; &lt;b&gt;titolo&lt;/b&gt;"))
        val head = Regex("""<head>[\s\S]*?</head>""").find(html)!!.value
        assertFalse(head.contains("<script") || head.contains("<b>") || head.contains("<i>"), head)
        assertTrue(head.contains("&lt;script"), "the attribute is escaped: $head")
        // The attribute is not closed by the quotes of the title: its text is whole inside it, quotes escaped.
        val ogTitle = Regex("""<meta property="og:title" content="([^"]*)">""").find(head)?.groupValues?.get(1)
        assertTrue(ogTitle != null && ogTitle.contains("&lt;b") && ogTitle.contains("x&#34;") || ogTitle != null && ogTitle.contains("x&quot;"), head)
        assertTrue(Regex("""<meta name="description" content="[^"]*&lt;i>Casa&lt;/i>[^"]*">""").containsMatchIn(head), head)
    }

    // ---- The registration (criterion 25) -------------------------------------------------------------------------------------------

    @Test
    fun `t4 c25 someone with no access who taps the button goes to the access page and, after the link in the same browser, comes back to the registration`() {
        kit.start(marta, paolo)
        fullOpen()
        val browser = MockBrowser(mockMvc)
        val address = Addresses.unique("info-genitore")

        val asked = browser.get("/info/$code/iscrizione")
        assertEquals(302, asked.status())
        assertEquals("/accedi", asked.location())

        val tap = browser.confirm(kit.link(address))
        assertEquals("/info/$code/iscrizione", tap.location())
        val page = browser.get("/info/$code/iscrizione")
        assertEquals(200, page.status())
        assertEquals("Iscrizione", heading(page.body()))
        assertEquals("Iscrizione · Talità", title(page.body()))
        assertTrue(text(page.body()).contains("Convivenza di settembre 2027"), "the title of the gathering under the heading")
        // The registration of TALITA-5 has taken the place of the page «in preparazione» (its pages are in talita.registration).
        assertTrue(text(page.body()).contains("Passo 1 di 2 · I dati"))
        assertFalse(text(page.body()).contains("In preparazione"))
        assertTrue(Regex("""<a href="/info/$code"[^>]*>[\s\S]*?Annulla</a>""").containsMatchIn(page.body()))
        // The navigation is the one of a parent, with Iscrizioni as the current entry.
        assertEquals(listOf("Home", "Iscrizioni"), Regex("""<span class="max-w-full truncate">([^<]+)</span>""").findAll(page.body()).map { it.groupValues[1] }.toList())
        assertTrue(Regex("""<a href="/iscrizioni" aria-current="page"""").containsMatchIn(page.body()))
    }

    @Test
    fun `t4 c25 in another browser the link leads to the home, and with access already made the page is reached at once`() {
        kit.start(marta, paolo)
        fullOpen()
        val phone = MockBrowser(mockMvc)
        val address = Addresses.unique("info-due-browser")
        phone.get("/info/$code/iscrizione")

        val elsewhere = MockBrowser(mockMvc)
        assertEquals("/", elsewhere.confirm(kit.link(address)).location())

        assertEquals(200, elsewhere.get("/info/$code/iscrizione").status())
    }

    @Test
    fun `t4 c25 with the registrations closed, or the gathering over, a person who is inside is led back to the information page, which says why`() {
        kit.start(marta, paolo)
        val parent = kit.parent()
        fullOpen(status = GatheringStatus.closed)

        val closed = parent.get("/info/$code/iscrizione")
        assertEquals(303, closed.status())
        assertEquals("/info/$code", closed.location())

        val end = gatherings.today().minusDays(3)
        gatherings.reset()
        gatherings.make(code = code, start = end.minusDays(2), end = end, status = GatheringStatus.`open`)
        val over = parent.get("/info/$code/iscrizione")
        assertEquals(303, over.status())
        assertEquals("/info/$code", over.location())
    }

    @Test
    fun `t4 c25 for a draft or a code that does not exist the registration is the same 404, in the shell of the app`() {
        kit.start(marta, paolo)
        val parent = kit.parent()
        gatherings.make(code = code, status = GatheringStatus.draft)

        val draft = parent.get("/info/$code/iscrizione")
        val unknown = parent.get("/info/zzzzzzzzzzzzzzzz/iscrizione")

        assertEquals(404, draft.status())
        assertEquals(404, unknown.status())
        assertEquals(draft.body(), unknown.body())
        assertEquals("Pagina non trovata", heading(draft.body()))
        assertTrue(draft.body().contains("""aria-label="Navigazione principale""""))
    }

    @Test
    fun `t4 c25 a query added by an app, like fbclid, changes nothing`() {
        kit.start(marta, paolo)
        fullOpen()

        val plain = open("/info/$code")
        val withQuery = open("/info/$code?fbclid=IwAR0abcdef&utm_source=whatsapp")

        assertEquals(200, withQuery.status())
        assertEquals(plain.body(), withQuery.body())
    }
}
