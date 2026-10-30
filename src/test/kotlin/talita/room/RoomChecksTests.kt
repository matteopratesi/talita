package talita.room

import org.junit.jupiter.api.Test
import talita.registration.DayKind
import talita.registration.Presence
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The checks of the form of a room and the words of the rooms, without the application (criteria 2 and 3): what is a name and what are beds, which names say who sleeps in the
 * room, and how numbers, people and presences are written. Names are synthetic.
 */
class RoomChecksTests {

    @Test
    fun `t11 c02 a name is from 1 to 50 characters on one line, without the spaces at the sides`() {
        assertEquals(NameCheck("Camera 3", null), RoomChecks.name("Camera 3"))
        assertEquals(NameCheck("Stanza azzurra", null), RoomChecks.name("   Stanza azzurra \t"))
        assertEquals(NameCheck("Primo piano, a destra", null), RoomChecks.name("Primo piano, a destra"))
        // A line break or a control character is a space: the name stays on one line.
        assertEquals(NameCheck("Camera 3 bis", null), RoomChecks.name("Camera 3\r\nbis"))
        assertEquals(NameCheck("Camera 3 bis", null), RoomChecks.name("Camera\u0000 3\u0007 bis"))
        assertEquals(RoomChecks.NAME_MISSING, RoomChecks.name("").error)
        assertEquals(RoomChecks.NAME_MISSING, RoomChecks.name("   \n  ").error)
        assertEquals(RoomChecks.NAME_MISSING, RoomChecks.name(null).error)
        // 50 characters are good, 51 are not (counted as the database counts them: an emoji is one character).
        assertNull(RoomChecks.name("a".repeat(50)).error)
        assertEquals(RoomChecks.NAME_TOO_LONG, RoomChecks.name("a".repeat(51)).error)
        assertNull(RoomChecks.name("🛏".repeat(50)).error)
        assertEquals(RoomChecks.NAME_TOO_LONG, RoomChecks.name("🛏".repeat(51)).error)
    }

    @Test
    fun `t11 c02 the beds are a whole number from 1 to 50 and anything else has one sentence`() {
        for (good in listOf("1", "4", "50", " 12 ", "007")) assertEquals(null, RoomChecks.beds(good).error, good)
        assertEquals(4, RoomChecks.beds(" 4 ").beds)
        for (bad in listOf("", "   ", "0", "51", "-3", "4,5", "4.0", "quattro", "+4", "1e1", "123456789012")) {
            val check = RoomChecks.beds(bad)
            assertEquals(RoomChecks.BEDS_WRONG, check.error, "«$bad»")
            assertNull(check.beds)
        }
        assertEquals("Scrivi quanti posti ci sono, da 1 a 50.", RoomChecks.BEDS_WRONG)
        assertEquals(RoomChecks.BEDS_WRONG, RoomChecks.beds(null).error)
    }

    @Test
    fun `t11 c03 a name that says the sex of who sleeps there is refused with one sentence that does not repeat the word`() {
        for (bad in listOf("Maschi", "camera delle femmine", "Bambine", "MASCHI", "Camera dei maschietti", "Stanza femminile", "Le ragazze", "Camera uomini", "Girls", "boys room", "Femmine · piano 1")) {
            val check = RoomChecks.name(bad)
            assertEquals(RoomChecks.NAME_DESCRIBES_PEOPLE, check.error, "«$bad»")
            assertTrue(RoomChecks.describesPeople(bad), bad)
        }
        assertEquals("Scrivi il nome della camera nella casa, non chi ci dorme.", RoomChecks.NAME_DESCRIBES_PEOPLE)
        for (bad in listOf("Maschi", "camera delle femmine", "Bambine")) {
            assertFalse(RoomChecks.NAME_DESCRIBES_PEOPLE.lowercase().contains(bad.lowercase().removePrefix("camera delle ")), "the sentence repeats the word of $bad")
        }
    }

    @Test
    fun `t11 c03 the names of the house go, and so do the plurals that also mean children and the accents are not a way round`() {
        for (good in listOf("Camera 3", "Stanza azzurra", "Primo piano, a destra", "Camera 1", "Mansarda", "Bambini piccoli", "Sala ragazzi", "Maschera", "Camera gialla")) {
            assertNull(RoomChecks.name(good).error, good)
        }
        // Without the accent and in other capitals it is the same word.
        assertTrue(RoomChecks.describesPeople("signorine"))
        assertTrue(RoomChecks.describesPeople("SIGNORINE"))
        assertTrue(RoomChecks.describesPeople("Camera-delle-femmine"))
    }

    @Test
    fun `t11 c03 the list of the words is one, in the code, with the stars of the words that have a stem`() {
        assertTrue(RoomChecks.SEX_WORDS.contains("maschi*") && RoomChecks.SEX_WORDS.contains("femmin*"))
        assertTrue(RoomChecks.SEX_WORDS.none { it.contains(' ') })
        // «bambini» and «ragazzi» are also how Italian says children: a room called so says an age, not a sex.
        assertTrue(RoomChecks.SEX_WORDS.none { it == "bambini" || it == "ragazzi" })
    }

    @Test
    fun `t11 c01 beds, people and rooms are written as the guide says, with the singular and the zero in words`() {
        assertEquals("1 posto", RoomFormat.beds(1))
        assertEquals("4 posti", RoomFormat.beds(4))
        assertEquals("1 persona", RoomFormat.people(1))
        assertEquals("5 persone", RoomFormat.people(5))
        assertEquals("Vuota · 4 posti", RoomFormat.count(0, 4))
        assertEquals("1 persona · 2 posti", RoomFormat.count(1, 2))
        assertEquals("5 persone · 4 posti", RoomFormat.count(5, 4))
        assertEquals("5 persone per 4 posti.", RoomFormat.overfull(5, 4))
        assertEquals("2 persone per 1 posto.", RoomFormat.overfull(2, 1))
        assertEquals("Camera 2 · 4 su 4 posti", RoomFormat.option("Camera 2", 4, 4))
        assertEquals("Stanza azzurra · 1 su 2 posti", RoomFormat.option("Stanza azzurra", 1, 2))
        assertEquals("Stanza piccola · 0 su 1 posto", RoomFormat.option("Stanza piccola", 0, 1))
    }

    @Test
    fun `t11 c06 what the child comes for is written with the days and the lunches as the design has it`() {
        val friday = LocalDate.of(2027, 9, 17)
        assertEquals("Tutti i giorni", RoomFormat.presence(Presence.ALL))
        assertEquals("Sab 18 · Dom 19", RoomFormat.presence(Presence(false, mapOf(friday.plusDays(1) to DayKind.FULL, friday.plusDays(2) to DayKind.FULL))))
        assertEquals("Ven 17 · Sab 18 · pranzo Dom 19", RoomFormat.presence(Presence(false, mapOf(friday to DayKind.FULL, friday.plusDays(1) to DayKind.FULL, friday.plusDays(2) to DayKind.LUNCH))))
        assertEquals("Dom 19", RoomFormat.presence(Presence(false, mapOf(friday.plusDays(2) to DayKind.FULL))))
        assertEquals("pranzo Sab 18, Dom 19", RoomFormat.presence(Presence(false, mapOf(friday.plusDays(1) to DayKind.LUNCH, friday.plusDays(2) to DayKind.LUNCH))))
    }

    @Test
    fun `t11 c06 a person is the one of a form only when it has the letter and the number`() {
        assertEquals(PersonRef.Child(12), PersonRef.parse("b12"))
        assertEquals(PersonRef.Teacher(7), PersonRef.parse(" m7 "))
        assertEquals("b12", PersonRef.Child(12).key)
        assertEquals("m7", PersonRef.Teacher(7).key)
        for (bad in listOf("", "12", "b", "x12", "b-1", "b1.5", "B12", "bb12", "b1234567890123456789")) {
            assertNull(PersonRef.parse(bad), bad)
        }
        assertNull(PersonRef.parse(null))
    }
}
