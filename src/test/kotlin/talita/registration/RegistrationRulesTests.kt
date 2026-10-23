package talita.registration

import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import talita.gathering.Fees
import talita.gathering.GatheringFormat
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two rules that do not need a database: the fee that is due (criterion 4) and the model of the text of the custody (criterion 35). Dates are
 * fixed: nothing here looks at the calendar.
 */
class RegistrationRulesTests {

    private val friday = LocalDate.of(2027, 9, 17)
    private val saturday = friday.plusDays(1)
    private val sunday = friday.plusDays(2)
    private val days = listOf(friday, saturday, sunday)

    private fun offer(full: Int? = 6000, daily: Int? = 2500, lunch: Int? = 1000, on: List<LocalDate> = days) = Offer(Fees(full, daily, lunch), on)

    private fun due(offer: Offer, vararg picked: Pair<LocalDate, DayKind>): Int? = Quota.due(offer, Presence(false, mapOf(*picked)))

    // ---- The fee (criterion 4) -----------------------------------------------------------------------------------------------

    @Test
    fun `t5 c04 every day is the full fee`() {
        assertEquals(6000, Quota.due(offer(), Presence.ALL))
    }

    @Test
    fun `t5 c04 some days add the daily fee for a whole day and the lunch fee for a lunch only`() {
        val offer = offer()

        assertEquals(5000, due(offer, friday to DayKind.FULL, saturday to DayKind.FULL))
        assertEquals(3500, due(offer, saturday to DayKind.FULL, sunday to DayKind.LUNCH))
        assertEquals(1000, due(offer, sunday to DayKind.LUNCH))
        assertEquals(2500, due(offer, friday to DayKind.FULL))
    }

    @Test
    fun `t5 c04 the three whole days picked one by one are worth every day and are written as such`() {
        val offer = offer()
        val picked = mapOf(friday to DayKind.FULL, saturday to DayKind.FULL, sunday to DayKind.FULL)

        val (presence, error) = Quota.parse(offer, "days") { key -> if (key in setOf("fri", "sat", "sun")) "full" else "" }
        assertNull(error)
        assertEquals(Presence.ALL, presence)
        assertEquals(Presence.ALL, Quota.normalize(offer, Presence(false, picked)))
        assertEquals(6000, Quota.due(offer, presence!!))
        // With a lunch among them it is not every day.
        assertEquals(
            Presence(false, mapOf(friday to DayKind.FULL, saturday to DayKind.FULL, sunday to DayKind.LUNCH)),
            Quota.normalize(offer, Presence(false, picked + (sunday to DayKind.LUNCH))),
        )
    }

    @Test
    fun `t5 c04 a fee at zero is Gratuita and the sum is zero`() {
        val offer = offer(full = 0, daily = 0, lunch = 0)

        assertEquals(0, Quota.due(offer, Presence.ALL))
        assertEquals(0, due(offer, friday to DayKind.FULL, saturday to DayKind.LUNCH))
        assertEquals("Gratuita", GatheringFormat.fee(0))
    }

    @Test
    fun `t5 c04 a fee that is not set is not offered and a choice that needs it is refused`() {
        val noDaily = offer(daily = null)
        assertEquals(listOf(DayKind.LUNCH), noDaily.kinds)
        assertNull(due(noDaily, friday to DayKind.FULL))
        assertEquals(PresenceError.NOT_OFFERED, Quota.parse(noDaily, "days") { if (it == "fri") "full" else "" }.second)
        assertEquals(1000, due(noDaily, friday to DayKind.LUNCH))

        val noLunch = offer(lunch = null)
        assertEquals(listOf(DayKind.FULL), noLunch.kinds)
        assertNull(due(noLunch, friday to DayKind.LUNCH))
        assertEquals(PresenceError.NOT_OFFERED, Quota.parse(noLunch, "days") { if (it == "fri") "lunch" else "" }.second)

        val onlyFull = offer(daily = null, lunch = null)
        assertFalse(onlyFull.someDays)
        assertFalse(onlyFull.choice)
        assertEquals(PresenceError.NOT_OFFERED, Quota.parse(onlyFull, "days") { "full" }.second)
        assertEquals(Presence.ALL, Quota.parse(onlyFull, "all_days") { "" }.first)
    }

    @Test
    fun `t5 c04 the days of the choice are the days of the gathering`() {
        assertNull(due(offer(), LocalDate.of(2027, 9, 20) to DayKind.FULL))
        assertNull(Quota.due(offer(), Presence(false, emptyMap())))
        assertEquals(PresenceError.NONE_CHOSEN, Quota.parse(offer(), "") { "" }.second)
        assertEquals(PresenceError.NONE_CHOSEN, Quota.parse(offer(), "  ") { "" }.second)
        assertEquals(PresenceError.NO_DAY, Quota.parse(offer(), "days") { "" }.second)
        assertEquals(PresenceError.NOT_OFFERED, Quota.parse(offer(), "days") { if (it == "fri") "dinner" else "" }.second)
        assertEquals(PresenceError.NOT_OFFERED, Quota.parse(offer(), "weekly") { "" }.second)
    }

    @Test
    fun `t5 c04 a gathering of one day has the whole day and, if the lunch fee is set, the lunch only`() {
        val one = offer(on = listOf(saturday))

        assertTrue(one.oneDay)
        assertFalse(one.someDays)
        assertTrue(one.lunchOnlyOneDay)
        assertTrue(one.choice)
        assertEquals(6000, Quota.due(one, Presence.ALL))
        val (lunch, error) = Quota.parse(one, "lunch") { "" }
        assertNull(error)
        assertEquals(1000, Quota.due(one, lunch!!))
        // Not «some days»: with one day there is nothing to pick.
        assertEquals(PresenceError.NOT_OFFERED, Quota.parse(one, "days") { "full" }.second)
        assertEquals(PresenceError.NOT_OFFERED, Quota.parse(offer(on = listOf(saturday), lunch = null), "lunch") { "" }.second)
    }

    @Test
    fun `t5 c04 the key of a day is the one of its weekday`() {
        assertEquals(listOf("fri", "sat", "sun"), days.map(Quota::key))
        assertEquals("mon", Quota.key(LocalDate.of(2027, 9, 20)))
    }

    // ---- The model of the text (criterion 35) --------------------------------------------------------------------------------

    private val luca = CustodyChild("Luca Rossi", LocalDate.of(2016, 4, 4))
    private val anna = CustodyChild("Anna Rossi", LocalDate.of(2019, 1, 9))

    private fun data(children: List<CustodyChild>, custodians: List<String>) = CustodyData(
        email = "nome@example.org",
        children = children,
        custodians = custodians,
        title = "Convivenza di settembre 2027",
        place = "Casa per ritiri di esempio",
        start = friday,
        end = sunday,
    )

    @Test
    fun `t5 c35 the fingerprint of the model of each version is fixed, so a change needs a new version`() {
        // The text of `affidamento-1` is the one that was approved. To change it, write `affidamento-2.txt` and its fingerprint here;
        // this one stays as it is, because the registrations that accepted it keep the version.
        val bytes = ClassPathResource("registration/affidamento-1.txt").inputStream.use { it.readAllBytes() }
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        assertEquals("affidamento-1", CustodyText.VERSION)
        assertEquals("d4575ee4d07b5ccbab3f572f102df6c141bd126da5b72bd7ddd9205dc82ff9ac", fingerprint)
        assertEquals(64, CustodyText.hash("any text").length)
        assertEquals(CustodyText.hash("a"), CustodyText.hash("a"))
        assertTrue(CustodyText.hash("a") != CustodyText.hash("b"))
    }

    @Test
    fun `t5 c35 the text of one child and one custodian uses the first form of the two rules`() {
        val text = CustodyText.render(data(listOf(luca), listOf("Marta Bianchi")))

        assertEquals(
            "Affidamento di figli minorenni\n\n" +
                "Io, titolare dell’account nome@example.org, affido Luca Rossi, nato/a il 4 aprile 2016 alla responsabilità di Marta Bianchi " +
                "per la durata della convivenza «Convivenza di settembre 2027», che si svolge a Casa per ritiri di esempio dal 17 settembre 2027 " +
                "al 19 settembre 2027, e lo/la autorizzo a prendere, in mia assenza, tutte le decisioni necessarie per il suo bene.",
            text.body,
        )
        assertEquals("Dichiaro di accettare anche con il consenso dell’altro genitore, oppure di esercitare da solo/a la responsabilità genitoriale.", text.declaration)
        assertEquals(text.body + "\n\n" + text.declaration, text.full)
    }

    @Test
    fun `t5 c35 the text of two children and two custodians uses the second form`() {
        val text = CustodyText.render(data(listOf(luca, anna), listOf("Marta Bianchi", "Paolo Verdi")))

        assertTrue(text.body.contains("affido Luca Rossi, nato/a il 4 aprile 2016 e Anna Rossi, nato/a il 9 gennaio 2019 alla responsabilità di Marta Bianchi e Paolo Verdi per"), text.body)
        assertTrue(text.body.contains(", e li autorizzo a prendere, in mia assenza, tutte le decisioni necessarie per il loro bene."), text.body)
        assertFalse(text.body.contains("{") || text.body.contains("}"), "a placeholder was left")
    }

    @Test
    fun `t5 c35 the rules follow the children and the custodians separately`() {
        val oneChildTwoCustodians = CustodyText.render(data(listOf(luca), listOf("Marta Bianchi", "Paolo Verdi"))).body
        assertTrue(oneChildTwoCustodians.contains("e li autorizzo") && oneChildTwoCustodians.contains("per il suo bene"), oneChildTwoCustodians)

        val twoChildrenOneCustodian = CustodyText.render(data(listOf(luca, anna), listOf("Marta Bianchi"))).body
        assertTrue(twoChildrenOneCustodian.contains("e lo/la autorizzo") && twoChildrenOneCustodian.contains("per il loro bene"), twoChildrenOneCustodian)

        val three = CustodyText.render(data(listOf(luca), listOf("Marta Bianchi", "Paolo Verdi", "Elisa Conti"))).body
        assertTrue(three.contains("alla responsabilità di Marta Bianchi, Paolo Verdi e Elisa Conti per"), three)
    }

    @Test
    fun `t5 c35 the text is made from a model with the rules and the placeholders, and the model has nothing left to fill`() {
        val model = "{b:uno|molti} {a:solo|tanti} {titolo} {bambini}\n\ndichiarazione {indirizzo}"
        val rendered = CustodyText.render(data(listOf(luca, anna), listOf("Marta Bianchi")), model)

        assertEquals("molti solo Convivenza di settembre 2027 Luca Rossi, nato/a il 4 aprile 2016 e Anna Rossi, nato/a il 9 gennaio 2019", rendered.body)
        assertEquals("dichiarazione nome@example.org", rendered.declaration)
        assertNotNull(CustodyText.model)
        assertFalse(CustodyText.model.contains("[Segnaposto") || CustodyText.model.contains("[TESTO DI PROVA"), "the model has a placeholder that nobody filled")
    }

    // ---- The documents (criterion 37) --------------------------------------------------------------------------------------------

    private fun doc(path: String): String = java.nio.file.Files.readString(java.nio.file.Path.of(path)).replace(Regex("""\s+"""), " ")

    @Test
    fun `t5 c37 the documents have what the ticket decided`() {
        val data = doc("docs/02-modello-dati.md")
        for (part in listOf(
            "indirizzo email di chi accetta", "maestro che l'ha inserita dal cartaceo", "data e ora del consenso", "health_note_access", "`V5__registration.sql`",
            "**confermata** (era «proposta»)", "**La quota dovuta non si salva**", "nome del genitore che accetta",
        )) {
            assertTrue(data.contains(part), "docs/02 does not say: $part")
        }
        assertFalse(data.contains("Nome del genitore che accetta l'affidamento (TALITA-5)"), "the question of the parent's name is closed")
        val privacy = doc("docs/03-privacy.md")
        for (part in listOf("Informativa a strati", "Consenso alle segnalazioni", "firma elettronica semplice", "## Prima del rilascio", "la DPIA", "registro dei trattamenti", "una sola classe del codice")) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        val environment = doc("docs/04-ambiente.md")
        assertTrue(environment.contains("TALITA_PRIVACY_CONTROLLER_NAME") && environment.contains("TALITA_PRIVACY_CONTACT") && environment.contains("-Prelease"))
        val decisions = doc("docs/05-decisioni.md")
        assertTrue(decisions.contains("## 2026-10-23: scelte di TALITA-5") && decisions.contains("## 2026-10-23: il repo resta pubblico (TALITA-5)"))
        assertTrue(decisions.indexOf("il repo resta pubblico (TALITA-5)") < decisions.indexOf("scelte di TALITA-5"), "the entry about the public repo comes before the one of the ticket")
        val guide = doc("docs/design/README.md")
        for (part in listOf("### 4.15 Passi", "### 4.16 Quota che si aggiorna", "### 4.17 Sezione che si apre", "### 4.18 Testo lungo e cornice del testo di prova", "### 4.19 Azioni di una scheda", "Scelta a segmenti")) {
            assertTrue(guide.contains(part), "the guide does not say: $part")
        }
    }
}
