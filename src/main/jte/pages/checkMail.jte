@import talita.account.CheckMailPage
@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
@param CheckMailPage page

@template.layout.shell(shell = shell, content = @`
  @template.components.statePage(
    icon = Icon.MAIL,
    title = "Controlla la posta",
    body = @`
      @if(page.getEmail() != null)
        <p class="mt-2 max-w-sm text-ink-muted">Abbiamo mandato un link a <span class="font-semibold wrap-anywhere text-ink">${page.getEmail()}</span>. Toccalo per entrare.</p>
      @else
        <p class="mt-2 max-w-sm text-ink-muted">Abbiamo mandato un link al tuo indirizzo. Toccalo per entrare.</p>
      @endif
      <ul class="mt-6 w-full max-w-sm list-disc space-y-3 rounded-2xl border border-line bg-surface py-4 pr-4 pl-9 text-left">
        <li>Apri la mail da questo telefono o computer: se la apri su un altro, entri lì.</li>
        <li>Non la trovi? Guarda anche nello spam o nella posta indesiderata.</li>
        <li>Il link vale ${page.getValidityMinutes()} minuti e si usa una volta sola.</li>
      </ul>
      <p class="mt-6 max-w-sm text-ink-muted">Hai sbagliato indirizzo o la mail non arriva?</p>
    `,
    actions = @`
      <div class="mt-3 w-full max-w-xs">
        @template.components.linkButton(href = "/accedi", label = "Chiedi un altro link", variant = "secondary", width = "full")
      </div>
    `)
`)
