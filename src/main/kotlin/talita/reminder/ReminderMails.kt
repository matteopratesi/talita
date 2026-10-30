package talita.reminder

import talita.mail.OutgoingMail

/**
 * The two mails of the reminders (TALITA-13, design part 3): the subject and the text are fixed, here in the code, as [talita.account.LoginLinkMail] does for the link.
 * Nothing a teacher writes goes in them but the title of the gathering (written by a teacher with the checks of TALITA-4: no address, no telephone, no IBAN) and its dates.
 *
 * - **What is in a mail**: the title and the dates of the gathering, the address of `/iscrizioni` (for «in forse») or of the information page `/info/{codice}` (for the
 *   «avviso»), the way to stop with its address, and a line that says why the mail arrives.
 * - **What is never in a mail**: names or dates of birth of the children, telephones, notes on health, the place, the fees, **how many children** and which are in forse.
 *   «C'è almeno un'iscrizione» says nothing about the number. A test builds the mails with two children registered and looks for each of these.
 * - **The decision of 24 October** (comment 10950 of the ticket): the mail «in forse» invites to **confirm** and says that thinking about it a while longer is fine («Ci penso
 *   ancora un po'»: nothing to do, the registration stays in forse while the registrations are open). It does not invite to withdraw, and has no link to do it: the
 *   withdrawal stays where it was, in the pages of the registrations. A test looks for «ritira» and «ritirare» in the text.
 * - **The tone** is that of the other two mails: «Ciao,», short sentences, «tu», the line «Questa mail è automatica…» and the signature. Never a form that depends on the
 *   sex of the person, and none of the words that the texts of the app do not use.
 *
 * Plain text, with the headers of Mailjet that switch off the tracking, and `List-Unsubscribe` with the address to stop ([OutgoingMail.unsubscribe], written by `Mailer`).
 * The code to stop is only in that address: [stopLink] is made by whoever sends and never goes to the log.
 */
object ReminderMails {

    /** The words of the button of the page for stopping, repeated in the mails so that the person finds the same words. */
    const val STOP_WORDS = "Non mandarmi più promemoria"

    private const val AUTOMATIC =
        "Questa mail è automatica: non risponderle. Se hai bisogno di aiuto, scrivi a chi organizza la convivenza."

    /** A title as it goes in the subject: one line, with the spaces squeezed (a title with a line break must not break the header). */
    private fun oneLine(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    /**
     * «in forse»: [registrationsLink] is the address of `/iscrizioni`, [stopLink] the address of the page for stopping with the code of the account.
     * [dates] is the range as the gathering writes it (`17–19 settembre 2027`).
     */
    fun maybe(to: String, title: String, dates: String, registrationsLink: String, stopLink: String): OutgoingMail = OutgoingMail(
        to = to,
        subject = "Iscrizione in forse alla ${oneLine(title)}",
        text = """
            Ciao,

            c’è almeno un’iscrizione in forse alla ${oneLine(title)} ($dates).

            Puoi fare due cose.

            Conferma l’iscrizione: entra in Talità, apri la pagina «Iscrizioni» e cambia lo stato in «Confermata».

            $registrationsLink

            Per entrare ti mandiamo un link, senza password: usa questo stesso indirizzo email.

            Ci penso ancora un po’: non devi fare niente. L’iscrizione resta in forse finché le iscrizioni sono aperte.

            Non vuoi più ricevere queste mail? Apri questa pagina e tocca «$STOP_WORDS»:

            $stopLink

            Ricevi questa mail perché hai un’iscrizione in forse. Per i link di accesso a Talità non cambia niente.

            $AUTOMATIC

            Talità
        """.trimIndent() + "\n",
        unsubscribe = stopLink,
    )

    /** «avviso»: [infoLink] is the address of the information page of the gathering (`/info/{codice}`), which whoever asked for the avviso may see. */
    fun notice(to: String, title: String, dates: String, infoLink: String, stopLink: String): OutgoingMail = OutgoingMail(
        to = to,
        subject = "Iscrizioni aperte: ${oneLine(title)}",
        text = """
            Ciao,

            hai chiesto di ricevere un avviso quando si aprono le iscrizioni di una nuova convivenza dei bambini: ora sono aperte quelle della ${oneLine(title)} ($dates).

            Trovi le informazioni e il modo per iscrivere i bambini in questa pagina:

            $infoLink

            Non vuoi più ricevere queste mail? Apri questa pagina e tocca «$STOP_WORDS»:

            $stopLink

            Ricevi questa mail perché hai chiesto l’avviso. Per i link di accesso a Talità non cambia niente.

            $AUTOMATIC

            Talità
        """.trimIndent() + "\n",
        unsubscribe = stopLink,
    )
}
