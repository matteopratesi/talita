package talita.e2e

import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.jooq.DSLContext
import talita.Addresses
import talita.account.TestAccounts
import talita.teacher.Invitations
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two roles in a real browser (criteria 31-37): the navigation and the pages of a parent, the page
 * 403 and the message «Accesso negato», the invitation to be a teacher with its name form, the role that
 * changes while the person is inside. On a phone and on a desktop, with axe.
 */
@E2eApplication
class RolesE2eTest {

    // ---- A parent (criterion 34) ------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c34 a parent has two entries in the navigation, each half of the bar, and the home and the registrations for a parent`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/")

        assertEquals(listOf("Home", "Iscrizioni"), page.locator("nav a").allInnerTexts().map { it.trim() })
        if (e2e.profile.isPhone) {
            val bar = page.mainNavigation().box()
            for (entry in page.locator("nav li").all()) {
                val box = entry.box()
                assertTrue(Math.abs(box.width - (bar.width - 8) / 2) < 3, "an entry is ${box.width} px wide in a bar of ${bar.width}")
            }
        }
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
        assertThat(page.locator("main")).containsText("Qui iscrivi i tuoi figli alla convivenza e controlli le loro iscrizioni.")
        assertThat(page.locator("main")).containsText("Talità è in costruzione. Le iscrizioni arriveranno presto.")
        assertThat(page.locator("main h2")).hasText("Cosa trovi qui")
        assertEquals(1, page.locator("main ul a").count(), "the list of the home of a parent has only the registrations")
        assertThat(page.locator("main ul a")).containsText("Le iscrizioni dei tuoi figli.")
        assertAccessible(page, "home of a parent (${e2e.profile.label})")

        e2e.touch(page.navLink("Iscrizioni"))

        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
        assertThat(page.locator("main")).containsText("Qui troverai le iscrizioni dei tuoi figli.")
        assertThat(page.locator("nav a[aria-current=page]")).hasText("Iscrizioni")
        assertAccessible(page, "registrations of a parent (${e2e.profile.label})")
    }

    @E2eTest(teacher = false)
    fun `c34 the profile of a parent has no row for the role, the one of a teacher has`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/profilo")

        assertThat(page.locator("main")).not().containsText("Ruolo")

        val teacher = e2e.newPage(teacher = true)
        teacher.navigate(e2e.url("/profilo"))
        assertThat(teacher.locator("main")).containsText("Ruolo")
        assertThat(teacher.locator("main dd").nth(1)).hasText("Maestro")
        assertAccessible(teacher, "profile of a teacher (${e2e.profile.label})")
    }

    // ---- 403 (criterion 35) -------------------------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c35 a parent who opens a page of the teachers sees the page Non puoi vedere questa pagina, in the shell, with no current entry`(e2e: E2e) {
        val page = e2e.page

        for (path in listOf("/convivenza", "/arrivi", "/maestri")) {
            val response = e2e.open(path)

            assertEquals(403, response.status(), path)
            assertThat(page).hasTitle("Non puoi vedere questa pagina · Talità")
            assertThat(page.locator("h1")).hasText("Non puoi vedere questa pagina")
            assertThat(page.locator("main")).containsText("La pagina esiste, ma il tuo accesso non comprende questi dati. Se pensi di doverla vedere, chiedi a chi organizza la convivenza.")
            assertThat(page.mainNavigation()).isVisible()
            assertThat(page.locator("nav [aria-current]")).hasCount(0)
            assertEquals(2, page.locator("nav a").count())
        }
        assertAccessible(page, "page 403 (${e2e.profile.label})")

        e2e.touch(page.linkNamed("Vai alla home"))
        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("h1")).hasText("Ti diamo il benvenuto")
    }

    @E2eTest(teacher = false)
    fun `c35 a link to a page of the teachers shows the page 403 like any page, with its address, and the back button comes back`(e2e: E2e) {
        val page = e2e.page
        e2e.open("/iscrizioni")
        page.addToContent("""<a id="link-vietato" href="/maestri">Maestri</a>""")

        val response = page.waitForResponse({ it.url().endsWith("/maestri") }) { e2e.touch(page.locator("#link-vietato")) }

        assertEquals(403, response.status())
        assertThat(page).hasURL(e2e.url("/maestri"))
        assertThat(page.locator("h1")).hasText("Non puoi vedere questa pagina")
        assertEquals(0, page.messages().count(), "a 403 on a link is a page, not a message")
        page.goBack()
        assertThat(page).hasURL(e2e.url("/iscrizioni"))
        assertThat(page.locator("h1")).hasText("Iscrizioni")
    }

    @E2eTest(teacher = false)
    fun `c35 a request that does not change the page and is refused leaves the page as it is and shows the message Accesso negato, with no Riprova`(e2e: E2e) {
        val page = e2e.page
        // The profile has a form, and so the CSRF code that the form of a person needs.
        e2e.open("/profilo")
        val csrf = page.locator("input[name=_csrf]").inputValue()
        page.addToContent(
            """<form id="invito-a-mano" method="post" action="/maestri/invita" hx-post="/maestri/invita" hx-target="#bersaglio">
                 <input type="hidden" name="_csrf" value="$csrf"><input type="hidden" name="email" value="${Addresses.unique("a-mano")}">
                 <button id="manda" type="submit">Manda</button></form>
               <div id="bersaglio"><p>Contenuto di prima</p></div>
               <button id="leggi" type="button" hx-get="/convivenza" hx-target="#bersaglio">Leggi</button>""",
        )

        e2e.touch(page.locator("#manda"))

        val message = page.messages().filter(textFilter("Accesso negato"))
        assertThat(message).hasCount(1)
        assertThat(message).containsText("Non hai il permesso di farlo. Se pensi di doverlo fare, chiedi a chi organizza la convivenza.")
        assertEquals(0, page.messages().locator("[data-azione=riprova]").count(), "to repeat a refused request changes nothing")
        assertThat(page.locator("#bersaglio")).hasText("Contenuto di prima")
        assertThat(page).hasURL(e2e.url("/profilo"))
        assertThat(page.locator("h1")).hasText("Profilo")
        page.waitUntil("document.querySelector('#messaggi [role=alert]').getAnimations().length === 0")
        assertAccessible(page, "message Accesso negato open (${e2e.profile.label})")

        // A read of a piece of a page: the same message, and no 403 page inside the piece.
        e2e.touch(page.locator("#leggi"))
        assertThat(page.locator("#bersaglio")).hasText("Contenuto di prima")
        assertThat(page.messages().filter(textFilter("Accesso negato"))).hasCount(1)
        assertEquals(1, page.locator("h1").count())

        e2e.touch(page.messages().locator("[data-azione=chiudi]"))
        assertThat(page.messages()).hasCount(0)
    }

    @E2eTest(teacher = false, signedIn = true)
    fun `c35 without JavaScript the page 403 comes from the server with the same texts`(e2e: E2e) {
        val page = e2e.newPage(teacher = false) { setJavaScriptEnabled(false) }

        val response = page.navigate(e2e.url("/arrivi"))

        assertEquals(403, requireNotNull(response).status())
        assertThat(page.locator("h1")).hasText("Non puoi vedere questa pagina")
        assertThat(page.linkNamed("Vai alla home")).hasAttribute("href", "/")
    }

    /** The field «Nome»; the label «Cognome» also contains the word, so it has to be exact. */
    private fun com.microsoft.playwright.Page.nameField() = getByLabel("Nome", com.microsoft.playwright.Page.GetByLabelOptions().setExact(true))

    private fun textFilter(text: String) = com.microsoft.playwright.Locator.FilterOptions().setHasText(text)

    // ---- Role read at every request (criterion 37) ------------------------------------------------------------------------

    @E2eTest(teacher = false)
    fun `c37 a role that changes while the person is inside shows at the next touch, with no new login`(e2e: E2e) {
        val page = e2e.page
        val dsl = e2e.application.getBean(DSLContext::class.java)
        val address = checkNotNull(e2e.signedInAs)
        e2e.open("/")
        assertEquals(2, page.locator("nav a").count())

        // The invitation is accepted (here: the row that it makes): at the next touch the person has the four entries.
        TestAccounts.makeTeacher(dsl, address)
        e2e.touch(page.navLink("Iscrizioni"))

        assertThat(page.locator("nav a")).hasCount(4)
        assertThat(page.locator("main")).containsText("potrai inserire le iscrizioni arrivate sul modulo cartaceo")

        // The teacher is removed: the touch after is already the one of a parent, and the page of the teachers is a 403.
        TestAccounts.removeTeacher(dsl, address)
        e2e.touch(page.navLink("Home"))
        assertThat(page.locator("nav a")).hasCount(2)
        assertEquals(403, e2e.open("/convivenza").status())
    }

    // ---- The invitation (criteria 31 and 32) ------------------------------------------------------------------------------------

    @E2eTest(signedIn = false)
    fun `c31 whoever enters with an address that has an invitation arrives at Accetta l'invito, and with name and surname becomes a teacher`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("invitata")
        e2e.application.getBean(Invitations::class.java).invite(talita.account.EmailAddress.parse(address)!!)

        e2e.enterWithTheMail(address)

        assertThat(page).hasURL(e2e.url("/invito"))
        assertThat(page).hasTitle("Accetta l’invito · Talità")
        assertThat(page.locator("h1")).hasText("Accetta l’invito")
        assertThat(page.locator("main")).containsText("Ti hanno invitato a organizzare la convivenza come maestro. Il tuo nome compare nell’elenco dei maestri e nei documenti della convivenza.")
        assertThat(page.locator("nav a[aria-current]")).hasCount(0)
        assertEquals("given-name", page.nameField().attr("autocomplete"))
        assertEquals("family-name", page.getByLabel("Cognome").attr("autocomplete"))
        assertEquals("words", page.nameField().attr("autocapitalize"))
        assertAccessible(page, "accept the invitation (${e2e.profile.label})")

        // Nothing written: two errors, the notice on top, the focus on the first field.
        e2e.touch(page.buttonNamed("Accetta l’invito"))
        assertThat(page.getByText("Scrivi il nome.")).isVisible()
        assertThat(page.getByText("Scrivi il cognome.")).isVisible()
        assertThat(page.locator("main [role=alert]")).containsText("Controlla i 2 campi segnati in rosso.")
        page.waitUntil("document.activeElement && document.activeElement.id === 'nome'")
        assertThat(page).hasURL(e2e.url("/invito"))
        assertAccessible(page, "accept the invitation with errors (${e2e.profile.label})")

        // One field: one error under it, no notice.
        page.nameField().fill("Niccolò")
        e2e.touch(page.buttonNamed("Accetta l’invito"))
        // (The page of before had both errors: the one of the name has to go away before anything else is true.)
        assertThat(page.getByText("Scrivi il nome.")).hasCount(0)
        assertThat(page.getByText("Scrivi il cognome.")).isVisible()
        assertThat(page.nameField()).hasValue("Niccolò")
        assertThat(page.locator("main [role=alert]")).hasCount(0)
        page.waitUntil("document.activeElement && document.activeElement.id === 'cognome'")

        page.getByLabel("Cognome").fill("D’Angiò")
        e2e.touch(page.buttonNamed("Accetta l’invito"))

        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("main [role=status]")).hasText("Invito accettato. Ora sei tra i maestri.")
        assertThat(page.locator("nav a")).hasCount(4)
        assertThat(page.linkNamed("Profilo")).containsText("ND")
        assertAccessible(page, "home after accepting (${e2e.profile.label})")
    }

    @E2eTest(signedIn = false)
    fun `c32 later leads to the home as a parent, where a notice reminds of the invitation with the way to accept it`(e2e: E2e) {
        val page = e2e.page
        val address = Addresses.unique("piu-tardi")
        e2e.application.getBean(Invitations::class.java).invite(talita.account.EmailAddress.parse(address)!!)
        e2e.enterWithTheMail(address)
        assertThat(page).hasURL(e2e.url("/invito"))

        e2e.touch(page.linkNamed("Più tardi"))

        assertThat(page).hasURL(e2e.url("/"))
        assertThat(page.locator("nav a")).hasCount(2)
        val notice = page.locator("main [role=status]")
        assertThat(notice).containsText("Ti hanno invitato come maestro. L’invito vale fino al")
        assertThat(notice.locator("a")).hasText("Accetta l’invito")
        assertAccessible(page, "home with the reminder of the invitation (${e2e.profile.label})")

        e2e.touch(notice.locator("a"))

        assertThat(page).hasURL(e2e.url("/invito"))
        assertThat(page.locator("h1")).hasText("Accetta l’invito")
    }
}
