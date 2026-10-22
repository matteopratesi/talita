package talita.ui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import jakarta.servlet.http.HttpServletRequest
import talita.account.CurrentAccount
import talita.teacher.Invitations
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row of the list of the home: a section, with the line that says what it holds for this person. */
data class HomeEntry(val section: Section, val summary: String)

/** The notice that reminds a person of an invitation to be a teacher: the date until which it can be accepted. */
data class InvitationNotice(val validUntil: String)

/**
 * Model of the home page. [parent] is a person who is not a teacher: a different welcome, only the
 * registrations in the list. [alert] is the notice left by the page before (an accepted invitation, an
 * invitation that is no longer valid), [invitation] the reminder of an invitation not yet accepted.
 */
data class HomePage(val entries: List<HomeEntry>, val parent: Boolean, val invitation: InvitationNotice?, val alert: Alert?)

/** The date as people read it: `29 ottobre 2026`, in lowercase, in the time zone of the gatherings. */
internal val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ITALIAN).withZone(talita.ClockConfiguration.ZONE)

@Controller
class HomeController(private val shells: ShellFactory, private val invitations: Invitations) {

    @GetMapping("/")
    fun home(account: CurrentAccount, request: HttpServletRequest, model: Model): String {
        val parent = !account.isTeacher
        val entries = Section.entries.filter { it.summary != null && (!parent || it.visibleToParents) }
            .map { HomeEntry(it, (if (parent) it.parentSummary else it.summary)!!) }
        val invitation = invitations.pendingFor(account.email)?.let { InvitationNotice(LONG_DATE.format(it.expiresAt)) }

        model.addAttribute("shell", shells.shell(page = null, current = Section.HOME))
        model.addAttribute("page", HomePage(entries, parent, invitation, Flash.take(request)))
        return "pages/home"
    }
}
