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

// The reason of a disabled test: the string literal that is the argument of the annotation `@Disabled(`, with or
// without `value =` and with or without the qualified name (`@org.junit.jupiter.api.Disabled(`). The `@` must not
// follow an identifier and the name must be exactly `Disabled`, so `Disabled(` in a string, in a call or at the
// end of the name of another annotation is not the annotation.
private val disabledReason = Regex("""(?<!\w)@(?:[A-Za-z_]\w*\.)*Disabled\(\s*(?:value\s*=\s*)?"(?:[^"\\]|\\.)*"""")

/**
 * The line without the mentions of the origin ticket that are allowed. It says where a test or a
 * choice comes from, so it is allowed:
 *  - in a comment (a whole line, or what follows a line-comment or block-comment marker after some code);
 *  - in the path of the folder of the mockups, `docs/design/<origin>`;
 *  - in the reason of a disabled test: only inside the string that is the argument of
 *    `@Disabled("...")`, not in the rest of the line (`@Disabled("reason") val x = "<origin>"` is found),
 *    and only for the annotation itself: the words `Disabled("<origin>")` in a raw string, in a call or
 *    in another annotation are not it, and the search finds the name.
 * Anywhere else (a string, a configuration value) it stays in the line, and the search finds it.
 *
 * Limits, known and on purpose:
 *  - The rule works on one line at a time, and what follows a marker of a comment (two slashes, the
 *    start of a block comment, a `#` after a space) is taken as a comment even when the marker is inside
 *    a string: a line like `val s = "x // <origin>"` is not found (a test fixes this, so that whoever
 *    closes the case knows what they are changing).
 *    Closing it well means recognizing the strings of every language of the repository (Kotlin, also
 *    raw and with templates, YAML, shell, JTE, JS, CSS, SQL) in a rule that reads lines; counting
 *    the quotes would close only the case of the double ones and bring errors of its own. The rule
 *    protects from a leftover copied by mistake, not from whoever hides it on purpose: for that
 *    there is the review.
 *  - The reason of a `Disabled` must be a plain string on the same line as `@Disabled(`: one split
 *    over more lines, or a raw string, is not allowed and the search finds the name in it. The other
 *    way round, the text of the annotation itself (with its `@`) inside a string looks like the
 *    annotation, and the rule cannot tell: same limit as the comment markers, same answer.
 */
internal fun withoutAllowedMentions(line: String): String {
    val trimmed = line.trimStart()
    if (wholeLineComment.any { trimmed.startsWith(it) }) return line.replace(origin, "")

    val marker = commentStart.find(line)
    val code = if (marker == null) line else line.substring(0, marker.range.first)
    val comment = if (marker == null) "" else line.substring(marker.range.first).replace(origin, "")

    val codeWithoutPath = code.replace("docs/design/$origin", "docs/design/")
    val allowedCode = disabledReason.replace(codeWithoutPath) { it.value.replace(origin, "") }
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

    // ---- The reason of a disabled test (TALITA-14, criteria 17-19) ----------------------------------------

    @Test
    fun `t14 c17 the origin is allowed inside the reason of a disabled test, and found elsewhere on the same line`() {
        val allowed = listOf(
            """@Disabled("$origin")""",
            """@Disabled(value = "Observation $origin (D7)")""",
            """    @org.junit.jupiter.api.Disabled("Observation $origin (D7): a slow back")""",
            """@Disabled("see \"$origin\" in the notes")""",
        )
        for (line in allowed) {
            assertFalse(withoutAllowedMentions(line).contains(origin), "should be allowed: $line")
        }

        val forbidden = listOf(
            """@Disabled("reason") val x = "$origin"""",
            """@org.junit.jupiter.api.Disabled("Observation $origin (D7)") val x = "$origin"""",
            """@Disabled("reason") fun f() = println("$origin")""",
            """@Disabled("$origin is" + "$origin")""",
            """val x = "$origin"; @Disabled("reason")""",
            """@Disabled val x = "$origin"""",
            """@Disabled(reason) val x = "$origin"""",
            // The words of the annotation without the annotation: in a raw string, in a call, in another annotation.
            "val copy = \"\"\"Disabled(\"$origin\")\"\"\"",
            """val copy = Disabled("$origin")""",
            """@NotDisabled("$origin")""",
            """@org.example.NotDisabled("$origin")""",
            """label@Disabled("$origin")""",
        )
        for (line in forbidden) {
            assertTrue(withoutAllowedMentions(line).contains(origin), "should be found: $line")
        }
    }

    @Test
    fun `t14 c18 a string with a comment marker before the name is not found, a limit that is known and fixed by this test`() {
        // The rule reads one line at a time and cuts it at the first marker of a comment, even inside a string.
        for (marker in listOf("//", "/" + "*", "#")) {
            val line = """val s = "x $marker $origin""""
            assertFalse(withoutAllowedMentions(line).contains(origin), "the limit is gone for the marker $marker: update this test and the KDoc of withoutAllowedMentions")
        }
        // The text of the annotation itself, with its `@`, inside a string looks like the annotation: same limit.
        assertFalse(
            withoutAllowedMentions("val copy = \"\"\"@Disabled(\"$origin\")\"\"\"").contains(origin),
            "the limit is gone for the annotation inside a string: update this test and the KDoc of withoutAllowedMentions",
        )
        // What comes before the marker is still searched.
        assertTrue(withoutAllowedMentions("""val s = "$origin // x"""").contains(origin))
        assertTrue(withoutAllowedMentions("""val s = "$origin" // x""").contains(origin))

        // The KDoc of the rule says it is a known limit, and why.
        val source = Files.readString(root.resolve("src/test/kotlin/talita/NoLeftoversTests.kt"))
        assertTrue(source.contains("Limits, known and on purpose"))
        assertTrue(source.contains("recognizing the strings of every language of the repository"))
        assertTrue(source.contains("not from whoever hides it on purpose"))
    }

    @Test
    fun `t14 c19 the real disabled test is still allowed, and so are the cases that were allowed before`() {
        val file = root.resolve("src/e2eTest/kotlin/talita/e2e/HistoryConsistencyE2eTest.kt")
        val disabled = Files.readAllLines(file).filter { it.contains("Disabled(") }

        assertEquals(1, disabled.size, "the one disabled test of the repository")
        assertTrue(disabled.single().contains(origin), "the reason of the disabled test says where it comes from")
        assertFalse(withoutAllowedMentions(disabled.single()).contains(origin))
    }
}
