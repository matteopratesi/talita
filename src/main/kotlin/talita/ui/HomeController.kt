package talita.ui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import jakarta.servlet.http.HttpServletRequest
import talita.account.CurrentAccount
import talita.gathering.Badge
import talita.registration.MinePresenter
import talita.teacher.Invitations
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row of the list of the home: a section, with the line that says what it holds for this person. */
data class HomeEntry(val section: Section, val summary: String)

/** The notice that reminds a person of an invitation to be a teacher: the date until which it can be accepted. */
data class InvitationNotice(val validUntil: String)

/** A gathering with registrations of the parent, as a row of «Le tue iscrizioni»: the title, `2 bambini · 95 €`, and the labels of its state. */
data class HomeRegistration(val title: String, val detail: String, val badges: List<Badge>)

/**
 * Model of the home page. [parent] is a person who is not a teacher: a different welcome, only the
 * registrations in the list. [alert] is the notice left by the page before (an accepted invitation, an
 * invitation that is no longer valid), [invitation] the reminder of an invitation not yet accepted.
 * [registrations] are the gatherings in which the parent has registered a child (none: the page says to open the link of the teachers).
 */
data class HomePage(val entries: List<HomeEntry>, val parent: Boolean, val invitation: InvitationNotice?, val alert: Alert?, val registrations: List<HomeRegistration> = emptyList())

/** The date as people read it: `29 ottobre 2026`, in lowercase, in the time zone of the gatherings. */
internal val LONG_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ITALIAN).withZone(talita.ClockConfiguration.ZONE)

@Controller
class HomeController(private val shells: ShellFactory, private val invitations: Invitations, private val mine: MinePresenter) {

    @GetMapping("/")
    fun home(account: CurrentAccount, request: HttpServletRequest, model: Model): String {
        val parent = !account.isTeacher
        val entries = Section.entries.filter { it.summary != null && (!parent || it.visibleToParents) }
            .map { HomeEntry(it, (if (parent) it.parentSummary else it.summary)!!) }
        val invitation = invitations.pendingFor(account.email)?.let { InvitationNotice(LONG_DATE.format(it.expiresAt)) }

        model.addAttribute("shell", shells.shell(page = null, current = Section.HOME))
        val registrations = if (parent) mine.sections(account.id).map { HomeRegistration(it.title, mine.summary(it), it.badges) } else emptyList()

        model.addAttribute("page", HomePage(entries, parent, invitation, Flash.take(request), registrations))
        return "pages/home"
    }
}
