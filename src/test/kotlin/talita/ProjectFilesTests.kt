package talita

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import talita.ui.Section
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The files that are not code: the agents and the skill of the work flow, CLAUDE.md, the documents
 * and the license. They are what every future session reads first, so a wrong name, a missing
 * file or a leftover of the project this one was copied from has a cost: these checks keep them
 * honest.
 */
class ProjectFilesTests {

    private val root: Path = Path.of(".")

    private fun read(path: String): String = Files.readString(root.resolve(path))

    private val agents = mapOf(
        "architect" to Triple("Edgar Case", "architetto", "opus"),
        "designer" to Triple("Grace Kerning", "designer", "sonnet"),
        "developer" to Triple("Rick Ursion", "developer", "sonnet"),
        "qa" to Triple("Tess Ting", "QA", "sonnet"),
    )

    private val skillPath = ".claude/skills/avanza/SKILL.md"

    // The name and the ticket key of the project this one was copied from, in pieces: this file is
    // under src/ and the code must not carry them (see NoLeftoversTests).
    private val otherName = "Koi" + "nè"
    private val otherKey = "KOI" + "NE"

    private fun agentPath(key: String) = ".claude/agents/$key.md"

    // ---- The work flow (criteria 25-28) ----------------------------------------------------------

    @Test
    fun `t2 c25 the agents and the skill have nothing of the project they were copied from`() {
        // In pieces, so that this file does not contain what it looks for.
        val forbidden = listOf(
            "KOI" + "NE", "Koi" + "nè", "\"In Pro" + "gress\"", "\"Do" + "ne\"", "Modu" + "lith",
            "da-confermare-" + "in-call", "docs/0" + "6", "docs/05-" + "accordo",
        )
        val files = agents.keys.map(::agentPath) + skillPath

        for (file in files) {
            val text = read(file)
            for (word in forbidden) assertFalse(text.contains(word), "$file contains $word")
        }
    }

    @Test
    fun `t2 c26 the skill works on the project TALITA with the transitions named after the destination state`() {
        val skill = read(skillPath)

        assertTrue(skill.contains("Jira: progetto TALITA, cloudId `7c631dfd-34f5-47cd-8883-fcde1c6f298d`"))
        assertTrue(skill.contains("""project = TALITA AND status = "To Do" ORDER BY rank"""))
        assertTrue(skill.contains(""""In Development" porta a In Development e"""))
        assertTrue(skill.contains(""""Closed" a Closed"""))
        // The flow of the pull requests: draft, web link without duplicates, ready after the QA, CodeRabbit asked by hand, squash.
        for (step in listOf("gh pr create --draft", "listJiraIssueRemoteIssueLinks", "createJiraIssueRemoteIssueLink", "gh pr ready", "@coderabbitai review", "Review completed", "gh pr merge --squash --delete-branch")) {
            assertTrue(skill.contains(step), "the skill does not mention $step")
        }
        assertTrue(skill.contains("etichetta `decisione`"))
        assertTrue(skill.contains("/avanza TALITA-"))
    }

    // ---- CodeRabbit asked by hand (TALITA-14, criteria 1-6) ------------------------------------------

    /** The skill on one line per paragraph, so that a sentence wrapped at 100 columns is still found. */
    private fun skillText(): String = read(skillPath).replace(Regex("""\s+"""), " ")

    @Test
    fun `t14 c01 the skill asks for the review with a comment as soon as the pull request is ready, unless it already started`() {
        val skill = skillText()

        assertTrue(skill.contains("`gh pr ready <numero>`"))
        assertTrue(skill.contains("""`gh pr comment <numero> --body "@coderabbitai review"`"""))
        assertTrue(skill.contains("meno di 10 stelle"), "it does not say why CodeRabbit does not start by itself")
        assertTrue(skill.contains("«Review skipped: manual review required for this OSS repository»"))
        // When the review already started (or finished) the comment is not written.
        assertTrue(skill.contains("«Review in progress» o «Review completed»"))
        assertTrue(skill.contains("il commento non si scrive"))
        // The pull request of a draft says so, and the skill says to look again.
        assertTrue(skill.contains("«Review skipped: draft pull request»"))
    }

    @Test
    fun `t14 c02 the signal is the description of the CodeRabbit status on the head commit, read with the gh that is installed`() {
        val skill = skillText()

        assertTrue(skill.contains("descrizione dello status `CodeRabbit` sul commit di testa della PR, non il suo stato"))
        assertTrue(skill.contains("anche «Review skipped…» ha stato `success`"))
        assertTrue(skill.contains("`gh pr checks` lo mostra come `pass`"))
        assertTrue(skill.contains("(2.45) non ha `gh pr checks --json`"))
        assertTrue(skill.contains("`gh pr view <numero> --json headRefOid --jq .headRefOid`"))
        assertTrue(skill.contains("gh api repos/{owner}/{repo}/commits/<sha>/status"))
        // The status is per commit: a push puts the new head back to "skipped".
        assertTrue(skill.contains("Lo status è per commit"))
    }

    @Test
    fun `t14 c03 after the corrections the skill asks for a second round on the new commits, and for a third it asks Matteo`() {
        val skill = skillText()

        assertTrue(skill.contains("**Secondo giro.**"))
        assertTrue(skill.contains("quando la CI è di nuovo verde e il QA ha ripetuto i criteri toccati"))
        assertTrue(skill.contains("`@coderabbitai review` (rivede solo i commit nuovi)"))
        assertTrue(skill.contains("aspetta «Review completed» sul nuovo commit di testa"))
        assertTrue(skill.contains("niente terzo giro automatico: lo dici a Matteo"))
        assertTrue(skill.contains("Niente push mentre la review è «in progress»"))
    }

    @Test
    fun `t14 c04 the skill waits for the rate limit and tells Matteo when nothing comes within 30 minutes`() {
        val skill = skillText()

        assertTrue(skill.contains("rate limit lo scrive in un commento sulla PR (lo status può non dirlo)"))
        assertTrue(skill.contains("aspetta il tempo indicato e chiedi di nuovo la review"))
        assertTrue(skill.contains("entro 30 minuti dalla richiesta"))
        assertTrue(skill.contains("né «Review completed» né un commento del bot che spieghi perché"))
        assertTrue(skill.contains("lo dici a Matteo invece di chiedergli l'ok al merge"))
    }

    @Test
    fun `t14 c05 the review is over only when the last round is completed and every comment has an answer, and Matteo is told the rounds`() {
        val skill = skillText()

        assertTrue(skill.contains("**Review conclusa.**"))
        assertTrue(skill.contains("l'ultimo giro che hai chiesto ha «Review completed» e ogni commento di CodeRabbit ha una correzione o una risposta"))
        assertTrue(skill.contains("quanti giri sono stati fatti e quali commit CodeRabbit non ha rivisto"))
        assertTrue(skill.contains("Mai merge prima che la review di CodeRabbit sia conclusa"))
    }

    @Test
    fun `t14 c06 CLAUDE_md, the developer and the QA no longer say that CodeRabbit reviews once or by itself`() {
        for (file in listOf("CLAUDE.md", agentPath("developer"), agentPath("qa"), skillPath)) {
            val text = read(file).replace(Regex("""\s+"""), " ")
            assertFalse(text.contains("una volta sola"), "$file still says that the review is done once")
            assertFalse(text.contains("la rivede una volta"), "$file")
        }

        val claude = read("CLAUDE.md").replace(Regex("""\s+"""), " ")
        assertTrue(claude.contains("Dopo il QA si mette pronta (`gh pr ready`) e la review di CodeRabbit si chiede con un commento `@coderabbitai review`"))
        assertTrue(claude.contains("non parte da sola"))

        val developer = read(agentPath("developer")).replace(Regex("""\s+"""), " ")
        assertTrue(developer.contains("Non metterla pronta e non chiedere la review di CodeRabbit: lo fa l'orchestratore dopo il QA"))
        assertFalse(developer.contains("gh pr ready"), "the developer does not mark the pull request ready")

        val qa = read(agentPath("qa")).replace(Regex("""\s+"""), " ")
        assertTrue(qa.contains("scrivi che è pronto per la review di CodeRabbit e poi per l'ok di Matteo al merge"))
    }

    @Test
    fun `t2 c27 every agent has its name, role, model and signature, for Talita, and points to documents that exist`() {
        for ((key, expected) in agents) {
            val (name, role, model) = expected
            val text = read(agentPath(key))

            assertTrue(text.contains("name: $key\n"), key)
            assertTrue(text.contains("model: $model\n"), "$key: model")
            assertTrue(text.contains("**$name · $role**"), "$key: signature")
            assertTrue(Regex("""Sei $name, (l'|la |il )?$role di Talità""", RegexOption.IGNORE_CASE).containsMatchIn(text), "$key: 'di Talità'")
        }

        val architect = read(agentPath("architect"))
        assertTrue(architect.contains("**Pacchetti e schema**"))
        assertFalse(architect.contains("Moduli e schema"))
        assertTrue(architect.contains("un genitore vede solo i suoi"))
        assertTrue(architect.contains("stato della convivenza (bozza, aperta, chiusa)"))
        assertTrue(architect.contains("l'anno dopo"))
        assertTrue(architect.contains("la rete assente"))

        val designer = read(agentPath("designer"))
        assertTrue(designer.contains("I genitori che arrivano dal link condiviso"))
        assertTrue(designer.contains("i maestri, dal telefono"))
        assertTrue(designer.contains("durante la convivenza"))

        val developer = read(agentPath("developer"))
        assertTrue(developer.contains("TALITA-3-login"))
        assertTrue(developer.contains("(TALITA-5)"))
        assertTrue(developer.contains("un pacchetto per area sotto `talita`"))

        val qa = read(agentPath("qa"))
        assertTrue(qa.contains("dati sintetici creati dai test stessi"))
        assertFalse(qa.contains("generatore."), "the QA still points at a generator")
        assertTrue(qa.contains("un genitore vede solo i suoi figli"))

        for (file in agents.keys.map(::agentPath) + skillPath + "CLAUDE.md" + "README.md" + docsFiles().filter { it.endsWith(".md") }.map { "docs/$it" }) {
            for (path in documentPaths(read(file))) {
                assertTrue(Files.exists(root.resolve(path)), "$file points to $path, which does not exist")
            }
        }
    }

    @Test
    fun `t2 c28 the Atlassian allow-list is that of the other project and the worktrees of the agents are not tracked`() {
        val settings = read(".claude/settings.json")

        val allowed = Regex(""""(mcp__[^"]+)"""").findAll(settings).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "mcp__fcd55015-e375-4e00-9dba-aa8917e2d20e__editJiraIssue",
                "mcp__fcd55015-e375-4e00-9dba-aa8917e2d20e__addOrEditJiraIssueComment",
                "mcp__fcd55015-e375-4e00-9dba-aa8917e2d20e__transitionJiraIssue",
            ),
            allowed,
        )
        assertTrue(read(".gitignore").lines().contains(".claude/worktrees/"))
    }

    // ---- CLAUDE.md, README and documents (criteria 21-24, 34) ----------------------------------------

    @Test
    fun `t2 c22 CLAUDE_md says what Talita is, who is there, the rules, the principles, the stack, the documents and how to work`() {
        val claude = read("CLAUDE.md")

        assertTrue(claude.startsWith("# Talità\n"))
        for (section in listOf("## Chi c'è", "## Regole non negoziabili", "## Principi di design", "## Stack", "## Documenti", "## Come lavorare")) {
            assertTrue(claude.contains("\n$section\n"), "missing $section")
        }
        assertTrue(claude.contains("product owner, titolare dei dati e maestro"))
        for (account in listOf(
            "712020:75ae7c20-cfb1-4392-a92b-62934ecf7c58", "712020:f3119d87-5232-4656-a6b9-d685cc9600cc",
            "712020:5cd74b49-1b67-4d54-94eb-f393ab3e0b7b", "712020:c5d138eb-26a9-4169-aa3b-272776f79cb0",
        )) {
            assertTrue(claude.contains(account), "agent account $account")
        }
        // The seven rules, numbered.
        val rules = claude.substringAfter("## Regole non negoziabili").substringBefore("\n## ")
        assertEquals((1..7).toList(), Regex("""(?m)^(\d)\. \*\*""").findAll(rules).map { it.groupValues[1].toInt() }.toList())
        // Stack, with versions and the destination.
        for (item in listOf("Kotlin 2.3.21", "Spring Boot 4.1.1", "JDK 25", "Gradle 9.8", "PostgreSQL 18", "JTE 3.2.4", "htmx 2.0.11", "Tailwind 4.3.3", "Oracle A1")) {
            assertTrue(claude.contains(item), "the stack does not say $item")
        }
        // Work flow: Jira TALITA, states, transitions, draft PR, CodeRabbit, commits.
        for (item in listOf("progetto TALITA", "Open → To Do → Analyzed → Designed → Ready to Develop → In Development → In QA → Closed", "\"In Development\" e \"Closed\"", "gh pr create --draft", "CodeRabbit", "Conventional Commits")) {
            assertTrue(claude.contains(item), "the work flow does not say $item")
        }
        // Nothing that belongs to the other project.
        for (word in listOf("decim", "turni", "preti")) assertFalse(claude.contains(word, ignoreCase = true), word)
        assertFalse(Regex("""\bCentro\b""").containsMatchIn(claude), "the Centro is not the client of Talità")
    }

    @Test
    fun `t2 c34 the documents listed in CLAUDE_md are the files in docs`() {
        val section = read("CLAUDE.md").substringAfter("\n## Documenti\n").substringBefore("\n## ")
        val listed = Regex("""(?m)^- `(docs/[^`]+)`""").findAll(section).map { it.groupValues[1] }.toSet()

        val actual = docsFiles().filter { it.endsWith(".md") }.map { "docs/$it" }.toSet()

        assertEquals(actual, listed)
        assertEquals(
            setOf("docs/01-contesto.md", "docs/02-modello-dati.md", "docs/03-privacy.md", "docs/04-ambiente.md", "docs/05-decisioni.md", "docs/design/README.md"),
            actual,
        )
    }

    @Test
    fun `t2 c23 the README says what Talita is and how to build, develop and test`() {
        val readme = read("README.md")

        assertTrue(readme.startsWith("# Talità\n"))
        assertTrue(readme.contains("*An app for organizing spiritual retreats for parish children. The app and its documentation are in Italian.*"))
        assertTrue(readme.contains("Kotlin, Spring Boot, JTE, htmx e Tailwind, con Postgres."))
        for (item in listOf("JDK", "Docker", "./gradlew build", "scripts/smoke-test-jar.sh", "./gradlew bootRun", "./gradlew tailwindWatch", "./gradlew test", "./gradlew e2eTest")) {
            assertTrue(readme.contains(item), "the README does not say $item")
        }
    }

    @Test
    fun `t2 c24 the decision log has the birth of Talita, the inherited stack, the decisions behind the copied code and those of this ticket`() {
        val log = read("docs/05-decisioni.md")

        for (heading in listOf(
            "## 2026-10-21: nascita di Talità ($otherKey-29)",
            "## 2026-10-21: lo stack di $otherName, senza ciò che qui non serve (TALITA-2)",
            "## 2026-10-21: scelte di TALITA-2",
            "## 2026-10-19: design system ($otherKey-11)",
            "## 2026-10-19: spike JTE ($otherKey-11), si resta su JTE",
            "## 2026-10-19: asset, e2e e guscio ($otherKey-11)",
        )) {
            assertTrue(log.contains("\n$heading\n"), "missing $heading")
        }
        // What is taken out of the inherited stack, and why.
        val stack = log.substringAfter("## 2026-10-21: lo stack di $otherName").substringBefore("\n## ")
        for (removed in listOf("Spring Modu" + "lith", "Multi-centro", "Row-level security", "btree" + "_gist", "pg" + "_trgm")) {
            assertTrue(stack.contains(removed), "the stack section does not explain why $removed is out")
        }
        // The choices of this ticket.
        val choices = log.substringAfter("## 2026-10-21: scelte di TALITA-2").substringBefore("\n## ")
        for (choice in listOf("**Porte di sviluppo**", "**Voci di navigazione**", "**Cache di Tailwind**", "**Solo `unaccent`**")) {
            assertTrue(choices.contains(choice), "missing $choice")
        }
    }

    @Test
    fun `t14 c20 the entries of the decision log go from the oldest to the newest, whatever is added after them`() {
        val log = read("docs/05-decisioni.md")

        // The line 3 of the log promises it: "dalla più vecchia alla più recente". The whole log is checked,
        // so an entry added tomorrow, with a later or equal date, keeps the test green; an older one does not.
        assertTrue(log.contains("dalla più vecchia alla più recente"))
        val headings = entryHeadings(log)
        val dates = headings.map { it.take(10) }
        assertTrue(dates.size >= 8, "the log has only ${dates.size} entries")
        assertTrue(datesNeverDescend(dates), "the entries are not in order of date: $dates")

        // The entries that had to be moved: the four of 19/10 come before the three of 21/10, in this order.
        // Nothing is said about the entries that come later.
        val moved = listOf(
            "2026-10-19: design system", "2026-10-19: spike JTE", "2026-10-19: asset, e2e e guscio", "2026-10-19: un solo ticket",
            "2026-10-21: nascita di Talità", "2026-10-21: lo stack di ", "2026-10-21: scelte di TALITA-2",
        )
        val positions = moved.map { prefix -> headings.indexOfFirst { it.startsWith(prefix) }.also { assertTrue(it >= 0, "the log has no entry '$prefix'") } }
        assertTrue(positions.zipWithNext().all { (before, after) -> before < after }, "the entries are not in the order $moved: $positions")

        // The rule itself: equal dates are fine, a date that goes back is not.
        assertTrue(datesNeverDescend(listOf("2026-10-19", "2026-10-19", "2026-10-21", "2026-11-02")))
        assertFalse(datesNeverDescend(listOf("2026-10-21", "2026-10-19")))
        assertFalse(datesNeverDescend(listOf("2026-10-19", "2026-10-21", "2026-10-20")))
    }

    @Test
    fun `t14 c20 the decision log has the entry of TALITA-14 with the choices of points 1, 3, 4 and 5`() {
        // Found by its title, wherever it is: other entries may come after it (the point 2 was tried before
        // and is said in a line).
        val entry = decisionEntries(read("docs/05-decisioni.md")).firstOrNull { it.lineSequence().first().contains("scelte di TALITA-14") }

        assertNotNull(entry, "the log has no entry 'scelte di TALITA-14'")
        for (choice in listOf(
            "**Review di CodeRabbit a mano.**", "**Prova di `/avanza` senza chiave: già fatta.**", "**Download di Tailwind fermo a metà.**",
            "**Bot di GitHub ammessi nel controllo degli indirizzi**", "**Regola sul nome del ticket di origine**",
        )) {
            assertTrue(entry.contains(choice), "the entry of TALITA-14 misses $choice")
        }
    }

    @Test
    fun `t2 c21 the design guide is the one of Talita and its navigation table is the one of the code`() {
        val guide = read("docs/design/README.md")

        assertTrue(guide.startsWith("# Design system di Talità\n"))
        assertTrue(guide.contains("`docs/design/KOINE-11/` è il mockup di origine"))
        assertTrue(Files.isDirectory(root.resolve("docs/design/KOINE-11")))
        // Icons of the navigation and the mark.
        assertTrue(guide.contains("| Arrivi | `clipboard-check` |"))
        for (gone in listOf("contact-round", "building-2", "`merge`")) assertFalse(guide.contains(gone), gone)
        assertTrue(guide.contains("""<path d="M10.5 9h11M16 9v14""""))
        assertTrue(guide.contains("con una T bianca"))

        // The table of the entries (3.3) is the list of sections of the code: same order, labels, addresses and icons.
        val rows = Regex("""(?m)^\| (\d) \| ([^|]+) \| `([^`]+)` \| `([^`]+)` \| ([^|]+) \|""").findAll(guide.substringAfter("### 3.3 Navigazione").substringBefore("### 3.4"))
            .map { listOf(it.groupValues[2].trim(), it.groupValues[3], it.groupValues[4], it.groupValues[5].trim()) }.toList()
        // The fourth column says who sees the entry: the table and the navigation of the code agree on the roles too (TALITA-3).
        val sections = Section.entries.map {
            listOf(it.label, it.path, it.icon.name.lowercase().replace('_', '-'), if (it.visibleToParents) "Maestro e genitore" else "Solo maestro")
        }
        assertEquals(sections, rows)
    }

    // ---- A repository that is public (criteria 35, 37) ----------------------------------------------

    @Test
    fun `t2 c35 the license is the AGPL 3_0 and the README says so`() {
        val license = read("LICENSE")

        assertTrue(license.contains("GNU AFFERO GENERAL PUBLIC LICENSE"))
        assertTrue(license.contains("Version 3, 19 November 2007"))
        assertTrue(license.trimEnd().endsWith("<https://www.gnu.org/licenses/>."))
        assertTrue(read("README.md").contains("AGPL-3.0"))
        // The licenses of the files that come from elsewhere stay next to them.
        for (file in listOf("fonts/LICENSE.txt", "icons/LICENSE.txt", "js/htmx-LICENSE.txt")) {
            assertTrue(Files.isRegularFile(root.resolve("src/main/resources/static/$file")), file)
        }
    }

    @Test
    fun `t2 c37 no tracked file has an email address`() {
        val tracked = trackedFiles()
        assumeTrue(tracked != null, "not a git checkout: the tracked files cannot be listed")

        // A placeholder that shows the format of an address is not the address of anyone. The ones
        // written as <id>+<user>@... are not matched at all: a bracket is not part of an address.
        val placeholders = setOf("nome@example.org")
        val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
        val binary = setOf("woff2", "jar", "png", "ico")

        val found = tracked!!.filter { Path.of(it).extension !in binary }.flatMap { file ->
            val text = Files.readString(root.resolve(file))
            email.findAll(text).map { it.value }.filter { it !in placeholders }.map { "$file: an email address" }.toList()
        }

        assertEquals(emptyList<String>(), found)
    }

    // ---- Support ------------------------------------------------------------------------------------

    /** The headings `## AAAA-MM-GG: ...` of the decision log, without the `## `, in the order they appear. */
    private fun entryHeadings(log: String): List<String> =
        Regex("""(?m)^## (\d{4}-\d{2}-\d{2}\b.*)$""").findAll(log).map { it.groupValues[1] }.toList()

    /** The entries of the decision log, each from its heading (included) to the next one. */
    private fun decisionEntries(log: String): List<String> = log.split(Regex("""(?m)^(?=## )""")).filter { it.startsWith("## ") }

    /** ISO dates compare as text. */
    private fun datesNeverDescend(dates: List<String>): Boolean = dates.zipWithNext().all { (before, after) -> before <= after }

    /** Files under docs/, as paths relative to docs/ (the mockups of the origin are html and css). */
    private fun docsFiles(): List<String> = Files.walk(root.resolve("docs")).use { stream ->
        stream.filter { it.isRegularFile() }.map { root.resolve("docs").relativize(it).joinToString("/") }.sorted().toList()
    }

    /** The `docs/...` paths a text points to, without examples that are not meant to exist. */
    private fun documentPaths(text: String): List<String> =
        Regex("""docs/[A-Za-z0-9_./-]+""").findAll(text).map { it.value.trimEnd('.', '/', '-') }
            .filter { !it.contains("TALITA-") && it.length > "docs/".length }
            .distinct().toList()

    private fun trackedFiles(): List<String>? = try {
        val process = ProcessBuilder("git", "ls-files", "-z").directory(root.toFile()).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) null else output.split('\u0000').filter { it.isNotEmpty() }
    } catch (_: java.io.IOException) {
        null
    }
}
