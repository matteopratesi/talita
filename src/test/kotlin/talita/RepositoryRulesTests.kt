package talita

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue(!Files.exists(root.resolve("src/test/kotlin/talita/" + "Modularity" + "Tests.kt")), "the test of the module boundaries is gone with the framework")
    }

    @Test
    fun `t2 c13 htmx has its license and source next to it, and the source names the test that checks its hash`() {
        val source = read("src/main/resources/static/js/htmx-SOURCE.txt")

        assertTrue(source.startsWith("htmx 2.0.11,"))
        assertTrue(source.contains("Un test (AssetTests) confronta"))
        assertTrue(Files.isRegularFile(root.resolve("src/test/kotlin/talita/ui/AssetTests.kt")), "the test named in htmx-SOURCE.txt does not exist")
        assertTrue(Files.isRegularFile(root.resolve("src/main/resources/static/js/htmx-LICENSE.txt")))
    }

    @Test
    fun `t2 c07 Tailwind is version 4_3_3 with the pinned checksum, cached apart from other projects`() {
        val script = read("build.gradle.kts")

        assertTrue(script.contains("""val tailwindVersion = "4.3.3""""))
        assertTrue(script.contains(""""linux-x64" to "dc61b3ac6b8c9ca874c0cc4c57b2409791a64c5540404ca5f5367360babc313a""""))
        assertTrue(script.contains("""caches/talita-tools/tailwindcss/v${'$'}tailwindVersion"""))
    }

    // ---- A download that stops halfway (TALITA-14, criteria 8-12) -------------------------------------
    // The stall itself (headers, some body, then silence) was tried by hand against a local server: it
    // needs the Gradle limit to be lowered, which these tests must not do. Here the pieces that make it
    // work are checked: the limit, the cleanup of the partial file and the way the client is left alone.

    /** The text of a top-level block of the build script: from [start] to the first `}` at the start of a line. */
    private fun block(script: String, start: String): String {
        val from = script.indexOf(start)
        assertTrue(from >= 0, "build.gradle.kts has no `$start`")
        val to = script.indexOf("\n}\n", from)
        assertTrue(to > from, "the block `$start` does not end")
        return script.substring(from, to + 3)
    }

    @Test
    fun `t14 c08 the download of Tailwind is limited to 15 minutes, leaves no partial file and does not catch the interruption`() {
        val script = read("build.gradle.kts")
        // The code of the task, without its comments (which explain the very things that must not be in the code).
        val download = block(script, "val downloadTailwind = tasks.register(").lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

        assertTrue(script.contains("val tailwindDownloadTimeout = Duration.ofMinutes(15)"))
        assertTrue(download.contains("timeout.set(tailwindDownloadTimeout)"))
        // The partial file goes away in a `finally`, so also when the limit interrupts the thread of the task...
        assertTrue(Regex("""(?s)\} finally \{\s*partial\.delete\(\)""").containsMatchIn(download), "no finally that deletes the partial file")
        // ...and an old one, left by a killed build, is removed before the download (which does not truncate it).
        assertTrue(Regex("""(?s)partial\.delete\(\)\s+val failure = try""").containsMatchIn(download), "the partial file is not removed before the download")
        // The interruption is the limit working: it must reach Gradle, not end up in the "cannot download" error.
        assertFalse(download.contains("InterruptedException"), "the download catches the interruption")
        assertFalse(download.contains("catch (e: Exception)") || download.contains("catch (e: Throwable)"), "the download catches more than IOException")
        assertEquals(1, Regex("""\bcatch \(""").findAll(download).count(), "the download has a catch besides the one for IOException")
        // The client is not closed (close() waits for the exchange that never ends) and not used as a resource.
        assertFalse(download.contains("client.close()"))
        assertFalse(download.contains("client.use"))
    }

    @Test
    fun `t14 c09 the checks of the download of today are still there, and the timeouts of the client too`() {
        val download = block(read("build.gradle.kts"), "val downloadTailwind = tasks.register(")

        assertTrue(download.contains(".connectTimeout(Duration.ofSeconds(30))"))
        assertTrue(download.contains(".timeout(Duration.ofMinutes(2))"))
        assertTrue(download.contains("Tailwind CSS: cannot download "))
        assertTrue(download.contains("Tailwind CSS: the file downloaded from "))
        assertTrue(download.contains("It was not run."))
        assertTrue(download.contains("if (!binary.exists())"), "with the binary in the cache the task must not use the network")
    }

    @Test
    fun `t14 c10 the limit of time is only on downloadTailwind, not on tailwindWatch or the other tasks`() {
        val script = read("build.gradle.kts")

        val limits = Regex("""\btimeout\s*(\.set\(|=)""").findAll(script).count()
        assertEquals(1, limits, "the build script sets more than one limit of time on a task")
        assertFalse(block(script, "tasks.register<Exec>(\"tailwindWatch\")").contains("timeout"), "tailwindWatch must run until it is stopped")
        assertFalse(block(script, "val tailwindCss = tasks.register<Exec>(\"tailwindCss\")").contains("timeout"))
    }

    @Test
    fun `t14 c11 the build job has timeout-minutes 45 and the download task has a timeout`() {
        val workflow = read(".github/workflows/build.yml")

        val job = workflow.substringAfter("\njobs:\n").substringAfter("\n  build:\n").substringBefore("\n    steps:\n")
        assertEquals(listOf("45"), Regex("""(?m)^    timeout-minutes: (\d+)\s*$""").findAll(job).map { it.groupValues[1] }.toList())
        assertTrue(block(read("build.gradle.kts"), "val downloadTailwind = tasks.register(").contains("timeout.set("))
    }

    @Test
    fun `t14 c12 the environment guide tells what happens to a download that stops and what to do on a slow line`() {
        val cache = read("docs/04-ambiente.md").substringAfter("\n## Cache\n").substringBefore("\n## ").replace(Regex("""\s+"""), " ")

        assertTrue(cache.contains("si interrompe da solo dopo 15 minuti"))
        assertTrue(cache.contains("basta rilanciarla"))
        assertTrue(cache.contains("si può copiare il file `tailwindcss-linux-x64`"))
        assertTrue(cache.contains("nella cartella della cache: la build ne controlla comunque lo SHA-256"))
        assertTrue(cache.contains("`timeout-minutes`"))
    }

    // ---- Tests that wait for a condition, not for a time (TALITA-16, criterion 8) ------------------------------------

    /** Why a test may still wait for a fixed time: the three reasons of the list. */
    private enum class Why {
        /** A loop that asks again, with a condition and a maximum time, inside a helper that all the tests share. */
        POLL,

        /** A server that is slow, simulated: the test holds the answer or sleeps in the handler of the request. */
        SLOW_SERVER,

        /** The proof that something does not happen, or that a time has to pass: it cannot fail for a slow runner, at most it proves nothing. */
        NOTHING_HAPPENS,
    }

    private data class AllowedWaits(val count: Int, val why: Set<Why>)

    private fun waits(count: Int, vararg why: Why) = AllowedWaits(count, why.toSet())

    /**
     * Every file of the tests that still has a wait of a fixed time (a sleep of the thread, or `waitForTimeout` of Playwright), with how many and why. **No test of concurrency is
     * here**: they wait for the lock with `LockWait`; and the waits of the mail, of the focus, of the scroll and of htmx are conditions too. A file that is not here may have none.
     */
    private val allowedWaits: Map<String, AllowedWaits> = mapOf(
        "src/test/kotlin/talita/LockWait.kt" to waits(1, Why.POLL),
        "src/test/kotlin/talita/mail/Mailbox.kt" to waits(1, Why.POLL),
        "src/e2eTest/kotlin/talita/e2e/ShellPages.kt" to waits(1, Why.POLL),
        "src/test/kotlin/talita/mail/FakeSmtpServer.kt" to waits(1, Why.SLOW_SERVER),
        "src/e2eTest/kotlin/talita/e2e/MenuSlowUploadE2eTest.kt" to waits(1, Why.SLOW_SERVER),
        "src/e2eTest/kotlin/talita/e2e/LoadingStateE2eTest.kt" to waits(5, Why.NOTHING_HAPPENS, Why.SLOW_SERVER),
        "src/e2eTest/kotlin/talita/e2e/MessagesE2eTest.kt" to waits(1, Why.NOTHING_HAPPENS),
        "src/e2eTest/kotlin/talita/e2e/NavigationE2eTest.kt" to waits(2, Why.NOTHING_HAPPENS),
        "src/e2eTest/kotlin/talita/e2e/HistoryE2eTest.kt" to waits(3, Why.NOTHING_HAPPENS),
        "src/e2eTest/kotlin/talita/e2e/HistoryRecoveryE2eTest.kt" to waits(1, Why.NOTHING_HAPPENS),
        "src/e2eTest/kotlin/talita/e2e/HistoryConsistencyE2eTest.kt" to waits(11, Why.NOTHING_HAPPENS, Why.POLL),
        "src/e2eTest/kotlin/talita/e2e/ArrivalsDayE2eTest.kt" to waits(1, Why.NOTHING_HAPPENS),
        "src/e2eTest/kotlin/talita/e2e/ArrivalsE2eTest.kt" to waits(1, Why.NOTHING_HAPPENS),
        "src/e2eTest/kotlin/talita/e2e/EnrollmentE2eTest.kt" to waits(1, Why.NOTHING_HAPPENS),
        "src/e2eTest/kotlin/talita/e2e/LoginE2eTest.kt" to waits(2, Why.NOTHING_HAPPENS),
        "src/e2eTest/kotlin/talita/e2e/RoomsQaE2eTest.kt" to waits(2, Why.NOTHING_HAPPENS),
    )

    private val timedWait = Regex("""Thread\.sleep\(|TimeUnit\.[A-Z]+\.sleep\(|waitForTimeout\(""")

    /** The sources of the tests, unit and e2e, without this file (which is the one that holds the rule), as paths with `/`. */
    private fun testSources(): List<Path> = listOf("src/test", "src/e2eTest").map(root::resolve).filter(Files::isDirectory).flatMap { folder ->
        Files.walk(folder).use { stream -> stream.filter { Files.isRegularFile(it) && it.extension == "kt" && it.name != "RepositoryRulesTests.kt" }.toList() }
    }

    private fun relative(path: Path): String = root.relativize(path).joinToString("/").removePrefix("./")

    /** How many waits of a fixed time a source has: the lines of code, not those of a comment. */
    private fun timedWaitsIn(path: Path): Int =
        path.readLines().filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }.sumOf { timedWait.findAll(it).count() }

    @Test
    fun `t16 c08 a test waits for a condition and not for a time, except the waits of the list, that have a reason`() {
        val found = testSources().associate { relative(it) to timedWaitsIn(it) }.filterValues { it > 0 }
        val use = "wait for the condition: `LockWait.awaitBlocked` for a lock, `focusIs` for the focus, `waitUntil` or `awaitCondition` for the page and for a position, `waitForHtmxIdle` for htmx, " +
            "`assertThat` of Playwright for what is on the page, `Mailbox.waitForMails` for a mail. A test that proves that something does not happen, or that a time has to pass, " +
            "goes in the list of RepositoryRulesTests, with the reason"

        for ((file, count) in found) {
            val allowed = allowedWaits[file]
            assertTrue(allowed != null, "$file has $count wait(s) of a fixed time and is not in the list: $use")
            assertTrue(count <= allowed.count, "$file has $count wait(s) of a fixed time and the list allows ${allowed.count}: $use")
        }
        // The list stays true: a file that was cleaned has to come out of it (or lose the waits that it does not have any more), so that nobody finds room for a new wait.
        for ((file, allowed) in allowedWaits) {
            assertEquals(allowed.count, found[file] ?: 0, "the list allows ${allowed.count} wait(s) in $file and the file has ${found[file] ?: 0}: update the list")
            assertTrue(allowed.why.isNotEmpty(), "$file has no reason in the list")
            assertTrue(Files.isRegularFile(root.resolve(file)), "$file is in the list and is not there")
        }
    }

    @Test
    fun `t16 c08 the rule counts a wait in a line of code and not in a comment, and counts every kind`() {
        val dir = Files.createTempDirectory("waits")
        try {
            val source = dir.resolve("Example.kt")
            Files.writeString(
                source,
                listOf(
                    "// Thread." + "sleep(10) in a comment",
                    " * page.waitFor" + "Timeout(10.0) in a KDoc",
                    "    Thread." + "sleep(300)",
                    "    java.util.concurrent.TimeUnit.SECONDS." + "sleep(1)",
                    "    page.waitFor" + "Timeout(500.0)",
                ).joinToString("\n"),
            )
            assertEquals(3, timedWaitsIn(source))
        } finally {
            Files.walk(dir).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    // ---- The output of a test is the output of the test (TALITA-16, criterion 4) --------------------------------------

    @Test
    fun `t16 c04 only the tests that read the log of the start put the capture of the output on the class`() {
        val withExtension = testSources().filter { "OutputCaptureExtension" in Files.readString(it) }.map { it.name }.sorted()

        // `CapturesOutput` is the annotation, the other two are the classes that read the log of the start of the application (each says why).
        assertEquals(listOf("BootstrapTeacherTests.kt", "CapturesOutput.kt", "NoMailTests.kt"), withExtension)
        for (startup in listOf("BootstrapTeacherTests.kt", "NoMailTests.kt")) {
            val source = testSources().single { it.name == startup }
            assertEquals(1, Regex("""@ExtendWith\(OutputCaptureExtension::class\)""").findAll(Files.readString(source)).count(), "$startup: one class with the capture of the start")
        }
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
