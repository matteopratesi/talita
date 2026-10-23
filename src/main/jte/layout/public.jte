@import talita.ui.PublicShell
@param PublicShell shell
@param gg.jte.Content content
<%-- The shell of the pages that anyone can open with a link and that need nothing from the app (the information page of the gathering): the header with the
     mark only (not a link), no navigation, a column of 672 px, and NO script at all: no htmx, no app.js, no pop-up messages, no loading bar. So nothing here
     reads the CSRF code (it would start a session and send a cookie), and the page weighs about 85 KB. In print the header and the link «Vai al contenuto»
     are not printed (app.css). When [shell] has a share (the title and the line of the preview of the link) the page says it with the `description` and `og:*`
     tags: never an image and never an address outside the app. --%>
<!doctype html>
<html lang="it">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="theme-color" content="#ffffff">
  <title>${shell.getTitle()}</title>
  <meta name="robots" content="noindex, nofollow">
  @if(shell.getShare() != null)
    <meta name="description" content="${shell.getShare().getDescription()}">
    <meta property="og:title" content="${shell.getShare().getTitle()}">
    <meta property="og:description" content="${shell.getShare().getDescription()}">
    <meta property="og:type" content="website">
    <meta property="og:site_name" content="Talità">
    <meta property="og:locale" content="it_IT">
  @endif
  <link rel="icon" href="${shell.getAssets().url("/favicon.svg")}" type="image/svg+xml">
  <link rel="preload" href="${shell.getAssets().url("/fonts/inter-latin.woff2")}" as="font" type="font/woff2" crossorigin>
  <link rel="stylesheet" href="${shell.getAssets().url("/css/app.css")}">
</head>
<body class="min-h-dvh bg-canvas font-sans text-base text-ink antialiased">

  <a href="#contenuto" class="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-70 focus:rounded-xl focus:bg-ink focus:px-4 focus:py-3 focus:font-semibold focus:text-white">Vai al contenuto</a>

  <header class="sticky top-0 z-40 flex min-h-14 items-center gap-2 border-b border-line bg-surface pt-safe px-gutter short:static lg:px-10">
    <p class="inline-flex min-h-11 items-center gap-2 text-lg font-bold tracking-tight text-ink">
      @template.components.brandMark()
      Talità
    </p>
  </header>

  <main id="contenuto" tabindex="-1" class="block px-gutter pt-6 pb-no-nav lg:px-10 lg:pt-16 lg:pb-16">
    <div class="mx-auto w-full max-w-2xl">
      ${content}
    </div>
  </main>
</body>
</html>
