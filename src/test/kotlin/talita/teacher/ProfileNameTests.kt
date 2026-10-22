package talita.teacher

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** «Il tuo nome» in the profile of a teacher (criterion 47): the rules of the invitation, and nothing for a parent. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfileNameTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
) {

    private val support = MaestriSupport(mockMvc, tokens, dsl)

    private fun names(email: String) =
        dsl.select(TEACHER.FIRST_NAME, TEACHER.LAST_NAME).from(TEACHER).join(ACCOUNT).on(ACCOUNT.ID.eq(TEACHER.ACCOUNT_ID))
            .where(ACCOUNT.EMAIL.eq(email)).fetchOne { it[TEACHER.FIRST_NAME]!! to it[TEACHER.LAST_NAME]!! }

    @Test
    fun `t3 c47 the profile of a teacher has the card Il tuo nome with the two fields filled in, and the one of a parent has none`() {
        val me = Addresses.unique("profilo-maestra")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))

        val html = support.browser(me).page("/profilo")

        assertTrue(html.contains(">Il tuo nome</h2>"))
        val nome = Regex("""<input id="nome"[^>]*>""").find(html)?.value
        val cognome = Regex("""<input id="cognome"[^>]*>""").find(html)?.value
        assertNotNull(nome)
        assertNotNull(cognome)
        assertTrue(nome.contains("""value="Elisa"""") && nome.contains("""autocomplete="given-name"""") && nome.contains("""autocapitalize="words""""), nome)
        assertTrue(cognome.contains("""value="Conti"""") && cognome.contains("""autocomplete="family-name"""") && cognome.contains("""autocapitalize="words""""), cognome)
        for (field in listOf(nome, cognome)) assertFalse(field.contains("required") || field.contains("maxlength") || field.contains("pattern"), field)
        assertTrue(Regex("""<form method="post" action="/profilo/nome"[^>]*novalidate""").containsMatchIn(html))
        assertTrue(html.contains("Salva il nome"))

        val parent = support.browser(Addresses.unique("profilo-genitore")).page("/profilo")
        assertFalse(parent.contains("Il tuo nome") || parent.contains("""id="nome"""") || parent.contains("/profilo/nome"), "a parent has the card")
    }

    @Test
    fun `t3 c47 a teacher corrects the name, with no spaces at the sides, the notice comes once and the initials follow at the next request`() {
        val me = Addresses.unique("profilo-corregge")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val browser = support.browser(me)

        val result = browser.submit("/profilo", "/profilo/nome", mapOf("nome" to "  Niccolò ", "cognome" to " D’Angiò  "))

        assertEquals(303, result.response.status)
        assertEquals("/profilo", result.location())
        assertEquals("Niccolò" to "D’Angiò", names(me))
        val page = browser.page("/profilo")
        assertTrue(page.contains("Nome salvato."))
        assertTrue(page.contains("""role="status""""))
        assertTrue(page.contains("""value="Niccolò"""") && page.contains("""value="D’Angiò""""))
        assertTrue(Regex("""class="grid size-8 [^"]*">ND</span>""").containsMatchIn(page), "the initials did not follow")
        assertFalse(browser.page("/profilo").contains("Nome salvato."), "the notice came back")
        // The list of «Maestri» shows the new name.
        assertTrue(browser.page("/maestri").contains(">Niccolò D’Angiò</span>"))
    }

    @Test
    fun `t3 c47 the rules are those of the invitation, an error sends the form back filled in with the error under the field`() {
        val me = Addresses.unique("profilo-errori")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val browser = support.browser(me)

        val missing = browser.submit("/profilo", "/profilo/nome", mapOf("nome" to "  ", "cognome" to "Rossi"))
        assertEquals(422, missing.response.status)
        assertTrue(missing.html().contains("Scrivi il nome."))
        assertFalse(missing.html().contains("Scrivi il cognome."))
        assertTrue(Regex("""<input id="nome"[^>]*aria-invalid="true"""").containsMatchIn(missing.html()))
        assertTrue(missing.html().contains("""value="Rossi""""))
        assertEquals("Elisa" to "Conti", names(me))

        val both = browser.submit("/profilo", "/profilo/nome", mapOf("nome" to "", "cognome" to ""))
        assertEquals(422, both.response.status)
        assertTrue(both.html().contains("Controlla i 2 campi segnati in rosso."))
        assertTrue(both.html().contains("""role="alert""""))
        assertTrue(both.html().contains("Scrivi il nome.") && both.html().contains("Scrivi il cognome."))

        val tooLong = browser.submit("/profilo", "/profilo/nome", mapOf("nome" to "a".repeat(101), "cognome" to "b".repeat(100)))
        assertEquals(422, tooLong.response.status)
        assertTrue(tooLong.html().contains("Usa al massimo 100 caratteri."))
        assertEquals("Elisa" to "Conti", names(me))

        val exactly = browser.submit("/profilo", "/profilo/nome", mapOf("nome" to "a".repeat(100), "cognome" to "b".repeat(100)))
        assertEquals(303, exactly.response.status)
        assertEquals("a".repeat(100) to "b".repeat(100), names(me))
    }

    @Test
    fun `t3 c47 with htmx the page with errors keeps the address of the profile, and a reload of the address of the form leads to the profile`() {
        val me = Addresses.unique("profilo-indirizzo")
        support.onlyTeachers(Person(me, "Elisa", "Conti"))
        val browser = support.browser(me)

        val withHtmx = browser.submit("/profilo", "/profilo/nome", mapOf("nome" to "", "cognome" to "Rossi")) { header("HX-Request", "true") }
        val without = browser.submit("/profilo", "/profilo/nome", mapOf("nome" to "", "cognome" to "Rossi"))

        assertEquals(422, withHtmx.response.status)
        assertEquals("/profilo", withHtmx.response.getHeader("HX-Replace-Url"))
        assertEquals(422, without.response.status)
        assertNull(without.response.getHeader("HX-Replace-Url"))
        val reload = browser.get("/profilo/nome")
        assertEquals(303, reload.response.status)
        assertEquals("/profilo", reload.location())
    }

    @Test
    fun `t3 c47 a teacher changes only their own name, whatever the request says`() {
        val me = Addresses.unique("profilo-mio")
        val other = Addresses.unique("profilo-altro")
        support.onlyTeachers(Person(me, "Elisa", "Conti"), Person(other, "Paolo", "Ferri"))
        val browser = support.browser(me)

        val result = browser.submit(
            "/profilo", "/profilo/nome",
            mapOf("nome" to "Marta", "cognome" to "Bianchi", "maestro" to support.accountId(other).toString(), "id" to support.accountId(other).toString()),
        )

        assertEquals(303, result.response.status)
        assertEquals("Marta" to "Bianchi", names(me))
        assertEquals("Paolo" to "Ferri", names(other))
        assertNull(names(Addresses.unique("nessuno")))
    }
}
