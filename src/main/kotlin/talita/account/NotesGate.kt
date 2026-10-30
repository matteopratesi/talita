package talita.account

/** What the card «Segnalazioni» says about the confirmation with the passkey (TALITA-15, criteria 37-46). */
enum class GateMode {
    /** Nothing: a parent, a teacher without passkeys on a site that has none, or a card that is open already. */
    NONE,

    /** A teacher with a passkey and no confirmation that holds: the phone will ask, and the form carries `data-conferma`. */
    CONFIRM,

    /** The confirmation holds until [NotesGate.until]: the button opens at once, with a single tap. */
    VALID,

    /** A teacher with no passkey (on a site that can have them): a discreet line invites them to add one. It never blocks and never closes. */
    INVITE,

    /** The answer to a tap that the server refused (409): the card says «Serve la conferma.» and keeps the button, which now asks the phone. */
    SERVE,

    /** The same without JavaScript: there is nobody to ask the phone, so the page says that the confirmation wants it, and has no button. */
    SERVE_NO_JS,
}

/**
 * The state of the confirmation, as a card of the notes draws it. The properties are what the templates read (a template compares no enums). It is worked out when the page is drawn;
 * **the server decides again at the tap** (criterion 44), so a card that says something old costs one thing: the phone asks, or does not ask.
 */
data class NotesGate(val mode: GateMode, val until: String? = null) {

    /** The form of «Mostra le segnalazioni» holds its request until the phone has verified the person. */
    val confirm: Boolean get() = mode == GateMode.CONFIRM || mode == GateMode.SERVE

    /** The sentence under the button that says that the phone will ask. */
    val asks: Boolean get() = mode == GateMode.CONFIRM

    /** «Hai già confermato…», until [until]. */
    val valid: Boolean get() = mode == GateMode.VALID

    val invite: Boolean get() = mode == GateMode.INVITE

    /** The notice «Serve la conferma.» above the button, with the button. */
    val serve: Boolean get() = mode == GateMode.SERVE

    /** The notice «Serve la conferma.» with no button and no note. */
    val serveNoJs: Boolean get() = mode == GateMode.SERVE_NO_JS

    companion object {
        val NONE = NotesGate(GateMode.NONE)

        fun of(view: ConfirmationView): NotesGate = when (view.mode) {
            ConfirmationMode.NONE -> NONE
            ConfirmationMode.CONFIRM -> NotesGate(GateMode.CONFIRM)
            ConfirmationMode.VALID -> NotesGate(GateMode.VALID, view.until)
            ConfirmationMode.INVITE -> NotesGate(GateMode.INVITE)
        }
    }
}
