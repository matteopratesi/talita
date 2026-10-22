package talita.ui

import gg.jte.TemplateEngine
import gg.jte.output.StringOutput
import gg.jte.springframework.boot.autoconfigure.JteProperties
import talita.TestcontainersConfiguration
import talita.account.MockSignIn
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val templatesDir: Path = Path.of("src/main/jte")

/** Every template file as the name the engine knows it by (`pages/home.jte`). */
internal fun templateNames(): List<String> = Files.walk(templatesDir).use { files ->
    files.filter { it.extension == "jte" }
        .map { templatesDir.relativize(it).joinToString("/") }
        .sorted()
        .toList()
}

/** Name of the engine's template mode (`Precompiled` or `OnDemand`), which has no public getter. */
internal fun TemplateEngine.templateMode(): String {
    val field = TemplateEngine::class.java.getDeclaredField("templateMode")
    field.isAccessible = true
    return field.get(this).toString()
}

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class TemplateEngineTests(
    @Autowired private val engine: TemplateEngine,
    @Autowired private val jte: JteProperties,
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val assets: Assets,
    @Autowired tokens: OneTimeTokenService,
) {

    private val signedInSession = MockSignIn(mockMvc, tokens).session()

    @Test
    fun `c01 every template has its compiled class on the classpath`() {
        val names = templateNames()
        assertTrue(names.isNotEmpty(), "no template found in $templatesDir")

        for (name in names) {
            val dir = name.substringBeforeLast('/', "").replace('/', '.')
            val file = name.substringAfterLast('/').removeSuffix(".jte")
            val className = listOf("gg.jte.generated.precompiled", dir, "Jte${file}Generated")
                .filter { it.isNotEmpty() }
                .joinToString(".")

            assertNotNull(runCatching { Class.forName(className) }.getOrNull(), "$name: class $className is missing")
            assertTrue(engine.hasTemplate(name), "$name: the engine does not find the compiled template")
        }
    }

    @Test
    fun `c04 without profiles JTE uses the precompiled templates and development mode is off`() {
        assertTrue(jte.isUsePrecompiledTemplates)
        assertFalse(jte.isDevelopmentMode)
        assertEquals("Precompiled", engine.templateMode())
    }

    @Test
    fun `c05 model values are escaped`() {
        val navigation = listOf(NavItem(label = "<u>n</u>", href = "/x", icon = Icon.HOUSE, current = false))
        val output = StringOutput()
        engine.render(
            "pages/placeholder.jte",
            mapOf(
                "shell" to Shell(title = "<b>x</b><script>", assets = assets, navigation = navigation, profile = ProfileLink(initials = "<s>", current = false)),
                "page" to PlaceholderPage(title = "<i>y</i>", text = "<em>z</em>"),
            ),
            output,
        )

        val html = output.toString()
        for ((markup, escaped) in mapOf(
            "<b>x</b><script>" to "&lt;b&gt;x&lt;/b&gt;&lt;script&gt;",
            "<u>n</u>" to "&lt;u&gt;n&lt;/u&gt;",
            "<i>y</i>" to "&lt;i&gt;y&lt;/i&gt;",
            "<em>z</em>" to "&lt;em&gt;z&lt;/em&gt;",
            "<s>" to "&lt;s&gt;",
        )) {
            assertTrue(html.contains(escaped), "$markup is not escaped:\n$html")
            assertFalse(html.contains(markup), "$markup arrived as markup")
        }
    }

    @Test
    fun `c05 no template uses unsafe output`() {
        for (name in templateNames()) {
            assertFalse(templatesDir.resolve(name).readText().contains("\$unsafe"), "$name uses \$unsafe")
        }
    }

    @Test
    fun `c06 the page is UTF-8 and accented text arrives intact`() {
        mockMvc.get("/") { session = signedInSession }.andExpect {
            status { isOk() }
            content { contentType("text/html;charset=UTF-8") }
            content { string(containsString("<title>Talità</title>")) }
        }
    }
}

/** The development mode is switched on only by the profile that bootRun uses. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ActiveProfiles("dev")
class TemplateEngineDevProfileTests(
    @Autowired private val engine: TemplateEngine,
    @Autowired private val jte: JteProperties,
    @Autowired private val environment: Environment,
) {

    @Test
    fun `c04 the dev profile switches on development mode`() {
        assertTrue(jte.isDevelopmentMode)
        assertFalse(jte.isUsePrecompiledTemplates)
        assertEquals("OnDemand", engine.templateMode())
    }

    @Test
    fun `t2 c12 the dev profile runs the app on port 8081`() {
        assertEquals("8081", environment.getProperty("server.port"))
    }
}
