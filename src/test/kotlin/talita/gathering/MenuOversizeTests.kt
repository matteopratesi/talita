package talita.gathering

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest
import org.springframework.security.authentication.ott.OneTimeTokenService
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.TestAccounts
import talita.jooq.enums.GatheringStatus
import talita.jooq.tables.references.GATHERING_MENU
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The files that are past the limit of the container (TALITA-12, criterion 4), with a real server and a real HTTP client, not MockMvc: the limit is Tomcat's, and
 * MockMvc has no Tomcat. A form with a file of 25 MB must get an answer (a 303 to the page of the menu, which says the file is too big under the field), never the
 * home, never the 403 and never a connection that is closed on the browser; and the same form from someone who is not a teacher must not change a thing.
 *
 * The client is the one of the JDK, with a cookie jar and no redirect followed: it enters with the link (the real way in), asks for the page of the form for the
 * code against forgery, and sends the form by hand as a browser does (`multipart/form-data`, the code first and then the file).
 */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MenuOversizeTests(
    @Autowired private val environment: Environment,
    @Autowired private val dsl: DSLContext,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val clock: Clock,
) {

    private val base: String get() = "http://localhost:" + environment.getRequiredProperty("local.server.port")

    private fun client(): HttpClient =
        HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).cookieHandler(CookieManager()).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()

    private fun HttpClient.get(path: String): HttpResponse<String> =
        send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun csrfIn(html: String): String = checkNotNull(Regex("""name="_csrf" value="([^"]+)"""").find(html)?.groupValues?.get(1)) { "no code against forgery in the page" }

    /** A person who is inside, as one enters: the link, the page of the link, the tap on «Entra». */
    private fun enter(teacher: Boolean): HttpClient {
        val email = Addresses.unique(if (teacher) "grosso-maestro" else "grosso-genitore")
        if (teacher) TestAccounts.makeTeacher(dsl, email, "Marta", "Bianchi")
        val token = tokens.generate(GenerateOneTimeTokenRequest(email, Duration.ofMinutes(15))).tokenValue
        val client = client()
        val page = client.get("/accedi/conferma?token=$token")
        val body = "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&_csrf=" + URLEncoder.encode(csrfIn(page.body()), StandardCharsets.UTF_8)
        val tap = client.send(
            HttpRequest.newBuilder(URI.create("$base/accedi/conferma")).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(tap.statusCode() == 303 && tap.headers().firstValue("Location").orElse("") == "/") { "the person did not get in: ${tap.statusCode()}" }
        return client
    }

    /** The form of the menu, as a browser sends it: the code, then the file, with the boundary and the length. [size] is the size of the file. */
    private fun HttpClient.sendFile(path: String, csrf: String?, name: String, size: Int, content: ByteArray = MenuSamples.pdfOf(size)): HttpResponse<String> {
        val boundary = "----TalitaBoundary7d9f3c"
        val head = buildString {
            if (csrf != null) append("--$boundary\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n$csrf\r\n")
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/pdf\r\n\r\n")
        }
        val publisher = HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofString(head), HttpRequest.BodyPublishers.ofByteArray(content), HttpRequest.BodyPublishers.ofString("\r\n--$boundary--\r\n"),
        )
        return send(
            HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120)).header("Content-Type", "multipart/form-data; boundary=$boundary").POST(publisher).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    private fun gathering(): Long {
        val support = GatheringSupport(dsl, clock)
        support.reset()
        return support.make(title = "Convivenza di settembre 2027", status = GatheringStatus.draft)
    }

    private fun stored(id: Long) = dsl.select(GATHERING_MENU.FILE_NAME, GATHERING_MENU.CONTENT).from(GATHERING_MENU).where(GATHERING_MENU.GATHERING_ID.eq(id)).fetchOne()

    private fun HttpResponse<String>.location(): String? = headers().firstValue("Location").orElse(null)

    @Test
    fun `t12 c04 a file of 25 MB gets an answer that leads to the page of the menu, which says it is too big under the field, never the home and never the 403`() {
        val id = gathering()
        val me = enter(teacher = true)
        val before = MenuSamples.pdfOf(2000)
        // A menu is there already: the refused upload leaves it as it is.
        me.sendFile("/convivenza/$id/menu", csrfIn(me.get("/convivenza/$id/menu").body()), "quello-di-prima.pdf", 2000, before)
        assertEquals("quello-di-prima.pdf", stored(id)!!.get(GATHERING_MENU.FILE_NAME))

        val answer = me.sendFile("/convivenza/$id/menu", csrfIn(me.get("/convivenza/$id/menu").body()), "grande.pdf", 25 * 1024 * 1024)

        assertEquals(303, answer.statusCode(), "the answer of the server, not an interrupted connection")
        assertEquals("/convivenza/$id/menu", answer.location(), "to the page of the menu, not to the home")
        val page = me.get("/convivenza/$id/menu")
        assertEquals(200, page.statusCode())
        assertEquals("Il file è troppo grande: al massimo 10 MB.", errorOf(page.body(), "menu-file"))
        assertTrue(Regex("""<input id="menu-file"[^>]*aria-invalid="true"[^>]*>""").containsMatchIn(page.body()) || Regex("""<input id="menu-file"[^>]*autofocus[^>]*>""").containsMatchIn(page.body()))
        assertFalse(page.body().contains("Controlla i"), "one error under the field, no notice above")
        // Once: the next time the page is the page.
        assertEquals(null, errorOf(me.get("/convivenza/$id/menu").body(), "menu-file"))
        assertTrue(before.contentEquals(stored(id)!!.get(GATHERING_MENU.CONTENT)), "the menu that was there stays")
    }

    @Test
    fun `t12 c11 a file past the limit of the container is in the log with its size and the address, never its name`(output: CapturedOutput) {
        val id = gathering()
        val me = enter(teacher = true)

        me.sendFile("/convivenza/$id/menu", csrfIn(me.get("/convivenza/$id/menu").body()), "Zuppa-Quagliotti-Segreta.pdf", 22 * 1024 * 1024)

        val log = output.all
        assertTrue(Regex("""Upload refused: 2\d{7} bytes is over the limit of the request, on /convivenza/$id/menu\.""").containsMatchIn(log), log.lines().filter { it.contains("Upload") }.joinToString("\n"))
        for (secret in listOf("Zuppa-Quagliotti-Segreta", "Quagliotti", "grande.pdf")) assertFalse(log.contains(secret), "the log has '$secret'")
    }

    @Test
    fun `t12 c04 the limit of the container is the size of the whole form, so a file of 20 MB is past it and one of 15 MB is the application's`() {
        val id = gathering()
        val me = enter(teacher = true)
        fun send(size: Int): HttpResponse<String> = me.sendFile("/convivenza/$id/menu", csrfIn(me.get("/convivenza/$id/menu").body()), "grande.pdf", size)

        // 20 MB of file and the rest of the form is more than 20 MB: the container does not read it.
        val container = send(20 * 1024 * 1024)
        assertEquals(303, container.statusCode())
        assertEquals("/convivenza/$id/menu", container.location())

        // 15 and 10 MB and a byte are read: the application says it, with a 422 and the page.
        for (size in listOf(15 * 1024 * 1024, MenuFile.MAX_BYTES + 1)) {
            val app = send(size)
            assertEquals(422, app.statusCode(), "$size")
            assertEquals("Il file è troppo grande: al massimo 10 MB.", errorOf(app.body(), "menu-file"))
        }
        assertEquals(null, stored(id), "nothing was saved")

        // 10 MB exactly goes in, with the same real server and the same form.
        val exact = send(MenuFile.MAX_BYTES)
        assertEquals(303, exact.statusCode())
        assertEquals("/convivenza/$id", exact.location())
        assertEquals(MenuFile.MAX_BYTES, stored(id)!!.get(GATHERING_MENU.CONTENT)!!.size)
    }

    @Test
    fun `t12 c04 a file much bigger than that is still answered, up to what the container reads and throws away`() {
        val id = gathering()
        val me = enter(teacher = true)

        val answer = me.sendFile("/convivenza/$id/menu", csrfIn(me.get("/convivenza/$id/menu").body()), "enorme.pdf", 60 * 1024 * 1024)

        assertEquals(303, answer.statusCode())
        assertEquals("/convivenza/$id/menu", answer.location())
        assertEquals(null, stored(id))
    }

    @Test
    fun `t12 c04 the limit is read before the permissions, so someone who is not a teacher is told the same, and the next page is the 403 and changes nothing`() {
        val id = gathering()
        val parent = enter(teacher = false)
        val code = csrfIn(parent.get("/profilo").body())

        // Too big: the body is read first, so the answer is the same 303; the page it leads to is the 403.
        val big = parent.sendFile("/convivenza/$id/menu", code, "grande.pdf", 25 * 1024 * 1024)
        assertEquals(303, big.statusCode())
        assertEquals(403, parent.get("/convivenza/$id/menu").statusCode())
        // A file that fits: the 403 straight away.
        val small = parent.sendFile("/convivenza/$id/menu", code, "menu.pdf", 5000)
        assertEquals(403, small.statusCode())
        assertEquals(null, stored(id), "nothing was saved")
    }

    @Test
    fun `t12 c04 whoever has no access is led to the page of the form, which sends them to the access page, with a file that is too big as with any other`() {
        val id = gathering()
        val nobody = client()

        val big = nobody.sendFile("/convivenza/$id/menu", null, "grande.pdf", 25 * 1024 * 1024)

        // No session is made for them and nothing is said of the file: they go where anyone without access goes.
        assertEquals(303, big.statusCode())
        assertEquals("/convivenza/$id/menu", big.location())
        val next = nobody.get("/convivenza/$id/menu")
        assertEquals(302, next.statusCode())
        assertEquals("/accedi", next.headers().firstValue("Location").orElse(null)?.removePrefix(base))
        assertEquals(null, stored(id))
    }
}
