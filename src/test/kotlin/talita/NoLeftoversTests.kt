package talita

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the code must not carry over from the project it was copied from. */
class NoLeftoversTests {

    private val root: Path = Path.of(".")

    // The words are written in pieces so that this file does not contain them: it is itself under
    // src/ and would be found by its own search.
    private val leftovers = listOf(
        "koi" + "ne", "koi" + "nè", "centro" + "_id", "modu" + "lith", "date" + "range", "btree" + "_gist", "pg" + "_trgm",
    )

    private val searched = listOf("src", "scripts", ".github", "build.gradle.kts", "settings.gradle.kts", "compose.yaml")
    private val binaryExtensions = setOf("woff2", "jar", "png", "ico")

    @Test
    fun `t2 c09 the code carries nothing of the project it was copied from except the origin of a test`() {
        val files = searched.map(root::resolve).filter(Files::exists).flatMap { start ->
            Files.walk(start).use { stream -> stream.filter { it.isRegularFile() && it.extension !in binaryExtensions }.toList() }
        }
        assertTrue(files.size > 50, "the search found only ${files.size} files: is it running from the project directory?")

        val found = files.flatMap { file ->
            // "KOINE-11" is the one mention that is allowed: it says where a test or a choice comes from.
            val lines = Files.readAllLines(file).map { it.replace("KOINE-11", "") }
            lines.withIndex().filter { (_, line) -> leftovers.any { line.contains(it, ignoreCase = true) } }
                .map { (index, line) -> "$file:${index + 1}: ${line.trim().take(120)}" }
        }

        assertEquals(emptyList<String>(), found)
    }
}
