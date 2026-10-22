package talita.teacher

import org.jooq.DSLContext
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.account.MockBrowser
import talita.account.MockSignIn
import talita.account.TestAccounts
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.TEACHER
import talita.jooq.tables.references.TEACHER_INVITATION

/** A teacher as a test makes them: the address and the name. */
internal data class Person(val email: String, val first: String, val last: String)

/**
 * What the tests of «Maestri», of the profile and of the failures of the mail have in common: the set-up of the teachers (straight in
 * the database, like an invitation that was accepted yesterday) and the way in, which is the real one (the link of the mail and the tap).
 * Every test begins with exactly the teachers it needs: the other tests make their own when they start.
 */
internal class MaestriSupport(private val mockMvc: MockMvc, tokens: OneTimeTokenService, private val dsl: DSLContext) {

    private val signIn = MockSignIn(mockMvc, tokens, dsl)

    /** Only these teachers, and no invitation at all: the lists of the page are those of the test. */
    fun onlyTeachers(vararg people: Person) {
        dsl.deleteFrom(TEACHER_INVITATION).execute()
        dsl.deleteFrom(TEACHER).execute()
        for (person in people) TestAccounts.makeTeacher(dsl, person.email, person.first, person.last)
    }

    /** A browser of [email] that has entered with the link of the mail. */
    fun browser(email: String): MockBrowser = MockBrowser(mockMvc).also { it.confirm(signIn.link(email)) }

    /** The link of the mail of [email], as the token service makes it. */
    fun link(email: String): String = signIn.link(email)

    fun accountId(email: String): Long = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(email)).fetchSingle(ACCOUNT.ID)!!
}

/** The action of a block of «Maestri», as htmx sends it (or as a form without JavaScript does): the page first, for the CSRF code, then the post. */
internal fun MockBrowser.act(action: String, fields: Map<String, String>, htmx: Boolean = true): MvcResult =
    submit("/maestri", action, fields) { if (htmx) header("HX-Request", "true") }

internal fun MockBrowser.page(path: String): String = get(path).response.contentAsString

internal fun MvcResult.html(): String = response.contentAsString

internal fun MvcResult.location(): String? = response.getHeader("Location")

internal fun heading(html: String): String? =
    Regex("""<h1[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim()

/** The labels of the navigation, in order: what a person sees as their sections. */
internal fun navigation(html: String): List<String> =
    Regex("""<span class="max-w-full truncate">([^<]+)</span>""").findAll(html).map { it.groupValues[1] }.toList()
