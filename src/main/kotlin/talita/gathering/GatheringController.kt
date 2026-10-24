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
 * the section, «Nuova convivenza», the detail and one form for each of its parts (title, dates and place · fees · custodians · information).
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
    private val presenter: GatheringPresenter,
    private val usage: RegistrationUsage,
    private val arrivals: ArrivalUsage,
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
    ): ModelAndView {
        // Somebody made one (or it was there all along, for a request made by hand): the page says so, nothing is created.
        gatherings.current()?.let { return newPage(blocked(it), HttpStatus.CONFLICT) }

        val titleCheck = GatheringChecks.title(title)
        val dates = GatheringChecks.dates(startDate, endDate, gatherings.today())
        val errors = listOfNotNull(titleCheck.error, dates.startError, dates.endError).size
        if (errors > 0) {
            val page = NewPage(
                titleCheck.value.take(MAX_ECHO), dates.startTyped.take(MAX_ECHO), dates.endTyped.take(MAX_ECHO),
                titleCheck.error, dates.startError, dates.endError, errorsAlert(errors), null,
            )
            return newPage(page, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (val result = gatherings.create(titleCheck.value, dates.start!!, dates.end!!)) {
            is CreateResult.Created -> savedAndBack(request, result.id, "Convivenza salvata.")
            is CreateResult.AlreadyCurrent -> newPage(blocked(result.id, result.title, result.state), HttpStatus.CONFLICT)
        }
    }

    // ---- The detail ----------------------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}")
    fun detail(account: CurrentAccount, @PathVariable id: Long, request: HttpServletRequest): ModelAndView =
        detailPage(account, found(id), Flash.take(request), showMissing = false, status = HttpStatus.OK)

    // ---- Title, dates and place ----------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/date")
    fun datesForm(@PathVariable id: Long): ModelAndView {
        val gathering = found(id)
        return datesPage(DatesForm(id, gathering.title, gathering.startDate.toString(), gathering.endDate.toString(), gathering.place.orEmpty(), null, null, null, null, lockedDates(gathering), null, hasRegistrations(id)), HttpStatus.OK)
    }

    @PostMapping("/convivenza/{id:[0-9]{1,18}}/date")
    fun saveDates(
        @PathVariable id: Long,
        @RequestParam(name = "title", defaultValue = "") title: String,
        @RequestParam(name = "startDate", required = false) startDate: String?,
        @RequestParam(name = "endDate", required = false) endDate: String?,
        @RequestParam(name = "place", defaultValue = "") place: String,
        request: HttpServletRequest,
    ): ModelAndView {
        val gathering = found(id)
        val today = gatherings.today()
        val over = gathering.isOver(today)

        // After the end the dates do not change: a request made by hand that carries other dates is refused whole, nothing is saved.
        val changesDates = over && ((startDate != null && startDate.trim() != gathering.startDate.toString()) || (endDate != null && endDate.trim() != gathering.endDate.toString()))
        val titleCheck = GatheringChecks.title(title)
        val placeCheck = GatheringChecks.place(place, required = gathering.state != GatheringState.DRAFT)
        if (changesDates) {
            val form = DatesForm(id, titleCheck.value.take(MAX_ECHO), "", "", placeCheck.value.take(MAX_ECHO), titleCheck.error, null, null, placeCheck.error, lockedDates(gathering), Alert(WARNING, GatheringChecks.DATES_CLOSED), hasRegistrations(id))
            return datesPage(form, HttpStatus.CONFLICT)
        }
        val dates = if (over) null else GatheringChecks.dates(startDate, endDate, today, gathering.startDate to gathering.endDate)
        val errors = listOfNotNull(titleCheck.error, dates?.startError, dates?.endError, placeCheck.error).size
        if (errors > 0) {
            val form = DatesForm(
                id, titleCheck.value.take(MAX_ECHO), dates?.startTyped?.take(MAX_ECHO).orEmpty(), dates?.endTyped?.take(MAX_ECHO).orEmpty(), placeCheck.value.take(MAX_ECHO),
                titleCheck.error, dates?.startError, dates?.endError, placeCheck.error, lockedDates(gathering), errorsAlert(errors), hasRegistrations(id),
            )
            return datesPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (val result = gatherings.saveDates(id, titleCheck.value, dates?.start, dates?.end, placeCheck.value.ifEmpty { null }, usage, arrivals)) {
            DatesResult.Saved -> savedAndBack(request, id, "Titolo, date e luogo salvati.")
            DatesResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is DatesResult.DaysInUse -> {
                // One sentence for each day that would go (rule R3), under the field that would lose it: the first day or the last.
                val sentences = result.days.map { use -> use.day to "Non puoi togliere ${GatheringFormat.weekday(use.day)} ${GatheringFormat.date(use.day)}: " + holdsTheDay(use) }
                val startError = sentences.filter { it.first.isBefore(dates!!.start!!) }.joinToString(" ") { it.second }.ifEmpty { null }
                val endError = sentences.filter { !it.first.isBefore(dates!!.start!!) }.joinToString(" ") { it.second }.ifEmpty { null }
                val form = DatesForm(
                    id, titleCheck.value.take(MAX_ECHO), dates!!.startTyped.take(MAX_ECHO), dates.endTyped.take(MAX_ECHO), placeCheck.value.take(MAX_ECHO),
                    null, startError, endError, null, lockedDates(gathering), errorsAlert(listOfNotNull(startError, endError).size), true,
                )
                datesPage(form, HttpStatus.UNPROCESSABLE_ENTITY)
            }
        }
    }

    // ---- Fees ----------------------------------------------------------------------------------------------------------------

    @GetMapping("/convivenza/{id:[0-9]{1,18}}/quote")
    fun feesForm(@PathVariable id: Long): ModelAndView {
        val fees = found(id).fees
        val form = FeesForm(id, fees.fullCents.field(), fees.dailyCents.field(), fees.lunchOnlyCents.field(), null, null, null, null, feesNotice(id))
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
        val gathering = found(id)
        val fullCheck = GatheringChecks.fee(full, required = gathering.state != GatheringState.DRAFT)
        val dailyCheck = GatheringChecks.fee(daily)
        val lunchCheck = GatheringChecks.fee(lunchOnly)
        val errors = listOfNotNull(fullCheck.error, dailyCheck.error, lunchCheck.error).size
        if (errors > 0) {
            val form = FeesForm(
                id, fullCheck.typed.take(MAX_ECHO), dailyCheck.typed.take(MAX_ECHO), lunchCheck.typed.take(MAX_ECHO),
                fullCheck.error, dailyCheck.error, lunchCheck.error, errorsAlert(errors), feesNotice(id),
            )
            return page("gathering/feesForm", "Modifica le quote", form, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        return when (val result = gatherings.saveFees(id, Fees(fullCheck.cents, dailyCheck.cents, lunchCheck.cents), usage)) {
            FeesResult.Saved -> savedAndBack(request, id, "Quote salvate.")
            FeesResult.NotFound -> throw ResponseStatusException(HttpStatus.NOT_FOUND)
            is FeesResult.InUse -> {
                // A fee that registrations use cannot be left empty (rule R2): the error says how many use it and what to write if it becomes free.
                val daily = result.daily.takeIf { it > 0 }?.let(::inUse)
                val lunch = result.lunchOnly.takeIf { it > 0 }?.let(::inUse)
                val form = FeesForm(
                    id, fullCheck.typed.take(MAX_ECHO), dailyCheck.typed.take(MAX_ECHO), lunchCheck.typed.take(MAX_ECHO), null, daily, lunch,
                    errorsAlert(listOfNotNull(daily, lunch).size), feesNotice(id),
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
        val infoCheck = GatheringChecks.info(info)
        val bringCheck = GatheringChecks.whatToBring(whatToBring)
        val errors = listOfNotNull(infoCheck.error, bringCheck.error).size
        if (errors > 0) {
            val form = InfoForm(id, infoCheck.value.take(MAX_ECHO_LONG), bringCheck.value.take(MAX_ECHO_LONG), infoCheck.error, bringCheck.error, errorsAlert(errors))
            return page("gathering/infoForm", "Modifica le informazioni", form, HttpStatus.UNPROCESSABLE_ENTITY)
        }
        gatherings.saveInfo(id, infoCheck.value.ifEmpty { null }, bringCheck.value.ifEmpty { null })
        return savedAndBack(request, id, "Informazioni salvate.")
    }

    // ---- Support -------------------------------------------------------------------------------------------------------------

    /** The gathering, or the page «Pagina non trovata» (an id that does not exist). */
    private fun found(id: Long): Gathering = gatherings.find(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

    /** The error of a fee that registrations use and that was left empty. */
    private fun inUse(count: Int): String =
        (if (count == 1) "La usa 1 iscrizione" else "La usano $count iscrizioni") + ": non puoi lasciarla vuota. Se diventa gratuita, scrivi 0."

    /** What holds a day that the new dates would take away: the registrations that chose it, the presences marked on it, or both (one sentence). */
    private fun holdsTheDay(use: DayInUse): String {
        val registrations = when (use.registrations) {
            0 -> null
            1 -> "l’ha scelto 1 iscrizione"
            else -> "l’hanno scelto ${use.registrations} iscrizioni"
        }
        val presences = when (use.presences) {
            0 -> null
            1 -> "c’è 1 presenza segnata"
            else -> "ci sono ${use.presences} presenze segnate"
        }
        return listOfNotNull(registrations, presences).joinToString(" e ") + "."
    }

    private fun hasRegistrations(id: Long): Boolean = usage.counts(id).active > 0

    /** Rule R2: with registrations that are not withdrawn the form of the fees says how many are recalculated. */
    private fun feesNotice(id: Long): String? {
        val active = usage.counts(id).active
        return when {
            active == 0 -> null
            active == 1 -> "Hai già 1 iscrizione: se cambi le quote, la sua quota dovuta si ricalcola. Quello che è già stato pagato non cambia."
            else -> "Hai già $active iscrizioni: se cambi le quote, la quota dovuta di ognuna si ricalcola. Quello che è già stato pagato non cambia."
        }
    }

    private fun Int?.field(): String = this?.let(GatheringFormat::amountField).orEmpty()

    private fun lockedDates(gathering: Gathering): String? =
        if (gathering.isOver(gatherings.today())) GatheringFormat.range(gathering.startDate, gathering.endDate) else null

    private fun errorsAlert(errors: Int): Alert? = if (errors > 1) Alert(DANGER, "Controlla i $errors campi segnati in rosso.") else null

    private fun blocked(gathering: Gathering): NewPage = blocked(gathering.id, gathering.title, gathering.state)

    private fun blocked(id: Long, title: String, state: GatheringState): NewPage {
        val text = if (state == GatheringState.DRAFT) {
            "C’è già una convivenza in bozza: «$title». Puoi modificarla invece di crearne un’altra."
        } else {
            "C’è già una convivenza con le iscrizioni aperte: «$title». Chiudi le iscrizioni prima di crearne un’altra."
        }
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
