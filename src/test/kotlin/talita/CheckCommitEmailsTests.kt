package talita

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The script that CI runs on every pull request to keep personal and work addresses out of the
 * history of a public repository (scripts/check-commit-emails.sh). It runs here as it is, on a
 * repository made for the occasion: commits with addresses chosen by the test, no network and
 * nothing taken from the configuration of whoever runs the tests (signing, hooks, name of the
 * default branch), on a PC and in CI.
 *
 * The addresses are written in pieces: the rule `t2 c37` looks for addresses in every tracked
 * file, this one included, and its placeholders must not grow for the sake of a test.
 */
class CheckCommitEmailsTests {

    @TempDir
    lateinit var tmp: Path

    private val script: Path = Path.of("scripts/check-commit-emails.sh").toAbsolutePath()

    // The domain of the noreply addresses, without the "@": alone it is not an address.
    private val noreplyDomain = "users.noreply.github.com"

    private fun noreply(id: Int, login: String) = "$id+$login@$noreplyDomain"

    private val person = noreply(1001, "maria-esempio")
    private val bot = noreply(41898282, "github-actions[bot]")

    /** The address GitHub itself uses as committer (squash, "Update branch"). */
    private val github = "noreply" + "@github.com"

    /** The placeholder of the project for the address of a person: not a noreply one. */
    private val other = "nome@example.org"

    private class Run(val exit: Int, val output: String)

    private var repos = 0

    // ---- Criterion 13: the bots of GitHub -----------------------------------------------------------

    @Test
    fun `t14 c13 a commit of a GitHub bot with its id passes, as author and as committer`() {
        assertEquals(0, runCheck(bot).exit)
        assertEquals(0, runCheck(author = person, committer = bot).exit)
        assertEquals(0, runCheck(author = bot, committer = github).exit)
        // Another bot, with a login that has a hyphen and a short id.
        assertEquals(0, runCheck(noreply(49699333, "dependabot[bot]")).exit)
        assertEquals(0, runCheck(noreply(7, "coderabbitai[bot]")).exit)

        val result = runCheck(bot)
        assertTrue(result.output.startsWith("OK: every commit of "), result.output)
    }

    @Test
    fun `t14 c13 the comment at the top of the script says that bots are allowed and why`() {
        val comment = Files.readAllLines(script).takeWhile { it.startsWith("#") }
            .joinToString(" ") { it.removePrefix("#").trim() }.replace(Regex("""\s+"""), " ")

        assertTrue(comment.contains("<id>+<login>[bot]@users.noreply.github.com, that of a GitHub bot"), comment)
        assertTrue(comment.contains("It is public and belongs to no person"), comment)
        assertTrue(comment.contains("refusing it would protect nothing"), comment)
        assertTrue(comment.contains("with the id, \"[bot]\" right before the \"@\", and the domain ending there"), comment)
    }

    // ---- Criterion 14: what looks like a bot and is not ---------------------------------------------

    @Test
    fun `t14 c14 an address that looks like the one of a bot but is not in its form is refused`() {
        val lookAlikes = mapOf(
            "without the id" to "github-actions[bot]@$noreplyDomain",
            "with an id that is not a number" to "abc+github-actions[bot]@$noreplyDomain",
            "with [bot] not right before the at sign" to "41898282+github-actions[bot]x@$noreplyDomain",
            "with [bot] at the start of the login" to "41898282+[bot]github-actions@$noreplyDomain",
            "with [bot] twice" to "41898282+github-actions[bot][bot]@$noreplyDomain",
            "with a domain that goes on after github.com" to "41898282+github-actions[bot]@$noreplyDomain.example.org",
            "with a domain that starts before users" to "41898282+github-actions[bot]@x.$noreplyDomain",
            "with a login that is empty" to "41898282+[bot]@$noreplyDomain",
            "with a different domain" to "41898282+github-actions[bot]@users.noreply.example.org",
        )

        for ((description, address) in lookAlikes) {
            for ((role, result) in listOf("author" to runCheck(author = address, committer = person), "committer" to runCheck(author = person, committer = address))) {
                assertEquals(1, result.exit, "an address $description is accepted as $role")
                assertFalse(result.output.contains(address), "the output shows an address $description")
            }
        }
    }

    // ---- Criterion 15: what the script did before ----------------------------------------------------

    @Test
    fun `t14 c15 the noreply address of a person and the committer of GitHub pass`() {
        assertEquals(0, runCheck(person).exit)
        assertEquals(0, runCheck(author = person, committer = github).exit)
        assertEquals(0, runCheck(github).exit)
    }

    @Test
    fun `t14 c15 an address that is not a noreply one is refused as author or as committer, and the output has the hash and not the address`() {
        for ((role, result) in listOf("author" to runCheck(author = other, committer = person), "committer" to runCheck(author = person, committer = other), "both" to runCheck(other))) {
            assertEquals(1, result.exit, "an ordinary address passes as $role")
            assertTrue(Regex("""Commit [0-9a-f]{7,}: the author or the committer is not a GitHub noreply address\.""").containsMatchIn(result.output), result.output)
            assertFalse(result.output.contains(other), "the output shows the address")
            assertFalse(result.output.contains("example.org"), "the output shows the domain of the address")
            assertFalse(result.output.startsWith("OK"), result.output)
        }
    }

    @Test
    fun `t14 c15 the output names only the commits that are wrong, with their short hash`() {
        val dir = newRepository()
        val base = commit(dir, person, person, "base")
        val good = commit(dir, bot, bot, "good")
        val wrong = commit(dir, other, person, "wrong")
        val goodAgain = commit(dir, person, github, "good again")

        val result = runScript(dir, "$base..HEAD")

        assertEquals(1, result.exit)
        assertTrue(result.output.contains("Commit $wrong:"), result.output)
        assertFalse(result.output.contains("Commit $good:"), result.output)
        assertFalse(result.output.contains("Commit $goodAgain:"), result.output)
        assertFalse(result.output.contains(other), result.output)
    }

    @Test
    fun `t14 c15 a range that git cannot read ends with an error, not with a pass`() {
        val dir = newRepository()
        commit(dir, person, person, "base")

        val result = runScript(dir, "no-such-branch..HEAD")

        assertNotEquals(0, result.exit, result.output)
        assertFalse(result.output.contains("OK:"), result.output)
    }

    @Test
    fun `t14 c15 an empty range passes`() {
        val dir = newRepository()
        val base = commit(dir, person, person, "base")

        assertEquals(0, runScript(dir, "$base..HEAD").exit)
    }

    // ---- Criterion 16: the script runs here as it is, on a repository that depends on nothing of the PC ----

    @Test
    fun `t14 c16 the repository of the tests reads no configuration of the PC, only its own and the options of the test`() {
        val dir = newRepository()
        commit(dir, person, person, "base")

        val origins = git(dir, emptyMap(), "config", "--list", "--show-origin").output.lines().filter { it.isNotBlank() }
            .map { it.substringBefore('\t') }.toSet()

        assertEquals(setOf("file:.git/config", "command line:"), origins)
        // The branch of the repository is the one that the test chose, whatever the PC says.
        assertEquals("main", git(dir, emptyMap(), "symbolic-ref", "--short", "HEAD").output.trim())
    }

    // ---- Support ----------------------------------------------------------------------------------------

    /** A new repository, a new commit on it with [author] and [committer], and the script on that commit alone. */
    private fun runCheck(author: String, committer: String = author): Run {
        val dir = newRepository()
        val base = commit(dir, person, person, "base")
        commit(dir, author, committer, "change")
        return runScript(dir, "$base..HEAD")
    }

    private fun newRepository(): Path {
        val dir = Files.createDirectory(tmp.resolve("repo-${repos++}"))
        git(dir, emptyMap(), "init", "-q")
        return dir
    }

    /** An empty commit with the given author and committer. Returns its short hash, as the script prints it. */
    private fun commit(dir: Path, author: String, committer: String, message: String): String {
        val environment = mapOf(
            "GIT_AUTHOR_NAME" to "Test Author", "GIT_AUTHOR_EMAIL" to author, "GIT_AUTHOR_DATE" to "2026-01-01T00:00:00Z",
            "GIT_COMMITTER_NAME" to "Test Committer", "GIT_COMMITTER_EMAIL" to committer, "GIT_COMMITTER_DATE" to "2026-01-01T00:00:00Z",
        )
        git(dir, environment, "commit", "-q", "--allow-empty", "-m", message)
        return git(dir, emptyMap(), "log", "-1", "--format=%h").output.trim()
    }

    private fun git(dir: Path, environment: Map<String, String>, vararg arguments: String): Run {
        // The configuration that could change the result is set here, as options: no signing, no hooks,
        // a branch name that is not the default of whoever runs the test.
        val options = listOf("-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", "-c", "core.hooksPath=/dev/null", "-c", "init.defaultBranch=main")
        val result = run(dir, environment, listOf("git") + options + arguments)
        assertEquals(0, result.exit, "git ${arguments.joinToString(" ")} failed: ${result.output}")
        return result
    }

    private fun runScript(dir: Path, range: String): Run = run(dir, emptyMap(), listOf("bash", script.toString(), range))

    /** Runs a command in [dir] with the environment of the test cleaned of what git reads from outside. */
    private fun run(dir: Path, environment: Map<String, String>, command: List<String>): Run {
        val builder = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
        val variables = builder.environment()
        variables.keys.removeIf { it.startsWith("GIT_") }
        variables["GIT_CONFIG_GLOBAL"] = "/dev/null"
        variables["GIT_CONFIG_NOSYSTEM"] = "1"
        variables["HOME"] = tmp.toString()
        variables["XDG_CONFIG_HOME"] = tmp.toString()
        variables.putAll(environment)

        val process = builder.start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor(60, TimeUnit.SECONDS)) { "${command.first()} did not end within 60 seconds" }
        return Run(process.exitValue(), output)
    }
}
