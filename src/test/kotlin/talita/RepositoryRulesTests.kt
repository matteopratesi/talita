package talita

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rules about the repository itself, checked on every build: the build script, the settings that
 * let two projects run on the same PC, and the files that must never be tracked in a public
 * repository.
 */
class RepositoryRulesTests {

    private val root: Path = Path.of(".")

    private fun read(path: String): String = Files.readString(root.resolve(path))

    @Test
    fun `t2 c10 the build script has no module framework, no range type binding and no postgres extensions library`() {
        val script = read("build.gradle.kts")

        for (word in listOf("modu" + "lith", "Forced" + "Type", "date" + "range", "jooq-postgres-" + "extensions")) {
            assertTrue(!script.contains(word, ignoreCase = true), "build.gradle.kts mentions $word")
        }
        assertTrue(script.contains("""group = "talita""""))
        assertEquals("""rootProject.name = "talita"""", read("settings.gradle.kts").lines().last { it.isNotBlank() })
        assertTrue(script.contains(""".withPackageName("talita.jooq")"""))
    }

    @Test
    fun `t2 c07 Tailwind is version 4_3_3 with the pinned checksum, cached apart from other projects`() {
        val script = read("build.gradle.kts")

        assertTrue(script.contains("""val tailwindVersion = "4.3.3""""))
        assertTrue(script.contains(""""linux-x64" to "dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a""""))
        assertTrue(script.contains("""caches/talita-tools/tailwindcss/v${'$'}tailwindVersion"""))
    }

    // ---- Two projects on the same PC ------------------------------------------------------------

    @Test
    fun `t2 c12 compose starts Postgres 18 with database, user and password talita on 127_0_0_1 port 5433`() {
        val compose = read("compose.yaml")

        assertTrue(compose.contains("image: postgres:18"))
        for (variable in listOf("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")) {
            assertTrue(compose.contains("$variable: talita"), variable)
        }
        assertTrue(compose.contains(""""127.0.0.1:5433:5432""""))
        assertTrue(!compose.contains("5432:5432"), "the default port would collide with another project")
    }

    // ---- Files that never belong in a public repository -----------------------------------------

    @Test
    fun `t2 c38 the rule finds documents and images outside the places that may hold images`() {
        val tracked = listOf(
            "docs/manleva.pdf", "docs/elenco.XLSX", "notes/lettera.docx", "slides/intro.pptx", "docs/foglio.ods", "docs/testo.odt",
            "docs/design/KOINE-11/ok.png", "src/main/resources/static/favicon.svg", "src/main/resources/static/fonts/inter.woff2",
            "docs/schermata.png", "src/test/resources/foto.jpg", "README.md", ".dockerignore", "docs/design/README.md",
        )

        assertEquals(
            listOf("docs/manleva.pdf", "docs/elenco.XLSX", "notes/lettera.docx", "slides/intro.pptx", "docs/foglio.ods", "docs/testo.odt", "docs/schermata.png", "src/test/resources/foto.jpg"),
            forbiddenTrackedFiles(tracked),
        )
    }

    @Test
    fun `t2 c38 no tracked file is a PDF, an office document or an image outside static and docs design`() {
        val tracked = trackedFiles()
        assumeTrue(tracked != null, "not a git checkout: the tracked files cannot be listed")

        assertTrue(tracked!!.size > 50, "git lists only ${tracked.size} files: is it running from the project directory?")
        assertEquals(emptyList<String>(), forbiddenTrackedFiles(tracked))
    }

    private fun trackedFiles(): List<String>? = try {
        val process = ProcessBuilder("git", "ls-files", "-z").directory(root.toFile()).redirectErrorStream(false).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) null else output.split('\u0000').filter { it.isNotEmpty() }
    } catch (_: java.io.IOException) {
        null
    }
}

private val documentExtensions = setOf(
    "pdf", "doc", "docx", "docm", "dot", "dotx", "dotm", "rtf",
    "xls", "xlsx", "xlsm", "xlsb", "xlt", "xltx", "xltm",
    "ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx",
    "odt", "ods", "odp", "odg", "odf", "odb", "ott", "ots", "otp",
)

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "tif", "tiff", "heic", "heif", "avif", "ico", "svg")

/** Places that may hold images: the static files of the app and the design system. */
private val imageFolders = listOf("src/main/resources/static/", "docs/design/")

/**
 * The tracked files that must not be there: a PDF, a Word, Excel, PowerPoint, OpenDocument file
 * anywhere, and an image outside [imageFolders]. Real documents of a gathering (consent forms,
 * lists, registers) have exactly these formats.
 */
internal fun forbiddenTrackedFiles(tracked: List<String>): List<String> = tracked.filter { file ->
    val extension = Path.of(file).name.substringAfterLast('.', "").lowercase()
    extension in documentExtensions || (extension in imageExtensions && imageFolders.none { file.startsWith(it) })
}
