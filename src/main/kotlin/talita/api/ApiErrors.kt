package talita.api

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

/**
 * **An error of the APIs** (TALITA-21, criterion 9): the body is `{"error": "...", "fields": {"field": "sentence"}}` (`fields` only with 422) and the code is one of 400, 401, 404, 405, 409, 413, 415, 422, 429 and 500.
 * Whoever throws it ([ApiProblem]) decides the code and the sentence; what comes out of the framework (an address that does not exist, a wrong method, a body that is not JSON) is turned into one by [ApiResponseFilter].
 */
class ApiProblem(val status: Int, override val message: String, val fields: Map<String, String>? = null) : RuntimeException(message)

/** The words of the errors that are not about a field, written once. */
object ApiTexts {
    const val UNAUTHORIZED = "Token mancante o non valido."
    const val BAD_REQUEST = "Richiesta non valida."
    const val NOT_FOUND = "Non c’è niente a questo indirizzo."
    const val METHOD = "Questo indirizzo non accetta questo metodo."
    const val TOO_LARGE = "Il corpo è troppo grande: al massimo 64 KB."
    const val NOT_JSON = "Il corpo deve essere JSON, con Content-Type: application/json."
    const val SERVER = "Qualcosa non ha funzionato."
    const val INVALID = "Alcuni campi non sono validi."

    fun tooMany(seconds: Long) = "Troppe richieste: riprova fra $seconds secondi."

    /** The sentence of a code that comes from the framework and has no sentence of ours. */
    fun forStatus(status: Int): String = when (status) {
        HttpServletResponse.SC_UNAUTHORIZED -> UNAUTHORIZED
        HttpServletResponse.SC_NOT_FOUND -> NOT_FOUND
        HttpServletResponse.SC_METHOD_NOT_ALLOWED -> METHOD
        HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE -> TOO_LARGE
        HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE -> NOT_JSON
        in 500..599 -> SERVER
        else -> BAD_REQUEST
    }
}

/** Writes an error in JSON, the same way from everywhere: the filter, the advice and the 401 (which must be the same bytes whatever was wrong with the token). */
object ApiErrors {

    internal val json: JsonMapper = JsonMapper.builder().build()

    fun write(response: HttpServletResponse, status: Int, message: String, fields: Map<String, String>? = null, headers: Map<String, String> = emptyMap()) {
        if (response.isCommitted) return
        response.reset()
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("X-Content-Type-Options", "nosniff")
        headers.forEach { (name, value) -> response.setHeader(name, value) }
        val body = LinkedHashMap<String, Any>()
        body["error"] = message
        if (fields != null) body["fields"] = fields
        response.outputStream.write(json.writeValueAsBytes(body))
    }

    /** The answer for every token that does not work, whatever was wrong with it: byte for byte the same (criterion 8). */
    fun unauthorized(response: HttpServletResponse) {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, ApiTexts.UNAUTHORIZED, headers = mapOf("WWW-Authenticate" to "Bearer"))
    }
}

/**
 * The first filter of the chain of the APIs: it gives every answer, errors too, the headers of the APIs, turns what the framework answers with `sendError` (404 for an address that does not exist, 405, 415…)
 * into a JSON error, and turns what escapes into a 500 with no detail (criterion 9). No answer of `/api/…` is a page.
 */
class ApiResponseFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(ApiResponseFilter::class.java)

    private class JsonErrors(response: HttpServletResponse) : HttpServletResponseWrapper(response) {
        override fun sendError(sc: Int) = fail(sc)

        override fun sendError(sc: Int, msg: String?) = fail(sc)

        private fun fail(status: Int) = ApiErrors.write(response as HttpServletResponse, status, ApiTexts.forStatus(status))
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("X-Content-Type-Options", "nosniff")
        try {
            chain.doFilter(request, JsonErrors(response))
        } catch (e: Exception) {
            // The type of the exception and nothing else: its message could carry a text of a body.
            log.error("API: request failed ({}).", (e.cause ?: e).javaClass.simpleName)
            ApiErrors.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ApiTexts.SERVER)
        }
    }
}

/** A body that goes past the limit while it is read (a request that has no `Content-Length`). */
class BodyTooLargeException : IOException("body too large")

/** The request with a body that cannot be longer than [max] bytes: reading past it throws [BodyTooLargeException]. */
class LimitedRequest(request: HttpServletRequest, private val max: Int) : HttpServletRequestWrapper(request) {

    override fun getInputStream(): ServletInputStream {
        val inner = super.getInputStream()
        return object : ServletInputStream() {
            private var read = 0

            private fun count(bytes: Int): Int {
                if (bytes > 0) {
                    read += bytes
                    if (read > max) throw BodyTooLargeException()
                }
                return bytes
            }

            override fun read(): Int = inner.read().also { if (it >= 0) count(1) }

            override fun read(b: ByteArray, off: Int, len: Int): Int = count(inner.read(b, off, len))

            override fun isFinished(): Boolean = inner.isFinished

            override fun isReady(): Boolean = inner.isReady

            override fun setReadListener(listener: ReadListener?) = inner.setReadListener(listener)
        }
    }

    companion object {
        const val MAX_BYTES = 64 * 1024
    }
}

/**
 * What the controllers of the APIs say when they fail: [ApiProblem] (their own sentence and code), a body that went past the limit or is not JSON, and the rest as a 500 with no detail. It covers the
 * handlers of this package; what fails before a handler is found is [ApiResponseFilter]'s.
 */
@RestControllerAdvice(basePackages = ["talita.api"])
class ApiExceptionAdvice {

    private val log = LoggerFactory.getLogger(ApiExceptionAdvice::class.java)

    private fun answer(status: Int, message: String, fields: Map<String, String>? = null): ResponseEntity<Map<String, Any>> {
        val body = LinkedHashMap<String, Any>()
        body["error"] = message
        if (fields != null) body["fields"] = fields
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).header("Cache-Control", "no-store").body(body)
    }

    @ExceptionHandler(ApiProblem::class)
    fun problem(e: ApiProblem): ResponseEntity<Map<String, Any>> = answer(e.status, e.message, e.fields)

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class, org.springframework.web.bind.MissingServletRequestParameterException::class)
    fun unreadable(e: Exception): ResponseEntity<Map<String, Any>> {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is BodyTooLargeException) return answer(HttpStatus.PAYLOAD_TOO_LARGE.value(), ApiTexts.TOO_LARGE)
            cause = cause.cause
        }
        return answer(HttpStatus.BAD_REQUEST.value(), ApiTexts.BAD_REQUEST)
    }

    @ExceptionHandler(Exception::class)
    fun failed(e: Exception): ResponseEntity<Map<String, Any>> {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is BodyTooLargeException) return answer(HttpStatus.PAYLOAD_TOO_LARGE.value(), ApiTexts.TOO_LARGE)
            cause = cause.cause
        }
        // The type of the exception and nothing else: its message could carry a text of a body.
        log.error("API: request failed ({}).", e.javaClass.simpleName)
        return answer(HttpStatus.INTERNAL_SERVER_ERROR.value(), ApiTexts.SERVER)
    }
}

/**
 * Reads the body of a request of the APIs, strictly (criterion 9): it must be a JSON object, every field it has must be one of the class of the request (a field that does not exist is a 400 that names it),
 * the types must be the right ones (a text where a text is expected, a whole number where a number is) and the fields in [required] must be there: **a `PUT` writes every field**, so one that is missing is a 400
 * and not a field that is emptied by mistake. A field may be `null` (or, for a text, empty) when it is not required to be there with a value: the checks of the area say the rest.
 */
object ApiBody {

    fun <T : Any> read(raw: ByteArray, type: KClass<T>, required: Set<String>): T {
        if (raw.isEmpty()) throw ApiProblem(400, "Il corpo deve essere un oggetto JSON.")
        val tree = try {
            ApiErrors.json.readTree(raw)
        } catch (e: tools.jackson.core.JacksonException) {
            throw ApiProblem(400, "Il corpo non è JSON valido.")
        }
        if (tree == null || !tree.isObject) throw ApiProblem(400, "Il corpo deve essere un oggetto JSON.")
        val constructor = checkNotNull(type.primaryConstructor)
        val parameters = constructor.parameters.associateBy { checkNotNull(it.name) }
        for (name in tree.propertyNames()) if (name !in parameters) throw ApiProblem(400, "Il campo «$name» non esiste.")
        for (name in required) if (!tree.has(name)) throw ApiProblem(400, "Manca il campo «$name».")
        val values = HashMap<KParameter, Any?>()
        for ((name, parameter) in parameters) {
            if (!tree.has(name)) continue
            val node: JsonNode = tree.get(name)
            values[parameter] = convert(name, parameter, node)
        }
        return constructor.callBy(values)
    }

    private fun convert(name: String, parameter: KParameter, node: JsonNode): Any? {
        val wrong = ApiProblem(400, "Il campo «$name» non ha il tipo giusto.")
        if (node.isNull) {
            if (!parameter.type.isMarkedNullable) throw wrong
            return null
        }
        return when (parameter.type.classifier) {
            String::class -> if (node.isString) node.stringValue() else throw wrong
            Int::class -> if (node.isIntegralNumber && node.canConvertToInt()) node.intValue() else throw wrong
            Long::class -> if (node.isIntegralNumber && node.canConvertToLong()) node.longValue() else throw wrong
            Boolean::class -> if (node.isBoolean) node.booleanValue() else throw wrong
            else -> throw IllegalStateException("type of the field $name is not supported")
        }
    }
}
