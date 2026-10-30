package talita.reminder

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import talita.gathering.GatheringReader
import talita.gathering.ReminderBlock
import talita.gathering.ReminderButton
import talita.gathering.ReminderCards
import talita.gathering.RemindersCard
import talita.registration.RegistrationFormat
import talita.ui.Alert
import talita.ui.AlertVariant
import talita.ui.ConfirmModel
import java.time.Clock
import java.time.OffsetDateTime

/** What a teacher is told after asking to send: the notice (live, with the focus) and the status of the answer. */
data class SendOutcome(val alert: Alert?, val status: HttpStatus)

/**
 * The card «Promemoria» of the detail of a gathering, its dialogs and what is said after a send (TALITA-13, design part 1). **Every sentence here is the one of the design**,
 * with the decision of Matteo of 24 October (comment 10950): the reminder «in forse» invites to confirm, never to withdraw.
 *
 * What the card shows is **counts only**: how many people would receive each kind, when the last one left and to how many people. Never a name or an address. In a draft,
 * with the registrations closed or with the gathering over it has only the sentence that says that reminders go with the registrations open. In a block there is one of
 * three things, never two: the button, the wait until the next one can go, or the zero in words with why.
 */
@Component
class ReminderPresenter(
    private val reminders: Reminders,
    private val gatherings: GatheringReader,
    private val clock: Clock,
) : ReminderCards {

    override fun card(gatheringId: Long, alert: Alert?): RemindersCard {
        val gathering = gatherings.byId(gatheringId)
        if (gathering == null || !gathering.registrationsPossibleToday) return RemindersCard(gatheringId, false, alert, emptyList())
        return RemindersCard(gatheringId, true, alert, ReminderType.entries.map { block(gatheringId, it) })
    }

    private fun block(gatheringId: Long, type: ReminderType): ReminderBlock {
        val count = reminders.count(gatheringId, type)
        val last = reminders.lastBatch(gatheringId, type)
        val waiting = last?.again?.takeIf { OffsetDateTime.now(clock).isBefore(it) }
        val words = words(type)
        return ReminderBlock(
            type = type.code,
            title = words.title,
            what = words.what,
            receivers = people(count),
            last = last?.let { "${RegistrationFormat.moment(it.sentAt)} · a ${people(it.reached)}" } ?: "Mai",
            button = if (waiting == null && count > 0) {
                ReminderButton("Manda il promemoria", "Manda il promemoria ${words.toWhom}", "/convivenza/$gatheringId/promemoria?tipo=${type.code}", dialog(gatheringId, type, count))
            } else {
                null
            },
            wait = waiting?.let { "Potrai rimandarlo dal ${RegistrationFormat.moment(it)}." },
            nobody = if (waiting == null && count == 0) words.nobody else null,
        )
    }

    /** «3 persone», «1 persona», «Nessuno»: the zero in words. */
    private fun people(count: Int): String = when (count) {
        0 -> "Nessuno"
        1 -> "1 persona"
        else -> "$count persone"
    }

    // ---- The dialog ----------------------------------------------------------------------------------------------------------------

    /**
     * The dialog «Mandare il promemoria?» of [type] for [count] people (also the page of confirmation without JavaScript): to how many people, to whom, what they get, that a
     * mail that has left cannot be taken back, and that another one of this kind cannot go for [Reminders.WAIT_DAYS] days. The button is the main one, not red: it takes
     * nothing away from anyone, and the sentence says the consequence.
     */
    private fun dialog(gatheringId: Long, type: ReminderType, count: Int): ConfirmModel {
        val words = words(type)
        val to = if (count == 1) "1 persona" else "$count persone"
        return ConfirmModel(
            id = "manda-${type.code}",
            title = words.dialogTitle,
            text = "Mandiamo una mail a $to: ${words.whom}. Con la mail riceveranno ${words.gets}. Non ci sono nomi né dati dei bambini. " +
                "Una mail partita non si ritira, e non potrai mandare un altro ${words.another} per ${Reminders.WAIT_DAYS} giorni.",
            confirmLabel = words.confirm,
            busyLabel = "Invio…",
            action = "/convivenza/$gatheringId/promemoria",
            field = "tipo",
            value = type.code,
            target = "#promemoria",
            cancelHref = "/convivenza/$gatheringId",
        )
    }

    /**
     * The dialog of [type] as it is now, or null when there is nothing to confirm: the gathering has the registrations closed (or is over), the next one of this kind
     * cannot go yet, or nobody would receive it. The page of confirmation without JavaScript sends the person back to the detail in that case.
     */
    fun confirmation(gatheringId: Long, type: ReminderType): ConfirmModel? {
        val gathering = gatherings.byId(gatheringId) ?: return null
        if (!gathering.registrationsPossibleToday) return null
        val last = reminders.lastBatch(gatheringId, type)
        if (last != null && OffsetDateTime.now(clock).isBefore(last.again)) return null
        val count = reminders.count(gatheringId, type)
        return if (count == 0) null else dialog(gatheringId, type, count)
    }

    // ---- What is said after the send ---------------------------------------------------------------------------------------------------

    /**
     * The answer to a request to send: the notice, and the status. The numbers are the true ones at the moment of the sending, which may not be the ones of the dialog
     * (a parent confirmed in the meantime). A request that is repeated (a double tap, two teachers) is not an error: 200, with what was done. What the day does not
     * allow, and the mail that does not leave, say so with a status that the page shows as the answer (`app.js`: 429 and 422 are swapped).
     */
    fun outcome(result: SendResult): SendOutcome = when (result) {
        is SendResult.Sent ->
            if (result.failed == 0) {
                SendOutcome(Alert(AlertVariant.SUCCESS, "Promemoria mandato a ${people(result.reached)}."), HttpStatus.OK)
            } else {
                // The register has its row: the wait of 7 days begins, there is no way to send again only to who is missing.
                SendOutcome(
                    Alert(
                        AlertVariant.WARNING, "Potrai mandare un altro promemoria dal ${RegistrationFormat.moment(result.last.again)}.",
                        lead = "Mandato a ${people(result.reached)} su ${result.recipients}: " + if (result.failed == 1) "per una la mail non è partita." else "per ${result.failed} le mail non sono partite.",
                    ),
                    HttpStatus.OK,
                )
            }
        is SendResult.TooSoon ->
            SendOutcome(Alert(AlertVariant.INFO, "Questo promemoria è già stato mandato il ${RegistrationFormat.moment(result.last.sentAt)}, a ${people(result.last.reached)}."), HttpStatus.OK)
        is SendResult.QuotaLow ->
            SendOutcome(
                Alert(
                    AlertVariant.WARNING,
                    if (result.remaining == 0) {
                        "Oggi Talità ha già mandato tutti i promemoria che può mandare. Riprova domani."
                    } else {
                        "Oggi si possono mandare ancora ${result.remaining} promemoria, ma questo ne richiede ${result.needed}. Riprova domani."
                    },
                ),
                HttpStatus.TOO_MANY_REQUESTS,
            )
        SendResult.NotSent -> SendOutcome(Alert(AlertVariant.DANGER, "Non siamo riusciti a mandare le mail. Riprova tra qualche minuto."), HttpStatus.UNPROCESSABLE_ENTITY)
        SendResult.Nobody -> SendOutcome(Alert(AlertVariant.INFO, "Nessuno da avvisare."), HttpStatus.OK)
        // The registrations closed while the page was open: the card says so by itself, with its one sentence.
        SendResult.NotOpen -> SendOutcome(null, HttpStatus.CONFLICT)
        SendResult.NotFound -> SendOutcome(null, HttpStatus.NOT_FOUND)
    }

    private class Words(
        val title: String,
        val what: String,
        val toWhom: String,
        val nobody: String,
        val dialogTitle: String,
        val whom: String,
        val gets: String,
        val another: String,
        val confirm: String,
    )

    private fun words(type: ReminderType): Words = when (type) {
        ReminderType.MAYBE -> Words(
            title = "Iscrizioni in forse",
            what = "Per chi ha un’iscrizione in forse: riceve un invito a confermarla.",
            toWhom = "a chi ha un’iscrizione in forse",
            nobody = "Nessuno da avvisare: non c’è nessuna iscrizione in forse con un indirizzo email. Le iscrizioni inserite dal cartaceo non hanno un indirizzo: se sono in forse, chiedi tu ai genitori.",
            dialogTitle = "Mandare il promemoria?",
            whom = "chi ha un’iscrizione in forse",
            gets = "un invito a confermare l’iscrizione, il titolo e le date della convivenza e il modo per smettere",
            another = "promemoria di questo tipo",
            confirm = "Manda il promemoria",
        )
        ReminderType.NOTICE -> Words(
            title = "Avviso per le nuove iscrizioni",
            what = "Per chi ha già iscritto i figli a una convivenza e ha chiesto un avviso: riceve il link della pagina informativa.",
            toWhom = "a chi ha chiesto l’avviso",
            nobody = "Nessuno da avvisare: nessun genitore ha ancora chiesto l’avviso. L’avviso serve dalla seconda convivenza: i genitori lo chiedono dopo aver iscritto i figli.",
            dialogTitle = "Mandare l’avviso?",
            whom = "chi ha già iscritto i figli a una convivenza e ha chiesto un avviso",
            gets = "il titolo e le date della convivenza, il link della pagina informativa e il modo per smettere",
            another = "avviso",
            confirm = "Manda l’avviso",
        )
    }
}
