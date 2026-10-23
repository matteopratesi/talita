package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import talita.TestcontainersConfiguration
import talita.jooq.enums.GatheringStatus
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the teachers have to share the page (criteria 34 and 35) and what the page prints like (criterion 33): the card «Link per i genitori», the preview with its
 * notice and the button that leads nowhere, and the rules of print that the CSS carries.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class GatheringLinkTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired clock: Clock,
) {

    private val kit = GatheringTestKit(mockMvc, tokens, dsl, clock)
    private val gatherings = kit.gatherings
    private val code = "q7Vd_3KmZ0xRb-Ew"
    private val address = "${TestcontainersConfiguration.PUBLIC_URL}/info/$code"

    private fun card(html: String): String = Regex("""<section[^>]*aria-labelledby="link"[\s\S]*?</section>""").find(html)?.value.orEmpty()

    // ---- The card «Link per i genitori» (criterion 34) -------------------------------------------------------------------------------

    @Test
    fun `t4 c34 with the registrations open or closed the card shows the complete address as text, the two buttons hidden until the browser can, and the link to the preview`() {
        val (browser, me) = kit.start()
        for (status in listOf(GatheringStatus.`open`, GatheringStatus.closed)) {
            gatherings.reset()
            val id = gatherings.make(status = status, code = code, title = "Convivenza di settembre 2027", custodians = listOf(me))

            val html = card(browser.get("/convivenza/$id").body())

            assertTrue(html.contains(">Link per i genitori</h2>"), "$status")
            assertTrue(html.contains("Condividilo con i genitori, per esempio su WhatsApp. Chi lo apre vede le informazioni, senza dover entrare."), "$status")
            assertTrue(html.contains("Indirizzo della pagina"), "$status")
            assertTrue(Regex("""<p id="link-indirizzo" class="[^"]*select-all[^"]*">$address</p>""").containsMatchIn(html), "$status: the address is text that can be selected")
            // The buttons are there for a browser that can, hidden for the others, with both texts, so that the script only swaps them.
            assertTrue(Regex("""<button type="button" hidden data-azione="copia" data-copia="link-indirizzo"[^>]*>[\s\S]*?<span data-copia-normale>Copia il link</span><span hidden data-copia-fatto>Link copiato</span>""").containsMatchIn(html), "$status")
            assertTrue(Regex("""<button type="button" hidden data-azione="condividi" data-titolo="Convivenza di settembre 2027" data-testo="Informazioni: Convivenza di settembre 2027" data-indirizzo-da="link-indirizzo"[^>]*>[\s\S]*?Condividi""").containsMatchIn(html), "$status")
            assertTrue(Regex("""<p role="status" class="sr-only" data-copia-stato data-copia-annuncio="Link copiato."></p>""").containsMatchIn(html), "$status")
            assertTrue(html.contains("""<p hidden data-copia-errore class="mt-2 text-sm text-ink-muted">Non riesco a copiare il link. Tienilo premuto per copiarlo.</p>"""), "$status")
            assertTrue(Regex("""<a href="/convivenza/$id/anteprima"[^>]*>[\s\S]*?Apri l’anteprima</a>""").containsMatchIn(html), "$status")
            assertFalse(html.contains("La pagina dice che la convivenza è finita."), "$status")
        }
    }

    @Test
    fun `t4 c34 the main button is Copia il link, and Chiudi when the gathering is over and the registrations are still open`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.`open`, code = code, custodians = listOf(me))
        val primary = "bg-brand text-white hover:bg-brand-strong"

        val open = browser.get("/convivenza/$id").body()
        assertTrue(Regex("""data-azione="copia"[^>]*class="[^"]*$primary""").containsMatchIn(open), "Copia is the main button")
        assertFalse(Regex("""<a href="/convivenza/$id/chiudi"[^>]*class="[^"]*$primary""").containsMatchIn(open), "Chiudi is not")

        gatherings.reset()
        val end = gatherings.today().minusDays(3)
        val over = gatherings.make(status = GatheringStatus.`open`, code = code, start = end.minusDays(2), end = end, custodians = listOf(me))
        val html = browser.get("/convivenza/$over").body()
        assertFalse(Regex("""data-azione="copia"[^>]*class="[^"]*$primary""").containsMatchIn(html), "Copia is not the main button any more")
        assertTrue(Regex("""<a href="/convivenza/$over/chiudi"[^>]*class="[^"]*$primary""").containsMatchIn(html), "Chiudi is")
        assertTrue(card(html).contains("La pagina dice che la convivenza è finita."))
    }

    @Test
    fun `t4 c34 a draft has no address and no buttons to copy, only the sentence and the link to the preview`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, code = code, custodians = listOf(me))

        val html = card(browser.get("/convivenza/$id").body())

        assertTrue(html.contains("Il link funziona dopo l’apertura delle iscrizioni. Intanto puoi vedere come sarà la pagina."))
        assertFalse(html.contains(code), "the address of a draft is not shown: it does not work yet")
        assertFalse(html.contains("data-azione"))
        assertFalse(html.contains("link-indirizzo"))
        assertTrue(Regex("""<a href="/convivenza/$id/anteprima"[^>]*>[\s\S]*?Apri l’anteprima</a>""").containsMatchIn(html))
    }

    @Test
    fun `t4 c34 the card is after the state and before the data, the title of the gathering in the share text is text, and the address is never built from the host of the request`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.`open`, code = code, title = "<b>Convivenza</b> \"x\"", custodians = listOf(me))

        val result = browser.get("/convivenza/$id") { header("Host", "attacco.example.net") }
        val html = result.body()

        assertTrue(html.indexOf("aria-labelledby=\"stato\"") < html.indexOf("aria-labelledby=\"link\""))
        assertTrue(html.indexOf("aria-labelledby=\"link\"") < html.indexOf("aria-labelledby=\"quando\""))
        assertFalse(html.contains("<b>Convivenza</b>"))
        assertFalse(html.contains("attacco.example.net"), "the address comes from the setting of the site, not from the request")
        assertTrue(html.contains(address))
        assertTrue(Regex("""data-titolo="&lt;b>Convivenza&lt;/b> &#34;x&#34;"""").containsMatchIn(html) || Regex("""data-titolo="&lt;b&gt;Convivenza""").containsMatchIn(html), "the title is escaped in the attribute")
    }

    // ---- The preview (criterion 35) --------------------------------------------------------------------------------------------------

    @Test
    fun `t4 c35 the preview is the page as a parent sees it now, with the notice on top, noindex, and a button that does nothing`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.`open`, code = code, title = "Convivenza di settembre 2027", info = "Un paragrafo.\n\nUn altro.", whatToBring = "Torcia", custodians = listOf(me))

        val page = browser.get("/convivenza/$id/anteprima")
        val html = page.body()

        assertEquals(200, page.status())
        assertEquals("Anteprima · Talità", title(html))
        assertEquals("Anteprima della pagina", heading(html))
        assertEquals(1, Regex("""<h1[\s>]""").findAll(html).count(), "the only h1 is the one of the preview")
        assertTrue(Regex("""<h2 tabindex|<h2 id="" class="text-2xl""").containsMatchIn(html).not())
        assertTrue(Regex("""<h2 class="text-2xl font-semibold tracking-tight wrap-anywhere lg:text-3xl">Convivenza di settembre 2027</h2>""").containsMatchIn(html), "the title of the gathering is an h2 of the same size")
        assertEquals(listOf("Quando e dove", "Quanto costa", "Informazioni", "Cosa portare"), Regex("""<h3 id="[a-z]+" class="text-lg font-semibold">(.*?)</h3>""").findAll(html).map { it.groupValues[1] }.toList())
        assertTrue(Regex("""<div id="avviso-anteprima">[\s\S]*?Così vede la pagina chi apre il link adesso\. Il bottone «Iscrivi i tuoi figli» qui non porta all’iscrizione\.""").containsMatchIn(html))
        assertTrue(html.contains("""<meta name="robots" content="noindex, nofollow">"""))
        assertEquals("noindex, nofollow", page.response.getHeader("X-Robots-Tag"))
        assertFalse(html.contains("og:title") || html.contains("og:description"), "the preview is not shared")
        // The button that is not a link, described by the notice; no link to the registration, ever.
        assertTrue(html.contains("""<button type="button" aria-describedby="avviso-anteprima" class="""))
        assertFalse(html.contains("/iscrizione"))
        assertFalse(Regex("""<a [^>]*>\s*Iscrivi i tuoi figli""").containsMatchIn(html))
        // The same content as the public page, from the same component.
        val publicText = text(Regex("""<main[\s\S]*</main>""").find(mockMvc.get("/info/$code").andReturn().body())!!.value)
        for (part in listOf("Quando e dove", "Quanto costa Tutti i giorni 60 €", "Informazioni Un paragrafo. Un altro.", "Cosa portare Torcia")) {
            assertTrue(publicText.contains(part), "public: $part")
            assertTrue(text(html).contains(part), "preview: $part")
        }
        assertTrue(Regex("""data-azione="stampa"[^>]*>[\s\S]*?Stampa la pagina""").containsMatchIn(html))
        assertTrue(Regex("""<a href="/convivenza/$id"[^>]*>Torna alla convivenza</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="/convivenza" aria-current="page"""").containsMatchIn(html), "Convivenza is the current entry")
    }

    @Test
    fun `t4 c35 the preview of a draft is the page as it will be, with its own notice, and no way to print`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, code = code, custodians = listOf(me))

        val html = browser.get("/convivenza/$id/anteprima").body()

        assertTrue(text(html).contains("Così apparirà la pagina quando aprirai le iscrizioni. Il bottone «Iscrivi i tuoi figli» qui non porta all’iscrizione."))
        assertTrue(html.contains("""<button type="button" aria-describedby="avviso-anteprima" class="""), "the draft is shown as it will be when open")
        assertFalse(html.contains("data-azione=\"stampa\""))
        assertFalse(html.contains(address), "the address of a draft is not true yet")
        assertFalse(html.contains("apri questo indirizzo"))
    }

    @Test
    fun `t4 c35 closed and over say how things are, closed has no button and over has only the title and the dates`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.closed, code = code, custodians = listOf(me))

        val closed = browser.get("/convivenza/$id/anteprima").body()
        assertTrue(text(closed).contains("Così vede la pagina chi apre il link adesso."))
        assertFalse(text(closed).contains("qui non porta all’iscrizione"))
        assertTrue(text(closed).contains("Le iscrizioni sono chiuse."))
        assertFalse(closed.contains("Iscrivi i tuoi figli"))
        assertTrue(closed.contains("data-azione=\"stampa\""))
        assertFalse(closed.contains("apri questo indirizzo"), "nothing to print for the registrations")

        gatherings.reset()
        val end = gatherings.today().minusDays(3)
        val over = gatherings.make(status = GatheringStatus.`open`, code = code, start = end.minusDays(2), end = end, custodians = listOf(me), place = "Casa per ritiri di esempio")
        val html = browser.get("/convivenza/$over/anteprima").body()
        assertTrue(text(html).contains("Così vede la pagina chi apre il link adesso: la convivenza è finita."))
        assertTrue(text(html).contains("La convivenza è finita. Per la prossima arriverà un link nuovo."))
        assertFalse(html.contains("Casa per ritiri di esempio"))
    }

    @Test
    fun `t4 c35 the preview shows a text with markup as text, and for a gathering that does not exist it is a page that does not exist`() {
        val (browser, me) = kit.start()
        val id = gatherings.make(status = GatheringStatus.draft, code = code, title = "<script>x()</script>", info = "<b>grassetto</b>", custodians = listOf(me))

        val html = browser.get("/convivenza/$id/anteprima").body()

        assertFalse(html.contains("<script>x()") || html.contains("<b>grassetto"))
        assertTrue(html.contains("&lt;script>x()&lt;/script>") || html.contains("&lt;script&gt;x()&lt;/script&gt;"))
        assertEquals(404, browser.get("/convivenza/999999/anteprima").status())
    }

    // ---- Print (criterion 33) --------------------------------------------------------------------------------------------------------

    @Test
    fun `t4 c33 the CSS of the page has the rules of print outside every layer, A4 portrait, and the classes of the information page for print`() {
        val (_, me) = kit.start()
        gatherings.make(status = GatheringStatus.`open`, code = code, custodians = listOf(me), info = "Un testo", whatToBring = "Una cosa")
        val html = mockMvc.get("/info/$code").andReturn().body()
        val cssUrl = Regex("""<link rel="stylesheet" href="([^"]+)"""").find(html)!!.groupValues[1]

        val css = mockMvc.get(cssUrl).andReturn().body()

        assertTrue(css.contains("@media print{@page{size:A4 portrait;margin:16mm 18mm}"), "the page of print")
        val print = css.substringAfter("@media print{").substringBefore("}@property")
        assertTrue(print.contains("--color-ink:#000") && print.contains("--color-surface:#fff") && print.contains("--color-canvas:#fff"), "black on white")
        assertTrue(print.contains("body>header") && print.contains("#caricamento") && print.contains("#messaggi") && print.contains("body>nav"), "what is not printed")
        assertTrue(print.contains("break-after:avoid"), "a heading does not stay alone at the bottom")
        assertTrue(print.contains("font-size:11pt"))
        // The print block comes after every layer, so that it wins without !important.
        assertFalse(print.contains("!important"))
        // The classes of the page for print are in the CSS: the button and its line are hidden, the address is shown, the notices have a border.
        for (utility in listOf("print\\:hidden", "print\\:block", "print\\:border", "print\\:border-ink", "print\\:border-0", "print\\:bg-transparent", "print\\:p-0")) {
            assertTrue(css.contains(".$utility"), "no rule for $utility")
        }
        // The page: the button and its line are in a block that is hidden in print, the address is in a block that is only for print.
        assertTrue(Regex("""<div class="mt-8 print:hidden">[\s\S]*?Iscrivi i tuoi figli""").containsMatchIn(html))
        assertTrue(Regex("""<div class="mt-8 hidden break-inside-avoid border-t-2 border-ink pt-4 print:block">""").containsMatchIn(html))
        // The short cards are not cut by a page; the ones that can be long (information, things to bring) split, or the sheets stay half empty.
        assertEquals(2, Regex("""class="break-inside-avoid rounded-2xl""").findAll(html).count(), "the short cards are not cut by a page")
        for (long in listOf("informazioni", "portare")) {
            assertTrue(Regex("""<section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="$long">""").containsMatchIn(html), "the card $long can be split between pages")
        }
        assertTrue(print.contains("orphans:3") && print.contains("widows:3") && print.contains("li{break-inside:avoid}"), "a paragraph is not left with fewer than three lines, a list item is not cut")
    }
}
