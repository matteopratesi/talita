package talita.checkin

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import talita.Addresses
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.body
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.GATHERING
import talita.jooq.tables.references.PAYMENT
import talita.registration.Notes
import talita.registration.RegistrationTestKit
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the log says and what the markup of the new pages is (criteria 30, 32): while the teachers mark, pay, read the notes and print, the log (INFO and over) has no name,
 * no phone, no amount, no note and no address of a person; and every page and piece of page of Arrivi names nothing of the other project, has no style, no inline script,
 * no handler and no link outside the app. Names, numbers and notes are synthetic, and unusual, so that a word of the log cannot be one of them by chance.
 */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ArrivalsLogAndPagesTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = CheckinTestKit(mockMvc, tokens, dsl, clock)

    private inner class World {
        val setup = kit.start()
        val zeffirino = kit.rows.register(
            setup.id, kit.account(), "Zeffirino", "Quagliotti", LocalDate.of(2019, 1, 9), phone = "333 777 8888", notes = Notes("Polline", "Lattosio", "Cortisone"),
        )
        val ludovica = kit.rows.register(setup.id, null, "Ludovica", "Pellegrini", LocalDate.of(2016, 5, 5), phone = "333 666 9999", enteredBy = setup.martaId)
        val ottavia = kit.rows.register(setup.id, kit.account(), "Ottavia", "Zanzotto", LocalDate.of(2017, 7, 7), status = RegistrationStatus.maybe)
        val withdrawn = kit.rows.register(setup.id, kit.account(), "Cleofe", "Tarallo", status = RegistrationStatus.withdrawn)
        val parent: MockBrowser = kit.kit.parent(Addresses.unique("genitore-log"))
    }

    // ---- The log (criterion 30) -------------------------------------------------------------------------------------------------

    @Test
    fun `t6 c30 the log has no name, phone, amount, note or address while the teachers mark, pay, read the notes and print`(output: CapturedOutput) {
        val w = World()
        val marta = w.setup.marta
        val today = w.setup.today

        // The pages.
        for (path in listOf("/arrivi", "/arrivi?giorno=${w.setup.yesterday}", "/arrivi/${w.zeffirino}", "/arrivi/${w.ludovica}", "/arrivi/registro", "/arrivi/elenco", "/arrivi/elenco?giorno=$today", "/arrivi/foglio")) {
            marta.get(path)
        }
        // The touches: the presence, taken away and again, the payments (wrong, right, twice, taken away), the notes, the manleva and the registration put back.
        marta.touch(w.setup, "/arrivi/${w.zeffirino}/presenze", mapOf("da" to "scheda"))
        marta.touch(w.setup, "/arrivi/${w.ottavia}/presenze", emptyMap())
        marta.touch(w.setup, "/arrivi/${w.zeffirino}/presenze/togli", mapOf("da" to "scheda"))
        marta.touch(w.setup, "/arrivi/${w.zeffirino}/pagamenti", mapOf("importo" to "713,177", "richiesta" to UUID.randomUUID().toString()))
        val code = UUID.randomUUID().toString()
        marta.touch(w.setup, "/arrivi/${w.zeffirino}/pagamenti", mapOf("importo" to "713,17", "richiesta" to code))
        marta.touch(w.setup, "/arrivi/${w.zeffirino}/pagamenti", mapOf("importo" to "713,17", "richiesta" to code))
        marta.touch(w.setup, "/arrivi/${w.zeffirino}/pagamenti", mapOf("importo" to "713,17", "richiesta" to "non-un-codice"))
        val payment = dsl.select(PAYMENT.ID).from(PAYMENT).fetchSingle(PAYMENT.ID)!!
        marta.touch(w.setup, "/arrivi/${w.zeffirino}/pagamenti/$payment/togli", emptyMap())
        marta.touch(w.setup, "/arrivi/${w.zeffirino}/segnalazioni", emptyMap())
        marta.touch(w.setup, "/arrivi/${w.ludovica}/manleva", emptyMap())
        marta.touch(w.setup, "/arrivi/${w.withdrawn}/rimetti", emptyMap())
        marta.touch(w.setup, "/arrivi/${w.withdrawn}/presenze", emptyMap())
        // What is not allowed or does not exist: a parent, an id that is not there, a day that is not one.
        w.parent.get("/arrivi/${w.zeffirino}")
        marta.get("/arrivi/${w.zeffirino + 100_000}")
        marta.get("/arrivi/elenco?giorno=2031-01-01")

        val log = output.all
        val emailOfMarta = dsl.select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(w.setup.martaId)).fetchSingle(ACCOUNT.EMAIL)!!
        val secrets = listOf(
            "Zeffirino", "Quagliotti", "Ludovica", "Pellegrini", "Ottavia", "Zanzotto", "Cleofe", "Tarallo", "Polline", "Lattosio", "Cortisone", "333 777", "3337778888", "333 666", "3336669999",
            "713,17", "713.17", "71317", "Marta", "Bianchi", emailOfMarta, "2019-01-09",
        )
        for (secret in secrets) assertFalse(log.contains(secret), "the log has '$secret': ${log.lines().filter { it.contains(secret) }.take(3)}")
    }

    @Test
    fun `t6 c30 names and phones with markup are shown as text in the list, in the card and in the answers`() {
        val setup = kit.start()
        val id = kit.rows.register(setup.id, kit.account(), "<b>Anna</b>", "R<script>alert(1)</script>ossi", phone = "<i>333</i> 123")

        val pages = listOf(
            setup.marta.get("/arrivi").body(), setup.marta.card(id),
            setup.marta.touch(setup, "/arrivi/$id/presenze", emptyMap()).body(), setup.marta.touch(setup, "/arrivi/$id/presenze", mapOf("da" to "scheda")).body(),
            setup.marta.touch(setup, "/arrivi/$id/segnalazioni", emptyMap()).body(),
        )

        for (html in pages) {
            assertFalse(html.contains("<b>Anna") || html.contains("<script>alert") || html.contains("<i>333"), "markup that was written is markup in the page")
            assertTrue(html.contains("&lt;b&gt;Anna&lt;/b&gt;") || html.contains("&lt;b>Anna&lt;/b>") || !html.contains("Anna"), "it is shown as the text that was written")
        }
    }

    // ---- The markup of every new page (criterion 32) ---------------------------------------------------------------------------

    @Test
    fun `t6 c32 the pages and pieces of the arrivals name nothing of the other project, have no style, no inline script, no handler and no link outside the app`() {
        val w = World()
        val marta = w.setup.marta
        kit.presence(w.zeffirino, w.setup.today, w.setup.martaId)
        kit.payment(w.zeffirino, 2500, w.setup.paoloId)
        val payment = dsl.select(PAYMENT.ID).from(PAYMENT).fetchSingle(PAYMENT.ID)!!

        val pages = mutableMapOf<String, String>()
        for (path in listOf(
            "/arrivi", "/arrivi?giorno=${w.setup.yesterday}", "/arrivi?giorno=${w.setup.tomorrow}", "/arrivi/${w.zeffirino}", "/arrivi/${w.ludovica}", "/arrivi/${w.ottavia}", "/arrivi/${w.withdrawn}",
            "/arrivi/${w.zeffirino}/pagamenti/$payment/togli", "/arrivi/registro", "/arrivi/elenco", "/arrivi/elenco?giorno=${w.setup.today}", "/arrivi/elenco?giorno=${w.setup.tomorrow}", "/arrivi/foglio",
        )) {
            pages[path] = marta.get(path).body()
        }
        pages["segna"] = marta.touch(w.setup, "/arrivi/${w.ottavia}/presenze", emptyMap()).body()
        pages["segna dalla scheda"] = marta.touch(w.setup, "/arrivi/${w.ludovica}/presenze", mapOf("da" to "scheda")).body()
        pages["pagamento sbagliato"] = marta.touch(w.setup, "/arrivi/${w.ludovica}/pagamenti", mapOf("importo" to "tanto", "richiesta" to UUID.randomUUID().toString())).body()
        pages["pagamento"] = marta.touch(w.setup, "/arrivi/${w.ludovica}/pagamenti", mapOf("importo" to "10", "richiesta" to UUID.randomUUID().toString())).body()
        pages["segnalazioni"] = marta.touch(w.setup, "/arrivi/${w.zeffirino}/segnalazioni", emptyMap()).body()
        pages["pagina senza JavaScript"] = marta.touch(w.setup, "/arrivi/${w.zeffirino}/segnalazioni", emptyMap(), viaHtmx = false).body()
        // The states without registrations, with a draft and with nothing.
        dsl.update(GATHERING).set(GATHERING.STATUS, GatheringStatus.draft).where(GATHERING.ID.eq(w.setup.id)).execute()
        pages["bozza"] = marta.get("/arrivi").body()
        kit.kit.gatherings.reset()
        pages["niente"] = marta.get("/arrivi").body()
        val empty = kit.start()
        pages["senza iscrizioni"] = empty.marta.get("/arrivi").body()
        pages["registro vuoto"] = empty.marta.get("/arrivi/registro").body()
        pages["foglio vuoto"] = empty.marta.get("/arrivi/foglio").body()
        pages["elenco vuoto"] = empty.marta.get("/arrivi/elenco").body()

        // Written in pieces: this file is under src/ and must not contain the words of the other project.
        val forbidden = listOf("koi" + "nè", "cen" + "tro", "comuni" + "tà", "fra" + "telli")
        for ((name, html) in pages) {
            assertTrue(html.length > 150, "$name is empty")
            for (word in forbidden) assertFalse(html.contains(word, ignoreCase = true), "'$word' is in $name")
            assertFalse(Regex("""<script(?![^>]*\ssrc=)""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: inline script")
            assertFalse(Regex("""<[^>]+\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: on* attribute")
            assertFalse(Regex("""<[^>]+\shx-on""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: hx-on")
            assertFalse(Regex("""<[^>]+\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: style attribute")
            assertFalse(Regex("""<style""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: style element")
            for (link in Regex("""\s(?:src|href|action|formaction)\s*=\s*["']([^"']*)["']""").findAll(html).map { it.groupValues[1] }) {
                assertFalse(Regex("""^(?:[a-z][a-z0-9+.-]*:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(link) && !link.startsWith("#") && !link.startsWith("tel:"), "$name: link outside the app $link")
            }
        }
    }

    @Test
    fun `t6 c32 the migration of the check-in is in the list of the migrations, and the database has no extension but unaccent`() {
        val versions = dsl.fetch("select version from flyway_schema_history where success order by installed_rank").map { it[0].toString() }
        assertTrue("6" in versions, "V6 is not among the migrations that ran: $versions")
        assertEquals(listOf("unaccent"), dsl.fetch("select extname from pg_extension where extname not in ('plpgsql')").map { it[0].toString() })
    }

    // ---- The notices tell that the venue receives names (criterion 31, t5 c33) --------------------------------------------------

    @Test
    fun `t6 c31 the complete notice says that the venue receives names on paper, and so does the short one of the second step`() {
        val full = words(mockMvc.get("/informativa").andReturn().response.contentAsString)
        assertTrue(
            full.contains("La struttura che ospita la convivenza riceve su carta un elenco con il nome e il cognome dei bambini e dei maestri affidatari, e nient’altro."), full,
        )
        val registrations = RegistrationTestKit(mockMvc, tokens, dsl, clock)
        val setup = registrations.start()
        val parent = registrations.parent()
        val second = parent.submit(setup.form, setup.form, registrations.form(registrations.child())).body()
        assertTrue(
            words(second).contains("Chi li riceve I maestri della convivenza. La struttura che ospita la convivenza riceve su carta il nome e il cognome dei bambini e dei maestri affidatari, e nient’altro."),
            words(second),
        )
    }

    @Test
    fun `t6 c31 both notices say that the attendance by day and the payments are collected, who marks and sees them, and for how long`() {
        val full = mockMvc.get("/informativa").andReturn().response.contentAsString
        fun card(id: String): String = words(Regex("""<section[^>]*aria-labelledby="$id"[^>]*>(.*?)</section>""", RegexOption.DOT_MATCHES_ALL).find(full)!!.groupValues[1])

        val data = card("dati")
        assertTrue(data.contains("giorni di presenza e stato dell’iscrizione"), data)
        assertTrue(
            data.contains(
                "Segnati dai maestri durante la convivenza: le presenze, giorno per giorno (il giorno e l’ora in cui un maestro segna che un bambino c’è), e i pagamenti (l’importo e l’ora). " +
                    "Ogni presenza e ogni pagamento porta il nome del maestro che l’ha segnato.",
            ),
            data,
        )
        assertTrue(
            card("scopi").contains("Presenze e pagamenti: servono ai maestri per sapere chi c’è ogni giorno e quanto è stato pagato, e li usiamo perché fanno parte del servizio che ci chiedi"),
            card("scopi"),
        )
        assertTrue(card("visibilita").contains("Le presenze e i pagamenti li vedono solo i maestri, con il nome di chi li ha segnati."), card("visibilita"))
        val time = Regex("""presenze.*pagamenti.*un anno dopo la fine della convivenza""", RegexOption.IGNORE_CASE)
        assertTrue(time.containsMatchIn(card("tempi")), card("tempi"))

        val registrations = RegistrationTestKit(mockMvc, tokens, dsl, clock)
        val setup = registrations.start()
        val second = words(registrations.parent().submit(setup.form, setup.form, registrations.form(registrations.child())).body())
        assertTrue(
            second.contains(
                "Durante la convivenza i maestri segnano giorno per giorno chi c’è e i pagamenti, ognuno con il nome del maestro che l’ha segnato: li vedono solo i maestri e servono a sapere chi è presente e quanto è stato pagato.",
            ),
            second,
        )
        val brief = Regex("""Per quanto tempo (.*?) I tuoi diritti""").find(second)!!.groupValues[1]
        assertTrue(time.containsMatchIn(brief), brief)
    }

}
