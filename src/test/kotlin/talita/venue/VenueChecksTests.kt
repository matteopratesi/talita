package talita.venue

import talita.gathering.GatheringChecks
import talita.gathering.PublicText
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The checks of the form of a venue (TALITA-20, criteria 2 and 3) that need no database and no server: the lengths, the line, and the words of the public texts. The check of a name of a person is
 * the one that needs the database (`PublicNames`): here only that it is asked for, in the same way as for the other public texts. Names and places are synthetic.
 */
class VenueChecksTests {

    @Test
    fun `t20 c02 the name has from 1 to 60 characters and the error says how many were written`() {
        assertEquals(VenueChecks.NAME_MISSING, VenueChecks.name("").error)
        assertEquals(VenueChecks.NAME_MISSING, VenueChecks.name("   ").error)
        assertEquals("Scrivi il nome della struttura.", VenueChecks.NAME_MISSING)
        assertNull(VenueChecks.name("C").error)
        assertNull(VenueChecks.name("a".repeat(60)).error)
        assertEquals("Il nome può avere al massimo 60 caratteri: ne hai scritti 61.", VenueChecks.name("a".repeat(61)).error)
        assertEquals("Il nome può avere al massimo 60 caratteri: ne hai scritti 71.", VenueChecks.name("a".repeat(71)).error)
        // The spaces at the sides are not counted, and an emoji is one character, as the database counts them.
        assertNull(VenueChecks.name("  " + "a".repeat(60) + "  ").error)
        assertNull(VenueChecks.name("😀".repeat(60)).error)
        assertEquals("Il nome può avere al massimo 60 caratteri: ne hai scritti 61.", VenueChecks.name("😀".repeat(61)).error)
    }

    @Test
    fun `t20 c02 the place has from 1 to 200 characters and the error says how many were written`() {
        assertEquals(VenueChecks.PLACE_MISSING, VenueChecks.place("").error)
        assertEquals(VenueChecks.PLACE_MISSING, VenueChecks.place("  \n ").error)
        assertEquals("Scrivi il luogo: la pagina per i genitori lo mostra.", VenueChecks.PLACE_MISSING)
        assertNull(VenueChecks.place("a".repeat(200)).error)
        assertEquals("Il luogo può avere al massimo 200 caratteri: ne hai scritti 201.", VenueChecks.place("a".repeat(201)).error)
        assertEquals("Il luogo può avere al massimo 200 caratteri: ne hai scritti 213.", VenueChecks.place("a".repeat(213)).error)
    }

    @Test
    fun `t20 c02 name and place are one line, the spaces at the sides go and a line break is a space`() {
        assertEquals("Casa del bosco", VenueChecks.name("  Casa del bosco  ").value)
        assertEquals("Casa per ritiri del bosco, Località Esempio", VenueChecks.place("Casa per ritiri\ndel bosco,\r\nLocalità Esempio").value)
        assertEquals("Casa del bosco", VenueChecks.name("Casa\tdel\u0007 bosco".replace("\t", " ").replace("\u0007", "")).value)
        assertFalse(VenueChecks.place("a\nb").value.contains('\n'))
    }

    @Test
    fun `t20 c03 name and place refuse an email, a phone number and an IBAN with the sentences of the public texts, the same for both`() {
        val email = "Qui c’è un indirizzo email. Toglilo: la pagina la legge chiunque abbia il link."
        val phone = "Qui c’è un numero di telefono. Toglilo: la pagina la legge chiunque abbia il link."
        val iban = "Qui c’è un IBAN. Toglilo: la pagina la legge chiunque abbia il link."

        for (check in listOf(VenueChecks::name, VenueChecks::place)) {
            assertEquals(email, check("Casa di nome@example.org").error)
            assertEquals(phone, check("Casa, tel. 333 1234567").error)
            assertEquals(iban, check("Casa IT60X0542811101000000123456").error)
            assertEquals(
                "Qui ci sono un indirizzo email e un numero di telefono. Toglili: la pagina la legge chiunque abbia il link.",
                check("nome@example.org e 333 1234567").error,
            )
            assertNull(check("Casa del bosco, dalle 9.30 alle 18:00, 60 €").error, "a time and an amount are not a phone")
        }
        // The words are the ones of PublicText, and the help says where each text appears and the rule.
        assertTrue(PublicText.NAME_HELP.endsWith(PublicText.RULE))
        assertTrue(PublicText.PLACE_HELP.endsWith(PublicText.RULE))
        assertEquals("Il nome della casa, per esempio «Casa del bosco». Compare nella pagina per i genitori e nell’anteprima del link. ${PublicText.RULE}", PublicText.NAME_HELP)
        assertEquals("Il nome della casa e dove si trova. Compare nella pagina per i genitori, nell’anteprima del link e nel testo dell’affidamento. ${PublicText.RULE}", PublicText.PLACE_HELP)
    }

    @Test
    fun `t20 c03 the check of the names is asked for a text that is not empty and not too long, as for the other public texts, and gives one error`() {
        val name = VenueChecks.name("Casa di Luca Rossi")
        val place = VenueChecks.place("Casa del bosco, Luca Rossi 333 123 4567")
        val empty = VenueChecks.name("  ")
        val tooLong = VenueChecks.place("Luca Rossi " + "x".repeat(200))

        assertTrue(name.askForName && name.valid, "nothing to take away yet, and the name is still to be asked")
        assertTrue(place.askForName)
        assertEquals(listOf(false, false), listOf(empty.askForName, tooLong.askForName), "an empty or too long text is not asked about")

        val answered = GatheringChecks.withNames(listOf(name, empty, place, tooLong), listOf(true, true))

        assertEquals("Qui ci sono nome e cognome di una persona. Toglili: la pagina la legge chiunque abbia il link.", answered[0].error)
        assertEquals(VenueChecks.NAME_MISSING, answered[1].error)
        assertEquals("Qui ci sono nome e cognome di una persona e un numero di telefono. Toglili: la pagina la legge chiunque abbia il link.", answered[2].error)
        assertNotNull(answered[3].error)
        assertTrue(answered[3].error!!.startsWith("Il luogo può avere al massimo 200 caratteri"))
    }
}
