package talita.e2e

import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.Media
import org.jooq.DSLContext
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.TestAccounts
import talita.gathering.GatheringFormat
import talita.gathering.GatheringSupport
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION
import talita.mail.Mailbox
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The information page for the parents and what the teachers do with it, in a real browser, on a phone and on a desktop, with axe (criteria 23-35): the three
 * variants and the 404, light and with nothing from other addresses and no cookie, the registration with the access of TALITA-3, the link to copy and share, the
 * preview and the printed sheet (also as a PDF of Chromium, written under `build/`, never in the repository).
 */
@E2eApplication
class InfoPageE2eTest {

    private val code = "q7Vd_3KmZ0xRb-Ew"

    private fun E2e.dsl(): DSLContext = application.getBean(DSLContext::class.java)

    private fun E2e.gatherings(): GatheringSupport = GatheringSupport(dsl(), application.getBean(Clock::class.java))

    /** No teacher but the person of the test, if there is one, and no gathering. Returns the id of the person's account (or 0 when nobody is inside). */
    private fun E2e.reset(): Long {
        dsl().deleteFrom(TEACHER_INVITATION).execute()
        dsl().deleteFrom(TEACHER).execute()
        val me = signedInAs?.let { TestAccounts.makeTeacher(dsl(), it, "Elisa", "Conti") } ?: 0L
        gatherings().reset()
        return me
    }

    private fun E2e.openGathering(
        status: GatheringStatus = GatheringStatus.`open`,
        title: String = "Convivenza di settembre 2027",
        place: String? = "Casa per ritiri di esempio",
        info: String? = "Possono partecipare i bambini dalla seconda elementare alla seconda media.\n\nCi si ritrova venerdì alle 18:00 davanti alla casa e si finisce domenica dopo il pranzo.",
        whatToBring: String? = "Sacco a pelo e cuscino\nTorcia\nBorraccia",
        custodians: List<Long> = emptyList(),
    ): Long {
        val gatherings = gatherings()
        val start = gatherings.today().plusDays(30)
        return gatherings.make(title = title, start = start, end = start.plusDays(2), status = status, place = place, info = info, whatToBring = whatToBring, code = code, custodians = custodians)
    }

    private fun E2e.overGathering(status: GatheringStatus): Long {
        val gatherings = gatherings()
        val end = gatherings.today().minusDays(3)
        return gatherings.make(title = "Convivenza di settembre 2026", start = end.minusDays(2), end = end, status = status, code = code, info = "Un testo che non si vede", whatToBring = "Una cosa che non si vede")
    }

    private fun Page.noHorizontalScroll() = (evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth") as Number).toInt() <= 0

    private fun Locator.buttonNamed(name: String): Locator = getByRole(AriaRole.BUTTON, Locator.GetByRoleOptions().setName(name))

    private fun Locator.linkNamed(name: String): Locator = getByRole(AriaRole.LINK, Locator.GetByRoleOptions().setName(name))

    private fun Page.focusIs(expression: String) = waitUntil("document.activeElement && ($expression)")

    private fun E2e.press(target: Locator) {
        target.evaluate("e => e.scrollIntoView({ block: 'center' })")
        touch(target)
    }

    /** What the browser has downloaded for the page, in bytes, and where from: the first visit of the page of [path]. */
    private data class Visit(val bytes: Long, val urls: List<String>)

    private fun Page.firstVisit(url: String): Visit {
        val sizes = CopyOnWriteArrayList<Long>()
        val urls = CopyOnWriteArrayList<String>()
        val listener = java.util.function.Consumer<com.microsoft.playwright.Response> {
            urls += it.url()
            sizes += runCatching { it.body().size.toLong() }.getOrDefault(0L)
        }
        onResponse(listener)
        navigate(url)
        waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE)
        offResponse(listener)
        return Visit(sizes.sum(), urls.toList())
    }

    // ---- The three variants (criteria 24, 26, 27, 32) ---------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c24 c32 with the registrations open the page is readable on a phone and on a desktop, with the button at the end as wide as the screen on the phone, and accessible`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        e2e.openGathering()
        val start = e2e.gatherings().today().plusDays(30)

        e2e.open("/info/$code")

        assertThat(page).hasTitle("Convivenza di settembre 2027 · Talità")
        assertThat(page.locator("h1")).hasText("Convivenza di settembre 2027")
        assertThat(page.locator("main h2")).hasText(arrayOf("Quando e dove", "Quanto costa", "Informazioni", "Cosa portare"))
        assertThat(page.locator("main")).containsText(GatheringFormat.days((0L..2L).map { start.plusDays(it) }))
        assertThat(page.locator("main")).containsText("Casa per ritiri di esempio")
        assertThat(page.locator("main")).containsText("Tutti i giorni 60 €")
        assertThat(page.locator("main")).containsText("Alcuni giorni 25 € per ogni giorno")
        assertThat(page.locator("main")).containsText("Solo pranzo 10 € per ogni pranzo")
        assertThat(page.locator("main section[aria-labelledby=informazioni] p")).hasCount(2)
        assertThat(page.locator("main section[aria-labelledby=portare] li")).hasCount(3)
        assertThat(page.locator("header a")).hasCount(0)
        assertThat(page.locator("nav")).hasCount(0)
        assertThat(page.locator("header")).containsText("Talità")
        val button = page.locator("main").linkNamed("Iscrivi i tuoi figli")
        assertThat(button).isVisible()
        assertThat(button).hasAttribute("href", "/info/$code/iscrizione")
        assertTrue(page.locator("main h2").last().box().y < button.box().y, "the button is after the information")
        assertThat(page.locator("main")).containsText("Per iscriverli entri con il tuo indirizzo email: ti mandiamo un link, senza password.")
        assertTrue(button.box().height >= 47.9, "the button is at least 48 px high")
        if (e2e.profile.isPhone) {
            val viewport = page.viewport()
            assertTrue(button.box().width >= viewport.width - 34, "the button is as wide as the screen: ${button.box().width} of ${viewport.width}")
            // The two columns of a card come after 640 px.
            assertTrue(page.locator("main dl").first().locator("dt").first().box().y < page.locator("main dl").first().locator("dd").first().box().y, "the label is above the value on a phone")
        } else {
            assertTrue(button.box().width < 300, "on a desktop the button has its own width, not the width of the column")
            assertEquals(1280, page.viewport().width)
        }
        assertTrue(page.noHorizontalScroll())
        page.waitForSettledContent()
        assertAccessible(page, "information page, open (${e2e.profile.label})")
    }

    @E2eTest(signedIn = false)
    fun `c26 with the registrations closed the same information is there with the notice, and no button`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        e2e.openGathering(status = GatheringStatus.closed)

        e2e.open("/info/$code")

        assertThat(page.locator("main")).containsText("Le iscrizioni sono chiuse. Per iscrivere un altro bambino o cambiare un’iscrizione già fatta, chiedi ai maestri.")
        assertThat(page.locator("main h2")).hasText(arrayOf("Quando e dove", "Quanto costa", "Informazioni", "Cosa portare"))
        assertThat(page.locator("main").linkNamed("Iscrivi i tuoi figli")).hasCount(0)
        assertThat(page.locator("main a")).hasCount(0)
        assertTrue(page.noHorizontalScroll())
        page.waitForSettledContent()
        assertAccessible(page, "information page, closed (${e2e.profile.label})")
    }

    @E2eTest(signedIn = false)
    fun `c27 a gathering that is over shows the title, the dates and the notice, whatever its state, and accessible`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        for (status in listOf(GatheringStatus.`open`, GatheringStatus.closed)) {
            e2e.gatherings().reset()
            e2e.overGathering(status)
            val end = e2e.gatherings().today().minusDays(3)

            e2e.open("/info/$code")

            assertThat(page.locator("h1")).hasText("Convivenza di settembre 2026")
            assertThat(page.locator("main")).containsText(GatheringFormat.range(end.minusDays(2), end))
            assertThat(page.locator("main")).containsText("La convivenza è finita. Per la prossima arriverà un link nuovo.")
            assertThat(page.locator("main h2")).hasCount(0)
            assertThat(page.locator("main section")).hasCount(0)
            assertThat(page.locator("main a")).hasCount(0)
            assertFalse(page.content().contains("Un testo che non si vede"), "$status")
            assertTrue(page.noHorizontalScroll())
            page.waitForSettledContent()
            assertAccessible(page, "information page, over, $status (${e2e.profile.label})")
        }
    }

    @E2eTest(signedIn = false)
    fun `c23 a draft and a code that does not exist show the same page, which says what to do, and leads to the access page`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        e2e.openGathering(status = GatheringStatus.draft, title = "Una bozza che nessuno deve vedere")

        val draft = e2e.open("/info/$code")
        val draftHtml = page.content()
        val unknown = e2e.open("/info/zzzzzzzzzzzzzzzz")

        assertEquals(404, draft.status())
        assertEquals(404, unknown.status())
        assertEquals(draftHtml, page.content(), "the page of a draft is not the same as the page of an unknown code")
        assertEquals("noindex, nofollow", unknown.headers()["x-robots-tag"])
        assertThat(page).hasTitle("Pagina non trovata · Talità")
        assertThat(page.locator("h1")).hasText("Pagina non trovata")
        assertThat(page.locator("main")).containsText("L’indirizzo potrebbe essere sbagliato o incompleto. Se te l’ha mandato un maestro, chiedigli di mandartelo di nuovo.")
        assertFalse(draftHtml.contains("Una bozza che nessuno deve vedere"))
        assertTrue(page.noHorizontalScroll())
        page.waitForSettledContent()
        assertAccessible(page, "information page, not found (${e2e.profile.label})")

        e2e.touch(page.locator("main").linkNamed("Vai all’accesso"))
        assertThat(page).hasURL(e2e.url("/accedi"))
    }

    // ---- Light, no cookie, nothing from outside (criterion 29) ------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c29 the first visit weighs less than 250 KB, asks for nothing outside the app, runs no script and leaves no cookie`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        e2e.openGathering()

        val visit = page.firstVisit(e2e.url("/info/$code"))

        assertTrue(visit.bytes < 250 * 1024, "the first visit weighs ${visit.bytes} bytes: ${visit.urls}")
        assertTrue(visit.urls.all { it.startsWith(e2e.url("/")) }, "a request outside the app: ${visit.urls}")
        assertTrue(visit.urls.none { it.contains("/js/") }, "a script was loaded: ${visit.urls}")
        assertEquals(0, (page.evaluate("document.scripts.length") as Number).toInt())
        assertEquals(emptyList(), e2e.browserContext.cookies(), "a cookie was set")
        assertEquals("", page.evaluate("document.cookie"))
        // Without JavaScript the page is the same.
        val noScript = e2e.newPage(signedIn = false, customize = { setJavaScriptEnabled(false) })
        noScript.navigate(e2e.url("/info/$code"))
        assertThat(noScript.locator("h1")).hasText("Convivenza di settembre 2027")
        assertThat(noScript.locator("main").linkNamed("Iscrivi i tuoi figli")).isVisible()
        // The link «Vai al contenuto» works with no script: it is an anchor.
        assertThat(noScript.locator("a[href='#contenuto']")).hasCount(1)
    }

    // ---- At 320, 360 and 1280 pixels, with long words (criterion 32) -------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c32 a title of 100 letters in one word, a place of 200 and long words wrap, with no horizontal scroll at 320, 360 and 1280`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        e2e.openGathering(
            title = "L".repeat(100), place = "P".repeat(200), info = "I".repeat(300) + "\n\n" + "https://".replace("https://", "") + "J".repeat(150), whatToBring = "C".repeat(200) + "\nTorcia",
        )

        for (width in listOf(320, 360, 1280)) {
            page.setViewportSize(width, 800)
            e2e.open("/info/$code")
            page.waitForSettledContent()
            assertTrue(page.noHorizontalScroll(), "the page scrolls sideways at $width px")
            for (card in page.locator("main section").all()) {
                val box = card.box()
                assertTrue(box.x + box.width <= width + 0.5, "a card goes out of the screen at $width px")
            }
        }
        page.setViewportSize(360, 800)
        e2e.open("/info/$code")
        page.waitForSettledContent()
        assertAccessible(page, "information page with long words (${e2e.profile.label})")
    }

    // ---- The registration (criterion 25) --------------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c25 the button leads to the access page and, with the link of the mail in the same browser, back to the registration, which says that it is being prepared`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        e2e.openGathering()
        e2e.open("/info/$code")

        e2e.touch(page.locator("main").linkNamed("Iscrivi i tuoi figli"))

        assertThat(page).hasURL(e2e.url("/accedi"))
        val address = Addresses.unique("e2e-genitore")
        page.waitForSettledContent()
        page.getByLabel("Indirizzo email").fill(address)
        e2e.touch(page.buttonNamed("Ricevi il link"))
        assertThat(page.locator("h1")).hasText("Controlla la posta")
        val link = Mailbox.waitForMail(address).links().single()
        page.navigate(e2e.url(link.removePrefix(TestcontainersConfiguration.PUBLIC_URL)))
        page.waitForSettledContent()
        e2e.touch(page.buttonNamed("Entra"))

        assertThat(page).hasURL(e2e.url("/info/$code/iscrizione"))
        assertThat(page).hasTitle("Iscrizione · Talità")
        assertThat(page.locator("h1")).hasText("Iscrizione")
        assertThat(page.locator("main")).containsText("Convivenza di settembre 2027")
        // The registration of TALITA-5 is where the page «in preparazione» was.
        assertThat(page.locator("main")).containsText("Passo 1 di 2 · I dati")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Iscrizioni")
        assertThat(page.locator("nav a")).hasText(arrayOf("Home", "Iscrizioni"))
        page.waitForSettledContent()
        assertAccessible(page, "registration, first step (${e2e.profile.label})")

        e2e.touch(page.locator("main").linkNamed("Annulla"))
        assertThat(page).hasURL(e2e.url("/info/$code"))
        assertThat(page.locator("main").linkNamed("Iscrivi i tuoi figli")).isVisible()

        // Once inside, the button leads straight there.
        e2e.touch(page.locator("main").linkNamed("Iscrivi i tuoi figli"))
        assertThat(page).hasURL(e2e.url("/info/$code/iscrizione"))
    }

    @E2eTest(teacher = false)
    fun `c25 with the registrations closed a person who is inside is led back to the information page, which says why`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        e2e.openGathering(status = GatheringStatus.closed)

        e2e.open("/info/$code/iscrizione")

        assertThat(page).hasURL(e2e.url("/info/$code"))
        assertThat(page.locator("main")).containsText("Le iscrizioni sono chiuse.")
    }

    // ---- The link of the teachers (criterion 34) -------------------------------------------------------------------------------------

    @E2eTest
    fun `c34 the card of the link shows the address as text, copies it with a tap and says so where it was touched, shares it when the browser can, and leads to the preview`(e2e: E2e) {
        val page = e2e.page
        e2e.browserContext.grantPermissions(listOf("clipboard-read", "clipboard-write"))
        page.addInitScript("window.share = null; navigator.share = async (data) => { window.sharedData = data; }")
        val me = e2e.reset()
        val id = e2e.openGathering(custodians = listOf(me))
        e2e.open("/convivenza/$id")
        val address = "${TestcontainersConfiguration.PUBLIC_URL}/info/$code"

        val card = page.locator("section[aria-labelledby=link]")
        assertThat(card.locator("h2")).hasText("Link per i genitori")
        assertThat(card).containsText("Condividilo con i genitori, per esempio su WhatsApp. Chi lo apre vede le informazioni, senza dover entrare.")
        assertThat(card).containsText("Indirizzo della pagina")
        assertThat(card.locator("#link-indirizzo")).hasText(address)
        assertEquals("all", card.locator("#link-indirizzo").evaluate("e => getComputedStyle(e).userSelect"), "the address is selectable with a tap held")
        val copy = card.buttonNamed("Copia il link")
        assertThat(copy).isVisible()
        assertThat(card.buttonNamed("Condividi")).isVisible()
        assertThat(card.linkNamed("Apri l’anteprima")).isVisible()
        // Open and not over: the main button is «Copia il link», the one of the state is not.
        assertEquals("rgb(42, 75, 160)", copy.evaluate("e => getComputedStyle(e).backgroundColor"))
        assertEquals("rgb(255, 255, 255)", page.locator("main").linkNamed("Chiudi le iscrizioni").evaluate("e => getComputedStyle(e).backgroundColor"))
        page.waitForSettledContent()
        assertAccessible(page, "detail with the link (${e2e.profile.label})")

        e2e.press(copy)

        assertThat(card.buttonNamed("Link copiato")).isVisible()
        assertEquals(address, page.evaluate("navigator.clipboard.readText()"))
        assertThat(card.locator("[data-copia-stato]")).hasText("Link copiato.")
        assertThat(card.locator("[data-copia-errore]")).isHidden()
        assertThat(page.locator("#messaggi [role=alert]")).hasCount(0)
        // After three seconds the button is again what it was.
        assertThat(card.buttonNamed("Copia il link")).isVisible(Locator.IsVisibleOptions().let { com.microsoft.playwright.assertions.LocatorAssertions.IsVisibleOptions().setTimeout(6000.0) })

        e2e.press(card.buttonNamed("Condividi"))
        page.waitUntil("window.sharedData && window.sharedData.url")
        assertEquals(address, page.evaluate("window.sharedData.url"))
        assertEquals("Convivenza di settembre 2027", page.evaluate("window.sharedData.title"))
        assertEquals("Informazioni: Convivenza di settembre 2027", page.evaluate("window.sharedData.text"))

        e2e.press(card.linkNamed("Apri l’anteprima"))
        assertThat(page).hasURL(e2e.url("/convivenza/$id/anteprima"))
    }

    @E2eTest
    fun `c34 the buttons that copy and share are not there where the browser cannot do it, and without JavaScript the address and the link to the preview remain`(e2e: E2e) {
        val me = e2e.reset()
        val id = e2e.openGathering(custodians = listOf(me))
        val page = e2e.newPage(customize = { setJavaScriptEnabled(false) })
        page.navigate(e2e.url("/convivenza/$id"))
        page.waitForSettledContent()

        val card = page.locator("section[aria-labelledby=link]")

        assertThat(card.locator("#link-indirizzo")).hasText("${TestcontainersConfiguration.PUBLIC_URL}/info/$code")
        assertThat(card.buttonNamed("Copia il link")).hasCount(0)
        assertThat(card.buttonNamed("Condividi")).hasCount(0)
        assertThat(card.linkNamed("Apri l’anteprima")).isVisible()
        assertEquals(0, card.locator("button:visible").count())
    }

    @E2eTest
    fun `c34 a draft has no address to copy, only a sentence and the preview`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.reset()
        val id = e2e.openGathering(status = GatheringStatus.draft, custodians = listOf(me))
        e2e.open("/convivenza/$id")

        val card = page.locator("section[aria-labelledby=link]")

        assertThat(card).containsText("Il link funziona dopo l’apertura delle iscrizioni. Intanto puoi vedere come sarà la pagina.")
        assertThat(card.locator("#link-indirizzo")).hasCount(0)
        assertThat(card.buttonNamed("Copia il link")).hasCount(0)
        assertThat(card.linkNamed("Apri l’anteprima")).isVisible()
        // The main action is to open the registrations.
        assertEquals("rgb(42, 75, 160)", page.locator("main").linkNamed("Apri le iscrizioni").evaluate("e => getComputedStyle(e).backgroundColor"))
        page.waitForSettledContent()
        assertAccessible(page, "detail of a draft with the link card (${e2e.profile.label})")
    }

    @E2eTest
    fun `c34 when the gathering is over and the registrations are still open the main button is Chiudi and the card says that the page tells it is over`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.reset()
        val gatherings = e2e.gatherings()
        val end = gatherings.today().minusDays(3)
        val id = gatherings.make(status = GatheringStatus.`open`, start = end.minusDays(2), end = end, code = code, custodians = listOf(me))
        e2e.open("/convivenza/$id")

        val card = page.locator("section[aria-labelledby=link]")

        assertThat(card).containsText("La pagina dice che la convivenza è finita.")
        assertEquals("rgb(255, 255, 255)", card.buttonNamed("Copia il link").evaluate("e => getComputedStyle(e).backgroundColor"))
        assertEquals("rgb(42, 75, 160)", page.locator("main").linkNamed("Chiudi le iscrizioni").evaluate("e => getComputedStyle(e).backgroundColor"))
    }

    // ---- The preview (criterion 35) ------------------------------------------------------------------------------------------------

    @E2eTest
    fun `c35 the preview shows the page as a parent sees it with the notice on top, a button that leads nowhere, noindex, and is accessible`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.reset()
        val id = e2e.openGathering(custodians = listOf(me))
        e2e.open("/convivenza/$id/anteprima")

        assertThat(page).hasTitle("Anteprima · Talità")
        assertThat(page.locator("h1")).hasText("Anteprima della pagina")
        assertThat(page.locator("h1")).hasCount(1)
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Convivenza")
        assertThat(page.locator("#avviso-anteprima")).hasText("Così vede la pagina chi apre il link adesso. Il bottone «Iscrivi i tuoi figli» qui non porta all’iscrizione.")
        assertThat(page.locator("meta[name=robots]")).hasAttribute("content", "noindex, nofollow")
        assertThat(page.locator("main h2").first()).hasText("Convivenza di settembre 2027")
        assertThat(page.locator("main h3")).hasText(arrayOf("Quando e dove", "Quanto costa", "Informazioni", "Cosa portare"))
        assertThat(page.locator("main").buttonNamed("Stampa la pagina")).isVisible()
        assertThat(page.locator("main").linkNamed("Torna alla convivenza")).isVisible()
        val fake = page.locator("main").buttonNamed("Iscrivi i tuoi figli")
        assertThat(fake).isVisible()
        assertThat(fake).hasAttribute("aria-describedby", "avviso-anteprima")
        assertThat(page.locator("a[href*='/iscrizione']")).hasCount(0)
        assertTrue(page.noHorizontalScroll())
        page.waitForSettledContent()
        assertAccessible(page, "preview (${e2e.profile.label})")

        e2e.press(fake)
        assertThat(page).hasURL(e2e.url("/convivenza/$id/anteprima"))
        assertThat(page.locator("#messaggi [role=alert]")).hasCount(0)

        e2e.touch(page.locator("main").linkNamed("Torna alla convivenza"))
        assertThat(page).hasURL(e2e.url("/convivenza/$id"))
    }

    @E2eTest
    fun `c35 the preview of a draft says how the page will be, with no button to print and no address, and closed or over say how they are`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.reset()
        val id = e2e.openGathering(status = GatheringStatus.draft, custodians = listOf(me))

        e2e.open("/convivenza/$id/anteprima")
        assertThat(page.locator("#avviso-anteprima")).hasText("Così apparirà la pagina quando aprirai le iscrizioni. Il bottone «Iscrivi i tuoi figli» qui non porta all’iscrizione.")
        assertThat(page.locator("main").buttonNamed("Stampa la pagina")).hasCount(0)
        assertEquals(0, page.locator("main .hidden.print\\:block").count(), "the block of the address is not drawn for a draft")
        assertFalse(page.content().contains("${TestcontainersConfiguration.PUBLIC_URL}/info/$code"), "the address of a draft is not true yet")

        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(id)).execute()
        e2e.open("/convivenza/$id/anteprima")
        assertThat(page.locator("#avviso-anteprima")).hasText("Così vede la pagina chi apre il link adesso.")
        assertThat(page.locator("main").linkNamed("Iscrivi i tuoi figli")).hasCount(0)
        assertThat(page.locator("main").buttonNamed("Iscrivi i tuoi figli")).hasCount(0)
        assertThat(page.locator("main")).containsText("Le iscrizioni sono chiuse.")
        assertThat(page.locator("main").buttonNamed("Stampa la pagina")).isVisible()

        val gatherings = e2e.gatherings()
        val end = gatherings.today().minusDays(3)
        e2e.dsl().update(GATHERING).set(GATHERING.START_DATE, end.minusDays(2)).set(GATHERING.END_DATE, end).where(GATHERING.ID.eq(id)).execute()
        e2e.open("/convivenza/$id/anteprima")
        assertThat(page.locator("#avviso-anteprima")).hasText("Così vede la pagina chi apre il link adesso: la convivenza è finita.")
        assertThat(page.locator("main")).containsText("La convivenza è finita. Per la prossima arriverà un link nuovo.")
    }

    // ---- Print (criterion 33) -------------------------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c33 printed, the page has black text on white, no button, no skip link, no header, the address in place of the button, and nothing goes out on the right`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        e2e.openGathering()
        e2e.open("/info/$code")
        page.waitForSettledContent()

        page.emulateMedia(Page.EmulateMediaOptions().setMedia(Media.PRINT).setColorScheme(ColorScheme.LIGHT))
        // An A4 sheet with the margins of the design (18 mm at the sides) has about 658 px of width.
        page.setViewportSize(658, 1000)

        // Playwright finds by role only what is visible, and in print there is not much of it: so these are found by selector.
        assertEquals("none", page.locator("main a[href$='/iscrizione']").evaluate("e => getComputedStyle(e.parentElement).display"))
        assertEquals("none", page.locator("header").evaluate("e => getComputedStyle(e).display"))
        assertEquals("none", page.locator("a[href='#contenuto']").evaluate("e => getComputedStyle(e).display"))
        val address = page.locator("main .print\\:block")
        assertThat(address).isVisible()
        assertThat(address).containsText("Per iscrivere i tuoi figli apri questo indirizzo dal telefono o dal computer:")
        assertThat(address).containsText("${TestcontainersConfiguration.PUBLIC_URL}/info/$code")
        assertThat(address).containsText("Poi entri con il tuo indirizzo email: ti mandiamo un link, senza password.")
        assertEquals("rgb(0, 0, 0)", page.locator("h1").evaluate("e => getComputedStyle(e).color"))
        assertEquals("rgb(0, 0, 0)", page.locator("main section").first().locator("dd").first().evaluate("e => getComputedStyle(e).color"))
        assertEquals("rgb(255, 255, 255)", page.locator("body").evaluate("e => getComputedStyle(e).backgroundColor"))
        assertEquals("rgb(255, 255, 255)", page.locator("main section").first().evaluate("e => getComputedStyle(e).backgroundColor"))
        assertTrue(page.noHorizontalScroll(), "the sheet is cut on the right")
        for (element in page.locator("main section, main h1, main .print\\:block").all()) {
            val box = element.box()
            assertTrue(box.x >= 0 && box.x + box.width <= 658.5, "an element goes out of the sheet: $box")
        }
        // The cards are not cut by a page: they ask not to be.
        assertEquals("avoid", page.locator("main section").first().evaluate("e => getComputedStyle(e).breakInside"))
        assertEquals("avoid", page.locator("main h2").first().evaluate("e => getComputedStyle(e).breakAfter"))

        // The sheet as Chromium prints it: one A4 page, written under build/ (never in the repository).
        val pdf = Path.of(System.getProperty("e2e.artifacts", "build/e2e-artifacts"), "print", "info-aperta-${e2e.profile.label}.pdf")
        Files.createDirectories(pdf.parent)
        page.setViewportSize(1280, 800)
        page.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
        val bytes = Files.readAllBytes(pdf)
        assertTrue(bytes.isNotEmpty())
        assertEquals(1, Regex("/Type\\s*/Page[^s]").findAll(String(bytes, Charsets.ISO_8859_1)).count(), "the sheet has more than one page")
    }

    /**
     * The longest texts that the forms accept: 5,000 characters of information and 2,000 of things to bring. Two ways of writing them: the information in
     * twenty paragraphs and one thing per line (about 55 lines), and the information as one paragraph and the things in a few long lines.
     */
    private fun longestTexts(): List<Pair<String, String>> {
        val sentence = "Ci si ritrova venerdì alle 18:00 davanti alla casa, si dorme in camerata e si finisce domenica dopo il pranzo, verso le 15:00. "
        val paragraphs = (1..20).joinToString("") { sentence.repeat(3).trim() + "\n\n" }.trim().take(5000)
        val lines = (1..200).joinToString("\n") { "Un oggetto da portare, il numero $it" }.take(2000).substringBeforeLast('\n')
        val oneParagraph = (1..40).joinToString(" ") { sentence.trim() }.take(5000)
        val longLines = (1..20).joinToString("\n") { "Tutto il necessario per dormire in camerata, per lavarsi e per stare all'aperto, il pacchetto numero $it" }.take(2000).substringBeforeLast('\n')
        check(paragraphs.length == 5000 && lines.length in 1900..2000 && oneParagraph.length == 5000 && longLines.length in 1500..2000)
        return listOf(paragraphs to lines, oneParagraph to longLines)
    }

    /** The number of pages of a PDF that Chromium wrote (the page objects are in the clear: Skia writes no object streams). */
    private fun pagesOf(pdf: Path): Int = Regex("/Type\\s*/Page[^s]").findAll(String(Files.readAllBytes(pdf), Charsets.ISO_8859_1)).count()

    @E2eTest(signedIn = false)
    fun `c33 with the texts at their maximum the long cards are split between the pages, so that no sheet is left with a large white space`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        val folder = Files.createTempDirectory("talita-stampa")
        try {
            for ((index, texts) in longestTexts().withIndex()) {
                val (info, things) = texts
                e2e.gatherings().reset()
                e2e.openGathering(info = info, whatToBring = things)
                page.setViewportSize(1280, 800)
                e2e.open("/info/$code")
                page.waitForSettledContent()

                // The PDF as Chromium prints it, A4 with the margins of the design, in a folder that is thrown away: never in the repository. (Not after emulating the
                // screen: then Chromium prints with the CSS of the screen, on a Letter sheet.)
                val pdf = folder.resolve("info-massimo-$index-${e2e.profile.label}.pdf")
                page.pdf(Page.PdfOptions().setPath(pdf).setPreferCSSPageSize(true).setPrintBackground(false))
                val pages = pagesOf(pdf)

                // How many sheets the content needs when each one is filled: its height printed in one piece (a sheet of A4 with the margins of the design is 658 px
                // wide and 265 mm, about 1,001 px, high) over the height of a sheet. A page that is split badly takes more sheets than this, with white at the bottom.
                page.emulateMedia(Page.EmulateMediaOptions().setMedia(Media.PRINT))
                page.setViewportSize(658, 1001)
                val height = (page.evaluate("document.documentElement.scrollHeight") as Number).toDouble()
                val needed = Math.ceil(height / 1001.0).toInt()

                assertTrue(pages > 1, "texts $index: the texts at their maximum fit on one page")
                assertTrue(pages <= needed, "texts $index: the sheet is $pages pages, and its content ($height px) fills $needed: some pages are left with white space")
            }
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @E2eTest
    fun `c33 the preview prints only the page, with the address and no notice, no buttons and no frame, and with the registrations closed there is no address`(e2e: E2e) {
        val page = e2e.page
        val me = e2e.reset()
        val id = e2e.openGathering(custodians = listOf(me))
        e2e.open("/convivenza/$id/anteprima")
        page.waitForSettledContent()
        page.emulateMedia(Page.EmulateMediaOptions().setMedia(Media.PRINT))
        page.setViewportSize(658, 1000)

        assertEquals("none", page.locator("#avviso-anteprima").evaluate("e => getComputedStyle(e.parentElement).display"))
        assertEquals("none", page.locator("main button[data-azione=stampa]").evaluate("e => getComputedStyle(e.parentElement.parentElement).display"))
        assertEquals("none", page.locator("header").evaluate("e => getComputedStyle(e).display"))
        assertEquals("none", page.locator("nav").evaluate("e => getComputedStyle(e).display"))
        assertEquals("none", page.locator("main button[aria-describedby=avviso-anteprima]").evaluate("e => getComputedStyle(e.parentElement).display"))
        assertThat(page.locator("main .print\\:block")).isVisible()
        val frame = page.locator("main h2").first().locator("xpath=ancestor::div[contains(@class,'bg-canvas')]")
        assertEquals("0px", frame.evaluate("e => getComputedStyle(e).borderTopWidth"))
        assertEquals("0px", frame.evaluate("e => getComputedStyle(e).paddingLeft"))
        assertTrue(page.noHorizontalScroll())
        assertEquals("rgb(0, 0, 0)", page.locator("main h2").first().evaluate("e => getComputedStyle(e).color"))

        // Closed: nothing to do, so no address.
        e2e.dsl().update(GATHERING).set(GATHERING.STATUS, GatheringStatus.closed).where(GATHERING.ID.eq(id)).execute()
        page.emulateMedia(Page.EmulateMediaOptions().setMedia(Media.SCREEN))
        e2e.open("/convivenza/$id/anteprima")
        assertEquals(0, page.locator("main .print\\:block").count())
        // The warning of «iscrizioni chiuse» has a black border on paper: no meaning depends on a background.
        page.emulateMedia(Page.EmulateMediaOptions().setMedia(Media.PRINT))
        assertEquals("1px", page.locator("main div.rounded-xl:has-text('Le iscrizioni sono chiuse')").last().evaluate("e => getComputedStyle(e).borderTopWidth"))
    }

    // ---- The mark and the link «Vai al contenuto» ---------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `the public shell has the mark that is not a link, the skip link as the first thing to reach with the keyboard, and the title of the page is the first heading`(e2e: E2e) {
        val page = e2e.page
        e2e.reset()
        e2e.openGathering()
        e2e.open("/info/$code")

        assertThat(page.locator("header p")).containsText("Talità")
        assertThat(page.locator("header a")).hasCount(0)
        assertThat(page.locator("html")).hasAttribute("lang", "it")
        if (!e2e.profile.isPhone) {
            page.keyboard().press("Tab")
            page.focusIs("document.activeElement.getAttribute('href') === '#contenuto'")
            assertThat(page.getByText("Vai al contenuto")).isVisible()
            page.keyboard().press("Enter")
            page.focusIs("document.activeElement.id === 'contenuto'")
        }
    }
}
