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
import talita.account.isHtmxFragment
import talita.account.seeOther
import talita.gathering.GatheringInfo
import talita.registration.ChangeResult
import talita.registration.RegistrationData
import talita.registration.Registrations
import talita.registration.RestoreResult
import talita.ui.Flash
import talita.ui.AlertVariant
import talita.ui.Section
import talita.ui.ShellFactory

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
) {

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun choice(gathering: GatheringInfo, typedDay: String?): DayChoice = DayChoice.of(gathering.days, typedDay, scope.today())

    private fun view(registration: RegistrationData, gathering: GatheringInfo, typedDay: String?, flash: CardAlert?): CardPage {
        val data = arrivals.load(gathering)
        return cards.page(data, data.registrations.first { it.id == registration.id }, choice(gathering, typedDay), flash)
    }

    /** The card as a whole page: with the title of the child, the way back to the list of the same day and the notice that a redirect left. */
    @GetMapping("/arrivi/{id:[0-9]{1,18}}")
    fun card(@PathVariable id: Long, @RequestParam(name = "giorno", required = false) day: String?, request: HttpServletRequest): ModelAndView {
        val (registration, gathering) = scope.registration(id) ?: notFound()
        val flash = CardFlash.take(request) ?: Flash.take(request)?.let { CardAlert(it.variant, null, it.text) }
        val page = view(registration, gathering, day, flash)
        return ModelAndView("checkin/childPage", mapOf("shell" to shells.shell(page = registration.name, current = Section.ARRIVI), "page" to page))
    }

    /** The answer to an action of the card: the block with the notice (htmx), or a redirect to the card with the notice kept for it. */
    private fun answer(request: HttpServletRequest, registration: RegistrationData, gathering: GatheringInfo, typedDay: String?, alert: CardAlert, status: HttpStatus = HttpStatus.OK): ModelAndView {
        if (request.isHtmxFragment()) {
            return ModelAndView("checkin/childCard", mapOf("page" to view(registration, gathering, typedDay, alert))).also { it.status = status }
        }
        CardFlash.put(request, alert)
        return seeOther("/arrivi/${registration.id}?giorno=${choice(gathering, typedDay).selected}")
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
}
