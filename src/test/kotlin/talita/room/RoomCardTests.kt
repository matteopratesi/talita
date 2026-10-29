package talita.room

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.checkin.card
import talita.checkin.touch
import talita.checkin.words
import talita.gathering.body
import talita.jooq.enums.RegistrationStatus
import talita.jooq.tables.references.ROOM_CHILD
import talita.jooq.tables.references.ROOM_TEACHER
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The card of the child in Arrivi has the room (criterion 15): a card under «Giorni» that says the room, with the way to «Camere», or «Senza camera», and nothing else of the lists of
 * Arrivi changes. Names are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RoomCardTests(
    @Autowired mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
) {

    private val kit = RoomTestKit(mockMvc, tokens, dsl, clock)

    private fun card(html: String): String = Regex("""<section[^>]*aria-labelledby="camera-titolo".*?</section>""", RegexOption.DOT_MATCHES_ALL).find(html)!!.value

    @Test
    fun `t11 c15 the card of a child in a room says the room with the link to the page of the rooms, right under the days`() {
        val setup = kit.start()
        val two = kit.room(setup.id, "Camera 2", 4)
        val anna = kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi")
        kit.put(anna, setup.id, two)

        val html = setup.marta.card(anna)
        val card = card(html)
        assertTrue(words(card).contains("Camera Camera 2 Si cambia dalla pagina «Camere», nella Convivenza."), words(card))
        assertTrue(card.contains("""<a href="/convivenza/${setup.id}/camere#camera-$two""""), "the name is a link to the room on the page «Camere»")
        // The place is under «Giorni» and above the contact of the parent: it serves at night and in an emergency.
        val titles = Regex("""<h2[^>]*>([^<]*)</h2>""").findAll(html).map { it.groupValues[1] }.filter { it != "Togliere il pagamento di" }.toList()
        assertEquals(listOf("Giorni", "Camera", "Recapito del genitore", "Segnalazioni", "Quota e pagamenti", "Manleva", "Iscrizione"), titles.take(7))
        assertFalse(html.contains("<form") && Regex("""<form[^>]*camere""").containsMatchIn(html), "the card changes nothing: the room is changed from «Camere»")
    }

    @Test
    fun `t11 c15 a child without a room has Senza camera and the way to choose one`() {
        val setup = kit.start()
        kit.room(setup.id, "Camera 2", 4)
        val anna = kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi")

        val html = setup.marta.card(anna)
        val card = card(html)
        assertTrue(words(card).contains("Camera Senza camera Vai alle camere"), words(card))
        assertTrue(card.contains("""href="/convivenza/${setup.id}/camere#senza-camera""""), card)
        // Every action of the card answers with the block and the card of the room is in it, as the days are.
        val answer = setup.marta.touch(setup, "/arrivi/$anna/presenze", mapOf("da" to "scheda"))
        assertTrue(words(card(answer.body())).contains("Camera Senza camera"), "the block of the card has the room")
        kit.put(anna, setup.id, kit.roomId(setup.id, "Camera 2"))
        assertTrue(words(card(setup.marta.touch(setup, "/arrivi/$anna/presenze/togli", mapOf("da" to "scheda")).body())).contains("Camera Camera 2"))
    }

    @Test
    fun `t11 c15 a registration that was withdrawn has no card of the room`() {
        val setup = kit.start()
        val chiara = kit.rows.register(setup.id, kit.checkin.account(), "Chiara", "Fontana", status = RegistrationStatus.withdrawn)

        val html = setup.marta.card(chiara)
        assertFalse(html.contains("""aria-labelledby="camera-titolo""""), "the card is not there")
        assertFalse(words(html).contains("Senza camera"))
    }

    @Test
    fun `t11 c15 the lists, the register, the list for the venue and the sheet for the teachers do not say the room`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera Quadrifoglio", 4)
        val anna = kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi")
        kit.put(anna, setup.id, one)
        kit.putTeacher(setup.id, setup.paoloId, one)

        for (path in listOf("/arrivi", "/arrivi?giorno=${setup.yesterday}", "/arrivi/registro", "/arrivi/elenco", "/arrivi/elenco?giorno=${setup.today}", "/arrivi/foglio", "/iscrizioni", "/iscrizioni/$anna")) {
            val text = words(setup.marta.get(path).body())
            assertFalse(text.contains("Quadrifoglio"), "$path says the room")
            assertFalse(text.contains("Senza camera"), "$path says «Senza camera»")
        }
    }

    @Test
    fun `t11 c15 after the end the card says that who slept where was deleted, with the date, and until then it says the room as it was`() {
        val setup = kit.start()
        val one = kit.room(setup.id, "Camera 1", 4)
        val anna = kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi")
        val luca = kit.rows.register(setup.id, kit.checkin.account(), "Luca", "Rossi")
        kit.put(anna, setup.id, one)
        kit.endedDaysAgo(setup.id, 1)

        // The job has not run: who was in a room is there, who was not is without a room.
        assertTrue(words(card(setup.marta.card(anna))).contains("Camera 1"))
        assertTrue(words(card(setup.marta.card(luca))).contains("Senza camera"))
        // The job deleted them: the same sentence for every child, and the date of the deletion is the day after the last day.
        dsl.deleteFrom(ROOM_CHILD).execute()
        dsl.deleteFrom(ROOM_TEACHER).execute()
        for (id in listOf(anna, luca)) {
            val text = words(card(setup.marta.card(id)))
            assertTrue(Regex("""Camera Chi dormiva dove è stato cancellato il \d{1,2} [a-zà-ù]+ \d{4}\.""").containsMatchIn(text), text)
            assertFalse(text.contains("Vai alle camere"), "nothing to choose any more")
        }
    }

    @Test
    fun `t11 c15 c13 after the end with no rooms the card says Senza camera, not that anything was deleted, and with rooms and nobody in them it says the deletion`() {
        val setup = kit.start()
        val anna = kit.rows.register(setup.id, kit.checkin.account(), "Anna", "Rossi")
        kit.endedDaysAgo(setup.id, 1)

        // No rooms were ever written: there was nothing to delete.
        val text = words(card(setup.marta.card(anna)))
        assertTrue(text.contains("Camera Senza camera"), text)
        assertFalse(text.contains("cancellato"), text)

        // With a room and nobody in it, as the job leaves it: the sentence of the deletion.
        kit.room(setup.id, "Camera 1", 4)
        val deleted = words(card(setup.marta.card(anna)))
        assertTrue(Regex("""Camera Chi dormiva dove è stato cancellato il \d{1,2} [a-zà-ù]+ \d{4}\.""").containsMatchIn(deleted), deleted)
    }
}
