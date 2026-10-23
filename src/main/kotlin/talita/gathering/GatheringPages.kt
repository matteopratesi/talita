package talita.gathering

import talita.ui.Alert
import talita.ui.ConfirmModel

/** A label of state (design guide, 4.5): [tone] is `neutral`, `info`, `positive`, `warning` or `danger` (`components/badge.jte` has the classes). */
data class Badge(val label: String, val tone: String = "neutral")

/** A row of the list of the section: the whole row is the link to the detail. */
data class GatheringRow(val href: String, val title: String, val badges: List<Badge>, val dates: String)

/** The section «Convivenza»: the current gathering (a draft or open), the closed ones, and whether «Crea la convivenza» is offered. */
data class SectionPage(val current: GatheringRow?, val closed: List<GatheringRow>) {
    val empty: Boolean get() = current == null && closed.isEmpty()

    /** There is no draft or open gathering: a new one can be made. */
    val canCreate: Boolean get() = current == null
}

/** The page «Nuova convivenza»: the fields as written, their errors, and the notice above the card. */
data class NewPage(
    val title: String,
    val startDate: String,
    val endDate: String,
    val titleError: String?,
    val startError: String?,
    val endError: String?,
    val alert: Alert?,
    /** There is a draft or open gathering already: the page says so and leads to it instead of showing the form. */
    val existing: ExistingGathering?,
)

/** The gathering that stops a new one from being made. */
data class ExistingGathering(val href: String, val text: String)

/** One entry of «Prima di aprire le iscrizioni»: what to do, and the page that does it. */
data class MissingLink(val text: String, val href: String)

/** A line of the card «Quote»: [value] is null when the fee is not provided («Non prevista»). [needed]: the draft needs it to open («Da completare»). */
data class FeeLine(val label: String, val value: String?, val needed: Boolean)

/** A custodian in the card «Maestri affidatari». */
data class CustodianLine(val name: String, val you: Boolean)

/**
 * The button of the card «Stato»: [href] is the page of confirmation (without JavaScript; with it the dialog [opens] shows up
 * instead), or, when [direct], the button sends a form straight away (a draft that is not complete: no point asking to confirm what
 * the server will refuse).
 */
data class StateButton(val label: String, val primary: Boolean, val href: String, val opens: String?, val direct: Boolean, val action: String)

/** The sentence of the card «Stato» may go on with a link to the other gathering that stops the registrations from reopening: [before] [linkText] [after]. */
data class StateNote(val before: String, val linkText: String, val linkHref: String, val after: String)

/** The card «Stato». */
data class StateCard(val badges: List<Badge>, val text: String, val note: StateNote?, val button: StateButton?, val from: String)

/**
 * The detail of a gathering. [alert] is the notice that follows an action (a confirmation, a refusal), [missing] the list of what
 * a draft lacks (it comes after tapping «Apri le iscrizioni»), [standing] the notice that is there while a situation lasts.
 */
data class DetailPage(
    val id: Long,
    val title: String,
    val alert: Alert?,
    val missing: List<MissingLink>?,
    val standing: String?,
    val state: StateCard,
    val dialog: ConfirmModel?,
    val days: String,
    val place: String?,
    val placeNeeded: Boolean,
    val fees: List<FeeLine>,
    val custodians: List<CustodianLine>,
    val custodiansNeeded: Boolean,
    val infoParagraphs: List<String>,
    val whatToBring: List<String>,
    val link: LinkCard,
)

/**
 * The card «Link per i genitori»: while the gathering is a draft there is nothing to copy (the link works after the opening: only the
 * preview), afterwards the complete address, as selectable text, with the buttons that copy and share it (shown by `app.js` only if the browser
 * can). [address] is null when the public address of the site is not set (`talita.public-url`): the card says so instead of a wrong link.
 * [copyPrimary]: «Copia il link» is the main action of the page, unless the gathering is over and the thing to do is to close the registrations.
 */
data class LinkCard(
    val open: Boolean,
    val address: String?,
    val over: Boolean,
    val previewHref: String,
    val title: String,
    val copyPrimary: Boolean,
)

/** «Titolo, date e luogo»: the dates are shown as text, with no fields, when the gathering is over (criterion 17). */
data class DatesForm(
    val id: Long,
    val title: String,
    val startDate: String,
    val endDate: String,
    val place: String,
    val titleError: String?,
    val startError: String?,
    val endError: String?,
    val placeError: String?,
    val lockedDates: String?,
    val alert: Alert?,
)

/** «Quote»: three fields of an amount. */
data class FeesForm(
    val id: Long,
    val full: String,
    val daily: String,
    val lunchOnly: String,
    val fullError: String?,
    val dailyError: String?,
    val lunchOnlyError: String?,
    val alert: Alert?,
)

/** One teacher that can be chosen as a custodian. [invalid] is on the first box of a list that was refused: the focus goes there. */
data class TeacherOption(val accountId: Long, val name: String, val you: Boolean, val checked: Boolean, val invalid: Boolean = false)

/** «Maestri affidatari»: a box for every teacher. */
data class CustodiansForm(val id: Long, val teachers: List<TeacherOption>, val error: String?, val alert: Alert?)

/** «Informazioni»: the two texts of the public page. */
data class InfoForm(
    val id: Long,
    val info: String,
    val whatToBring: String,
    val infoError: String?,
    val whatToBringError: String?,
    val alert: Alert?,
)
