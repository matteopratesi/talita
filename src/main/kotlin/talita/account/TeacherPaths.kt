package talita.account

import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

/**
 * The addresses that only a teacher opens: the gathering, the arrivals, the list of the teachers (with its actions) and the correction of their name. For
 * anyone else (a parent) they answer 403, the page «Non puoi vedere questa pagina», or for a request that
 * does not change the page the message «Accesso negato». Every other address that asks for a login is
 * open to every account (the home, the registrations and what is under them, the profile and the
 * invitation: what a parent sees of the registrations is filtered by TALITA-5, with its own tests).
 *
 * The one rule: [SecurityConfiguration] reads this list, and a test (`TeacherPathsTests`) tries a parent
 * on every address. A ticket that adds a page for the teachers adds its address here.
 */
object TeacherPaths {

    /** `/profilo/nome` is the correction of their name that a teacher makes in the profile (a POST): the profile itself is for everyone. */
    val patterns: List<String> = listOf("/convivenza/**", "/arrivi/**", "/maestri/**", "/profilo/nome")

    private val parsed = patterns.map(PathPatternParser.defaultInstance::parse)

    /** True if [path] is one that only a teacher opens. */
    fun matches(path: String): Boolean = PathContainer.parsePath(path).let { container -> parsed.any { it.matches(container) } }
}
