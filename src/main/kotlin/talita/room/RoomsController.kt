package talita.room

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.account.isHtmxFragment
import talita.account.seeOther
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.SUCCESS
import talita.ui.AlertVariant.WARNING
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * «Camere» (`/convivenza/{id}/camere` and what is under it): the rooms of the house and who sleeps where, written and read only by the teachers. The addresses are under
 * `/convivenza` (`TeacherPaths` answers 403 to anyone else, a parent, on every address and every method, before a request gets here).
 *
 * - **Every action answers with the block `#camere-contenuto` as a whole** (htmx replaces it): the summary, the rooms and who has none, always from the database of the
 *   moment, so with two teachers at work together each answer is true. The message is one, in the place that was touched ([Spot]). Without JavaScript the form is an
 *   ordinary POST that answers 303 to the page, with the message left for it.
 * - **No name and no number of a person is in an address**: a person and a room travel in the body of the POST (`b12`, `m7`); only the number of the gathering is in the
 *   address, as in the pages of the gathering, and the number of a room in the pages of its form and of its removal (a room is the house, not a person).
 * - **What is not there, or is of another gathering, is `404`** and nothing changes; what the end of the gathering refuses is `409` with the page as it is, and nothing changes;
 *   a choice that was already made, or a person who was out already, is `200` with the state as it is, never an error (criteria 7, 10).
 */
@Controller
class RoomsController(
    private val shells: ShellFactory,
    private val rooms: Rooms,
    private val presenter: RoomsPresenter,
) {

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun board(id: Long): RoomBoard = rooms.board(id) ?: notFound()

    private fun pageHref(id: Long, anchor: String? = null): String = "/convivenza/$id/camere" + (anchor?.let { "#$it" } ?: "")

    private fun errorsAlert(errors: Int): Alert? = if (errors > 1) Alert(DANGER, "Controlla i $errors campi segnati in rosso.") else null

    private fun shell(title: String) = shells.shell(page = title, current = Section.CONVIVENZA)

    // ---- The page ---------------------------------------------------------------------------------------------------------------

    private fun page(board: RoomBoard, feedback: Feedback, flash: Alert?, status: HttpStatus): ModelAndView =
        ModelAndView("room/rooms", mapOf("shell" to shell("Camere"), "page" to presenter.page(board, feedback, flash))).also { it.status = status }

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/camere")
    fun roomsPage(@PathVariable id: Long, request: HttpServletRequest): ModelAndView = page(board(id), Feedback.NONE, Flash.take(request), HttpStatus.OK)

    /**
     * The answer to an action. With htmx, the block (`status` says whether it went well); without it, a redirect to the page at [anchor] with the message left for it
     * (a success), or the whole page with the message in its place (what did not go well: the form stays where it was).
     */
    private fun answer(request: HttpServletRequest, id: Long, feedback: Feedback, status: HttpStatus = HttpStatus.OK, anchor: String? = null): ModelAndView {
        val board = board(id)
        if (request.isHtmxFragment()) {
            return ModelAndView("room/roomsAnswer", mapOf("block" to presenter.block(board, feedback))).also { it.status = status }
        }
        if (status == HttpStatus.OK) {
            // The message of a spot that is not the top goes to the top: the page is a new one, and the message is read once.
            feedback.alert?.let { Flash.put(request, it) }
            return seeOther(pageHref(id, anchor))
        }
        return page(board, feedback, null, status)
    }

    /** The gathering is over: the page as it is, with the sentence of the end, and nothing changed (409). */
    private fun over(request: HttpServletRequest, id: Long): ModelAndView =
        answer(request, id, Feedback(Spot.Top, Alert(INFO, RoomsPresenter.FINISHED)), HttpStatus.CONFLICT)

    /** A form that has no page of its own, asked with a GET (a page without JavaScript that came back with an error and was reloaded): the page of the rooms. */
    @GetMapping(
        "/convivenza/{id:[0-9]{1,18}}/camere/scegli", "/convivenza/{id:[0-9]{1,18}}/camere/sposta", "/convivenza/{id:[0-9]{1,18}}/camere/maestri",
        "/convivenza/{id:[0-9]{1,18}}/camere/copia",
    )
    fun reloaded(@PathVariable id: Long): ModelAndView {
        board(id)
        return seeOther(pageHref(id))
    }

    // ---- Who sleeps where -----------------------------------------------------------------------------------------------------------

    private fun known(board: RoomBoard, person: PersonRef): Boolean = when (person) {
        is PersonRef.Child -> board.registrations.any { it.id == person.registrationId }
        is PersonRef.Teacher -> board.teachers.any { it.accountId == person.accountId }
    }

    private fun roomOf(typed: String): Long? = typed.trim().toLongOrNull()?.takeIf { it > 0 }

    /**
     * What a person's new place says: «Davide Costa è in Stanza azzurra.», with «Prima era in Camera 2.» if they were in another (a choice that finds the person in a room already:
     * another teacher put them there). [withSource] is false when the message is in the room that the person left: there it would say twice where they were.
     */
    private fun placed(result: PlaceResult.Placed, withSource: Boolean = true): Alert {
        val before = result.before
        return when {
            before != null && before.id == result.room.id -> Alert(INFO, "${result.name} è già in ${result.room.name}.")
            before != null && withSource -> Alert(SUCCESS, "${result.name} è in ${result.room.name}. Prima era in ${before.name}.")
            else -> Alert(SUCCESS, "${result.name} è in ${result.room.name}.")
        }
    }

    /**
     * The answer to a result of [Rooms.place] or [Rooms.clear]. [inRoom]: the touch was on a person of a room («Sposta»), and the message is in the room that they left (design,
     * part 1, 4); otherwise it was on a row of «Senza camera» («Metti»), and the message is in that row.
     */
    private fun outcome(request: HttpServletRequest, id: Long, ref: PersonRef, result: PlaceResult, inRoom: Boolean): ModelAndView = when (result) {
        is PlaceResult.Placed -> {
            val roomId = result.before?.id ?: result.room.id
            if (inRoom) {
                answer(request, id, Feedback(Spot.Room(roomId), placed(result, withSource = false)), anchor = "camera-$roomId")
            } else {
                answer(request, id, Feedback(Spot.Person(ref.key), placed(result)), anchor = "senza-camera")
            }
        }

        is PlaceResult.Cleared -> {
            val before = result.before
            if (before != null) {
                answer(request, id, Feedback(Spot.Room(before.id), Alert(SUCCESS, "${result.name} è senza camera.")), anchor = "camera-${before.id}")
            } else {
                answer(request, id, Feedback(Spot.Top, Alert(INFO, "${result.name} era già senza camera.")))
            }
        }

        is PlaceResult.Withdrawn ->
            answer(request, id, Feedback(Spot.Person(ref.key), Alert(WARNING, "L’iscrizione di ${result.name} è stata ritirata: non può avere una camera.")), HttpStatus.CONFLICT)

        PlaceResult.Over -> over(request, id)

        PlaceResult.NotFound -> notFound()
    }

    /** «Metti»: a child or a teacher who has no room is put in the room they chose. */
    @PostMapping("/convivenza/{id:[0-9]{1,18}}/camere/scegli")
    fun choose(
        @PathVariable id: Long,
        @RequestParam(name = "persona", defaultValue = "") person: String,
        @RequestParam(name = "camera", defaultValue = "") room: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val ref = PersonRef.parse(person) ?: notFound()
        if (!known(board(id), ref)) notFound()
        val roomId = roomOf(room) ?: return answer(request, id, Feedback(Spot.Person(ref.key), null, CHOOSE_ROOM), HttpStatus.UNPROCESSABLE_ENTITY)
        return outcome(request, id, ref, rooms.place(id, ref, roomId), inRoom = false)
    }

    /** «Sposta» and «Togli dalla camera»: the form that «Cambia» opens in a person of a room. */
    @PostMapping("/convivenza/{id:[0-9]{1,18}}/camere/sposta")
    fun move(
        @PathVariable id: Long,
        @RequestParam(name = "persona", defaultValue = "") person: String,
        @RequestParam(name = "azione", defaultValue = "sposta") action: String,
        @RequestParam(name = "camera", defaultValue = "") room: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val ref = PersonRef.parse(person) ?: notFound()
        if (!known(board(id), ref)) notFound()
        if (action.trim() == "togli") return outcome(request, id, ref, rooms.clear(id, ref), inRoom = true)
        val roomId = roomOf(room) ?: return answer(request, id, Feedback(Spot.Person(ref.key), null, CHOOSE_ROOM), HttpStatus.UNPROCESSABLE_ENTITY)
        return outcome(request, id, ref, rooms.place(id, ref, roomId), inRoom = true)
    }

    /** «Aggiungi un maestro» of a room: any teacher who has no room goes in it. The room is in the body, as the teacher (`m7`). */
    @PostMapping("/convivenza/{id:[0-9]{1,18}}/camere/maestri")
    fun addTeacher(
        @PathVariable id: Long,
        @RequestParam(name = "camera", defaultValue = "") room: String,
        @RequestParam(name = "persona", defaultValue = "") person: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val roomId = roomOf(room) ?: notFound()
        if (rooms.find(id, roomId) == null) notFound()
        val ref = PersonRef.parse(person)
        if (ref == null && person.isBlank()) {
            return answer(request, id, Feedback(Spot.TeacherChoice(roomId), null, CHOOSE_TEACHER), HttpStatus.UNPROCESSABLE_ENTITY)
        }
        if (ref !is PersonRef.Teacher) notFound()
        val result = rooms.place(id, ref, roomId)
        // The message is in the room where the teacher was added, whatever room they came from.
        return if (result is PlaceResult.Placed) {
            answer(request, id, Feedback(Spot.Room(result.room.id), placed(result)), anchor = "camera-${result.room.id}")
        } else {
            outcome(request, id, ref, result, inRoom = true)
        }
    }

    // ---- A room: new and to change ---------------------------------------------------------------------------------------------------

    private fun formPage(form: RoomForm, status: HttpStatus = HttpStatus.OK): ModelAndView =
        ModelAndView("room/roomForm", mapOf("shell" to shell(form.title), "page" to form)).also { it.status = status }

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/camere/nuova")
    fun newForm(@PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        val board = board(id)
        if (board.over) return over(request, id)
        return formPage(presenter.newForm(board.gathering))
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/camere/nuova")
    fun create(
        @PathVariable id: Long,
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "beds", defaultValue = "") beds: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val board = board(id)
        val nameCheck = RoomChecks.name(name)
        val bedsCheck = RoomChecks.beds(beds)
        val errors = listOfNotNull(nameCheck.error, bedsCheck.error).size
        if (board.over) return over(request, id)
        if (errors > 0) {
            val form = presenter.newForm(board.gathering, nameCheck.value.take(MAX_ECHO), bedsCheck.typed.take(MAX_ECHO), nameCheck.error, bedsCheck.error, errorsAlert(errors))
            return formPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (rooms.create(id, nameCheck.value, bedsCheck.beds!!)) {
            is SaveResult.Saved -> saved(request, id)
            SaveResult.NameTaken -> {
                val form = presenter.newForm(board.gathering, nameCheck.value, bedsCheck.typed.take(MAX_ECHO), RoomChecks.NAME_TAKEN, null, null)
                formPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
            SaveResult.Over -> over(request, id)
            SaveResult.NotFound -> notFound()
        }
    }

    private fun saved(request: HttpServletRequest, id: Long): ModelAndView {
        Flash.put(request, Alert(SUCCESS, "Camera salvata."))
        return seeOther(pageHref(id))
    }

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/camere/{room:[0-9]{1,18}}/modifica")
    fun changeForm(@PathVariable id: Long, @PathVariable room: Long, request: HttpServletRequest): ModelAndView {
        val board = board(id)
        val found = board.rooms.firstOrNull { it.id == room } ?: notFound()
        if (board.over) return over(request, id)
        return formPage(presenter.changeForm(board.gathering, found, board.people(found)))
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/camere/{room:[0-9]{1,18}}/modifica")
    fun change(
        @PathVariable id: Long,
        @PathVariable room: Long,
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "beds", defaultValue = "") beds: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val board = board(id)
        val found = board.rooms.firstOrNull { it.id == room } ?: notFound()
        if (board.over) return over(request, id)
        val nameCheck = RoomChecks.name(name)
        val bedsCheck = RoomChecks.beds(beds)
        val errors = listOfNotNull(nameCheck.error, bedsCheck.error).size
        val people = board.people(found)
        if (errors > 0) {
            val form = presenter.changeForm(board.gathering, found, people, nameCheck.value.take(MAX_ECHO), bedsCheck.typed.take(MAX_ECHO), nameCheck.error, bedsCheck.error, errorsAlert(errors))
            return formPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (rooms.update(id, room, nameCheck.value, bedsCheck.beds!!)) {
            is SaveResult.Saved -> saved(request, id)
            SaveResult.NameTaken -> {
                val form = presenter.changeForm(board.gathering, found, people, nameCheck.value, bedsCheck.typed.take(MAX_ECHO), RoomChecks.NAME_TAKEN, null, null)
                formPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
            SaveResult.Over -> over(request, id)
            SaveResult.NotFound -> notFound()
        }
    }

    // ---- Taking a room away ---------------------------------------------------------------------------------------------------------

    /** Without JavaScript: the page of confirmation, with the words of the dialog (a room where nobody sleeps is taken away at once, with no page: the form is the button). */
    @GetMapping("/convivenza/{id:[0-9]{1,18}}/camere/{room:[0-9]{1,18}}/togli")
    fun removalPage(@PathVariable id: Long, @PathVariable room: Long, request: HttpServletRequest): ModelAndView {
        val board = board(id)
        val found = board.rooms.firstOrNull { it.id == room } ?: notFound()
        if (board.over) return over(request, id)
        val model = presenter.removalDialog(id, found, board.people(found)).copy(target = null)
        return ModelAndView("pages/confirmAction", mapOf("shell" to shell(model.title), "page" to model))
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/camere/{room:[0-9]{1,18}}/togli")
    fun remove(@PathVariable id: Long, @PathVariable room: Long, request: HttpServletRequest): ModelAndView {
        board(id)
        return when (rooms.remove(id, room)) {
            // Taken away by another teacher already: the state as it is, with the same words.
            is RemoveResult.Removed, RemoveResult.AlreadyGone -> answer(request, id, Feedback(Spot.Top, Alert(SUCCESS, "Camera tolta.")))
            RemoveResult.Over -> over(request, id)
            RemoveResult.NotFound -> notFound()
        }
    }

    // ---- Copying the rooms of the year before -------------------------------------------------------------------------------------------

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/camere/copia")
    fun copy(@PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        board(id)
        return when (rooms.copyPrevious(id)) {
            is CopyResult.Copied -> answer(request, id, Feedback(Spot.Top, Alert(SUCCESS, "Camere copiate.")))
            CopyResult.NotEmpty -> answer(request, id, Feedback(Spot.Top, Alert(INFO, "Ci sono già delle camere: non ho copiato niente.")))
            CopyResult.NothingToCopy -> answer(request, id, Feedback(Spot.Top, Alert(INFO, "Non c’è una convivenza precedente con delle camere.")))
            CopyResult.Over -> over(request, id)
            CopyResult.NotFound -> notFound()
        }
    }

    private companion object {
        const val MAX_ECHO = 1000
        const val CHOOSE_ROOM = "Scegli la camera."
        const val CHOOSE_TEACHER = "Scegli il maestro."
    }
}
