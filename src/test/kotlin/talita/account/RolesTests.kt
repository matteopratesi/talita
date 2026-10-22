package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The two roles (criteria 33-39): a teacher has four sections, a parent two; the pages of the teachers
 * answer 403 to a parent, as a page and as a message; the role is read at every request.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RolesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
) {

    private val signIn = MockSignIn(mockMvc, tokens, dsl)

    private fun html(path: String, session: MockHttpSession): String = mockMvc.get(path) { this.session = session }.andReturn().response.contentAsString

    private fun status(path: String, session: MockHttpSession): Int = mockMvc.get(path) { this.session = session }.andReturn().response.status

    private fun navigation(html: String): List<String> =
        Regex("""<nav[\s\S]*?</nav>""").find(html)?.value?.let { nav -> Regex("""<span class="max-w-full truncate">([^<]+)</span>""").findAll(nav).map { it.groupValues[1] }.toList() }.orEmpty()

    private fun heading(html: String) = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()

    private fun title(html: String) = Regex("""<title>(.*?)</title>""").find(html)?.groupValues?.get(1)

    // ---- Navigation, home and registrations by role (criteria 33 and 34) ----------------------------------------

    @Test
    fun `t3 c33 a teacher has Home, Convivenza, Iscrizioni and Arrivi, and opens the pages of the sections`() {
        val session = signIn.session(teacher = true)

        assertEquals(listOf("Home", "Convivenza", "Iscrizioni", "Arrivi"), navigation(html("/", session)))
        for (path in listOf("/", "/convivenza", "/iscrizioni", "/arrivi")) {
            assertEquals(200, status(path, session), path)
            assertEquals(listOf("Home", "Convivenza", "Iscrizioni", "Arrivi"), navigation(html(path, session)), path)
        }
        assertEquals("Qui troverai i bambini iscritti e potrai inserire le iscrizioni arrivate sul modulo cartaceo.", Regex("""<p class="mt-2 max-w-sm text-ink-muted">(.*?)</p>""").find(html("/iscrizioni", session))?.groupValues?.get(1))
        // The home of the teacher: the three sections of the list, the welcome of always.
        val home = html("/", session)
        assertTrue(home.contains("Talità raccoglie le iscrizioni alla convivenza dei bambini e aiuta i maestri a organizzarla."))
        assertTrue(home.contains("<strong>Talità è in costruzione.</strong> Le sezioni arriveranno una alla volta."))
        assertTrue(home.contains(">Sezioni</h2>"))
        assertEquals(listOf("/convivenza", "/iscrizioni", "/arrivi"), Regex("""<a href="(/[a-z]+)" class="flex min-h-16""").findAll(home).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `t3 c34 a parent has only Home and Iscrizioni, with the texts for a parent`() {
        val session = signIn.session()

        val home = html("/", session)
        assertEquals(listOf("Home", "Iscrizioni"), navigation(home))
        assertEquals("Talità", title(home))
        assertEquals("Ti diamo il benvenuto", heading(home))
        assertTrue(home.contains("Qui iscrivi i tuoi figli alla convivenza e controlli le loro iscrizioni."))
        assertTrue(home.contains("<strong>Talità è in costruzione.</strong> Le iscrizioni arriveranno presto."))
        assertTrue(home.contains(">Cosa trovi qui</h2>"))
        // The list has only the registrations, with the line for a parent.
        assertEquals(listOf("/iscrizioni"), Regex("""<a href="(/[a-z]+)" class="flex min-h-16""").findAll(home).map { it.groupValues[1] }.toList())
        assertTrue(home.contains("Le iscrizioni dei tuoi figli."))
        assertFalse(home.contains("Le date, i maestri") || home.contains("Chi è arrivato"))

        val registrations = html("/iscrizioni", session)
        assertEquals("Iscrizioni · Talità", title(registrations))
        assertEquals("Iscrizioni", heading(registrations))
        assertTrue(registrations.contains("Qui troverai le iscrizioni dei tuoi figli."))
        assertFalse(registrations.contains("modulo cartaceo"))
        assertEquals(listOf("Home", "Iscrizioni"), navigation(registrations))
        assertTrue(Regex("""<a href="/iscrizioni" aria-current="page"""").containsMatchIn(registrations))
    }

    // ---- 403 (criterion 35) ---------------------------------------------------------------------------------------------

    @Test
    fun `t3 c35 a parent who opens a page of the teachers gets 403 with the page Non puoi vedere questa pagina, in the shell, with no current entry`() {
        val session = signIn.session()

        for (path in listOf("/convivenza", "/arrivi", "/maestri", "/convivenza/qualcosa", "/arrivi/1/stampa", "/maestri/x")) {
            val result = mockMvc.get(path) { this.session = session }.andReturn()

            // The status is sent by the application as an error, which the server answers with the page of the error
            // (MockMvc does not run that dispatch: the page itself is tried below, and in the e2e tests).
            assertEquals(403, result.response.status, path)
        }
    }

    @Test
    fun `t3 c35 the page of the 403 has its texts, the lock, the way out and the navigation of a parent`() {
        val session = signIn.session()

        val result = mockMvc.get("/error") {
            this.session = session
            requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 403)
            requestAttr(jakarta.servlet.RequestDispatcher.ERROR_REQUEST_URI, "/convivenza")
        }.andReturn()
        val html = result.response.contentAsString

        assertEquals(403, result.response.status)
        assertEquals("Non puoi vedere questa pagina · Talità", title(html))
        assertEquals("Non puoi vedere questa pagina", heading(html))
        assertTrue(html.contains("La pagina esiste, ma il tuo accesso non comprende questi dati. Se pensi di doverla vedere, chiedi a chi organizza la convivenza."))
        assertTrue(Regex("""<a href="/"[^>]*>Vai alla home</a>""").containsMatchIn(html))
        assertTrue(html.contains("""<rect width="18" height="11" x="3" y="11" rx="2" ry="2"/>"""), "the icon is not the lock")
        assertEquals(listOf("Home", "Iscrizioni"), navigation(html))
        // No entry of the navigation is the current one.
        assertFalse(Regex("""<nav[\s\S]*?</nav>""").find(html)!!.value.contains("aria-current"))
    }

    @Test
    fun `t3 c35 the message Accesso negato is in every page, as a model with no Riprova`() {
        val html = html("/", signIn.session())

        val model = Regex("""<template id="messaggio-accesso-negato">[\s\S]*?</template>""").find(html)?.value
        assertTrue(model != null, "no model of the message in the page")
        assertTrue(model.contains("Accesso negato"))
        assertTrue(model.contains("Non hai il permesso di farlo. Se pensi di doverlo fare, chiedi a chi organizza la convivenza."))
        assertFalse(model.contains("Riprova"), "to repeat a refused request changes nothing")
        assertFalse(model.contains("data-azione=\"riprova\""))
        assertTrue(model.contains("""aria-label="Chiudi""""))
    }

    @Test
    fun `t3 c35 a request that is not a page, made by a parent, gets 403 too and changes nothing`() {
        val session = signIn.session()

        for (path in listOf("/maestri", "/maestri/invita", "/convivenza/qualcosa")) {
            val result = mockMvc.post(path) {
                this.session = session
                with(csrf())
                header("HX-Request", "true")
                param("email", Addresses.unique("a-mano"))
            }.andReturn()

            assertEquals(403, result.response.status, path)
        }
        assertEquals(403, mockMvc.get("/convivenza") { this.session = session; header("HX-Request", "true") }.andReturn().response.status)
    }

    // ---- The same person, the role read at every request (criteria 36 and 37) ---------------------------------------------

    @Test
    fun `t3 c36 who is a teacher and a parent has one account, the navigation of the teacher, and it is that account that is the current one`() {
        val address = Addresses.unique("doppio-ruolo")
        val session = signIn.session(address, teacher = true)

        assertEquals(1, dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.eq(address)), "the teacher made a second account")
        assertEquals(listOf("Home", "Convivenza", "Iscrizioni", "Arrivi"), navigation(html("/", session)))
        // The registrations are those of the same account that is the teacher: «CurrentAccount» is one.
        val profile = html("/profilo", session)
        assertTrue(profile.contains(address))
        assertTrue(Regex("""<dt class="text-sm text-ink-muted">Ruolo</dt>\s*<dd class="font-medium">Maestro</dd>""").containsMatchIn(profile))
        assertEquals(1, dsl.fetchCount(ACCOUNT, ACCOUNT.EMAIL.eq(address)))
    }

    @Test
    fun `t3 c36 a parent has no row Ruolo in the profile and the initial of the teacher is of name and surname`() {
        val parent = html("/profilo", signIn.session())
        assertFalse(parent.contains(">Ruolo<"))

        val teacher = html("/profilo", signIn.session(Addresses.unique("iniziali"), teacher = true))
        // The default name of the helper is Elisa Conti.
        assertTrue(Regex("""<span aria-hidden="true" class="grid size-8 [^"]*">EC</span>""").containsMatchIn(teacher), teacher)
    }

    @Test
    fun `t3 c37 a role that changes while the person is inside is in force at the next request, with no new login`() {
        val address = Addresses.unique("ruolo-cambia")
        val session = signIn.session(address)
        assertEquals(403, status("/convivenza", session))
        assertEquals(listOf("Home", "Iscrizioni"), navigation(html("/", session)))

        // An invitation accepted (here: the teacher row): the next touch is already the one of a teacher.
        TestAccounts.makeTeacher(dsl, address)
        assertEquals(200, status("/convivenza", session))
        assertEquals(listOf("Home", "Convivenza", "Iscrizioni", "Arrivi"), navigation(html("/", session)))

        // A teacher who is removed: back to the view of a parent, the same session.
        TestAccounts.removeTeacher(dsl, address)
        assertEquals(403, status("/convivenza", session))
        assertEquals(listOf("Home", "Iscrizioni"), navigation(html("/", session)))
        assertEquals(200, status("/profilo", session), "the account is still there")
    }

    @Test
    fun `t3 c37 an account that no longer exists is not inside any more`() {
        val address = Addresses.unique("cancellato")
        val session = signIn.session(address)
        assertEquals(200, status("/", session))

        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.EMAIL.eq(address)).execute()

        val result: MvcResult = mockMvc.get("/") { this.session = session }.andReturn()
        assertEquals(302, result.response.status)
        assertEquals("/accedi", result.response.getHeader("Location"))
    }

    // ---- One way to know who it is, and one rule for the addresses of the teachers (criterion 38) ----------------------------------

    @Test
    fun `t3 c38 there is one way to know the current account, written in the documentation of the code, and the addresses of the teachers are in one rule`() {
        val accounts = Files.readString(Path.of("src/main/kotlin/talita/account/CurrentAccounts.kt"))
        assertTrue(accounts.contains("Who is making the request: the one way to know it."))
        assertTrue(accounts.contains("[CurrentAccount]"))
        val model = Files.readString(Path.of("src/main/kotlin/talita/account/Accounts.kt"))
        for (word in listOf("@property id", "@property email", "@property teacher")) assertTrue(model.contains(word), word)

        // No other place reads the security context or the session for the account.
        val readers = Files.walk(Path.of("src/main/kotlin")).use { stream ->
            stream.filter { Files.isRegularFile(it) }.filter {
                val text = Files.readString(it)
                text.contains("SecurityContextHolder.getContext()") || text.contains("getAttribute(\"SPRING_SECURITY_CONTEXT\")")
            }.map { it.fileName.toString() }.toList().sorted()
        }
        assertEquals(listOf("AccessHandlers.kt", "CurrentAccountFilter.kt", "CurrentAccounts.kt"), readers)
        // The addresses of the teachers: one list, read by the security configuration.
        assertEquals(listOf("/convivenza/**", "/arrivi/**", "/maestri/**"), TeacherPaths.patterns)
        assertTrue(Files.readString(Path.of("src/main/kotlin/talita/account/SecurityConfiguration.kt")).contains("TeacherPaths.patterns.forEach"))
    }

    @Test
    fun `t3 c38 a parent is tried on every address of the teachers, with a page and with a form`() {
        val session = signIn.session()
        val concrete = listOf("/convivenza", "/convivenza/x", "/convivenza/x/y", "/arrivi", "/arrivi/x", "/maestri", "/maestri/x", "/maestri/x/y")
        // Every pattern of the rule is covered by an address here, and every address here by a pattern.
        assertEquals(TeacherPaths.patterns.toSet(), concrete.map { "/" + it.removePrefix("/").substringBefore("/") + "/**" }.toSet())

        for (path in concrete) {
            assertTrue(TeacherPaths.matches(path), path)
            assertEquals(403, mockMvc.get(path) { this.session = session }.andReturn().response.status, "GET $path")
            assertEquals(403, mockMvc.post(path) { this.session = session; with(csrf()) }.andReturn().response.status, "POST $path")
        }
        for (path in listOf("/", "/iscrizioni", "/iscrizioni/x", "/profilo", "/invito")) {
            assertFalse(TeacherPaths.matches(path), path)
        }
    }

    @Test
    fun `t3 c38 and the same addresses open for a teacher, which is never told 403`() {
        val session = signIn.session(teacher = true)

        for (path in listOf("/convivenza", "/arrivi")) {
            assertEquals(200, status(path, session), path)
        }
        // An address under the teachers' ones that nothing serves yet is a page that does not exist, not a refusal.
        assertNotEquals(403, status("/convivenza/non-esiste", session))
    }
}
