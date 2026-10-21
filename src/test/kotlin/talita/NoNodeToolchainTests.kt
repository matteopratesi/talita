package talita

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.assertEquals

/** Talità has no JavaScript toolchain: Tailwind is a standalone binary, htmx is a vendored file. */
class NoNodeToolchainTests {

    private val ignoredDirs = setOf(".git", "build", ".gradle", ".kotlin", ".idea", "jte-classes")

    private val nodeFiles = setOf(
        "package.json", "package-lock.json", "npm-shrinkwrap.json", "yarn.lock", "pnpm-lock.yaml", "bun.lockb", "bun.lock",
    )

    private fun repoFiles(): List<Path> = Files.walk(Path.of(".")).use { paths ->
        paths.filter { path -> path.none { it.name in ignoredDirs } }.toList()
    }

    @Test
    fun `c07 the repository has no package manifest, lockfile or node_modules`() {
        val found = repoFiles().filter { it.name in nodeFiles || it.name == "node_modules" }

        assertEquals(emptyList<Path>(), found)
    }

    @Test
    fun `c07 no build script, workflow or script runs node, npm or npx`() {
        val invocation = Regex("""(^|[\s"'`(;|&])(node|nodejs|npm|npx|pnpm|pnpx|yarn|bun|bunx)(\s|"|'|`|$)""")
        val scripts = repoFiles().filter { path ->
            path.isRegularFile() && (
                path.name.endsWith(".gradle.kts") ||
                    path.extension in setOf("sh", "yml", "yaml") &&
                    path.any { it.name in setOf(".github", "scripts", "gradle") }
                )
        }

        val offending = scripts.flatMap { script ->
            Files.readAllLines(script)
                .filterNot { it.trim().let { line -> line.startsWith("#") || line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
                .filter { invocation.containsMatchIn(it) }
                .map { "$script: ${it.trim()}" }
        }

        assertEquals(emptyList<String>(), offending)
    }
}
