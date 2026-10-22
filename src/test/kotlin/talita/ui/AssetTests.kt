package talita.ui

import jakarta.servlet.RequestDispatcher
import talita.TestcontainersConfiguration
import talita.account.MockSignIn
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.util.DigestUtils
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val staticDir: Path = Path.of("src/main/resources/static")

private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun sri384(bytes: ByteArray) = "sha384-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-384").digest(bytes))

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class AssetTests(@Autowired private val mockMvc: MockMvc, @Autowired tokens: OneTimeTokenService) {

    private val signedInSession = MockSignIn(mockMvc, tokens).session()

    private fun body(url: String): ByteArray = mockMvc.get(url) { session = signedInSession }.andReturn().response.contentAsByteArray

    private fun text(url: String): String = String(body(url), Charsets.UTF_8)

    private fun errorPage(status: Int): String = mockMvc.get("/error") {
        session = signedInSession
        requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
        requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/convivenza")
    }.andReturn().response.contentAsString

    /** Every page of the shell: the home, the sections, the profile, the page not found and the error page. */
    private fun shellPages(): Map<String, String> = mapOf(
        "/" to text("/"),
        "/convivenza" to text("/convivenza"),
        "/iscrizioni" to text("/iscrizioni"),
        "/arrivi" to text("/arrivi"),
        "/profilo" to text("/profilo"),
        "404" to errorPage(404),
        "500" to errorPage(500),
    ) + publicPages()

    /** The pages that anyone can open, seen by someone who is not inside: the access pages and the error pages. */
    private fun publicPages(): Map<String, String> = listOf(
        "/accedi", "/accedi/controlla-la-posta", "/accedi/conferma?token=0b8b3d7e-0000-4000-8000-000000000000", "/accedi/link-scaduto",
    ).associateWith { mockMvc.get(it).andReturn().response.contentAsString } +
        mapOf("404 without access" to errorPageWithoutAccess(404), "500 without access" to errorPageWithoutAccess(500))

    private fun errorPageWithoutAccess(status: Int): String = mockMvc.get("/error") {
        requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
        requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/convivenza")
    }.andReturn().response.contentAsString

    /** URLs of the static files that the page loads: its `<link>` and `<script>` elements. */
    private fun assetUrls(html: String): List<String> =
        Regex("""<(?:link|script)\b[^>]*?\s(?:src|href)="(/[^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()

    private fun cssUrl(html: String): String = assetUrls(html).single { it.endsWith(".css") }

    private fun jsUrl(html: String, name: String): String = assetUrls(html).single { it.startsWith("/js/$name") }

    @Test
    fun `c09 the CSS has the rules of the classes used in the templates and is minified`() {
        val css = text(cssUrl(text("/")))

        // min-h-dvh is written only in the layout template.
        assertTrue(css.contains(".min-h-dvh{"), "the class used in a template has no rule")
        // A class that no template uses is not generated: Tailwind really reads the templates.
        assertFalse(css.contains(".rotate-45"), "the CSS holds rules nobody uses")
        // Minified: the CSS comes as one line (plus the license comments), without indentation.
        assertTrue(css.lines().count { it.isNotBlank() } <= 4, "the CSS is not minified")
        assertFalse(css.contains("\n  "), "the CSS has indentation")
    }

    @Test
    fun `c10 htmx is the pinned 2x release, with the published hash and its license`() {
        val html = text("/")
        val served = body(jsUrl(html, "htmx.min"))
        val source = Files.readString(staticDir.resolve("js/htmx-SOURCE.txt"))

        val publishedHash = Regex("""sha384-[A-Za-z0-9+/]+=*""").find(source)?.value
        assertEquals(publishedHash, sri384(served), "htmx.min.js does not match the hash written in htmx-SOURCE.txt")
        assertTrue(source.contains(sha256(served)), "htmx-SOURCE.txt does not carry the SHA-256 of the file")
        assertTrue(String(served, Charsets.UTF_8).contains("""version:"2.0."""), "htmx is not a 2.0.x release")
        assertTrue(Files.readString(staticDir.resolve("js/htmx-LICENSE.txt")).contains("Zero-Clause BSD"))
    }

    @Test
    fun `c11 the fonts are woff2 files served by the app with font-display swap and their license`() {
        val css = text(cssUrl(text("/")))

        val fontUrls = Regex("""url\(["']?([^)"']+\.woff2)["']?\)""").findAll(css).map { it.groupValues[1] }.toList()
        assertEquals(2, fontUrls.size, "expected the latin and latin-ext files in the CSS: $fontUrls")
        for (url in fontUrls) {
            mockMvc.get(url).andReturn().response.let {
                assertEquals(200, it.status, url)
                assertEquals("font/woff2", it.contentType, url)
            }
        }
        assertEquals(2, Regex("""font-display:\s*swap""").findAll(css).count(), "every @font-face must use swap")

        val license = Files.readString(staticDir.resolve("fonts/LICENSE.txt"))
        assertTrue(license.contains("SIL OPEN FONT LICENSE"), "the font license is missing")
    }

    @Test
    fun `c11 the CSS carries the fingerprint of the fonts, so it changes when a font changes`() {
        // Spring computes the CSS fingerprint before rewriting the font URLs inside it: the line
        // `fonts: ...` in app.css makes the CSS itself change when a font file changes.
        val css = Files.readString(Path.of("src/main/css/app.css"))
        val declared = Regex("""/\*! fonts: (.+?) \*/""").find(css)?.groupValues?.get(1)
        val actual = listOf("inter-latin", "inter-latin-ext").joinToString(" ") {
            "$it " + sha256(Files.readAllBytes(staticDir.resolve("fonts/$it.woff2"))).take(12)
        }

        assertEquals(actual, declared, "update the 'fonts:' line in src/main/css/app.css")
    }

    @Test
    fun `c13 every static file has a fingerprinted URL and a cache of at least one year`() {
        val html = text("/")
        val css = text(cssUrl(html))
        val fontUrls = Regex("""url\(["']?([^)"']+\.woff2)["']?\)""").findAll(css).map { it.groupValues[1] }.toList()
        val urls = (assetUrls(html) + fontUrls).distinct()
        assertTrue(urls.size >= 6, "expected favicon, font, CSS, htmx, app.js and the fonts of the CSS: $urls")

        for (url in urls) {
            // `/js/app-<md5 of the content>.js`: the URL changes when the content changes.
            val fingerprint = Regex("""-([0-9a-f]{32})\.[a-z0-9]+$""").find(url)?.groupValues?.get(1)
            assertTrue(fingerprint != null, "$url has no content fingerprint")

            val original = ClassPathResource("static" + url.replace("-$fingerprint", ""))
            assertEquals(DigestUtils.md5DigestAsHex(original.inputStream), fingerprint, "$url: the fingerprint is not that of the content")

            val response = mockMvc.get(url).andReturn().response
            assertEquals(200, response.status, url)
            val maxAge = Regex("""max-age=(\d+)""").find(response.getHeader("Cache-Control").orEmpty())?.groupValues?.get(1)?.toLong()
            assertTrue(maxAge != null && maxAge >= 365L * 24 * 3600, "$url: Cache-Control is '${response.getHeader("Cache-Control")}'")
        }
    }

    @Test
    fun `c12 pages and CSS point only to the app and have no inline script, on handlers or hx-on`() {
        for ((path, html) in shellPages()) {

            val links = Regex("""\s(?:src|href|action|poster|data|formaction)\s*=\s*["']([^"']*)["']""").findAll(html).map { it.groupValues[1] }
            for (link in links) {
                assertFalse(Regex("""^(?:[a-z][a-z0-9+.-]*:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(link) && !link.startsWith("#"), "$path: external link $link")
            }
            assertFalse(Regex("""<script(?![^>]*\ssrc=)""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$path: inline script")
            assertFalse(Regex("""<[^>]+\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$path: on* attribute")
            assertFalse(Regex("""<[^>]+\shx-on""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$path: hx-on")
            assertFalse(Regex("""<[^>]+\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$path: style attribute")
            assertFalse(Regex("""<style""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$path: style element")
        }

        val css = text(cssUrl(text("/")))
        val cssUrls = Regex("""url\(\s*["']?([^)"']+)["']?\s*\)""").findAll(css).map { it.groupValues[1] }.toList()
        assertTrue(cssUrls.isNotEmpty())
        for (url in cssUrls) {
            assertTrue(url.startsWith("/") && !url.startsWith("//"), "the CSS points outside the app: $url")
        }
        assertFalse(css.contains("@import"), "the CSS imports other files")
    }
}
