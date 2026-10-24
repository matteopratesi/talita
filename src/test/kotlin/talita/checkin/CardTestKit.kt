package talita.checkin

import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MvcResult
import talita.account.MockBrowser
import talita.gathering.body
import java.time.LocalDate

/** The header that htmx adds to a request: the answer is then the block of the card and not the whole page. */
internal fun htmx(): MockHttpServletRequestDsl.() -> Unit = { header("HX-Request", "true") }

/** The card of a registration as a link opens it, for the day of the address if there is one. */
internal fun MockBrowser.card(id: Long, day: LocalDate? = null): String = get("/arrivi/$id" + (day?.let { "?giorno=$it" } ?: "")).body()

/**
 * A touch on the card as the page sends it (or, with [viaHtmx] false, as a form without JavaScript does): a page that always has a form is asked for first, for the
 * code against forgery and the session (the list has no form before the start, nor when nobody is registered), and the fields are those of the form; the day of the
 * address is [CheckinSetup.today] unless the fields say another.
 */
internal fun MockBrowser.touch(setup: CheckinSetup, path: String, fields: Map<String, String>, viaHtmx: Boolean = true): MvcResult =
    submit("/profilo", path, mapOf("giorno" to setup.today.toString()) + fields, if (viaHtmx) htmx() else ({}))

/** The request code that the form of the payments of this page carries. */
internal fun requestCodeIn(html: String): String = checkNotNull(Regex("""name="richiesta" value="([0-9a-f-]{36})"""").find(html)?.groupValues?.get(1)) { "the page has no request code" }
