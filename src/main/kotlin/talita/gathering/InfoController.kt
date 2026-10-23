package talita.gathering

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView
import org.springframework.stereotype.Component
import talita.account.CurrentAccounts
import talita.ui.ShellFactory

/**
 * What the pages of a code share: which gathering a code opens (not a draft), the headers of a page that must not be indexed and the page of a
 * code that leads nowhere. The information page and the registration under it (`talita.registration`) both go through here.
 */
@Component
class InfoPages(
    private val shells: ShellFactory,
    private val gatherings: Gatherings,
    private val accounts: CurrentAccounts,
) {

    /** The gathering for this code, if it exists and is not a draft. */
    fun visible(code: String): Gathering? = gatherings.findByCode(code)?.takeIf { it.state != GatheringState.DRAFT }

    /**
     * The same page for a draft and for a code that does not exist, for whoever asks (also a teacher). With an account it is in the shell
     * of the app, without one in the public shell; the text is the one for a link that arrived cut short.
     */
    fun notFound(): ModelAndView {
        val signedIn = accounts.current() != null
        val model = if (signedIn) {
            mapOf("shell" to shells.shell(page = "Pagina non trovata", current = null), "publicShell" to null)
        } else {
            mapOf("shell" to null, "publicShell" to shells.publicShell("Pagina non trovata", null))
        }
        return ModelAndView("pages/infoNotFound", model).also { it.status = HttpStatus.NOT_FOUND }
    }

    fun robots(response: HttpServletResponse) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow")
    }
}

/**
 * The information page for the parents, at an address with a code that nobody can guess (`/info/{codice}`). The way in to the registration
 * (`/info/{codice}/iscrizione`) is `talita.registration.EnrollmentController`.
 *
 * - **The page** is public: no access, no cookie (nothing here reads the CSRF code or the session), no script, nothing from other addresses,
 *   `noindex` in the page and in the header, never cached. It has three variants (registrations open, closed, gathering over) and shows
 *   [PublicInfo] only.
 * - **A draft and a code that does not exist** are the same answer, 404, with the same page: nobody can tell if a draft exists, not even a
 *   teacher.
 */
@Controller
class InfoController(
    private val shells: ShellFactory,
    private val infos: PublicInfos,
    private val pages: InfoPages,
) {

    @GetMapping("/info/{code}")
    fun info(@PathVariable code: String, response: HttpServletResponse): ModelAndView {
        pages.robots(response)
        val gathering = pages.visible(code) ?: return pages.notFound()
        val info = infos.of(gathering)
        return ModelAndView(
            "pages/info",
            mapOf("shell" to shells.publicShell(gathering.title, infos.share(gathering)), "info" to info),
        )
    }
}
