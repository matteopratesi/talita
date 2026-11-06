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
import talita.account.seeOther
import talita.gathering.GatheringChecks
import talita.gathering.GatheringReader
import talita.gathering.PublicNames
import talita.room.Rooms
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.SUCCESS
import talita.ui.AlertVariant.WARNING
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * The venues for the teachers (`/strutture` and what is under it: only for them, `TeacherPaths` answers 403 to anyone else before a request gets here; TALITA-20): the list, «Nuova struttura», the page
 * of a venue, «Modifica la struttura» and «Togli la struttura». The section of the navigation is «Convivenza»: the venues are reached from there.
 *
 * Every form is its own page with its own address, so the back button of the phone works, also without JavaScript. A form that goes well answers 303 to the page that shows the notice once; one that has
 * errors answers 422 with the same page, the fields as they were written and the error under each wrong one (a notice above them when there are two or more). **Nothing here writes a name or a place in the
 * log, and no name is in an address**: an address has only the number of the venue.
 *
 * A venue that a gathering uses is not taken away: the page says why, and a request made by hand gets 409 with the page as it is.
 */
@Controller
class VenueController(
    private val shells: ShellFactory,
    private val venues: Venues,
    private val presenter: VenuePresenter,
    private val names: PublicNames,
    private val gatherings: GatheringReader,
    private val rooms: Rooms,
) {

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun found(id: Long): Venue = venues.byId(id) ?: notFound()

    private fun errorsAlert(errors: Int): Alert? = if (errors > 1) Alert(DANGER, "Controlla i $errors campi segnati in rosso.") else null

    private fun page(template: String, title: String, model: Any, status: HttpStatus = HttpStatus.OK): ModelAndView =
        ModelAndView(template, mapOf("shell" to shells.shell(page = title, current = Section.CONVIVENZA), "page" to model)).also { it.status = status }

    // ---- The list ------------------------------------------------------------------------------------------------------------

    @GetMapping("/strutture")
    fun list(request: HttpServletRequest): ModelAndView = page("venue/list", "Strutture", presenter.list(venues.list(), Flash.take(request)))

    // ---- A new venue ---------------------------------------------------------------------------------------------------------

    @GetMapping("/strutture/nuova")
    fun newForm(): ModelAndView = page("venue/form", VenueTexts.NEW_TITLE, presenter.newForm())

    @PostMapping("/strutture/nuova")
    fun create(
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "place", defaultValue = "") place: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val (nameCheck, placeCheck) = GatheringChecks.withNames(names, VenueChecks.name(name), VenueChecks.place(place))
        val errors = listOfNotNull(nameCheck.error, placeCheck.error).size
        if (errors > 0) {
            val form = presenter.newForm(nameCheck.value.take(MAX_ECHO), placeCheck.value.take(MAX_ECHO), nameCheck.error, placeCheck.error, errorsAlert(errors))
            return page("venue/form", VenueTexts.NEW_TITLE, form, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (val result = venues.create(nameCheck.value, placeCheck.value)) {
            is VenueSave.Saved -> {
                // How to use it, with the way to the gathering: the venue is chosen there.
                val toGathering = gatherings.current()?.let { "/convivenza/${it.id}" } ?: "/convivenza"
                Flash.put(request, Alert(SUCCESS, VenueTexts.howToUse(result.venue.name), lead = VenueTexts.SAVED, actionLabel = "Vai alla convivenza", actionHref = toGathering))
                seeOther("/strutture/${result.venue.id}")
            }
            VenueSave.NameTaken -> page("venue/form", VenueTexts.NEW_TITLE, presenter.newForm(nameCheck.value, placeCheck.value, VenueChecks.NAME_TAKEN, null, null), HttpStatus.UNPROCESSABLE_ENTITY)
            VenueSave.NotFound -> notFound()
        }
    }

    // ---- A venue -------------------------------------------------------------------------------------------------------------

    @GetMapping("/strutture/{id:[0-9]{1,18}}")
    fun detail(@PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        val venue = found(id)
        return page("venue/detail", venue.name, presenter.page(venue, venues.usage(id), Flash.take(request), RoomFlash.take(request)))
    }

    @GetMapping("/strutture/{id:[0-9]{1,18}}/modifica")
    fun changeForm(@PathVariable id: Long): ModelAndView {
        val venue = found(id)
        return page("venue/form", VenueTexts.CHANGE_TITLE, presenter.changeForm(venue, venues.usage(id)))
    }

    @PostMapping("/strutture/{id:[0-9]{1,18}}/modifica")
    fun change(
        @PathVariable id: Long,
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "place", defaultValue = "") place: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val venue = found(id)
        val (nameCheck, placeCheck) = GatheringChecks.withNames(names, VenueChecks.name(name), VenueChecks.place(place))
        val errors = listOfNotNull(nameCheck.error, placeCheck.error).size
        if (errors > 0) {
            val form = presenter.changeForm(venue, venues.usage(id), nameCheck.value.take(MAX_ECHO), placeCheck.value.take(MAX_ECHO), nameCheck.error, placeCheck.error, errorsAlert(errors))
            return page("venue/form", VenueTexts.CHANGE_TITLE, form, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (val result = venues.update(id, nameCheck.value, placeCheck.value)) {
            is VenueSave.Saved -> {
                // A venue that a gathering uses: what the parents read has changed, and the notice says so (and that the registrations already made keep the text of that time).
                Flash.put(request, if (result.gatherings > 0) Alert(SUCCESS, VenueTexts.SAVED_USED, lead = VenueTexts.SAVED) else Alert(SUCCESS, VenueTexts.SAVED))
                seeOther("/strutture/$id")
            }
            VenueSave.NameTaken -> {
                val form = presenter.changeForm(venue, venues.usage(id), nameCheck.value, placeCheck.value, VenueChecks.NAME_TAKEN, null, null)
                page("venue/form", VenueTexts.CHANGE_TITLE, form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
            VenueSave.NotFound -> notFound()
        }
    }

    // ---- Taking a venue away -------------------------------------------------------------------------------------------------

    /** Without JavaScript: the page of confirmation, with the words of the dialog. A venue that a gathering uses has none: its page says why it is not taken away. */
    @GetMapping("/strutture/{id:[0-9]{1,18}}/togli")
    fun removalPage(@PathVariable id: Long): ModelAndView {
        val venue = found(id)
        if (venues.usage(id) > 0) return seeOther("/strutture/$id")
        val model = presenter.removalDialog(venue, rooms.rooms(id).size)
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.CONVIVENZA), "page" to model))
    }

    @PostMapping("/strutture/{id:[0-9]{1,18}}/togli")
    fun remove(@PathVariable id: Long, request: HttpServletRequest): ModelAndView {
        found(id)
        return when (val result = venues.remove(id)) {
            is VenueRemoval.Removed -> {
                Flash.put(request, Alert(SUCCESS, VenueTexts.REMOVED))
                seeOther("/strutture")
            }
            is VenueRemoval.InUse -> {
                // A request made by hand for a venue that a gathering uses: nothing is taken away, and the page is the one of now.
                val venue = found(id)
                page("venue/detail", venue.name, presenter.page(venue, result.gatherings, Alert(WARNING, VenueTexts.notRemoved(result.gatherings))), HttpStatus.CONFLICT)
            }
            VenueRemoval.NotFound -> notFound()
        }
    }

    private companion object {
        const val MAX_ECHO = 1000
    }
}
