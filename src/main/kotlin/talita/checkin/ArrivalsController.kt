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
import talita.account.isHtmxFragment
import talita.account.seeOther
import talita.gathering.GatheringInfo
import talita.registration.RegistrationData
import talita.ui.Alert
import talita.ui.AlertVariant
import talita.ui.Flash
import talita.ui.Section
import talita.ui.ShellFactory
import java.time.LocalDate
import java.time.OffsetDateTime

/** Reads the day of a form (`2027-09-18`): null when it is not a date at all. */
internal fun parseDay(typed: String?): LocalDate? = typed?.trim()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * «Arrivi» for the teachers (`/arrivi`, only for them: `TeacherPaths` answers 403 to anyone else before a request gets here): the list of the day with its summary
 * and its search, and the two touches of a row, «Segna presente» and «Togli». The card of the child, the payments, the notes on health and the prints are other
 * classes of this package.
 *
 * - **A touch is answered with what is true now**: the row, the summary and a sentence for the screen reader (htmx), or a redirect (without JavaScript). A presence
 *   that was there already, or taken away already, is the same answer with a note, never an error (200); what cannot be done any more (the registration was withdrawn
 *   meanwhile, the day is not one of the gathering, a day after today) is 409 and nothing changes in the database.
 * - **Nothing here writes a name in the log, and no name is in an address**: the addresses have the number of the registration and the day.
 */
@Controller
class ArrivalsController(
    private val shells: ShellFactory,
    private val scope: ArrivalsScope,
    private val arrivals: Arrivals,
    private val checkins: Checkins,
    private val presenter: ArrivalsPresenter,
    private val cards: CardPresenter,
    private val clock: java.time.Clock,
) {

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND)

    private fun page(template: String, title: String, model: Any, status: HttpStatus = HttpStatus.OK): ModelAndView =
        ModelAndView(template, mapOf("shell" to shells.shell(page = title, current = Section.ARRIVI), "page" to model)).also { it.status = status }

    // ---- The list (criteria 1-3) --------------------------------------------------------------------------------------------------

    @GetMapping("/arrivi")
    fun list(@RequestParam(name = "giorno", required = false) day: String?, request: HttpServletRequest): ModelAndView {
        val alert = Flash.take(request)
        val gathering = scope.gathering()
        if (gathering == null) {
            val draft = scope.draft()
            return page("checkin/arrivals", "Arrivi", if (draft != null) presenter.draftPage(draft) else presenter.nonePage())
        }
        val choice = DayChoice.of(gathering.days, day, scope.today())
        return page("checkin/arrivals", "Arrivi", presenter.page(arrivals.load(gathering), choice, OffsetDateTime.now(clock), alert))
    }

    // ---- The touches (criteria 4, 6-8, 20, 21) -------------------------------------------------------------------------------------

    /** What a touch led to, in the words of the row, of the announcement and of the card. */
    private data class Outcome(
        val status: HttpStatus,
        val rowNote: RowNote?,
        val announcement: String,
        val card: CardAlert,
        /** The row is not a button any more (the registration was withdrawn, the day is not one of the gathering). */
        val asLink: Boolean = false,
    )

    private fun markOutcome(registration: RegistrationData, day: LocalDate, result: MarkResult): Outcome {
        val name = registration.name
        return when (result) {
            is MarkResult.Marked -> {
                val confirmed = if (result.confirmed) " L’iscrizione di $name era in forse: adesso è confermata." else ""
                val announcement = "Presenza segnata: $name." + if (result.confirmed) " L’iscrizione era in forse: adesso è confermata." else ""
                val card = if (result.notExpected) {
                    CardAlert(
                        AlertVariant.WARNING, "Presenza segnata.",
                        "${CheckinFormat.longDayCapital(day)} non era previsto per $name: la quota dovuta non cambia finché non correggi l’iscrizione.$confirmed",
                        "Correggi l’iscrizione", "/iscrizioni/${registration.id}/correggi",
                    )
                } else {
                    CardAlert(AlertVariant.SUCCESS, null, "Presenza segnata.$confirmed")
                }
                Outcome(HttpStatus.OK, null, announcement, card)
            }

            is MarkResult.AlreadyMarked -> {
                val by = presenter.teacherName(result.byAccountId)
                val when_ = cards.whenMarked(Checkin(registration.id, day, result.at, result.byAccountId), day)
                Outcome(
                    HttpStatus.OK, RowNote("Era già presente: l’ha segnata $by $when_.", warning = false),
                    "$name era già presente: l’ha segnata $by $when_.", CardAlert(AlertVariant.INFO, null, "$name era già presente: l’ha segnata $by $when_."),
                )
            }

            MarkResult.Withdrawn -> Outcome(
                HttpStatus.CONFLICT, RowNote("Non segnata: nel frattempo l’iscrizione è stata ritirata.", warning = true),
                "$name non è stata segnata: l’iscrizione è stata ritirata.",
                CardAlert(AlertVariant.WARNING, null, "Non è stato possibile segnare la presenza: l’iscrizione di $name nel frattempo è stata ritirata."), asLink = true,
            )

            MarkResult.OutsideTheDates -> Outcome(
                HttpStatus.CONFLICT, RowNote("Non segnata: nel frattempo le date della convivenza sono cambiate. Aggiorna la pagina.", warning = true),
                "$name non è stata segnata: le date della convivenza sono cambiate.",
                CardAlert(AlertVariant.WARNING, null, "Non è stato possibile segnare la presenza: le date della convivenza sono cambiate. Ecco i giorni di adesso."), asLink = true,
            )

            MarkResult.NotYet -> Outcome(
                HttpStatus.CONFLICT, RowNote("Non segnata: quel giorno non è ancora arrivato.", warning = true),
                "$name non è stata segnata: quel giorno non è ancora arrivato.",
                CardAlert(AlertVariant.WARNING, null, "Non è stato possibile segnare la presenza: quel giorno non è ancora arrivato."), asLink = true,
            )

            MarkResult.NotFound -> notFound()
        }
    }

    private fun unmarkOutcome(registration: RegistrationData, result: UnmarkResult): Outcome {
        val name = registration.name
        return when (result) {
            UnmarkResult.Unmarked -> Outcome(HttpStatus.OK, null, "Presenza tolta: $name.", CardAlert(AlertVariant.SUCCESS, null, "Presenza tolta."))

            UnmarkResult.AlreadyUnmarked -> Outcome(
                HttpStatus.OK, RowNote("La presenza era già stata tolta.", warning = false),
                "$name: la presenza era già stata tolta.", CardAlert(AlertVariant.INFO, null, "La presenza era già stata tolta."),
            )

            UnmarkResult.OutsideTheDates -> Outcome(
                HttpStatus.CONFLICT, RowNote("Non tolta: nel frattempo le date della convivenza sono cambiate. Aggiorna la pagina.", warning = true),
                "$name: la presenza non è stata tolta, le date della convivenza sono cambiate.",
                CardAlert(AlertVariant.WARNING, null, "Non è stato possibile togliere la presenza: le date della convivenza sono cambiate. Ecco i giorni di adesso."), asLink = true,
            )

            UnmarkResult.NotYet -> Outcome(
                HttpStatus.CONFLICT, RowNote("Non tolta: quel giorno non è ancora arrivato.", warning = true),
                "$name: la presenza non è stata tolta, quel giorno non è ancora arrivato.",
                CardAlert(AlertVariant.WARNING, null, "Non è stato possibile togliere la presenza: quel giorno non è ancora arrivato."), asLink = true,
            )

            UnmarkResult.NotFound -> notFound()
        }
    }

    /**
     * Marks or takes away the presence of [id] on the day of the form and answers. With htmx the answer is the row (or, when the touch came from the card of the
     * child, `da=scheda`, the whole block of the card); without it a redirect to the list, at the row, or to the card.
     */
    private fun change(account: CurrentAccount, id: Long, typedDay: String, from: String?, request: HttpServletRequest, marking: Boolean): ModelAndView {
        val (registration, gathering) = scope.registration(id) ?: notFound()
        val day = parseDay(typedDay)
        val outcome = if (marking) {
            markOutcome(registration, day ?: gathering.days.first(), if (day == null) MarkResult.OutsideTheDates else checkins.mark(id, day, account.id))
        } else {
            unmarkOutcome(registration, if (day == null) UnmarkResult.OutsideTheDates else checkins.unmark(id, day))
        }
        // The day that the answer is about: the one of the form when it is one of the gathering, else the day that the list opens on.
        val shown = day?.takeIf { it in gathering.days } ?: DayChoice.defaultDay(gathering.days, scope.today())
        val choice = DayChoice(gathering.days, shown, scope.today())
        val fromCard = from == "scheda"
        return if (request.isHtmxFragment()) {
            answer(gathering, registration.id, choice, outcome, fromCard)
        } else {
            redirect(request, registration.id, choice, outcome, fromCard)
        }
    }

    private fun answer(gathering: GatheringInfo, id: Long, choice: DayChoice, outcome: Outcome, fromCard: Boolean): ModelAndView {
        val data = arrivals.load(gathering)
        val registration = data.registrations.first { it.id == id }
        val model = if (fromCard) {
            ModelAndView("checkin/childCard", mapOf("page" to cards.page(data, registration, choice, outcome.card)))
        } else {
            val row = presenter.row(data, registration, choice.selected, choice.markable, outcome.rowNote, focus = true, asLink = outcome.asLink)
            ModelAndView("checkin/rowAnswer", mapOf("answer" to RowAnswer(row, presenter.summary(data, choice), outcome.announcement)))
        }
        return model.also { it.status = outcome.status }
    }

    private fun redirect(request: HttpServletRequest, id: Long, choice: DayChoice, outcome: Outcome, fromCard: Boolean): ModelAndView {
        if (fromCard) {
            CardFlash.put(request, outcome.card)
            return seeOther("/arrivi/$id?giorno=${choice.selected}")
        }
        // On the list the row says what happened; only what did not go as the person thought is also said at the top.
        if (outcome.status != HttpStatus.OK || outcome.rowNote != null) {
            Flash.put(request, Alert(if (outcome.status == HttpStatus.OK) AlertVariant.INFO else AlertVariant.WARNING, outcome.announcement))
        }
        return seeOther("/arrivi?giorno=${choice.selected}#riga-$id")
    }

    @PostMapping("/arrivi/{id:[0-9]{1,18}}/presenze")
    fun mark(
        account: CurrentAccount,
        @PathVariable id: Long,
        @RequestParam(name = "giorno", defaultValue = "") day: String,
        @RequestParam(name = "da", required = false) from: String?,
        request: HttpServletRequest,
    ): ModelAndView = change(account, id, day, from, request, marking = true)

    @PostMapping("/arrivi/{id:[0-9]{1,18}}/presenze/togli")
    fun unmark(
        account: CurrentAccount,
        @PathVariable id: Long,
        @RequestParam(name = "giorno", defaultValue = "") day: String,
        @RequestParam(name = "da", required = false) from: String?,
        request: HttpServletRequest,
    ): ModelAndView = change(account, id, day, from, request, marking = false)
}
