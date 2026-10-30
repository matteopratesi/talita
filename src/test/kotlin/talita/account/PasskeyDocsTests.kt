package talita.account

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.ott.OneTimeTokenService
import org.springframework.test.web.servlet.MockMvc
import talita.TestcontainersConfiguration
import talita.gathering.text
import talita.retention.RetentionPolicy
import talita.retention.RetentionTexts
import java.nio.file.Files
import java.nio.file.Path
import java.time.Period
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The notice and the documents of TALITA-15 (criteria 34 and 35). The complete notice has the card «Cookie e accesso» and the two new rows of «Per quanto tempo», with the numbers of the code and none written by
 * hand; `docs/02` to `docs/05` and the design guide have what the comments of the analysis ask for, and the mockups are in the repository.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class PasskeyDocsTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val tokens: OneTimeTokenService,
    @Autowired private val texts: RetentionTexts,
    @Autowired private val sessionTexts: SessionTexts,
) {

    private fun doc(path: String): String = Files.readString(Path.of(path)).replace(Regex("""\s+"""), " ")

    private fun notice(): String = text(Phone(mockMvc, tokens).get("/informativa").response.contentAsString)

    // ---- The complete notice (criterion 34) ------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c34 the complete notice has the card Cookie e accesso with the cookie of the session, the cookie of the box, no other cookie and the passkeys, with the numbers of the code`() {
        val page = notice()

        val card = page.substringAfter("Cookie e accesso").substringBefore("Per quanto tempo")
        for (part in listOf(
            "Talità usa solo i cookie che servono a farti entrare. Non ci sono cookie di altri, né statistiche, né pubblicità.",
            "Il cookie che ti tiene dentro mentre usi Talità: l’accesso si chiude da solo dopo ${sessionTexts.idleWords} che non la usi, e comunque dopo ${sessionTexts.capWords} dall’entrata.",
            "Il cookie di «Ricordami su questo telefono»: lo mettiamo solo se spunti la casella quando entri. Dura ${texts.devicesParent}, ${texts.devicesTeacherNumber} per i maestri, e contiene solo due codici casuali: non dice chi sei. Lo togli con «Esci», o dal tuo profilo.",
            "Le passkey restano nel tuo telefono: a Talità arriva solo il pezzo che serve a controllare che la passkey sia tua, mai quello che serve a usarla.",
            "Se il telefono le sincronizza con il tuo account Apple o Google, lo fa il servizio che hai scelto, in modo cifrato: Talità non manda niente né ad Apple né a Google.",
            "Per i maestri con una passkey: prima di aprire le segnalazioni dei bambini c’è una conferma con la passkey. Ci ricordiamo solo l’ora della conferma, e solo finché resta aperto l’accesso.",
            "Né «Ricordami su questo telefono» né le passkey sono obbligatori: il link funziona sempre.",
        )) {
            assertTrue(card.contains(part), "the card does not say: $part")
        }
        assertTrue(page.indexOf("Chi li riceve per far funzionare Talità") < page.indexOf("Cookie e accesso"), "after the providers")
        assertTrue(page.indexOf("Cookie e accesso") < page.indexOf("Per quanto tempo"), "before the times")
        // The numbers are the ones of the policy and of the settings (the defaults of the product).
        assertEquals("2 ore", sessionTexts.idleWords)
        assertEquals("12 ore", sessionTexts.capWords)
        assertEquals("30 giorni", texts.devicesParent)
        assertEquals("7", texts.devicesTeacherNumber)
    }

    @Test
    fun `t15 c34 Per quanto tempo has the rows of the devices and of the passkeys, and the small additions say the data, the purpose, who sees them and the rights`() {
        val page = notice()

        val times = page.substringAfter("Per quanto tempo")
        assertTrue(times.contains("Dispositivi collegati («Ricordami su questo telefono») 30 giorni dal giorno in cui spunti la casella, 7 per i maestri. Prima, se esci, li togli dal tuo profilo o cancelli l’account. Talità li cancella da sola, ogni notte, quando scadono."))
        assertTrue(times.contains("Passkey Finché non le togli dal tuo profilo o non cancelli l’account. Se l’account si cancella da solo perché è vuoto, si cancellano con lui."))
        for (part in listOf(
            "Di chi sceglie «Ricordami su questo telefono» o di usare una passkey: il giorno in cui l’ha fatto e un codice casuale che riconosce il dispositivo o la passkey. Mai il modello del telefono, il browser o il luogo.",
            "«Ricordami su questo telefono» e le passkey: li usiamo perché lo chiedi tu. Sono facoltativi, e il link funziona sempre.",
            "I dispositivi collegati e le passkey li vedi solo tu, nel tuo profilo.",
            "Sempre dal «Profilo» togli i dispositivi collegati e le passkey.",
        )) {
            assertTrue(page.contains(part), "the notice does not say: $part")
        }
        // Not a word of cookies of others, of a banner or of a tool of statistics: the notice says that there are none.
        assertFalse(page.contains("banner"))
        assertFalse(Regex("""<script""").containsMatchIn(Phone(mockMvc, tokens).get("/informativa").response.contentAsString))
    }

    @Test
    fun `t15 c34 the rows of the times follow the policy, with a policy of other periods every text follows it`() {
        val other = RetentionTexts(RetentionPolicy(devicesParent = Period.ofDays(20), devicesTeacher = Period.ofDays(3)))

        val row = other.completeRows().single { it.what.startsWith("Dispositivi collegati") }

        assertTrue(row.time.startsWith("20 giorni dal giorno in cui spunti la casella, 3 per i maestri."), row.time)
        assertEquals("20 giorni", other.devicesParent)
        assertEquals("3", other.devicesTeacherNumber)
    }

    // ---- The documents (criterion 35) ------------------------------------------------------------------------------------------------------

    @Test
    fun `t15 c35 the model of the data has the new rows with their purpose and what is not collected`() {
        val model = doc("docs/02-modello-dati.md")

        for (part in listOf(
            "`remembered_device`", "`V12__devices_and_passkeys.sql`", "SHA-256 del valore del cookie", "Il valore non si salva mai", "**Niente ultimo uso, nome del dispositivo, browser, indirizzo di rete né luogo**",
            "Cookie «Ricordami su questo telefono»", "Passkey (`passkey`)", "**Non si salvano** attestazione, client data e AAGUID", "`account.passkey_handle`", "lo «user handle» di WebAuthn", "`account.session_generation`",
            "chiude il caso che TALITA-7 aveva lasciato aperto", "il momento dell'ultima conferma con la passkey, e la sfida della conferma fino al suo uso", "il registro delle letture non cambia",
            "**nemmeno l'ultimo uso di un dispositivo o di una passkey**", "**con quale passkey si conferma, quante conferme ci sono state, quelle annullate o rifiutate**", "TALITA-15",
        )) {
            assertTrue(model.contains(part), "docs/02 does not say: $part")
        }
    }

    @Test
    fun `t15 c35 the privacy document has the measures, the limits of the confirmation, the providers that are not, the times and what is left to check before the release`() {
        val privacy = doc("docs/03-privacy.md")

        for (part in listOf(
            "**«Ricordami su questo telefono»** (TALITA-15)", "**impronta SHA-256 del valore**", "**il valore cambia a ogni rientro**", "**La durata è del ruolo di adesso**", "Al massimo 10 dispositivi per account",
            "**Il contatore delle sessioni**", "**Le passkey** (TALITA-15)", "**Verifica della persona obbligatoria**", "attestazione `none`", "**Il link resta**", "**La conferma con la passkey** (TALITA-15",
            "**Vale 10 minuti**", "**non si legge niente e non si scrive nessuna lettura**", "Un maestro senza passkey non conferma", "**I limiti**, da dire: il codice è quello del telefono, e chi lo conosce supera la conferma",
            "dopo 12 ore** dall'accesso", "**Passkey (TALITA-15): nessun fornitore nuovo.**", "FIDO Metadata Service", "il computer trova il telefono via Bluetooth", "non fornitori di Talità",
            "| Dispositivi collegati (`remembered_device`, TALITA-15)", "| Passkey (`passkey`, TALITA-15)", "i cookie e l'accesso** (TALITA-15, scheda «Cookie e accesso»)", "**cookie e accesso** (TALITA-15)",
            "**passkey e conferma sui dispositivi veri**", "**il dominio di `talita.public-url` non cambia più dopo il rilascio**", "**toglie dispositivi e passkey dal profilo** (TALITA-15)", "senza banner",
        )) {
            assertTrue(privacy.contains(part), "docs/03 does not say: $part")
        }
        assertTrue(privacy.contains("linee guida del Garante del 10/06/2021"))
    }

    @Test
    fun `t15 c35 the environment document has the rpId, the loss of the passkeys when the domain changes and the way the owner takes away a lost one on the server`() {
        val environment = doc("docs/04-ambiente.md")

        for (part in listOf(
            "## Le passkey e «Ricordami su questo telefono»", "**L'`rpId` è il dominio di `TALITA_PUBLIC_URL`** (senza porta)", "l'`rpId` è `localhost`", "**Cambiare dominio fa perdere le passkey.**", "il titolare cancella tutte le passkey sul server",
            "delete from passkey;", "**La passkey persa di un maestro.**", "solo su un dispositivo", "delete from passkey where account_id = (select id from account where email = 'nome@example.org');",
            "autenticatore virtuale di Chromium", "`SoftPasskey`", "regola 1 di `CLAUDE.md`", "talita.login.session-max-age", "talita.login.confirmation-validity", "Da TALITA-15 è anche il sito delle passkey",
        )) {
            assertTrue(environment.contains(part), "docs/04 does not say: $part")
        }
    }

    @Test
    fun `t15 c35 the decision log has the entry of TALITA-15 with the answers of Matteo, the differences from the analysis and what is left to try by hand`() {
        val log = doc("docs/05-decisioni.md")

        val entry = log.substringAfter("## 2026-10-30: scelte di TALITA-15")
        for (part in listOf(
            "commento 10965", "commento 10970", "commento 10974", "«ok, con la conferma con impronta prima delle segnalazioni»", "**Domanda 3 cambia**", "scelta 1 di Matteo", "**10 minuti**", "**Domande 6 e 7**",
            "`V12__devices_and_passkeys.sql`", "**Dipendenza nuova: `spring-security-webauthn`**", "webauthn4j", "`PersistentTokenBasedRememberMeServices`", "**verificato su 7.1.1 e scartato**", "**Un solo tentativo per richiesta.**",
            "`SessionGuardFilter`", "**dopo 12 ore, anche se usata**", "il caso che TALITA-7 aveva lasciato aperto", "`PasskeyConfigurer` e `PasskeyOperations`", "`ConfirmationProof`", "**una chiamata che non è passata dal controllo non compila**",
            "**Differenze dall'analisi**", "**Dove il design taceva (scelta prudente)**", "**Da provare a mano prima del rilascio**", "Safari", "autenticatore virtuale di Chromium",
        )) {
            assertTrue(entry.contains(part), "docs/05 does not say: $part")
        }
    }

    @Test
    fun `t15 c35 the design guide has the new components, the htmx behaviour and the words, and the mockups are in the repository`() {
        val guide = doc("docs/design/README.md")

        for (part in listOf(
            "### 4.23 Casella con spiegazione (TALITA-15)", "### 4.24 «Oppure» e le passkey (TALITA-15)", "### 4.25 Riga di un dispositivo o di una passkey (TALITA-15)", "### 4.26 Conferma con la passkey (TALITA-15)",
            "has-checked:border-brand has-checked:bg-brand-soft", "data-passkey hidden", "`passkeys.js`", "`monitor-smartphone`", "`key-round`", "`fingerprint-pattern`", "data-conferma=\"<tipo>\"", "`htmx:confirm`",
            "### Accesso e passkey (TALITA-15)", "`#dispositivi-contenuto`", "`#passkey-contenuto`", "### Accesso (TALITA-15)", "«Ricordami su questo telefono»", "«codice di sblocco»", "«Serve la conferma.»",
        )) {
            assertTrue(guide.contains(part), "the guide does not say: $part")
        }
        val mockups = Path.of("docs/design/TALITA-15")
        assertTrue(Files.isRegularFile(mockups.resolve("index.html")), "the index of the mockups")
        Files.list(mockups).use { files -> assertEquals(52, files.filter { it.fileName.toString().endsWith(".html") }.count(), "the 51 pages and the index") }
    }
}
