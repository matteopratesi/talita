package talita.account

import org.junit.jupiter.api.Test
import talita.Addresses
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What is, and what is not, an address (criteria 9 and 11). */
class EmailAddressTests {

    /** The addresses of these tests are written in pieces: a test (`t2 c37`) allows only one in tracked files. */
    private fun at(local: String, domain: String) = local + "@" + domain

    private val domain = "example.org"

    @Test
    fun `t3 c09 empty, malformed, with spaces inside, with accents or too long is not an address`() {
        for (raw in listOf(
            null, "", "   ", "nome", at("nome", ""), at("", domain), at("nome", "example"), at("nome@", domain),
            at("nome cognome", domain), at("no me", domain), at("città", domain), at("nome", "exämple.org"), at("nome", ".org"),
            at("nome", "example..org"), at("nome..cognome", domain), at(".nome", domain), at("nome.", domain), at("nome", "-example.org"),
            at("nome", domain) + "\nBcc: " + at("altro", domain), at("nome\r\n", domain),
            at("a".repeat(250), domain), at("a".repeat(65), domain),
        )) {
            assertNull(EmailAddress.parse(raw), "should not be an address: '$raw'")
        }
    }

    @Test
    fun `t3 c11 an address is written in lowercase and without the spaces at the sides`() {
        val typed = "  " + at("Nome.Cognome", "Example.ORG") + "  "

        val address = EmailAddress.parse(typed)

        assertNotNull(address)
        assertEquals(Addresses.of("nome.cognome"), address.value)
        assertEquals(address, EmailAddress.parse(Addresses.of("nome.cognome")))
    }

    @Test
    fun `t3 c09 the usual addresses are accepted, with plus, dots, dashes and numbers`() {
        for (raw in listOf(Addresses.of("nome"), Addresses.of("nome.cognome"), Addresses.of("nome+convivenza"), Addresses.of("nome-cognome_1"), Addresses.of("n"))) {
            assertNotNull(EmailAddress.parse(raw), raw)
        }
        assertNotNull(EmailAddress.parse(at("nome", "sotto.dominio." + domain)))
        assertNotNull(EmailAddress.parse(at("a".repeat(64), "b".repeat(63) + "." + domain)))
    }

    @Test
    fun `an address never shows itself in a log by chance`() {
        assertEquals("EmailAddress", EmailAddress.parse(Addresses.of("nome")).toString())
    }
}
