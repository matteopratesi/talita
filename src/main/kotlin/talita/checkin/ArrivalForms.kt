package talita.checkin

/**
 * A form of the venue of a gathering (TALITA-20, part 3): a document that the house asks for, which Talità prints from Arrivi. It is a **name and four columns**, never a value: [age] is the age in years of each child on the
 * first day of the gathering (never the date of birth), [days] a column for each day, and [teacherDocument] and [teacherTaxCode] a field for each teacher in the page of the print, which a teacher fills in the browser and
 * which goes only on the paper: Talità does not receive it and does not keep it. «Cognome e nome» is always there.
 */
data class VenueFormRef(val id: Long, val name: String, val age: Boolean, val days: Boolean, val teacherDocument: Boolean, val teacherTaxCode: Boolean) {

    /** The sheet has fields for the teachers. */
    val teacherColumns: Boolean get() = teacherDocument || teacherTaxCode

    /** How many columns of fields the teachers have: 0, 1 or 2. */
    val fields: Int get() = (if (teacherDocument) 1 else 0) + (if (teacherTaxCode) 1 else 0)

    /** The columns for the children, in words: «Nome e cognome, età al primo giorno e giorni di presenza», or «Solo nome e cognome». */
    private fun children(): String = when {
        age && days -> "Nome e cognome, età al primo giorno e giorni di presenza"
        age -> "Nome e cognome, età al primo giorno"
        days -> "Nome e cognome, giorni di presenza"
        else -> "Solo nome e cognome"
    }

    /** The columns for the teachers, in words: «documento e codice fiscale», «documento» or «codice fiscale»; null when there are none. */
    private fun teachers(): String? = when {
        teacherDocument && teacherTaxCode -> "documento e codice fiscale"
        teacherDocument -> "documento"
        teacherTaxCode -> "codice fiscale"
        else -> null
    }

    /** What the sheet has, as the page of the venue and Arrivi say it: «Nome e cognome, età al primo giorno e giorni di presenza. Maestri: documento e codice fiscale.» */
    fun columns(): String = children() + "." + (teachers()?.let { " Maestri: $it." }.orEmpty())
}

/**
 * The forms that a venue asks for, as the arrivals read them (TALITA-20, part 3). Arrivi lists them under «Da stampare» and prints the one that is asked for; `talita.venue` writes them and answers here, as it does for
 * the venue of a gathering. A form is of a venue: the id of a form of another venue is an id that does not exist.
 */
interface ArrivalForms {

    /** The forms of the venue, by name. */
    fun ofVenue(venueId: Long): List<VenueFormRef>

    /** The form [id] of the venue [venueId], or null. */
    fun find(venueId: Long, id: Long): VenueFormRef?
}
