package talita.account

import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import talita.ui.ShellFactory
import jakarta.servlet.http.HttpServletRequest

/** Model of the profile page: what the person sees about their own access. */
/** [role] is «Maestro» for a teacher; a parent has no row (every account is a parent, it does not need saying). */
data class ProfilePage(val email: String, val role: String?)

/**
 * The profile, two taps from every page (the pill in the header, then "Esci"): the address with which
 * the person is inside and the way out. Every account has one.
 */
@Controller
class ProfileController(private val shells: ShellFactory) {

    @GetMapping("/profilo")
    fun profile(account: CurrentAccount, model: Model): String {
        model.addAttribute("shell", shells.shell(page = "Profilo", current = null, onProfile = true))
        model.addAttribute("page", ProfilePage(email = account.email, role = if (account.isTeacher) "Maestro" else null))
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
