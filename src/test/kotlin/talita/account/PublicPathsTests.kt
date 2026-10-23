package talita.account

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The list of the addresses that anyone can open (criterion 7): one list, and a test that watches it. */
class PublicPathsTests {

    @Test
    fun `t3 c07 the public addresses are the access pages, the health, the error page and the static files, and nothing else`() {
        // A ticket that adds a public page changes this list and writes its own test (TALITA-4: /info/{codice}, in InfoPageTests).
        assertEquals(listOf("/accedi/**", "/actuator/health", "/error", "/info/*"), PublicPaths.pages)
        assertEquals(listOf("/css/**", "/js/**", "/fonts/**", "/icons/**", "/favicon.svg", "/favicon-*.svg"), PublicPaths.staticFiles)
        assertEquals(PublicPaths.pages + PublicPaths.staticFiles, PublicPaths.all)
    }

    @Test
    fun `t3 c07 an address is public only if the list says so`() {
        for (path in listOf("/accedi", "/accedi/conferma", "/accedi/controlla-la-posta", "/actuator/health", "/error", "/info/q7Vd_3KmZ0xRb-Ew", "/css/app-0123.css", "/js/htmx.min-0123.js", "/fonts/inter-latin-0123.woff2", "/favicon-0123.svg")) {
            assertTrue(PublicPaths.matches(path), path)
        }
        for (path in listOf("/", "/profilo", "/iscrizioni", "/convivenza/x", "/maestri", "/accedi-altro", "/actuator", "/actuator/env", "/actuator/health/liveness", "/errors", "/test/errore", "/ott/generate", "/info", "/info/q7Vd_3KmZ0xRb-Ew/iscrizione", "/convivenza/1/anteprima")) {
            assertFalse(PublicPaths.matches(path), path)
        }
    }

    @Test
    fun `t3 c07 every static file of the app is in the list of the public files`() {
        val staticDir = Path.of("src/main/resources/static")
        val files = Files.walk(staticDir).use { stream ->
            stream.filter { it.isRegularFile() }.map { "/" + it.relativeTo(staticDir).joinToString("/") }.toList()
        }
        assertTrue(files.size >= 10, "found only ${files.size} static files: $files")

        for (file in files) {
            assertTrue(PublicPaths.isStaticFile(file), "$file is served by the app but is not in PublicPaths.staticFiles")
        }
    }

    @Test
    fun `t3 c07 the security configuration opens addresses only through the list`() {
        val sources = Files.walk(Path.of("src/main/kotlin")).use { stream -> stream.filter { it.isRegularFile() }.toList() }

        val opening = sources.filter { Files.readString(it).contains("permitAll") && it.fileName.toString() != "PublicPaths.kt" }

        assertEquals(listOf("SecurityConfiguration.kt"), opening.map { it.fileName.toString() })
        assertTrue(Files.readString(opening.single()).contains("PublicPaths.all.forEach"))
    }
}
