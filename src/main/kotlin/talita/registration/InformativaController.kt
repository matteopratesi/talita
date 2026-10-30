package talita.registration

import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.ModelAndView
import talita.account.PasskeySupport
import talita.account.SessionTexts
import talita.retention.RetentionTexts
import talita.retention.TimeRow
import talita.ui.ShellFactory

/**
 * Who is the controller of the data and how to write to them (`talita.privacy.*`, in production the environment variables
 * `TALITA_PRIVACY_CONTROLLER_NAME` and `TALITA_PRIVACY_CONTACT`): the short notice at step 2 of the registration and the complete one at
 * `/informativa` say it. **There is no default**: without the two values the application does not start, so that a production instance can never tell
 * the parents that the controller of their data is a placeholder. The `dev` profile and the tests set the placeholders «Titolare di esempio» and
 * `nome@example.org` (`application-dev.yaml`, `MailpitContextCustomizer`); the address that is real comes with TALITA-9 (docs/03).
 *
 * @property controllerName the name that the notices give for the controller
 * @property contact the address to write to, for questions and for the rights of the parents
 */
@ConfigurationProperties("talita.privacy")
data class PrivacySettings(
    val controllerName: String = "",
    val contact: String = "",
) {
    init {
        require(controllerName.isNotBlank() && contact.isNotBlank()) { MISSING }
    }

    companion object {
        const val MISSING = "talita.privacy.controller-name and talita.privacy.contact (the environment variables TALITA_PRIVACY_CONTROLLER_NAME and " +
            "TALITA_PRIVACY_CONTACT) must be set: the notices tell the parents who the controller of their data is, and the application does not " +
            "start without it (docs/04-ambiente.md)."
    }
}

/**
 * What the complete notice says that is not settled yet. They are constants that a test finds: the test of the release (`-Prelease`) fails if one of them
 * is still in the texts, so that a release cannot go out with a notice that nobody competent has read, or with the term of the accounts still open.
 */
object Informativa {

    /** The frame at the top of the complete notice, until a competent person has read it and the real text is in (docs/03). */
    const val PLACEHOLDER_NOTICE = "[TESTO DI PROVA · segnaposto per lo sviluppo: da sostituire con il testo vero, letto da una persona competente, prima del rilascio]"

    /** Every marker that must not reach a release. The terms are not among them any more: TALITA-7 fixed them, and the texts take them from `RetentionPolicy`. */
    val markers: List<String> = listOf(PLACEHOLDER_NOTICE, "[Segnaposto", "[TESTO DI PROVA")
}

/**
 * The complete notice: who the controller is, the frame that says that the text is not final, and the times and the rights, which come from `RetentionTexts` ([time] is the
 * introduction of the card «Per quanto tempo», [times] its rows).
 */
data class InformativaPage(
    val controller: String,
    val contact: String,
    val notice: String,
    val time: String,
    val times: List<TimeRow>,
    val rights: List<String>,
    val cookies: CookieCard,
    /** The site has passkeys (`talita.public-url` is set): without them the notice does not speak of them, as the pages do not. */
    val passkeys: Boolean,
)

/** A sentence of the card «Cookie e accesso», with what it is about in bold when there is one. */
data class Lead(val lead: String, val text: String)

/**
 * The card «Cookie e accesso» (TALITA-15, criterion 34): the cookie of the session and the one of «Ricordami su questo telefono» (only if the box is ticked, how long, what it holds), no other
 * cookie and none of others, and the passkeys. The numbers come from the container, [LoginSettings] and [RetentionTexts]: nothing is written by hand.
 */
data class CookieCard(val intro: String, val bullets: List<Lead>, val paragraphs: List<Lead>)

/**
 * The complete notice on the privacy, `/informativa` (criteria 33, 34). It is **public** (the list of the public addresses has it), in the public shell:
 * no script, no cookie, no CSRF code, `noindex` in the page and in the header, never kept. It has nothing personal but the controller, whose name and
 * address come from the settings (`talita.privacy.*`; in the repository the placeholders «Titolare di esempio» and `nome@example.org`).
 */
@Controller
class InformativaController(
    private val shells: ShellFactory,
    private val privacy: PrivacySettings,
    private val texts: RetentionTexts,
    private val sessions: SessionTexts,
    private val passkeySupport: PasskeySupport,
) {

    private fun cookies(passkeys: Boolean) = CookieCard(
        "Talità usa solo i cookie che servono a farti entrare. Non ci sono cookie di altri, né statistiche, né pubblicità.",
        listOf(
            Lead("Il cookie che ti tiene dentro", "mentre usi Talità: l’accesso si chiude da solo dopo ${sessions.idleWords} che non la usi, e comunque dopo ${sessions.capWords} dall’entrata."),
            Lead(
                "Il cookie di «Ricordami su questo telefono»:",
                "lo mettiamo solo se spunti la casella quando entri. Dura ${texts.devicesParent}, ${texts.devicesTeacherNumber} per i maestri, e contiene solo due codici casuali: non dice chi sei. Lo togli con «Esci», o dal tuo profilo.",
            ),
        ),
        if (passkeys) {
            listOf(
                Lead(
                    "Le passkey",
                    "restano nel tuo telefono: a Talità arriva solo il pezzo che serve a controllare che la passkey sia tua, mai quello che serve a usarla. Se il telefono le sincronizza con il tuo account Apple o Google, " +
                        "lo fa il servizio che hai scelto, in modo cifrato: Talità non manda niente né ad Apple né a Google.",
                ),
                Lead(
                    "Per i maestri con una passkey:",
                    "prima di aprire le segnalazioni dei bambini c’è una conferma con la passkey. Ci ricordiamo solo l’ora della conferma, e solo finché resta aperto l’accesso.",
                ),
                Lead("", "Né «Ricordami su questo telefono» né le passkey sono obbligatori: il link funziona sempre."),
            )
        } else {
            listOf(Lead("", "«Ricordami su questo telefono» non è obbligatorio: il link funziona sempre."))
        },
    )

    @GetMapping("/informativa")
    fun informativa(response: HttpServletResponse): ModelAndView {
        response.setHeader("X-Robots-Tag", "noindex, nofollow")
        return ModelAndView(
            "registration/informativa",
            mapOf(
                "shell" to shells.publicShell("Informativa sulla privacy", null),
                "page" to InformativaPage(
                    privacy.controllerName, privacy.contact, Informativa.PLACEHOLDER_NOTICE, texts.completeIntro, texts.completeRows(passkeySupport.enabled), texts.rightsFor(passkeySupport.enabled), cookies(passkeySupport.enabled), passkeySupport.enabled,
                ),
            ),
        )
    }
}
