package talita.api

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import talita.Addresses
import talita.CapturesOutput
import talita.MutableClock
import talita.TestcontainersConfiguration
import talita.account.Confirmed
import talita.account.PasskeyConfirmations
import talita.account.PasskeyKit
import talita.account.Phone
import talita.account.SoftPasskey
import talita.account.TestAccounts
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.API_REQUEST
import talita.jooq.tables.references.API_TOKEN
import talita.jooq.tables.references.PASSKEY
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.retention.RetentionJob
import talita.retention.RetentionPolicy
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The token for the APIs of configuration and the shell around the APIs (TALITA-21, part 1, criteria 1-12): how it is made and shown, what replaces it, how long it works, what the chain of the APIs
 * accepts and refuses, the errors, the limit, the log and the register. Teachers, addresses and values are synthetic; the token that a test uses is made by the service, except where the test is about
 * the page that makes it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiTokenTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val oneTimeTokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: MutableClock,
    @Autowired private val tokens: ApiTokens,
    @Autowired private val policy: RetentionPolicy,
    @Autowired private val job: RetentionJob,
) {

    private val kit = ApiKit(mockMvc, dsl, tokens)
    private val made = mutableListOf<String>()

    @BeforeEach
    fun start() {
        kit.clearRegister()
    }

    @AfterEach
    fun clean() {
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.`in`(ids)).execute()
        dsl.execute("delete from teacher where account_id = any(?)", ids.toTypedArray())
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
        kit.clean()
        kit.clearRegister()
    }

    private fun phone() = Phone(mockMvc, oneTimeTokens)

    /** A teacher at their phone, who has entered with a link. [passkey]: with the passkey of a soft phone. */
    private class AtPhone(val email: String, val id: Long, val phone: Phone, val soft: SoftPasskey?)

    private fun teacherAtPhone(passkey: Boolean = false, name: String = "maestro-token"): AtPhone {
        val email = Addresses.unique(name).also { made += it }
        val id = TestAccounts.makeTeacher(dsl, email)
        val phone = phone()
        phone.enter(email)
        val soft = if (passkey) SoftPasskey(TestcontainersConfiguration.PUBLIC_URL, "talita.example.org").also { assertEquals(200, PasskeyKit.add(phone, it).response.status) } else null
        return AtPhone(email, id, phone, soft)
    }

    private fun htmx(t: AtPhone, action: String, fields: Map<String, String> = emptyMap(), page: String = "/profilo") = t.phone.submit(page, action, fields) { header("HX-Request", "true") }

    private val valueInPage = Regex("""Authorization: Bearer (talita_[A-Za-z0-9_-]{43})""")

    private fun tokenIn(html: String): String = checkNotNull(valueInPage.find(html)) { "no token in the page" }.groupValues[1]

    private fun rows(accountId: Long) = dsl.fetchCount(API_TOKEN, API_TOKEN.ACCOUNT_ID.eq(accountId))

    private fun text(html: String) = html.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ")

    // ---- The token (criteria 1-5) -----------------------------------------------------------------------------------------------------

    @Test
    fun `t21 c01 a teacher makes a token from the profile and the answer shows it once, with no-store and hx-history false, and the database holds only its hash`() {
        val t = teacherAtPhone()

        val answer = htmx(t, "/profilo/token")

        assertEquals(200, answer.response.status)
        val html = answer.response.contentAsString
        val value = tokenIn(html)
        assertEquals("no-store", answer.response.getHeader("Cache-Control"))
        assertTrue(html.contains("""hx-history="false""""))
        assertTrue(text(html).contains("Token creato. Copialo adesso: dopo questa schermata non si vede più."))
        assertTrue(html.contains("umask 077; mkdir -p ~/.config/talita &amp;&amp; rm -f ~/.config/talita/dev.header &amp;&amp; cat &gt; ~/.config/talita/dev.header") || html.contains("umask 077; mkdir -p ~/.config/talita && rm -f ~/.config/talita/dev.header && cat > ~/.config/talita/dev.header"), "the command has no token and names the file of the configuration")
        assertFalse(html.substringAfter("token-comando").substringBefore("</p>").contains("talita_"), "the command does not hold the token")
        // The value is in a text to select: never in an attribute or an address.
        assertFalse(Regex("""(href|value|data-[a-z-]+|action|hx-[a-z-]+)="[^"]*$value""").containsMatchIn(html))
        // The database holds the hash and nothing from which the value comes back.
        val row = dsl.selectFrom(API_TOKEN).where(API_TOKEN.ACCOUNT_ID.eq(t.id)).fetchSingle()
        assertTrue(ApiTokens.hash(value).contentEquals(row.tokenHash!!))
        assertEquals(LocalDate.now(clock), row.createdOn)
        // The profile that is asked for again, or came back, does not have it.
        assertFalse(t.phone.body("/profilo").contains(value))
        assertNotNull(tokens.authenticate(value))
    }

    @Test
    fun `t21 c01 without JavaScript the answer that makes the token is the whole profile, 200 and no-store`() {
        val t = teacherAtPhone()

        val answer = t.phone.submit("/profilo", "/profilo/token")

        assertEquals(200, answer.response.status)
        assertEquals("no-store", answer.response.getHeader("Cache-Control"))
        val html = answer.response.contentAsString
        assertNotNull(valueInPage.find(html))
        assertTrue(html.contains("""id="accesso""""), "the whole page")
    }

    @Test
    fun `t21 c02 a teacher who has a passkey needs the confirmation, even by hand, and a teacher without one does not`() {
        val withKey = teacherAtPhone(passkey = true)

        val refused = htmx(withKey, "/profilo/token")

        assertEquals(409, refused.response.status)
        assertTrue(text(refused.response.contentAsString).contains("Serve la conferma. Per creare un token il telefono ti chiede l’impronta, il volto o il codice di sblocco. Tocca di nuovo «Crea un token»."))
        assertEquals(0, rows(withKey.id))
        // By hand, without htmx: the same 409 with the whole page.
        val byHand = withKey.phone.submit("/profilo", "/profilo/token")
        assertEquals(409, byHand.response.status)
        assertEquals(0, rows(withKey.id))
        // The button carries what the script of the passkeys reads, and the outcomes of the phone are in the block.
        val page = withKey.phone.body("/profilo")
        assertTrue(page.contains("""data-conferma="token""""))
        assertTrue(page.contains("""data-conferma-esito="token-annullata"""") && page.contains("""data-conferma-esito="token-rifiutata""""))
        // With the confirmation (a valid one in the session) the token is made.
        withKey.phone.session!!.setAttribute(PasskeyConfirmations.CONFIRMED, Confirmed(withKey.id, Instant.now(clock)))
        assertEquals(200, htmx(withKey, "/profilo/token").response.status)
        assertEquals(1, rows(withKey.id))

        val without = teacherAtPhone(name = "maestro-senza-passkey")
        assertEquals(200, htmx(without, "/profilo/token").response.status)
        assertFalse(without.phone.body("/profilo").contains("""data-conferma="token""""), "no confirmation is asked of who has no passkey")
    }

    @Test
    fun `t21 c03 a new token replaces the old one at once, Revoca stops the token, a Revoca about an old one changes nothing, and Esci da tutti gli altri dispositivi takes the token too`() {
        val t = teacherAtPhone()
        val first = tokenIn(htmx(t, "/profilo/token").response.contentAsString)
        assertEquals(200, kit.get("/api/convivenza", first).response.status)

        val firstId = dsl.select(API_TOKEN.ID).from(API_TOKEN).where(API_TOKEN.ACCOUNT_ID.eq(t.id)).fetchSingle(API_TOKEN.ID)!!
        val second = tokenIn(htmx(t, "/profilo/token", mapOf("azione" to "nuovo")).response.contentAsString)

        assertNotEquals(first, second)
        assertEquals(1, rows(t.id), "one token for each teacher")
        assertEquals(401, kit.get("/api/convivenza", first).response.status)
        assertEquals(200, kit.get("/api/convivenza", second).response.status)
        // A double tap, or a page left open: «Revoca» with the number of the old token touches nothing and says so.
        val stale = htmx(t, "/profilo/token/revoca", mapOf("token" to firstId.toString()))
        assertEquals(200, stale.response.status)
        assertTrue(text(stale.response.contentAsString).contains("Quel token era già stato revocato o sostituito. Qui sotto trovi quello di adesso."))
        assertEquals(200, kit.get("/api/convivenza", second).response.status)
        // «Revoca» with the number of the token of now.
        val secondId = dsl.select(API_TOKEN.ID).from(API_TOKEN).where(API_TOKEN.ACCOUNT_ID.eq(t.id)).fetchSingle(API_TOKEN.ID)!!
        val revoked = htmx(t, "/profilo/token/revoca", mapOf("token" to secondId.toString()))
        assertTrue(text(revoked.response.contentAsString).contains("Token revocato. Il programma non può più fare modifiche con quel token. Quello che ha già fatto resta. Se ti serve ancora, crea un token nuovo."))
        assertEquals(401, kit.get("/api/convivenza", second).response.status)

        // «Esci da tutti gli altri dispositivi»: the dialog says it, the token goes with the devices, and the card says so out of order.
        val third = tokenIn(htmx(t, "/profilo/token").response.contentAsString)
        val page = t.phone.body("/profilo")
        assertTrue(text(page).contains("Il token per le API viene revocato: nessun programma potrà più fare modifiche finché non ne crei uno nuovo."))
        assertTrue(text(page).contains("Revoca anche il token per le API."))
        val exited = htmx(t, "/profilo/dispositivi/esci-altri", mapOf("azione" to "esci-altri"))
        val answer = exited.response.contentAsString
        assertTrue(text(answer).contains("Questo resta dentro. Anche il token per le API è stato revocato."))
        assertTrue(answer.contains("""hx-swap-oob="true"""") && text(answer).contains("Sei uscito da tutti gli altri dispositivi e il token è andato con loro. Se ti serve ancora, creane uno nuovo."))
        assertEquals(1, Regex("data-fuoco").findAll(answer).count(), "one notice takes the focus")
        assertEquals(401, kit.get("/api/convivenza", third).response.status)
    }

    @Test
    fun `t21 c03 the dialog and the line of Esci da tutti gli altri dispositivi say nothing of the token when there is none`() {
        val t = teacherAtPhone()

        val page = t.phone.body("/profilo")

        assertFalse(text(page).contains("Il token per le API viene revocato"))
        assertFalse(text(page).contains("Revoca anche il token per le API."))
        val exited = htmx(t, "/profilo/dispositivi/esci-altri", mapOf("azione" to "esci-altri"))
        assertFalse(exited.response.contentAsString.contains("token per le API"))
    }

    @Test
    fun `t21 c04 a token works through the 30th day after the day it was made and not after, and the card says Attivo, Ultimo giorno or Scaduto`() {
        val t = kit.teacher()
        val today = LocalDate.now(clock)
        fun madeOn(day: LocalDate) = dsl.update(API_TOKEN).set(API_TOKEN.CREATED_ON, day).where(API_TOKEN.ACCOUNT_ID.eq(t.id)).execute()

        madeOn(today.minusDays(29))
        assertEquals(200, kit.get("/api/convivenza", t.token).response.status)
        madeOn(today.minusDays(30))
        assertEquals(200, kit.get("/api/convivenza", t.token).response.status, "the last day, 30 days after the day it was made, still works")
        madeOn(today.minusDays(31))
        assertEquals(401, kit.get("/api/convivenza", t.token).response.status)
        assertEquals(today.minusDays(31).plusDays(30), policy.apiTokenLastDay(today.minusDays(31)))
    }

    @Test
    fun `t21 c04 the profile says when it was made and when it stops, with no last use, and the night job deletes the token past its day without a line in the register of the deletions`() {
        val t = teacherAtPhone()
        htmx(t, "/profilo/token")
        val today = LocalDate.now(clock)
        val life = { day: LocalDate -> dsl.update(API_TOKEN).set(API_TOKEN.CREATED_ON, day).where(API_TOKEN.ACCOUNT_ID.eq(t.id)).execute() }

        val fresh = text(t.phone.body("/profilo"))
        assertTrue(fresh.contains("Token creato il ${talita.gathering.GatheringFormat.date(today)} Attivo Vale fino al ${talita.gathering.GatheringFormat.date(today.plusDays(30))}. Poi smette di funzionare da solo."), fresh)
        assertFalse(fresh.contains("ultimo uso", ignoreCase = true))
        life(today.minusDays(30))
        assertTrue(text(t.phone.body("/profilo")).contains("Ultimo giorno Vale fino a oggi. Da domani smette di funzionare: se ti serve ancora, crea un token nuovo."))
        life(today.minusDays(31))
        val expired = text(t.phone.body("/profilo"))
        assertTrue(expired.contains("Scaduto È scaduto il ${talita.gathering.GatheringFormat.date(today.minusDays(1))}: il programma non può più usarlo."), expired)
        assertTrue(expired.contains("Crea un token nuovo"))

        val before = dsl.fetchCount(talita.jooq.tables.references.RETENTION_LOG)
        val report = job.run()
        assertTrue(report.succeeded)
        assertEquals(0, rows(t.id), "the night job deletes it")
        assertEquals(before, dsl.fetchCount(talita.jooq.tables.references.RETENTION_LOG), "no line in the register of the deletions")
    }

    @Test
    fun `t21 c05 a teacher who is taken away from the teachers loses the token with the row of the teacher, and the next request is a 401`() {
        val t = kit.teacher()
        assertEquals(200, kit.get("/api/strutture", t.token).response.status)

        dsl.execute("delete from teacher where account_id = ?", t.id)

        assertEquals(0, rows(t.id))
        assertEquals(401, kit.get("/api/strutture", t.token).response.status)
    }

    @Test
    fun `t21 c05 a parent has no card, and the two addresses of the token answer 403 by hand`() {
        val email = Addresses.unique("genitore-token").also { made += it }
        val parent = phone().also { it.enter(email) }

        assertFalse(parent.body("/profilo").contains("Token per le API"))
        assertEquals(403, parent.submit("/profilo", "/profilo/token").response.status)
        assertEquals(403, parent.submit("/profilo", "/profilo/token/revoca", mapOf("token" to "1")).response.status)
        assertEquals(403, parent.get("/profilo/token").response.status)
    }

    // ---- The borders (criteria 6-8) --------------------------------------------------------------------------------------------------

    @Test
    fun `t21 c06 the token does not open the pages, a session does not open the APIs, and no answer of the APIs sets a cookie or makes a session`() {
        val t = kit.teacher()

        for (path in listOf("/convivenza", "/profilo", "/iscrizioni")) {
            val result = mockMvc.get(path) { header("Authorization", "Bearer ${t.token}") }.andReturn()
            assertTrue(result.response.status in setOf(302, 303), "$path: ${result.response.status}")
            assertTrue(result.response.getHeader("Location")!!.contains("/accedi"), path)
        }
        val phone = teacherAtPhone(name = "maestro-sessione").phone
        val withCookie = phone.get("/api/convivenza")
        assertEquals(401, withCookie.response.status)
        // A token that works: no Set-Cookie and no session.
        val ok = kit.get("/api/convivenza", t.token)
        assertEquals(200, ok.response.status)
        assertNull(ok.response.getHeader("Set-Cookie"))
        assertTrue(ok.response.cookies.isEmpty())
        assertNull(ok.request.getSession(false))
        val refused = kit.get("/api/convivenza", null)
        assertNull(refused.response.getHeader("Set-Cookie"))
        assertNull(refused.request.getSession(false))
    }

    @Test
    fun `t21 c07 every answer of the APIs, errors too, is JSON with no-store, nosniff and a policy that allows nothing, with no CORS and no CSRF`() {
        val t = kit.teacher()
        val answers = listOf(
            kit.get("/api/convivenza", t.token),
            kit.get("/api/convivenza", null),
            kit.get("/api/non-esiste", t.token),
            kit.delete("/api/strutture", t.token),
            kit.get("/api/convivenza", "talita_" + "x".repeat(43)),
        )

        for (answer in answers) {
            val r = answer.response
            assertTrue(r.contentType!!.startsWith("application/json"), "${r.status}: ${r.contentType}")
            assertEquals("no-store", r.getHeader("Cache-Control"), "${r.status}")
            assertEquals("nosniff", r.getHeader("X-Content-Type-Options"), "${r.status}")
            assertEquals("default-src 'none'; frame-ancestors 'none'", r.getHeader("Content-Security-Policy"), "${r.status}")
            assertNull(r.getHeader("Access-Control-Allow-Origin"))
            assertFalse(r.contentAsString.contains("<"), "no page")
        }
        // A preflight from another site gets no permission; a POST with no CSRF code is not turned away for that (it is turned away for the token).
        val preflight = kit.call(HttpMethod.OPTIONS, "/api/strutture", null, configure = { header("Origin", "https://altro.example.org"); header("Access-Control-Request-Method", "PUT") })
        assertNull(preflight.response.getHeader("Access-Control-Allow-Origin"))
        assertNull(preflight.response.getHeader("Access-Control-Allow-Methods"))
        assertEquals(401, kit.call(HttpMethod.POST, "/api/strutture", null, "{}").response.status)
    }

    @Test
    fun `t21 c08 a token that is missing, of another form, unknown, revoked, past its day or of who is not a teacher is the same answer, byte for byte`() {
        val t = kit.teacher()
        val revoked = kit.teacher("maestro-revocato")
        dsl.deleteFrom(API_TOKEN).where(API_TOKEN.ACCOUNT_ID.eq(revoked.id)).execute()
        val expired = kit.teacher("maestro-scaduto")
        dsl.update(API_TOKEN).set(API_TOKEN.CREATED_ON, LocalDate.now(clock).minusDays(40)).where(API_TOKEN.ACCOUNT_ID.eq(expired.id)).execute()
        val gone = kit.teacher("maestro-tolto")
        dsl.execute("delete from teacher where account_id = ?", gone.id)

        val answers = listOf(
            kit.get("/api/convivenza", null),
            kit.get("/api/convivenza", "corto"),
            kit.get("/api/convivenza", "talita_" + "A".repeat(43)),
            kit.get("/api/convivenza", revoked.token),
            kit.get("/api/convivenza", expired.token),
            kit.get("/api/convivenza", gone.token),
            kit.call(HttpMethod.GET, "/api/convivenza", null, configure = { header("Authorization", "Basic abc") }),
        )

        val first = answers.first().response
        assertEquals(401, first.status)
        assertEquals("Bearer", first.getHeader("WWW-Authenticate"))
        assertEquals("""{"error":"Token mancante o non valido."}""", first.contentAsString)
        for (other in answers) {
            assertEquals(first.status, other.response.status)
            assertEquals(first.contentAsString, other.response.contentAsString)
            assertEquals(first.headerNames.toSet(), other.response.headerNames.toSet())
            assertEquals(first.getHeader("WWW-Authenticate"), other.response.getHeader("WWW-Authenticate"))
        }
        assertEquals(200, kit.get("/api/convivenza", t.token).response.status)
    }

    // ---- Errors and limits (criteria 9, 10) --------------------------------------------------------------------------------------------

    @Test
    fun `t21 c09 an address that does not exist and a wrong method are JSON errors, 404 and 405, never a page`() {
        val t = kit.teacher()

        val missing = kit.get("/api/non-esiste/affatto", t.token)
        assertEquals(404, missing.response.status)
        assertEquals("Non c’è niente a questo indirizzo.", kit.error(missing))
        val wrong = kit.delete("/api/strutture", t.token)
        assertEquals(405, wrong.response.status)
        assertEquals("Questo indirizzo non accetta questo metodo.", kit.error(wrong))
        assertNull(kit.tree(wrong).get("fields"))
        // An address that a route would serve if the number were one is a 404 too.
        assertEquals(404, kit.get("/api/strutture/abc", t.token).response.status)
    }

    @Test
    fun `t21 c10 the 61st request of a token in a minute is a 429 with Retry-After and the words, and it does not touch another token`() {
        val t = kit.teacher()
        val other = kit.teacher("maestro-altro")

        repeat(ApiRateLimit.LIMIT) { assertEquals(200, kit.get("/api/convivenza", t.token).response.status) }
        val over = kit.get("/api/convivenza", t.token)

        assertEquals(429, over.response.status)
        val seconds = over.response.getHeader("Retry-After")!!.toLong()
        assertTrue(seconds in 1..60, "$seconds")
        assertEquals("Troppe richieste: riprova fra $seconds secondi.", kit.error(over))
        assertEquals(200, kit.get("/api/convivenza", other.token).response.status)
        assertEquals(429, kit.get("/api/convivenza", t.token).response.status)
        // The refusals of the limit are in the register too, as requests that a valid token made.
        assertEquals(1, kit.register().count { it.third == 429 }, "only the first refusal of the minute is written")
    }

    // ---- The log and the register (criteria 11, 12) -----------------------------------------------------------------------------------

    @Test
    @CapturesOutput
    fun `t21 c11 the log has no token, no header Authorization and no text, and a token that does not work leaves one line a minute with a number`(output: CapturedOutput) {
        val t = kit.teacher()
        // The minute of the line of the log is the one of the clock of the application: it is moved forward so that the line of an earlier test is not in this one.
        clock.advance(Duration.ofMinutes(2))
        kit.get("/api/strutture", t.token)
        repeat(5) { kit.get("/api/strutture", "talita_" + "B".repeat(43)) }
        kit.get("/api/non-esiste", t.token)

        val log = output.all
        assertFalse(log.contains(t.token))
        assertFalse(log.contains("Bearer"))
        assertFalse(log.contains("Authorization"))
        assertFalse(log.contains("B".repeat(43)))
        val warnings = log.lines().filter { it.contains("requests refused for a token") }
        assertEquals(1, warnings.size, "at most one line a minute")
        assertTrue(Regex("""API: \d+ requests refused for a token that is missing or not valid\.""").containsMatchIn(warnings.single()))
    }

    @Test
    fun `t21 c12 a request with a valid token leaves a line with the moment, the method, the route as a model and the code, and a 401 leaves none`() {
        val t = kit.teacher()

        kit.get("/api/convivenza", t.token)
        kit.get("/api/strutture", t.token)
        kit.get("/api/convivenza", "talita_" + "C".repeat(43))
        kit.get("/api/non-esiste", t.token)
        kit.delete("/api/strutture", t.token)

        assertEquals(
            listOf(Triple("GET", "/api/convivenza", 200), Triple("GET", "/api/strutture", 200), Triple("GET", "/api/{altro}", 404), Triple("DELETE", "/api/{altro}", 405)),
            kit.register(),
        )
    }

    @Test
    fun `t21 c12 a route with a number is written as a model, never as the address that was asked for`() {
        val t = kit.teacher()

        kit.get("/api/strutture/987654321", t.token)

        assertEquals(listOf(Triple("GET", "/api/strutture/{id}", 404)), kit.register())
        assertEquals("strutture", ApiRequests.label("/api/strutture/{id}"))
        assertEquals("camere", ApiRequests.label("/api/strutture/{id}/camere/{camera}"))
    }

    @Test
    fun `t21 c12 the table of the register has the moment, the method, the route and the code and nothing else, and the table of the tokens the hash and the day`() {
        val columns = { table: String ->
            dsl.resultQuery("select column_name from information_schema.columns where table_name = ? order by ordinal_position", table).fetch(0, String::class.java)
        }

        assertEquals(listOf("id", "at", "method", "route", "status"), columns("api_request"))
        assertEquals(listOf("id", "account_id", "token_hash", "created_on"), columns("api_token"))
    }

    @Test
    fun `t21 c12 the card shows the last 20 requests without saying who and the night job deletes the lines after 30 days`() {
        val t = teacherAtPhone()
        val token = checkNotNull(tokens.create(t.id)).value
        repeat(22) { kit.get("/api/convivenza", token) }
        kit.get("/api/strutture", token)
        // The register keeps the whole day of 30 days ago (Rome): the oldest line goes one second before that day begins and is deleted, the next one exactly at its start and stays.
        // Not "now minus 31 days": an `OffsetDateTime` keeps its offset, so in the month after the end of summer time, from 23:00 on, that moment falls in the first day that is kept.
        val keptFrom = policy.apiRequestsKeptFrom(LocalDate.now(clock))
        val (oldest, next) = dsl.select(API_REQUEST.ID).from(API_REQUEST).orderBy(API_REQUEST.ID).limit(2).fetch(API_REQUEST.ID)
        dsl.update(API_REQUEST).set(API_REQUEST.AT, keptFrom.minusSeconds(1)).where(API_REQUEST.ID.eq(oldest)).execute()
        dsl.update(API_REQUEST).set(API_REQUEST.AT, keptFrom).where(API_REQUEST.ID.eq(next)).execute()

        val page = t.phone.body("/profilo")

        val log = page.substringAfter("""id="token-registro"""")
        assertEquals(20, Regex("<li class=\"border-t border-line py-2.5").findAll(log).count())
        assertTrue(text(log).contains("· GET strutture · 200 Andata a buon fine."))
        assertTrue(text(log).contains("· GET convivenza · 200 Andata a buon fine."))
        assertFalse(text(log).contains(t.email))
        assertTrue(text(log).contains("Le richieste si tengono per 30 giorni."))
        assertTrue(log.contains("<details"))
        assertFalse(log.substringBefore("</summary>").contains(" open"), "closed to start with")
        val before = kit.register().size
        assertTrue(job.run().succeeded)
        assertEquals(before - 1, kit.register().size, "the line before the first day that is kept is deleted, the one at its start and the others stay")
    }

    @Test
    fun `t21 c12 without requests the register says so`() {
        val t = teacherAtPhone()

        val words = text(t.phone.body("/profilo").substringAfter("""id="token-registro""""))

        assertTrue(words.contains("Nessuna richiesta negli ultimi 30 giorni. Quando un programma userà il token, le vedi qui."))
    }

    // ---- The routes of the first part (criteria 15 and 22 for the lists) -----------------------------------------------------------------

    @Test
    fun `t21 c15 the list of the gatherings has id, title, dates, state, over and venue, and nothing about the people`() {
        val t = kit.teacher()

        val answer = kit.get("/api/convivenza", t.token)

        assertEquals(200, answer.response.status)
        assertTrue(kit.tree(answer).isArray)
        for (item in kit.tree(answer)) {
            assertEquals(listOf("id", "title", "startDate", "endDate", "state", "over", "venueId"), item.propertyNames().toList())
            assertTrue(item.get("state").stringValue() in setOf("draft", "open", "closed"))
        }
    }

    @Test
    fun `t21 c22 the list of the venues has id, name, place and the counts, and nothing about the people`() {
        val t = kit.teacher()

        val answer = kit.get("/api/strutture", t.token)

        assertEquals(200, answer.response.status)
        for (item in kit.tree(answer)) {
            assertEquals(listOf("id", "name", "place", "gatherings", "rooms", "beds", "forms"), item.propertyNames().toList())
        }
    }

    @Test
    fun `t21 c14 no class of the package of the APIs uses DSLContext or what jOOQ generates, except the two that own their table`() {
        val allowed = setOf("ApiTokens.kt", "ApiRequests.kt")
        val offenders = java.nio.file.Files.list(java.nio.file.Path.of("src/main/kotlin/talita/api")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".kt") && it.fileName.toString() !in allowed }.filter {
                val text = java.nio.file.Files.readString(it)
                text.contains("DSLContext") || text.contains("talita.jooq") || text.contains("org.jooq")
            }.map { it.fileName.toString() }.toList()
        }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun `t21 c01 only ApiTokens and ApiRequests use their tables`() {
        for ((table, owner) in listOf("API_TOKEN" to "ApiTokens.kt", "API_REQUEST" to "ApiRequests.kt")) {
            val users = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/kotlin")).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".kt") }.filter { Regex("""\b$table\b""").containsMatchIn(java.nio.file.Files.readString(it)) }.map { it.fileName.toString() }.toList()
            }
            assertEquals(listOf(owner), users, table)
        }
    }

    @Test
    fun `t21 c05 the dialogs of taking a teacher away and of cancelling the account say that the token stops`() {
        val t = teacherAtPhone()
        val other = Addresses.unique("maestro-secondo").also { made += it }
        TestAccounts.makeTeacher(dsl, other, "Paolo", "Verdi")

        val maestri = text(t.phone.body("/maestri"))
        val profile = text(t.phone.body("/profilo"))

        assertTrue(maestri.contains("Potrà ancora entrare in Talità come genitore. Se ha un token per le API, smette di funzionare."), maestri)
        assertTrue(maestri.contains("Un altro maestro potrà invitarti di nuovo. Se hai un token per le API, smette di funzionare."), maestri)
        assertTrue(profile.contains("Il token per le API, se ne hai uno: smette di funzionare."), profile)
    }

    @Test
    fun `t21 c28 the notice says in one sentence what a teacher can make and how long it lasts`() {
        val email = Addresses.unique("genitore-informativa").also { made += it }
        val words = text(phone().also { it.enter(email) }.body("/informativa"))

        assertTrue(words.contains("Un maestro può creare un token personale per far lavorare un programma sulla configurazione (strutture, camere, quote e testi della convivenza)."), words)
        assertTrue(words.contains("Il token non apre mai i dati di bambini, genitori e maestri, vale 30 giorni e si cancella alla scadenza, con «Revoca» o quando il maestro lascia i maestri."), words)
        assertFalse(words.contains("Claude"))
    }
}
