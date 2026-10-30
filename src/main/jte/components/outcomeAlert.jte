@import talita.ui.AlertVariant
@param AlertVariant variant
@param String lead
@param String text
@param String passkeyEsito = null
@param String confermaEsito = null
@param String classes = "mt-3"
<%-- A notice that the script of the passkeys (`passkeys.js`) shows when something happens on the phone (cancelled, refused, a passkey that is there already): it is in the page, hidden, with its own
     words, and the script only shows it and gives it the focus, so no text is written in the JavaScript. `passkeyEsito` names it for the access and the registration (`data-passkey-esito`),
     `confermaEsito` for the confirmation (`data-conferma-esito`, «togli-annullata»). It is announced when it is shown: the script gives it the role of its variant (`data-role`) at that moment. While it is hidden it has no role, so that the pages have as `role=status` or `role=alert` only the notices that are really there. --%>
<div hidden data-passkey-esito="${passkeyEsito}" data-conferma-esito="${confermaEsito}" data-role="${variant.getRole()}" tabindex="-1" class="${classes}">
  @template.components.alert(variant = variant, lead = lead, content = @`${text}`)
</div>
