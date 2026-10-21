package talita.ui

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.webmvc.error.ErrorController
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.RequestMapping

/** Model of the page for an unexpected error. [retryHref] is the address to try again, if any. */
data class ErrorPage(val retryHref: String?)

/**
 * The error pages, inside the shell: "not found" and "something went wrong". They replace Spring
 * Boot's error page, so they are what a person sees both when opening the address directly and when
 * arriving from a link of the app. Never a code, an exception name or a stack trace.
 */
@Controller
class ErrorPageController(private val shells: ShellFactory) : ErrorController {

    @RequestMapping("/error")
    fun error(request: HttpServletRequest, response: HttpServletResponse, model: Model): String {
        // The container sets the status of the original request; an address that nobody asked for
        // (someone opening /error by hand) is simply a page that does not exist.
        val status = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int ?: HttpServletResponse.SC_NOT_FOUND
        response.status = status

        return if (status >= 500) {
            model.addAttribute("shell", shells.shell(page = "Qualcosa non ha funzionato", current = null))
            model.addAttribute("page", ErrorPage(retryHref = retryHref(request)))
            "pages/error"
        } else {
            model.addAttribute("shell", shells.shell(page = "Pagina non trovata", current = null))
            "pages/notFound"
        }
    }

    /** "Riprova" only exists for a read (GET), and only towards an address of this app. */
    private fun retryHref(request: HttpServletRequest): String? {
        if (request.method != "GET") return null
        val path = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) as? String ?: return null
        // A single leading slash: `//host` would leave the app.
        if (!path.startsWith("/") || path.startsWith("//") || path.contains('\\')) return null
        val query = request.getAttribute(RequestDispatcher.FORWARD_QUERY_STRING) as? String
        return if (query.isNullOrEmpty()) path else "$path?$query"
    }
}
