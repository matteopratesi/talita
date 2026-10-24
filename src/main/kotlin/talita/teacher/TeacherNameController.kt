package talita.teacher

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import talita.account.CurrentAccount
import talita.account.NameForm
import talita.account.ProfileController
import talita.account.isHtmx
import talita.account.seeOther
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.SUCCESS
import talita.ui.Flash

/**
 * «Il tuo nome» in the profile: a teacher corrects name and surname, with the rules of the invitation (both
 * required, at most 100 characters, accents and apostrophes). The initials in the header follow at the next
 * request. A parent has no such fields, and a request made by hand by anyone who is not a teacher is a 403
 * (the address is in `TeacherPaths`).
 */
@Controller
class TeacherNameController(private val profile: ProfileController, private val teachers: Teachers) {

    /** A reload of the page that an error left at the address of the form (without JavaScript): back to the profile. */
    @GetMapping("/profilo/nome")
    fun reloaded(): ModelAndView = seeOther("/profilo")

    @PostMapping("/profilo/nome")
    fun save(
        account: CurrentAccount,
        @RequestParam(name = "nome", defaultValue = "") first: String,
        @RequestParam(name = "cognome", defaultValue = "") last: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ModelAndView {
        val check = PersonName.check(first, last)
        if (!check.valid) {
            val alert = if (check.errors > 1) Alert(DANGER, "Controlla i ${check.errors} campi segnati in rosso.") else null
            // With htmx the address bar stays on the profile, where a reload leads; without it the page is at the address of the form.
            if (request.isHtmx()) response.setHeader("HX-Replace-Url", "/profilo")
            return profile.page(account, alert, HttpStatus.UNPROCESSABLE_ENTITY, NameForm(first.take(MAX_ECHO), last.take(MAX_ECHO), check.firstError, check.lastError))
        }
        teachers.rename(account.id, check.name())
        Flash.put(request, Alert(SUCCESS, "Nome salvato."))
        return seeOther("/profilo")
    }

    private companion object {
        const val MAX_ECHO = 1000
    }
}
