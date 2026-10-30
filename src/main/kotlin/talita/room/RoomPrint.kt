package talita.room

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.gathering.GatheringFormat
import talita.registration.RegistrationData
import talita.registration.Status
import talita.retention.RetentionTexts
import talita.teacher.TeacherRow
import talita.ui.Icon
import talita.ui.Section
import talita.ui.ShellFactory
import java.time.Clock
import java.time.OffsetDateTime

/** A room on the print: the name, its beds, who sleeps in it. A room where nobody sleeps says «Vuota». */
data class PrintRoom(val id: Long, val name: String, val beds: String, val children: List<PrintPerson>, val teachers: List<PrintPerson>)

data class PrintPerson(val name: String, val maybe: Boolean)

/** The block «Senza camera» of the print: it may break between two sheets. */
data class PrintFree(val title: String, val children: List<PrintPerson>, val teachers: List<PrintPerson>)

/** The page that says there is nothing to print, with the way out. */
data class PrintEmpty(val icon: Icon, val title: String, val text: String)

data class PrintPage(
    val gatheringId: Long,
    val title: String,
    val lead: String,
    val printedLine: String,
    val notice: String,
    val rooms: List<PrintRoom>,
    val free: PrintFree?,
    val footer: String,
    val empty: PrintEmpty?,
)

/** The words of the print «Camere» and how its rooms are laid out: what is written here is in the design (TALITA-11, part 2, 3). */
@Component
class RoomPrintPresenter(private val texts: RetentionTexts) {

    private val nothing = "Quando scrivi le camere, le trovi qui da stampare."

    /** The print «Camere» (criterion 14): rooms with who sleeps in them, then who has none. No age, no presence, no contact and no notes. */
    fun print(board: RoomBoard, now: OffsetDateTime): PrintPage {
        val gathering = board.gathering
        val deleted = board.peopleDeleted
        val head = "${gathering.title} · ${GatheringFormat.range(gathering.start, gathering.end)}"
        val printed = "Stampato il ${RoomFormat.moment(now)}."
        val notice = "Questo foglio dice dove dorme ogni persona. Tienilo fra i maestri e distruggilo alla fine della convivenza."
        val footer = "Alla fine della convivenza questo foglio va distrutto. Non ha età, presenze, recapiti né segnalazioni."
        if (board.rooms.isEmpty() || deleted) {
            val empty = if (deleted) {
                PrintEmpty(Icon.ARCHIVE, "Niente da stampare", texts.roomsDeleted(gathering.end).main)
            } else {
                PrintEmpty(Icon.BED_DOUBLE, "Nessuna camera da stampare", if (board.over) RoomsPresenter.EMPTY_OVER_TEXT else nothing)
            }
            return PrintPage(gathering.id, "Camere", head, printed, notice, emptyList(), null, footer, empty)
        }
        fun person(registration: RegistrationData) = PrintPerson(registration.name, registration.status == Status.MAYBE)
        fun person(teacher: TeacherRow) = PrintPerson(teacher.name.full, false)
        val printRooms = board.rooms.map { room ->
            PrintRoom(room.id, room.name, RoomFormat.beds(room.beds), board.childrenIn(room).map(::person), board.teachersIn(room).map(::person))
        }
        val freeChildren = board.freeChildren
        val freeTeachers = board.freeCustodians
        val free = if (freeChildren.isEmpty() && freeTeachers.isEmpty()) {
            null
        } else {
            PrintFree("Senza camera · ${RoomFormat.people(freeChildren.size + freeTeachers.size)}", freeChildren.map(::person), freeTeachers.map(::person))
        }
        return PrintPage(gathering.id, "Camere", head, printed, notice, printRooms, free, footer, null)
    }
}

/**
 * The print «Camere» (`/convivenza/{id}/camere/stampa`, only for the teachers): a whole page with a button that prints it, as the prints of the arrivals are, and not a piece that htmx
 * updates. What is on the screen is what is on the paper. Nothing here is personal beyond the names of who sleeps where, and no name is in an address or in the log; the time of the print is
 * the one of the server at the request, and the page is never kept (`no-store`, as every page behind the access).
 */
@Controller
class RoomPrintController(private val shells: ShellFactory, private val rooms: Rooms, private val presenter: RoomPrintPresenter, private val clock: Clock) {

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/camere/stampa")
    fun print(@PathVariable id: Long): ModelAndView {
        val board = rooms.board(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return ModelAndView(
            "room/print",
            mapOf("shell" to shells.shell(page = "Camere · stampa", current = Section.CONVIVENZA, wide = true), "page" to presenter.print(board, OffsetDateTime.now(clock))),
        )
    }
}
