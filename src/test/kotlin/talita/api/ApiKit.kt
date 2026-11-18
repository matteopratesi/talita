package talita.api

import org.jooq.DSLContext
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request
import talita.Addresses
import talita.account.TestAccounts
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.API_REQUEST
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** A teacher of the tests of the APIs: the address, the id of the account and a token that works (made straight with the service, like the set-up of a test). */
class ApiTeacher(val email: String, val id: Long, val token: String)

/**
 * What the tests of the APIs have in common (TALITA-21): teachers with a token, a call of the APIs with `Authorization: Bearer`, and the reading of the JSON answer. The names of the people are synthetic.
 * Every test cleans what it made.
 */
class ApiKit(private val mockMvc: MockMvc, private val dsl: DSLContext, private val tokens: ApiTokens) {

    private val made = mutableListOf<String>()

    val json: JsonMapper = JsonMapper.builder().build()

    fun teacher(name: String = "maestro-api", first: String = "Marta", last: String = "Bianchi"): ApiTeacher {
        val email = Addresses.unique(name).also { made += it }
        val id = TestAccounts.makeTeacher(dsl, email, first, last)
        return ApiTeacher(email, id, checkNotNull(tokens.create(id)).value)
    }

    fun call(method: HttpMethod, path: String, token: String?, body: String? = null, contentType: MediaType? = MediaType.APPLICATION_JSON, configure: org.springframework.test.web.servlet.MockHttpServletRequestDsl.() -> Unit = {}): MvcResult =
        mockMvc.request(method, path) {
            if (token != null) header("Authorization", "Bearer $token")
            if (body != null) {
                content = body
                if (contentType != null) this.contentType = contentType
            }
            configure()
        }.andReturn()

    fun get(path: String, token: String?): MvcResult = call(HttpMethod.GET, path, token)

    fun put(path: String, token: String?, body: String): MvcResult = call(HttpMethod.PUT, path, token, body)

    fun post(path: String, token: String?, body: String): MvcResult = call(HttpMethod.POST, path, token, body)

    fun delete(path: String, token: String?): MvcResult = call(HttpMethod.DELETE, path, token)

    fun body(result: MvcResult): String = result.response.contentAsString

    fun tree(result: MvcResult): JsonNode = json.readTree(body(result))

    /** The text of an error: `{"error": "…"}`. */
    fun error(result: MvcResult): String = tree(result).get("error").stringValue()

    /** The register of the requests as the tests read it: method, route and code, the oldest first. */
    fun register(): List<Triple<String, String, Int>> =
        dsl.select(API_REQUEST.METHOD, API_REQUEST.ROUTE, API_REQUEST.STATUS).from(API_REQUEST).orderBy(API_REQUEST.ID).fetch { Triple(it[API_REQUEST.METHOD]!!, it[API_REQUEST.ROUTE]!!, it[API_REQUEST.STATUS]!!) }

    fun clearRegister() {
        dsl.deleteFrom(API_REQUEST).execute()
    }

    /** Takes away the accounts of the test (the token goes with the teacher, by the cascade). */
    fun clean() {
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.execute("delete from teacher where account_id = any(?)", ids.toTypedArray())
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
    }
}
