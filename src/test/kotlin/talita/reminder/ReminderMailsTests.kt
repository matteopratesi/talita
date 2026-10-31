package talita.reminder

import org.junit.jupiter.api.Test
import talita.gathering.GatheringFormat
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two mails (TALITA-13, criterion 8), word by word: the subject and the text are fixed in the code, with the decision of 24 October (comment 10950): the mail «in forse»
 * invites to confirm, says that thinking about it a while longer is fine, and does not invite to withdraw. The mails as they leave (with the people of the example, from
 * Mailpit) are tried by the tests of the sending; here the texts themselves.
 */
class ReminderMailsTests {

    private val title = "Convivenza di settembre 2027"
    private val dates = GatheringFormat.range(LocalDate.of(2027, 9, 17), LocalDate.of(2027, 9, 19))
    private val registrations = "https://talita.example.org/iscrizioni"
    private val info = "https://talita.example.org/info/q7Vd_3KmZ0xRb-Ew"
    private val stop = "https://talita.example.org/promemoria/smetti?codice=Xq3Zp0mV_8aTe-Lw"

    private val maybe = ReminderMails.maybe("nome@example.org", title, dates, registrations, stop)
    private val notice = ReminderMails.notice("nome@example.org", title, dates, info, stop)

    @Test
    fun `t13 c08 the mail in forse has the subject and the text of the design, with the decision of Matteo`() {
        assertEquals("nome@example.org", maybe.to)
        assertEquals("Iscrizione in forse per «Convivenza di settembre 2027»", maybe.subject)
        assertEquals(
            """
            Ciao,

            c’è almeno un’iscrizione in forse per «Convivenza di settembre 2027» (17–19 settembre 2027).

            Puoi fare due cose.

            Conferma l’iscrizione: entra in Talità, apri la pagina «Iscrizioni» e cambia lo stato in «Confermata».

            https://talita.example.org/iscrizioni

            Per entrare ti mandiamo un link, senza password: usa questo stesso indirizzo email.

            Ci penso ancora un po’: non devi fare niente. L’iscrizione resta in forse finché le iscrizioni sono aperte.

            Non vuoi più ricevere queste mail? Apri questa pagina e tocca «Non mandarmi più promemoria»:

            https://talita.example.org/promemoria/smetti?codice=Xq3Zp0mV_8aTe-Lw

            Ricevi questa mail perché hai un’iscrizione in forse. Per i link di accesso a Talità non cambia niente.

            Questa mail è automatica: non risponderle. Se hai bisogno di aiuto, scrivi a chi organizza la convivenza.

            Talità
            """.trimIndent() + "\n",
            maybe.text,
        )
    }

    @Test
    fun `t13 c08 the mail in forse invites to confirm and never to withdraw, and has no link to withdraw`() {
        val text = maybe.text.lowercase()

        assertFalse(text.contains("ritira"), "the decision of 24 October: no invitation to withdraw")
        assertFalse(text.contains("ritirare"))
        assertFalse(maybe.subject.lowercase().contains("ritir"))
        assertTrue(maybe.text.contains("Conferma l’iscrizione"), "the first option")
        assertTrue(maybe.text.contains("Ci penso ancora un po’"), "the second option: nothing to do")
        assertTrue(maybe.text.contains("non devi fare niente"))
        // The only addresses are the page of the registrations and the page to stop: nothing to withdraw, nothing with the id of a registration.
        assertEquals(listOf(registrations, stop), Regex("""https?://\S+""").findAll(maybe.text).map { it.value }.toList())
    }

    @Test
    fun `t13 c08 the mail of the avviso has the subject and the text of the design`() {
        assertEquals("Iscrizioni aperte per «Convivenza di settembre 2027»", notice.subject)
        assertEquals(
            """
            Ciao,

            hai chiesto di ricevere un avviso quando si aprono le iscrizioni di una nuova convivenza dei bambini: ora sono aperte le iscrizioni per «Convivenza di settembre 2027» (17–19 settembre 2027).

            Trovi le informazioni e il modo per iscrivere i bambini in questa pagina:

            https://talita.example.org/info/q7Vd_3KmZ0xRb-Ew

            Non vuoi più ricevere queste mail? Apri questa pagina e tocca «Non mandarmi più promemoria»:

            https://talita.example.org/promemoria/smetti?codice=Xq3Zp0mV_8aTe-Lw

            Ricevi questa mail perché hai chiesto l’avviso. Per i link di accesso a Talità non cambia niente.

            Questa mail è automatica: non risponderle. Se hai bisogno di aiuto, scrivi a chi organizza la convivenza.

            Talità
            """.trimIndent() + "\n",
            notice.text,
        )
        assertEquals(listOf(info, stop), Regex("""https?://\S+""").findAll(notice.text).map { it.value }.toList())
        assertFalse(notice.subject.contains("q7Vd_3KmZ0xRb-Ew"), "the code of the information page is never in the subject")
    }

    @Test
    fun `t13 c08 both mails say why they arrive and how to stop, with the words of the button of the page`() {
        for (mail in listOf(maybe, notice)) {
            assertTrue(mail.text.contains("Non vuoi più ricevere queste mail? Apri questa pagina e tocca «Non mandarmi più promemoria»:"))
            assertTrue(mail.text.contains(stop))
            assertTrue(mail.text.contains("Ricevi questa mail perché"))
            assertTrue(mail.text.contains("Per i link di accesso a Talità non cambia niente."))
            assertEquals(stop, mail.unsubscribe, "the same address goes in the header List-Unsubscribe")
            assertTrue(mail.text.startsWith("Ciao,\n\n"))
            assertTrue(mail.text.endsWith("\n\nTalità\n"))
            assertTrue(mail.text.contains("Questa mail è automatica: non risponderle. Se hai bisogno di aiuto, scrivi a chi organizza la convivenza."))
        }
        assertEquals("Non mandarmi più promemoria", ReminderMails.STOP_WORDS)
    }

    @Test
    fun `t13 c08 the text has no HTML, no forms of a sex, and none of the words of the machine`() {
        for (mail in listOf(maybe, notice)) {
            assertFalse(mail.text.contains("<") || mail.text.contains(">") || mail.text.contains("&"), "plain text only")
            for (word in listOf("avvisato/a", "iscritto/a", "utente", "server", "database", "sessione", "login", "clic", "cliccare")) {
                assertFalse(mail.text.lowercase().contains(word), word)
            }
        }
    }

    @Test
    fun `t16 c20 whatever the title is, the subject and the text of both mails put it between guillemets after per, with no article before it`() {
        val article = Regex("""(?i)(?<![\p{L}])(il|lo|la|l’|i|gli|le|un|uno|una|al|allo|alla|del|dello|della|dei|degli|delle)\s+«""")

        for (title in listOf("Campo estivo", "Convivenza di settembre 2027", "Gita al lago", "Estate 2027", "Il ritrovo")) {
            val inForse = ReminderMails.maybe("nome@example.org", title, dates, registrations, stop)
            val avviso = ReminderMails.notice("nome@example.org", title, dates, info, stop)

            assertEquals("Iscrizione in forse per «$title»", inForse.subject)
            assertEquals("Iscrizioni aperte per «$title»", avviso.subject)
            assertTrue(inForse.text.contains("c’è almeno un’iscrizione in forse per «$title» ($dates)."), inForse.text)
            assertTrue(avviso.text.contains("ora sono aperte le iscrizioni per «$title» ($dates)."), avviso.text)
            for (part in listOf(inForse.subject, inForse.text, avviso.subject, avviso.text)) {
                assertFalse(article.containsMatchIn(part), "an article before the title «$title»: $part")
                assertFalse(part.contains("alla $title") || part.contains("della $title"), "the title after an article: $part")
            }
        }
    }

    @Test
    fun `t13 c08 a title with line breaks or extra spaces cannot break the subject`() {
        val mail = ReminderMails.maybe("nome@example.org", "Convivenza\r\nBcc: altro  di settembre ", dates, registrations, stop)

        assertEquals("Iscrizione in forse per «Convivenza Bcc: altro di settembre»", mail.subject)
        assertFalse(mail.subject.contains('\n') || mail.subject.contains('\r'))
        assertNull(ReminderMails.notice("nome@example.org", "A\nB", dates, info, stop).subject.lines().getOrNull(1))
    }
}
