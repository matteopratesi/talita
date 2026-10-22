package talita.account

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.server.PathContainer
import org.springframework.web.util.pattern.PathPatternParser

/**
 * The addresses that anyone can open without being inside. Everything else asks for a login: a
 * person without one is sent to `/accedi`, whatever the address (even one that does not exist).
 *
 * This is the only list. A ticket that adds a public page (the information page of the gathering,
 * TALITA-4) adds it here, and the test of the list (`PublicPathsTests`) says so if it forgets.
 */
object PublicPaths {

    /** Pages: the login pages, the health of the application (for whoever watches it), the error page. */
    val pages: List<String> = listOf("/accedi/**", "/actuator/health", "/error")

    /**
     * The static files of the app. The fingerprint in the name (`/css/app-<hash>.css`) is added by
     * the resource chain, so `/favicon-*.svg` is the favicon. Served with the cache of always: a year.
     */
    val staticFiles: List<String> = listOf("/css/**", "/js/**", "/fonts/**", "/icons/**", "/favicon.svg", "/favicon-*.svg")

    val all: List<String> = pages + staticFiles

    private val parser = PathPatternParser.defaultInstance

    private val staticPatterns = staticFiles.map(parser::parse)

    private val allPatterns = all.map(parser::parse)

    /** The path of the request without the context path and the query. */
    fun pathOf(request: HttpServletRequest): String {
        val uri = request.requestURI
        val context = request.contextPath
        return if (context.isNotEmpty() && uri.startsWith(context)) uri.substring(context.length) else uri
    }

    /** True if [path] (the path of the request, without the query) is one of the public ones. */
    fun matches(path: String): Boolean = matches(allPatterns, path)

    /** True if [path] is a static file. */
    fun isStaticFile(path: String): Boolean = matches(staticPatterns, path)

    private fun matches(patterns: List<org.springframework.web.util.pattern.PathPattern>, path: String): Boolean {
        val container = PathContainer.parsePath(path)
        return patterns.any { it.matches(container) }
    }
}
