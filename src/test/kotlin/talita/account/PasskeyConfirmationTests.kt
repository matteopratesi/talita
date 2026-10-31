package talita.account

import org.jooq.DSLContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.security.web.webauthn.api.Bytes
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import talita.Addresses
import talita.CapturesOutput
import talita.TestcontainersConfiguration
import talita.jooq.tables.references.ACCOUNT
import talita.jooq.tables.references.HEALTH_NOTE_ACCESS
import talita.jooq.tables.references.PASSKEY
import talita.jooq.tables.references.REMEMBERED_DEVICE
import talita.registration.HealthNotes
import talita.registration.Notes
import talita.retention.RetentionTestKit
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The confirmation with the passkey before the notes on health (TALITA-15, criteria 37-48, decisions of Matteo 10965 and 10970): when a teacher has to confirm, what the two requests of the phone are,
 * that without a confirmation nothing is read and no reading is written (from the three places, even by hand), how long it holds, what refuses it, that a parent never confirms, and that nothing new
 * goes in the register or the log. The phone is [SoftPasskey], the browser a [Phone]; the answers are those of the real application, with MockMvc. Names, dates and addresses are synthetic.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PasskeyConfirmationTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val confirmations: PasskeyConfirmations,
    @Autowired private val healthNotes: HealthNotes,
) {

    private val origin = TestcontainersConfiguration.PUBLIC_URL
    private val rpId = "talita.example.org"
    private val kit = RetentionTestKit(dsl, clock)
    private val notes = Notes("Arachidi", null, "Antistaminico")
    private val made = mutableListOf<String>()
    private var gathering = 0L

    /** A teacher at their phone, with the passkey of [soft] if they have one. */
    private class Teacher(val email: String, val id: Long, val phone: Phone, val soft: SoftPasskey?)

    @BeforeEach
    fun start() {
        kit.reset()
        gathering = kit.september2027()
    }

    @AfterEach
    fun clean() {
        kit.reset()
        val ids = dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.`in`(made)).fetch(ACCOUNT.ID).map { it!! }
        dsl.deleteFrom(REMEMBERED_DEVICE).where(REMEMBERED_DEVICE.ACCOUNT_ID.`in`(ids)).execute()
        dsl.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.`in`(ids)).execute()
        dsl.execute("delete from teacher where account_id = any(?)", ids.toTypedArray())
        dsl.deleteFrom(ACCOUNT).where(ACCOUNT.ID.`in`(ids)).execute()
        made.clear()
    }

    private fun phone() = Phone(mockMvc, tokens)

    private fun teacher(passkey: Boolean = true, remember: Boolean = false): Teacher {
        val email = Addresses.unique("maestro-conferma").also { made += it }
        val id = TestAccounts.makeTeacher(dsl, email)
        val phone = phone()
        phone.enter(email, remember)
        val soft = if (passkey) SoftPasskey(origin, rpId).also { assertEquals(200, PasskeyKit.add(phone, it).response.status) } else null
        return Teacher(email, id, phone, soft)
    }

    /** A child with notes, registered by a parent. Returns the id of the registration. */
    private fun child(first: String = "Elena", last: String = "Marino"): Long {
        return kit.registration(gathering, kit.account("genitore"), first, last, notes = notes)
    }

    private fun paperWithNotes(teacher: Teacher): Long {
        return kit.paper(gathering, teacher.id, notes = notes)
    }

    private fun detail(t: Teacher, id: Long, htmx: Boolean = true): MvcResult =
        t.phone.submit("/iscrizioni/$id", "/iscrizioni/$id/mostra-segnalazioni") { if (htmx) header("HX-Request", "true") }

    private fun card(t: Teacher, id: Long, htmx: Boolean = true): MvcResult =
        t.phone.submit("/arrivi/$id", "/arrivi/$id/segnalazioni", mapOf("giorno" to "2027-09-17")) { if (htmx) header("HX-Request", "true") }

    private fun html(result: MvcResult) = result.response.contentAsString

    private fun readings() = dsl.fetchCount(HEALTH_NOTE_ACCESS)

    private fun hasNotes(html: String) = html.contains("Arachidi")

    /** The moment of the confirmation was [ago] ago: the session of [t] says so (the clock of the application is shared by every test and only goes forward). */
    private fun confirmedAgo(t: Teacher, ago: Duration) {
        t.phone.session!!.setAttribute(PasskeyConfirmations.CONFIRMED, Confirmed(t.id, Instant.now(clock).minus(ago)))
    }

    // ---- When it is needed (criterion 37) -------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c37 a teacher with a passkey and no confirmation is told to confirm from the detail, from the card of the arrivals and from the correction of a paper form, and reads nothing`() {
        val t = teacher()
        val child = child()
        val paper = paperWithNotes(t)

        val fromDetail = detail(t, child)
        val fromCard = card(t, child)
        val fromCorrection = t.phone.get("/iscrizioni/$paper/correggi-segnalazioni")

        assertEquals(409, fromDetail.response.status)
        assertTrue(html(fromDetail).contains("Serve la conferma."))
        assertEquals(409, fromCard.response.status)
        assertTrue(html(fromCard).contains("Serve la conferma."))
        assertEquals(200, fromCorrection.response.status, "the page of the correction is a page: it asks first")
        assertTrue(html(fromCorrection).contains("Serve la conferma") && html(fromCorrection).contains("Conferma con la passkey"))
        for (answer in listOf(fromDetail, fromCard, fromCorrection)) assertFalse(hasNotes(html(answer)), "the notes are in the answer")
        assertFalse(html(fromCorrection).contains("""name="allergies""""), "the page of the correction has no fields")
        assertTrue(html(fromCorrection).contains("""name="_csrf""""), "the script sends the CSRF code of the page, and this page has no form: the code is in a field of its own")
        assertFalse(html(fromCorrection).contains("Questa pagina mostra le segnalazioni"), "and no notice that a reading was written, because none was")
        assertEquals(0, readings(), "nothing was registered")
    }

    @Test
    fun `t15 c37 after the confirmation the correction of a paper form opens with its fields and the notes, and writes one reading`() {
        val t = teacher()
        val paper = paperWithNotes(t)
        PasskeyKit.confirm(t.phone, t.soft!!)

        val page = t.phone.get("/iscrizioni/$paper/correggi-segnalazioni")

        assertEquals(200, page.response.status, page.response.contentAsString.take(300))
        assertTrue(html(page).contains("""name="allergies""""))
        assertTrue(hasNotes(html(page)))
        assertEquals(1, readings())
    }

    @Test
    fun `t15 c37 a teacher without a passkey opens the notes as before, with the reading`() {
        val t = teacher(passkey = false)
        val child = child()

        val opened = detail(t, child)

        assertEquals(200, opened.response.status)
        assertTrue(hasNotes(html(opened)))
        assertEquals(1, readings())
        assertFalse(html(opened).contains("Serve la conferma"))
        val page = html(t.phone.get("/iscrizioni/$child"))
        assertFalse(page.contains("Prima di mostrarle, il telefono ti chiede"), "no sentence about the phone for who has no passkey")
        assertFalse(page.contains("data-conferma="))
    }

    @Test
    fun `t15 c37 the card of the notes says that the phone will ask, and the form carries the mark that the script reads, for a teacher with a passkey`() {
        val t = teacher()
        val child = child()

        val page = html(t.phone.get("/iscrizioni/$child"))

        assertTrue(page.contains("Prima di mostrarle, il telefono ti chiede l’impronta, il volto o il codice di sblocco."))
        assertTrue(Regex("""<form[^>]*data-conferma="segnalazioni"[^>]*>""").containsMatchIn(page))
        assertTrue(page.contains("Per vederle serve la rete: la lettura si registra prima di mostrarle."))
        // The same card is drawn for every child (the words do not depend on the child) and the arrivals have it too.
        val other = child("Anna", "Rossi")
        assertTrue(html(t.phone.get("/arrivi/$other")).contains("Prima di mostrarle, il telefono ti chiede l’impronta, il volto o il codice di sblocco."))
        assertTrue(page.contains("""data-conferma-esito="segnalazioni-annullata"""") && page.contains("""data-conferma-esito="segnalazioni-rifiutata""""), "the outcomes are in the page, hidden")
    }

    @Test
    fun `t15 c37 the confirmation is asked for each child, and saving a correction or a paper form without notes does not ask for it`() {
        val t = teacher()
        val first = child("Elena", "Marino")
        val second = child("Marco", "Neri")
        PasskeyKit.confirm(t.phone, t.soft!!)

        assertEquals(200, detail(t, first).response.status)
        // Another child inside the window: the same confirmation holds, with a reading each (criterion 40).
        assertEquals(200, detail(t, second).response.status)
        assertEquals(2, readings())
        // A paper registration with no notes has nothing to confirm.
        val without = kit.paper(gathering, t.id, "Nicolò", "Ferri")
        confirmedAgo(t, Duration.ofMinutes(11))
        val page = t.phone.get("/iscrizioni/$without/correggi-segnalazioni")
        assertEquals(200, page.response.status)
        assertTrue(html(page).contains("""name="allergies""""), "the fields are there: there was nothing to read")
        assertFalse(html(page).contains("Serve la conferma"))
        // Saving a correction of the notes is not a reading: it asks for nothing, and shows nothing of what was saved.
        val withNotes = paperWithNotes(t)
        val saved = t.phone.submit("/iscrizioni/$without/correggi-segnalazioni", "/iscrizioni/$without/correggi-segnalazioni", mapOf("allergies" to "Latte", "healthConsent" to "true"))
        assertEquals(303, saved.response.status)
        assertNotNull(withNotes)
    }

    // ---- How: the two requests of the phone (criterion 38) ------------------------------------------------------------------------------

    @Test
    fun `t15 c38 the options of the confirmation are the passkeys of the account with the person verified and hybrid, and say nothing when there is nothing to confirm`() {
        val t = teacher()

        val options = PasskeyKit.json(PasskeyKit.confirmationOptions(t.phone))

        assertEquals(true, options["serve"])
        @Suppress("UNCHECKED_CAST")
        val inner = options["options"] as Map<String, Any?>
        assertEquals("required", inner["userVerification"])
        assertEquals(rpId, inner["rpId"])
        @Suppress("UNCHECKED_CAST")
        val allowed = inner["allowCredentials"] as List<Map<String, Any?>>
        assertEquals(listOf(Bytes(t.soft!!.credentialId)), allowed.map { Bytes.fromBase64(it["id"] as String) })
        @Suppress("UNCHECKED_CAST")
        assertEquals(setOf("internal", "hybrid"), (allowed.single()["transports"] as List<String>).toSet(), "the transports of the phone, and hybrid so that a computer offers the phone with the QR code")
        // Without a passkey, or after a confirmation that holds, only «not needed».
        val without = teacher(passkey = false)
        assertEquals(false, PasskeyKit.json(PasskeyKit.confirmationOptions(without.phone))["serve"])
        PasskeyKit.confirm(t.phone, t.soft!!)
        assertEquals(false, PasskeyKit.json(PasskeyKit.confirmationOptions(t.phone))["serve"])
    }

    @Test
    fun `t15 c38 a good answer opens the notes from then on, writes the moment in the session, changes the id of the session and is not an access`() {
        val t = teacher()
        val child = child()
        val sessionBefore = checkNotNull(t.phone.session).id
        val originBefore = t.phone.session!!.getAttribute(SessionOrigins.KEY)
        assertEquals(409, detail(t, child).response.status)

        val answer = PasskeyKit.confirm(t.phone, t.soft!!)

        assertEquals(200, answer.response.status)
        assertEquals(true, PasskeyKit.json(answer)["ok"])
        assertNotEquals(sessionBefore, checkNotNull(t.phone.session).id, "a new id of the session at every change of privilege")
        assertNotNull(t.phone.session!!.getAttribute(PasskeyConfirmations.CONFIRMED))
        assertEquals(originBefore, t.phone.session!!.getAttribute(SessionOrigins.KEY), "no new origin: it is not an access")
        assertEquals(emptyList(), answer.response.cookies.map { it.name }, "no cookie")
        assertEquals(0, readings(), "the confirmation reads nothing")
        val opened = detail(t, child)
        assertEquals(200, opened.response.status)
        assertTrue(hasNotes(html(opened)))
        assertEquals(1, readings(), "one reading, written with the notes, as always")
    }

    @Test
    fun `t15 c38 the challenge is for one use, so the same answer sent twice is refused the second time`() {
        val t = teacher()
        val csrf = t.phone.csrfOf("/profilo")
        @Suppress("UNCHECKED_CAST")
        val challenge = Bytes.fromBase64(((PasskeyKit.json(PasskeyKit.confirmationOptions(t.phone, csrf))["options"] as Map<String, Any?>)["challenge"]) as String)
        val body = SoftPasskey.json.writeValueAsString(t.soft!!.get(challenge))

        assertEquals(200, t.phone.postJson("/verifica-passkey", body, csrf).response.status)
        confirmedAgo(t, Duration.ofMinutes(11))
        val again = t.phone.postJson("/verifica-passkey", body, csrf)

        assertEquals(403, again.response.status)
        assertNull(t.phone.session!!.getAttribute(PasskeyConfirmations.CHALLENGE), "the challenge is spent")
    }

    @Test
    fun `t15 c38 the two addresses want a person who is inside and the CSRF code, and are not public`() {
        val anonymous = phone()
        assertEquals(302, anonymous.postJson("/verifica-passkey/opzioni", csrf = "x").response.status)
        assertEquals(302, anonymous.postJson("/verifica-passkey", "{}", csrf = "x").response.status)
        assertFalse(PublicPaths.matches("/verifica-passkey") || PublicPaths.matches("/verifica-passkey/opzioni"))

        val t = teacher()
        assertEquals(303, t.phone.postJson("/verifica-passkey/opzioni").response.status, "no code from a person who is inside: back to the home")
        assertEquals(0, readings())
    }

    // ---- Without a confirmation, nothing (criterion 39) --------------------------------------------------------------------------------------

    @Test
    fun `t15 c39 a request made by hand, without htmx, gets the whole page that says Serve la conferma, with no notes and no reading`() {
        val t = teacher()
        val child = child()

        val byHand = detail(t, child, htmx = false)
        val arrivals = card(t, child, htmx = false)

        for (answer in listOf(byHand, arrivals)) {
            assertEquals(409, answer.response.status)
            assertTrue(html(answer).contains("<!doctype html>"), "the whole page")
            assertTrue(html(answer).contains("Serve la conferma."))
            assertFalse(hasNotes(html(answer)))
        }
        assertEquals(0, readings())
    }

    @Test
    fun `t15 c39 the reading of the notes wants a proof that only the check can give, and a call without the check does not compile`() {
        val method = HealthNotes::class.java.methods.single { it.name == "readForTeacher" }
        assertEquals(listOf(Long::class.javaPrimitiveType, ConfirmationProof::class.java), method.parameterTypes.toList(), "the second parameter is the proof, not the id of the teacher")
        // The proof is a sealed interface whose only implementation is private and inside the class that checks: nothing else can make one.
        assertEquals(1, ConfirmationProof::class.sealedSubclasses.size)
        val implementation = ConfirmationProof::class.sealedSubclasses.single().java
        assertEquals("talita.account.PasskeyConfirmations\$Proof", implementation.name)
        assertTrue(java.lang.reflect.Modifier.isPrivate(implementation.modifiers) || implementation.declaredConstructors.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) || !java.lang.reflect.Modifier.isPublic(it.modifiers) })
        // And the three places that open the notes get theirs from the check.
        for (file in listOf("registration/TeacherRegistrationsController.kt", "checkin/CardController.kt")) {
            val text = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/kotlin/talita/$file"))
            assertTrue(text.contains("confirmations.gate("), file)
            assertFalse(Regex("""readForTeacher\([^)]*account\.id""").containsMatchIn(text), "$file reads with the id of the teacher")
        }
        val proof = (confirmations.gate(CurrentAccount(1, Addresses.of("x"), null), null) as Gate.Granted).proof
        assertEquals(1L, proof.accountId)
        assertNotNull(healthNotes)
    }

    // ---- How long it holds (criterion 40) -----------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c40 a confirmation holds ten minutes from the moment, the use does not lengthen it and it ends with the session`() {
        val t = teacher()
        val children = listOf(child("Elena", "Marino"), child("Marco", "Neri"), child("Anna", "Rossi"))
        PasskeyKit.confirm(t.phone, t.soft!!)
        val moment = (t.phone.session!!.getAttribute(PasskeyConfirmations.CONFIRMED) as Confirmed).at

        // Several cards one after the other, inside the window: no new confirmation, a reading each, the moment unchanged.
        for (id in children) assertEquals(200, detail(t, id).response.status)
        assertEquals(3, readings())
        assertEquals(moment, (t.phone.session!!.getAttribute(PasskeyConfirmations.CONFIRMED) as Confirmed).at, "the use does not lengthen the window")

        confirmedAgo(t, Duration.ofMinutes(10).minusSeconds(1))
        assertEquals(200, detail(t, children[0]).response.status, "at nine minutes and 59 seconds")
        confirmedAgo(t, Duration.ofMinutes(10))
        assertEquals(409, detail(t, children[0]).response.status, "at ten minutes it asks again")
        // The session ends: a new one does not have it.
        val again = phone()
        again.enter(t.email)
        assertEquals(409, again.submit("/iscrizioni/${children[0]}", "/iscrizioni/${children[0]}/mostra-segnalazioni") { header("HX-Request", "true") }.response.status)
    }

    @Test
    fun `t15 c40 the window is a setting of LoginSettings, ten minutes, and the card says until when it holds`() {
        val t = teacher()
        val child = child()
        assertEquals(Duration.ofMinutes(10), confirmations.let { PasskeyConfirmationsSettings.of(it) })
        PasskeyKit.confirm(t.phone, t.soft!!)

        val page = html(t.phone.get("/iscrizioni/$child"))

        val until = Regex("""Hai già confermato con la passkey: fino alle (\d{2}:\d{2}) le apri senza confermare di nuovo\.""").find(page)
        assertNotNull(until, page)
        val expected = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(talita.ClockConfiguration.ZONE).format((t.phone.session!!.getAttribute(PasskeyConfirmations.CONFIRMED) as Confirmed).at.plus(Duration.ofMinutes(10)))
        assertEquals(expected, until.groupValues[1])
        assertFalse(page.contains("Prima di mostrarle, il telefono ti chiede"), "the sentence that says that the phone will ask would be false now")
    }

    @Test
    fun `t15 c40 the entry with a passkey is a confirmation, the entry with a link or with the cookie is not, and neither is adding the first passkey`() {
        val soft = SoftPasskey(origin, rpId)
        val t = teacher(passkey = false, remember = true)
        val child = child()
        // Adding the first passkey does not confirm: the first reading after it asks.
        PasskeyKit.add(t.phone, soft)
        assertEquals(409, detail(t, child).response.status)

        // The link: a new session, no confirmation.
        val byLink = phone()
        byLink.enter(t.email)
        assertEquals(409, byLink.submit("/iscrizioni/$child", "/iscrizioni/$child/mostra-segnalazioni") { header("HX-Request", "true") }.response.status)

        // The cookie of «Ricordami»: no confirmation either.
        t.phone.forgetSession()
        assertEquals(409, t.phone.submit("/iscrizioni/$child", "/iscrizioni/$child/mostra-segnalazioni") { header("HX-Request", "true") }.response.status)

        // The passkey: the phone has just verified the person, and the notes open at once.
        val byPasskey = phone()
        assertEquals(200, PasskeyKit.signIn(byPasskey, soft).response.status)
        val opened = byPasskey.submit("/iscrizioni/$child", "/iscrizioni/$child/mostra-segnalazioni") { header("HX-Request", "true") }
        assertEquals(200, opened.response.status)
        assertTrue(hasNotes(html(opened)))
    }

    @Test
    fun `t15 c40 the confirmation of a person does not pass to another person who enters in the same session`() {
        val t = teacher()
        PasskeyKit.confirm(t.phone, t.soft!!)
        val other = teacher()
        val child = child()

        // The attribute of the first teacher is in a session that the second one now uses: it is not hers.
        other.phone.session!!.setAttribute(PasskeyConfirmations.CONFIRMED, t.phone.session!!.getAttribute(PasskeyConfirmations.CONFIRMED))

        assertEquals(409, detail(other, child).response.status)
    }

    // ---- From a computer (criterion 41) ------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c41 on a computer the transports let the browser offer the phone with the QR code, and the card says that the browser can ask`() {
        val t = teacher()
        val child = child()

        val page = html(t.phone.get("/iscrizioni/$child"))

        assertTrue(page.contains("Dal computer, il browser può chiederti di usare il telefono."), "in the text of the waiting, which only a precise pointer shows")
        assertTrue(page.contains("[@media(pointer:fine)]:inline"))
        // A phone that said only «internal» is offered with hybrid as well (the options, which the unit test of the operations proves for each transport).
        val options = PasskeyKit.json(PasskeyKit.confirmationOptions(t.phone))
        @Suppress("UNCHECKED_CAST")
        val inner = options["options"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        assertTrue("hybrid" in ((inner["allowCredentials"] as List<Map<String, Any?>>).single()["transports"] as List<*>))
    }

    // ---- Cancelled, refused, no network (criterion 42) ------------------------------------------------------------------------------------------

    @Test
    @CapturesOutput
    fun `t15 c42 a refused answer is a 403 with nothing in the session and a WARN without data, and the notes stay closed`(output: CapturedOutput) {
        val t = teacher()
        val other = teacher()
        val child = child()

        // The phone has been used once, so its counter is above zero: an answer that goes back is a copy of an old one.
        assertEquals(200, PasskeyKit.signIn(phone(), t.soft!!).response.status)
        val bad = listOf<(SoftPasskey, Bytes) -> org.springframework.security.web.webauthn.api.PublicKeyCredential<org.springframework.security.web.webauthn.api.AuthenticatorAssertionResponse>>(
            { s, c -> s.get(c, userVerified = false) },
            { s, c -> s.get(c, origin = "https://altro.example.org") },
            { s, c -> s.get(c, rp = "altro.example.org") },
            { s, c -> s.get(c, counterAfter = 0) },
        )
        for (answer in bad) {
            val result = PasskeyKit.confirm(t.phone, t.soft!!, answer)
            assertEquals(403, result.response.status)
            assertEquals("rifiutata", PasskeyKit.json(result)["stato"])
            assertNull(t.phone.session!!.getAttribute(PasskeyConfirmations.CONFIRMED), "nothing in the session")
        }
        // A passkey of another account: refused as well, whoever it belongs to.
        assertEquals(403, PasskeyKit.confirm(t.phone, other.soft!!).response.status)
        assertEquals(409, detail(t, child).response.status)
        assertEquals(0, readings())
        assertTrue(output.all.contains("Passkey: a confirmation was refused"), "the log has the warning")
        val secrets = listOf(t.email, other.email, Bytes(t.soft!!.credentialId).toBase64UrlString(), Bytes(other.soft!!.credentialId).toBase64UrlString(), t.id.toString() + ",")
        for (secret in secrets) assertFalse(output.all.contains(secret), "the log has $secret")
    }

    @Test
    fun `t15 c42 a challenge that is too old is refused`() {
        val t = teacher()
        val csrf = t.phone.csrfOf("/profilo")
        @Suppress("UNCHECKED_CAST")
        val challenge = Bytes.fromBase64(((PasskeyKit.json(PasskeyKit.confirmationOptions(t.phone, csrf))["options"] as Map<String, Any?>)["challenge"]) as String)
        val pending = t.phone.session!!.getAttribute(PasskeyConfirmations.CHALLENGE) as PendingChallenge
        t.phone.session!!.setAttribute(PasskeyConfirmations.CHALLENGE, pending.copy(at = Instant.now(clock).minus(Duration.ofMinutes(5).plusSeconds(1))))

        val answer = t.phone.postJson("/verifica-passkey", SoftPasskey.json.writeValueAsString(t.soft!!.get(challenge)), csrf)

        assertEquals(403, answer.response.status)
    }

    @Test
    fun `t15 c42 an answer without a challenge that the server gave is refused, and so is one that is not JSON`() {
        val t = teacher()
        val csrf = t.phone.csrfOf("/profilo")

        assertEquals(403, t.phone.postJson("/verifica-passkey", SoftPasskey.json.writeValueAsString(t.soft!!.get(Bytes.random())), csrf).response.status)
        PasskeyKit.confirmationOptions(t.phone, csrf)
        assertEquals(403, t.phone.postJson("/verifica-passkey", "non è json", csrf).response.status)
    }

    // ---- Without JavaScript (criterion 43) ---------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c43 without JavaScript the page says that the confirmation wants it and that another teacher can open the notes, with no button and nothing registered`() {
        val t = teacher()
        val child = child()

        val answer = detail(t, child, htmx = false)

        assertEquals(409, answer.response.status)
        assertTrue(html(answer).contains("Per aprire le segnalazioni devi confermare con la passkey, e la conferma vuole JavaScript, che in questo browser è spento o non funziona. Le segnalazioni le può aprire un altro maestro."))
        assertFalse(html(answer).contains("Per vederle serve la rete"), "no note")
        assertFalse(Regex("""<button[^>]*>[^<]*(<[^>]+>)*[^<]*Mostra le segnalazioni""").containsMatchIn(html(answer).substringAfter("id=\"segnalazioni\"").substringBefore("</section>")), "no button")
        assertEquals(0, readings())
        // The correction: both texts are in the page, and the one for the browser without the script is the one that is not hidden.
        val paper = paperWithNotes(t)
        val page = html(t.phone.get("/iscrizioni/$paper/correggi-segnalazioni"))
        assertTrue(page.contains("""<div data-passkey-no>"""))
        assertTrue(page.contains("Per aprire questa pagina devi confermare con la passkey, e la conferma vuole JavaScript, che in questo browser è spento o non funziona. Un altro maestro può aprire la pagina al posto tuo."))
        assertTrue(page.contains("""<div data-passkey hidden>"""))
        assertTrue(page.contains("""data-azione="conferma-pagina""""))
        assertEquals(0, readings())
    }

    // ---- Passkeys changed with the page open (criterion 44) ------------------------------------------------------------------------------------

    @Test
    fun `t15 c44 the server decides at the tap, whatever the page said when it was drawn`() {
        val t = teacher()
        val child = child()
        assertTrue(html(t.phone.get("/iscrizioni/$child")).contains("data-conferma=\"segnalazioni\""), "the page was drawn with a passkey")
        // The last passkey is taken away (from another tab, or by a colleague): the page is old, and the notes open without a confirmation.
        dsl.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(t.id)).execute()
        assertEquals(200, detail(t, child).response.status)

        // And the other way: the page was drawn without passkeys, and one was added: «Serve la conferma».
        val late = teacher(passkey = false)
        val second = child("Marco", "Neri")
        assertFalse(html(late.phone.get("/iscrizioni/$second")).contains("data-conferma="), "drawn without")
        PasskeyKit.add(late.phone, SoftPasskey(origin, rpId))
        assertEquals(409, detail(late, second).response.status)
    }

    @Test
    fun `t15 c44 a confirmation that was made holds to the end of the window even if its passkey is taken away`() {
        val t = teacher()
        val child = child()
        PasskeyKit.confirm(t.phone, t.soft!!)
        dsl.deleteFrom(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(t.id)).execute()
        assertEquals(200, detail(t, child).response.status)

        // With another passkey, though, the confirmation is the one of before: still valid.
        PasskeyKit.add(t.phone, SoftPasskey(origin, rpId))
        assertEquals(200, detail(t, child).response.status)
    }

    // ---- Taking away and adding (criterion 45) -------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c45 a teacher with a passkey needs the confirmation to take one away, and nothing changes without it, even by hand`() {
        val t = teacher()
        val extra = SoftPasskey(origin, rpId)
        PasskeyKit.confirm(t.phone, t.soft!!)
        assertEquals(200, PasskeyKit.add(t.phone, extra).response.status)
        confirmedAgo(t, Duration.ofMinutes(11))
        val reference = Passkeys.reference(extra.credentialId)

        val answer = PasskeyKit.htmxPost(t.phone, "/profilo", "/profilo/passkey/togli", mapOf("passkey" to reference))

        assertEquals(409, answer.response.status)
        assertTrue(html(answer).contains("Serve la conferma.") && html(answer).contains("Per togliere una passkey il telefono ti chiede l’impronta, il volto o il codice di sblocco. Tocca di nuovo «Togli»."))
        assertEquals(2, dsl.fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(t.id)), "nothing changed")
        // Without htmx, the profile says it wants JavaScript, and nothing is removed.
        val byHand = t.phone.submit("/profilo", "/profilo/passkey/togli", mapOf("passkey" to reference))
        assertEquals(303, byHand.response.status)
        assertTrue(t.phone.body("/profilo").contains("la conferma vuole JavaScript"))
        assertEquals(2, dsl.fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(t.id)))

        PasskeyKit.confirm(t.phone, t.soft!!)
        val done = PasskeyKit.htmxPost(t.phone, "/profilo", "/profilo/passkey/togli", mapOf("passkey" to reference))
        assertEquals(200, done.response.status)
        assertEquals(1, dsl.fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(t.id)))
    }

    @Test
    fun `t15 c45 and to add one, with the options of Spring answering Serve la conferma`() {
        val t = teacher()
        confirmedAgo(t, Duration.ofMinutes(11))

        val options = t.phone.postJson("/webauthn/register/options", csrf = t.phone.csrfOf("/profilo"))

        assertEquals(409, options.response.status)
        assertEquals("serve-conferma", PasskeyKit.json(options)["stato"])
        assertEquals(1, dsl.fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(t.id)))
        PasskeyKit.confirm(t.phone, t.soft!!)
        assertEquals(200, PasskeyKit.add(t.phone, SoftPasskey(origin, rpId)).response.status)
        val html = html(t.phone.get("/profilo/passkey") { header("HX-Request", "true") })
        assertTrue(html.contains("Passkey aggiunta.") && html.contains("Da ora, prima di aprire le segnalazioni dei bambini il telefono ti chiede l’impronta, il volto o il codice di sblocco."), "the notice says it to the teachers")
    }

    @Test
    fun `t15 c45 the first passkey of a teacher and every passkey of a parent are free`() {
        val teacher = teacher(passkey = false)
        assertEquals(200, PasskeyKit.add(teacher.phone, SoftPasskey(origin, rpId)).response.status, "the first one of a teacher")

        val parentEmail = Addresses.unique("genitore-passkey").also { made += it }
        val parent = phone()
        parent.enter(parentEmail)
        repeat(3) { assertEquals(200, PasskeyKit.add(parent).response.status) }
        assertEquals(3, dsl.fetchCount(PASSKEY, PASSKEY.ACCOUNT_ID.eq(dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(parentEmail)))))
        val reference = Passkeys.reference(dsl.select(PASSKEY.CREDENTIAL_ID).from(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(dsl.select(ACCOUNT.ID).from(ACCOUNT).where(ACCOUNT.EMAIL.eq(parentEmail)))).limit(1).fetchSingle(PASSKEY.CREDENTIAL_ID)!!)
        assertEquals(200, PasskeyKit.htmxPost(parent, "/profilo", "/profilo/passkey/togli", mapOf("passkey" to reference)).response.status)
    }

    @Test
    fun `t15 c45 the card of a teacher with a passkey says that the phone asks before Togli and Aggiungi, and what to do if the only passkey is lost`() {
        val t = teacher()

        val html = html(t.phone.get("/profilo"))

        assertTrue(html.contains("Prima il telefono ti chiede di confermare con una passkey che hai già. Poi crea quella nuova."))
        assertTrue(html.contains("Per toglierla, il telefono ti chiede prima l’impronta, il volto o il codice di sblocco."))
        assertTrue(Regex("""data-azione="aggiungi-passkey"[^>]*data-conferma="aggiungi"|data-conferma="aggiungi"[^>]*data-azione="aggiungi-passkey"""").containsMatchIn(html))
        assertTrue(html.contains("""data-conferma="togli""""))
        assertTrue(html.contains("Se la passkey persa era l’unica che avevi, non puoi toglierla da solo: scrivi al"))
        assertTrue(html.contains("""href="/informativa#titolare""""))
        assertTrue(html.contains("Per i maestri «Ricordami su questo telefono» dura 7 giorni: con una passkey non serve chiedere un link nemmeno dopo."))
        for (word in listOf("aggiungi-annullata", "togli-annullata", "togli-rifiutata", "aggiungi-rifiutata")) assertTrue(html.contains(word), word)
        // The lines of the waiting say, as the mockups do, that on a computer the browser can ask for the phone: the dialog of Togli and the two steps of Aggiungi.
        assertTrue(Regex("Dal computer, il browser può chiederti di usare il telefono\\.").findAll(html).count() >= 3, "the waiting lines of Togli and Aggiungi")
        // «Se cambi o perdi il telefono» has the icon of information, as in the mockup, and not the key.
        val summary = html.substringAfter("<summary", "").substringBefore("Se cambi o perdi il telefono")
        assertTrue(summary.contains("M12 16v-4"), "the icon of information")
        // A confirmation that holds: no sentence that the phone will ask.
        PasskeyKit.confirm(t.phone, t.soft!!)
        val valid = html(t.phone.get("/profilo"))
        assertFalse(valid.contains("Prima il telefono ti chiede di confermare con una passkey che hai già."))
        assertFalse(valid.contains("""data-conferma="aggiungi""""))
    }

    // ---- The invitation (criterion 46) ----------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c46 a teacher with no passkey has a discreet line that invites them to add one, in the profile and in the card of a child of the arrivals, and it never closes`() {
        val t = teacher(passkey = false)
        val child = child()

        val profile = html(t.phone.get("/profilo"))
        val arrivals = html(t.phone.get("/arrivi/$child"))

        for (page in listOf(profile, arrivals)) {
            assertTrue(page.contains("Proteggi le segnalazioni con una passkey."))
            assertTrue(page.contains("Prima di aprirle, il telefono ti chiede l’impronta, il volto o il codice di sblocco."))
            assertTrue(Regex("""<div data-passkey hidden class="mt-4 border-t border-line pt-4">""").containsMatchIn(page), "hidden until the script sees the support")
            assertFalse(page.contains("Chiudi") && page.substringAfter("Proteggi le segnalazioni").substringBefore("</div>").contains("Chiudi"), "it does not close")
        }
        assertTrue(arrivals.contains("""href="/profilo#passkey""""))
        assertTrue(profile.contains("Per iniziare, aggiungi una passkey qui sotto."))
        // It does not block the button, and it is there at every visit (nothing is saved of it).
        assertEquals(200, detail(t, child).response.status)
        assertTrue(html(t.phone.get("/arrivi/$child")).contains("Proteggi le segnalazioni con una passkey."))
    }

    @Test
    fun `t15 c46 the invitation is not there for a teacher with a passkey, for a parent, or in the detail of the registration`() {
        val withPasskey = teacher()
        val without = teacher(passkey = false)
        val child = child()
        val parentEmail = Addresses.unique("genitore-invito").also { made += it }
        val parent = phone()
        parent.enter(parentEmail)

        assertFalse(html(withPasskey.phone.get("/profilo")).contains("Proteggi le segnalazioni con una passkey."))
        assertFalse(html(withPasskey.phone.get("/arrivi/$child")).contains("Proteggi le segnalazioni con una passkey."))
        assertFalse(html(parent.get("/profilo")).contains("Proteggi le segnalazioni con una passkey."))
        assertFalse(html(without.phone.get("/iscrizioni/$child")).contains("Proteggi le segnalazioni con una passkey."), "the detail has no invitation: «senza passkey» opens as today, without sentences")
    }

    // ---- Parents (criterion 47) ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c47 a parent, with or without a passkey, never confirms, and a teacher who reads the notes of their own child from the pages of the parent has no confirmation and no register`() {
        val t = teacher()
        val own = kit.registration(gathering, t.id, "Luca", "Verdi", notes = notes)

        val mine = t.phone.get("/iscrizioni/$own/segnalazioni")

        assertEquals(200, mine.response.status, "the page of the parent for their own child")
        assertTrue(hasNotes(html(mine)), "the notes of their own child, read as a parent")
        assertEquals(0, readings(), "no reading is written, as for every parent (TALITA-5)")
        assertFalse(html(mine).contains("Serve la conferma"))

        // From the pages of the teachers it is a reading like the others, with the confirmation.
        assertEquals(409, detail(t, own).response.status)
        PasskeyKit.confirm(t.phone, t.soft!!)
        assertEquals(200, detail(t, own).response.status)
        assertEquals(1, readings())

        // A parent with a passkey is asked for nothing: «nothing to confirm».
        val parent = phone()
        parent.enter(Addresses.unique("genitore-conferma").also { made += it })
        PasskeyKit.add(parent)
        assertEquals(false, PasskeyKit.json(PasskeyKit.confirmationOptions(parent))["serve"])
    }

    // ---- Register and log (criterion 48) --------------------------------------------------------------------------------------------------------------

    @Test
    @CapturesOutput
    fun `t15 c48 after a confirmation the register has the row of always and nothing new, the session has only the moment and the challenge, and the log has no challenge, credential, handle or id`(output: CapturedOutput) {
        val t = teacher()
        val child = child()
        val csrf = t.phone.csrfOf("/profilo")
        @Suppress("UNCHECKED_CAST")
        val challenge = ((PasskeyKit.json(PasskeyKit.confirmationOptions(t.phone, csrf))["options"] as Map<String, Any?>)["challenge"]) as String
        assertNotNull(t.phone.session!!.getAttribute(PasskeyConfirmations.CHALLENGE), "the challenge is in the session until its use")
        t.phone.postJson("/verifica-passkey", SoftPasskey.json.writeValueAsString(t.soft!!.get(Bytes.fromBase64(challenge))), csrf)
        detail(t, child)

        val reading = dsl.selectFrom(HEALTH_NOTE_ACCESS).fetchSingle()
        assertEquals(setOf("id", "registration_id", "teacher_account_id", "accessed_at"), dsl.meta().getTables("health_note_access").single().fields().map { it.name }.toSet(), "the register has no mark of the confirmation")
        assertEquals(t.id, reading.teacherAccountId)
        // In the session: the moment, and the challenge that was spent (it is gone).
        val names = t.phone.session!!.attributeNames.toList().filter { it.startsWith("talita.passkey") }.toSet()
        assertEquals(setOf(PasskeyConfirmations.CONFIRMED), names)
        // In the database: the counter of the passkey, and nothing else changed in its row.
        assertEquals(1L, dsl.select(PASSKEY.SIGNATURE_COUNT).from(PASSKEY).where(PASSKEY.ACCOUNT_ID.eq(t.id)).fetchSingle(PASSKEY.SIGNATURE_COUNT))
        for (secret in listOf(challenge, Bytes(t.soft!!.credentialId).toBase64UrlString(), Bytes(passkeysHandle(t.id)).toBase64UrlString(), t.email)) assertFalse(output.all.contains(secret), "the log has $secret")
    }

    private fun passkeysHandle(accountId: Long): ByteArray = dsl.select(ACCOUNT.PASSKEY_HANDLE).from(ACCOUNT).where(ACCOUNT.ID.eq(accountId)).fetchSingle(ACCOUNT.PASSKEY_HANDLE)!!
}

/** The window of the confirmations as the settings say it (read through the settings bean that the class uses). */
private object PasskeyConfirmationsSettings {
    fun of(confirmations: PasskeyConfirmations): Duration {
        val field = PasskeyConfirmations::class.java.getDeclaredField("settings").also { it.isAccessible = true }
        return (field.get(confirmations) as LoginSettings).confirmationValidity
    }
}
