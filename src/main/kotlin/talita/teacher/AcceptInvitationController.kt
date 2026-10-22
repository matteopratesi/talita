package talita.teacher

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.RedirectView
import talita.account.CurrentAccount
import talita.account.PostLoginDestination
import talita.account.seeOther
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.SUCCESS
import talita.ui.AlertVariant.WARNING
import talita.ui.Flash
import talita.ui.ShellFactory

/** Model of «Accetta l'invito»: what is in the two fields (as written), their errors, and the notice above the card. */
data class AcceptInvitationPage(val first: String, val last: String, val firstError: String?, val lastError: String?, val alert: Alert?)

/**
 * Whoever enters with an address that has an invitation to be a teacher goes to «Accetta l'invito», and not
 * to the home or to the page they asked for: the invitation is the reason they came.
 */
@Component
class InvitationDestination(private val invitations: Invitations) : PostLoginDestination {
    override fun forNewLogin(email: String): String? = if (invitations.pendingFor(email) != null) "/invito" else null
}

/**
 * «Accetta l'invito» (`/invito`): name and surname, then the person is a teacher. The name comes from the
 * person, in this one way for everyone, the first teacher too; the invitation has only the address.
 *
 * - Whoever has no invitation they can accept (none, withdrawn, expired, or already a teacher) opens this
 *   address by hand and goes to the home, with no message.
 * - «Più tardi» leads to the home as a parent, where a notice reminds of the invitation until it expires.
 * - If the invitation expired or was withdrawn while the page was open, the person goes to the home with a
 *   notice that says so.
 */
@Controller
class AcceptInvitationController(
    private val shells: ShellFactory,
    private val invitations: Invitations,
) {

    @GetMapping("/invito")
    fun form(account: CurrentAccount): ModelAndView {
        if (account.isTeacher || invitations.pendingFor(account.email) == null) return ModelAndView(RedirectView("/"))
        return page(AcceptInvitationPage("", "", null, null, null), HttpStatus.OK)
    }

    @PostMapping("/invito")
    fun accept(
        account: CurrentAccount,
        @RequestParam(name = "nome", defaultValue = "") first: String,
        @RequestParam(name = "cognome", defaultValue = "") last: String,
        request: HttpServletRequest,
    ): ModelAndView {
        // A second tap on a page that already led here: the person is a teacher, nothing more to say.
        if (account.isTeacher) return seeOther("/")

        val check = PersonName.check(first, last)
        if (!check.valid) {
            val alert = if (check.errors > 1) Alert(DANGER, "Controlla i ${check.errors} campi segnati in rosso.") else null
            return page(
                AcceptInvitationPage(first.take(MAX_ECHO), last.take(MAX_ECHO), check.firstError, check.lastError, alert),
                HttpStatus.UNPROCESSABLE_ENTITY,
            )
        }
        if (!invitations.accept(account, check.name())) {
            Flash.put(request, Alert(WARNING, "L’invito non vale più. Se serve, chiedi a un maestro di mandartene un altro."))
            return seeOther("/")
        }
        Flash.put(request, Alert(SUCCESS, "Invito accettato. Ora sei tra i maestri."))
        return seeOther("/")
    }

    private fun page(model: AcceptInvitationPage, status: HttpStatus): ModelAndView =
        ModelAndView("pages/acceptInvitation", mapOf("shell" to shells.shell(page = "Accetta l’invito", current = null), "page" to model))
            .also { it.status = status }

    private companion object {
        const val MAX_ECHO = 1000
    }
}
