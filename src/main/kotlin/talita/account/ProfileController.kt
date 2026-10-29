package talita.account

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.stereotype.Controller
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.servlet.ModelAndView
import talita.reminder.EmailCard
import talita.reminder.NoticePresenter
import talita.retention.YourData
import talita.retention.YourDataPresenter
import talita.ui.Alert
import talita.ui.Flash
import talita.ui.ShellFactory

/** The two fields «Nome» and «Cognome» of the profile of a teacher, as written, with their errors. */
data class NameForm(val first: String, val last: String, val firstError: String?, val lastError: String?)

/**
 * Model of the profile page: what the person sees about their own access. [role] is «Maestro» for a teacher; a
 * parent has no row (every account is a parent, it does not need saying). [nameForm] is the card «Il tuo nome»,
 * only for a teacher. [alert] is the notice at the top: a confirmation («Nome salvato.», «Dati di Luca cancellati.») or the count of errors.
 * [data] is the card «I tuoi dati» (TALITA-7): the children, with the way to delete their data, and the account. [emailCard] is the card «Email da Talità» (TALITA-13): the avviso and the stop,
 * for who has children and for who stopped the reminders (null for anyone else).
 */
data class ProfilePage(val email: String, val role: String?, val nameForm: NameForm?, val alert: Alert?, val data: YourData, val emailCard: EmailCard? = null)

/**
 * The profile, two taps from every page (the pill in the header, then "Esci"): the address with which
 * the person is inside, the way out, for a teacher the correction of their name (`talita.teacher`) and, for everyone, «I tuoi dati»: the data of the children
 * and of the account, with the way to delete them (`talita.retention`, TALITA-7). Every account has one.
 */
@Controller
class ProfileController(private val shells: ShellFactory, private val yourData: YourDataPresenter, private val notices: NoticePresenter) {

    @GetMapping("/profilo")
    fun profile(account: CurrentAccount, request: HttpServletRequest): ModelAndView = page(account, Flash.take(request), HttpStatus.OK)

    /** The profile as it is now, with [alert] at the top: also the answer of a request that cannot be made (409), which shows what to do first. */
    fun page(
        account: CurrentAccount,
        alert: Alert?,
        status: HttpStatus,
        nameForm: NameForm? = account.teacher?.let { NameForm(it.first, it.last, null, null) },
    ): ModelAndView =
        ModelAndView(
            "pages/profile",
            mapOf(
                "shell" to shells.shell(page = "Profilo", current = null, onProfile = true),
                "page" to ProfilePage(
                    email = account.email,
                    role = if (account.isTeacher) "Maestro" else null,
                    nameForm = nameForm,
                    alert = alert,
                    data = yourData.forProfile(account),
                    emailCard = notices.card(account.id, full = true),
                ),
            ),
        ).also { it.status = status }
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
