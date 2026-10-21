package talita

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The ticket of the project this one was copied from. In pieces, like the words below: this file
// is under src/ and would be found by its own search.
private val origin = "KOI" + "NE-11"

private val wholeLineComment = listOf("//", "*", "/*", "#", "<%--")

// Where a comment starts after some code: `//` (but not in `https://`), `/*`, or a `#` after a space.
private val commentStart = Regex("""(?<!:)//|/\*|(?<=\s)#""")

/**
 * The line without the mentions of the origin ticket that are allowed. It says where a test or a
 * choice comes from, so it is allowed:
 *  - in a comment (a whole line, or what follows a line-comment or block-comment marker after some code);
 *  - in the path of the folder of the mockups, `docs/design/<origin>`;
 *  - in the reason of a disabled test (`@Disabled("...")`).
 * Anywhere else (a string, a configuration value) it stays in the line, and the search finds it.
 */
internal fun withoutAllowedMentions(line: String): String {
    val trimmed = line.trimStart()
    if (wholeLineComment.any { trimmed.startsWith(it) }) return line.replace(origin, "")

    val marker = commentStart.find(line)
    val code = if (marker == null) line else line.substring(0, marker.range.first)
    val comment = if (marker == null) "" else line.substring(marker.range.first).replace(origin, "")

    val codeWithoutPath = code.replace("docs/design/$origin", "docs/design/")
    val allowedCode = if (codeWithoutPath.contains("Disabled(")) codeWithoutPath.replace(origin, "") else codeWithoutPath
    return allowedCode + comment
}

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
            // The origin ticket is the one mention that is allowed, and only where it says where a test or a choice comes from.
            val lines = Files.readAllLines(file).map(::withoutAllowedMentions)
            lines.withIndex().filter { (_, line) -> leftovers.any { line.contains(it, ignoreCase = true) } }
                .map { (index, line) -> "$file:${index + 1}: ${line.trim().take(120)}" }
        }

        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `t2 c09 the origin of a test is allowed in a comment, in the mockup path and in a disabled test, not in code or strings`() {
        val allowed = listOf(
            "// QA of $origin, round two",
            "    // ---- Defects found by the QA ($origin) ----",
            " * What happens around the back button ($origin, after the defects)",
            "# The production jar starts without profiles ($origin).",
            "val retries = 3 // as in $origin",
            "step: run # from $origin",
            "/* from $origin */",
            "<%-- model of the messages, from $origin --%>",
            "    @org.junit.jupiter.api.Disabled(\"Observation $origin (D7): a slow back\")",
            "val folder = \"docs/design/$origin/\"",
        )
        for (line in allowed) {
            assertFalse(withoutAllowedMentions(line).contains(origin), "should be allowed: $line")
        }

        val forbidden = listOf(
            "val ticket = \"$origin\"",
            "implementation(\"group:artifact:$origin\")",
            "name: $origin",
            "val url = \"https://example.org/$origin\"",
            "println(\"see docs/design/ and $origin\")",
            "val ticket = \"$origin\" // and a comment",
        )
        for (line in forbidden) {
            assertTrue(withoutAllowedMentions(line).contains(origin), "should be found: $line")
        }
    }
}
