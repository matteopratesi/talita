package talita.account

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import talita.ui.Alert
import talita.ui.Flash
import talita.ui.ShellFactory

/** The two fields «Nome» and «Cognome» of the profile of a teacher, as written, with their errors. */
data class NameForm(val first: String, val last: String, val firstError: String?, val lastError: String?)

/**
 * Model of the profile page: what the person sees about their own access. [role] is «Maestro» for a teacher; a
 * parent has no row (every account is a parent, it does not need saying). [nameForm] is the card «Il tuo nome»,
 * only for a teacher. [alert] is the notice at the top: a confirmation («Nome salvato.») or the count of errors.
 */
data class ProfilePage(val email: String, val role: String?, val nameForm: NameForm?, val alert: Alert?)

/**
 * The profile, two taps from every page (the pill in the header, then "Esci"): the address with which
 * the person is inside, the way out and, for a teacher, the correction of their name (`talita.teacher`).
 * Every account has one.
 */
@Controller
class ProfileController(private val shells: ShellFactory) {

    @GetMapping("/profilo")
    fun profile(account: CurrentAccount, request: HttpServletRequest, model: Model): String {
        model.addAttribute("shell", shells.shell(page = "Profilo", current = null, onProfile = true))
        model.addAttribute(
            "page",
            ProfilePage(
                email = account.email,
                role = if (account.isTeacher) "Maestro" else null,
                nameForm = account.teacher?.let { NameForm(it.first, it.last, null, null) },
                alert = Flash.take(request),
            ),
        )
        return "pages/profile"
    }
}

/**
 * Gives every template the CSRF code, for its forms. It is the lazy one of Spring Security: nothing is
 * read, and no session starts, until a form writes its value (`components/csrf.jte`). A page without
 * forms (the public information page of TALITA-4) leaves no cookie.
 */
@ControllerAdvice
class CsrfModel {

    @ModelAttribute("csrf")
    fun csrf(request: HttpServletRequest): CsrfToken? = request.getAttribute(CsrfToken::class.java.name) as? CsrfToken
}
