@import talita.ui.AlertVariant
@param AlertVariant variant
@param String lead
@param String text
@param String passkeyEsito = null
@param String confermaEsito = null
@param String classes = "mt-3"
<%-- A notice that the script of the passkeys (`passkeys.js`) shows when something happens on the phone (cancelled, refused, a passkey that is there already): it is in the page, hidden, with its own
     words, and the script only shows it and gives it the focus, so no text is written in the JavaScript. `passkeyEsito` names it for the access and the registration (`data-passkey-esito`),
     `confermaEsito` for the confirmation (`data-conferma-esito`, «togli-annullata»). It is announced when it is shown: its role is that of its variant. --%>
<div hidden data-passkey-esito="${passkeyEsito}" data-conferma-esito="${confermaEsito}" role="${variant.getRole()}" tabindex="-1" class="${classes}">
  @template.components.alert(variant = variant, lead = lead, content = @`${text}`)
</div>
