package talita.venue

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
import talita.room.RemoveResult
import talita.room.Room
import talita.room.RoomChecks
import talita.room.RoomPeople
import talita.room.Rooms
import talita.room.SaveResult
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.SUCCESS
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * The rooms of a venue (`/strutture/{id}/camere/…`, only for the teachers: `TeacherPaths` covers everything under `/strutture`; TALITA-20, criteria 13 and 16): «Nuova camera», «Modifica la camera» and «Togli», in the page of the
 * venue. The rooms are the house's: they are written, changed and taken away **at any moment**, whatever the state of the gatherings that use the venue, with the rules of TALITA-11 ([RoomChecks]: the words that say the
 * sex of the people are refused, the beds are from 1 to 50, the name is unique in the venue).
 *
 * - **«Togli» answers with the block `#camere-struttura` as a whole** (htmx replaces it): the rooms of the moment, with the notice at the top (`role="status"`) and the sentence for a screen reader in `#esito`. A room that was
 *   taken away already is `200` with «La camera era già stata tolta.», never an error. Without JavaScript the form is an ordinary POST that answers 303 to the page, with the notice left for it.
 * - **A room with people in it has a dialog** that says how many (in the gatherings that are not over) and in how many gatherings; a room where nobody sleeps is taken away at once.
 * - **No name and no number of a person is in an address**: only the number of the venue and the number of the room, which is the house.
 * - **What is not there, or is of another venue, is `404`** and nothing changes.
 */
@Controller
class VenueRoomsController(
    private val shells: ShellFactory,
    private val venues: Venues,
    private val rooms: Rooms,
    private val presenter: VenueRoomsPresenter,
) {

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun venue(id: Long): Venue = venues.byId(id) ?: notFound()

    private fun room(venueId: Long, roomId: Long): Room = rooms.find(venueId, roomId) ?: notFound()

    private fun errorsAlert(errors: Int): Alert? = if (errors > 1) Alert(DANGER, "Controlla i $errors campi segnati in rosso.") else null

    private fun formPage(form: VenueRoomForm, status: HttpStatus = HttpStatus.OK): ModelAndView =
        ModelAndView("venue/roomForm", mapOf("shell" to shells.shell(page = form.title, current = Section.CONVIVENZA), "page" to form)).also { it.status = status }

    /** The people in a room, in the gatherings that are not over (what the form says «Ora ci dormono…» and the dialog says). */
    private fun peopleIn(venueId: Long, roomId: Long): RoomPeople = rooms.people(venueId)[roomId] ?: RoomPeople(0, 0)

    private fun saved(request: HttpServletRequest, venueId: Long): ModelAndView {
        RoomFlash.put(request, Alert(SUCCESS, VenueRoomTexts.SAVED))
        return seeOther("/strutture/$venueId#camere")
    }

    // ---- A new room ----------------------------------------------------------------------------------------------------------

    @GetMapping("/strutture/{id:[0-9]{1,18}}/camere/nuova")
    fun newForm(@PathVariable id: Long): ModelAndView = formPage(presenter.newForm(id, venue(id).name))

    @PostMapping("/strutture/{id:[0-9]{1,18}}/camere/nuova")
    fun create(
        @PathVariable id: Long,
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "beds", defaultValue = "") beds: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val venue = venue(id)
        val nameCheck = RoomChecks.name(name)
        val bedsCheck = RoomChecks.beds(beds)
        val errors = listOfNotNull(nameCheck.error, bedsCheck.error).size
        if (errors > 0) {
            val form = presenter.newForm(id, venue.name, nameCheck.value.take(MAX_ECHO), bedsCheck.typed.take(MAX_ECHO), nameCheck.error, bedsCheck.error, errorsAlert(errors))
            return formPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (rooms.create(id, nameCheck.value, bedsCheck.beds!!)) {
            is SaveResult.Saved -> saved(request, id)
            SaveResult.NameTaken -> formPage(presenter.newForm(id, venue.name, nameCheck.value, bedsCheck.typed.take(MAX_ECHO), RoomChecks.NAME_TAKEN), HttpStatus.UNPROCESSABLE_ENTITY)
            SaveResult.NotFound -> notFound()
        }
    }

    // ---- A room to change ----------------------------------------------------------------------------------------------------

    @GetMapping("/strutture/{id:[0-9]{1,18}}/camere/{room:[0-9]{1,18}}/modifica")
    fun changeForm(@PathVariable id: Long, @PathVariable room: Long): ModelAndView {
        val venue = venue(id)
        val found = room(id, room)
        return formPage(presenter.changeForm(id, venue.name, found, peopleIn(id, room).people))
    }

    @PostMapping("/strutture/{id:[0-9]{1,18}}/camere/{room:[0-9]{1,18}}/modifica")
    fun change(
        @PathVariable id: Long,
        @PathVariable room: Long,
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "beds", defaultValue = "") beds: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val venue = venue(id)
        val found = room(id, room)
        val nameCheck = RoomChecks.name(name)
        val bedsCheck = RoomChecks.beds(beds)
        val errors = listOfNotNull(nameCheck.error, bedsCheck.error).size
        val people = peopleIn(id, room).people
        if (errors > 0) {
            val form = presenter.changeForm(id, venue.name, found, people, nameCheck.value.take(MAX_ECHO), bedsCheck.typed.take(MAX_ECHO), nameCheck.error, bedsCheck.error, errorsAlert(errors))
            return formPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (rooms.update(id, room, nameCheck.value, bedsCheck.beds!!)) {
            is SaveResult.Saved -> saved(request, id)
            SaveResult.NameTaken -> formPage(presenter.changeForm(id, venue.name, found, people, nameCheck.value, bedsCheck.typed.take(MAX_ECHO), RoomChecks.NAME_TAKEN), HttpStatus.UNPROCESSABLE_ENTITY)
            SaveResult.NotFound -> notFound()
        }
    }

    // ---- Taking a room away --------------------------------------------------------------------------------------------------

    /** Without JavaScript: the page of confirmation, with the words of the dialog (a room where nobody sleeps is taken away at once, with no page: the form is the button). */
    @GetMapping("/strutture/{id:[0-9]{1,18}}/camere/{room:[0-9]{1,18}}/togli")
    fun removalPage(@PathVariable id: Long, @PathVariable room: Long): ModelAndView {
        venue(id)
        val found = room(id, room)
        val model = presenter.removalDialog(id, found, peopleIn(id, room)).copy(target = null)
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.CONVIVENZA), "page" to model))
    }

    @PostMapping("/strutture/{id:[0-9]{1,18}}/camere/{room:[0-9]{1,18}}/togli")
    fun remove(@PathVariable id: Long, @PathVariable room: Long, request: HttpServletRequest): ModelAndView {
        venue(id)
        val alert = when (rooms.remove(id, room)) {
            is RemoveResult.Removed -> Alert(SUCCESS, VenueRoomTexts.REMOVED)
            // Taken away by another teacher already: the state as it is, with its own words.
            RemoveResult.AlreadyGone -> Alert(INFO, VenueRoomTexts.ALREADY_REMOVED)
            RemoveResult.NotFound -> notFound()
        }
        if (request.isHtmxFragment()) {
            return ModelAndView("venue/roomsAnswer", mapOf("block" to presenter.block(id, alert)))
        }
        RoomFlash.put(request, alert)
        return seeOther("/strutture/$id#camere")
    }

    private companion object {
        const val MAX_ECHO = 1000
    }
}
