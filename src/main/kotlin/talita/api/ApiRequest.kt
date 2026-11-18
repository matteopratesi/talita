package talita.api

import kotlin.reflect.KClass

/**
 * Says which class holds the fields of the body of a route of the APIs (TALITA-21): the handler reads the body with [ApiBody.read] and this names the class, so that a test (`ApiDocsTests`) can compare
 * the fields of the class with the ones that `docs/06-api.md` lists for the route.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ApiRequest(val type: KClass<*>)
