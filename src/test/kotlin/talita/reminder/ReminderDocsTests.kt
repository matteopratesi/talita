package talita.reminder

import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.account.MockBrowser
import talita.gathering.text
import talita.registration.RegistrationTestKit
import talita.retention.RetentionTexts
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The documents and the texts of TALITA-13 (criterion 14): `docs/02` and `03` with the changes of the comment «Dati personali · dettaglio» (the line of Mailjet and «Prima del rilascio» included),
 * `docs/04` with the new count of the day, `docs/05` with the entry of the ticket and the decision of 24 October, the design guide with its new parts, the mockups, and the two notices on the
 * privacy (the short one at step 2 of the registration and the complete one) with the purpose, the basis and how to stop.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ReminderDocsTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired tokens: OneTimeTokenService,
    @Autowired private val dsl: DSLContext,
    @Autowired private val clock: Clock,
    @Autowired private val texts: RetentionTexts,
) {

    private val registrations = RegistrationTestKit(mockMvc, tokens, dsl, clock)

    private fun doc(path: String): String = Files.readString(Path.of(path)).replace(Regex("""[ \t]*\n[ \t]*"""), " ")

    private fun squeezed(html: String): String = text(html)

    // ---- docs/02 and docs/03 -------------------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c14 the model of the data has the lines of the comment, with the purpose of each new datum, and what is never collected`() {
        val model = doc("docs/02-modello-dati.md")

        for (part in listOf(
            "`account.notice_consent_at`", "`account.reminders_off_at`", "`account.reminder_code`", "`reminder_batch`", "**promemoria delle iscrizioni**",
            "mandare l'avviso solo a chi l'ha chiesto, e poterlo dimostrare (art. 7.1)", "non mandare più promemoria né avvisi all'account che ha smesso, e dimostrare da quando", "che funziona anche se la mail è inoltrata: vale per l'account, e l'app non sa chi ha toccato il link",
            "96 bit casuali", "e non va mai nel log", "**Nessun dato personale**: niente destinatari né maestro", "TALITA-3, 7, 13", "`V11__reminders.sql`",
            "i maestri vedono solo **quante persone** riceveranno un promemoria", "mai a indirizzi presi da fuori", "mai ad account vuoti",
            "**chi ha ricevuto un promemoria, chi l'ha mandato, aperture e clic**", "indirizzi email da liste esterne, per i promemoria",
        )) {
            assertTrue(model.contains(part), "docs/02 does not say: $part")
        }
        assertEquals(1, Regex("""\| Account \| \*\*data e ora del consenso all'avviso\*\*""").findAll(model).count())
    }

    @Test
    fun `t13 c14 the privacy document corrects the line of Mailjet and says the basis, the measures, the times and what is left to check before the release`() {
        val privacy = doc("docs/03-privacy.md")

        // The line of the provider: where the data are, as the provider declares, its role for the antispam, and what it receives.
        val row = privacy.substringAfter("| Invio email: Mailjet").substringBefore("| Backup cifrati fuori sede")
        for (part in listOf(
            "gruppo Sinch", "6.000 mail al mese e 200 al giorno per tutto l'account", "Francoforte e Saint-Ghislain su Google Cloud", "**come dichiara il fornitore**", "DPA Sinch, versione 8 del 29/10/2025",
            "«solo UE» non si scrive finché non è verificato", "**titolare autonomo**", "antispam, sicurezza e frodi", "decisione 2021/914", "Data Privacy Framework", "entro 90 giorni dalla chiusura dell'account",
            "i promemoria (TALITA-13)", "**mai dati dei bambini**", "Parigi è la sede, non il posto dei dati",
        )) {
            assertTrue(row.contains(part), "the line of Mailjet does not say: $part")
        }
        assertFalse(privacy.contains("Mailjet (Parigi, piano gratuito)"), "the old line is gone")
        for (part in listOf(
            "art. 6.1.b", "art. 6.1.a", "art. 130.4", "**Promemoria per email** (TALITA-13)", "`reminder_batch`", "`talita.mail.reminder-daily-limit`", "190 in tutto", "**mai nel log**", "Chi inoltra la mail",
            "| Consenso all'avviso, stop ai promemoria, codice per smettere", "| Registro degli invii dei promemoria", "**Consenso all'avviso** (TALITA-13)", "**si oppone ai promemoria con un tocco", "il link funziona anche se la mail è inoltrata, e in quel caso lo stop può averlo chiesto chi ha ricevuto la mail, non il genitore",
        )) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        val before = privacy.substringAfter("## Prima del rilascio").substringBefore("## Diritti degli interessati")
        for (part in listOf("il parere sulla base (6.1.b oppure 6.1.f)", "linee guida del Garante sullo spam del 4/7/2013", "il testo del consenso all'avviso", "la verifica dei trasferimenti nell'accordo di Mailjet", "la DPIA comprende i promemoria", "SPF e DKIM")) {
            assertTrue(before.contains(part), "«Prima del rilascio» does not say: $part")
        }
    }

    @Test
    fun `t13 c14 the guide of the environment has the count of the reminders and how to try them with Mailpit`() {
        val environment = doc("docs/04-ambiente.md")

        for (part in listOf("`talita.mail.reminder-daily-limit`", "**40 per i promemoria**", "**150 mail**", "Insieme sono 190", "6.000 al mese", "Mailpit", "/promemoria/smetti", "`List-Unsubscribe`", "TALITA-9")) {
            assertTrue(environment.contains(part), "docs/04 does not say: $part")
        }
    }

    // ---- docs/05 -------------------------------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c14 the decision log has the entry of the ticket after the earlier ones, with the decision of Matteo of 24 October and the differences from the analysis`() {
        val log = doc("docs/05-decisioni.md")

        assertTrue(log.contains("## 2026-10-29: scelte di TALITA-13"))
        assertTrue(log.indexOf("## 2026-10-24: scelte di TALITA-7") < log.indexOf("## 2026-10-29: scelte di TALITA-13"), "in order of date")
        val entry = log.substringAfter("## 2026-10-29: scelte di TALITA-13").substringBefore(" ## 2026-10-29: scelte di TALITA-1").substringBefore(" ## 2026-10-30")
        for (part in listOf(
            "**Le decisioni di Matteo (24/10/2026)**", "commento 10953", "commento 10950", "vincolante per sviluppo e QA", "«Conferma l'iscrizione»", "«Ci penso ancora un po'»", "**Niente invito a ritirare",
            "«ritira» né «ritirare»", "«riceveranno un invito a confermare l'iscrizione»", "`V11__reminders.sql`", "**`Mailer.sendAll`**", "**Due conti**", "`pg_advisory_xact_lock(13,", "**Differenza dall'analisi: l'attesa è di 168 ore esatte**",
            "`ReminderCards`", "**`/promemoria/smetti?codice=…`**", "guscio senza accesso di `/accedi`", "byte per byte", "**la riga di Mailjet corretta**", "**Prima del rilascio**", "`docs/design/TALITA-13/`",
        )) {
            assertTrue(entry.contains(part), "the entry of TALITA-13 misses: $part")
        }
    }

    // ---- The guide and the mockups -----------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c14 the design guide has the icons, the page for stopping, the card with blocks, the dialog that sends, the box of consent, the htmx part and the texts with the mails`() {
        val guide = doc("docs/design/README.md")

        for (part in listOf(
            "| Manda il promemoria | `send` |", "| Riprendi i promemoria | `bell` |", "| Non mandarmi più promemoria | `bell-off` |", "| La pagina per smettere i promemoria | `mail-x` |",
            "**La pagina per smettere i promemoria** (`/promemoria/smetti`, TALITA-13)", "guscio senza accesso di `/accedi`", "**Scheda con più blocchi**", "**Dialogo di invio**", "**Casella di consenso**", "mai spuntata prima",
            "«Salva la scelta»", "### Promemoria (TALITA-13)", "Un invio che sostituisce una scheda intera", "Una richiesta ripetuta non è un errore", "**Le mail** (una voce nuova)", "«Conferma l’iscrizione», «Ci penso ancora un po’»",
            "«Potrai rimandarlo dal 21 giugno 2027 alle 21:30.»",
        )) {
            assertTrue(guide.contains(part), "the guide does not say: $part")
        }
        assertEquals(2, Regex("""### Promemoria \(TALITA-13\)""").findAll(guide).count(), "one part in htmx and one in the texts")
    }

    @Test
    fun `t13 c14 the mockups are in the repository, synthetic, with no style, no script and nothing from outside, and the mail in forse follows the decision of Matteo`() {
        val dir = Path.of("docs/design/TALITA-13")

        assertEquals(21, Files.list(dir).use { it.count() }, "the 20 pages and the style")
        val pages = Files.list(dir).use { stream -> stream.filter { it.toString().endsWith(".html") }.toList() }
        assertEquals(20, pages.size)
        for (page in pages) {
            val html = Files.readString(page)
            assertFalse(Regex("""\sstyle=""").containsMatchIn(html), "$page has an inline style")
            assertFalse(html.contains("<script"), "$page has a script")
            assertFalse(Regex("""(src|href)="https?://""").containsMatchIn(html), "$page loads something from outside")
            assertFalse(Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""").findAll(html).any { it.value != "nome@example.org" }, "$page has an address that is not the placeholder")
        }
        val mail = Files.readString(dir.resolve("mail-in-forse.html"))
        assertTrue(mail.contains("Conferma l’iscrizione: entra in Talità, apri la pagina «Iscrizioni» e cambia lo stato in «Confermata»."))
        assertTrue(mail.contains("Ci penso ancora un po’: non devi fare niente."))
        assertFalse(mail.substringAfter("""<div class="mail__body">""").substringBefore("</div>").lowercase().contains("ritira"), "no invitation to withdraw in the text of the mail")
        val dialog = Files.readString(dir.resolve("promemoria-dialogo.html"))
        assertTrue(dialog.contains("riceveranno un invito a confermare l’iscrizione"))
        assertFalse(dialog.contains("confermare o ritirare"))
        assertTrue(Files.readString(dir.resolve("informativa-promemoria.html")).contains("ricordarti di confermare un’iscrizione in forse"))
    }

    // ---- The notices on the privacy (the two layers) ------------------------------------------------------------------------------------

    @Test
    fun `t13 c14 the complete notice says the purpose, the basis, the provider as an autonomous controller for the antispam, the times and how to stop`() {
        val complete = squeezed(MockBrowser(mockMvc).get("/informativa").response.contentAsString)

        for (part in listOf(
            "la data e l’ora della richiesta di avviso, se l’ha chiesta, e la data e l’ora dello stop ai promemoria, se c’è.",
            "Lo stop si chiede dal profilo o dal link «Non mandarmi più promemoria» che c’è in ogni mail: il link funziona anche se la mail viene inoltrata, e in quel caso può averlo chiesto chi l’ha ricevuta, e Talità non sa chi è.",
            "Il tuo indirizzo email serve anche a ricordarti di confermare un’iscrizione in forse: lo facciamo perché hai fatto l’iscrizione. L’avviso per una nuova convivenza ti arriva solo se lo chiedi: ci vuole il tuo consenso, e lo togli quando vuoi. In ogni mail c’è il modo per smettere.",
            "il servizio che manda le email, che riceve l’indirizzo e il testo del messaggio (anche dei promemoria, mai con dati dei bambini) e che per proteggersi da spam e frodi usa l’indirizzo come titolare autonomo, secondo la sua informativa;",
            "La richiesta di avviso e lo stop ai promemoria", "Finché non li cambi o non cancelli l’account.",
            "I promemoria li fermi con un tocco dalla mail o dal profilo, e la richiesta di avviso la togli quando vuoi.",
        )) {
            assertTrue(complete.contains(part), "the complete notice does not say: $part")
        }
        assertTrue(texts.rights.any { it.contains("I promemoria li fermi con un tocco dalla mail o dal profilo, e la richiesta di avviso la togli quando vuoi.") })
        assertTrue(texts.completeRows().any { it.what == "La richiesta di avviso e lo stop ai promemoria" })
        assertFalse(complete.contains("Mailjet"), "the name of the provider is not written in the texts for the people (it is in docs/03)")
    }

    @Test
    fun `t13 c14 the short notice of step 2 says the purpose, who receives the mails, the time and how to stop`() {
        val setup = registrations.start()
        val parent = registrations.parent()
        val form = registrations.form(registrations.child(0, "Luca", "Rossi"))
        val second = squeezed(parent.submit(setup.form, setup.form, form).response.contentAsString)

        for (part in listOf(
            "Se hai un’iscrizione in forse ti scriviamo per ricordartelo; l’avviso per una nuova convivenza solo se lo chiedi. In ogni mail c’è il modo per smettere.",
            "a chi manda le email, che riceve l’indirizzo e il testo, anche dei promemoria, mai con dati dei bambini",
            "I promemoria li fermi con un tocco dalla mail o dal profilo.", "La richiesta di avviso, finché non la togli.",
        )) {
            assertTrue(second.contains(part), "the short notice does not say: $part")
        }
        assertTrue(texts.briefTime().endsWith("La richiesta di avviso, finché non la togli."))
        assertTrue(texts.briefRights.contains("I promemoria li fermi con un tocco dalla mail o dal profilo."))
    }

    // ---- The words ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun `t13 c14 the sentences of the reminders have none of the words of the machine or of the other project`() {
        val forbidden = listOf("Koin" + "è", "Koin" + "e", "centro", "comunità", "fratelli")
        val jargon = listOf("database", "server", "sessione", "utente", "login", "errore 500")
        for (file in listOf("ReminderMails.kt", "ReminderPresenter.kt", "NoticePresenter.kt")) {
            val source = Files.readString(Path.of("src/main/kotlin/talita/reminder/$file"))
            val sentences = Regex(""""([^"\n]{12,})"""").findAll(source).map { it.groupValues[1] }.filter { it.contains(' ') && !it.startsWith("talita.") && !it.startsWith("/") }.toList()
            assertTrue(sentences.size >= 3, "$file has ${sentences.size} sentences")
            for (sentence in sentences) {
                for (word in jargon + forbidden) assertFalse(sentence.contains(word, ignoreCase = true), "«$sentence» has «$word»")
            }
        }
        for (file in Files.list(Path.of("src/main/jte/reminder")).use { it.toList() }) {
            val body = Files.readString(file)
            assertFalse(Regex("""\sstyle=""").containsMatchIn(body), "$file has an inline style")
            assertFalse(Regex("""<script(?![^>]*\ssrc=)""").containsMatchIn(body), "$file has an inline script")
            assertFalse(Regex("""\son[a-z]+=""").containsMatchIn(body), "$file has a handler")
            assertFalse(Regex("""(src|href)="https?://""").containsMatchIn(body), "$file loads something from outside")
        }
    }
}
