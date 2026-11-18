package talita.gathering

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import jakarta.servlet.http.HttpServletRequest
import talita.account.CurrentAccount
import talita.account.seeOther
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.SUCCESS
import talita.ui.AlertVariant.WARNING
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * The gathering for the teachers (`/convivenza` and what is under it: only for them, `TeacherPaths` answers 403 to anyone else before a request gets here):
 * the section, «Nuova convivenza», the detail and one form for each of its parts (title, dates and venue · fees · custodians · information).
 *
 * Every form is its own page with its own address, so the back button of the phone and the links of «Prima di aprire le iscrizioni» reach the
 * right field, also without JavaScript. A form that goes well answers 303 to the detail with a notice that the detail shows once; one that
 * has errors answers 422 with the same page, the fields as they were written, and the error under each wrong one (a notice above them when
 * there are two or more).
 */
@Controller
class GatheringController(
    private val shells: ShellFactory,
    private val gatherings: Gatherings,
    private val edits: GatheringEdits,
    private val presenter: GatheringPresenter,
    private val usage: RegistrationUsage,
    private val venues: GatheringVenues,
    private val assignments: RoomAssignments,
) {

    // ---- The section ---------------------------------------------------------------------------------------------------------

    @GetMapping("/convivenza")
    fun section(): ModelAndView = page("gathering/section", "Convivenza", presenter.section())

    // ---- A new gathering -----------------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/nuova")
    fun newForm(): ModelAndView {
        gatherings.current()?.let { return newPage(blocked(it), HttpStatus.OK) }
        return newPage(NewPage("", "", "", null, null, null, null, null), HttpStatus.OK)
    }

    @PostMapping("/convivenza/nuova")
    fun create(
        @RequestParam(name = "title", defaultValue = "") title: String,
        @RequestParam(name = "startDate", defaultValue = "") startDate: String,
        @RequestParam(name = "endDate", defaultValue = "") endDate: String,
        request: HttpServletRequest,
    ): ModelAndView = when (val result = edits.create(title, startDate, endDate)) {
        // Somebody made one (or it was there all along, for a request made by hand): the page says so, nothing is created.
        is CreateEdit.AlreadyCurrent -> newPage(blocked(result.id, result.title, result.state), HttpStatus.CONFLICT)
        is CreateEdit.Invalid -> {
            val page = NewPage(
                result.title.value.take(MAX_ECHO), result.dates.startTyped.take(MAX_ECHO), result.dates.endTyped.take(MAX_ECHO),
                result.title.error, result.dates.startError, result.dates.endError, errorsAlert(result.errors), null,
            )
            newPage(page, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        is CreateEdit.Created -> savedAndBack(request, result.id, "Convivenza salvata.")
    }

    // ---- The detail ----------------------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}")
    fun detail(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView =
        detailPage(account, found(id), Flash.take(request), showMissing = false, status = HttpStatus.OK)

    // ---- Title, dates and place ----------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/date")
    fun datesForm(@PathVariable id: Long): ModelAndView {
        val gathering = found(id)
        val form = datesForm(gathering, gathering.title, gathering.startDate.toString(), gathering.endDate.toString(), gathering.venueId?.toString().orEmpty())
        return datesPage(form, HttpStatus.OK)
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/date")
    fun saveDates(
        @PathVariable id: Long,
        @RequestParam(name = "title", defaultValue = "") title: String,
        @RequestParam(name = "startDate", required = false) startDate: String?,
        @RequestParam(name = "endDate", required = false) endDate: String?,
        @RequestParam(name = "venue", defaultValue = "") venue: String,
        @RequestParam(name = "cambio", defaultValue = "") change: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val gathering = found(id)
        return when (val edit = edits.saveDates(id, title, startDate, endDate, venue, change)) {
            DatesEdit.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is DatesEdit.Closed -> {
                // After the end the dates do not change: a request made by hand that carries other dates is refused whole, nothing is saved.
                val form = datesForm(gathering, edit.title.value.take(MAX_ECHO), "", "", edit.chosen, titleError = edit.title.error, alert = Alert(WARNING, GatheringChecks.DATES_CLOSED))
                datesPage(form, HttpStatus.CONFLICT)
            }
            is DatesEdit.Invalid -> {
                val dates = edit.dates
                val form = datesForm(
                    gathering, edit.title.value.take(MAX_ECHO), dates?.startTyped?.take(MAX_ECHO).orEmpty(), dates?.endTyped?.take(MAX_ECHO).orEmpty(), edit.chosen,
                    titleError = edit.title.error, startError = dates?.startError, endError = dates?.endError, venueError = edit.venueError, alert = errorsAlert(edit.errors),
                )
                datesPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
            is DatesEdit.Done -> saved(id, gathering, edit, request)
        }
    }

    /** What came of saving the dates of a gathering whose checks were good. */
    private fun saved(id: Long, gathering: Gathering, edit: DatesEdit.Done, request: HttpServletRequest): ModelAndView {
        val dates = edit.dates
        val titleValue = edit.title.value.take(MAX_ECHO)
        return when (val result = edit.result) {
            DatesResult.Saved -> savedAndBack(request, id, "Titolo, date e luogo salvati.")
            is DatesResult.VenueChanged -> {
                // The people who were in the rooms of the venue before are without a room: the notice says how many.
                Flash.put(request, Alert(SUCCESS, VenueChangeTexts.left(result.people, result.from), lead = "Titolo, date e luogo salvati."))
                seeOther("/convivenza/$id")
            }
            is DatesResult.PeopleInRooms -> {
                // The venue changes and people are in the rooms of the one before: the page that stands in for the dialog (with JavaScript the dialog came first, and this is a page that was old).
                val chosenVenue = edit.chosenId?.let(venues::find)
                val page = VenueChangePage(
                    id, titleValue, dates?.start?.toString(), dates?.end?.toString(), edit.chosen, result.people,
                    VenueChangeTexts.leave(result.people, result.from), chosenVenue?.let { VenueChangeTexts.parents(it.name, it.place) },
                )
                page("gathering/changeVenue", "Cambiare la struttura?", page)
            }
            DatesResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            DatesResult.VenueNotFound -> {
                // A venue that is not there any more (the page was old) or never was: the field says so, and the choices are those of now.
                val form = datesForm(
                    gathering, titleValue, dates?.startTyped?.take(MAX_ECHO).orEmpty(), dates?.endTyped?.take(MAX_ECHO).orEmpty(), edit.chosen,
                    venueError = GatheringChecks.VENUE_MISSING,
                )
                datesPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
            is DatesResult.DaysInUse -> {
                // One sentence for each day that would go (rule R3), under the field that would lose it: the first day or the last.
                val (startError, endError) = GatheringEdits.daysInUse(result, checkNotNull(dates?.start))
                val form = datesForm(
                    gathering, titleValue, checkNotNull(dates).startTyped.take(MAX_ECHO), dates.endTyped.take(MAX_ECHO), edit.chosen,
                    startError = startError, endError = endError, alert = errorsAlert(listOfNotNull(startError, endError).size), withRegistrations = true,
                )
                datesPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
        }
    }

    // ---- Fees ----------------------------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/quote")
    fun feesForm(@PathVariable id: Long): ModelAndView {
        val fees = found(id).fees
        val form = FeesForm(id, fees.fullCents.field(), fees.dailyCents.field(), fees.lunchOnlyCents.field(), null, null, null, null, edits.feesNotice(id))
        return page("gathering/feesForm", "Modifica le quote", form)
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/quote")
    fun saveFees(
        @PathVariable id: Long,
        @RequestParam(name = "fullFee", defaultValue = "") full: String,
        @RequestParam(name = "dailyFee", defaultValue = "") daily: String,
        @RequestParam(name = "lunchOnlyFee", defaultValue = "") lunchOnly: String,
        request: HttpServletRequest,
    ): ModelAndView {
        found(id)
        return when (val edit = edits.saveFees(id, full, daily, lunchOnly)) {
            FeesEdit.Saved -> savedAndBack(request, id, "Quote salvate.")
            FeesEdit.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is FeesEdit.Invalid -> {
                val form = FeesForm(
                    id, edit.full.typed.take(MAX_ECHO), edit.daily.typed.take(MAX_ECHO), edit.lunchOnly.typed.take(MAX_ECHO),
                    edit.full.error, edit.daily.error, edit.lunchOnly.error, errorsAlert(edit.errors), edits.feesNotice(id),
                )
                page("gathering/feesForm", "Modifica le quote", form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
            is FeesEdit.InUse -> {
                // A fee that registrations use cannot be left empty (rule R2): the error says how many use it and what to write if it becomes free.
                val form = FeesForm(
                    id, edit.full.typed.take(MAX_ECHO), edit.daily.typed.take(MAX_ECHO), edit.lunchOnly.typed.take(MAX_ECHO), null, edit.dailyError, edit.lunchOnlyError,
                    errorsAlert(listOfNotNull(edit.dailyError, edit.lunchOnlyError).size), edits.feesNotice(id),
                )
                page("gathering/feesForm", "Modifica le quote", form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
        }
    }

    // ---- Custodians ----------------------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/affidatari")
    fun custodiansForm(account: CurrentAccount, @PathVariable id: Long): ModelAndView {
        found(id)
        val chosen = gatherings.custodians(id).map { it.accountId }.toSet()
        return page("gathering/custodiansForm", "Modifica i maestri affidatari", custodiansForm(account, id, chosen, null))
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/affidatari")
    fun saveCustodians(
        account: CurrentAccount,
        @PathVariable id: Long,
        @RequestParam(name = "custodians", required = false) custodians: List<String>?,
        request: HttpServletRequest,
    ): ModelAndView {
        found(id)
        val wanted = custodians.orEmpty().mapNotNull { it.trim().toLongOrNull() }.toSet()
        return when (gatherings.saveCustodians(id, wanted)) {
            CustodiansResult.Saved -> savedAndBack(request, id, "Maestri affidatari salvati.")
            CustodiansResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            CustodiansResult.NeedsOne ->
                page("gathering/custodiansForm", "Modifica i maestri affidatari", custodiansForm(account, id, wanted, GatheringChecks.CUSTODIAN_MISSING), HttpStatus.UNPROCESSABLE_ENTITY)
        }
    }

    // ---- Information ---------------------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/informazioni")
    fun infoForm(@PathVariable id: Long): ModelAndView {
        val gathering = found(id)
        return page("gathering/infoForm", "Modifica le informazioni", InfoForm(id, gathering.infoText.orEmpty(), gathering.whatToBring.orEmpty(), null, null, null))
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/informazioni")
    fun saveInfo(
        @PathVariable id: Long,
        @RequestParam(name = "infoText", defaultValue = "") info: String,
        @RequestParam(name = "whatToBring", defaultValue = "") whatToBring: String,
        request: HttpServletRequest,
    ): ModelAndView {
        found(id)
        return when (val edit = edits.saveInfo(id, info, whatToBring)) {
            InfoEdit.Saved -> savedAndBack(request, id, "Informazioni salvate.")
            InfoEdit.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is InfoEdit.Invalid -> {
                val form = InfoForm(id, edit.info.value.take(MAX_ECHO_LONG), edit.whatToBring.value.take(MAX_ECHO_LONG), edit.info.error, edit.whatToBring.error, errorsAlert(edit.errors))
                page("gathering/infoForm", "Modifica le informazioni", form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
        }
    }

    // ---- Support -------------------------------------------------------------------------------------------------------------

    /** The gathering, or the page «Pagina non trovata» (an id that does not exist). */
    private fun found(id: Long): Gathering = gatherings.find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun hasRegistrations(id: Long): Boolean = usage.counts(id).active > 0

    /**
     * The form «Modifica titolo, date e luogo» (TALITA-20, criterion 8): the fields as they are, the choices of the venue (the first one has no value and says «Scegli la struttura»; [chosen] is the id
     * that is selected), «Luogo di adesso» and what the page needs to know about the registrations. The venue is the house: its place is not written here.
     */
    private fun datesForm(
        gathering: Gathering,
        title: String,
        start: String,
        end: String,
        chosen: String,
        titleError: String? = null,
        startError: String? = null,
        endError: String? = null,
        venueError: String? = null,
        alert: Alert? = null,
        withRegistrations: Boolean = hasRegistrations(gathering.id),
    ): DatesForm {
        val choices = listOf(VenueChoice("", "Scegli la struttura", chosen.isEmpty(), null)) +
            venues.all().map { VenueChoice(it.id.toString(), it.name, it.id.toString() == chosen, it.place) }
        val current = gathering.venueId?.let(venues::find)
        // The dialog of the change of venue is in the page when somebody is in a room of this venue: with JavaScript it comes before the form is sent.
        val people = if (current == null) 0 else assignments.peopleIn(gathering.id)
        return DatesForm(
            gathering.id, title, start, end, choices, titleError, startError, endError, venueError, lockedDates(gathering), alert, withRegistrations,
            currentPlace = current?.place, currentVenue = gathering.venueId?.toString().orEmpty(),
            change = if (people > 0) VenueChange(people, VenueChangeTexts.leave(people, current?.name)) else null,
        )
    }

    private fun Int?.field(): String = this?.let(GatheringFormat::amountField).orEmpty()

    private fun lockedDates(gathering: Gathering): String? =
        if (gathering.isOver(gatherings.today())) GatheringFormat.range(gathering.startDate, gathering.endDate) else null

    private fun errorsAlert(errors: Int): Alert? = if (errors > 1) Alert(DANGER, "Controlla i $errors campi segnati in rosso.") else null

    private fun blocked(gathering: Gathering): NewPage = blocked(gathering.id, gathering.title, gathering.state)

    private fun blocked(id: Long, title: String, state: GatheringState): NewPage {
        val text = GatheringEdits.blocked(title, state)
        return NewPage("", "", "", null, null, null, null, ExistingGathering("/convivenza/$id", text))
    }

    private fun custodiansForm(account: CurrentAccount, id: Long, chosen: Set<Long>, error: String?): CustodiansForm {
        val teachers = gatherings.teachers().mapIndexed { index, teacher ->
            TeacherOption(teacher.accountId, teacher.name, teacher.accountId == account.id, teacher.accountId in chosen, invalid = error != null && index == 0)
        }
        return CustodiansForm(id, teachers, error, null)
    }

    /** After a save that went well: the detail, with the notice (once). The session is where the notice waits; the redirect carries nothing else. */
    private fun savedAndBack(request: HttpServletRequest, id: Long, text: String): ModelAndView {
        Flash.put(request, Alert(SUCCESS, text))
        return seeOther("/convivenza/$id")
    }

    private fun page(template: String, title: String, model: Any, status: HttpStatus = HttpStatus.OK): ModelAndView =
        ModelAndView(template, mapOf("shell" to shells.shell(page = title, current = Section.CONVIVENZA), "page" to model)).also { it.status = status }

    private fun newPage(model: NewPage, status: HttpStatus): ModelAndView = page("gathering/new", "Nuova convivenza", model, status)

    private fun datesPage(form: DatesForm, status: HttpStatus): ModelAndView =
        page("gathering/datesForm", "Modifica titolo, date e luogo", form, status)

    private fun detailPage(account: CurrentAccount, gathering: Gathering, alert: Alert?, showMissing: Boolean, status: HttpStatus): ModelAndView =
        page("gathering/detail", gathering.title, presenter.detail(gathering, account, alert, showMissing), status)

    private companion object {
        const val MAX_ECHO = 1000
        const val MAX_ECHO_LONG = 10000
    }
}
