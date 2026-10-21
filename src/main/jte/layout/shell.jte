@import koine.ui.Icon
@import koine.ui.NavItem
@import koine.ui.Shell
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

  <header class="sticky top-0 z-40 flex min-h-14 items-center gap-2 border-b border-line bg-surface pt-safe px-gutter short:static lg:fixed lg:top-0 lg:left-0 lg:h-16 lg:w-64 lg:border-r lg:border-b-0 lg:px-6">
    <a href="/" class="inline-flex min-h-11 items-center gap-2 rounded-lg text-lg font-bold tracking-tight text-ink">
      <svg viewBox="0 0 32 32" aria-hidden="true" class="size-7 text-brand">
        <rect width="32" height="32" rx="9" fill="currentColor"/>
        <path d="M11.5 9v14M20.5 9l-8 7 8 7" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
      </svg>
      Koinè
    </a>
  </header>

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

  @template.components.toast(id = "messaggio-rete-assente", icon = Icon.WIFI_OFF, title = "Nessuna connessione", text = "Controlla di avere campo o il Wi-Fi e riprova.")
  @template.components.toast(id = "messaggio-rete-lenta", icon = Icon.CLOCK, title = "La connessione è troppo lenta", text = "Koinè non ha risposto in tempo. Riprova quando hai più campo.")
  @template.components.toast(id = "messaggio-errore-server", icon = Icon.TRIANGLE_ALERT, title = "Qualcosa non ha funzionato", text = "Riprova tra qualche minuto. Se il problema continua, avvisa il Centro.")
</body>
</html>
