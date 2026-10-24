package talita.checkin

/**
 * The rows of a printed document divided into sheets of paper (criterion 24): [pages] are the rows of each sheet, and [trailerAlone] says that what comes after the
 * last row (the totals of the register, the teachers of the venue list, the closing sentence of the sheet) does not fit on the last sheet and has a sheet of its own.
 */
data class Sheets<T>(val pages: List<List<T>>, val trailerAlone: Boolean) {

    /** How many sheets the document has, the one of the trailer included: the «di M» of «pagina N di M». */
    val count: Int get() = pages.size + if (trailerAlone) 1 else 0
}

/** How many rows a sheet holds: the first has the title of the document over the table, the others do not. */
data class Capacity(val first: Int, val other: Int)

/**
 * Where a printed document breaks into sheets. Every sheet is a block with its own table and its own header, because Safari does not repeat the `thead` over the sheets
 * and a person who reads a sheet alone must see what each column is. So the server divides the rows, with the capacities that the design measured with Chromium (they
 * leave 15-25 mm free on every sheet, so that a name or a note on two lines does not push a row over), and counts the sheets with the same rule for the «di M».
 *
 * The last sheet leaves the room of [reserve] rows for what follows the table; if the rows of the last sheet are more than what is left, the trailer goes to one
 * more sheet, which says so in its caption.
 */
object Pagination {

    /** The register: 9,5 mm a row. With more than four days the columns of the days squeeze the notes, and four rows less fit. */
    fun register(days: Int): Capacity = if (days > 4) Capacity(16, 20) else Capacity(20, 24)

    /** What the totals of the register take, in rows. */
    const val REGISTER_RESERVE = 5

    /** The venue list: 8 mm a row. */
    val VENUE = Capacity(20, 26)

    /** The heading, the header of the table, the teachers, the three blank rows and the sentence under them: with two teachers, eight rows. */
    fun venueReserve(teachers: Int): Int = maxOf(8, teachers + 6)

    /** The sheet for the teachers: 9 mm a row. */
    val TEACHER_SHEET = Capacity(18, 24)

    /** The sentence at the end of the sheet for the teachers. */
    const val TEACHER_SHEET_RESERVE = 2

    /** Divides [rows]: no row is lost, no sheet is empty (but the one of a document with no rows), and the last one has room for the trailer unless it goes alone. */
    fun <T> split(rows: List<T>, capacity: Capacity, reserve: Int): Sheets<T> {
        require(capacity.first > 0 && capacity.other > 0 && reserve >= 0) { "the capacity of a sheet is more than zero" }
        val pages = mutableListOf<List<T>>()
        var rest = rows
        var room = capacity.first
        while (rest.isNotEmpty()) {
            pages += rest.take(room)
            rest = rest.drop(room)
            room = capacity.other
        }
        if (pages.isEmpty()) pages += emptyList<T>()
        val lastCapacity = if (pages.size == 1) capacity.first else capacity.other
        return Sheets(pages, trailerAlone = pages.last().size > lastCapacity - reserve)
    }
}
