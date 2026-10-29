package talita.reminder

import org.springframework.stereotype.Component
import talita.registration.RegistrationFormat
import talita.ui.Alert

/**
 * The card about the mail of Talità that is not a login link (TALITA-13, design part 2): the box «Avvisami per email…» (never ticked before the person ticks it), the way to stop, and, for
 * who stopped, the way to take the reminders back. It is the same card in two places: **«Iscrizioni»** (short: [full] false, `#avviso-card`) and the **profile** (complete: [full] true,
 * `#email`, with the stop). Every action answers with the card as a whole, which htmx puts in the place of the old one.
 *
 * @property id the `id` of the section, and the target of the answer of htmx
 * @property intro the introduction, only in the profile
 * @property alert the notice of what the person just did (live: announced, and it takes the focus)
 * @property standing «Hai smesso di ricevere i promemoria il …»: the notice that stays while the reminders are stopped (it is replaced by [alert] in the answer to the stop itself)
 * @property ask the box and «Salva la scelta»: not for someone who stopped; [checked] is the box as saved, [since] the sentence with the moment of the consent
 * @property stopText the sentence under «Non voglio più promemoria» (the profile, for someone who has not stopped); null where the stop is not offered
 * @property profileHref where «Gestisci le email» (the short card) and «Vai al profilo» (the short card of someone who stopped) lead
 */
data class EmailCard(
    val full: Boolean,
    val id: String,
    val title: String,
    val intro: String?,
    val alert: Alert?,
    val standing: String?,
    val stopped: Boolean,
    val ask: Boolean,
    val checked: Boolean,
    val since: String?,
    val askAction: String,
    val stopText: String?,
    val profileHref: String,
) {
    /** The label and the help of the box: the same in the two places. */
    val askLabel: String get() = NoticePresenter.ASK_LABEL

    val askHint: String get() = NoticePresenter.ASK_HINT
}

/**
 * Builds the card of an account. The sentences are those of the design, word for word. The card of «Iscrizioni» is for **who has children** (the avviso is for them); the one of the profile
 * is also for **who stopped** (it is the only way to take the reminders back). For anyone else there is none, except in the answer to an action in the profile, which has to say what happened.
 */
@Component
class NoticePresenter(private val notices: Notices) {

    fun card(accountId: Long, full: Boolean, alert: Alert? = null): EmailCard? {
        val state = notices.state(accountId) ?: return null
        val stopped = state.stoppedAt != null
        // «Iscrizioni» is for who has children; the profile is also for who stopped, because it is the only way to take the reminders back.
        if (!full && !state.hasChildren) return null
        if (!state.hasChildren && !stopped && alert == null) return null
        val ask = state.hasChildren && !stopped
        return EmailCard(
            full = full,
            id = if (full) "email" else "avviso-card",
            title = if (full) "Email da Talità" else "Avviso per la prossima convivenza",
            intro = if (full && !stopped) INTRO else null,
            alert = alert,
            standing = state.stoppedAt?.let { "Hai smesso di ricevere i promemoria il ${RegistrationFormat.day(it)}. I link per entrare in Talità ti arrivano comunque." },
            stopped = stopped,
            ask = ask,
            checked = state.consentAt != null,
            since = state.consentAt?.let { "Hai chiesto l’avviso il ${RegistrationFormat.moment(it)}." },
            askAction = if (full) "/profilo/avviso" else "/iscrizioni/avviso",
            stopText = if (full && ask) {
                if (state.consentAt != null) STOP_WITH_CONSENT else STOP_WITHOUT_CONSENT
            } else {
                null
            },
            profileHref = "/profilo#email",
        )
    }

    companion object {
        const val INTRO = "Oltre ai link per entrare, Talità ti scrive solo per ricordarti di confermare un’iscrizione in forse e, se lo chiedi, per avvisarti quando si aprono le iscrizioni " +
            "di una nuova convivenza. Nelle mail non ci sono i nomi dei bambini."

        const val ASK_LABEL = "Avvisami per email quando si aprono le iscrizioni di una nuova convivenza"

        const val ASK_HINT = "Quando le iscrizioni di una nuova convivenza sono aperte, i maestri possono mandarti una mail con il link per iscrivere i bambini. Nella mail non ci sono i nomi dei " +
            "bambini. Puoi smettere quando vuoi, da qui o dalla mail."

        const val STOP_WITH_CONSENT = "Non riceverai né i promemoria né l’avviso, e la tua richiesta di avviso si cancella. I link per entrare in Talità ti arrivano sempre."

        const val STOP_WITHOUT_CONSENT = "Non riceverai né i promemoria né l’avviso. I link per entrare in Talità ti arrivano sempre."

        /** What the person is told after each action: a confirmation, announced when there is a pause. */
        const val ASKED = "Scelta salvata. Ti avviseremo quando i maestri apriranno le iscrizioni di una nuova convivenza."
        const val NOT_ASKED = "Scelta salvata. Non ti avviseremo."
        const val STOPPED_WITH_CONSENT = "Non ti mandiamo più promemoria. La tua richiesta di avviso è stata cancellata."
        const val STOPPED = "Non ti mandiamo più promemoria."
        const val RESUMED = "Da ora ricevi di nuovo i promemoria."
    }
}
