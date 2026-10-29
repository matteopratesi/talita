package talita.ui

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.tomcat.util.http.fileupload.impl.SizeException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.server.PathContainer
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.pattern.PathPatternParser
import talita.account.PublicPaths

/**
 * A form with a file whose body is bigger than the container takes (`spring.servlet.multipart.max-request-size`, 20 MB: TALITA-12). Tomcat stops reading and **throws**
 * (`InvalidParameterException`, caused by a `SizeException`) from `getParameter`, which the filter of the CSRF code of Spring Security is the first to call: without this the
 * exception would cross the whole chain, and the person would see the page «Pagina non trovata» of the answer 413, or a closed connection. This is how the case is
 * recognised ([TooLargeUploadFilter]) and how the answer is given: back to the page of the form (303, a GET of the same address), which finds in the session that the file was
 * too big and says so under its field, as it does for a file that is too big for the application but not for the container (the same words and the same look: one error, one
 * appearance).
 *
 * The addresses that have such a form are listed in [forms]: a ticket that adds a form with a file adds its address here. Nothing else changes for the other forms.
 */
object TooLargeUpload {

    private const val KEY = "talita.upload.too-large"

    /** The addresses of the forms that carry a file: the page of the form and its POST are the same address. */
    val forms: List<String> = listOf("/convivenza/{id:[0-9]{1,18}}/menu")

    private val parsed = forms.map(PathPatternParser.defaultInstance::parse)

    /** [path] is the address of a form with a file. */
    fun isForm(path: String): Boolean = path.startsWith("/") && !path.startsWith("//") && !path.contains('\\') &&
        PathContainer.parsePath(path).let { container -> parsed.any { it.matches(container) } }

    /** [failure], or what caused it, is the container saying that the body of the request is bigger than it takes. */
    fun isTooLarge(failure: Throwable): Boolean = generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }.any { it is SizeException }

    /**
     * Leaves in the session that the file sent to [path] was too big. Only if there is a session: a person who is inside has one, and one who is not goes to the access page
     * from the page of the form, so there is nothing to leave and no session is made for them.
     */
    fun put(request: HttpServletRequest, path: String) {
        request.getSession(false)?.setAttribute(KEY, path)
    }

    /** Whether the file sent to [path] was too big, once: the next call finds nothing. */
    fun take(request: HttpServletRequest, path: String): Boolean {
        val session = request.getSession(false) ?: return false
        val left = session.getAttribute(KEY) as? String ?: return false
        session.removeAttribute(KEY)
        return left == path
    }
}

/**
 * Answers a form with a file that is past the limit of the container (see [TooLargeUpload]): 303 to the page of the form, with the note in the session. It stands in the chain
 * of Spring Security before the filter of the CSRF code, which is the first to read the body: it only takes the exception of the size and nothing else, and only for the
 * addresses in [TooLargeUpload.forms]. The body that remains is read and thrown away by Tomcat (`server.tomcat.max-swallow-size`), so the browser gets its answer. Whoever has
 * no access is not told anything of this: the page of the form sends them to the access page, like any other.
 */
class TooLargeUploadFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(TooLargeUploadFilter::class.java)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.method != "POST" || !TooLargeUpload.isForm(PublicPaths.pathOf(request))

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        try {
            chain.doFilter(request, response)
        } catch (failure: Exception) {
            if (!TooLargeUpload.isTooLarge(failure) || response.isCommitted) throw failure
            val path = PublicPaths.pathOf(request)
            // The size and the address: nothing of the file, not even its name (Tomcat did not read it).
            log.info("Upload refused: {} bytes is over the limit of the request, on {}.", request.contentLengthLong, path)
            TooLargeUpload.put(request, path)
            response.status = HttpStatus.SEE_OTHER.value()
            response.setHeader(HttpHeaders.LOCATION, path)
        }
    }
}
