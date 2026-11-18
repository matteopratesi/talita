package talita.api

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import talita.jooq.tables.references.API_REQUEST
import java.time.Clock
import java.time.OffsetDateTime

/** A line of the register of the requests: the moment, the method, the route as a model and the code of the answer. Nothing else, and never who. */
data class ApiRequestRow(val at: OffsetDateTime, val method: String, val route: String, val status: Int)

/**
 * **The register of the requests made with a valid token, and the only class that reads or writes its table** (TALITA-21, criterion 12): the moment, the method, the route as a model
 * (`/api/strutture/{id}/camere`, never the address that was asked for) and the code. It does not say which token, which teacher, which body, which address of the network or which browser. A request
 * with no valid token writes nothing. The job deletes the rows after [talita.retention.RetentionPolicy.apiRequests].
 */
@Component
class ApiRequests(private val dsl: DSLContext, private val clock: Clock) {

    fun add(method: String, route: String, status: Int) {
        if (method !in METHODS) return
        dsl.insertInto(API_REQUEST)
            .set(API_REQUEST.AT, OffsetDateTime.now(clock))
            .set(API_REQUEST.METHOD, method)
            .set(API_REQUEST.ROUTE, route.take(MAX_ROUTE))
            .set(API_REQUEST.STATUS, status)
            .execute()
    }

    /** The last [count] requests, the most recent first. */
    fun last(count: Int = 20): List<ApiRequestRow> =
        dsl.select(API_REQUEST.AT, API_REQUEST.METHOD, API_REQUEST.ROUTE, API_REQUEST.STATUS).from(API_REQUEST).orderBy(API_REQUEST.AT.desc(), API_REQUEST.ID.desc()).limit(count)
            .fetch { ApiRequestRow(it[API_REQUEST.AT]!!, it[API_REQUEST.METHOD]!!, it[API_REQUEST.ROUTE]!!, it[API_REQUEST.STATUS]!!) }

    /** Deletes what is older than [from] (the job). Returns how many rows went. */
    fun removeBefore(context: DSLContext, from: OffsetDateTime): Int = context.deleteFrom(API_REQUEST).where(API_REQUEST.AT.lt(from)).execute()

    companion object {
        val METHODS = setOf("GET", "POST", "PUT", "DELETE")
        private const val MAX_ROUTE = 100

        /** The route of a request that no handler took (an address that does not exist): the register has a line all the same. */
        const val UNKNOWN_ROUTE = "/api/{altro}"

        /** The route of a handler as a model: without the pattern that limits the number (`{id:[0-9]{1,18}}` is `{id}`). */
        fun model(pattern: String): String = pattern.replace(":[0-9]{1,18}", "").replace(":[1-9][0-9]{0,17}", "")

        /** The last fixed segment of a route as a model: `/api/strutture/{id}/camere/{camera}` is «camere». What the profile shows. */
        fun label(route: String): String =
            if (route == UNKNOWN_ROUTE) "altro" else route.split('/').lastOrNull { it.isNotEmpty() && !it.startsWith("{") } ?: "api"
    }
}
