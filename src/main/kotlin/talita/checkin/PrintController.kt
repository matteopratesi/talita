package talita.checkin

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.gathering.GatheringInfo
import talita.ui.Section
import talita.ui.ShellFactory
import java.time.Clock
import java.time.OffsetDateTime

/**
 * The three prints of the gathering of Arrivi (`/arrivi/registro`, `/arrivi/elenco`, `/arrivi/foglio`, only for the teachers: `TeacherPaths` answers 403 to anyone
 * else before a request gets here): the register to fill in by hand, the list of names for the venue and the sheet with the parents' contacts for the teachers.
 * They are whole pages with a button that prints them, not pieces that htmx updates: what is on the screen is what is on the paper, and the paper is the reserve when
 * there is no network where the gathering is.
 *
 * - **No notes on health in any of them** (the answer to question 6 of the analysis is no): nothing here reads the table of the notes, so nothing here can write a
 *   reading, and no print says whether a child has any.
 * - **Nothing personal in an address or in the log**: the list for the venue takes a day of the gathering and nothing else; a day that is not one is a page that
 *   does not exist. Without a gathering that is not a draft there is nothing to print: 404.
 * - The time of the print is the one of the server at the request, and the page is never kept (`no-store`, as every page behind the access).
 */
@Controller
class PrintController(
    private val shells: ShellFactory,
    private val scope: ArrivalsScope,
    private val arrivals: Arrivals,
    private val presenter: PrintPresenter,
    private val clock: Clock,
) {

    private fun gathering(): GatheringInfo = scope.gathering() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun page(template: String, title: String, model: Any): ModelAndView =
        ModelAndView("checkin/$template", mapOf("shell" to shells.shell(page = title, current = Section.ARRIVI, wide = true), "page" to model))

    @GetMapping("/arrivi/registro")
    fun register(): ModelAndView = page("register", "Registro arrivi", presenter.register(arrivals.load(gathering()), OffsetDateTime.now(clock)))

    @GetMapping("/arrivi/elenco")
    fun venueList(@RequestParam(name = "giorno", required = false) day: String?): ModelAndView {
        val gathering = gathering()
        val chosen = if (day.isNullOrBlank()) null else presenter.dayOf(gathering, day) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return page("venueList", "Elenco per la struttura", presenter.venue(arrivals.load(gathering), chosen, OffsetDateTime.now(clock)))
    }

    @GetMapping("/arrivi/foglio")
    fun teacherSheet(): ModelAndView = page("teacherSheet", "Foglio per i maestri", presenter.sheet(arrivals.load(gathering()), OffsetDateTime.now(clock)))
}
