package koine.e2e

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
}
