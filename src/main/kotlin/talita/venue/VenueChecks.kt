package talita.venue

import talita.gathering.GatheringChecks
import talita.gathering.Limits
import talita.gathering.TextCheck

/**
 * The checks of the form of a venue (TALITA-20, criteria 2 and 3), in one place so that «Nuova struttura» and «Modifica la struttura» say the same words. One error per field: the first that applies.
 *
 * **Name and place are public text, both**: the parents read them in the page of the gathering and in the preview of the link, and the place in the text of the custody. So they go through
 * the same checks as the title and the place of a gathering did: no email, no telephone, no IBAN and no first and last name of a child or a teacher that the application knows, with the same words
 * (`PublicText`: «la pagina la legge chiunque abbia il link»). The name of a venue that another venue has already is not checked here: it is the database that knows ([Venues]).
 */
object VenueChecks {

    const val NAME_MISSING = "Scrivi il nome della struttura."
    const val NAME_TAKEN = "C’è già una struttura con questo nome."
    const val PLACE_MISSING = "Scrivi il luogo: la pagina per i genitori lo mostra."

    /** The name: from 1 to 60 characters, one line, and no personal data. */
    fun name(raw: String?): TextCheck =
        GatheringChecks.publicLine(raw, Limits.VENUE_NAME, NAME_MISSING) { "Il nome può avere al massimo ${Limits.VENUE_NAME} caratteri: ne hai scritti $it." }

    /** The place: from 1 to 200 characters, one line, and no personal data. */
    fun place(raw: String?): TextCheck =
        GatheringChecks.publicLine(raw, Limits.PLACE, PLACE_MISSING) { "Il luogo può avere al massimo ${Limits.PLACE} caratteri: ne hai scritti $it." }
}
