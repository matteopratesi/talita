package talita.room

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
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.checkin.htmx
import talita.checkin.words
import talita.gathering.body
import talita.jooq.enums.GatheringStatus
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import java.time.Clock
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the log says and what the markup of the pages of the rooms is (criteria 21, 23): while the teachers write the rooms, put people in them, move them and print, the log (INFO and over) has no
 * name of a person, no name of a room and no address; and every page and piece of page of the rooms has no style, no inline script, no handler, no link outside the app, no word of the other
 * project and nothing that says the sex. Names are synthetic and unusual, so that a word of the log cannot be one of them by chance.
 */
@ExtendWith(OutputCaptureExtension::class)
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomLogAndMarkupTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    private fun MockBrowser.touch(base: String, tail: String, fields: Map<String, String>, viaHtmx: Boolean = true) =
        submit("/profilo", base + tail, fields, if (viaHtmx) htmx() else ({}))

    @Test
    fun `t11 c21 the log has no name of a person, no name of a room and no address while the teachers write the rooms, move the people and print`(output: CapturedOutput) {
        val setup = kit.start()
        val base = "/convivenza/${setup.id}/camere"
        val marta = setup.marta
        val zeffirino = kit.rows.register(setup.id, kit.checkin.account(), "Zeffirino", "Quagliotti")
        val ludovica = kit.rows.register(setup.id, null, "Ludovica", "Pellegrini", enteredBy = setup.martaId)
        val cleofe = kit.rows.register(setup.id, kit.checkin.account(), "Cleofe", "Tarallo", status = RegistrationStatus.maybe)

        for (path in listOf(base, "$base/nuova", "$base/stampa")) marta.get(path)
        marta.touch(base, "/nuova", mapOf("name" to "Torre di Ottaviano", "beds" to "3"), viaHtmx = false)
        marta.touch(base, "/nuova", mapOf("name" to "Maschi", "beds" to "x"), viaHtmx = false)
        marta.touch(base, "/nuova", mapOf("name" to "Torre di Ottaviano", "beds" to "3"), viaHtmx = false)
        marta.touch(base, "/nuova", mapOf("name" to "Soffitta dei Gelsomini", "beds" to "2"), viaHtmx = false)
        val room = kit.roomId(setup.id, "Torre di Ottaviano")
        val last = kit.roomId(setup.id, "Soffitta dei Gelsomini")
        marta.touch(base, "/scegli", mapOf("persona" to "b$zeffirino", "camera" to room.toString()))
        marta.touch(base, "/scegli", mapOf("persona" to "b$ludovica", "camera" to ""))
        marta.touch(base, "/sposta", mapOf("persona" to "b$zeffirino", "azione" to "sposta", "camera" to last.toString()))
        marta.touch(base, "/sposta", mapOf("persona" to "b$zeffirino", "azione" to "togli"))
        marta.touch(base, "/maestri", mapOf("camera" to room.toString(), "persona" to "m${setup.giuliaId}"))
        marta.touch(base, "/$room/modifica", mapOf("name" to "Torre di Ottaviano alta", "beds" to "4"), viaHtmx = false)
        marta.touch(base, "/$room/togli", mapOf("camera" to room.toString()))
        marta.touch(base, "/copia", emptyMap())
        // What is refused or does not exist.
        marta.touch(base, "/scegli", mapOf("persona" to "b${zeffirino + 100_000}", "camera" to room.toString()))
        marta.get("$base/${room + 100_000}/modifica")
        kit.endedDaysAgo(setup.id, 1)
        marta.touch(base, "/scegli", mapOf("persona" to "b$cleofe", "camera" to last.toString()))
        marta.get(base)
        marta.get("$base/stampa")

        val log = output.all
        val emailOfMarta = dsl.select(ACCOUNT.EMAIL).from(ACCOUNT).where(ACCOUNT.ID.eq(setup.martaId)).fetchSingle(ACCOUNT.EMAIL)!!
        val secrets = listOf(
            "Zeffirino", "Quagliotti", "Ludovica", "Pellegrini", "Cleofe", "Tarallo", "Torre di Ottaviano", "Ottaviano", "Soffitta", "Gelsomini", "Marta", "Bianchi", "Giulia", "Serra", "Paolo", "Verdi",
            emailOfMarta,
        )
        for (secret in secrets) assertFalse(log.contains(secret), "the log has '$secret': ${log.lines().filter { it.contains(secret) }.take(3)}")
    }

    @Test
    fun `t11 c21 the pages and pieces of the rooms have no style, no inline script, no handler, no link outside the app, no word of the other project and nothing that says the sex`() {
        val setup = kit.start()
        val base = "/convivenza/${setup.id}/camere"
        val marta = setup.marta
        val pages = mutableMapOf<String, String>()
        pages["senza camere"] = marta.get(base).body()
        pages["nuova"] = marta.get("$base/nuova").body()
        pages["stampa vuota"] = marta.get("$base/stampa").body()
        val before = kit.otherGathering("Convivenza di settembre 2026", daysFromToday = -400)
        kit.room(before, "Camera 1", 4)
        pages["con la copia"] = marta.get(base).body()
        pages["copiate"] = marta.touch(base, "/copia", emptyMap()).body()
        val one = kit.roomId(setup.id, "Camera 1")
        val azzurra = kit.room(setup.id, "Stanza azzurra", 2)
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        val sara = kit.rows.register(setup.id, kit.checkin.account(), "Sara", "Neri", status = RegistrationStatus.maybe)
        val chiara = kit.rows.register(setup.id, kit.checkin.account(), "Chiara", "Fontana")
        kit.put(luca, setup.id, one)
        kit.putTeacher(setup.id, setup.paoloId, azzurra)
        pages["con persone"] = marta.get(base).body()
        pages["modifica"] = marta.get("$base/$one/modifica").body()
        pages["modifica errori"] = marta.touch(base, "/$one/modifica", mapOf("name" to "", "beds" to "x"), viaHtmx = false).body()
        pages["togli senza JavaScript"] = marta.get("$base/$one/togli").body()
        pages["scelta"] = marta.touch(base, "/scegli", mapOf("persona" to "b$sara", "camera" to azzurra.toString())).body()
        pages["scelta senza camera"] = marta.touch(base, "/scegli", mapOf("persona" to "b$chiara", "camera" to "")).body()
        pages["scelta senza JavaScript"] = marta.touch(base, "/scegli", mapOf("persona" to "b$chiara", "camera" to ""), viaHtmx = false).body()
        pages["sposta"] = marta.touch(base, "/sposta", mapOf("persona" to "b$luca", "azione" to "sposta", "camera" to azzurra.toString())).body()
        pages["togli dalla camera"] = marta.touch(base, "/sposta", mapOf("persona" to "b$luca", "azione" to "togli")).body()
        pages["maestro"] = marta.touch(base, "/maestri", mapOf("camera" to one.toString(), "persona" to "m${setup.giuliaId}")).body()
        pages["maestro non scelto"] = marta.touch(base, "/maestri", mapOf("camera" to one.toString(), "persona" to "")).body()
        pages["troppe persone"] = run {
            for (name in listOf("Anna", "Elena", "Matilde")) kit.put(kit.rows.register(setup.id, kit.checkin.account(), name, "Gallo"), setup.id, azzurra)
            marta.get(base).body()
        }
        pages["stampa"] = marta.get("$base/stampa").body()
        pages["camera tolta"] = marta.touch(base, "/$one/togli", mapOf("camera" to one.toString())).body()
        kit.endedDaysAgo(setup.id, 1)
        pages["finita"] = marta.get(base).body()
        pages["stampa dopo la fine"] = marta.get("$base/stampa").body()
        pages["409"] = marta.touch(base, "/scegli", mapOf("persona" to "b$sara", "camera" to azzurra.toString())).body()
        dsl.deleteFrom(ROOM_CHILD).execute()
        dsl.deleteFrom(ROOM_TEACHER).execute()
        pages["cancellate"] = marta.get(base).body()
        pages["stampa cancellate"] = marta.get("$base/stampa").body()
        val draft = kit.start(first = 30, last = 32, status = GatheringStatus.draft)
        kit.room(draft.id, "Camera 1", 4)
        pages["bozza"] = draft.marta.get("/convivenza/${draft.id}/camere").body()
        pages["dettaglio"] = draft.marta.get("/convivenza/${draft.id}").body()

        // Written in pieces: this file is under src/ and must not contain the words of the other project, nor the ones that say the sex.
        val forbidden = listOf("koi" + "nè", "cen" + "tro", "comuni" + "tà", "fra" + "telli")
        val sex = listOf("masch" + "i", "femmi" + "n", "ses" + "so", "bambin" + "e", "ragazz" + "e")
        for ((name, html) in pages) {
            assertTrue(html.length > 150, "$name is empty")
            for (word in forbidden) assertFalse(html.contains(word, ignoreCase = true), "'$word' is in $name")
            for (word in sex) assertFalse(html.contains(word, ignoreCase = true), "'$word' is in $name")
            assertFalse(Regex("""<script(?![^>]*\ssrc=)""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: inline script")
            assertFalse(Regex("""<[^>]+\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: on* attribute")
            assertFalse(Regex("""<[^>]+\shx-on""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: hx-on")
            assertFalse(Regex("""<[^>]+\sstyle\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: style attribute")
            assertFalse(Regex("""<style""", RegexOption.IGNORE_CASE).containsMatchIn(html), "$name: style element")
            for (link in Regex("""\s(?:src|href|action|formaction)\s*=\s*["']([^"']*)["']""").findAll(html).map { it.groupValues[1] }) {
                assertFalse(Regex("""^(?:[a-z][a-z0-9+.-]*:|//)""", RegexOption.IGNORE_CASE).containsMatchIn(link) && !link.startsWith("#") && !link.startsWith("tel:"), "$name: link outside the app $link")
            }
            // No person in an address: the links and the actions have the number of the gathering and, at most, the number of a room.
            for (link in Regex("""\s(?:href|action|hx-post)\s*=\s*["']([^"']*)["']""").findAll(html).map { it.groupValues[1] }) {
                assertFalse(Regex("""/camere/(?:scegli|sposta|maestri)\?|persona=|[?&]b\d""").containsMatchIn(link), "$name: a person in an address: $link")
            }
        }
        assertTrue(words(pages.getValue("finita")).contains("La convivenza è finita"), "the page of the end")
    }

    @Test
    fun `t11 c21 a name of a room and of a person with markup is shown as text on the page, the print and the card of the arrivals`() {
        val setup = kit.start()
        val room = kit.room(setup.id, "<u>Torre</u> & co", 3)
        val id = kit.rows.register(setup.id, kit.checkin.account(), "<b>Anna</b>", "R<script>alert(1)</script>ossi")
        kit.put(id, setup.id, room)
        kit.putTeacher(setup.id, setup.paoloId, room)

        for (path in listOf("/convivenza/${setup.id}/camere", "/convivenza/${setup.id}/camere/stampa", "/convivenza/${setup.id}/camere/$room/modifica", "/convivenza/${setup.id}/camere/$room/togli", "/arrivi/$id")) {
            val html = setup.marta.get(path).body()
            assertFalse(html.contains("<u>Torre") || html.contains("<b>Anna") || html.contains("<script>alert"), "$path: markup that was written is not markup in the page")
        }
        assertTrue(setup.marta.get("/convivenza/${setup.id}/camere/stampa").body().contains("&lt;b&gt;Anna&lt;/b&gt;"), "it is shown as the text that was written")
    }
}
