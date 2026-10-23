package talita.gathering

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.servlet.ModelAndView
import talita.account.CurrentAccount
import talita.account.CurrentAccounts
import talita.account.seeOther
import talita.ui.Section
import talita.ui.ShellFactory

/** The page that a person with an account sees where the registration will be: until TALITA-5 it says that it is being prepared. */
data class RegistrationSoonPage(val title: String, val infoHref: String)

/**
 * The information page for the parents, at an address with a code that nobody can guess (`/info/{codice}`), and the way in to the
 * registration (`/info/{codice}/iscrizione`).
 *
 * - **The page** is public: no access, no cookie (nothing here reads the CSRF code or the session), no script, nothing from other addresses,
 *   `noindex` in the page and in the header, never cached. It has three variants (registrations open, closed, gathering over) and shows
 *   [PublicInfo] only.
 * - **A draft and a code that does not exist** are the same answer, 404, with the same page: nobody can tell if a draft exists, not even a
 *   teacher.
 * - **The registration** asks for an account (the access of TALITA-3, and back here after it in the same browser). Until TALITA-5 it says «in
 *   preparazione»; with the registrations closed or the gathering over it leads back to the information page, which says why.
 */
@Controller
class InfoController(
    private val shells: ShellFactory,
    private val gatherings: Gatherings,
    private val infos: PublicInfos,
    private val accounts: CurrentAccounts,
) {

    @GetMapping("/info/{code}")
    fun info(@PathVariable code: String, response: HttpServletResponse): ModelAndView {
        robots(response)
        val gathering = visible(code) ?: return notFound()
        val info = infos.of(gathering)
        return ModelAndView(
            "pages/info",
            mapOf("shell" to shells.publicShell(gathering.title, infos.share(gathering)), "info" to info),
        )
    }

    @GetMapping("/info/{code}/iscrizione")
    fun registration(@PathVariable code: String, account: CurrentAccount, response: HttpServletResponse): ModelAndView {
        robots(response)
        val gathering = visible(code) ?: return notFound()
        if (!gathering.registrationsPossible(gatherings.today())) return seeOther("/info/${gathering.publicCode}")
        return ModelAndView(
            "gathering/registrationSoon",
            mapOf(
                "shell" to shells.shell(page = "Iscrizione", current = Section.ISCRIZIONI),
                "page" to RegistrationSoonPage(gathering.title, "/info/${gathering.publicCode}"),
            ),
        )
    }

    /** The gathering for this code, if it exists and is not a draft. */
    private fun visible(code: String): Gathering? = gatherings.findByCode(code)?.takeIf { it.state != GatheringState.DRAFT }

    /**
     * The same page for a draft and for a code that does not exist, for whoever asks (also a teacher). With an account it is in the shell
     * of the app, without one in the public shell; the text is the one for a link that arrived cut short.
     */
    private fun notFound(): ModelAndView {
        val signedIn = accounts.current() != null
        val model = if (signedIn) {
            mapOf("shell" to shells.shell(page = "Pagina non trovata", current = null), "publicShell" to null)
        } else {
            mapOf("shell" to null, "publicShell" to shells.publicShell("Pagina non trovata", null))
        }
        return ModelAndView("pages/infoNotFound", model).also { it.status = HttpStatus.NOT_FOUND }
    }

    private fun robots(response: HttpServletResponse) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow")
    }
}
