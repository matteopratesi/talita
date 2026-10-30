package talita.gathering

import talita.ui.Alert
import talita.ui.ConfirmModel

/**
 * The button «Manda il promemoria» of a block: [href] is the page of confirmation (what a link without JavaScript leads to), and with JavaScript the
 * dialog [dialog] opens instead. [ariaLabel] names the kind of reminder when the label alone does not say it (it starts with the label).
 */
data class ReminderButton(val label: String, val ariaLabel: String, val href: String, val dialog: ConfirmModel)

/**
 * One kind of reminder in the card «Promemoria» of the detail (TALITA-13, design part 1): [type] is the word of the kind (`maybe` or `notice`: it is in the
 * `id` of the block and in the body of the request, never a name), then the two rows «Lo riceverebbero» ([receivers]) and «Ultimo invio» ([last]), and **one of
 * three things**, never two: the [button], the [wait] until the next one can go, or the [nobody] sentence (the zero in words, with why).
 */
data class ReminderBlock(
    val type: String,
    val title: String,
    val what: String,
    val receivers: String,
    val last: String,
    val button: ReminderButton?,
    val wait: String?,
    val nobody: String?,
)

/**
 * The card «Promemoria» of the detail of a gathering. It is the whole `#promemoria` section: the answer to a send replaces it as a whole (htmx), with [alert]
 * (what came of the action) in it. [open] is false when the registrations are not open or the gathering is over (a draft, closed, finished): then the card
 * has only a sentence, and no blocks.
 */
data class RemindersCard(val gatheringId: Long, val open: Boolean, val alert: Alert?, val blocks: List<ReminderBlock>)

/**
 * What the detail of a gathering asks of the reminders (as [RoomSummaries] and [RegistrationUsage] do): the gathering asks, `talita.reminder` answers, and this
 * package depends on neither the tables nor the code of the reminders, which in their turn read the gathering.
 */
interface ReminderCards {

    /** The card of [gatheringId], with [alert] in it if there is one. Counts only: never a name or an address. */
    fun card(gatheringId: Long, alert: Alert? = null): RemindersCard
}
