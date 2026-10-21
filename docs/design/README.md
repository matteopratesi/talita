# Design system di Talità

Eredita il design system di Koinè (KOINE-11, Grace Kerning) e lo adatta a Talità con TALITA-2: token, componenti, accessibilità e movimento restano quelli, cambiano marchio, voci, testi ed esempi.

Questa guida vale per tutte le schermate di Talità. Chi sviluppa la usa così com'è: token, classi
Tailwind e testi. Se una schermata ha bisogno di un pezzo che qui non c'è, il pezzo si aggiunge
prima qui (con il ticket della schermata), poi nel template.

`docs/design/KOINE-11/` è il mockup di origine, copiato da Koinè e non più modificato, tranne alcune righe di `componenti.html` (nomi di parrocchia e indirizzo email di esempio, sostituiti con «Parrocchia di esempio» e `nome@example.org` perché il repo è pubblico): componenti e guscio valgono ancora, testi, voci e marchio no. Si apre `index.html` nel browser, senza server e senza rete. Se il mockup e questa guida non vanno d'accordo, vale la guida.

I rimandi «criterio N» e «KOINE-N» che restano nel testo sono ai criteri e ai ticket di Koinè: dicono da dove viene una regola, non sono criteri di Talità.

## Indice

1. [Principi](#1-principi)
2. [Fondamenta](#2-fondamenta): font, scala tipografica, colori, CSS sorgente, spazi, breakpoint,
   schermi difficili, icone, movimento
3. [Guscio dell'app](#3-guscio-dellapp)
4. [Componenti](#4-componenti)
5. [htmx: cosa vede chi usa Talità](#5-htmx-cosa-vede-chi-usa-talità)
6. [Scrivere i testi](#6-scrivere-i-testi)
7. [Accessibilità: controlli prima della PR](#7-accessibilità-controlli-prima-della-pr)
8. [Da non fare](#8-da-non-fare)

## 1. Principi

Chi usa Talità: i maestri che organizzano la convivenza dei bambini e i genitori che arrivano da un link condiviso su WhatsApp per iscrivere i figli. Non informatici, spesso adulti, quasi sempre dal telefono. I maestri la usano anche durante la convivenza, in una casa dove il campo può essere scarso.

1. **Un'app, non un sito.** Navigazione in basso sul telefono, titolo grande in cima alla
   pagina, cambio di pagina senza ricaricare, risposta visibile appena si tocca qualcosa,
   transizioni brevi e discrete.
2. **Si legge senza sforzo.** Testo base a 17 px, contrasto almeno AA ovunque (il testo principale
   è sopra AAA), aree di tocco di almeno 44 px (i nostri bottoni ne hanno 48).
3. **Una cosa per volta.** Una sola azione principale per schermata, form corti, niente menu
   nascosti per le cose importanti.
4. **Dire sempre cosa succede.** Caricamento, errore, conferma: nessuno stato muto. Ogni errore
   dice cosa fare.
5. **Il segnale scarso è normale.** Pagine leggere (prima visita sotto 250 KB), niente servizi
   esterni, messaggi chiari quando la rete manca. Nessuna azione resta senza risposta.
6. **Nessuno si perde.** Home sempre a un tocco, voce corrente evidente, il tasto indietro del
   telefono funziona come ci si aspetta.
7. **Pochi pezzi, sempre gli stessi.** Solo i token e i componenti di questa guida: la coerenza
   è ciò che rende moderno un gestionale.

## 2. Fondamenta

### 2.1 Font: Inter 4.1

**Scelta**: [Inter](https://github.com/rsms/inter) 4.1, licenza SIL Open Font License 1.1, senza
nome riservato (si può ridurre a un sottoinsieme e servire dall'app).

Perché Inter:

- è disegnato per gli schermi: occhio alto, lettere aperte, resta leggibile anche piccolo e su
  telefoni economici;
- è il carattere delle app moderne: dà l'aspetto "app" senza bisogno di effetti;
- ha le cifre tabulari (`tnum`) per date, quote e numeri in colonna;
- copre tutte le lettere latine dei nomi che possono comparire in un'anagrafe (italiano, ma anche
  rumeno, polacco, albanese, spagnolo, tagalog);
- è variabile: un solo file per tutti i pesi.

Un difetto per un'anagrafe: la I maiuscola e la l minuscola sono uguali ("Ilaria"). Per questo su
tutto il testo è accesa la variante `cv08` (I maiuscola con le grazie).

Alternative scartate: Atkinson Hyperlegible Next (ottima per chi vede poco, ma ha un aspetto più
da ente pubblico che da app); font di sistema (zero byte, ma i criteri 11 e 17 chiedono un font
servito dall'app e l'aspetto cambierebbe fra Android e iPhone).

**File da preparare** (una volta, a mano, con `fonttools` in Python: niente Node né npm):

1. dallo zip della release 4.1 si prende `InterVariable.ttf` (solo il dritto: il corsivo non si
   usa);
2. si fissa l'asse `opsz` a 14 e si tiene solo `wght` (100-900);
3. si divide in due file woff2 con gli intervalli Unicode di Google Fonts:
   - `inter-latin.woff2`: italiano e lingue dell'Europa occidentale, circa 48 KB, l'unico che
     scarica la home;
   - `inter-latin-ext.woff2`: lettere come ł, ș, ő, circa 85 KB, scaricato dal browser solo
     quando una pagina le contiene (per esempio un nome polacco).

```bash
fonttools varLib.instancer InterVariable.ttf opsz=14 -o inter-wght.ttf
pyftsubset inter-wght.ttf --flavor=woff2 --layout-features+=cv08,tnum,case \
  --unicodes="U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD" \
  --output-file=inter-latin.woff2
pyftsubset inter-wght.ttf --flavor=woff2 --layout-features+=cv08,tnum,case \
  --unicodes="U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF" \
  --output-file=inter-latin-ext.woff2
```

Accanto ai file vanno la licenza di Inter (`LICENSE.txt` della release) e due righe su come sono
stati prodotti (versione, comandi). Se `inter-latin.woff2` supera i 60 KB, il sottoinsieme è
sbagliato: va ricontrollato prima di ritoccare il budget.

**Budget della prima visita** (criterio 29: home sul telefono, cache vuota, senza compressione):

| Risorsa | Peso stimato |
|---|---|
| HTML della home, icone SVG comprese | circa 10 KB |
| CSS dell'app, minificato | 20-30 KB |
| htmx 2.0.11, `htmx.min.js` | 52 KB |
| JS dell'app (errori, messaggi, fuoco) | circa 3 KB |
| `inter-latin.woff2` | circa 48 KB |
| Favicon SVG | meno di 1 KB |
| **Totale** | **circa 135-145 KB** su 250 |

### 2.2 Scala tipografica

La scala sostituisce quella predefinita di Tailwind (i nomi delle classi restano gli stessi, i
valori no). Unità in rem: se la persona ingrandisce il testo nel browser, cresce tutto insieme.

| Classe | Dimensione | Interlinea | Uso |
|---|---|---|---|
| `text-2xs` | 11 px | 16 px | solo etichette della navigazione sotto i 320 px |
| `text-xs` | 13 px | 18 px | etichette della navigazione, etichette di stato |
| `text-sm` | 15 px | 22 px | testo secondario, aiuti dei campi, errori dei campi, didascalie |
| `text-base` | 17 px | 26 px | testo normale, campi, bottoni |
| `text-lg` | 19 px | 26 px | titoli di scheda, nome nell'intestazione |
| `text-xl` | 22 px | 30 px | titoli di sezione (`h2`), titoli di dialogo e di stato vuoto |
| `text-2xl` | 26 px | 32 px | titolo della pagina (`h1`) sul telefono |
| `text-3xl` | 32 px | 40 px | titolo della pagina (`h1`) su desktop |

Regole:

- Pesi: `font-normal` (400) per il testo, `font-semibold` (600) per titoli, bottoni ed etichette
  dei campi, `font-medium` (500) per le etichette della navigazione e i valori nelle schede.
  `font-bold` (700) solo per il nome "Talità" nell'intestazione.
- Titoli `h1` e `h2` con `tracking-tight`.
- Niente corsivo, niente maiuscolo, niente sottolineato fuori dai link dentro un testo.
- Date e numeri in colonna con `tabular-nums`.
- Paragrafi lunghi al massimo `max-w-prose`.

### 2.3 Colori

Token semantici: il nome dice a cosa serve il colore, non che colore è. La palette predefinita
di Tailwind è spenta (`--color-*: initial`), quindi `bg-blue-500` non esiste e non si può usare
per sbaglio.

Rapporti di contrasto calcolati con la formula WCAG 2.

| Token | Valore | Uso | Contrasto |
|---|---|---|---|
| `canvas` | `#F6F5F2` | sfondo della pagina | |
| `surface` | `#FFFFFF` | schede, liste, intestazione, navigazione, campi | |
| `surface-muted` | `#EFEDE8` | pressione e hover, etichette neutre | |
| `line` | `#E2DED6` | bordi di schede e separatori (decorativi) | |
| `line-strong` | `#8B8478` | bordi di campi e bottoni secondari | 3,70:1 su bianco, 3,40:1 su canvas (minimo 3:1) |
| `ink` | `#1C1B19` | testo principale | 17,21:1 su bianco, 15,79:1 su canvas |
| `ink-muted` | `#57534C` | testo secondario, icone non attive | 7,64:1 su bianco, 7,01:1 su canvas, 6,53:1 su surface-muted |
| `white` | `#FFFFFF` | testo su brand, danger e ink | |
| `brand` | `#2A4BA0` | azioni, link, voce corrente | 8,04:1 su bianco, 7,37:1 su canvas, 6,79:1 su brand-soft; bianco su brand 8,04:1 |
| `brand-strong` | `#1F3B85` | bottone primario premuto o sotto il mouse | bianco 10,38:1 |
| `brand-soft` | `#E7ECF8` | voce corrente, avvisi informativi, riquadri delle icone | ink 14,55:1 |
| `danger` | `#B42318` | azioni distruttive, errori | bianco 6,57:1; su danger-soft 5,79:1 |
| `danger-strong` | `#912018` | bottone pericolo premuto | bianco 8,66:1 |
| `danger-soft` | `#FDEDEB` | sfondo di errori e campi sbagliati | ink 15,15:1 |
| `success` | `#1D6B3A` | conferme, stato "attiva" | 6,53:1 su bianco, 5,66:1 su success-soft |
| `success-soft` | `#E5F2EA` | sfondo di conferme | |
| `warning` | `#8A5300` | attenzione, stato "da pagare" | 6,33:1 su bianco, 5,71:1 su warning-soft |
| `warning-soft` | `#FFF2D9` | sfondo di avvisi | ink 15,53:1 |
| `focus` | `#2A4BA0` | anello del fuoco da tastiera | |

Perché questi colori: un blu profondo, calmo e leggibile, che tutti riconoscono come "si può
toccare"; neutri caldi invece dei grigi freddi, per un tono accogliente; nessun richiamo a un
ente o a una regione. Il colore non è mai l'unico segnale: c'è sempre anche un'icona o un testo.

Il tema scuro è fuori perimetro. I token semantici permetteranno di aggiungerlo ridefinendo i
valori, senza toccare i template.

### 2.4 Il CSS sorgente

Da copiare in `src/main/css/app.css` (il percorso dei template va adattato se cambia).

```css
@import "tailwindcss" source(none);
@source "../jte";

/* Font: Inter 4.1, sottoinsiemi woff2 serviti dall'app. URL riscritti con l'impronta del
   contenuto dalla catena delle risorse di Spring. */
@font-face {
  font-family: "Inter";
  font-style: normal;
  font-weight: 100 900;
  font-display: swap;
  src: url("/fonts/inter-latin.woff2") format("woff2");
  unicode-range: U+0000-00FF, U+0131, U+0152-0153, U+02BB-02BC, U+02C6, U+02DA, U+02DC, U+0304,
    U+0308, U+0329, U+2000-206F, U+20AC, U+2122, U+2191, U+2193, U+2212, U+2215, U+FEFF, U+FFFD;
}

@font-face {
  font-family: "Inter";
  font-style: normal;
  font-weight: 100 900;
  font-display: swap;
  src: url("/fonts/inter-latin-ext.woff2") format("woff2");
  unicode-range: U+0100-02BA, U+02BD-02C5, U+02C7-02CC, U+02CE-02D7, U+02DD-02FF, U+0304, U+0308,
    U+0329, U+1D00-1DBF, U+1E00-1E9F, U+1EF2-1EFF, U+2020, U+20A0-20AB, U+20AD-20C0, U+2113,
    U+2C60-2C7F, U+A720-A7FF;
}

@theme {
  /* Solo i nostri colori: la palette di Tailwind è spenta */
  --color-*: initial;
  --color-canvas: #f6f5f2;
  --color-surface: #ffffff;
  --color-surface-muted: #efede8;
  --color-line: #e2ded6;
  --color-line-strong: #8b8478;
  --color-ink: #1c1b19;
  --color-ink-muted: #57534c;
  --color-white: #ffffff;
  --color-brand: #2a4ba0;
  --color-brand-strong: #1f3b85;
  --color-brand-soft: #e7ecf8;
  --color-danger: #b42318;
  --color-danger-strong: #912018;
  --color-danger-soft: #fdedeb;
  --color-success: #1d6b3a;
  --color-success-soft: #e5f2ea;
  --color-warning: #8a5300;
  --color-warning-soft: #fff2d9;
  --color-focus: #2a4ba0;

  --font-*: initial;
  --font-sans: "Inter", ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto,
    "Helvetica Neue", Arial, sans-serif;
  --font-sans--font-feature-settings: "cv08";

  --text-*: initial;
  --text-2xs: 0.6875rem;
  --text-2xs--line-height: 1rem;
  --text-xs: 0.8125rem;
  --text-xs--line-height: 1.125rem;
  --text-sm: 0.9375rem;
  --text-sm--line-height: 1.375rem;
  --text-base: 1.0625rem;
  --text-base--line-height: 1.625rem;
  --text-lg: 1.1875rem;
  --text-lg--line-height: 1.625rem;
  --text-xl: 1.375rem;
  --text-xl--line-height: 1.875rem;
  --text-2xl: 1.625rem;
  --text-2xl--line-height: 2rem;
  --text-3xl: 2rem;
  --text-3xl--line-height: 2.5rem;

  --animate-enter: enter 160ms ease-out;
  --animate-toast-in: toast-in 200ms ease-out;
  --animate-sheet-in: sheet-in 200ms ease-out;
  --animate-progress: progress 1.2s ease-in-out infinite;

  @keyframes enter {
    from { opacity: 0; transform: translateY(4px); }
  }
  @keyframes toast-in {
    from { opacity: 0; transform: translateY(0.5rem); }
  }
  @keyframes sheet-in {
    from { opacity: 0; transform: translateY(1.5rem); }
  }
  @keyframes progress {
    from { transform: translateX(-100%); }
    to { transform: translateX(300%); }
  }
}

/* busy: l'elemento che ha fatto partire una richiesta htmx, o ciò che c'è dentro */
@custom-variant busy {
  &.htmx-request { @slot; }
  .htmx-request & { @slot; }
}

/* page-busy: c'è almeno una richiesta htmx in corso nella pagina */
@custom-variant page-busy {
  body:has(.htmx-request) & { @slot; }
}

/* nav-busy: è in corso un cambio di pagina (link) o l'invio di un form */
@custom-variant nav-busy {
  body:has(a.htmx-request, form.htmx-request) & { @slot; }
}

/* short: schermo basso, per esempio il telefono in orizzontale */
@custom-variant short {
  @media (max-height: 30rem) { @slot; }
}

/* Margini laterali con la safe area dell'iPhone (tacca in orizzontale) */
@utility px-gutter {
  padding-inline: max(1rem, env(safe-area-inset-left)) max(1rem, env(safe-area-inset-right));
}

@utility pt-safe {
  padding-top: env(safe-area-inset-top);
}

@utility pb-safe {
  padding-bottom: env(safe-area-inset-bottom);
}

/* Spazio in fondo al contenuto: navigazione (4rem) + barra home dell'iPhone + respiro */
@utility pb-above-nav {
  padding-bottom: calc(4rem + env(safe-area-inset-bottom) + 2rem);
}

/* Posizione dei messaggi a comparsa, appena sopra la navigazione */
@utility bottom-above-nav {
  bottom: calc(4rem + env(safe-area-inset-bottom) + 0.75rem);
}

@layer base {
  html {
    -webkit-tap-highlight-color: transparent;
  }

  :focus-visible {
    outline: 3px solid var(--color-focus);
    outline-offset: 2px;
  }

  /* h1 e main ricevono il fuoco dal codice dopo una navigazione: niente anello */
  [tabindex="-1"]:focus {
    outline: none;
  }
}
```

Le classi si scrivono sempre intere nei template. `"bg-" + colore`, oppure una classe costruita in
Kotlin, non viene vista da Tailwind e lo stile manca senza nessun errore.

### 2.5 Spazi, raggi, ombre, livelli

Spaziature: la scala predefinita di Tailwind (multipli di 4 px). Quelle che usiamo:

| Dove | Classe | Valore |
|---|---|---|
| Margine laterale della pagina sul telefono | `px-gutter` | 16 px più la safe area |
| Margine della pagina su desktop | `lg:px-10 lg:pt-10` | 40 px |
| Titolo della pagina e contenuto | `mb-6 lg:mb-8` | 24 / 32 px |
| Fra una sezione e l'altra | `mt-8` | 32 px |
| Fra elementi in pila (schede, avvisi) | `space-y-3` | 12 px |
| Fra un campo e l'altro in un form | `space-y-5` | 20 px |
| Dentro una scheda | `p-4 lg:p-6` | 16 / 24 px |
| Fra icona e testo | `gap-2` o `gap-3` | 8 / 12 px |

Raggi (valori predefiniti di Tailwind 4):

| Classe | Valore | Dove |
|---|---|---|
| `rounded-lg` | 8 px | piccoli controlli dentro altri componenti (azione del messaggio) |
| `rounded-xl` | 12 px | bottoni, campi, avvisi, voci della navigazione |
| `rounded-2xl` | 16 px | schede, liste, messaggi a comparsa, dialoghi |
| `rounded-full` | | etichette di stato, iniziali, evidenza della voce corrente |

Ombre: le schede **non** hanno ombra (bordo `border-line` su sfondo `canvas`). `shadow-lg` solo
per i messaggi a comparsa, `shadow-xl` solo per i dialoghi.

Livelli: `z-40` intestazione e navigazione, `z-50` messaggi a comparsa, `z-60` indicatore di
caricamento, `z-70` link "Vai al contenuto". I dialoghi nativi stanno nel top layer e non hanno
bisogno di `z-`.

### 2.6 Breakpoint, griglia, larghezze

Breakpoint predefiniti di Tailwind, in rem (con il testo del browser ingrandito, il layout passa
prima a quello del telefono, ed è giusto così). Se ne usano solo due, più due casi speciali:

| Variante | Da | Cosa cambia |
|---|---|---|
| (nessuna) | 0 | telefono: una colonna, navigazione in basso |
| `sm:` | 40rem (640 px) | dentro il contenuto: bottoni in fila, schede chiave-valore su due colonne, dialogo al centro |
| `lg:` | 64rem (1024 px) | desktop: navigazione a sinistra, contenuto spostato a destra |
| `max-[20rem]:` | sotto 320 px | solo etichette della navigazione più piccole |
| `short:` | altezza sotto 30rem (480 px) | intestazione che scorre via; con `sm:` anche navigazione compatta |

Niente `md:`, `xl:`, `2xl:` per il guscio: due layout bastano.

Griglia: sul telefono una colonna con margini di 16 px. Su desktop la navigazione è larga
`w-64` (256 px) e il contenuto sta in una colonna centrata larga al massimo `max-w-3xl` (768 px).
Le pagine con tabelle larghe (in futuro) possono arrivare a `max-w-5xl` (1024 px), dichiarandolo
nel loro design.

### 2.7 Schermi difficili

- **iPhone con barra home e tacca**: meta viewport con `viewport-fit=cover`; la navigazione in
  basso ha `pb-safe`, il contenuto `pb-above-nav`, i margini laterali `px-gutter`, l'intestazione
  `pt-safe`. Nessun elemento interattivo dentro la safe area.
- **Telefono in orizzontale** (`short:`): l'intestazione smette di restare in alto e scorre via;
  da 640 px di larghezza la navigazione diventa compatta, alta 48 px, con icona ed etichetta
  affiancate.
- **Testo di sistema ingrandito**: su Android con caratteri grandi lo schermo "si stringe" (un
  telefono da 412 px con caratteri al 130% diventa largo circa 317 px). Il layout regge fino a
  256 px senza scorrimento orizzontale: sotto i 320 px le etichette della navigazione passano a
  11 px (`max-[20rem]:text-2xs`), sotto i 256 px si accorciano con i puntini. Le etichette non
  spariscono mai: per chi usa testo grande l'icona da sola non basta.
- **Zoom al 200%**: un desktop da 1280 px diventa largo 640 px e passa al layout del telefono.
  Nessun testo tagliato, nessuno scorrimento orizzontale.
- **Tastiera aperta**: i campi hanno almeno 16 px (i nostri 17), così iOS non ingrandisce la
  pagina quando si tocca un campo.
- **Browser vecchi** (iOS 15 e precedenti): parte degli stili non funziona; HTML semantico e
  navigazione senza JS tengono la pagina usabile (criterio 23).

### 2.8 Icone: Lucide

**Set**: [Lucide](https://lucide.dev) 1.59.0, licenza ISC (permissiva, come MIT; una parte delle
icone viene da Feather, MIT, coperta dallo stesso file di licenza). Tratto a mano libera,
arrotondato, coerente con Inter.

Come si usano:

- SVG inline nel template, copiato dal file del pacchetto `lucide-static` della versione fissata;
  un template JTE per icona (o uno con l'elenco), così ogni SVG è scritto una volta sola. Il file
  `LICENSE` di Lucide va nel repo accanto alle icone.
- Attributi originali di Lucide (`viewBox="0 0 24 24"`, `fill="none"`, `stroke="currentColor"`,
  `stroke-width="2"`, `stroke-linecap="round"`, `stroke-linejoin="round"`), più `aria-hidden="true"`
  e la classe della dimensione. Il colore viene dal testo (`text-*`).
- Dimensioni: `size-6` (24 px) navigazione, riquadri e avvisi; `size-5` (20 px) dentro i bottoni
  e per le frecce delle liste; `size-4` (16 px) negli errori dei campi; `size-8` (32 px) negli
  stati vuoti.
- Un'icona sta sempre accanto a un testo. Unica eccezione: il bottone "Chiudi" (X), che ha
  `aria-label="Chiudi"`.

Icone assegnate (nomi di Lucide):

| Uso | Icona |
|---|---|
| Home | `house` |
| Convivenza | `calendar-days` |
| Iscrizioni | `users-round` |
| Arrivi | `clipboard-check` |
| Altro (se le voci diventano più di cinque) | `ellipsis` |
| Aggiungi, nuovo | `plus` |
| Modifica | `pencil` |
| Elimina | `trash-2` |
| Cerca | `search` |
| Esporta | `download` |
| Esci (TALITA-3) | `log-out` |
| Riprova | `rotate-cw` |
| Chiudi | `x` |
| Apri un elemento di lista | `chevron-right` |
| Informazione | `info` |
| Conferma riuscita | `circle-check` |
| Attenzione, errore imprevisto | `triangle-alert` |
| Errore in un campo o in un form | `circle-alert` |
| Connessione assente | `wifi-off` |
| Connessione troppo lenta | `clock` |
| Sezione in preparazione | `hourglass` |
| Pagina non trovata | `compass` |
| Permesso negato (TALITA-3) | `lock` |
| Attesa dentro un bottone | `loader-circle` |

**Marchio**: un quadrato arrotondato `brand` con una T bianca, disegnato qui (non è un'icona di
Lucide). Si usa nell'intestazione e come favicon (`favicon.svg`, servito dall'app):

```html
<svg viewBox="0 0 32 32" aria-hidden="true" class="size-7 text-brand">
  <rect width="32" height="32" rx="9" fill="currentColor"/>
  <path d="M10.5 9h11M16 9v14" fill="none" stroke="#fff" stroke-width="3"
        stroke-linecap="round" stroke-linejoin="round"/>
</svg>
```

### 2.9 Movimento

- Durate brevi: 150 ms per colori e opacità (`transition-colors duration-150`), 160 ms per il
  contenuto che entra, 200 ms per messaggi e dialoghi. Mai oltre 250 ms. Curva `ease-out`.
- Solo opacità e spostamenti di pochi pixel. Niente rimbalzi, zoom, parallasse, scorrimenti
  laterali di pagina.
- Le animazioni con movimento usano `motion-safe:`. Con "riduci movimento" attivo il contenuto
  compare senza spostarsi, la barra di caricamento è ferma e piena.
- L'indicatore di caricamento compare dopo **300 ms**: le risposte veloci non fanno lampeggiare
  niente.

## 3. Guscio dell'app

### 3.1 Struttura

Telefono (sotto 1024 px):

```
┌──────────────────────────────┐  ← barra di caricamento (4 px, solo durante le richieste)
│ [T] Talità                   │  intestazione, 56 px, resta in alto
├──────────────────────────────┤
│ Titolo della pagina (h1)     │
│                              │  contenuto, colonna unica
│ …                            │
│                              │
│ ┌──────────────────────────┐ │  messaggi a comparsa, sopra la navigazione
│ └──────────────────────────┘ │
├──────────────────────────────┤
│ Home  Conviv. Iscriz. Arrivi │  navigazione, 64 px + barra home
└──────────────────────────────┘
```

Desktop (da 1024 px):

```
┌──────────────┬─────────────────────────────────────────┐
│ [T] Talità   │                                         │
│              │   Titolo della pagina (h1)              │
│ ▣ Home       │   contenuto centrato, max 768 px        │
│ ○ Convivenza │                                         │
│ ○ Iscrizioni │                                         │
│ ○ Arrivi     │                       ┌───────────────┐ │
│              │                       │ messaggio     │ │
│ (256 px)     │                       └───────────────┘ │
└──────────────┴─────────────────────────────────────────┘
```

Un solo HTML per i due layout: cambiano solo le classi `lg:`.

### 3.2 Markup del layout

Lo scheletro del template di layout, con le classi esatte. I punti `…` sono il contenuto della
pagina; `<hash>` è l'impronta messa dalla catena delle risorse.

```html
<!doctype html>
<html lang="it">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
  <meta name="theme-color" content="#ffffff">
  <meta name="htmx-config" content='{"historyCacheSize":0,"includeIndicatorStyles":false,"timeout":20000}'>
  <title>Convivenza · Talità</title>
  <link rel="icon" href="/favicon-<hash>.svg" type="image/svg+xml">
  <link rel="preload" href="/fonts/inter-latin-<hash>.woff2" as="font" type="font/woff2" crossorigin>
  <link rel="stylesheet" href="/css/app-<hash>.css">
  <script src="/js/htmx-<hash>.min.js" defer></script>
  <script src="/js/app-<hash>.js" defer></script>
</head>
<body hx-boost="true" class="min-h-dvh bg-canvas font-sans text-base text-ink antialiased">

  <a href="#contenuto" class="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-70 focus:rounded-xl focus:bg-ink focus:px-4 focus:py-3 focus:font-semibold focus:text-white">Vai al contenuto</a>

  <div id="caricamento" aria-hidden="true" class="pointer-events-none fixed inset-x-0 top-0 z-60 h-1 overflow-hidden opacity-0 transition-opacity duration-150 page-busy:opacity-100 page-busy:delay-300">
    <div class="h-full w-1/3 rounded-full bg-brand motion-safe:animate-progress motion-reduce:w-full"></div>
  </div>

  <header class="sticky top-0 z-40 flex min-h-14 items-center gap-2 border-b border-line bg-surface pt-safe px-gutter short:static lg:fixed lg:top-0 lg:left-0 lg:h-16 lg:w-64 lg:border-r lg:border-b-0 lg:px-6">
    <a href="/" class="inline-flex min-h-11 items-center gap-2 rounded-lg text-lg font-bold tracking-tight text-ink">
      <!-- marchio, vedi 2.8 -->
      Talità
    </a>
  </header>

  <nav aria-label="Navigazione principale" class="fixed inset-x-0 bottom-0 z-40 border-t border-line bg-surface pb-safe px-1 lg:top-16 lg:right-auto lg:w-64 lg:border-t-0 lg:border-r lg:px-3 lg:py-4">
    <ul class="mx-auto flex max-w-lg lg:max-w-none lg:flex-col lg:gap-1">
      <!-- una <li> per voce, vedi 3.3 -->
    </ul>
  </nav>

  <main id="contenuto" tabindex="-1" class="block px-gutter pt-6 pb-above-nav transition-opacity duration-150 motion-safe:animate-enter nav-busy:opacity-60 nav-busy:delay-300 lg:ml-64 lg:px-10 lg:pt-10 lg:pb-16">
    <div class="mx-auto w-full max-w-3xl">
      <div class="mb-6 lg:mb-8">
        <h1 tabindex="-1" class="text-2xl font-semibold tracking-tight lg:text-3xl">Convivenza</h1>
        <!-- facoltativo: <p class="mt-2 text-ink-muted">…</p> -->
      </div>
      …
    </div>
  </main>

  <div id="messaggi" aria-live="polite" class="pointer-events-none fixed inset-x-0 bottom-above-nav z-50 flex flex-col items-center gap-2 px-gutter lg:inset-x-auto lg:right-6 lg:bottom-6 lg:w-full lg:max-w-md lg:px-0"></div>

  <!-- modelli dei messaggi a comparsa, clonati da app.js (vedi 4.7) -->
  <template id="messaggio-rete-assente">…</template>
  <template id="messaggio-rete-lenta">…</template>
  <template id="messaggio-errore-server">…</template>
</body>
</html>
```

Note:

- `hx-boost="true"` sul `body`: tutti i link interni e i form diventano richieste htmx. I link
  che non devono passare da htmx (download, siti esterni) hanno `hx-boost="false"`.
- Nessun `hx-indicator`: htmx mette la classe `htmx-request` sull'elemento che fa la richiesta e
  le varianti `busy:`, `page-busy:` e `nav-busy:` fanno il resto.
- Il nome "Talità" è un link alla home ma **non** ha mai `aria-current`: l'unica voce con
  `aria-current="page"` è quella della navigazione (criterio 21).
- Nessun `<script>` inline, nessun attributo `on*=`, nessun `hx-on`, nessuno `style=""`.
- `theme-color` bianco: su Android la barra del browser si fonde con l'intestazione.
- `px-1` sul `<nav>` e nessun margine sull'etichetta delle voci, per tenere a 320 px ogni voce a
  78 px: "Convivenza" a 13 px misura 74 e con margini più larghi si tronca in "Conviven…".

### 3.3 Navigazione

**Voci** (TALITA-2, vista del maestro; le voci del genitore le decide TALITA-3):

| Ordine | Etichetta | URL | Icona | Cosa ci sarà |
|---|---|---|---|---|
| 1 | Home | `/` | `house` | pagina iniziale |
| 2 | Convivenza | `/convivenza` | `calendar-days` | configurazione, maestri, pagina informativa per i genitori |
| 3 | Iscrizioni | `/iscrizioni` | `users-round` | elenco e inserimento dal modulo cartaceo |
| 4 | Arrivi | `/arrivi` | `clipboard-check` | check-in e stampe |

Perché quattro: restano sotto il limite di cinque, e con quattro ogni voce è larga almeno 78 px anche su uno schermo da 320 px (74 bastano a "Convivenza" a 13 px), quindi si legge intera anche con il testo ingrandito. Il profilo con "Esci" arriva con il login (TALITA-3) in alto a destra nell'intestazione, come nelle app di posta.

Regole:

- Al massimo cinque voci sul telefono. Se le voci visibili a una persona diventano più di cinque,
  le prime quattro restano e la quinta diventa "Altro" (`ellipsis`), che apre una pagina con le
  altre come lista. Su desktop si possono mostrare tutte.
- Solo sezioni del perimetro di Talità: la convivenza, le iscrizioni, gli arrivi.
- Le voci arrivano al template come lista costruita a ogni richiesta (etichetta, URL, icona,
  corrente sì/no), non scritte nel template: TALITA-3 le filtrerà per ruolo (maestro o genitore).
- La voce corrente è quella della sezione in cui si trova la pagina (anche nelle pagine interne:
  la pagina di una singola iscrizione ha corrente "Iscrizioni"). Nelle pagine di errore nessuna voce è
  corrente.
- URL in italiano e senza accenti (li vede chi usa Talità, anche nei link condivisi).

Voce della navigazione, classi esatte (la stessa `<li>` per telefono e desktop):

```html
<li class="min-w-0 flex-1">
  <a href="/convivenza" aria-current="page"
     class="group flex h-16 flex-col items-center justify-center gap-1 rounded-xl text-xs font-medium text-ink-muted transition-colors duration-150 active:bg-surface-muted busy:bg-surface-muted aria-[current=page]:font-semibold aria-[current=page]:text-brand max-[20rem]:text-2xs sm:short:h-12 sm:short:flex-row sm:short:gap-2 sm:short:text-sm sm:short:aria-[current=page]:bg-brand-soft lg:h-12 lg:flex-row lg:justify-start lg:gap-3 lg:px-3 lg:text-base lg:hover:bg-surface-muted lg:aria-[current=page]:bg-brand-soft">
    <span class="flex h-8 w-14 items-center justify-center rounded-full transition-colors duration-150 group-aria-[current=page]:bg-brand-soft max-[20rem]:w-12 sm:short:h-auto sm:short:w-auto sm:short:group-aria-[current=page]:bg-transparent lg:h-auto lg:w-auto lg:group-aria-[current=page]:bg-transparent">
      <svg class="size-6" …></svg>
    </span>
    <span class="max-w-full truncate">Convivenza</span>
  </a>
</li>
```

`aria-current="page"` solo sulla voce corrente; sulle altre l'attributo non c'è.

Cosa si vede:

- telefono: icona in una pillola `brand-soft` larga 56 px ed etichetta sotto, entrambe `brand`;
  le altre voci in `ink-muted`;
- desktop: tutta la riga della voce corrente su `brand-soft`, testo `brand`;
- al tocco, subito (prima che arrivi la risposta): la voce toccata si scurisce (`active:` e
  `busy:`), così chi tocca sa che il tocco è arrivato.

### 3.4 Titolo della scheda del browser

`<title>`: nome della pagina, punto in mezzo, Talità. Esempi: `Convivenza · Talità`,
`Pagina non trovata · Talità`. La home ha solo `Talità`. Lo stesso testo dell'`h1` quando
l'`h1` è il nome della pagina.

## 4. Componenti

Le classi qui sotto sono quelle da scrivere nei template. Nel catalogo
`docs/design/KOINE-11/componenti.html` ogni componente si vede con dati sintetici.

### 4.1 Bottoni

| Tipo | Quando | Classi |
|---|---|---|
| Base (sempre) | | `inline-flex min-h-12 items-center justify-center gap-2 rounded-xl px-5 text-base font-semibold transition-colors duration-150 select-none disabled:cursor-wait disabled:opacity-70` |
| Primario | l'azione principale, una sola per schermata | base + `bg-brand text-white hover:bg-brand-strong active:bg-brand-strong` |
| Secondario | alternative, "Annulla" | base + `border border-line-strong bg-surface text-ink hover:bg-surface-muted active:bg-surface-muted` |
| Pericolo | azioni che tolgono qualcosa per sempre o annullano un'iscrizione | base + `bg-danger text-white hover:bg-danger-strong active:bg-danger-strong` |
| Testo | azioni minori dentro schede e liste ("Modifica") | `inline-flex min-h-11 items-center gap-2 rounded-xl px-3 text-base font-semibold text-brand transition-colors duration-150 hover:bg-brand-soft active:bg-brand-soft` |

- Icona facoltativa a sinistra, `size-5`, con `aria-hidden="true"`.
- Sul telefono i bottoni dei form e dei dialoghi sono larghi quanto lo schermo (`w-full sm:w-auto`).
- Gruppo di bottoni: `flex flex-col gap-3 sm:flex-row-reverse sm:justify-start`, con il
  primario **primo** nell'HTML. Sul telefono il primario sta sopra e "Annulla" sotto; da 640 px
  il primario sta a destra.
- Un bottone non è mai disabilitato per dire "manca qualcosa": resta attivo e, se si tocca, gli
  errori spiegano cosa manca. È disabilitato solo durante la sua richiesta, per evitare il doppio
  invio.
- Durante la richiesta (`hx-disabled-elt="this"` sul bottone, oppure
  `hx-disabled-elt="find button[type=submit]"` sul form) il bottone mostra la rotellina e il testo
  "in corso":

```html
<button type="submit" class="… base … bg-brand text-white …">
  <svg class="hidden size-5 animate-spin busy:block" aria-hidden="true">…loader-circle…</svg>
  <span class="busy:hidden">Salva la convivenza</span>
  <span class="hidden busy:inline">Salvataggio…</span>
</button>
```

### 4.2 Campi ed errori di validazione

```html
<div class="space-y-1.5">
  <label for="nome" class="block font-semibold">Nome</label>
  <p id="nome-aiuto" class="text-sm text-ink-muted">Come compare nei documenti.</p>
  <input id="nome" name="nome" type="text" autocomplete="given-name"
         aria-describedby="nome-aiuto nome-errore" aria-invalid="true"
         class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface px-4 text-base text-ink placeholder:text-ink-muted focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
  <p id="nome-errore" class="flex items-start gap-1.5 text-sm font-medium text-danger">
    <svg class="mt-0.5 size-4 shrink-0" aria-hidden="true">…circle-alert…</svg>
    Scrivi il nome.
  </p>
</div>
```

- L'etichetta sta sempre sopra il campo ed è sempre visibile. Il segnaposto (`placeholder`) non
  sostituisce l'etichetta; si usa solo per un esempio nei campi di ricerca.
- Aiuto facoltativo fra etichetta e campo, collegato con `aria-describedby`.
- Errore sotto il campo, in rosso con icona, collegato con `aria-describedby`; il campo ha
  `aria-invalid="true"`. Senza errore, il paragrafo dell'errore e `aria-invalid` non ci sono.
- Con più di un errore, in cima al form c'è un avviso di pericolo (4.6) con "Controlla i 2 campi
  segnati in rosso." e il fuoco va al primo campo sbagliato.
- `type`, `inputmode` e `autocomplete` giusti per ogni campo: la tastiera del telefono cambia di
  conseguenza (`type="email"`, `type="tel"`, `inputmode="numeric"`).
- **Data di nascita** (tre campi, regola di Koinè approvata da Matteo il 19/10/2026; in Talità la data di nascita del bambino è un dato da confermare in TALITA-5, e lì si decide anche il campo): Giorno,
  Mese e Anno dentro un `fieldset` con `legend`, tutti con
  `inputmode="numeric"` e larghi `w-18`, `w-18`, `w-26`; per una data di nascita è molto più facile
  scrivere 4 4 2016 che scorrere un calendario di dieci anni. Le date degli eventi (convivenze)
  usano invece `type="date"`.
- Scelte: `fieldset` + `legend`; ogni opzione in `flex min-h-12 items-center gap-3`, con
  `size-6 accent-brand` sul controllo. Sotto le cinque opzioni radio; oltre, una `select` con le
  stesse classi del campo di testo.
- Campo di ricerca: `type="search"`, icona `search` a sinistra (`absolute left-3.5 top-1/2
  size-5 -translate-y-1/2 text-ink-muted`) e campo con `pl-11`; etichetta `sr-only` "Cerca una
  persona".
- Pila dei campi: `space-y-5`. Bottoni del form in fondo, con `mt-6`.

### 4.3 Liste

La lista è il componente principale di Talità: iscrizioni, maestri, arrivi.

```html
<ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
  <li>
    <a href="/iscrizioni/…" class="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted">
      <span class="grid size-10 shrink-0 place-items-center rounded-full bg-brand-soft text-sm font-semibold text-brand">MB</span>
      <span class="min-w-0 flex-1">
        <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span class="font-semibold wrap-break-word">Marta Bianchi</span>
          <span class="… etichetta di stato …">Confermata</span>
        </span>
        <span class="block text-sm text-ink-muted">3 giorni di presenza</span>
      </span>
      <svg class="size-5 shrink-0 text-ink-muted" aria-hidden="true">…chevron-right…</svg>
    </a>
  </li>
</ul>
```

- Tutta la riga è il link (area di tocco alta almeno 64 px), con la freccia a destra.
- A sinistra: per le persone le iniziali in un cerchio (`grid size-10 shrink-0 place-items-center
  rounded-full bg-brand-soft text-sm font-semibold text-brand`), per le altre cose un'icona in un
  riquadro. Le iniziali non sono una foto: niente foto delle persone.
- Titolo in `font-semibold`, dettagli in `text-sm text-ink-muted` separati da " · ".
- I nomi non si tagliano mai con i puntini: vanno a capo.
- L'etichetta di stato sta accanto al titolo (va a capo se non c'è spazio), non in colonna a
  destra: sul telefono ruberebbe spazio al testo.
- Righe non cliccabili (per esempio un elenco di sola lettura): stesso `li`, senza link e senza freccia.
- Lista vuota: si mostra lo stato vuoto (4.9), mai una lista senza righe.
- Liste lunghe: ricerca in cima; la paginazione o il "Mostra altre" li decide il ticket della
  schermata.

### 4.4 Schede

```html
<section class="rounded-2xl border border-line bg-surface p-4 lg:p-6" aria-labelledby="iscrizione">
  <div class="flex items-center justify-between gap-3">
    <h2 id="iscrizione" class="text-lg font-semibold">Iscrizione</h2>
    <a href="…" class="… bottone testo …">Modifica</a>
  </div>
  <dl class="mt-3 space-y-3">
    <div class="sm:grid sm:grid-cols-[11rem_1fr] sm:gap-6">
      <dt class="text-sm text-ink-muted">Giorni di presenza</dt>
      <dd class="font-medium">3 giorni</dd>
    </div>
  </dl>
</section>
```

- Schede in pila con `space-y-3`.
- Una lista dentro una scheda va a filo dei bordi (`-mx-4 -mb-4 lg:-mx-6 lg:-mb-6`, scheda con
  `overflow-hidden`) e ha solo il bordo in alto.
- Un dato che manca si scrive "Non indicata" / "Non indicato" in `text-ink-muted`; mai un trattino
  o un campo vuoto.

### 4.5 Etichette di stato

`inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap`
più una variante:

| Variante | Classi | Esempi |
|---|---|---|
| Neutra | `bg-surface-muted text-ink-muted` | Bozza, Chiusa |
| Informativa | `bg-brand-soft text-brand` | Aperta, In corso |
| Positiva | `bg-success-soft text-success` | Confermata, Pagata |
| Attenzione | `bg-warning-soft text-warning` | In forse, Da pagare |
| Negativa | `bg-danger-soft text-danger` | Annullata |

Una parola o due, con l'iniziale maiuscola. L'etichetta ripete in parole quello che il colore
dice: il colore da solo non basta.

### 4.6 Avvisi nella pagina

Un messaggio che resta nella pagina finché la situazione non cambia.

```html
<div class="flex items-start gap-3 rounded-xl bg-brand-soft p-4">
  <svg class="size-6 shrink-0 text-brand" aria-hidden="true">…info…</svg>
  <p>Le iscrizioni chiudono il 30 novembre 2026.</p>
</div>
```

| Variante | Sfondo | Icona |
|---|---|---|
| Informazione | `bg-brand-soft` | `info`, `text-brand` |
| Conferma | `bg-success-soft` | `circle-check`, `text-success` |
| Attenzione | `bg-warning-soft` | `triangle-alert`, `text-warning` |
| Errore | `bg-danger-soft` | `circle-alert`, `text-danger` |

Testo in `text-ink`. Se l'avviso compare dopo un'azione (per esempio un form rimandato con errori),
ha `role="alert"`; una conferma ha `role="status"`.

### 4.7 Messaggi a comparsa

Per ciò che riguarda tutta la pagina e non un punto preciso: connessione assente, errore del
server, conferma dopo un salvataggio. Scuri, in basso sopra la navigazione sul telefono, in basso
a destra su desktop.

```html
<div role="alert" class="pointer-events-auto grid w-full max-w-md grid-cols-[auto_1fr_auto] items-start gap-x-3 rounded-2xl bg-ink py-3.5 pr-1.5 pl-4 text-white shadow-lg motion-safe:animate-toast-in">
  <svg class="mt-0.5 size-6" aria-hidden="true">…wifi-off…</svg>
  <div>
    <p class="font-semibold">Nessuna connessione</p>
    <p class="text-sm text-white/80">Controlla di avere campo o il Wi-Fi e riprova.</p>
    <button type="button" class="mt-2 inline-flex min-h-11 items-center gap-1.5 rounded-lg bg-white/15 px-3.5 font-semibold hover:bg-white/25 focus-visible:outline-white">
      <svg class="size-4.5" aria-hidden="true">…rotate-cw…</svg>Riprova
    </button>
  </div>
  <button type="button" aria-label="Chiudi" class="-mt-2 grid size-11 place-items-center rounded-full text-white/80 hover:bg-white/15 focus-visible:outline-white">
    <svg class="size-5" aria-hidden="true">…x…</svg>
  </button>
</div>
```

- I messaggi stanno nel layout come `<template>` (testi nei template, non nel JS); `app.js` li
  clona dentro `#messaggi`.
- Errori (`role="alert"`): restano finché la persona non tocca "Chiudi" o finché la richiesta
  successiva va a buon fine. Nessuna scomparsa a tempo: chi legge piano deve poter leggere.
- Conferme (`role="status"`, icona `circle-check`, solo titolo e "Chiudi"): spariscono dopo 6
  secondi, ma restano finché hanno il fuoco o il mouse sopra.
- Uno solo per tipo: un nuovo errore di rete sostituisce quello vecchio, non si accumulano.
- "Riprova" c'è solo se la richiesta fallita era una lettura (GET) e ripete quella richiesta. Per
  un invio (POST) non c'è: il form resta compilato e basta toccare di nuovo il bottone.
- Il messaggio non copre la navigazione e non sposta il contenuto.

### 4.8 Dialogo di conferma

Prima di un'azione con conseguenze (ritiro di un'iscrizione,
eliminazione). Non per le azioni normali: salvare non chiede conferma.

```html
<dialog id="conferma-ritiro" aria-labelledby="conferma-titolo" aria-describedby="conferma-testo"
        class="mx-0 mt-auto mb-0 w-full max-w-none rounded-t-2xl bg-surface px-gutter pt-6 pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-ink shadow-xl backdrop:bg-ink/50 motion-safe:animate-sheet-in sm:m-auto sm:max-w-md sm:rounded-2xl sm:p-6">
  <h2 id="conferma-titolo" class="text-xl font-semibold tracking-tight">Ritirare l’iscrizione?</h2>
  <p id="conferma-testo" class="mt-2 text-ink-muted">L’iscrizione di Marta Bianchi alla convivenza di settembre 2027 risulterà ritirata. Resta nell’elenco, con lo stato «Ritirata».</p>
  <div class="mt-6 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
    <button type="submit" form="form-ritiro" class="… primario … w-full sm:w-auto">Ritira l’iscrizione</button>
    <form method="dialog" class="contents"><button autofocus class="… secondario … w-full sm:w-auto">Annulla</button></form>
  </div>
</dialog>
```

- Elemento `<dialog>` nativo aperto con `showModal()` da `app.js`: fuoco intrappolato, tasto Esc,
  sfondo inerte senza codice in più. "Annulla" chiude con `<form method="dialog">`.
- Sul telefono sale dal basso a tutta larghezza; da 640 px compare al centro, largo al massimo
  448 px.
- Il fuoco iniziale va su "Annulla" (`autofocus`).
- Titolo = la domanda con il verbo. Testo = cosa succede, con numeri, nomi e data, e cosa resta.
  Bottone = lo stesso verbo del titolo. Mai "Sei sicuro?", mai "OK" o "Sì".
- Il bottone è primario se l'azione si può rimediare (ritiro: l'iscrizione resta nell'elenco), di pericolo se
  toglie qualcosa per sempre.
- Senza JavaScript il bottone che apre il dialogo è un link a una pagina di conferma con gli stessi
  testi.

### 4.9 Stato vuoto

Quando una lista non ha elementi o una sezione non è ancora pronta.

```html
<div class="flex flex-col items-center rounded-2xl border border-line bg-surface px-6 py-12 text-center">
  <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
    <svg class="size-8" aria-hidden="true">…</svg>
  </span>
  <h2 class="mt-4 text-xl font-semibold tracking-tight">Nessuna convivenza in programma</h2>
  <p class="mt-2 max-w-sm text-ink-muted">Quando la crei, la trovi qui con le date, i maestri e le iscrizioni.</p>
  <a href="…" class="mt-6 … primario …">Crea la convivenza</a>
</div>
```

Titolo che dice cosa manca, una frase su cosa comparirà qui, e il bottone per crearlo se la
persona può farlo. Se non può, niente bottone.

### 4.10 Indicatore di caricamento

- Barra alta 4 px in cima allo schermo, colore `brand`, che scorre da sinistra a destra (ferma e
  piena con "riduci movimento").
- Compare 300 ms dopo l'inizio di qualsiasi richiesta htmx e sparisce appena la richiesta
  finisce, bene o male.
- Durante un cambio di pagina o l'invio di un form, il contenuto di prima si attenua al 60% dopo
  gli stessi 300 ms: si capisce che sta per cambiare.
- La voce o la riga toccata cambia sfondo subito (`busy:`), senza aspettare i 300 ms.
- Non c'è mai una rotellina a tutto schermo che blocca la pagina.
- Classi: vedi `#caricamento` in 3.2.

### 4.11 Pagine di errore e di permesso negato

Dentro il guscio (intestazione e navigazione ci sono, nessuna voce corrente), contenuto centrato:

```html
<div class="flex flex-col items-center py-10 text-center lg:py-16">
  <span class="grid size-16 place-items-center rounded-full bg-brand-soft text-brand">
    <svg class="size-8" aria-hidden="true">…compass…</svg>
  </span>
  <h1 tabindex="-1" class="mt-5 text-2xl font-semibold tracking-tight lg:text-3xl">Pagina non trovata</h1>
  <p class="mt-2 max-w-sm text-ink-muted">L’indirizzo potrebbe essere sbagliato, oppure la pagina non esiste più.</p>
  <a href="/" class="mt-6 … primario …">Vai alla home</a>
</div>
```

| Pagina | Icona e colori | Titolo | Testo | Bottoni |
|---|---|---|---|---|
| 404 | `compass`, `bg-brand-soft text-brand` | Pagina non trovata | L’indirizzo potrebbe essere sbagliato, oppure la pagina non esiste più. | Vai alla home (primario) |
| 500 | `triangle-alert`, `bg-warning-soft text-warning` | Qualcosa non ha funzionato | Non è colpa tua: c’è stato un problema in Talità. Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza. | Riprova (primario, solo se la richiesta era un GET: link allo stesso indirizzo), Vai alla home (secondario) |
| 403 (TALITA-3) | `lock`, `bg-brand-soft text-brand` | Non puoi vedere questa pagina | La pagina esiste, ma il tuo accesso non comprende questi dati. Se pensi di doverla vedere, chiedi a chi organizza la convivenza. | Vai alla home (primario) |

Mai codici di errore, nomi di eccezioni, stack trace o parole come "server" e "database".

## 5. htmx: cosa vede chi usa Talità

**Cambio di pagina** (link della navigazione o di una lista, `hx-boost`):

- si aggiorna tutto il `body`: intestazione, navigazione con la nuova voce corrente, contenuto,
  più il titolo della scheda del browser e l'indirizzo. La pagina torna in cima;
- i messaggi di errore aperti spariscono (la richiesta è andata a buon fine);
- il nuovo contenuto entra con una dissolvenza di 160 ms;
- il fuoco va sull'`h1` della nuova pagina (`tabindex="-1"`), così chi usa un lettore di schermo
  sente il titolo; senza anello visibile;
- il tasto indietro torna alla pagina precedente chiedendola di nuovo al server: nessuna copia
  delle pagine resta nel browser (`historyCacheSize: 0`);
- se la risposta è 404 o 500, si vede la pagina d'errore dentro il guscio come una pagina
  qualsiasi, con il suo indirizzo e il suo titolo (criteri 25 e 26);
- doppio tocco o tocco su un'altra voce mentre si aspetta: vale l'ultimo tocco, senza errori.

**Richiesta che non cambia pagina** (un pezzo della pagina, per esempio una riga dopo una
modifica):

- si aggiorna solo quel pezzo; il resto resta com'è, compresa la posizione di scorrimento;
- se va male (5xx), il pezzo **non** viene sostituito e compare il messaggio "Qualcosa non ha
  funzionato" (criterio 28).

**Rete assente o lenta** (qualsiasi richiesta):

- il contenuto resta quello di prima, l'indicatore sparisce, compare il messaggio "Nessuna
  connessione" (criterio 27);
- dopo 20 secondi senza risposta la richiesta si ferma e compare "La connessione è troppo
  lenta", con lo stesso comportamento;
- tornata la rete, "Riprova" o un nuovo tocco funzionano normalmente;
- vale soprattutto per i maestri durante la convivenza, dove il campo può mancare a lungo: una richiesta che fallisce non cancella mai quello che la persona ha scritto, il form resta compilato (già previsto in 4.7).

**Senza JavaScript** (o prima che htmx sia caricato): link e form funzionano come in un sito
normale, con il ricaricamento della pagina. Niente indicatore e niente messaggi a comparsa, ma le
pagine 404 e 500 arrivano dal server con gli stessi testi.

Il codice che fa queste cose sta in un file JS dell'app (`app.js`), non nei template: gestione di
`htmx:beforeSwap`, `htmx:sendError`, `htmx:timeout`, `htmx:afterRequest`, `htmx:afterSettle`,
apertura dei dialoghi. Come scriverlo lo decide il developer.

## 6. Scrivere i testi

### Tono

- Si dà del **tu**, con gentilezza. Frasi brevi, parole di tutti i giorni.
- Niente gergo informatico: non "utente", "login", "sessione", "server", "database", "errore 500",
  "campo obbligatorio", "operazione non valida". Al loro posto: "persona", "accedi", "Talità",
  "scrivi il nome".
- Le parole che maestri e genitori usano già vanno bene: convivenza, maestri, bambini, genitori,
  iscrizione, arrivi. "Persona" quando non si sa di chi si parla. Non si usano «Koinè», «Centro»,
  «comunità», «fratelli»: sono di un altro progetto.
- Quando si può, forme che valgono per tutti: "Ti diamo il benvenuto" invece di "Benvenuto".
  Talità non raccoglie il sesso: di un bambino o di un genitore non si scrivono forme accordate.
  Si parla della cosa («Iscrizione confermata», «Arrivo segnato») o si usano parole che non
  cambiano («Presente»). Il bambino è «il bambino», mai «bambino/a».
- Mai il nome di una parrocchia, di una città o di una persona scritto nei template: si scrive
  "Talità".

### Maiuscole e punteggiatura

- Maiuscola solo all'inizio: "Nuova convivenza", non "Nuova Convivenza".
- Titoli, etichette e bottoni senza punto finale. Frasi complete (messaggi, aiuti, errori) con il
  punto.
- Caratteri tipografici giusti: apostrofo ’, puntini di sospensione … (un carattere), trattino
  medio – negli intervalli, virgolette « » quando si cita il nome di un bottone. "È" maiuscola con
  l'accento, mai "E'".
- Nel testo i nomi dei bottoni fra virgolette basse: Tocca «Nuova convivenza».

### Titoli

- Pagina di sezione: il nome della sezione ("Iscrizioni").
- Pagina di dettaglio: il nome della cosa ("Convivenza di settembre 2027", "Marta Bianchi").
- Form: l'azione ("Nuova iscrizione", "Modifica i dati").

### Bottoni

- Verbo all'imperativo, più l'oggetto se serve a capire: "Salva la convivenza", "Ritira
  l’iscrizione", "Iscrivi", "Annulla".
- Al massimo tre o quattro parole.
- Mai "OK", "Sì", "No", "Conferma" da soli, "Clicca qui".
- Durante l'attesa: il nome dell'azione in corso con i puntini: "Salvataggio…", "Invio…".

### Errori

Un errore dice cosa fare, non solo cosa non va. Senza colpe e senza codici.

| Invece di | Si scrive |
|---|---|
| Campo obbligatorio | Scrivi il nome. |
| Data non valida | Scrivi una data vera, per esempio 4 4 2016. |
| Formato email errato | Scrivi un indirizzo email completo, per esempio nome@example.org. |
| Errore 500 | Qualcosa non ha funzionato. Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza. |
| Network error | Nessuna connessione. Controlla di avere campo o il Wi-Fi e riprova. |
| Accesso negato | Non puoi vedere questa pagina. Se pensi di doverla vedere, chiedi a chi organizza la convivenza. |

### Conferme

- Dopo un'azione riuscita: oggetto e participio, breve. "Convivenza salvata.", "Iscrizione ritirata."
- Prima di un'azione con conseguenze: dialogo (4.8).

### Stati vuoti

Cosa manca, cosa comparirà, come aggiungerlo: "Nessuna convivenza in programma. Quando la crei,
la trovi qui con le date, i maestri e le iscrizioni."

### Date, numeri, importi

| Cosa | Formato | Esempio |
|---|---|---|
| Data | giorno senza zero, mese in lettere minuscole, anno | 3 settembre 2027 |
| Data con giorno della settimana | quando aiuta (eventi) | venerdì 3 settembre 2027 |
| Intervallo nello stesso mese | trattino medio senza spazi | 3–5 settembre 2027 |
| Intervallo fra mesi diversi | trattino medio con spazi | 30 aprile – 2 maggio 2026 |
| Periodo | "dal … al …" | dal 3 ottobre 1990 all’11 marzo 1998 |
| Ora | 24 ore con i due punti | 21:30 |
| Età | numero e "anni" | 65 anni |
| Numeri | punto per le migliaia | 1.250 persone |
| Importi | virgola per i decimali, euro dopo, decimali solo se non zero | 120 €, 120,50 € |
| Zero | in parole | Nessuna iscrizione (non "0 iscrizioni") |
| Singolare e plurale | sempre giusti | 1 bambino, 24 bambini (mai "bambino/i") |

Nel codice le date e gli importi si formattano con `Locale.ITALIAN`, non a mano.

## 7. Accessibilità: controlli prima della PR

- `<html lang="it">`, un solo `h1` per pagina, titoli in ordine (`h1` → `h2` → `h3`).
- Punti di riferimento: `header`, `nav` con `aria-label="Navigazione principale"`, `main`.
- Link "Vai al contenuto" come primo elemento della pagina.
- Anello del fuoco visibile su tutto ciò che si raggiunge con la tastiera (regola base in 2.4).
- Contrasto: solo i token del 2.3, che sono già verificati.
- Aree di tocco di almeno 44 × 44 px (bottoni 48, voci della navigazione 64, righe di lista 64).
- Etichette sempre visibili, errori collegati con `aria-describedby` e `aria-invalid`.
- Il colore non è mai l'unico segnale.
- Nessuno scorrimento orizzontale da 256 px in su; zoom al 200% senza testo tagliato.
- Viewport senza `maximum-scale` né `user-scalable=no`.
- Animazioni con `motion-safe:`.
- Messaggi a comparsa con `role="alert"` (errori) o `role="status"` (conferme), dentro una regione
  `aria-live`.
- axe-core senza violazioni "serious" o "critical" (criterio 30).

## 8. Da non fare

- Classi costruite a pezzi o colori fuori dai token (`bg-[#…]`, `text-blue-600`).
- Testo sotto i 13 px (l'unica eccezione è l'etichetta della navigazione sotto i 320 px).
- Bottoni con la sola icona (tranne "Chiudi"), bottoni disabilitati per dire che manca qualcosa,
  segnaposto al posto delle etichette.
- Rotelline a tutto schermo, messaggi di errore che spariscono da soli, finestre che si aprono da
  sole.
- Corsivo, maiuscolo, sottolineature decorative, ombre sulle schede.
- Font, icone, script o stili da CDN o da servizi esterni.
- `<script>` inline, attributi `on*=`, `hx-on`, `style=""`: la Content-Security-Policy li
  bloccherà (TALITA-3).
- Testi tecnici rivolti alle persone (codici, eccezioni, nomi di tabelle).
- Le parole «Koinè», «Centro», «comunità» e «fratelli» nei testi fissi: sono di un altro progetto.
- Voci o schermate fuori dal perimetro di Talità, quello scritto in `CLAUDE.md`.
