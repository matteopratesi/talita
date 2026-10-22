package talita.ui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import talita.account.CurrentAccount

/** Model of the "in preparation" page of a section. */
data class PlaceholderPage(val title: String, val text: String)

/**
 * The "in preparation" pages of the sections. They are temporary: each ticket replaces the one of
 * its section with the real page. The gathering and the arrivals are only for teachers (`TeacherPaths`
 * answers 403 to anyone else before the request gets here); the registrations are for every account, with
 * the sentence of a parent or the one of a teacher.
 */
@Controller
class SectionController(private val shells: ShellFactory) {

    @GetMapping("/convivenza")
    fun gathering(account: CurrentAccount, model: Model): String = placeholder(Section.CONVIVENZA, account, model)

    @GetMapping("/iscrizioni")
    fun registrations(account: CurrentAccount, model: Model): String = placeholder(Section.ISCRIZIONI, account, model)

    @GetMapping("/arrivi")
    fun arrivals(account: CurrentAccount, model: Model): String = placeholder(Section.ARRIVI, account, model)

    private fun placeholder(section: Section, account: CurrentAccount, model: Model): String {
        val text = if (account.isTeacher) section.placeholder else section.parentPlaceholder
        model.addAttribute("shell", shells.shell(page = section.label, current = section))
        model.addAttribute("page", PlaceholderPage(title = section.label, text = checkNotNull(text)))
        return "pages/placeholder"
    }
}
