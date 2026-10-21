package talita.ui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/** Model of the "in preparation" page of a section. */
data class PlaceholderPage(val title: String, val text: String)

/**
 * The "in preparation" pages of the sections. They are temporary: each ticket replaces the one of
 * its section with the real page.
 */
@Controller
class SectionController(private val shells: ShellFactory) {

    @GetMapping("/convivenza")
    fun gathering(model: Model): String = placeholder(Section.CONVIVENZA, model)

    @GetMapping("/iscrizioni")
    fun registrations(model: Model): String = placeholder(Section.ISCRIZIONI, model)

    @GetMapping("/arrivi")
    fun arrivals(model: Model): String = placeholder(Section.ARRIVI, model)

    private fun placeholder(section: Section, model: Model): String {
        model.addAttribute("shell", shells.shell(page = section.label, current = section))
        model.addAttribute("page", PlaceholderPage(title = section.label, text = checkNotNull(section.placeholder)))
        return "pages/placeholder"
    }
}
