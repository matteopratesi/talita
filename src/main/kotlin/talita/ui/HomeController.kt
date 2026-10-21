package talita.ui

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class HomeController(private val shells: ShellFactory) {

    @GetMapping("/")
    fun home(model: Model): String {
        model.addAttribute("shell", shells.shell(page = null, current = Section.HOME))
        model.addAttribute("page", HomePage(sections = Section.entries.filter { it.summary != null }))
        return "pages/home"
    }
}
