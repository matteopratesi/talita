package talita.venue

import org.springframework.stereotype.Component
import talita.ui.Alert
import talita.ui.ConfirmModel

// ---- The words ---------------------------------------------------------------------------------------------------------------------

/**
 * The sentences of the pages of the venues (design TALITA-20, guide 6 «Strutture»): written once, here, and read by the pages and by the tests. The presenter calculates (counts, which
 * sentence for zero, one or many) and the templates do not decide.
 */
object VenueTexts {
    const val LIST_LEAD = "Le case dove si fa la convivenza. Le vedono solo i maestri."
    const val EMPTY_TITLE = "Nessuna struttura"
    const val EMPTY_TEXT = "Scrivi la casa dove fai la convivenza. Poi la scegli nella convivenza."
    const val NEW_LEAD = "Per cominciare bastano il nome e il luogo. Il resto lo aggiungi dopo."
    const val READ_BY_PARENTS = "Nome e luogo li leggono i genitori, nella pagina informativa e nell’anteprima del link."
    const val NEW_TITLE = "Nuova struttura"
    const val CHANGE_TITLE = "Modifica la struttura"
    const val SAVED = "Struttura salvata."
    const val REMOVED = "Struttura tolta."
    const val SAVED_USED = "Nome e luogo nuovi sono già nella pagina per i genitori. Le iscrizioni già fatte restano con il testo di allora."

    /** `Usata da 1 convivenza`, `Usata da 2 convivenze`, or `Nessuna convivenza la usa`: the zero is in words. */
    fun used(count: Int): String = when (count) {
        0 -> "Nessuna convivenza la usa"
        1 -> "Usata da 1 convivenza"
        else -> "Usata da $count convivenze"
    }

    /** The notice of the form of a venue that is used: what changing the name or the place does. Null when nothing uses it. */
    fun usedNotice(count: Int): String? = if (count == 0) {
        null
    } else {
        "Questa struttura è usata da ${if (count == 1) "1 convivenza" else "$count convivenze"}. Se cambi il nome o il luogo, lo leggono subito i genitori. Le iscrizioni già fatte restano con il testo di allora."
    }

    /** The sentence that takes the place of «Togli la struttura» when a gathering uses the venue. */
    fun notRemovable(count: Int): String =
        "Questa struttura è usata da ${if (count == 1) "1 convivenza" else "$count convivenze"}, quindi non si toglie. Puoi cambiarle il nome e il luogo."

    /** The notice of a request made by hand to take away a venue that is used (409). */
    fun notRemoved(count: Int): String = "Non è stato tolto niente. Questa struttura è usata da ${if (count == 1) "1 convivenza" else "$count convivenze"}."

    /** «Per usarla, scegli «Casa sul lago» in «Modifica titolo, date e luogo» della convivenza.» */
    fun howToUse(name: String): String = "Per usarla, scegli «$name» in «Modifica titolo, date e luogo» della convivenza."

    /** What the dialog «Togliere …?» says: the venue goes away from the list, and what hangs from it with it. */
    fun removalText(name: String, hangsFrom: String?): String =
        "$name sparisce dall’elenco delle strutture. Nessuna convivenza la usa. " + (hangsFrom?.let { "$it " }.orEmpty()) + "Potrai scriverla di nuovo quando vuoi."
}

// ---- The view models ---------------------------------------------------------------------------------------------------------------

/** A row of the list «Strutture»: the whole row is the link to the page of the venue. Under the name: the [place], then the [details] (what the venue has, how many gatherings use it). */
data class VenueRow(val href: String, val name: String, val place: String, val details: List<String>)

/** The list «Strutture»: the venues, or the empty state when there are none. */
data class VenueListPage(val alert: Alert?, val rows: List<VenueRow>)

/** «Nuova struttura» and «Modifica la struttura»: the two fields as they were written, their errors, and the way back. [notice] says what changing a venue that is used does. */
data class VenueForm(
    val title: String,
    val lead: String,
    val name: String,
    val place: String,
    val nameError: String?,
    val placeError: String?,
    val alert: Alert?,
    val notice: String?,
    val action: String,
    val backHref: String,
    val backLabel: String,
)

/** The card «Nome e luogo» of the page of a venue. */
data class VenueCard(val name: String, val place: String, val used: String)

/**
 * The page of a venue: its name as the title, the cards, and what is at the bottom: «Togli la struttura» (a dialog) for a venue that no gathering uses, or the sentence that says why it is not taken away.
 */
data class VenuePage(
    val id: Long,
    val name: String,
    val alert: Alert?,
    val card: VenueCard,
    val removal: ConfirmModel?,
    val notRemovable: String?,
)

// ---- The presenter -----------------------------------------------------------------------------------------------------------------

/** Turns a venue into what its pages show: every sentence written here is the one of the design. The rules (what is in use, what is taken away) come from [Venues]. */
@Component
class VenuePresenter {

    fun row(summary: VenueSummary): VenueRow = VenueRow("/strutture/${summary.venue.id}", summary.venue.name, summary.venue.place, listOf(VenueTexts.used(summary.gatherings)))

    fun list(summaries: List<VenueSummary>, alert: Alert?): VenueListPage = VenueListPage(alert, summaries.map(::row))

    fun newForm(name: String = "", place: String = "", nameError: String? = null, placeError: String? = null, alert: Alert? = null): VenueForm =
        VenueForm(VenueTexts.NEW_TITLE, VenueTexts.NEW_LEAD, name, place, nameError, placeError, alert, null, "/strutture/nuova", "/strutture", "Torna alle strutture")

    fun changeForm(
        venue: Venue,
        gatherings: Int,
        name: String? = null,
        place: String? = null,
        nameError: String? = null,
        placeError: String? = null,
        alert: Alert? = null,
    ): VenueForm = VenueForm(
        VenueTexts.CHANGE_TITLE, venue.name, name ?: venue.name, place ?: venue.place, nameError, placeError, alert, VenueTexts.usedNotice(gatherings),
        "/strutture/${venue.id}/modifica", "/strutture/${venue.id}", "Torna alla struttura",
    )

    /** The dialog «Togliere {nome}?», or the page that stands in for it without JavaScript: the same words. A form that goes to the venue and back to the list. */
    fun removalDialog(venue: Venue): ConfirmModel = ConfirmModel(
        id = "togli-struttura",
        title = "Togliere ${venue.name}?",
        text = VenueTexts.removalText(venue.name, hangsFrom(venue)),
        confirmLabel = "Togli la struttura",
        busyLabel = "Rimozione…",
        action = "/strutture/${venue.id}/togli",
        field = "struttura",
        value = venue.id.toString(),
        target = null,
        cancelHref = "/strutture/${venue.id}",
        danger = true,
    )

    /** What goes with the venue when it is taken away: nothing yet (the rooms and the forms of the venue are in the next steps of TALITA-20). */
    private fun hangsFrom(venue: Venue): String? = null

    fun page(venue: Venue, gatherings: Int, alert: Alert?): VenuePage = VenuePage(
        venue.id, venue.name, alert, VenueCard(venue.name, venue.place, VenueTexts.used(gatherings) + "."),
        removal = if (gatherings == 0) removalDialog(venue) else null,
        notRemovable = if (gatherings > 0) VenueTexts.notRemovable(gatherings) else null,
    )
}
