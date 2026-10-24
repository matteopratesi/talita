package talita.account

import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

/**
 * The addresses that only a teacher opens: the gathering, the arrivals, the list of the teachers (with its actions), the correction of their name and, of
 * the registrations, the detail, the paper forms, the corrections, the notes and the manleva (TALITA-5). For
 * anyone else (a parent) they answer 403, the page «Non puoi vedere questa pagina», or for a request that
 * does not change the page the message «Accesso negato». Every other address that asks for a login is
 * open to every account (the home, `/iscrizioni` and the addresses of a parent under it, the profile and the
 * invitation: what a parent sees of the registrations is filtered by TALITA-5 (`OwnRegistrations`), with its own tests).
 *
 * The one rule: [SecurityConfiguration] reads this list, and a test (`TeacherPathsTests`) tries a parent
 * on every address. A ticket that adds a page for the teachers adds its address here.
 */
object TeacherPaths {

    /** `/profilo/nome` is the correction of their name that a teacher makes in the profile (a POST): the profile itself is for everyone. */
    val patterns: List<String> = listOf(
        "/convivenza/**", "/arrivi/**", "/maestri/**", "/profilo/nome",
        // The registrations of the teachers (TALITA-5). `/iscrizioni` itself is open to every account (the controller gives the list of the teachers to
        // a teacher and the cards to a parent), and so are `/iscrizioni/{id}/modifica`, `/segnalazioni`, `/segnalazioni/togli` and `/ritira`, which a
        // parent uses (a teacher withdraws any registration from the last one).
        "/iscrizioni/nuova", "/iscrizioni/nuova/quota", "/iscrizioni/{id:[0-9]+}", "/iscrizioni/{id:[0-9]+}/mostra-segnalazioni", "/iscrizioni/{id:[0-9]+}/correggi",
        "/iscrizioni/{id:[0-9]+}/correggi/quota", "/iscrizioni/{id:[0-9]+}/correggi-segnalazioni", "/iscrizioni/{id:[0-9]+}/manleva",
    )

    private val parsed = patterns.map(PathPatternParser.defaultInstance::parse)

    /** True if [path] is one that only a teacher opens. */
    fun matches(path: String): Boolean = PathContainer.parsePath(path).let { container -> parsed.any { it.matches(container) } }
}
