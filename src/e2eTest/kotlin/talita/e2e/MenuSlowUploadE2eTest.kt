package talita.e2e

import com.microsoft.playwright.Page
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import com.microsoft.playwright.options.AriaRole
import com.microsoft.playwright.options.FilePayload
import talita.gathering.MenuSamples
import talita.jooq.tables.references.GATHERING_MENU
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The button of the upload while the file leaves (TALITA-12, criterion 14): the answer of the server is held for a moment on purpose, and the page is looked at while the file is
 * on its way: «Caricamento…», the button disabled, the page dimmed. Apart from the other tests of the menu because it holds a request.
 *
 * While a navigation is on its way Playwright cannot read the page (its calls wait for the navigation to end), so the page writes down by itself what it looks like half a second
 * after the form was sent, in the storage of the session (which a new page of the same site still reads), and the test reads it when the answer has come.
 */
@E2eApplication
class MenuSlowUploadE2eTest {

    private fun file(name: String, mime: String, bytes: ByteArray): FilePayload = FilePayload(name, mime, bytes)

    private fun E2e.storedName(gathering: Long): String? =
        dsl().select(GATHERING_MENU.FILE_NAME).from(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(gathering)).fetchOne(GATHERING_MENU.FILE_NAME)

    @E2eTest
    fun `t12 c14 while the file leaves the button says Caricamento, is disabled and the page dims, and the answer replaces it`(e2e: E2e) {
        val page = e2e.page
        val w = e2e.arrivalsGathering()
        e2e.open("/convivenza/${w.id}/menu")
        page.waitForSettledContent()
        // What the page looks like 700 ms after the form was sent, written down by the page itself.
        page.evaluate(
            """() => {
                sessionStorage.removeItem('menu-busy');
                document.addEventListener('submit', () => {
                    setTimeout(() => {
                        const form = document.querySelector('main form');
                        const button = form.querySelector('button[type=submit]');
                        const shown = Array.from(button.querySelectorAll('span')).filter((s) => getComputedStyle(s).display !== 'none').map((s) => s.textContent.trim()).join('');
                        sessionStorage.setItem('menu-busy', JSON.stringify({
                            sending: form.classList.contains('htmx-request'),
                            disabled: button.disabled,
                            label: shown,
                            dimmed: parseFloat(getComputedStyle(document.querySelector('main')).opacity) < 1,
                        }));
                    }, 700);
                });
            }""",
        )
        // The answer of the server is held for a second and a half: the browser has sent the file and waits, as on a line with little signal.
        var held = 0L
        page.route("**/convivenza/${w.id}/menu") { route ->
            if (route.request().method() == "POST") {
                held = System.nanoTime()
                Thread.sleep(1500)
                route.resume()
            } else {
                route.resume()
            }
        }
        page.getByLabel("File del menù").setInputFiles(file("menu.pdf", "application/pdf", MenuSamples.pdf()))

        e2e.touch(page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Carica il menù")))

        assertThat(page).hasURL(e2e.url("/convivenza/${w.id}"))
        assertThat(page.locator("main [data-fuoco]")).hasText("Menù caricato.")
        assertTrue(held > 0, "the form was sent while the route was there")
        val seen = page.evaluate("sessionStorage.getItem('menu-busy')") as String?
        assertTrue(seen != null, "the page wrote down how it looked while the file was leaving")
        assertEquals("""{"sending":true,"disabled":true,"label":"Caricamento…","dimmed":true}""", seen, "while the file leaves")
        assertEquals("menu.pdf", e2e.storedName(w.id))
    }
}
