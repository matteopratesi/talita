package talita.checkin

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.ModelAndView
import talita.account.CurrentAccount
import talita.account.CurrentAccounts
import talita.account.Gate
import talita.account.GateMode
import talita.account.NotesGate
import talita.account.PasskeyConfirmations
import talita.account.isHtmxFragment
import talita.account.seeOther
import talita.gathering.GatheringInfo
import talita.registration.ChangeResult
import talita.registration.HealthNotes
import talita.registration.NotesRead
import talita.registration.RegistrationData
import talita.registration.RegistrationFormat
import talita.registration.Registrations
import talita.registration.RestoreResult
import talita.ui.AlertVariant
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory
import java.util.UUID

/**
 * The card of a child (`/arrivi/{id}`, only for the teachers): the days with their presences, the manleva, the registration and the other children of the same
 * parent. Every action of the card answers with the whole block `#scheda` (htmx) or with a redirect to the card (without JavaScript), so that nothing in it is left
 * old. The presences are marked and taken away by [ArrivalsController], the same touches as the list.
 *
 * An id that does not exist, or that is of another gathering than the one of Arrivi, is a page that does not exist (404), also for a request that a person makes by
 * hand. Nothing here writes a name, a phone or an amount in the log, and no name is in an address.
 */
@Controller
class CardController(
    private val shells: ShellFactory,
    private val scope: ArrivalsScope,
    private val arrivals: Arrivals,
    private val registrations: Registrations,
    private val cards: CardPresenter,
    private val presenter: ArrivalsPresenter,
    private val payments: Payments,
    private val healthNotes: HealthNotes,
    private val confirmations: PasskeyConfirmations,
    private val accounts: CurrentAccounts,
    /** The request of the thread: the card is drawn by many actions, and every one of them says what the confirmation is (a teacher with a passkey, the invitation to add one). */
    private val current: HttpServletRequest,
) {

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

    /** What the card of the notes says about the confirmation with the passkey, for this person now (TALITA-15). */
    private fun currentGate(): NotesGate = accounts.current()?.let { NotesGate.of(confirmations.view(it, current.getSession(false))) } ?: NotesGate.NONE

    private fun choice(gathering: GatheringInfo, typedDay: String?): DayChoice = DayChoice.of(gathering.days, typedDay, scope.today())

    private fun view(
        registration: RegistrationData,
        gathering: GatheringInfo,
        typedDay: String?,
        flash: CardAlert?,
        read: NotesRead? = null,
        reader: String = "",
        typed: PaymentTyped? = null,
        gate: NotesGate = currentGate(),
    ): CardPage {
        val data = arrivals.load(gathering)
        return cards.page(data, data.registrations.first { it.id == registration.id }, choice(gathering, typedDay), flash, read, reader, typed, gate)
    }

    /** The card as a whole page: with the title of the child, the way back to the list of the same day and the notice that a redirect left. */
    @GetMapping("/arrivi/{id:[0-9]{1,18}}")
    fun card(@PathVariable id: Long, @RequestParam(name = "giorno", required = false) day: String?, request: HttpServletRequest): ModelAndView {
        val (registration, gathering) = scope.registration(id) ?: notFound()
        val flash = CardFlash.take(request) ?: Flash.take(request)?.let { CardAlert(it.variant, null, it.text) }
        val page = view(registration, gathering, day, flash)
        return ModelAndView("checkin/childPage", mapOf("shell" to shells.shell(page = registration.name, current = Section.ARRIVI), "page" to page))
    }

    private fun wholePage(registration: RegistrationData, page: CardPage, status: HttpStatus): ModelAndView =
        ModelAndView("checkin/childPage", mapOf("shell" to shells.shell(page = registration.name, current = Section.ARRIVI), "page" to page)).also { it.status = status }

    /**
     * The card as an answer that is not a redirect: the block `#scheda` for htmx, and the whole page without it (a form without JavaScript that came back with an
     * error, or the notes that were asked for). [read] is the notes of the touch that wrote their reading, [typed] what was typed in the form of the payment.
     */
    private fun block(
        request: HttpServletRequest,
        registration: RegistrationData,
        gathering: GatheringInfo,
        typedDay: String?,
        flash: CardAlert?,
        status: HttpStatus = HttpStatus.OK,
        read: NotesRead? = null,
        reader: String = "",
        typed: PaymentTyped? = null,
        gate: NotesGate = currentGate(),
    ): ModelAndView {
        val page = view(registration, gathering, typedDay, flash, read, reader, typed, gate)
        return if (request.isHtmxFragment()) ModelAndView("checkin/childCard", mapOf("page" to page)).also { it.status = status } else wholePage(registration, page, status)
    }

    /** The answer to an action that went well, or was done already: the block with the notice (htmx), or a redirect to the card with the notice kept for it. */
    private fun answer(request: HttpServletRequest, registration: RegistrationData, gathering: GatheringInfo, typedDay: String?, alert: CardAlert, status: HttpStatus = HttpStatus.OK): ModelAndView {
        if (request.isHtmxFragment()) return block(request, registration, gathering, typedDay, alert, status)
        CardFlash.put(request, alert)
        return seeOther("/arrivi/" + registration.id + "?giorno=" + choice(gathering, typedDay).selected)
    }

    // ---- The manleva and the registration (criteria 8, 9) ----------------------------------------------------------------------------

    /** «Segna come ricevuta»: the signed form of a paper registration has arrived; the service is the one of TALITA-5. */
    @PostMapping("/arrivi/{id:[0-9]{1,18}}/manleva")
    fun receive(@PathVariable id: Long, @RequestParam(name = "giorno", required = false) day: String?, request: HttpServletRequest): ModelAndView {
        val (registration, gathering) = scope.registration(id) ?: notFound()
        return when (registrations.markPaper(id, received = true)) {
            ChangeResult.Done, ChangeResult.AlreadyDone -> answer(request, registration, gathering, day, CardAlert(AlertVariant.SUCCESS, null, "Manleva segnata come ricevuta."))
            else -> notFound()
        }
    }

    /** «Rimetti confermata»: a withdrawn registration comes back confirmed, with the acceptance it had (the rule of TALITA-5). */
    @PostMapping("/arrivi/{id:[0-9]{1,18}}/rimetti")
    fun restore(@PathVariable id: Long, @RequestParam(name = "giorno", required = false) day: String?, request: HttpServletRequest): ModelAndView {
        val (registration, gathering) = scope.registration(id) ?: notFound()
        return when (registrations.restoreConfirmed(id)) {
            RestoreResult.Done, RestoreResult.AlreadyDone -> answer(request, registration, gathering, day, CardAlert(AlertVariant.SUCCESS, null, "Iscrizione rimessa confermata."))
            RestoreResult.MustBeCorrected -> answer(
                request, registration, gathering, day,
                CardAlert(AlertVariant.WARNING, null, "Non è stato possibile rimettere l’iscrizione: i giorni che aveva non sono più quelli della convivenza.", "Correggi l’iscrizione", "/iscrizioni/$id/correggi"),
                HttpStatus.CONFLICT,
            )

            RestoreResult.NotFound, RestoreResult.NotAllowed -> notFound()
        }
    }

    // ---- The money (criteria 10-15) ---------------------------------------------------------------------------------------------------

    /**
     * «Registra il pagamento»: the amount is read as the fees are (comma or dot, up to two decimals, from 0,01 to 1.000 euros); a wrong one is the card again with the
     * amount still written and the error under the field (422). The form carries a request code that the server wrote: the same form sent twice is one payment, and
     * the second answer is the same confirmation.
     */
    @PostMapping("/arrivi/{id:[0-9]{1,18}}/pagamenti")
    fun pay(
        account: CurrentAccount,
        @PathVariable id: Long,
        @RequestParam(name = "giorno", required = false) day: String?,
        @RequestParam(name = "importo", defaultValue = "") amount: String,
        @RequestParam(name = "richiesta", defaultValue = "") code: String,
        http: HttpServletRequest,
    ): ModelAndView {
        val (registration, gathering) = scope.registration(id) ?: notFound()
        val request = runCatching { UUID.fromString(code.trim()) }.getOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST)
        val check = PaymentAmount.check(amount)
        val cents = check.cents ?: return block(http, registration, gathering, day, null, HttpStatus.UNPROCESSABLE_ENTITY, typed = PaymentTyped(check.typed, checkNotNull(check.error)))
        return when (payments.register(id, request, cents, account.id)) {
            is PaymentResult.Registered, is PaymentResult.AlreadyRegistered -> answer(http, registration, gathering, day, CardAlert(AlertVariant.SUCCESS, null, "Pagamento registrato."))
            PaymentResult.RequestUsedElsewhere -> answer(http, registration, gathering, day, CardAlert(AlertVariant.WARNING, null, "Non è stato possibile registrare il pagamento. Riprova."), HttpStatus.CONFLICT)
            PaymentResult.NotFound -> notFound()
        }
    }

    /** Without JavaScript «Togli» is a link to this page, with the words of the dialog; the button on it sends the same post. */
    @GetMapping("/arrivi/{id:[0-9]{1,18}}/pagamenti/{payment:[0-9]{1,18}}/togli")
    fun confirmRemoval(@PathVariable id: Long, @PathVariable payment: Long, @RequestParam(name = "giorno", required = false) day: String?, http: HttpServletRequest): ModelAndView {
        val (registration, gathering) = scope.registration(id) ?: notFound()
        val found = payments.find(payment) ?: return answer(http, registration, gathering, day, CardAlert(AlertVariant.INFO, null, "Quel pagamento era già stato tolto."))
        if (found.registrationId != id) notFound()
        val amount = RegistrationFormat.money(found.cents)
        val model = cards.removeDialog(registration, found, choice(gathering, day), "togli-pagamento-" + found.id, amount, presenter.teacherName(found.byAccountId)).copy(target = null)
        return ModelAndView("pages/confirmAction", mapOf("shell" to shells.shell(page = model.title, current = Section.ARRIVI), "page" to model))
    }

    /** Takes the payment away: it goes with all its data, and the balance and the totals change. One that is gone already is the same answer (criterion 13). */
    @PostMapping("/arrivi/{id:[0-9]{1,18}}/pagamenti/{payment:[0-9]{1,18}}/togli")
    fun removePayment(@PathVariable id: Long, @PathVariable payment: Long, @RequestParam(name = "giorno", required = false) day: String?, http: HttpServletRequest): ModelAndView {
        val (registration, gathering) = scope.registration(id) ?: notFound()
        return when (payments.remove(id, payment)) {
            is RemoveResult.Removed -> answer(http, registration, gathering, day, CardAlert(AlertVariant.SUCCESS, null, "Pagamento tolto."))
            RemoveResult.AlreadyRemoved -> answer(http, registration, gathering, day, CardAlert(AlertVariant.INFO, null, "Quel pagamento era già stato tolto."))
            RemoveResult.NotFound -> notFound()
        }
    }

    // ---- The notes on health (criteria 17, 18) ------------------------------------------------------------------------------------------

    /**
     * «Mostra le segnalazioni»: the reading of the teacher is written and the notes are read in one transaction (`HealthNotes`, the only class that touches them),
     * and only then are they shown, in this answer and in no other: whatever the next action answers, the card is closed again. If the reading cannot be written
     * nothing is shown (an error, never the notes). Without htmx the answer is the whole page; it is never kept.
     */
    @PostMapping("/arrivi/{id:[0-9]{1,18}}/segnalazioni")
    fun showNotes(account: CurrentAccount, @PathVariable id: Long, @RequestParam(name = "giorno", required = false) day: String?, http: HttpServletRequest): ModelAndView {
        val (registration, gathering) = scope.registration(id) ?: notFound()
        // The check comes first, and `readForTeacher` wants its proof: a teacher with a passkey and no confirmation that holds reads nothing and writes no reading (TALITA-15, criterion 39).
        val proof = when (val check = confirmations.gate(account, http.getSession(false))) {
            is Gate.Granted -> check.proof

            Gate.Needed -> return block(
                http, registration, gathering, day, null, HttpStatus.CONFLICT,
                gate = NotesGate(if (http.isHtmxFragment()) GateMode.SERVE else GateMode.SERVE_NO_JS),
            )
        }
        val read = healthNotes.readForTeacher(id, proof)
        return block(http, registration, gathering, day, null, read = read, reader = account.teacher?.full ?: "un maestro")
    }
}
