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
import talita.checkin.VenueFormRef
import talita.gathering.GatheringChecks
import talita.gathering.PublicNames
import talita.ui.Alert
import talita.ui.AlertVariant.INFO
import talita.ui.AlertVariant.SUCCESS
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * The forms of a venue (`/strutture/{id}/moduli/…`, only for the teachers: `TeacherPaths` covers everything under `/strutture`; TALITA-20, part 3, criterion 20): «Nuovo modulo», «Modifica il modulo» and «Togli il
 * modulo», in the page of the venue. A form is a **name and four boxes** (the age on the first day, the days, the document and the tax code of the teachers): the last two say only that the sheet has a field for them,
 * and the value that a teacher writes there never reaches the server.
 *
 * - **The name is not public**: it goes through the controls of the public texts (no email, no telephone, no IBAN, no name of a person) with its own last sentence («scrivi solo il nome del documento»).
 * - **«Togli» answers with the block `#moduli-struttura` as a whole** (htmx replaces it), with the notice at the top; a form that was taken away already is `200` with «Il modulo era già stato tolto.», never an error.
 *   Without JavaScript it is an ordinary POST that answers 303 to the page, with the notice left for it.
 * - **No name goes in an address**: only the number of the venue and the number of the form. What is not there, or is of another venue, is `404` and nothing changes.
 */
@Controller
class VenueFormsController(
    private val shells: ShellFactory,
    private val venues: Venues,
    private val forms: VenueForms,
    private val names: PublicNames,
    private val presenter: VenueFormsPresenter,
) {

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun venue(id: Long): Venue = venues.byId(id) ?: notFound()

    private fun form(venueId: Long, formId: Long): VenueFormRef = forms.find(venueId, formId) ?: notFound()

    private fun page(model: VenueFormPage, status: HttpStatus = HttpStatus.OK): ModelAndView =
        ModelAndView("venue/formForm", mapOf("shell" to shells.shell(page = model.title, current = Section.CONVIVENZA), "page" to model)).also { it.status = status }

    private fun choices(age: String?, days: String?, document: String?, taxCode: String?) = FormColumns(age != null, days != null, document != null, taxCode != null)

    private fun saved(request: HttpServletRequest, venueId: Long): ModelAndView {
        FormFlash.put(request, Alert(SUCCESS, VenueFormTexts.SAVED))
        return seeOther("/strutture/$venueId#moduli")
    }

    // ---- A new form ----------------------------------------------------------------------------------------------------------------

    @GetMapping("/strutture/{id:[0-9]{1,18}}/moduli/nuovo")
    fun newForm(@PathVariable id: Long): ModelAndView = page(presenter.newPage(id, venue(id).name))

    @PostMapping("/strutture/{id:[0-9]{1,18}}/moduli/nuovo")
    fun create(
        @PathVariable id: Long,
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "withAge", required = false) age: String?,
        @RequestParam(name = "withDays", required = false) days: String?,
        @RequestParam(name = "withTeacherDocument", required = false) document: String?,
        @RequestParam(name = "withTeacherTaxCode", required = false) taxCode: String?,
        request: HttpServletRequest,
    ): ModelAndView {
        val venue = venue(id)
        val chosen = choices(age, days, document, taxCode)
        val check = GatheringChecks.withNames(names, VenueFormChecks.name(name)).single()
        if (!check.valid) return page(presenter.newPage(id, venue.name, check.value.take(MAX_ECHO), chosen, check.error), HttpStatus.UNPROCESSABLE_ENTITY)
        return when (forms.create(id, check.value, chosen)) {
            is FormSave.Saved -> saved(request, id)
            FormSave.NameTaken -> page(presenter.newPage(id, venue.name, check.value, chosen, VenueFormChecks.NAME_TAKEN), HttpStatus.UNPROCESSABLE_ENTITY)
            FormSave.NotFound -> notFound()
        }
    }

    // ---- A form to change ----------------------------------------------------------------------------------------------------------

    @GetMapping("/strutture/{id:[0-9]{1,18}}/moduli/{form:[0-9]{1,18}}/modifica")
    fun changeForm(@PathVariable id: Long, @PathVariable form: Long): ModelAndView {
        val venue = venue(id)
        return page(presenter.changePage(id, venue.name, form(id, form)))
    }

    @PostMapping("/strutture/{id:[0-9]{1,18}}/moduli/{form:[0-9]{1,18}}/modifica")
    fun change(
        @PathVariable id: Long,
        @PathVariable form: Long,
        @RequestParam(name = "name", defaultValue = "") name: String,
        @RequestParam(name = "withAge", required = false) age: String?,
        @RequestParam(name = "withDays", required = false) days: String?,
        @RequestParam(name = "withTeacherDocument", required = false) document: String?,
        @RequestParam(name = "withTeacherTaxCode", required = false) taxCode: String?,
        request: HttpServletRequest,
    ): ModelAndView {
        val venue = venue(id)
        val found = form(id, form)
        val chosen = choices(age, days, document, taxCode)
        val check = GatheringChecks.withNames(names, VenueFormChecks.name(name)).single()
        if (!check.valid) return page(presenter.changePage(id, venue.name, found, check.value.take(MAX_ECHO), chosen, check.error), HttpStatus.UNPROCESSABLE_ENTITY)
        return when (forms.update(id, form, check.value, chosen)) {
            is FormSave.Saved -> saved(request, id)
            FormSave.NameTaken -> page(presenter.changePage(id, venue.name, found, check.value, chosen, VenueFormChecks.NAME_TAKEN), HttpStatus.UNPROCESSABLE_ENTITY)
            FormSave.NotFound -> notFound()
        }
    }

    // ---- Taking a form away --------------------------------------------------------------------------------------------------------

    /** Without JavaScript: the page of confirmation, with the words of the dialog. */
    @GetMapping("/strutture/{id:[0-9]{1,18}}/moduli/{form:[0-9]{1,18}}/togli")
    fun removalPage(@PathVariable id: Long, @PathVariable form: Long): ModelAndView {
        venue(id)
        val model = presenter.removalDialog(id, form(id, form)).copy(target = null)
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.CONVIVENZA), "page" to model))
    }

    @PostMapping("/strutture/{id:[0-9]{1,18}}/moduli/{form:[0-9]{1,18}}/togli")
    fun remove(@PathVariable id: Long, @PathVariable form: Long, request: HttpServletRequest): ModelAndView {
        venue(id)
        val alert = when (forms.remove(id, form)) {
            is FormRemoval.Removed -> Alert(SUCCESS, VenueFormTexts.REMOVED)
            // Taken away by another teacher already: the state as it is, with its own words.
            FormRemoval.AlreadyGone -> Alert(INFO, VenueFormTexts.ALREADY_REMOVED)
            FormRemoval.NotFound -> notFound()
        }
        if (request.isHtmxFragment()) {
            return ModelAndView("venue/formsAnswer", mapOf("block" to presenter.block(id, alert)))
        }
        FormFlash.put(request, alert)
        return seeOther("/strutture/$id#moduli")
    }

    private companion object {
        const val MAX_ECHO = 1000
    }
}
