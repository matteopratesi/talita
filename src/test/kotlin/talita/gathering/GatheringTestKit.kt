package talita.gathering

import org.jooq.DSLContext
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.Addresses
import talita.account.MockBrowser
import talita.jooq.tables.references.GATHERING
import talita.teacher.MaestriSupport
import talita.teacher.Person
import java.time.Clock

/**
 * The tools of the page tests of the gathering: a teacher who has entered with the link (the real way in), the table of the gatherings in
 * the hands of the test, and a few readings of a page. The tests of the teachers' pages, of the steps of the state, of the custodians and of
 * the public page all begin here.
 */
internal class GatheringTestKit(private val mockMvc: MockMvc, tokens: OneTimeTokenService, private val dsl: DSLContext, clock: Clock) {

    val gatherings = GatheringSupport(dsl, clock)

    private val teachers = MaestriSupport(mockMvc, tokens, dsl)

    /** Only these teachers, and no gathering at all; the first of them has entered. Returns their browser and the id of their account. */
    fun start(me: Person = Person(Addresses.unique("conv-maestro"), "Elisa", "Conti"), vararg others: Person): Pair<MockBrowser, Long> {
        teachers.onlyTeachers(me, *others)
        gatherings.reset()
        return teachers.browser(me.email) to teachers.accountId(me.email)
    }

    fun accountId(email: String): Long = teachers.accountId(email)

    /** The link of the mail of [email], as the token service makes it. */
    fun link(email: String): String = teachers.link(email)

    /** A person who is not a teacher and has entered. */
    fun parent(email: String = Addresses.unique("conv-genitore")): MockBrowser =
        MockBrowser(mockMvc).also { it.confirm(teachers.link(email)) }

    fun idOf(code: String): Long = dsl.select(GATHERING.ID).from(GATHERING).where(GATHERING.PUBLIC_CODE.eq(code)).fetchSingle(GATHERING.ID)!!

    fun codeOf(id: Long): String = dsl.select(GATHERING.PUBLIC_CODE).from(GATHERING).where(GATHERING.ID.eq(id)).fetchSingle(GATHERING.PUBLIC_CODE)!!

    fun count(): Int = dsl.fetchCount(GATHERING)
}

internal fun MvcResult.body(): String = response.contentAsString

internal fun MvcResult.status(): Int = response.status

internal fun MvcResult.location(): String? = response.getHeader("Location")

internal fun heading(html: String): String? = Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()

internal fun title(html: String): String? = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)

/** The visible text of a page, roughly: tags out, entities left as they are, spaces squeezed. Enough to look for a sentence. */
internal fun text(html: String): String =
    html.replace(Regex("""<(script|style|template)[\s\S]*?</\1>"""), " ").replace(Regex("""<[^>]+>"""), " ").replace(Regex("""\s+"""), " ")
        .replace("&#39;", "’").replace("&quot;", "\"").replace("&amp;", "&").trim()

/** The error under a field, by its id, as the page writes it (empty if none). */
internal fun errorOf(html: String, field: String): String? =
    Regex("""<p id="$field-errore"[^>]*>([\s\S]*?)</p>""").find(html)?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), "")?.replace(Regex("""\s+"""), " ")?.trim()

/** The value of a field (input or textarea) by its id, with the entities of the page undone. */
internal fun valueOf(html: String, field: String): String? {
    val input = Regex("""<input id="$field"[^>]*?\svalue="([^"]*)"""").find(html)?.groupValues?.get(1)
    val area = Regex("""<textarea id="$field"[^>]*>([\s\S]*?)</textarea>""").find(html)?.groupValues?.get(1)
    return (input ?: area)?.replace("&#39;", "'")?.replace("&quot;", "\"")?.replace("&#34;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
}
