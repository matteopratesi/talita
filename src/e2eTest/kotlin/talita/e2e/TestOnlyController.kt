package talita.e2e

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Endpoints that exist only in the e2e tests (this class is not in the application): they provoke
 * the failures and the fragments that no real page has yet.
 */
@Controller
@RequestMapping("/test")
class TestOnlyController {

    /** An unexpected server error. The message must never reach the person using the app. */
    @GetMapping("/errore")
    fun failure(): String = throw IllegalStateException("segreto-tecnico: dettaglio che nessuno deve vedere")

    /** A fragment to insert into a page with `hx-get`. */
    @GetMapping("/frammento")
    @ResponseBody
    fun fragment(): String = """<p id="frammento-caricato">Frammento caricato</p>"""

    /**
     * Shortens the session of the person to one second, so that a test can see what happens after two
     * hours without waiting for them: Tomcat treats a session that has been idle for longer than its
     * interval as gone, exactly as it does after the real two hours.
     */
    @GetMapping("/sessione-breve")
    @ResponseBody
    fun shortSession(request: HttpServletRequest): String {
        request.getSession(true).maxInactiveInterval = 1
        return "ok"
    }
}
