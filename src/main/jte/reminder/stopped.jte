@import talita.ui.Icon
@import talita.ui.Shell
@param Shell shell
<%-- The answer to the tap on «Non mandarmi più promemoria» (TALITA-13, design part 2, 3): the same page for a code that exists and one that does not, and for an account that had
     stopped already, so it never says whether the account is there. No address, no name. Whoever is not inside goes through the access page to reach the profile. --%>

@template.layout.shell(shell = shell, content = @`
  @template.components.statePage(
    icon = Icon.CIRCLE_CHECK,
    title = "Non riceverai più promemoria",
    body = @`
      <p class="mt-2 max-w-sm text-ink-muted">Da ora Talità non ti manda più promemoria né avvisi. I link per entrare ti arrivano comunque, quando li chiedi.</p>
      <p class="mt-3 max-w-sm text-ink-muted">Se cambi idea, puoi riprendere i promemoria dal tuo profilo.</p>
    `,
    actions = @`
      <div class="mt-6 w-full max-w-xs">
        @template.components.linkButton(href = "/profilo", label = "Vai al profilo", variant = "secondary", icon = Icon.USER_ROUND, width = "full")
      </div>
    `)
`)
