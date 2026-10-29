package talita.reminder

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import talita.ui.Alert
import talita.ui.Flash
import talita.ui.ShellFactory

/**
 * Model of the page for stopping: the code of the link, kept for the form (hidden field), and a notice if the page was open too long (`PAGE_EXPIRED`). The code is echoed only when it is
 * the shape of one (16 characters of base64url): anything else is not echoed, and the page is the same.
 */
data class StopPage(val code: String, val alert: Alert?)

/**
 * The page that the link of every reminder leads to (TALITA-13, design part 2, 3): `/promemoria/smetti?codice=…`, **public** (it is in the list of the public addresses, with its test), for a
 * person who has the mail and may not be inside.
 *
 * - **Opening it (GET) changes nothing**: the mail programs open the links to look at them. It asks for a tap, and the tap is a POST with the CSRF code, like «Entra» of the login link.
 * - **A code that does not exist, or none, has the same page and the same answer**, so nobody learns whether an address has an account; the answer is the same for an account that had stopped
 *   already. The page never shows the address or says whose the code is.
 * - **The code** is only in the query of the link of the mail and in the body of the POST (a hidden field): never in the log, in a message, or in another address. The page says `noindex`, is never
 *   kept (`Cache-Control: no-store`, from Spring Security), and has no link to another site, so no `Referer` takes the code out.
 * - **After the tap**: no reminders and no avviso for that account, the consent taken away; the login links and the invitations still come ([Notices.stopByCode]).
 */
@Controller
class StopController(private val shells: ShellFactory, private val notices: Notices) {

    @GetMapping("/promemoria/smetti")
    fun page(@RequestParam(name = "codice", defaultValue = "") codice: String, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        response.setHeader("X-Robots-Tag", "noindex, nofollow")
        val shown = codice.trim().takeIf { CODE.matches(it) }.orEmpty()
        return ModelAndView(
            "reminder/stop",
            mapOf("shell" to shells.shell(page = "Promemoria di Talità", current = null, noindex = true), "page" to StopPage(shown, Flash.take(request))),
        )
    }

    @PostMapping("/promemoria/smetti")
    fun stop(@RequestParam(name = "codice", defaultValue = "") codice: String, response: HttpServletResponse): ModelAndView {
        response.setHeader("X-Robots-Tag", "noindex, nofollow")
        notices.stopByCode(codice.trim())
        return ModelAndView("reminder/stopped", mapOf("shell" to shells.shell(page = "Promemoria fermati", current = null, noindex = true)))
    }

    private companion object {
        val CODE = Regex("^[A-Za-z0-9_-]{16}$")
    }
}
