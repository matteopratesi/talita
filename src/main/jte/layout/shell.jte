@import talita.ui.Icon
@import talita.ui.NavItem
@import talita.ui.Shell
@param Shell shell
@param gg.jte.Content content
<!doctype html>
<html lang="it">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="theme-color" content="#ffffff">
  <meta name="htmx-config" content='{"historyCacheSize":0,"includeIndicatorStyles":false,"timeout":20000}'>
  <title>${shell.getTitle()}</title>
  @if(shell.getNoindex())
    <meta name="robots" content="noindex, nofollow">
  @endif
  <link rel="icon" href="${shell.getAssets().url("/favicon.svg")}" type="image/svg+xml">
  <link rel="preload" href="${shell.getAssets().url("/fonts/inter-latin.woff2")}" as="font" type="font/woff2" crossorigin>
  <link rel="stylesheet" href="${shell.getAssets().url("/css/app.css")}">
  <script src="${shell.getAssets().url("/js/htmx.min.js")}" defer></script>
  <script src="${shell.getAssets().url("/js/app.js")}" defer></script>
</head>
<body hx-boost="true" class="min-h-dvh bg-canvas font-sans text-base text-ink antialiased">

  <a href="#contenuto" class="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-70 focus:rounded-xl focus:bg-ink focus:px-4 focus:py-3 focus:font-semibold focus:text-white">Vai al contenuto</a>

  <div id="caricamento" aria-hidden="true" class="pointer-events-none fixed inset-x-0 top-0 z-60 h-1 overflow-hidden opacity-0 transition-opacity duration-150 page-busy:opacity-100 page-busy:delay-300">
    <div class="h-full w-1/3 rounded-full bg-brand motion-safe:animate-progress motion-reduce:w-full"></div>
  </div>

  @if(shell.isSignedIn())
    <header class="sticky top-0 z-40 flex min-h-14 items-center gap-2 border-b border-line bg-surface pt-safe px-gutter short:static lg:fixed lg:top-0 lg:left-0 lg:h-16 lg:w-64 lg:border-r lg:border-b-0 lg:px-6">
      <a href="/" class="inline-flex min-h-11 items-center gap-2 rounded-lg text-lg font-bold tracking-tight text-ink">
        @template.components.brandMark()
        Talità
      </a>
      <a href="/profilo" aria-current="${shell.getProfile().getCurrent() ? "page" : null}" class="group ml-auto inline-flex min-h-11 items-center gap-2 rounded-full border border-line-strong bg-surface py-1 pr-3 pl-1 text-sm font-semibold text-ink transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted aria-[current=page]:border-brand aria-[current=page]:bg-brand-soft aria-[current=page]:text-brand max-[20rem]:pr-1">
        <span aria-hidden="true" class="grid size-8 place-items-center rounded-full bg-brand-soft text-sm font-semibold text-brand group-aria-[current=page]:bg-surface">${shell.getProfile().getInitials()}</span>
        <span class="max-[20rem]:sr-only">Profilo</span>
      </a>
    </header>
  @else
    <header class="sticky top-0 z-40 flex min-h-14 items-center gap-2 border-b border-line bg-surface pt-safe px-gutter short:static lg:px-10">
      <p class="inline-flex min-h-11 items-center gap-2 text-lg font-bold tracking-tight text-ink">
        @template.components.brandMark()
        Talità
      </p>
    </header>
  @endif

  @if(shell.isSignedIn())
    <nav aria-label="Navigazione principale" class="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-surface pb-safe px-1 lg:top-16 lg:right-auto lg:w-64 lg:border-t-0 lg:border-r lg:px-3 lg:py-4">
      <ul class="mx-auto flex max-w-lg lg:max-w-none lg:flex-col lg:gap-1">
        @for(NavItem item : shell.getNavigation())
          @template.components.navItem(item = item)
        @endfor
      </ul>
    </nav>

    <main id="contenuto" tabindex="-1" class="block px-gutter pt-6 pb-above-nav transition-opacity duration-150 motion-safe:animate-enter nav-busy:opacity-60 nav-busy:delay-300 lg:ml-64 lg:px-10 lg:pt-10 lg:pb-16">
      <div class="mx-auto w-full max-w-3xl">
        ${content}
      </div>
    </main>

    <div id="messaggi" aria-live="polite" class="pointer-events-none fixed inset-x-0 bottom-above-nav z-50 flex flex-col items-center gap-2 px-gutter lg:inset-x-auto lg:right-6 lg:bottom-6 lg:w-full lg:max-w-md lg:px-0"></div>
  @else
    <main id="contenuto" tabindex="-1" class="block px-gutter pt-6 pb-no-nav transition-opacity duration-150 motion-safe:animate-enter nav-busy:opacity-60 nav-busy:delay-300 lg:px-10 lg:pt-16 lg:pb-16">
      <div class="mx-auto w-full max-w-md">
        ${content}
      </div>
    </main>

    <div id="messaggi" aria-live="polite" class="pointer-events-none fixed inset-x-0 bottom-no-nav z-50 flex flex-col items-center gap-2 px-gutter lg:inset-x-auto lg:right-6 lg:bottom-6 lg:w-full lg:max-w-md lg:px-0"></div>
  @endif

  @template.components.toast(id = "messaggio-rete-assente", icon = Icon.WIFI_OFF, title = "Nessuna connessione", text = "Controlla di avere campo o il Wi-Fi e riprova.")
  @template.components.toast(id = "messaggio-rete-lenta", icon = Icon.CLOCK, title = "La connessione è troppo lenta", text = "Talità non ha risposto in tempo. Riprova quando hai più campo.")
  @template.components.toast(id = "messaggio-errore-server", icon = Icon.TRIANGLE_ALERT, title = "Qualcosa non ha funzionato", text = "Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza.")
  @template.components.toast(id = "messaggio-accesso-negato", icon = Icon.LOCK, title = "Accesso negato", text = "Non hai il permesso di farlo. Se pensi di doverlo fare, chiedi a chi organizza la convivenza.", retry = false)
</body>
</html>
