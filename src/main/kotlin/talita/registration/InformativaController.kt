package talita.registration

import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.ModelAndView
import talita.ui.ShellFactory

/**
 * Who is the controller of the data and how to write to them (`talita.privacy.*`, in production the environment variables
 * `TALITA_PRIVACY_CONTROLLER_NAME` and `TALITA_PRIVACY_CONTACT`): the short notice at step 2 of the registration and the complete one at
 * `/informativa` say it. The repository holds only the placeholders below; the address that is real comes with TALITA-9 (docs/03).
 *
 * @property controllerName the name that the notices give for the controller
 * @property contact the address to write to, for questions and for the rights of the parents
 */
@ConfigurationProperties("talita.privacy")
data class PrivacySettings(
    @DefaultValue("Titolare di esempio") val controllerName: String,
    @DefaultValue("nome@example.org") val contact: String,
)

/**
 * What the complete notice says that is not settled yet. They are constants that a test finds: the test of the release (`-Prelease`) fails if one of them
 * is still in the texts, so that a release cannot go out with a notice that nobody competent has read, or with the term of the accounts still open.
 */
object Informativa {

    /** The frame at the top of the complete notice, until a competent person has read it and the real text is in (docs/03). */
    const val PLACEHOLDER_NOTICE = "[TESTO DI PROVA · segnaposto per lo sviluppo: da sostituire con il testo vero, letto da una persona competente, prima del rilascio]"

    /** The one term that is still to be decided (TALITA-7): how long the address of an account is kept. */
    const val OPEN_ITEM = "[Da fissare con TALITA-7: per quanto tempo si tiene l’indirizzo email dell’account.]"

    /** Every marker that must not reach a release. */
    val markers: List<String> = listOf(PLACEHOLDER_NOTICE, OPEN_ITEM, "[Segnaposto", "[TESTO DI PROVA")
}

/** The complete notice: who the controller is, and the two sentences that are not final. */
data class InformativaPage(val controller: String, val contact: String, val notice: String, val openItem: String)

/**
 * The complete notice on the privacy, `/informativa` (criteria 33, 34). It is **public** (the list of the public addresses has it), in the public shell:
 * no script, no cookie, no CSRF code, `noindex` in the page and in the header, never kept. It has nothing personal but the controller, whose name and
 * address come from the settings (`talita.privacy.*`; in the repository the placeholders «Titolare di esempio» and `nome@example.org`).
 */
@Controller
class InformativaController(private val shells: ShellFactory, private val privacy: PrivacySettings) {

    @GetMapping("/informativa")
    fun informativa(response: HttpServletResponse): ModelAndView {
        response.setHeader("X-Robots-Tag", "noindex, nofollow")
        return ModelAndView(
            "registration/informativa",
            mapOf(
                "shell" to shells.publicShell("Informativa sulla privacy", null),
                "page" to InformativaPage(privacy.controllerName, privacy.contact, Informativa.PLACEHOLDER_NOTICE, Informativa.OPEN_ITEM),
            ),
        )
    }
}
