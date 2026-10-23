package talita.gathering

import org.junit.jupiter.api.Test
import talita.ClockConfiguration
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of the gathering that need no database and no server: the days, what is «over» with the clock of Rome, the codes, the words
 * for dates and amounts, and the checks of the fields (criteria 3-7, 18, 22). Dates here are fixed on purpose (the examples of the
 * design, in 2027): they are never compared with today, except through a clock that the test fixes.
 */
class GatheringRulesTests {

    private fun gathering(start: LocalDate, end: LocalDate, state: GatheringState = GatheringState.OPEN) = Gathering(
        1, "q7Vd_3KmZ0xRb-Ew", "Convivenza di settembre 2027", start, end, "Casa per ritiri di esempio", Fees(6000, 2500, 1000), null, null, state,
    )

    private val friday = LocalDate.of(2027, 9, 17)

    private fun clockAt(instant: String): Clock = Clock.fixed(Instant.parse(instant), ClockConfiguration.ZONE)

    // ---- The days of presence (criterion 4) ----------------------------------------------------------------------------------

    @Test
    fun `t4 c04 a gathering from 17 to 19 September 2027 has three days, Friday 17, Saturday 18 and Sunday 19, and with the end on 18 two`() {
        val three = gathering(friday, LocalDate.of(2027, 9, 19))

        assertEquals(listOf(friday, LocalDate.of(2027, 9, 18), LocalDate.of(2027, 9, 19)), three.days)
        assertEquals("Venerdì 17, sabato 18 e domenica 19 settembre 2027", GatheringFormat.days(three.days))
        assertEquals(2, gathering(friday, LocalDate.of(2027, 9, 18)).days.size)
        assertEquals("Venerdì 17 e sabato 18 settembre 2027", GatheringFormat.days(gathering(friday, LocalDate.of(2027, 9, 18)).days))
        assertEquals("Sabato 18 settembre 2027", GatheringFormat.days(listOf(LocalDate.of(2027, 9, 18))))
    }

    @Test
    fun `t4 c04 days that change month or year each carry their own date`() {
        val newYear = gathering(LocalDate.of(2027, 12, 30), LocalDate.of(2028, 1, 1)).days
        assertEquals("Giovedì 30 dicembre 2027, venerdì 31 dicembre 2027 e sabato 1 gennaio 2028", GatheringFormat.days(newYear))

        val monthChange = gathering(LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 2)).days
        assertEquals("Venerdì 30 aprile 2027, sabato 1 maggio 2027 e domenica 2 maggio 2027", GatheringFormat.days(monthChange))
    }

    @Test
    fun `t4 c04 the longest gathering is seven days and a day has no more than itself`() {
        assertEquals(7, gathering(friday, friday.plusDays(6)).days.size)
        assertEquals(listOf(friday), gathering(friday, friday).days)
    }

    @Test
    fun `the range of the dates is written as the guide says`() {
        assertEquals("17–19 settembre 2027", GatheringFormat.range(friday, LocalDate.of(2027, 9, 19)))
        assertEquals("30 aprile – 2 maggio 2027", GatheringFormat.range(LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 2)))
        assertEquals("30 dicembre 2027 – 1 gennaio 2028", GatheringFormat.range(LocalDate.of(2027, 12, 30), LocalDate.of(2028, 1, 1)))
        assertEquals("18 settembre 2027", GatheringFormat.range(LocalDate.of(2027, 9, 18), LocalDate.of(2027, 9, 18)))
    }

    // ---- Over, with the day of Rome (criterion 18) -------------------------------------------------------------------------

    @Test
    fun `t4 c18 with the end on 19 September the 19 is not over and the 20 is, also at half past midnight in Rome (22_30 UTC of the 19)`() {
        val gathering = gathering(friday, LocalDate.of(2027, 9, 19))

        val during = LocalDate.now(clockAt("2027-09-19T10:00:00Z"))
        val justAfterMidnight = LocalDate.now(clockAt("2027-09-19T22:30:00Z"))
        val justBeforeMidnight = LocalDate.now(clockAt("2027-09-19T21:59:59Z"))

        assertEquals(LocalDate.of(2027, 9, 19), during)
        assertFalse(gathering.isOver(during))
        assertEquals(LocalDate.of(2027, 9, 19), justBeforeMidnight, "21:59 UTC is 23:59 in Rome (summer time)")
        assertFalse(gathering.isOver(justBeforeMidnight))
        assertEquals(LocalDate.of(2027, 9, 20), justAfterMidnight, "22:30 UTC of the 19th is already the 20th in Rome")
        assertTrue(gathering.isOver(justAfterMidnight))
    }

    @Test
    fun `t4 c18 the days of Rome follow the winter time as well`() {
        val gathering = gathering(LocalDate.of(2027, 12, 18), LocalDate.of(2027, 12, 19))

        assertFalse(gathering.isOver(LocalDate.now(clockAt("2027-12-19T22:30:00Z"))), "22:30 UTC is 23:30 in Rome in winter: still the 19th")
        assertTrue(gathering.isOver(LocalDate.now(clockAt("2027-12-19T23:30:00Z"))), "23:30 UTC is 00:30 in Rome: the 20th")
    }

    @Test
    fun `t4 c18 a draft is never over and the registrations are possible only when it is open and the end has not passed`() {
        val past = LocalDate.of(2027, 9, 1)
        val today = LocalDate.of(2027, 9, 19)

        assertFalse(gathering(friday, past, GatheringState.DRAFT).isOver(today))
        assertTrue(gathering(past.minusDays(2), past, GatheringState.OPEN).isOver(today))
        assertTrue(gathering(past.minusDays(2), past, GatheringState.CLOSED).isOver(today))
        assertTrue(gathering(friday, LocalDate.of(2027, 9, 19), GatheringState.OPEN).registrationsPossible(today))
        assertFalse(gathering(friday, LocalDate.of(2027, 9, 19), GatheringState.CLOSED).registrationsPossible(today))
        assertFalse(gathering(friday, LocalDate.of(2027, 9, 19), GatheringState.DRAFT).registrationsPossible(today))
        assertFalse(gathering(past.minusDays(2), past, GatheringState.OPEN).registrationsPossible(today))
    }

    // ---- The public code (criterion 22) ---------------------------------------------------------------------------------------

    @Test
    fun `t4 c22 a code is 16 characters of base64url, made from 96 bits, and two are never the same`() {
        val codes = (1..2000).map { PublicCodes.next() }

        assertEquals(emptyList(), codes.filter { !Regex("^[A-Za-z0-9_-]{16}$").matches(it) })
        assertEquals(codes.size, codes.toSet().size)
        // 16 characters of base64url carry 96 bits: the 12 bytes that made them come back whole.
        assertEquals(12, java.util.Base64.getUrlDecoder().decode(codes.first()).size)
    }

    // ---- Amounts and the fields (criteria 3, 5, 6, 7) -----------------------------------------------------------------------

    @Test
    fun `t4 c07 amounts are written with the comma, with the decimals only when they are not zero, and zero is Gratuita`() {
        assertEquals("60 €", GatheringFormat.fee(6000))
        assertEquals("12,50 €", GatheringFormat.fee(1250))
        assertEquals("12,05 €", GatheringFormat.fee(1205))
        assertEquals("1.000 €", GatheringFormat.fee(100000))
        assertEquals("Gratuita", GatheringFormat.fee(0))
        assertEquals("60", GatheringFormat.amountField(6000))
        assertEquals("12,50", GatheringFormat.amountField(1250))
        assertEquals("0", GatheringFormat.amountField(0))
    }

    @Test
    fun `t4 c07 a fee takes 0 to 1000 euros with two decimals at most, comma or dot, and nothing at all means not provided`() {
        assertEquals(6000, GatheringChecks.fee("60").cents)
        assertEquals(1250, GatheringChecks.fee("12,50").cents)
        assertEquals(1250, GatheringChecks.fee("12.5").cents)
        assertEquals(1205, GatheringChecks.fee("12,05").cents)
        assertEquals(0, GatheringChecks.fee("0").cents)
        assertEquals(100000, GatheringChecks.fee("1000").cents)
        assertEquals(6000, GatheringChecks.fee("  60  ").cents)
        assertNull(GatheringChecks.fee("").cents)
        assertNull(GatheringChecks.fee("   ").error)

        for (typed in listOf("-5", "dieci", "60 €", "1.000", "1,000", "1.000,50", "12,555", "12,", ",5", "1000,01", "1001", "10000", "6 0", "٣")) {
            val check = GatheringChecks.fee(typed)
            assertEquals(GatheringChecks.FEE_FORMAT, check.error, "«$typed» should be refused")
            assertNull(check.cents)
        }
        assertEquals("Scrivi un importo in euro, per esempio 60 oppure 12,50. Il massimo è 1000 €.", GatheringChecks.FEE_FORMAT)
    }

    @Test
    fun `t4 c03 the title is required, at most 100 characters after the spaces at the sides, and the error says how many were written`() {
        assertEquals("Scrivi il titolo.", GatheringChecks.title("").error)
        assertEquals("Scrivi il titolo.", GatheringChecks.title("   \t ").error)
        assertEquals("Convivenza di settembre 2027", GatheringChecks.title("  Convivenza di settembre 2027 ").value)
        assertNull(GatheringChecks.title("a".repeat(100)).error)
        assertEquals("Il titolo può avere al massimo 100 caratteri: ne hai scritti 101.", GatheringChecks.title("a".repeat(101)).error)
        // Spaces at the sides are not counted.
        assertNull(GatheringChecks.title("  " + "a".repeat(100) + "  ").error)
    }

    @Test
    fun `t4 c03 the length counts characters the way the database does, so an emoji is one`() {
        assertNull(GatheringChecks.title("😀".repeat(100)).error)
        assertEquals("Il titolo può avere al massimo 100 caratteri: ne hai scritti 101.", GatheringChecks.title("😀".repeat(101)).error)
    }

    @Test
    fun `t4 c05 place, information and what to bring have their maximums and the error says them`() {
        assertEquals("Il luogo può avere al massimo 200 caratteri: ne hai scritti 201.", GatheringChecks.place("a".repeat(201), required = false).error)
        assertNull(GatheringChecks.place("a".repeat(200), required = false).error)
        assertEquals("Le informazioni possono avere al massimo 5.000 caratteri: ne hai scritti 5.001.", GatheringChecks.info("a".repeat(5001)).error)
        assertNull(GatheringChecks.info("a".repeat(5000)).error)
        assertEquals("L’elenco può avere al massimo 2.000 caratteri: ne hai scritti 2.001.", GatheringChecks.whatToBring("a".repeat(2001)).error)
        assertNull(GatheringChecks.whatToBring("a".repeat(2000)).error)
    }

    @Test
    fun `t4 c05 the text is kept as written, with its line breaks, and only the spaces at the sides go`() {
        val written = "\r\n  Primo paragrafo\r\n\r\n\r\nSecondo, con\r\ndue righe  \r\n"

        assertEquals("Primo paragrafo\n\n\nSecondo, con\ndue righe", GatheringChecks.info(written).value)
        assertEquals("", GatheringChecks.info("  \n  ").value)
        // A place is one line: a line break becomes a space.
        assertEquals("Casa per ritiri di esempio", GatheringChecks.place("Casa per\nritiri di  esempio".replace("  ", " "), required = false).value)
    }

    @Test
    fun `t4 c05 without a place the draft is fine and a gathering that is not a draft any more has an error`() {
        assertNull(GatheringChecks.place("", required = false).error)
        assertEquals(GatheringChecks.PLACE_MISSING, GatheringChecks.place("  ", required = true).error)
        assertNull(GatheringChecks.fee("", required = false).error)
        assertEquals(GatheringChecks.FULL_FEE_MISSING, GatheringChecks.fee("", required = true).error)
    }

    @Test
    fun `t4 c06 an email address or a phone number in a text of the public page is refused, a date and a time are not`() {
        for (text in listOf("Scrivi a nome@example.org per informazioni", "mario.rossi+prova@example.org", "chiedi a marta@esempio.it")) {
            assertEquals(PublicText.Findings(email = true, phone = false, iban = false), PublicText.check(text), text)
        }
        for (text in listOf("Chiama il 3331234567", "tel. 333 123 4567", "tel. 333.123.4567", "333-123-4567", "+39 333 1234567", "+39333123456", "012345678")) {
            assertEquals(PublicText.Findings(email = false, phone = true, iban = false), PublicText.check(text), text)
        }
        for (text in listOf("17/09/2027 alle 18:30", "dalle 9.30", "60 €", "dal 17.09.2027 al 19.09.2027", "17-09-2027", "tra le 9 e le 10", "3 giorni, 2 notti", "Ci vediamo il 17 settembre alle 18:00 davanti alla casa", "12345678", "classi 2, 3 e 4", "ore 18.30–20.00")) {
            assertEquals(PublicText.Findings(email = false, phone = false, iban = false), PublicText.check(text), text)
        }
        assertEquals(PublicText.Findings(email = true, phone = true, iban = false), PublicText.check("nome@example.org oppure 333 1234567"))
    }

    @Test
    fun `t4 c06 a bank account is refused too, as the recommendation of the analysis says, and it is not told as a phone number`() {
        assertEquals(PublicText.Findings(email = false, phone = false, iban = true), PublicText.check("Versa la quota su IT60X0542811101000000123456"))
        assertEquals(PublicText.Findings(email = false, phone = false, iban = true), PublicText.check("IBAN: IT60 X054 2811 1010 0000 0123 456"))
        assertEquals(PublicText.Findings(email = false, phone = false, iban = false), PublicText.check("Il 2 settembre 2027 ci troviamo alle 9 davanti alla casa"))
    }

    @Test
    fun `t4 c06 the error says what was found, the way the design wrote it, and asks to take it away`() {
        assertEquals(
            "Qui c’è un indirizzo email. Toglilo: la pagina la legge chiunque abbia il link.",
            GatheringChecks.info("Scrivi a nome@example.org").error,
        )
        assertEquals(
            "Qui c’è un numero di telefono. Toglilo: la pagina la legge chiunque abbia il link.",
            GatheringChecks.info("Chiama il 333 1234567").error,
        )
        assertEquals(
            "Qui ci sono un indirizzo email e un numero di telefono. Toglili: la pagina la legge chiunque abbia il link.",
            GatheringChecks.whatToBring("nome@example.org e 333 1234567").error,
        )
        assertEquals("Qui c’è un IBAN. Toglilo: la pagina la legge chiunque abbia il link.", GatheringChecks.place("IT60X0542811101000000123456", required = false).error)
        // Title and place are public texts as well.
        assertNotNull(GatheringChecks.title("Convivenza di nome@example.org").error)
        assertNotNull(GatheringChecks.place("Casa, tel. 333 1234567", required = false).error)
        // A text with nothing to take away says nothing.
        assertNull(GatheringChecks.info("Ci si ritrova venerdì alle 18:30 e si finisce domenica dopo pranzo.").error)
    }

    @Test
    fun `t4 c03 the dates are required, real, in order, at most seven days, and new or changed ones are not in the past`() {
        val today = LocalDate.of(2027, 9, 1)

        assertEquals("Scegli il primo giorno.", GatheringChecks.dates("", "2027-09-19", today).startError)
        assertEquals("Scegli l’ultimo giorno.", GatheringChecks.dates("2027-09-17", "", today).endError)
        assertEquals("Scegli una data vera, per esempio 17/09/2027.", GatheringChecks.dates("17/09/2027", "2027-09-19", today).startError)
        assertEquals("Scegli una data vera, per esempio 17/09/2027.", GatheringChecks.dates("2027-09-17", "domani", today).endError)
        assertEquals("L’ultimo giorno non può essere prima del primo.", GatheringChecks.dates("2027-09-19", "2027-09-17", today).endError)
        assertEquals("La convivenza può durare al massimo 7 giorni: controlla le date.", GatheringChecks.dates("2027-09-17", "2027-09-24", today).endError)
        assertNull(GatheringChecks.dates("2027-09-17", "2027-09-23", today).endError)
        assertEquals("Il primo giorno è già passato. Scegli una data da oggi in poi.", GatheringChecks.dates("2027-08-31", "2027-09-02", today).startError)
        assertEquals("L’ultimo giorno è già passato. Scegli una data da oggi in poi.", GatheringChecks.dates("2027-08-30", "2027-08-31", today).endError)
        assertTrue(GatheringChecks.dates("2027-09-01", "2027-09-03", today).valid, "today itself is fine")
    }

    @Test
    fun `t4 c03 a date that is already saved may be in the past when it does not change, a new one may not`() {
        val today = LocalDate.of(2027, 9, 18)
        val saved = LocalDate.of(2027, 9, 17) to LocalDate.of(2027, 9, 19)

        assertTrue(GatheringChecks.dates("2027-09-17", "2027-09-19", today, saved).valid)
        assertEquals("Il primo giorno è già passato. Scegli una data da oggi in poi.", GatheringChecks.dates("2027-09-16", "2027-09-19", today, saved).startError)
        assertTrue(GatheringChecks.dates("2027-09-17", "2027-09-18", today, saved).valid, "anticipating the end during the gathering is allowed")
    }

    @Test
    fun `t4 c03 a year that is wrong but has the right length passes, it is the detail that shows it`() {
        val today = LocalDate.of(2027, 9, 1)

        assertTrue(GatheringChecks.dates("2029-09-17", "2029-09-19", today).valid)
    }
}
