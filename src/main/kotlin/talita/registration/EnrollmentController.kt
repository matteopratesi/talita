package talita.registration

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.ModelAndView
import talita.account.CurrentAccount
import talita.account.isHtmxFragment
import talita.account.seeOther
import talita.gathering.GatheringInfo
import talita.gathering.GatheringReader
import talita.gathering.GatheringState
import talita.gathering.Gatherings
import talita.gathering.InfoPages
import talita.ui.Alert
import talita.ui.AlertVariant.DANGER
import talita.ui.AlertVariant.SUCCESS
import talita.ui.AlertVariant.WARNING
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory

/**
 * The registration of the children by the parent, in two steps (criteria 1-10, 18, 33): `/info/{codice}/iscrizione`. Any account can open it;
 * without one the person goes to the access page and comes back here.
 *
 * - **Step 1, the data**: the phone, a block for each child (up to 10), what they come for, the notes on health with the consent. A good form is
 *   answered with **step 2**, the same request (200, not a redirect), with the data in the hidden fields: **nothing is saved before the second
 *   confirmation**, because a draft on the server would keep the names of children outside their tables. A bad one comes back as step 1 (422).
 * - **Step 2, the check**: the recap, the short notice, the text of the custody and the boxes. «Accetta e iscrivi» saves everything or nothing; every
 *   datum is checked again and the text is made again from what the database says now ([Registrations.submit]).
 * - **The blocks and the quota** are pieces that htmx asks for (`/figli`, `/quota`) and that work with a whole page without JavaScript. The quota
 *   is always worked out here, with [Quota], and the notes on health never travel with it.
 * - **Closed meanwhile**: the registrations that close, or a gathering that ends, while a form is open make a page that says so and saves nothing.
 *
 * Nothing here logs a name, a date of birth, a phone, a note or an address: only the ids that were made.
 */
@Controller
class EnrollmentController(
    private val shells: ShellFactory,
    private val gatherings: GatheringReader,
    private val days: Gatherings,
    private val registrations: Registrations,
    private val presenter: RegistrationPresenter,
    private val pages: InfoPages,
) {

    private val log = LoggerFactory.getLogger(EnrollmentController::class.java)

    // ---- Step 1 ---------------------------------------------------------------------------------------------------------------

    @GetMapping("/info/{code}/iscrizione")
    fun first(@PathVariable code: String, account: CurrentAccount, response: HttpServletResponse): ModelAndView {
        pages.robots(response)
        val gathering = visible(code) ?: return pages.notFound()
        if (!gathering.registrationsPossibleToday) return seeOther("/info/$code")
        val phone = registrations.latestPhone(account.id, gathering.id)
        return firstPage(code, gathering, account, FormInput(phone.orEmpty(), listOf(ChildInput())), null, null, phoneKnown = phone != null)
    }

    /** «Controlla e accetta»: step 2 for a good form, step 1 again (422) for one that is not. */
    @PostMapping("/info/{code}/iscrizione")
    fun next(@PathVariable code: String, @RequestParam params: Map<String, String>, account: CurrentAccount, response: HttpServletResponse): ModelAndView {
        pages.robots(response)
        val gathering = visible(code) ?: return pages.notFound()
        if (!gathering.registrationsPossibleToday) return closed(gathering)
        val form = FormParser.parse(params)
        if (form.tooMany) return firstPage(code, gathering, account, form, null, Alert(WARNING, RegistrationChecks.TOO_MANY), status = HttpStatus.UNPROCESSABLE_ENTITY)
        val check = checked(gathering, account, form)
        if (!check.valid) return firstPage(code, gathering, account, form, check, errorsAlert(check.errorCount), status = HttpStatus.UNPROCESSABLE_ENTITY)
        return secondPage(code, gathering, account, check, accept = false, other = false, religion = false, errors = emptyMap(), alert = null, response = response)
    }

    /** «Aggiungi un figlio», «Togli», «Aggiungi» of «I tuoi figli»: the blocks change and the quota with them. */
    @PostMapping("/info/{code}/iscrizione/figli")
    fun blocks(
        @PathVariable code: String,
        @RequestParam params: Map<String, String>,
        account: CurrentAccount,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ModelAndView {
        pages.robots(response)
        val gathering = visible(code) ?: return pages.notFound()
        if (!gathering.registrationsPossibleToday) return away(code, request, response)
        var form = FormParser.parse(params)
        var focus: Int? = null
        val action = params["azione"].orEmpty()
        when {
            action == "aggiungi" && form.children.size < FormParser.MAX_CHILDREN -> {
                form = form.copy(children = form.children + ChildInput())
                focus = form.children.lastIndex
            }

            action.startsWith("togli:") -> {
                val index = action.removePrefix("togli:").toIntOrNull()
                if (index != null && index in form.children.indices && form.children.size > 1) {
                    form = form.copy(children = form.children.filterIndexed { i, _ -> i != index })
                    focus = (index - 1).coerceAtLeast(0)
                }
            }

            action.startsWith("aggiungi:") -> {
                val known = action.removePrefix("aggiungi:").toLongOrNull()?.let { registrations.knownChild(account.id, it) }
                if (known != null && form.children.none { it.knownId == known.id }) {
                    val block = ChildInput(
                        knownId = known.id, firstName = known.firstName, lastName = known.lastName,
                        birthDay = known.birthDate.dayOfMonth.toString(), birthMonth = known.birthDate.monthValue.toString(), birthYear = known.birthDate.year.toString(),
                    )
                    val untouched = form.children.indexOfFirst { it.untouched }
                    if (untouched >= 0) {
                        form = form.copy(children = form.children.mapIndexed { i, child -> if (i == untouched) block else child })
                        focus = untouched
                    } else if (form.children.size < FormParser.MAX_CHILDREN) {
                        form = form.copy(children = form.children + block)
                        focus = form.children.lastIndex
                    }
                }
            }
        }
        val page = presenter.firstStep(code, gathering, form, null, registrations.knownChildren(account.id, gathering.id), null, focus)
        // With htmx only the blocks and the total are replaced; without JavaScript the whole page comes back, as it was.
        return if (request.isHtmxFragment()) {
            ModelAndView("registration/childrenFragment", mapOf("page" to page))
        } else {
            firstStepView(page)
        }
    }

    /** The quota of every block, after a change of the days: the lines and the total, as pieces that replace the old ones. */
    @PostMapping("/info/{code}/iscrizione/quota")
    fun quota(
        @PathVariable code: String,
        @RequestParam params: Map<String, String>,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ModelAndView {
        pages.robots(response)
        val gathering = visible(code) ?: return pages.notFound()
        if (!gathering.registrationsPossibleToday) return away(code, request, response)
        val offer = presenter.offer(gathering)
        val form = FormParser.parse(params)
        val lines = form.children.mapIndexed { index, input -> presenter.quota(offer, input)?.let(RegistrationFormat::money).let { QuotaLine(index, it) } }
        return ModelAndView("registration/quotaFragment", mapOf("lines" to lines, "total" to presenter.total(offer, form.children)))
    }

    // ---- Back from step 2 -------------------------------------------------------------------------------------------------------

    /** «Indietro» and «Modifica i dati»: step 1 again with what was written. */
    @PostMapping("/info/{code}/iscrizione/indietro")
    fun back(@PathVariable code: String, @RequestParam params: Map<String, String>, account: CurrentAccount, response: HttpServletResponse): ModelAndView {
        pages.robots(response)
        val gathering = visible(code) ?: return pages.notFound()
        if (!gathering.registrationsPossibleToday) return closed(gathering)
        // The step does not leave an entry in the history (step 2 had none): the address stays the one of step 1.
        response.setHeader("HX-Push-Url", "false")
        return firstPage(code, gathering, account, FormParser.parse(params), null, null)
    }

    /** A page that was reloaded: there is nothing to show at these addresses, the registration starts again from step 1. */
    @GetMapping("/info/{code}/iscrizione/indietro", "/info/{code}/iscrizione/conferma")
    fun reloaded(@PathVariable code: String): ModelAndView = seeOther("/info/$code/iscrizione")

    // ---- Step 2: «Accetta e iscrivi» ----------------------------------------------------------------------------------------------

    @PostMapping("/info/{code}/iscrizione/conferma")
    fun confirm(
        @PathVariable code: String,
        @RequestParam params: Map<String, String>,
        account: CurrentAccount,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): ModelAndView {
        pages.robots(response)
        val gathering = visible(code) ?: return pages.notFound()
        if (!gathering.registrationsPossibleToday) return closed(gathering)
        val form = FormParser.parse(params)
        // «Already registered» is not asked here: the same confirmation twice is not an error, and `submit` tells one from the other.
        val check = RegistrationChecks.check(form, presenter.offer(gathering), gathering.start, days.today())
        // The second step is reached with a good form: one that is not (made by hand) goes back to the first.
        if (form.tooMany || !check.valid) return firstPage(code, gathering, account, form, check, errorsAlert(check.errorCount), status = HttpStatus.UNPROCESSABLE_ENTITY)

        val accept = params["acceptCustody"] == "true"
        val other = params["otherParent"] == "true"
        val religion = params["religionConsent"] == "true"
        val plural = check.children.size > 1
        val errors = buildMap {
            if (!accept) put("accept", if (plural) "Per iscrivere i bambini devi accettare l’affidamento." else "Per iscrivere il bambino devi accettare l’affidamento.")
            if (!other) put("other", "Serve la dichiarazione sull’altro genitore: spunta la casella.")
            if (!religion) put("religion", "Senza questa casella non possiamo fare l’iscrizione.")
        }
        if (errors.isNotEmpty()) {
            val text = registrations.text(gathering, account.email, check.children)
            return secondPage(code, gathering, account, check, accept, other, religion, errors, errorsAlert(errors.size), HttpStatus.UNPROCESSABLE_ENTITY, text = text, response = response)
        }

        return when (val result = registrations.submit(account, gathering.id, check, params["textHash"].orEmpty(), religion)) {
            is SubmitResult.Created -> {
                log.info("Registrations made: gathering {}, {} of them.", gathering.id, result.registrationIds.size)
                done(request, plural)
            }

            SubmitResult.Repeated -> done(request, plural)

            is SubmitResult.Closed -> closed(gathering, result.over)

            is SubmitResult.TextChanged -> {
                val changed = Alert(WARNING, TEXT_CHANGED)
                secondPage(code, gathering, account, check, false, false, false, emptyMap(), changed, HttpStatus.CONFLICT, text = result.text, response = response)
            }

            is SubmitResult.Taken -> {
                val marked = check.copy(
                    children = check.children.mapIndexed { index, block ->
                        if (index in result.positions) block.copy(errors = block.errors + ("firstName" to "${block.name} ${Registrations.TAKEN}"), taken = true) else block
                    },
                )
                firstPage(code, gathering, account, form, marked, errorsAlert(marked.errorCount), status = HttpStatus.UNPROCESSABLE_ENTITY)
            }
        }
    }

    // ---- Support ----------------------------------------------------------------------------------------------------------------

    /** The gathering of this code, if it exists and is not a draft: a draft and a code that does not exist are the same answer. */
    private fun visible(code: String): GatheringInfo? = gatherings.byCode(code)?.takeIf { it.state != GatheringState.DRAFT }

    private fun checked(gathering: GatheringInfo, account: CurrentAccount, form: FormInput): FormCheck {
        val check = RegistrationChecks.check(form, presenter.offer(gathering), gathering.start, days.today())
        return registrations.markTaken(check, account.id, gathering.id)
    }

    private fun errorsAlert(errors: Int): Alert? = if (errors > 1) Alert(DANGER, "Controlla i $errors campi segnati in rosso.") else null

    private fun done(request: HttpServletRequest, plural: Boolean): ModelAndView {
        Flash.put(request, Alert(SUCCESS, if (plural) "Iscrizioni fatte." else "Iscrizione fatta."))
        return seeOther("/iscrizioni")
    }

    private fun firstPage(
        code: String,
        gathering: GatheringInfo,
        account: CurrentAccount,
        form: FormInput,
        check: FormCheck?,
        alert: Alert?,
        status: HttpStatus = HttpStatus.OK,
        phoneKnown: Boolean = false,
    ): ModelAndView =
        firstStepView(presenter.firstStep(code, gathering, form, check, registrations.knownChildren(account.id, gathering.id), alert, phoneKnown = phoneKnown)).also { it.status = status }

    private fun firstStepView(page: FirstStepPage): ModelAndView =
        ModelAndView("registration/firstStep", mapOf("shell" to shells.shell(page = "Iscrizione", current = Section.ISCRIZIONI), "page" to page))

    @Suppress("LongParameterList")
    private fun secondPage(
        code: String,
        gathering: GatheringInfo,
        account: CurrentAccount,
        check: FormCheck,
        accept: Boolean,
        other: Boolean,
        religion: Boolean,
        errors: Map<String, String>,
        alert: Alert?,
        status: HttpStatus = HttpStatus.OK,
        text: CustodyText.Rendered = registrations.text(gathering, account.email, check.children),
        response: HttpServletResponse? = null,
    ): ModelAndView {
        // The second step is the answer to the first (or to the second again) and adds no entry to the history of the browser.
        response?.setHeader("HX-Push-Url", "false")
        val page = presenter.secondStep(code, gathering, check, text, accept, other, religion, errors, alert)
        return ModelAndView("registration/secondStep", mapOf("shell" to shells.shell(page = "Controlla e accetta", current = Section.ISCRIZIONI), "page" to page))
            .also { it.status = status }
    }

    /** The page of a registration that can no longer be made (409): nothing was saved. */
    private fun closed(gathering: GatheringInfo, over: Boolean = gathering.over): ModelAndView =
        ModelAndView(
            "registration/closed",
            mapOf("shell" to shells.shell(page = if (over) "La convivenza è finita" else "Le iscrizioni sono chiuse", current = Section.ISCRIZIONI), "page" to ClosedPage(over, "/info/${gathering.code}")),
        ).also { it.status = HttpStatus.CONFLICT }

    /** A piece that was asked for after the registrations closed: htmx goes to the registration, which sends to the information page; a plain request is sent there. */
    private fun away(code: String, request: HttpServletRequest, response: HttpServletResponse): ModelAndView {
        if (request.isHtmxFragment()) {
            response.status = HttpStatus.OK.value()
            response.setHeader("HX-Redirect", "/info/$code")
            return ModelAndView("registration/quotaFragment", mapOf("lines" to emptyList<QuotaLine>(), "total" to null))
        }
        return seeOther("/info/$code")
    }

    companion object {
        const val TEXT_CHANGED = "Mentre leggevi, i maestri hanno cambiato il testo dell’affidamento (maestri affidatari, date o luogo). Rileggilo con attenzione: non abbiamo salvato niente e le caselle sono da spuntare di nuovo."
    }
}

/** The quota of the block [index] for the piece that replaces its line: null when there is no valid choice yet (the line is empty and hidden). */
data class QuotaLine(val index: Int, val amount: String?)
