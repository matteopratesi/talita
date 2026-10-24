package talita.checkin

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where the printed documents break into sheets (criterion 24): no row is lost, no sheet is empty, a sheet never has more rows than it holds, the last one has the room
 * of what follows the table unless that goes on a sheet of its own, and the count of the sheets («pagina N di M») is the one the division gives. A test of the rule is worth
 * more than a look at a print; the real sheets of Chromium are counted by the test of the browser. Rows are numbers: nothing here is personal.
 */
class PaginationTests {

    private val configurations = listOf(
        Triple("register of up to four days", Pagination.register(3), Pagination.REGISTER_RESERVE),
        Triple("register of more than four days", Pagination.register(7), Pagination.REGISTER_RESERVE),
        Triple("list for the venue with two teachers", Pagination.VENUE, Pagination.venueReserve(2)),
        Triple("list for the venue with five teachers", Pagination.VENUE, Pagination.venueReserve(5)),
        Triple("sheet for the teachers", Pagination.TEACHER_SHEET, Pagination.TEACHER_SHEET_RESERVE),
    )

    @Test
    fun `t6 c24 the capacities are the ones the design measured`() {
        assertEquals(Capacity(20, 24), Pagination.register(1))
        assertEquals(Capacity(20, 24), Pagination.register(4))
        assertEquals(Capacity(16, 20), Pagination.register(5))
        assertEquals(Capacity(16, 20), Pagination.register(7))
        assertEquals(5, Pagination.REGISTER_RESERVE)
        assertEquals(Capacity(20, 26), Pagination.VENUE)
        assertEquals(8, Pagination.venueReserve(2))
        assertEquals(8, Pagination.venueReserve(1))
        assertEquals(11, Pagination.venueReserve(5))
        assertEquals(Capacity(18, 24), Pagination.TEACHER_SHEET)
        assertEquals(2, Pagination.TEACHER_SHEET_RESERVE)
    }

    @Test
    fun `t6 c24 no row is lost or doubled, they stay in order, and no sheet is empty or holds more than its capacity`() {
        for ((name, capacity, reserve) in configurations) {
            for (count in 0..150) {
                val rows = (1..count).toList()

                val sheets = Pagination.split(rows, capacity, reserve)

                assertEquals(rows, sheets.pages.flatten(), "$name, $count rows")
                assertTrue(sheets.pages.isNotEmpty(), "$name, $count rows: there is always a sheet")
                if (count > 0) assertTrue(sheets.pages.none { it.isEmpty() }, "$name, $count rows: an empty sheet")
                assertTrue(sheets.pages.first().size <= capacity.first, "$name, $count rows: the first sheet has ${sheets.pages.first().size}")
                assertTrue(sheets.pages.drop(1).all { it.size <= capacity.other }, "$name, $count rows: a sheet holds more than ${capacity.other}")
                // A sheet is full before the next one starts: no half-empty sheet in the middle.
                assertTrue(sheets.pages.dropLast(1).withIndex().all { (index, page) -> page.size == if (index == 0) capacity.first else capacity.other }, "$name, $count rows: a sheet that is not full is followed by another")
            }
        }
    }

    @Test
    fun `t6 c24 the last sheet has the room for what follows the table, and when it has not the trailer goes on a sheet of its own`() {
        for ((name, capacity, reserve) in configurations) {
            for (count in 1..150) {
                val sheets = Pagination.split((1..count).toList(), capacity, reserve)

                val lastCapacity = if (sheets.pages.size == 1) capacity.first else capacity.other
                val last = sheets.pages.last().size
                if (sheets.trailerAlone) {
                    assertTrue(last > lastCapacity - reserve, "$name, $count rows: the trailer is alone but would have fitted")
                    assertEquals(sheets.pages.size + 1, sheets.count)
                } else {
                    assertTrue(last <= lastCapacity - reserve, "$name, $count rows: the trailer does not fit and is not alone")
                    assertEquals(sheets.pages.size, sheets.count)
                }
            }
        }
    }

    @Test
    fun `t6 c24 the register of a few days has twenty rows on the first sheet and twenty four on the others, and the totals take the room of five`() {
        val capacity = Pagination.register(3)
        // 26 children and the five blank rows: two sheets, the second with the 11 that are left and the totals.
        val two = Pagination.split((1..31).toList(), capacity, Pagination.REGISTER_RESERVE)
        assertEquals(listOf(20, 11), two.pages.map { it.size })
        assertFalse(two.trailerAlone)
        assertEquals(2, two.count)
        // One sheet: up to 15 rows with the totals under them; with 16 the totals have a sheet of their own.
        assertFalse(Pagination.split((1..15).toList(), capacity, Pagination.REGISTER_RESERVE).trailerAlone)
        assertEquals(2, Pagination.split((1..16).toList(), capacity, Pagination.REGISTER_RESERVE).count)
        assertTrue(Pagination.split((1..16).toList(), capacity, Pagination.REGISTER_RESERVE).trailerAlone)
        // The second sheet holds 24 rows, and 19 of them leave the room of the totals.
        assertFalse(Pagination.split((1..39).toList(), capacity, Pagination.REGISTER_RESERVE).trailerAlone)
        assertTrue(Pagination.split((1..41).toList(), capacity, Pagination.REGISTER_RESERVE).trailerAlone)
        assertEquals(3, Pagination.split((1..41).toList(), capacity, Pagination.REGISTER_RESERVE).count)
    }

    @Test
    fun `t6 c24 the register of more than four days has four rows less on every sheet`() {
        val capacity = Pagination.register(7)

        val two = Pagination.split((1..31).toList(), capacity, Pagination.REGISTER_RESERVE)
        assertEquals(listOf(16, 15), two.pages.map { it.size })
        assertFalse(two.trailerAlone)
        // One row more and the totals do not fit under the second sheet.
        val three = Pagination.split((1..32).toList(), capacity, Pagination.REGISTER_RESERVE)
        assertEquals(listOf(16, 16), three.pages.map { it.size })
        assertTrue(three.trailerAlone)
        assertEquals(3, three.count)
    }

    @Test
    fun `t6 c24 the list for the venue leaves the room of the teachers, and the sheet for the teachers the room of its last sentence`() {
        // 20 on the first sheet, and under them the heading, the table and the three blank rows of the teachers: 12 children leave the room, 13 do not.
        assertFalse(Pagination.split((1..12).toList(), Pagination.VENUE, Pagination.venueReserve(2)).trailerAlone)
        assertTrue(Pagination.split((1..13).toList(), Pagination.VENUE, Pagination.venueReserve(2)).trailerAlone)
        // 27 children: two sheets, 20 and 7, and the teachers under the 7.
        val children = Pagination.split((1..27).toList(), Pagination.VENUE, Pagination.venueReserve(2))
        assertEquals(listOf(20, 7), children.pages.map { it.size })
        assertFalse(children.trailerAlone)
        // The sheet for the teachers: 18 on the first, 24 on the others, and two rows under the last.
        val sheet = Pagination.split((1..31).toList(), Pagination.TEACHER_SHEET, Pagination.TEACHER_SHEET_RESERVE)
        assertEquals(listOf(18, 13), sheet.pages.map { it.size })
        assertFalse(sheet.trailerAlone)
        assertFalse(Pagination.split((1..16).toList(), Pagination.TEACHER_SHEET, Pagination.TEACHER_SHEET_RESERVE).trailerAlone)
        assertTrue(Pagination.split((1..17).toList(), Pagination.TEACHER_SHEET, Pagination.TEACHER_SHEET_RESERVE).trailerAlone)
    }

    @Test
    fun `t6 c24 a document with no rows is one sheet with no rows, and a capacity of zero is refused`() {
        val none = Pagination.split(emptyList<Int>(), Pagination.register(3), Pagination.REGISTER_RESERVE)

        assertEquals(listOf(emptyList<Int>()), none.pages)
        assertFalse(none.trailerAlone)
        assertEquals(1, none.count)
        assertFailsWith<IllegalArgumentException> { Pagination.split(listOf(1), Capacity(0, 10), 1) }
        assertFailsWith<IllegalArgumentException> { Pagination.split(listOf(1), Capacity(10, 0), 1) }
        assertFailsWith<IllegalArgumentException> { Pagination.split(listOf(1), Capacity(10, 10), -1) }
    }
}
