# Design system di Talità

Eredita il design system di Koinè (KOINE-11, Grace Kerning) e lo adatta a Talità con TALITA-2: token, componenti, accessibilità e movimento restano quelli, cambiano marchio, voci, testi ed esempi.

Questa guida vale per tutte le schermate di Talità. Chi sviluppa la usa così com'è: token, classi
Tailwind e testi. Se una schermata ha bisogno di un pezzo che qui non c'è, il pezzo si aggiunge
prima qui (con il ticket della schermata), poi nel template.

`docs/design/KOINE-11/` è il mockup di origine, copiato da Koinè e non più modificato, tranne alcune righe di `componenti.html` (nomi di parrocchia e indirizzo email di esempio, sostituiti con «Parrocchia di esempio» e `nome@example.org` perché il repo è pubblico): componenti e guscio valgono ancora, testi, voci e marchio no. Si apre `index.html` nel browser, senza server e senza rete. Se il mockup e questa guida non vanno d'accordo, vale la guida.

`docs/design/TALITA-3/` sono i mockup del login, dei ruoli e dei maestri (TALITA-3, Grace Kerning): 42 pagine statiche con tutti gli stati e dati sintetici, che si aprono allo stesso modo da `index.html`. Dove il mockup e il testo di questa guida non vanno d'accordo, vale la guida.

`docs/design/TALITA-4/` sono i mockup della convivenza e della pagina informativa (TALITA-4, Grace Kerning): 27 pagine statiche con tutti gli stati e dati sintetici, da `index.html`; `talita-4.css` importa il CSS del mockup di origine. Dove il mockup e il testo di questa guida non vanno d'accordo, vale la guida.

`docs/design/TALITA-6/` sono i mockup di Arrivi, della scheda del bambino e delle tre stampe (TALITA-6, Grace Kerning): 82 pagine statiche con tutti gli stati e dati sintetici, da `index.html`; `talita-6.css` importa il CSS dei mockup precedenti e ha in fondo le regole di stampa. Le stampe si provano con Ctrl+P. Dove il mockup e il testo di questa guida non vanno d'accordo, vale la guida (per esempio «Pagata Niente»: il mockup della scheda ha «0 €», la guida chiede lo zero in parole).

`docs/design/TALITA-7/` sono i mockup della conservazione dei dati e delle cancellazioni (TALITA-7, Grace Kerning): 25 pagine statiche più l'indice (`index.html`) e `talita-7.css`, con i dati sintetici di Luca e Anna Rossi e della «Convivenza di settembre 2027»: il Profilo con «I tuoi dati» e i dialoghi (si aprono con l'ancora, `profilo-genitore.html#cancella-luca`), «Le tue iscrizioni» che si guardano soltanto, la scheda «Conservazione dei dati» per i maestri, le segnalazioni e le iscrizioni cancellate e l'informativa. Dove il mockup e il testo di questa guida non vanno d'accordo, vale la guida.

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
    /* Le barre fisse del telefono non coprono quello che lo scorrimento porta in vista (il fuoco, un'ancora):
       intestazione in alto (3.5rem) e navigazione in basso (4rem), più la safe area e un po' di respiro. */
    scroll-padding-top: calc(3.5rem + env(safe-area-inset-top) + 0.5rem);
    scroll-padding-bottom: calc(4rem + env(safe-area-inset-bottom) + 0.5rem);
  }

  /* Da lg intestazione e navigazione stanno nella colonna a sinistra e non coprono il contenuto */
  @media (min-width: 64rem) {
    html {
      scroll-padding-top: 0;
      scroll-padding-bottom: 0;
    }
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
Le pagine con tabelle larghe possono arrivare a `max-w-5xl` (1024 px), dichiarandolo nel loro
design: il registro arrivi, l'elenco per la struttura e il foglio per i maestri (TALITA-6) lo fanno
con `Shell.wide`, che il layout legge (`shell.getWide()`). Le altre pagine restano a `max-w-3xl`.

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
| Esci | `log-out` |
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
| Permesso negato | `lock` |
| Attesa dentro un bottone | `loader-circle` |
| Controlla la posta, la mail del link | `mail` |
| Conferma dell'accesso | `log-in` |
| Il link non vale più | `link-2-off` |
| Maestri, nella pagina Convivenza | `user-round` |
| Copia il link | `copy` |
| Condividi | `share-2` |
| Stampa la pagina | `printer` |
| Anteprima della pagina | `eye` |
| Segnalazioni su allergie, intolleranze e farmaci | `heart-pulse` |
| Sezione che si apre | `chevron-down` |
| Affidamento accettato | `file-check` |
| Testo accettato | `file-text` |
| Ritira l'iscrizione | `circle-x` |
| Rimetti l'iscrizione | `rotate-ccw` |
| Togli le segnalazioni | `trash-2` |
| Segna come ricevuta, «Presente» | `check` |
| Si apre in una nuova scheda | `external-link` |
| Iscrizioni chiuse | `calendar-x` |
| Chiama il genitore | `phone` |
| Manda il promemoria | `send` |
| Riprendi i promemoria | `bell` |
| Non mandarmi più promemoria | `bell-off` |
| La pagina per smettere i promemoria | `mail-x` |
| Elenco per la struttura | `list` |
| Registro arrivi | `clipboard-list` |
| Torna agli arrivi | `chevron-left` |
| Conservata fino al…, quello che è stato cancellato (stato vuoto neutro) | `archive` |
| Per quanto tempo teniamo i dati | `shield-check` |

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
- Una pausa non è un movimento: `animate-touch-pause` (700 ms, `pointer-events: none` e niente
  altro) vale anche con «riduci movimento» e non usa `motion-safe:`. Si mette sul blocco che contiene il
  bottone di una risposta che lo sostituisce con un altro nello stesso punto (4.1, «Togli»), mai su
  una pagina intera.

## 3. Guscio dell'app

### 3.1 Struttura

Telefono (sotto 1024 px):

```
┌──────────────────────────────┐  ← barra di caricamento (4 px, solo durante le richieste)
│ [T] Talità          (EC) Profilo │  intestazione, 56 px, resta in alto
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

**Il fuoco non resta sotto le barre.** Sul telefono l'intestazione sta in alto (56 px) e la navigazione in basso
(64 px), entrambe fisse: quando lo scorrimento porta in vista un elemento (il fuoco che va a un avviso o a un campo, un'ancora), il browser lo mette al bordo dello schermo e le barre lo coprirebbero (WCAG 2.2, 2.4.11). Per questo `html` ha
`scroll-padding-top` e `scroll-padding-bottom` alti come le barre, con la safe area e mezzo rem di respiro (2.4); da
`lg`, dove le barre stanno nella colonna a sinistra, sono a zero. Vale per ogni pagina, non solo per «Maestri».

Un solo HTML per i due layout: cambiano solo le classi `lg:`. Chi non è dentro (pagine di accesso e di errore) ha un guscio senza navigazione né profilo, vedi 3.5.

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
    <!-- la pillola «Profilo», vedi 3.6 -->
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

**Voci** (TALITA-2 le quattro del maestro, TALITA-3 le voci del genitore):

| Ordine | Etichetta | URL | Icona | Chi la vede | Cosa ci sarà |
|---|---|---|---|---|---|
| 1 | Home | `/` | `house` | Maestro e genitore | pagina iniziale |
| 2 | Convivenza | `/convivenza` | `calendar-days` | Solo maestro | la convivenza (stato, date, luogo, quote, maestri affidatari, informazioni e link per i genitori) e, sotto, «Maestri» |
| 3 | Iscrizioni | `/iscrizioni` | `users-round` | Maestro e genitore | per il maestro elenco e inserimento dal modulo cartaceo; per il genitore le iscrizioni dei suoi figli |
| 4 | Arrivi | `/arrivi` | `clipboard-check` | Solo maestro | la lista del giorno con «Segna presente», la scheda del bambino (giorni, recapito, segnalazioni, quota e pagamenti, manleva) e le tre stampe: registro arrivi, elenco per la struttura, foglio per i maestri |

Il maestro ha le quattro voci. Il **genitore ne ha due**, Home e Iscrizioni: ognuna occupa metà barra
(le `<li>` hanno già `flex-1`), con etichette e icone di sempre, e non scatta mai la regola «più di
cinque voci → Altro». Chi è maestro e genitore (un solo account) vede la navigazione del maestro.
«Maestri» non è una voce: ci si arriva dalla Convivenza (la sua pagina ha la riga «Maestri» in fondo, in ogni stato, anche vuota, e nessun'altra voce di navigazione) e ha corrente «Convivenza». Il profilo,
«Accetta l’invito» e le pagine di errore non hanno nessuna voce corrente. Le voci arrivano al template
filtrate per ruolo, con il ruolo letto dal database a ogni richiesta: dopo un invito accettato, o dopo
che un maestro viene tolto, il tocco successivo mostra già la navigazione giusta. Gli indirizzi dei soli
maestri (`/convivenza`, `/arrivi`, `/maestri` e quelli sotto) rispondono 403 a un genitore (4.11).

Perché quattro per il maestro: restano sotto il limite di cinque, e con quattro ogni voce è larga almeno 78 px anche su uno schermo da 320 px (74 bastano a "Convivenza" a 13 px), quindi si legge intera anche con il testo ingrandito. Il profilo con "Esci" sta in alto a destra nell'intestazione, come nelle app di posta (3.6).

Regole:

- Al massimo cinque voci sul telefono. Se le voci visibili a una persona diventano più di cinque,
  le prime quattro restano e la quinta diventa "Altro" (`ellipsis`), che apre una pagina con le
  altre come lista. Su desktop si possono mostrare tutte.
- Solo sezioni del perimetro di Talità: la convivenza, le iscrizioni, gli arrivi.
- Le voci arrivano al template come lista costruita a ogni richiesta (etichetta, URL, icona,
  corrente sì/no), non scritte nel template e filtrate per ruolo (maestro o genitore).
- La voce corrente è quella della sezione in cui si trova la pagina (anche nelle pagine interne:
  la pagina di una singola iscrizione ha corrente "Iscrizioni"). Nelle pagine di errore nessuna voce è
  corrente.
- URL in italiano e senza accenti (li vede chi usa Talità, anche nei link condivisi).
- «Arrivi» è la voce corrente in `/arrivi`, nelle schede dei bambini (`/arrivi/{id}`) e nelle tre stampe (`/arrivi/registro`, `/arrivi/elenco`, `/arrivi/foglio`). Il selettore dei
  giorni di Arrivi (4.20) è una seconda `nav`, con la sua etichetta: la navigazione principale è la `nav` che sta sotto il corpo della pagina.

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

### 3.5 Guscio senza accesso

Le pagine che chiunque può aprire (accesso, «Controlla la posta», conferma del link, «Il link non
vale più», 404 e 500 per chi non è dentro) hanno lo stesso guscio senza navigazione e senza profilo:

| | Senza accesso | Con accesso |
|---|---|---|
| Intestazione | solo il marchio «Talità», **non** un link (porterebbe a `/accedi`, cioè qui) | marchio (link alla home) e, a destra, «Profilo» |
| Navigazione | nessuna | quella della persona |
| Contenuto | colonna centrata, larga al massimo 448 px (`mx-auto w-full max-w-md`) | come sopra, 768 px |
| Messaggi a comparsa | in basso, senza la navigazione sotto (`bottom-no-nav`) | sopra la navigazione (`bottom-above-nav`) |

Classi dell'intestazione senza accesso: `sticky top-0 z-40 flex min-h-14 items-center gap-2 border-b border-line bg-surface pt-safe px-gutter short:static lg:px-10`, a tutta larghezza anche su desktop (non c'è la colonna a sinistra); il marchio è un `<p class="inline-flex min-h-11 items-center gap-2 text-lg font-bold tracking-tight text-ink">`. `main`: `block px-gutter pt-6 pb-no-nav transition-opacity duration-150 motion-safe:animate-enter nav-busy:opacity-60 nav-busy:delay-300 lg:px-10 lg:pt-16 lg:pb-16`, senza `lg:ml-64`. Due utility nel CSS: `pb-no-nav` (`calc(2.5rem + env(safe-area-inset-bottom))`) e `bottom-no-nav` (`calc(env(safe-area-inset-bottom) + 0.75rem)`).

**Guscio pubblico** (`layout/public.jte`, TALITA-4): la pagina informativa della convivenza (4.14), che chiunque apre da un link, ha lo stesso guscio senza accesso, con tre differenze:

| | Accedi (TALITA-3) | Pagina informativa |
|---|---|---|
| Larghezza della colonna | `max-w-md` (448 px) | `max-w-2xl` (672 px): le schede con date e quote respirano su desktop |
| Script | htmx e `app.js` | **nessuno**: né `hx-boost`, né `htmx-config`, né `#caricamento`, né `#messaggi`, né i `<template>` dei messaggi, né il codice CSRF (che farebbe nascere la sessione e un cookie) |
| Animazione d'ingresso | sì | no (nessun cambio di pagina da attenuare) |

Nell'`<head>` ha solo il necessario: `noindex, nofollow`, e se la pagina si condivide `description` e `og:title`, `og:description`, `og:type` (`website`), `og:site_name`, `og:locale`: mai `og:image`, `og:url`, `twitter:*` né indirizzi esterni. La prima visita pesa circa 85 KB (HTML, CSS, il font latino, l'icona), sotto i 250 KB. `main` ha `pb-no-nav` e il link «Vai al contenuto» funziona anche senza JavaScript. In stampa intestazione e link «Vai al contenuto» non si stampano (4.12).

Le pagine di errore per chi non ha accesso (404 e 500 sono pubbliche) hanno i testi di 4.11, ma «Vai alla home» diventa «Vai all’accesso» (link a `/accedi`). «Riprova» della 500 resta, solo per le letture.

### 3.6 Profilo nell'intestazione

Chi è dentro ha, a destra dell'intestazione, la pillola «Profilo»: un cerchio con le iniziali e la
parola. La parola c'è sempre, perché un cerchio da solo non dice che si tocca; sotto i 320 px resta
il cerchio e il nome «Profilo» lo legge il lettore di schermo. Nei 256 px dell'intestazione del desktop
restano pochi pixel liberi fra marchio e pillola: non si allarga nessuno dei due. Da qui, due tocchi
arrivano a «Esci» da ogni pagina (la pillola, poi il bottone).

Iniziali: la prima lettera dell'indirizzo con cui si è entrati, in maiuscolo (il maestro con nome: le
due lettere di nome e cognome). Sulla pagina del profilo la pillola è la voce corrente
(`aria-current="page"`), come le voci della navigazione.

```html
<a href="/profilo" aria-current="page" class="group ml-auto inline-flex min-h-11 items-center gap-2 rounded-full border border-line-strong bg-surface py-1 pr-3 pl-1 text-sm font-semibold text-ink transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted aria-[current=page]:border-brand aria-[current=page]:bg-brand-soft aria-[current=page]:text-brand max-[20rem]:pr-1">
  <span aria-hidden="true" class="grid size-8 place-items-center rounded-full bg-brand-soft text-sm font-semibold text-brand group-aria-[current=page]:bg-surface">N</span>
  <span class="max-[20rem]:sr-only">Profilo</span>
</a>
```

La pagina «Profilo» (`/profilo`) è una scheda (4.4) «Il tuo accesso» con l'indirizzo email
(`wrap-anywhere`), il bottone secondario «Esci» con `log-out` (un `POST /esci` con il codice CSRF, senza
dialogo: si rientra in un minuto con un nuovo link) e la nota «Se usi un telefono o un computer di altri,
esci quando hai finito.». Dopo «Esci» si arriva a `/accedi` con l'avviso di conferma «Accesso chiuso. Per
rientrare, chiedi un nuovo link.», una volta sola. Le pagine di chi è dentro hanno `Cache-Control: no-store`:
il tasto indietro non le mostra più dopo l'uscita.

**«I tuoi dati»** (TALITA-7, ogni persona dentro): una scheda (4.4) dopo «Il tuo accesso» (e dopo «Il tuo nome» per il maestro) con un'introduzione, il collegamento di testo «Per quanto tempo teniamo i dati» (icona `shield-check`, verso `/informativa#per-quanto-tempo` nella stessa scheda: `hx-boost="false"`, perché la pagina pubblica ha un altro guscio), il sottotitolo (`h3`, `text-base font-semibold`) «I tuoi figli» con una riga per figlio (4.3, «Riga di un figlio nel Profilo») e il sottotitolo «Il tuo account». Cancellare non è mai l'azione principale della pagina: il bottone è il **pericolo in contorno** (4.1), largo sul telefono, e apre il dialogo con gli elenchi (4.8); il pericolo pieno sta solo dentro il dialogo. Quando c'è qualcosa da fare prima, la spiegazione prende il posto del bottone, nello stesso punto (`text-sm text-ink-muted`, nessun colore di avviso: non è un errore) con un bottone di testo che porta dove si fa («Vai alle iscrizioni», «Vai a Maestri», «Togliti dagli affidatari»); per l'account, un elenco puntato sotto «Per cancellare il tuo account, prima:». Non c'è una voce di navigazione nuova: il Profilo è la pillola dell'intestazione. Una richiesta che non si può più fare (una pagina rimasta aperta) è il profilo com'è adesso, con 409 e l'avviso di attenzione «Non è stato cancellato niente. Le cose da fare prima sono scritte qui sotto.».

**«Il tuo nome»** (solo il maestro): una seconda scheda (4.4) sotto «Il tuo accesso», con i due campi **Nome**
e **Cognome** (come in «Accetta l’invito»: stesse regole, stessi errori, `novalidate`, nessun `required`,
`maxlength` né `pattern`) compilati con il nome di adesso, e il bottone primario «Salva il nome» (in attesa:
«Salvataggio…»). Salvato, si torna al profilo con la conferma «Nome salvato.» (`role="status"`, in cima, una volta
sola) e le iniziali nell'intestazione cambiano al tocco dopo. Con errori: la stessa pagina, compilata, con l'errore
sotto il campo e il fuoco sul primo sbagliato; con due errori, in cima «Controlla i 2 campi segnati in rosso.».
Con htmx l'indirizzo resta `/profilo` (il form rimandato non lo cambia). Il genitore non ha la scheda: un genitore
non ha un nome da correggere.

### 3.7 Home e «Iscrizioni» per ruolo, e «Accetta l’invito»

**Home** (`/`). Il titolo è sempre «Ti diamo il benvenuto». Il maestro ha il testo di sempre; il genitore ha
questo, e nell'elenco solo le iscrizioni:

| Elemento | Maestro | Genitore |
|---|---|---|
| Introduzione | Talità raccoglie le iscrizioni alla convivenza dei bambini e aiuta i maestri a organizzarla. | Qui iscrivi i tuoi figli alla convivenza e controlli le loro iscrizioni. |
| Avviso informativo | **Talità è in costruzione.** Le sezioni arriveranno una alla volta. | Senza iscrizioni: **Non hai ancora iscritto nessun bambino. Per farlo, apri il link che ti hanno mandato i maestri.** Con iscrizioni: la sezione «Le tue iscrizioni» (sotto) |
| Titolo della sezione (h2) | Sezioni | Le tue iscrizioni (se ci sono) · Cosa trovi qui |
| Righe della lista | Convivenza, Iscrizioni, Arrivi | Iscrizioni · Le iscrizioni dei tuoi figli. |

L'avviso «in costruzione» del maestro è provvisorio e sparisce quando le sezioni esistono; quello del genitore è sparito con TALITA-5. «Le tue iscrizioni» ha una riga di lista per convivenza in cui il genitore ha iscritto qualcuno (`listLink`, icona `calendar-days`, titolo, **`2 bambini · 95 €`** o **`Nessuna iscrizione attiva`**, le etichette dello stato e la freccia verso `/iscrizioni`). Chi non ha iscrizioni non vede nessuna convivenza: il codice dei maestri è la chiave.

**Avviso dell'invito**, sulla home di chi ha un invito valido e non l'ha accettato, **sopra** l'avviso «in
costruzione», finché l'invito non viene accettato, ritirato o scade. Avviso informativo (`role="status"`) con
un bottone di testo sotto, dentro lo stesso riquadro: «Ti hanno invitato come maestro. L’invito vale fino al
29 ottobre 2026.» (la data è quella di scadenza, nel formato della guida) e il bottone di testo «Accetta
l’invito» (link a `/invito`). Se l'invito non vale più mentre la pagina era aperta, la persona arriva alla home
con un avviso di attenzione: «L’invito non vale più. Se serve, chiedi a un maestro di mandartene un altro.»

**Iscrizioni** (`/iscrizioni`, TALITA-5): per il genitore le schede dei suoi figli, per il maestro l'elenco della convivenza (nessuna pagina «in preparazione» resta, a parte «Arrivi»). Senza iscrizioni il genitore vede lo stato vuoto «Nessuna iscrizione» (4.9).

**Accetta l’invito** (`/invito`, scheda del browser `Accetta l’invito · Talità`): guscio con la navigazione del
genitore e nessuna voce corrente, colonna `max-w-md`. Titolo e introduzione («Ti hanno invitato a organizzare la
convivenza come maestro. Il tuo nome compare nell’elenco dei maestri e nei documenti della convivenza.»), una
scheda con due campi, **Nome** (`autocomplete="given-name" autocapitalize="words"`) e **Cognome**
(`autocomplete="family-name" autocapitalize="words"`), il bottone primario «Accetta l’invito» (in attesa:
«Invio…») e il secondario «Più tardi» (link alla home), e sotto la scheda «Se non ti aspettavi questo invito,
tocca «Più tardi»: non cambia niente.» Nessun `required`, `maxlength` né `pattern`: accenti e apostrofi sono
ammessi e gli errori sono quelli del server (`novalidate`). Errori: «Scrivi il nome.», «Scrivi il cognome.»,
«Usa al massimo 100 caratteri.»; con due errori, in cima, un avviso di errore «Controlla i 2 campi segnati in
rosso.» e il fuoco sul primo campo. Dopo l'accettazione: la home, con la navigazione del maestro e l'avviso di
conferma «Invito accettato. Ora sei tra i maestri.». «Più tardi» e un `/invito` aperto da chi non ha un invito
valido portano alla home, il secondo senza messaggio.

**Profilo del maestro**: nella scheda «Il tuo accesso» c'è anche la riga «Ruolo → Maestro». Il genitore non ha
la riga: ogni account è un genitore, non serve dirlo.

### 3.8 «Maestri»

`/maestri` (scheda `Maestri · Talità`) è dei soli maestri (403 per gli altri, 4.11). Non è una voce della
navigazione: sta sotto la Convivenza, che resta la voce corrente. Ci si arriva dalla pagina Convivenza, che
finché non ha altro sotto il testo «in preparazione» ha una sezione «Maestri» (h2) con una riga (4.3, con
freccia): `user-round` in un riquadro, «Maestri» e «Chi organizza la convivenza e chi puoi invitare.».

**Struttura**, nell'ordine: titolo «Maestri» e introduzione («Chi organizza la convivenza insieme a te. I maestri
vedono tutta la convivenza, i bambini e le segnalazioni.»); la scheda **Invita un maestro** (h2) con il campo
«Indirizzo email» (`type="email"`, senza correzione né controllo ortografico, aiuto «Gli arriverà una mail con le
istruzioni. L’invito vale 7 giorni. Chi accetta potrà vedere tutta la convivenza, bambini compresi.») e il bottone
primario «Manda l’invito» (in attesa: «Invio…»); la lista **I maestri** (h2); la lista **Inviti in attesa** (h2).
Il campo viene prima delle liste: è quello che il maestro viene a fare, e con molti inviti non finisce sotto.

**I maestri**: righe non cliccabili (4.3) con le iniziali in un cerchio, nome e cognome (mai l'indirizzo: non serve
a chi guarda e la pagina si può mostrare a un collega), l'etichetta «Sei tu» sulla propria riga e il bottone di testo
«Togli». Con un maestro solo il bottone non c'è, e sotto il nome sta la spiegazione «Serve almeno un maestro: per
toglierti, invita prima un altro maestro.». Ordine alfabetico per cognome, poi nome.

**Inviti in attesa**: righe non cliccabili con `mail` in un riquadro, l'indirizzo (`wrap-anywhere`) e la scadenza,
«Scade il 29 ottobre 2026» oppure, se è passata, l'etichetta di attenzione «Scaduto» e «Scadeva il 15 ottobre
2026»; due bottoni di testo, «Manda di nuovo» (stessa mail, nuova scadenza: un invito solo per indirizzo) e «Ritira»
(dialogo, 4.8). Senza inviti: «Nessun invito in attesa.» in testo semplice, non uno stato vuoto (4.9): la lista è
secondaria e il campo per invitare è lì sopra.

**Avvisi** (4.6, `live`, in cima al blocco, con il fuoco): conferma «Invito mandato.» (anche per «Manda di nuovo»),
conferma «Invito ritirato.», conferma «{Nome Cognome} non è più tra i maestri.»; errore «Non siamo riusciti a mandare
la mail. Riprova tra qualche minuto.» (la mail non è partita e l'invito non è stato salvato); attenzione per il limite
del giorno, con il testo della pagina di accesso («Oggi Talità ha già mandato tante mail e non può mandarne altre.
Riprova domani. Se è urgente, avvisa chi organizza la convivenza.»); attenzione «Serve almeno un maestro. Per
togliere questa persona, invita prima un altro maestro.» (quando la pagina era aperta con due maestri e uno se n'è
andato nel frattempo). Errori del campo: quello dell'accesso per un indirizzo malformato, «È già tra i maestri.» per un
maestro; il campo resta compilato come era scritto e prende il fuoco. Chi toglie sé stesso arriva alla home, da
genitore, con «Non sei più tra i maestri.».

**Dialoghi** (4.8, uno per riga, dentro il blocco): primari, perché si rimedia (si invita di nuovo).

| Dialogo | Titolo | Testo | Bottone |
|---|---|---|---|
| Togli un altro | Togliere {Nome Cognome} dai maestri? | {Nome Cognome} non potrà più gestire la convivenza né vedere i bambini e le segnalazioni. Il suo nome viene cancellato dall’elenco dei maestri. Potrà ancora entrare in Talità come genitore. | Togli dai maestri |
| Togli sé stesso | Toglierti dai maestri? | Non potrai più gestire la convivenza né vedere i bambini e le segnalazioni. Il tuo nome viene cancellato dall’elenco dei maestri. Un altro maestro potrà invitarti di nuovo. | Toglimi dai maestri |
| Ritira | Ritirare l’invito? | L’invito a {indirizzo} non varrà più. La persona potrà entrare in Talità, ma non come maestro. | Ritira l’invito |

**Niente nell'indirizzo**: nome, id e indirizzo email di una persona non stanno mai in un URL (finirebbero nei log e
nella cronologia): i form li mandano nel corpo di un `POST`. Ogni azione risponde con il blocco `#maestri-contenuto`
intero (avviso, scheda, liste e dialoghi), che htmx mette al posto del vecchio senza muovere la pagina (5). I
dialoghi sono nel blocco, quindi spariscono con la risposta.

**Senza JavaScript** il bottone «Togli» o «Ritira» manda il suo form a una pagina di conferma con lo stesso titolo,
lo stesso testo e lo stesso bottone del dialogo, e «Annulla» come link a `/maestri` (guscio con la Convivenza
corrente, colonna `max-w-md`); il bottone fa l'azione e la pagina «Maestri» torna con l'avviso. Un form senza
JavaScript che torna con un errore resta all'indirizzo della sua azione: chi lo apre di nuovo con un `GET`
(un preferito, il tasto indietro) arriva a `/maestri`.

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
| Pericolo in contorno | il bottone di una pagina che **apre un dialogo** che cancella dati: non cancella niente da solo e non è mai l'azione principale (TALITA-7). Sul telefono `w-full sm:w-auto`, con l'icona `trash-2` `size-5` | base + `border border-danger bg-surface text-danger hover:bg-danger-soft active:bg-danger-soft` (danger su bianco 6,57:1, su danger-soft 5,79:1) |
| Testo | azioni minori dentro schede e liste ("Modifica") | `inline-flex min-h-11 items-center gap-2 rounded-xl px-3 text-base font-semibold text-brand transition-colors duration-150 hover:bg-brand-soft active:bg-brand-soft` |

- **Azione rapida** («Segna presente», nella riga di una lista: 4.3): più alta e stretta, perché sta a destra del nome: `inline-flex min-h-14 w-24 shrink-0 items-center justify-center rounded-xl bg-brand px-2 text-center text-sm leading-tight font-semibold text-white … sm:w-auto sm:min-w-36 sm:px-5 sm:text-base sm:whitespace-nowrap`. Durante la richiesta dice «Segno…» e il suo nome accessibile continua con il nome del bambino e il giorno (`sr-only`: «Segna presente Anna Rossi, sabato 18 settembre»). **Il bottone che arriva al suo posto non prende i tocchi per 700 ms** (vedi «Togli» qui sotto e 2.9).
- **«Togli»** (di una presenza o di un pagamento): secondario più piccolo, `min-h-11 px-4 text-sm` (`sm:text-base`), senza riempimento. **Chi tocca due volte non annulla il primo tocco**, né da «Segna presente» a «Togli» né da «Togli» a «Segna presente»: il nuovo bottone sta dove stava il vecchio, e il secondo tocco ci cadrebbe sopra (il QA ha provato 80, 150, 250 e 400 ms). Perciò il bottone che arriva nella risposta a un tocco, nella riga della lista e nei giorni della scheda, non prende i tocchi per 700 ms (`animate-touch-pause` sulla riga o sul blocco del giorno, per il «pointer-events» che si eredita: sulla riga copre anche il nome, che col mouse può stare sotto il punto del primo clic quando il bottone nuovo è più stretto): il secondo tocco cade nel vuoto, un tocco solo dopo la pausa fa quello che faceva. Il fuoco resta sul bottone nuovo e il suo nome non cambia; la tastiera non è toccata. Per una presenza non ha dialogo (si rimedia con un tocco); per un pagamento il dialogo di 4.8 con il bottone di pericolo.
- **«Chiama»**: un collegamento `tel:` con l'aspetto di un secondario (`border border-line-strong min-h-12 min-w-34 px-5`), icona `phone`, `hx-boost="false"` (il browser apre il telefono, non una pagina) e il numero nel nome accessibile (`sr-only`: «Chiama 333 123 4567»). Il numero da chiamare ha solo le cifre e un «+» iniziale, se il numero scritto comincia con uno.
- **«Stampa»**: bottone primario con l'icona `printer` (`data-azione="stampa"`), che `app.js` mostra (è `hidden` nella pagina) e che chiama `window.print()`: senza JavaScript non c'è, e la riga di aiuto sotto dice che cosa fare. Sta in una barra che non si stampa (`print:hidden`).
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
- **Data di nascita** (tre campi, regola di Koinè approvata da Matteo il 19/10/2026; in Talità la data di nascita del bambino è un dato confermato in TALITA-5, con il suo scopo in `docs/02-modello-dati.md`): Giorno,
  Mese e Anno dentro un `fieldset` con `legend`, tutti con
  `inputmode="numeric"` e larghi `w-18`, `w-18`, `w-26`; per una data di nascita è molto più facile
  scrivere 4 4 2016 che scorrere un calendario di dieci anni. Le date degli eventi (convivenze)
  usano invece `type="date"`.
- Scelte: `fieldset` + `legend`; ogni opzione in `flex min-h-12 items-center gap-3`, con
  `size-6 accent-brand` sul controllo. Sotto le cinque opzioni radio; oltre, una `select` con le
  stesse classi del campo di testo (`components/selectField.jte`: `appearance-none`, `pr-10`, e la freccia `chevron-down` in un'icona `pointer-events-none` a destra, solo per gli occhi; l'etichetta è sempre visibile). Se il form è un GET (la scelta del giorno dell'elenco per la struttura), non porta niente di personale nell'indirizzo.
- Campo di ricerca: `type="search"`, icona `search` a sinistra (`absolute left-3.5 top-1/2
  size-5 -translate-y-1/2 text-ink-muted`) e campo con `pl-11`; etichetta `sr-only` "Cerca una
  persona".
- Pila dei campi: `space-y-5`. Bottoni del form in fondo, con `mt-6`.
- **Data** (le date degli eventi): lo stesso campo di testo con `type="date"`, senza `min` né `max` (gli errori sono i nostri, e un limite del browser coprirebbe l'errore). Il valore è `aaaa-mm-gg`.
- **Importo in euro** (`components/amountField.jte`): campo di testo con `inputmode="decimal"`, `autocomplete="off"`, il segno «€» dentro il campo a destra (solo per gli occhi, `aria-hidden`: l'etichetta dice cosa è) e `pr-12` perché il testo non lo copra. Si accettano `60`, `12,50`, `12.5`; si mostrano sempre come da 6.

```html
<div class="relative">
  <input id="quota-intera" name="fullFee" type="text" inputmode="decimal" autocomplete="off" aria-describedby="quota-intera-aiuto quota-intera-errore" aria-invalid="true"
         class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface pr-12 pl-4 text-base text-ink focus:border-brand aria-[invalid=true]:border-danger aria-[invalid=true]:bg-danger-soft">
  <span aria-hidden="true" class="pointer-events-none absolute inset-y-0 right-4 flex items-center text-ink-muted">€</span>
</div>
```

- **Area di testo** (`components/textAreaField.jte`): le stesse classi del campo, con `min-h-48` (9 righe) per un testo lungo e `min-h-36` (6 righe) per un elenco, `resize-y`, `py-3`. Gli a capo che la persona scrive si conservano.
- **Scelta multipla** (più caselle, dove le radio non servono: «Maestri affidatari»): un `fieldset` con `legend`, e le caselle in una lista con bordo, `min-h-14` per riga, `size-6 accent-brand`, il nome che va a capo (`wrap-anywhere`) e, se serve, un'etichetta («Sei tu»). L'errore sta sotto l'elenco, `aria-describedby` sul `fieldset` e `aria-invalid="true"` sulla **prima casella**, così `app.js` le dà il fuoco.

- **Radio e caselle con testo di aiuto** (TALITA-5, «Quando viene?», «Stato dell'iscrizione»): la riga intera è il bersaglio (`min-h-12`), il controllo è `size-6 accent-brand` e il testo di aiuto sta dentro l'etichetta, sotto il nome. Le scelte di una pagina non sono mai spuntate prima che la persona scelga, a parte quelle che il testo dice («Confermata»).

```html
<label class="flex min-h-12 items-start gap-3 py-2">
  <input type="radio" name="…" value="all_days" class="mt-0.5 size-6 shrink-0 accent-brand">
  <span class="min-w-0"><span class="block font-semibold">Tutti i giorni</span><span class="block text-sm text-ink-muted">Venerdì 17, sabato 18 e domenica 19 settembre 2027 · 60 €</span></span>
</label>
```

- **Scelta a segmenti** (2 o 3 tocchi affiancati, `grid-cols-2` con due; per ogni giorno «Non viene · Giornata · Solo pranzo»): una radio trasparente sopra a un riquadro. Le `select` sono scartate: le opzioni si vedrebbero solo dopo un tocco.

```html
<fieldset class="min-w-0"><legend class="mb-1.5 font-semibold">Venerdì 17</legend>
  <div class="grid grid-cols-3 gap-2">
    <label class="relative block">
      <input type="radio" name="…day.fri" value="full" class="peer absolute inset-0 size-full cursor-pointer opacity-0">
      <span class="flex min-h-12 items-center justify-center rounded-xl border border-line-strong bg-surface px-1.5 py-1 text-center text-sm font-medium transition-colors duration-150 peer-checked:border-brand peer-checked:bg-brand-soft peer-checked:font-semibold peer-checked:text-brand peer-checked:ring-1 peer-checked:ring-brand peer-focus-visible:outline-3 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-focus">Giornata</span>
    </label>
  </div>
</fieldset>
```

- **Giorni visibili con «Alcuni giorni»**, anche senza JavaScript: si nascondono solo con CSS (`:has()`), e dove manca (iOS prima della 15.4) restano sempre visibili.

```html
<fieldset class="group min-w-0">
  …radio «Tutti i giorni» e «Alcuni giorni» (value="days")…
  <div class="mt-1 ml-9 hidden rounded-2xl border border-line bg-canvas p-4 group-has-[input[value=days]:checked]:block not-supports-[selector(:has(*))]:block">…segmenti…</div>
</fieldset>
```

- **Casella con testo lungo e errore** (`registration/checkRow.jte`): l'errore sta sotto il testo, rientrato come lui (`ml-9`), non sotto la casella.

```html
<div><label class="flex min-h-12 items-start gap-3 py-2"><input type="checkbox" name="acceptCustody" value="true" aria-invalid="true" aria-describedby="accetto-errore" class="mt-0.5 size-6 shrink-0 accent-brand"><span class="min-w-0 font-semibold wrap-anywhere">Ho letto il testo dell’affidamento e lo accetto.</span></label>
<p id="accetto-errore" class="ml-9 flex items-start gap-1.5 text-sm font-medium text-danger"><svg class="mt-0.5 size-4 shrink-0" aria-hidden="true">…circle-alert…</svg>…</p></div>
```

- **Telefono**: `type="tel"` con `autocomplete="tel"` e `autocorrect="off"`, **senza** `inputmode="numeric"`: la tastiera numerica di iOS non ha il «+».
- **Appunti di sanità** (`registration/noteField.jte`): area di testo da due righe (`min-h-22`), che cresce, con l'esempio nell'aiuto sotto l'etichetta.

### 4.3 Liste

La lista è il componente principale di Talità: iscrizioni, maestri, arrivi.

```html
<ul class="divide-y divide-line overflow-hidden rounded-2xl border border-line bg-surface">
  <li>
    <a href="/iscrizioni/…" class="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors duration-150 hover:bg-surface-muted active:bg-surface-muted busy:bg-surface-muted">
      <span class="grid size-10 shrink-0 place-items-center rounded-full bg-brand-soft text-sm font-semibold text-brand">MB</span>
      <span class="min-w-0 flex-1">
        <span class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <span class="font-semibold wrap-anywhere">Marta Bianchi</span>
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
- I nomi non si tagliano mai con i puntini: vanno a capo, anche un nome in una sola parola di 100 lettere
  (`wrap-anywhere` sul nome; `wrap-break-word` lascia passare la parola sotto i bottoni o fuori dalla riga).
- L'etichetta di stato sta accanto al titolo (va a capo se non c'è spazio), non in colonna a
  destra: sul telefono ruberebbe spazio al testo.
- Righe non cliccabili (per esempio un elenco di sola lettura): stesso `li`, senza link e senza freccia.
- Righe con azioni (l'elenco dei maestri, gli inviti): il contenuto sta in un `div` (non un link) con
  `flex min-h-16 flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3`. Il blocco del testo ha **`min-w-0
  flex-[1_1_11rem]`**: parte da 11 rem e cresce, e se accanto non c'è posto per quella larghezza i bottoni vanno a
  capo sotto di lui, invece di stringerlo (con `flex-1` e basta un indirizzo da 34 caratteri restava largo 17 px, una
  lettera per riga). I bottoni di testo (4.1, `min-h-11`) hanno `-ml-3` sul telefono perché il loro testo si allinei a
  quello della riga e, da 640 px, `sm:ml-0 sm:shrink-0`: stanno a destra senza stringersi. Un `form` per bottone, con
  il suo codice CSRF; l'azione che ha conseguenze apre un dialogo (4.8).
- Indirizzi e nomi sono testo di chi li ha scritti e non hanno spazi dove andare a capo (un indirizzo, un nome di
  100 lettere): `wrap-anywhere` (`overflow-wrap: anywhere`) sul testo che li contiene, non `wrap-break-word`, che non
  restringe la larghezza minima del blocco dentro un flex. Vale in questa lista, nei dialoghi (4.8), negli avvisi (4.6)
  e nel titolo della pagina (`pageHead`).
- Lista vuota: si mostra lo stato vuoto (4.9), mai una lista senza righe.
- Liste lunghe: ricerca in cima (4.22); la paginazione o il "Mostra altre" li decide il ticket della
  schermata.

**Riga con azione rapida** (la lista di Arrivi, TALITA-6): il nome è un collegamento alla scheda a sinistra (`min-h-14`, nome in `font-semibold`, le etichette di stato sotto), il bottone a destra (azione rapida o «Togli», 4.1), niente iniziali né freccia. Quando l'azione è fatta la riga diventa verde (`bg-success-soft`, con l'etichetta «Presente» e la spunta: il significato non sta nel solo colore). Una nota sotto la riga (`basis-full`, `rounded-xl bg-brand-soft` o `bg-warning-soft` con icona) dice che cosa è successo quando non è quello che la persona si aspettava («Era già presente: l’ha segnata Paolo Verdi alle 9:12.»). La riga **non cambia posto dopo un tocco**: l'ordine si rifà al caricamento seguente. Un bambino che quel giorno non si può segnare (non è atteso, la convivenza non è iniziata, il giorno è dopo oggi) ha una riga di sola lettura con la freccia, che porta alla scheda. I filetti fra le righe si disegnano fra quelle che si vedono (`[&>li:not([hidden])~li:not([hidden])]:border-t`, non `divide-y`): la ricerca nasconde le righe, e un filetto sotto l'ultima rimasta raddoppierebbe il bordo della scheda.

```html
<li id="riga-12" data-filtro-riga class="flex flex-wrap items-center gap-x-3 py-2 pr-3 pl-4">
  <a href="/arrivi/12?giorno=2027-09-18" class="flex min-h-14 min-w-0 flex-1 flex-col justify-center rounded-lg py-1.5">
    <span class="font-semibold wrap-anywhere" data-filtro-testo>Anna Rossi</span>
    <span class="mt-1 flex flex-wrap gap-x-1.5 gap-y-1">…etichette…</span>
  </a>
  <form method="post" action="/arrivi/12/presenze" hx-post="/arrivi/12/presenze" hx-target="closest li" hx-swap="outerHTML" hx-disabled-elt="find button">
    …<button type="submit" class="…azione rapida…">Segna presente<span class="sr-only">Segna presente Anna Rossi, sabato 18 settembre</span></button>
  </form>
</li>
```

**Riga di un figlio nel Profilo** (TALITA-7): `<li class="border-t border-line py-4 first:border-t-0 first:pt-2">`; il cerchio con le iniziali e il nome e cognome (che vanno a capo, mai tagliati: `wrap-anywhere`) sulla stessa riga (`flex items-center gap-3`); sotto, a `mt-3`, il bottone di pericolo in contorno (4.1) o la spiegazione. Il bottone dice il nome proprio («Cancella i dati di Luca»), o nome e cognome se due figli dell'account hanno lo stesso nome proprio; il suo `aria-label` ha sempre nome e cognome e comincia con le parole che si vedono. La riga non mostra altri dati (né data di nascita né telefono).

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
- **Righe «etichetta … valore»** (le quote, la scheda «Quanto costa»): una riga per voce, anche sul telefono, con il valore a destra.

```html
<dl class="mt-3 divide-y divide-line">
  <div class="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 py-2.5 first:pt-0 last:pb-0">
    <dt class="text-ink-muted">Quota intera</dt>
    <dd class="text-right font-semibold">60 €</dd>
  </div>
</dl>
```

- **Riga con un valore lungo** («Quando», «Giornata: sabato 18. Solo pranzo: domenica 19.»): il valore va a capo sotto l'etichetta e sta a sinistra.

```html
<div class="flex flex-wrap items-baseline justify-between gap-x-4 py-2.5"><dt class="text-ink-muted">Quando</dt><dd class="basis-full text-left font-semibold">Giornata: sabato 18. Solo pranzo: domenica 19.</dd></div>
```

- **Riga di conservazione** (TALITA-7), sotto «Affidamento accettato il …» nella scheda di un'iscrizione di una convivenza finita: `mt-2 flex items-start gap-2 text-sm text-ink-muted` con l'icona `archive` `size-4 shrink-0`, e il testo «Conservata fino al 20 settembre 2028 come prova dell’affidamento», o «… come registro della convivenza» se l’iscrizione è ritirata, come nei dialoghi del profilo.
- **Scheda «Conservazione dei dati»** (TALITA-7, dettaglio della convivenza): titolo, una frase (`text-ink-muted`) e righe «etichetta · valore · spiegazione» in un `dl` (`space-y-3`; la riga `sm:grid sm:grid-cols-[11rem_1fr] sm:gap-x-6`; `<dt class="text-sm text-ink-muted">`; nel `<dd>` un `<p class="font-medium">` con la data e un `<p class="text-sm text-ink-muted">` con il motivo). È un'informazione, non un'azione: nessun bottone e nessun form.
- **Riquadro neutro con icona** (TALITA-7, «Segnalazioni cancellate il …»): `grid size-10 shrink-0 place-items-center rounded-xl bg-surface-muted text-ink-muted` con l'icona `trash-2` `size-6`, e accanto due frasi (la prima in `font-semibold`, la seconda `text-sm text-ink-muted`). Prende il posto del bottone «Mostra le segnalazioni», ed è uguale per ogni iscrizione.
- **Il titolo della scheda e il suo «Modifica»** stanno sulla stessa riga (`flex items-center justify-between gap-3`), con il bottone di testo e l'icona `pencil`; il bottone ha un `aria-label` che dice cosa si modifica («Modifica le quote»), perché sulla pagina ce ne sono quattro.

### 4.5 Etichette di stato

`inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold whitespace-nowrap`
più una variante:

| Variante | Classi | Esempi |
|---|---|---|
| Neutra | `bg-surface-muted text-ink-muted` | Bozza, Iscrizioni chiuse, Finita, Sei tu |
| Informativa | `bg-brand-soft text-brand` | Iscrizioni aperte, In corso |
| Positiva | `bg-success-soft text-success` | Confermata, Pagata |
| Attenzione | `bg-warning-soft text-warning` | In forse, Da pagare, Da completare |
| Negativa | `bg-danger-soft text-danger` | Annullata |

Una parola o due, con l'iniziale maiuscola. L'etichetta ripete in parole quello che il colore
dice: il colore da solo non basta.

Della convivenza (`components/badge.jte`, una parola di variante: `neutral`, `info`, `positive`, `warning`, `danger`): **Bozza** neutra, **Iscrizioni aperte** informativa, **Iscrizioni chiuse** neutra, e **Finita** neutra che si aggiunge accanto alla prima quando l'ultimo giorno è passato («finita» non è uno stato: è una data). **Sei tu** neutra accanto al proprio nome, **Da completare** di attenzione accanto a un dato che serve per aprire le iscrizioni.

Delle iscrizioni (TALITA-5): **Confermata** positiva, **In forse** di attenzione, **Ritirata** neutra; **Accettata online** e **Cartacea ricevuta** positive, **Da ricevere** e **Possibile doppione** di attenzione; **Sei tu** neutra (anche nelle letture delle segnalazioni).

### 4.6 Avvisi nella pagina

Un messaggio che resta nella pagina finché la situazione non cambia.

```html
<div class="flex items-start gap-3 rounded-xl bg-brand-soft p-4">
  <svg class="size-6 shrink-0 text-brand" aria-hidden="true">…info…</svg>
  <p class="min-w-0 wrap-anywhere">Le iscrizioni chiudono il 30 novembre 2026.</p>
</div>
```

| Variante | Sfondo | Icona |
|---|---|---|
| Informazione | `bg-brand-soft` | `info`, `text-brand` |
| Conferma | `bg-success-soft` | `circle-check`, `text-success` |
| Attenzione | `bg-warning-soft` | `triangle-alert`, `text-warning` |
| Errore | `bg-danger-soft` | `circle-alert`, `text-danger` |

Un avviso di attenzione può portare **un elenco di collegamenti** (le cose da fare prima di aprire le iscrizioni: ognuna porta al campo da completare, con il fuoco sul campo, `min-h-11` per il tocco):

```html
<div role="alert" tabindex="-1" data-fuoco class="flex items-start gap-3 rounded-xl bg-warning-soft p-4">
  <svg class="size-6 shrink-0 text-warning" aria-hidden="true">…triangle-alert…</svg>
  <div class="min-w-0">
    <p class="font-semibold">Prima di aprire le iscrizioni:</p>
    <ul class="mt-1 list-disc pl-5 marker:text-warning">
      <li><a href="/convivenza/12/date#luogo" class="inline-flex min-h-11 items-center font-semibold text-brand underline underline-offset-2">Scrivi il luogo</a></li>
    </ul>
  </div>
</div>
```

Testo in `text-ink`. Se l'avviso compare dopo un'azione (per esempio un form rimandato con errori),
ha `role="alert"` (errore e attenzione); una conferma ha `role="status"`; un'informazione che c'è
sempre non ha ruolo. Gli avvisi che seguono un'azione hanno anche `tabindex="-1"` e `data-fuoco`: dopo
che la pagina è stata sostituita, il fuoco va al primo campo sbagliato, e se non c'è all'avviso (5). Nel
codice è `components/alert.jte` (`variant`, `live`).

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
- **Le conferme dopo un'azione sono avvisi nella pagina** (4.6, `circle-check`, `role="status"`), non
  messaggi a comparsa: arrivano dopo un redirect o dentro un pezzo sostituito, restano finché la persona
  non cambia pagina e ricevono il fuoco (`data-fuoco`), così il lettore di schermo le annuncia. I messaggi
  a comparsa restano per ciò che riguarda tutta la pagina: rete assente o lenta, errore del server, accesso
  negato.

### 4.8 Dialogo di conferma

Prima di un'azione con conseguenze (ritiro di un'iscrizione,
eliminazione). Non per le azioni normali: salvare non chiede conferma.

```html
<dialog id="conferma-ritiro" aria-labelledby="conferma-titolo" aria-describedby="conferma-testo"
        class="mx-0 mt-auto mb-0 w-full max-w-none rounded-t-2xl bg-surface px-gutter pt-6 pb-[calc(1.5rem+env(safe-area-inset-bottom))] text-ink shadow-xl backdrop:bg-ink/50 motion-safe:animate-sheet-in sm:m-auto sm:max-w-md sm:rounded-2xl sm:p-6">
  <h2 id="conferma-titolo" class="text-xl font-semibold tracking-tight wrap-anywhere">Ritirare l’iscrizione?</h2>
  <p id="conferma-testo" class="mt-2 text-ink-muted wrap-anywhere">L’iscrizione di Marta Bianchi alla convivenza di settembre 2027 risulterà ritirata. Resta nell’elenco, con lo stato «Ritirata».</p>
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
- Titolo e testo hanno `wrap-anywhere`: un indirizzo o un nome lungo, senza spazi, va a capo dentro il dialogo e non
  lo fa scorrere di lato (taglierebbe proprio quello che si chiede di confermare).
- Titolo = la domanda con il verbo. Testo = cosa succede, con numeri, nomi e data, e cosa resta.
  Bottone = lo stesso verbo del titolo. Mai "Sei sicuro?", mai "OK" o "Sì".
- Il bottone è primario se l'azione si può rimediare (ritiro: l'iscrizione resta nell'elenco), di pericolo se
  toglie qualcosa per sempre.
- Senza JavaScript il bottone che apre il dialogo è un link a una pagina di conferma con gli stessi
  testi.
- Realizzazione (TALITA-3): il bottone che apre il dialogo è un normale bottone di un form (`data-apri="<id del
  dialogo>"` su `components/button.jte`): senza JavaScript manda il form alla pagina di conferma, con JavaScript
  `app.js` ferma l'invio e fa `showModal()`. Il dialogo è `components/confirmDialog.jte`, dentro il blocco che
  l'azione sostituisce; il suo form è un `hx-post` verso lo stesso blocco. L'id del dialogo è `togli-0`,
  `ritira-0`…: mai un nome o un indirizzo.
- **Passi dello stato della convivenza** (aprire, chiudere, riaprire le iscrizioni, TALITA-4): il bottone è un **link** con `data-apri` verso la pagina di conferma (`/convivenza/12/apri`), che senza JavaScript è la pagina con gli stessi testi (`pages/confirmAction.jte`, «Annulla» torna alla convivenza). Il form del dialogo è un normale form della pagina, non un blocco che si sostituisce (`ConfirmModel.target` nullo): risponde 303 alla pagina con la conferma. `app.js` ascolta i `data-apri` nella fase di cattura, perché un link è anche un link che htmx fa navigare. Una bozza che non si può aprire non ha dialogo: il bottone manda il form e la pagina risponde con l'elenco di cosa manca (4.6).
- **Se la richiesta del dialogo fallisce** (rete assente, lenta, errore del server, accesso negato) i dialoghi
  aperti si chiudono prima che compaia il messaggio (4.7): un dialogo aperto sta sopra a tutto, anche ai messaggi,
  e il messaggio resterebbe nascosto sotto il suo sfondo. La pagina resta com'era e il messaggio si vede.
  Se invece il server risponde con il blocco (422, 429), il blocco sostituisce anche il dialogo.
- Dopo l'azione il fuoco va all'avviso del blocco (5); dopo «Annulla» o Esc, al bottone che aveva aperto il dialogo.

**Dialogo con elenchi** (`erasureDialog`, TALITA-7): un dialogo che cancella dati. Dentro il `<dialog>` di sopra si aggiungono `max-h-[92dvh] overflow-y-auto` (scorre nel suo pannello se è più alto dello schermo) e il testo è un blocco `<div id="…-testo" class="mt-3 space-y-4">`, con `aria-describedby` che punta al blocco. Le sezioni hanno il titolo (`<h3 class="text-base font-semibold">`) e un elenco breve (`<ul class="mt-1 list-disc space-y-2 pl-5 text-ink-muted marker:text-ink-muted">`); la riga piccola è `text-sm text-ink-muted` e la chiusura `font-semibold`. La regola: **un dialogo che cancella dati dice, in quest'ordine, cosa si cancella subito («Cancelliamo subito»), cosa resta, con la data e il motivo («Resta»), oppure «Non resta niente.» in grassetto al posto dell'elenco; che le copie di sicurezza, cifrate, perdono quei dati entro il tempo della policy; e chiude con «Non si può annullare.» in grassetto.** Il bottone che conferma è di pericolo e ripete il verbo del titolo («Cancella i dati di Luca»); in attesa dice «Cancellazione…». L'`id` è `cancella-figlio-12` e `cancella-account`, mai un nome. Se resta un'iscrizione il dialogo dice che restano anche il nome e la data di nascita del bambino dentro quell'iscrizione: è così nel modello, e chi legge «cancelliamo i dati di Luca» deve saperlo. Senza JavaScript il bottone è un link a `GET` dello stesso indirizzo: una pagina con gli stessi testi (il titolo è l'`h1`), «Cancella i dati di Luca» e «Annulla» verso `/profilo`. Il testo si calcola quando si disegna la pagina e la cancellazione guarda lo stato del momento (il controllo sta nella transazione); l'avviso dopo dice cosa è rimasto davvero.

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

**Variante neutra** (TALITA-7, per quello che c'era e è stato **cancellato**: «Iscrizioni cancellate», «Arrivi cancellati»): il cerchio è `bg-surface-muted text-ink-muted` al posto di `bg-brand-soft text-brand`, con l'icona `archive`; il testo dice quando e perché (le date vengono dalla policy: «sono state cancellate il 20 settembre 2028, un anno dopo la fine: servivano come prova dell’affidamento»), poi che restano le date e le informazioni della convivenza, senza nomi, e un bottone di testo «Vai alla convivenza» (icona `calendar-days`).

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
| 403 | `lock`, `bg-brand-soft text-brand` | Non puoi vedere questa pagina | La pagina esiste, ma il tuo accesso non comprende questi dati. Se pensi di doverla vedere, chiedi a chi organizza la convivenza. | Vai alla home (primario) |

Mai codici di errore, nomi di eccezioni, stack trace o parole come "server" e "database".

**Messaggio «Accesso negato»**: per una richiesta che non cambia pagina e viene rifiutata (un form, un pezzo di
pagina), la pagina resta com'è e compare un messaggio a comparsa (4.7), modello nel layout come gli altri, con
`lock`: titolo «Accesso negato», testo «Non hai il permesso di farlo. Se pensi di doverlo fare, chiedi a chi
organizza la convivenza.» Mai «Riprova»: ripetere non cambia niente. Una 403 a un **link** (un `GET` con
`hx-boost`, e il tasto indietro) sostituisce invece il `body` con la pagina 403, con il suo indirizzo e il suo
titolo, come 404 e 500. Senza JavaScript la pagina 403 arriva dal server con gli stessi testi. Il CSRF scaduto non è
una 403 per la persona e non mostra mai «Non puoi vedere questa pagina» (5).

### 4.12 Stampa

Vale per la pagina informativa e per la sua anteprima (stesso componente), e per le tre stampe di Arrivi (le **tabelle stampabili**, in fondo a questa sezione). Da telefono la persona usa il menu del browser («Condividi → Stampa»), da desktop Ctrl+P; i maestri anche il bottone «Stampa la pagina» dell'anteprima (`data-azione="stampa"`, `app.js` chiama `window.print()`). La pagina pubblica non ha un bottone «Stampa»: servirebbe uno script.

| Cosa | Regola |
|---|---|
| Pagina | **A4 verticale**, margini 16 mm in alto e in basso e 18 mm ai lati, corpo del testo a 11 pt |
| Colori | testo **nero su bianco**; i token si portano a nero e grigio con le variabili del blocco CSS qui sotto, così nessuna classe va riscritta. Gli sfondi non si stampano (i browser li tolgono) e **nessun significato dipende da uno sfondo**: gli avvisi hanno un bordo nero in stampa (`print:border print:border-ink`, già in `components/alert.jte`) |
| Si stampa | il titolo; le schede (bordo grigio, senza sfondo); l'avviso delle iscrizioni chiuse o della convivenza finita. Le schede brevi (date, quote) non si spezzano mai a metà; quelle che possono essere lunghe (informazioni, cosa portare) si spezzano fra le pagine |
| **Non** si stampa | «Vai al contenuto», barra di caricamento, intestazione col marchio, navigazione, messaggi a comparsa, bottone «Iscrivi i tuoi figli» e la sua riga; nell'anteprima anche il titolo dell'anteprima, l'avviso, i bottoni e la cornice |
| **Al posto del bottone** (solo iscrizioni aperte) | un blocco con filetto nero sopra: «Per iscrivere i tuoi figli apri questo indirizzo dal telefono o dal computer:», l'**indirizzo completo** in grande (`text-xl font-bold break-all`) e «Poi entri con il tuo indirizzo email: ti mandiamo un link, senza password.» Niente QR (per dopo, con un ticket) |
| Non tagliato a destra | niente larghezze fisse; testi lunghi a capo (`wrap-anywhere`), l'indirizzo con `break-all`; il layout a due colonne delle schede o quello a una vanno bene tutti e due (la larghezza della pagina stampata, 658 px, sta a cavallo dei 640 px) |
| Pagine | i titoli non restano soli in fondo (`break-after: avoid`) e restano attaccati al testo che segue; un paragrafo non lascia meno di tre righe da solo (`orphans`, `widows`) e una voce dell'elenco non si taglia a metà; le schede brevi e il blocco dell'indirizzo non si spezzano (`break-inside-avoid`). Con testi normali è un foglio. Con i testi al massimo (5.000 caratteri di informazioni e 2.000 di cose da portare, una per riga) sono **quattro fogli, e pieni**: a 11 pt il contenuto è alto 3,9 fogli (provato con un PDF di Chromium), quindi due non bastano; il difetto da evitare è un foglio mezzo vuoto, che si aveva quando le schede lunghe non potevano spezzarsi (5 fogli) |
| Chiuse e finita | niente blocco dell'indirizzo: non c'è niente da fare |

L'indirizzo stampato è `talita.public-url` più `/info/{codice}`, completo e assoluto. Il CSS sta in `src/main/css/app.css`, **fuori da ogni** `@layer`: gli stili senza layer battono le utility di Tailwind, che stanno in `@layer utilities`, senza bisogno di `!important`.

```css
@media print {
  @page { size: A4 portrait; margin: 16mm 18mm; }

  :root {
    --color-canvas: #fff; --color-surface: #fff; --color-surface-muted: #fff;
    --color-line: #999; --color-line-strong: #666;
    --color-ink: #000; --color-ink-muted: #333;
    --color-brand: #000; --color-brand-soft: #fff;
    --color-warning: #000; --color-warning-soft: #fff;
    --color-success: #000; --color-success-soft: #fff;
  }

  html { font-size: 11pt; }
  body { background: #fff; color: #000; }

  a[href="#contenuto"], #caricamento, #messaggi, body > header, body > nav { display: none; }

  main { margin: 0; padding: 0; animation: none; opacity: 1; }
  h1, h2, h3 { break-after: avoid; }

  p, li { orphans: 3; widows: 3; }
  li { break-inside: avoid; }
}
```

Sugli elementi, con le utility (non nel CSS): `print:hidden` su bottone e riga della pagina pubblica e sull'avviso e sui bottoni dell'anteprima; `break-inside-avoid` sulle schede brevi (date e quote: non su «Informazioni» e «Cosa portare»); `hidden … print:block` sul blocco dell'indirizzo; `print:border print:border-ink` sugli avvisi; sulla cornice dell'anteprima `print:border-0 print:bg-transparent print:p-0`. La stampa si prova con un PDF di Chromium (`Page.pdf`, scritto sotto `build/`, mai nel repo: lo fanno i test e2e).

**Tabelle stampabili** (TALITA-6: registro arrivi, elenco per la struttura, foglio per i maestri). Sono pagine intere (`max-w-5xl`, 2.6) con una barra che non si stampa (`print:hidden`: i campi che si scelgono prima di stampare, il bottone «Stampa» di 4.1 e una riga di aiuto), «Stampato il 24 ottobre 2026 alle 11:24.» (l'ora del server alla richiesta) e il documento. Regole:

- **Una pagina di carta è un blocco** (`.blocco-stampa`) con **la sua tabella e la sua intestazione**: Safari non ripete il `thead` fra le pagine, e chi legge un foglio da solo deve vedere che cos'è ogni colonna. Il server divide le righe in blocchi (`Pagination`: 20 righe sulla prima pagina e 24 sulle altre per il registro, 16 e 20 con più di quattro giorni, 20 e 26 per l'elenco, 18 e 24 per il foglio) e dà a ogni blocco la sua didascalia «Registro arrivi · pagina 1 di 2»; il blocco dopo il primo comincia su un foglio nuovo (`break-before: page`). Le capacità lasciano 15-25 mm liberi per foglio: un nome o una nota su due righe non fa traboccare. Ciò che segue la tabella (i totali del registro, i maestri dell'elenco, la frase finale del foglio) sta sull'ultimo foglio se c'è posto, altrimenti ha un foglio suo.
- **I blocchi non evitano la rottura**: se una riga cresce più del previsto, le righe in più passano al foglio dopo (Chrome ripete il `thead`), invece di spostare tutto il blocco e lasciare un foglio vuoto. Le righe non si spezzano (`break-inside: avoid`).
- **Nero su bianco**, tabelle a 8,5 pt, intestazioni a 8 pt, note a 7,5 pt; i bordi sono neri e nessun sfondo porta significato: la **X** nella casella (`.casella`, quadrata, 4 mm) vale in nero. La frase da distruggere del foglio per i maestri sta in un riquadro con bordo nero (`.riquadro-riservato`).
- **A schermo** le tabelle stanno in un riquadro `relative overflow-x-auto rounded-2xl border border-line bg-surface print:overflow-visible print:rounded-none print:border-0`; il solo registro ha `min-w-176` e, sul telefono, una riga di aiuto sopra («Scorri la tabella di lato per vedere tutte le colonne.», `lg:hidden print:hidden`). Il riquadro del registro, che scorre, è una regione con nome e `tabindex="0"` (senza, una persona che non usa il tocco non lo può scorrere, e axe lo segnala). `position: relative` serve: senza, i testi `sr-only` della tabella allargano la pagina.
- **Cosa c'è in ogni stampa**: registro, una riga per iscrizione non ritirata (giorni `G`, `P` o `–` con la casella, quota dovuta o «Gratis», pagato, manleva «Online», «Cartacea» o una casella vuota, note dell'app) con cinque righe vuote e i totali (con «Incassato a mano» da scrivere), **mai recapiti né segnalazioni**; elenco, solo cognome e nome, i bambini e gli affidatari, con tre righe vuote; foglio, bambino e recapito come scritto. Nessuna stampa porta note libere.
- Le colonne e le larghezze sono quelle del CSS in `app.css` (`.tabella-stampa`, `.col-nome`, `.col-giorno`, `.col-dovuta`, `.col-pagato`, `.col-manleva`, `.col-note`, `.totali-stampa`): la parte a schermo sta in `@layer components`, quella di stampa fuori da ogni `@layer`, dopo la stampa della pagina informativa. A 320 px la pagina non scorre di lato (la tabella sì, nel suo riquadro). **Safari su iPhone lo prova Matteo prima del rilascio**; la prova automatica usa un PDF di Chromium (A4, fuori dal repo) e conta i fogli.

### 4.13 Indirizzo da copiare

Il blocco della scheda «Link per i genitori» del dettaglio di una convivenza. **L'indirizzo è sempre visibile e selezionabile, anche senza JavaScript** (`select-all`, `break-all`, un tocco lungo lo seleziona). I bottoni che copiano e condividono sono `hidden` nell'HTML e li mostra `app.js` solo se il browser lo sa fare (`navigator.clipboard`, `navigator.share`), e lo rifà dopo ogni cambio di pagina (`htmx:afterSettle`). Senza JavaScript restano l'indirizzo e il link all'anteprima.

```html
<p class="mt-3 mb-1.5 text-sm text-ink-muted">Indirizzo della pagina</p>
<p id="link-indirizzo" class="rounded-xl bg-surface-muted px-4 py-3 font-medium break-all select-all">https://talita.example.org/info/q7Vd_3KmZ0xRb-Ew</p>
<div class="mt-4 flex flex-col gap-3 sm:flex-row-reverse sm:justify-start">
  <button type="button" hidden data-azione="copia" data-copia="link-indirizzo" class="…primario 4.1…">
    <svg class="size-5" aria-hidden="true">…copy…</svg>
    <span data-copia-normale>Copia il link</span><span hidden data-copia-fatto>Link copiato</span>
  </button>
  <button type="button" hidden data-azione="condividi" data-titolo="Convivenza di settembre 2027" data-testo="Informazioni: Convivenza di settembre 2027" data-indirizzo-da="link-indirizzo" class="…secondario 4.1…"><svg class="size-5" aria-hidden="true">…share-2…</svg>Condividi</button>
  <a href="/convivenza/12/anteprima" class="…testo 4.1… justify-center"><svg class="size-5" aria-hidden="true">…eye…</svg>Apri l’anteprima</a>
</div>
<p role="status" class="sr-only" data-copia-stato data-copia-annuncio="Link copiato."></p>
<p hidden data-copia-errore class="mt-2 text-sm text-ink-muted">Non riesco a copiare il link. Tienilo premuto per copiarlo.</p>
```

- **Copia** scrive l'indirizzo negli appunti. La conferma sta **nel punto in cui si è toccato**: per 3 secondi il bottone dice «Link copiato» (`app.js` scambia i due testi, che stanno entrambi nell'HTML) e una regione `role="status"` solo per i lettori di schermo ripete «Link copiato.». Niente messaggio a comparsa. Se la copia fallisce compare la riga «Non riesco a copiare il link. Tienilo premuto per copiarlo.» e l'indirizzo viene selezionato.
- **Condividi** chiama `navigator.share` con titolo (`data-titolo`), testo (`data-testo`) e indirizzo. Se la persona annulla non succede niente e non si dice niente. I testi stanno nei `data-*` del template, non nel JS.
- Il bottone primario della pagina è «Copia il link»; è secondario se la convivenza è finita: con le iscrizioni ancora aperte il primario diventa «Chiudi le iscrizioni», con le iscrizioni chiuse la pagina non ha un primario. Il titolo e l'indirizzo sono testo, mai HTML.
- In una bozza non c'è l'indirizzo (non funziona ancora): la scheda dice «Il link funziona dopo l’apertura delle iscrizioni. Intanto puoi vedere come sarà la pagina.» e ha solo «Apri l’anteprima».

### 4.14 Pagina informativa

La pagina che i genitori ricevono da un link (`/info/{codice}`), nel guscio pubblico (3.5), senza script. Dall'alto: `h1` (il titolo), avviso (solo chiuse e finita), schede, bottone (solo aperte). Titoli in ordine: `h1` → `h2` delle schede. Mai nomi né email di maestri, né numeri sulle iscrizioni: il template riceve solo i dati pubblici (`PublicInfo`), non la convivenza.

| Elemento | Aperta | Chiusa | Finita |
|---|---|---|---|
| Sotto l'`h1` | — | — | le date (`17–19 settembre 2027`, `text-ink-muted`) |
| Avviso | — | **attenzione**, senza `role` (è la pagina, non la risposta a un'azione): «Le iscrizioni sono chiuse. Per iscrivere un altro bambino o cambiare un’iscrizione già fatta, chiedi ai maestri.» | **informativo**: «La convivenza è finita. Per la prossima arriverà un link nuovo.» |
| Quando e dove, Quanto costa, Informazioni, Cosa portare | sì, ognuna solo se c'è | sì | **no**: né luogo, né quote, né testi, né bottone |
| Bottone «Iscrivi i tuoi figli» | **sì**, in fondo | no | no |

Le schede sono quelle di 4.4 (`section` con `aria-labelledby`, `break-inside-avoid`). «Quanto costa» ha le righe di 4.4: **Tutti i giorni** `60 €`, **Alcuni giorni** `25 € per ogni giorno`, **Solo pranzo** `10 € per ogni pranzo`; zero: «Gratuita»; una quota non prevista non compare, e se non ce n'è nessuna la scheda non compare. Le informazioni sono paragrafi (`max-w-prose`, `whitespace-pre-line`, `wrap-anywhere`), «Cosa portare» un elenco puntato. Il bottone è un normale collegamento a `/info/{codice}/iscrizione` (a tutta larghezza sul telefono, `sm:w-auto`), con sotto «Per iscriverli entri con il tuo indirizzo email: ti mandiamo un link, senza password.» (`text-sm text-ink-muted`: ripete l'introduzione di «Accedi»). Sta in fondo, dopo le informazioni: chi arriva dalla lettera vuole prima sapere quando, dove e quanto costa.

**Anteprima per i maestri** (`/convivenza/{id}/anteprima`, nel guscio con navigazione, `noindex`): lo stesso componente (`gathering/infoContent.jte`, con il livello dei titoli come parametro: il titolo della convivenza diventa `h2` e le schede `h3`, l'unico `h1` è «Anteprima della pagina»), dentro una cornice (`rounded-2xl border border-line bg-canvas p-4 lg:p-6`) che su desktop dice che è un'anteprima. In cima un avviso informativo («Così vede la pagina chi apre il link adesso.», più «Il bottone «Iscrivi i tuoi figli» qui non porta all’iscrizione.» se aperta; «Così apparirà la pagina quando aprirai le iscrizioni…» in bozza; «…: la convivenza è finita.» se finita) e i bottoni «Stampa la pagina» (non in bozza) e «Torna alla convivenza». Il bottone «Iscrivi i tuoi figli» è un `<button type="button">` che non fa niente, con `aria-describedby` che punta all'avviso: è l'unico bottone «finto», e mai un collegamento a `/info/…/iscrizione`.

**«Pagina non trovata» per `/info`**: la stessa risposta (404, stesso HTML, stesse intestazioni) per una bozza e per un codice che non esiste, anche per una sola maiuscola diversa, con o senza accesso, maestri compresi: non si deve capire se la bozza esiste. Titolo «Pagina non trovata», testo «L’indirizzo potrebbe essere sbagliato o incompleto. Se te l’ha mandato un maestro, chiedigli di mandartelo di nuovo.» (una variante della 404 di 4.11 solo per `/info`: chi riceve il link tagliato da una chat sa cosa fare), bottone «Vai all’accesso» senza accesso (guscio pubblico), «Vai alla home» con l'accesso (guscio dell'app, nessuna voce corrente).

**«Iscrizione»** (`/info/{codice}/iscrizione`, per ogni account; chi non ha l'accesso va a `/accedi` e, con il link nello stesso browser, torna qui): con le iscrizioni aperte e la fine non passata, il passo 1 dell'iscrizione (TALITA-5, 4.15); con le iscrizioni chiuse o la convivenza finita, 303 verso `/info/{codice}`; con una bozza o un codice sconosciuto, la 404 di sopra.

### 4.15 Passi

Un'iscrizione è in due passi: due barre (`aria-hidden`, la prima piena al passo 1, tutte e due al passo 2) e una frase che dice dove si è. Sta sotto il titolo e il suo sottotitolo (`registration/steps.jte`).

```html
<div class="mt-4"><div class="flex gap-1.5" aria-hidden="true"><span class="h-1.5 flex-1 rounded-full bg-brand"></span><span class="h-1.5 flex-1 rounded-full bg-line"></span></div>
<p class="mt-2 text-sm font-semibold text-ink-muted">Passo 1 di 2 · I dati</p></div>
```

### 4.16 Quota che si aggiorna

La quota dovuta si calcola solo sul server (`Quota`, una funzione per tutti i moduli) e si vede dentro il blocco del bambino, sotto la presenza. È una regione che si annuncia (`aria-live="polite"`) e si **riempie**, non si sostituisce: così uno screen reader legge il cambiamento. Vuota finché non c'è una scelta valida (`empty:hidden`); si attenua mentre la risposta arriva (`busy:opacity-60`, la richiesta parte dal gruppo di radio che la contiene). Con due o più bambini c'è la scheda «Quota» con una riga per bambino e il totale.

```html
<p id="quota-0" aria-live="polite" class="mt-3 flex flex-wrap items-baseline justify-between gap-x-4 rounded-xl bg-brand-soft px-4 py-3 busy:opacity-60 empty:hidden"><span>Quota</span><strong class="font-bold">35 €</strong></p>
```

### 4.17 Sezione che si apre

Una parte facoltativa di un form sta in un `<details>`, chiuso se è vuoto e aperto se ha testo o errori (le segnalazioni di un bambino): il modulo resta corto per chi non ha niente da scrivere, e la scritta dice cosa c'è dentro (**Facoltativo**, o **Facoltativo · scritte**).

```html
<details class="group rounded-2xl border border-line-strong bg-surface">
  <summary class="flex min-h-14 cursor-pointer list-none items-center gap-3 px-4 py-2 [&::-webkit-details-marker]:hidden">
    <svg class="size-6 shrink-0 text-brand" aria-hidden="true">…heart-pulse…</svg>
    <span class="min-w-0 flex-1"><span class="block font-semibold">Allergie, intolleranze, farmaci</span><span class="block text-sm text-ink-muted">Facoltativo</span></span>
    <svg class="size-5 shrink-0 text-ink-muted transition-transform duration-150 group-open:rotate-180" aria-hidden="true">…chevron-down…</svg>
  </summary>
  <div class="space-y-5 border-t border-line px-4 pt-4 pb-4">…</div>
</details>
```

### 4.18 Testo lungo e cornice del testo di prova

Un testo che si deve leggere tutto (l'affidamento, il testo accettato) sta intero nella pagina, **mai in una finestra che scorre**: `max-w-prose whitespace-pre-line wrap-anywhere` a 17 px. Un testo che non è ancora quello vero (l'informativa completa, finché non l'ha letta una persona competente) ha in cima la sua cornice, e un test (`-Prelease`) non lascia uscire un rilascio che la porta ancora.

```html
<p class="mb-4 rounded-lg bg-warning-soft px-3 py-2 text-sm font-semibold wrap-anywhere">[TESTO DI PROVA · …]</p>
<p class="mt-3 max-w-prose whitespace-pre-line wrap-anywhere">Affidamento di figli minorenni …</p>
```

### 4.19 Azioni di una scheda

Le azioni di una scheda sono bottoni di testo (4.1) in fila, che vanno a capo, con l'icona e un `aria-label` che dice di chi sono («Modifica l’iscrizione di Luca Rossi»). Sul telefono ogni bottone è alto 44 px.

```html
<div class="-mb-2 -ml-3 mt-3 flex flex-wrap gap-1">…bottoni di testo…</div>
```

L'azione che toglie qualcosa per sempre («Togli le segnalazioni») ha il suo dialogo (4.8) e nel dialogo il bottone di **pericolo**; il ritiro di un'iscrizione si può rimediare, quindi nel suo dialogo il bottone è primario. Il ritiro e la revoca sono due verbi diversi: il secondo cancella.

### 4.20 Selettore di giorno

I giorni della convivenza (da 1 a 7) in una riga di link a colonne uguali, uno per giorno, dentro una `nav` con la sua etichetta («Giorni della convivenza»). Il giorno scelto ha `aria-current="date"` (sfondo `brand-soft`, anello `brand`); sotto il numero, «oggi» sul giorno di oggi. Ogni link porta la data (`/arrivi?giorno=2027-09-18`): nessun nome in un indirizzo. Il nome accessibile dice il giorno intero («Sabato 18 settembre (oggi)»); l'abbreviazione («Sab») è solo per gli occhi.

```html
<nav aria-label="Giorni della convivenza"><ul class="grid auto-cols-fr grid-flow-col gap-1.5 rounded-2xl border border-line bg-surface p-1.5">
  <li><a href="/arrivi?giorno=2027-09-18" aria-current="date" class="flex min-h-16 flex-col items-center justify-center rounded-xl text-center …">
    <span class="text-xs font-medium"><span aria-hidden="true">Sab</span><span class="sr-only">Sabato</span></span>
    <span class="text-lg font-semibold tabular-nums">18<span class="sr-only"> settembre (oggi)</span></span>
    <span class="text-xs font-semibold" aria-hidden="true">oggi</span>
  </a></li>…
</ul></nav>
```

### 4.21 Riepilogo con numeri

Quattro numeri in una griglia (`grid-cols-2 gap-2 sm:grid-cols-4`), ognuno in un riquadro `rounded-xl bg-canvas p-3` con la sua etichetta (`dt`, `text-sm text-ink-muted`) e il valore (`dd`, `text-2xl font-semibold tabular-nums`). **Lo zero è in parole** («Nessuno», «Nessuna», «Niente»), in un corpo più piccolo (`text-lg`), e il numero può avere una riga sotto («su 4 attesi»). I soldi stanno sotto, in righe «etichetta … valore» separate da un filetto: «Incassato in tutta la convivenza» (con sotto, per cognome, quanto ha incassato ogni maestro, solo chi ha incassato) e «Ancora da incassare». È una `section` con il suo titolo; le risposte a un tocco la sostituiscono fuori banda (`id="riepilogo"`, `hx-swap-oob`), e mentre si cerca un nome `app.js` la nasconde, per portare i risultati vicini alla cima.

### 4.22 Ricerca in una lista

Un campo `type="search"` senza `form`, con l'icona `search` a sinistra (4.2), nascosto nell'HTML (`hidden data-filtro`) e mostrato da `app.js`: senza JavaScript non c'è e la lista è intera. **Il filtro gira nel browser** sui nomi che sono già nella pagina (`data-filtro-riga`, `data-filtro-testo`) e non chiede niente al server: nessun nome negli indirizzi né nei log. Regola: maiuscole, accenti e apostrofi non contano; restano le righe in cui **ogni parola** scritta sta nel nome o nel cognome, in qualunque ordine («rossi luca», «dangelo» per D’Angelo). Un gruppo senza righe sparisce con il suo titolo. L'esito è annunciato (`role="status"`, dopo 250 ms dall'ultima lettera, «Trovati 2 bambini.») e, se non c'è nessuno, la lista è sostituita dallo stato vuoto di 4.9 («Nessun bambino con questo nome»). «Invio» chiude la tastiera e non segna nessuno.

```html
<div hidden data-filtro><label for="cerca" class="sr-only">Cerca un bambino</label>
  <div class="relative"><svg class="pointer-events-none absolute top-1/2 left-3.5 size-5 -translate-y-1/2 text-ink-muted" aria-hidden="true">…search…</svg>
    <input id="cerca" type="search" autocomplete="off" autocapitalize="off" spellcheck="false" enterkeyhint="search" placeholder="Cerca per nome o cognome" aria-describedby="cerca-esito" class="block min-h-12 w-full rounded-xl border border-line-strong bg-surface pr-4 pl-11 …"></div>
  <p id="cerca-esito" role="status" class="mt-1.5 min-h-5.5 text-sm text-ink-muted"></p></div>
```

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

**Form rimandato con errori** (un indirizzo sbagliato, un limite, una pagina scaduta, un passo che lo stato delle cose rifiuta): il server
risponde con la pagina del form, **422** (errori), **429** (limiti) o **409** (conflitto con lo stato: una convivenza che non si può
riaprire, un maestro che è affidatario), e `app.js` la mette al posto di
quella che c'è come per una risposta 200, senza il messaggio «Qualcosa non ha funzionato». I campi
restano compilati. Il fuoco va al primo campo con `aria-invalid="true"`; se non c'è, al primo elemento con
`data-fuoco` (l'avviso); se non c'è nemmeno quello e la pagina è cambiata, all'`h1`. L'errore di un campo lo
legge il lettore di schermo grazie ad `aria-describedby`. Dopo un cambio di pagina con un frammento nell'indirizzo (`/convivenza/12/date#luogo`, un collegamento di «Prima di aprire le iscrizioni») il fuoco va all'elemento che ha quell'`id`, il campo da completare, prima di ripiegare sull'`h1`. Il form rimandato non lascia un'altra voce nella
cronologia. Vale per ogni form con `hx-boost` e per ogni `hx-post` con un suo `hx-target`.

**Azione su un blocco della pagina** (un form o un bottone con `hx-post`, `hx-target` e `hx-swap="outerHTML"`,
come in «Maestri», 3.8): il server risponde con il blocco intero, che htmx mette al posto del vecchio; la pagina
resta com'è, compresa la posizione di scorrimento, e l'indirizzo non cambia. Con 422 e 429 il blocco si sostituisce
come con 200. Il fuoco va al primo campo con `aria-invalid="true"` del blocco nuovo o, se non c'è, al suo primo
elemento con `data-fuoco` (l'avviso); se non c'è nemmeno quello, non si muove. Senza JavaScript lo stesso form si
manda come una pagina: risponde con un 303 alla pagina, con l'avviso lasciato in sessione e letto una volta sola, o
con la pagina intera e gli errori.

**Accesso mancante o scaduto** su una richiesta di htmx (un cambio di pagina o un pezzo di pagina): il
server risponde con l'intestazione `HX-Redirect: /accedi` (non un 302, che htmx seguirebbe dentro il pezzo, e
non un 401, che sarebbe un errore). Il browser ricarica la pagina intera: mai la pagina di accesso dentro quella
vecchia, mai «Qualcosa non ha funzionato». Se la sessione era scaduta per inattività (due ore) la pagina di
accesso lo dice una volta sola.

**Richiesta che non cambia pagina** (un pezzo della pagina, per esempio una riga dopo una
modifica):

- si aggiorna solo quel pezzo; il resto resta com'è, compresa la posizione di scorrimento;
- se va male (5xx), il pezzo **non** viene sostituito e compare il messaggio "Qualcosa non ha
  funzionato" (criterio 28).
- se è rifiutato (403), il pezzo non viene sostituito e compare il messaggio «Accesso negato» (4.11), senza
  «Riprova».

**Rete assente o lenta** (qualsiasi richiesta):

- il contenuto resta quello di prima, l'indicatore sparisce, compare il messaggio "Nessuna
  connessione" (criterio 27);
- dopo 20 secondi senza risposta la richiesta si ferma e compare "La connessione è troppo
  lenta", con lo stesso comportamento;
- tornata la rete, "Riprova" o un nuovo tocco funzionano normalmente;
- vale soprattutto per i maestri durante la convivenza, dove il campo può mancare a lungo: una richiesta che fallisce non cancella mai quello che la persona ha scritto, il form resta compilato (già previsto in 4.7).

**La pagina informativa pubblica non usa htmx** (4.14): niente `hx-*`, niente `app.js`, un solo caricamento, nessun indicatore. Nel dettaglio e nell'anteprima (guscio con htmx) cambia solo la pagina intera; `app.js` ha tre azioni, tutte con `data-azione` e nessun `onclick`: `copia`, `condividi` e `stampa` (4.12, 4.13).

**Senza JavaScript** (o prima che htmx sia caricato): link e form funzionano come in un sito
normale, con il ricaricamento della pagina. Niente indicatore e niente messaggi a comparsa, ma le
pagine 404 e 500 arrivano dal server con gli stessi testi.

Il codice che fa queste cose sta in un file JS dell'app (`app.js`), non nei template: gestione di
`htmx:beforeSwap`, `htmx:sendError`, `htmx:timeout`, `htmx:afterRequest`, `htmx:afterSettle`,
apertura dei dialoghi. Come scriverlo lo decide il developer.

### Quota che si aggiorna e blocchi che si aggiungono (TALITA-5)

- **Quota che si aggiorna**: un cambio in un gruppo di radio → `hx-post` con `hx-params` e l'elenco esatto dei campi che viaggiano (htmx 2 aggiunge a ogni POST tutti i campi del form e `hx-include` non ne toglie): la presenza e i giorni di ogni blocco, il codice CSRF e, solo con due bambini o più, nome e cognome (le righe della card «Quota»); mai data di nascita, telefono, stato né segnalazioni: i dati sanitari non viaggiano a ogni tocco. I campi della presenza portano `data-quota`, e un test confronta l'elenco con quelli marcati. `hx-swap="none"` e `hx-sync="closest form:replace"` (vale l'ultima richiesta). La risposta contiene la riga di ogni bambino e il totale **fuori banda** (`hx-swap-oob="innerHTML:#quota-0"`). Senza JavaScript la quota si vede al passo 2.
- **Blocchi che si aggiungono e si tolgono dentro un form**: i bottoni «Aggiungi un figlio», «Togli», «Aggiungi» sono bottoni del form (`name="azione"`, `formaction`) che senza JavaScript mandano il form e ricevono la pagina intera; con htmx (`hx-post`, `hx-target="#figli"`, `hx-swap="outerHTML"`) si sostituisce **un pezzo solo**, e il fuoco va al blocco nuovo (`data-fuoco` sul suo primo campo).
- **Un passo che non salva** (passo 2 dell'iscrizione): è la risposta al passo 1 (200, non un redirect), con `HX-Push-Url: false` così non lascia una voce nella cronologia; i dati viaggiano nei campi nascosti e il server li ricontrolla tutti all'invio.

### Arrivi (TALITA-6)

- **Una risposta con quello che è vero adesso**: un tocco su una riga risponde con la riga nuova (`hx-target="closest li"`), il riepilogo (fuori banda) e una frase per il lettore di schermo in `hx-swap-oob="innerHTML:#esito"` (la regione `role="status"` resta nella pagina); un'azione della scheda risponde con tutto il blocco `#scheda` (così niente resta vecchio, e le segnalazioni, che si aprono solo nella risposta al tocco che ha scritto la lettura, tornano chiuse). Dopo la sostituzione di una riga il fuoco va al suo nuovo bottone (`data-fuoco`, che c'è solo nelle risposte), e nella scheda sull'avviso.
- **Le risposte 409 si sostituiscono come le 422**: la riga o la scheda com'è adesso, con la nota che dice perché non è stato fatto («Non segnata: nel frattempo l’iscrizione è stata ritirata.»); una richiesta che è già stata fatta (un'altra persona ha segnato un momento prima, un pagamento già tolto) è un 200 con una nota, mai un errore. Un POST rifiutato con 4xx per altre ragioni mostra «Qualcosa non ha funzionato»; un frammento chiesto con GET che risponde 4xx resta muto.
- **Senza rete, scaduta o con un errore del server** la pagina non cambia: mai un «Presente» non salvato, il messaggio è quello di sopra (senza «Riprova»: basta toccare di nuovo il bottone) e il form di un pagamento resta compilato. Tornata la rete un nuovo tocco funziona senza doppioni: la presenza ha una sola riga per iscrizione e giorno, e il form di un pagamento porta un codice della richiesta che il server accetta una volta sola.
- **I link `tel:` e i download** hanno `hx-boost="false"`: il browser apre il telefono o scarica, non una pagina.
- **Senza JavaScript** ogni tocco è un form normale che risponde con un redirect alla scheda, con l'avviso che il server si porta dietro e mostra una volta sola; «Togli» un pagamento è un link a una pagina con le parole del dialogo; il campo di ricerca e il bottone «Stampa» non ci sono.

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
| Codice CSRF scaduto | La pagina era aperta da troppo tempo. Riprova. |
| Invio della mail fallito | Non siamo riusciti a mandare il link. Riprova tra qualche minuto. Se il problema continua, avvisa chi organizza la convivenza. |
| Invio dell'invito fallito | Non siamo riusciti a mandare la mail. Riprova tra qualche minuto. |
| Invito a un maestro | È già tra i maestri. |
| Ultimo maestro | Serve almeno un maestro. Per togliere questa persona, invita prima un altro maestro. |

### Conferme

- Dopo un'azione riuscita: oggetto e participio, breve. "Convivenza salvata.", "Iscrizione ritirata."
- Prima di un'azione con conseguenze: dialogo (4.8).
- Una conferma può avere **una seconda frase** su cosa resta e fino a quando («Dati di Luca cancellati.», poi «Resta fino al 20 settembre 2028 l’iscrizione di Luca alla «Convivenza di settembre 2027», come prova dell’affidamento.»): un avviso (4.6) con due paragrafi (`space-y-2`), il primo in `font-semibold`, e se serve un bottone di testo sotto.
- **«Cancella» e «Togli»** (TALITA-7): «Cancella» è per sempre e riguarda dati personali; «Togli» si può rimediare (si invita di nuovo, si riscrive). Non si scambiano.
- **Il tempo del verbo** per le cancellazioni segue la data e non le righe del database: «Si cancellano il 20 ottobre 2027» prima della data, «Cancellate il 20 ottobre 2027» dal giorno stesso; mai «fra tre giorni».

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
| Importo a zero | in parole | Gratuita (non "0 €") |
| Giorni di una convivenza | giorno della settimana con l'iniziale maiuscola, i giorni separati da virgole e «e»; mese e anno una volta sola se sono gli stessi, altrimenti per ogni giorno | Venerdì 17, sabato 18 e domenica 19 settembre 2027 · Giovedì 30 dicembre 2027, venerdì 31 dicembre 2027 e sabato 1 gennaio 2028 |
| Anni diversi in un intervallo | trattino medio con spazi, l'anno di ogni data | 30 dicembre 2027 – 1 gennaio 2028 |
| Un giorno solo | la data intera | 18 settembre 2027 |
| Singolare e plurale | sempre giusti | 1 bambino, 24 bambini (mai "bambino/i") |

Nel codice le date e gli importi si formattano con `Locale.ITALIAN`, non a mano.

### Iscrizioni (TALITA-5)

- **Importi** con lo spazio non separabile prima di «€» (`60 €`); **«Gratuita»** per zero, mai «0 €»; **«Non dovuta»** per la quota di una ritirata.
- **Presenza**: `Tutti i giorni (17–19 settembre 2027)` oppure `Giornata: sabato 18. Solo pranzo: domenica 19.`; nelle righe di un elenco `Sab 18 · pranzo Dom 19`.
- **Modelli di testo** (l'affidamento): un solo testo con segnaposto, una versione (`affidamento-1`) e due regole d'accordo, `{b:suo|loro}` per i bambini e `{a:lo/la|li}` per i maestri affidatari; nessuno chiede il sesso, quindi la forma del singolare è le due insieme.
- **Ritirare** e **togliere le segnalazioni** sono due verbi diversi, e nei testi non si scambiano.
- Il **telefono** si legge come lo ha scritto chi lo ha scritto, con uno spazio per ogni separatore.

### Arrivi (TALITA-6)

- **«Presente»**, «Segna presente», «Presenza segnata.» e «Presenza tolta.»: mai «Arrivato/a». Una presenza segnata su un giorno non previsto dice che la quota dovuta non cambia finché non si corregge l'iscrizione.
- **Saldo in parole**: «Da pagare 25 €», «Pagata», «Pagato 10 € in più», «Gratuita» (dovuta e pagata a zero), «Non dovuta» per una iscrizione ritirata, «Quota da definire» quando i giorni non sono fra quelli che le quote offrono.
- **Importi** come sopra (`60 €`, `12,50 €`, migliaia `1.035 €`) e **zero in parole**: «Nessuno», «Nessuna» e «Niente» («Pagata Niente»: non «0 €»). Nel registro stampato la quota a zero è «Gratis» e un pagamento che non c'è è una cella vuota, da scrivere a mano.
- **Il recapito** si mostra come è stato scritto, e il bottone che chiama dice «Chiama» (con il numero per il lettore di schermo).
- **Segnalazioni**: «Sono dati sulla salute. Ogni volta che le mostri, resta scritto chi le ha lette e quando.»; il bottone «Mostra le segnalazioni» e, dopo, «Lettura registrata: Marta Bianchi, 18 settembre 2027 alle 10:05.»; «Nessuna segnalazione.» se non c'è niente. Si dice «segnalazioni», mai «dati sanitari» sul bottone.
- **Stampe**: «Registro arrivi», «Elenco per la struttura», «Foglio per i maestri», «Stampato il … alle …»; la frase del foglio dice di tenerlo fra i maestri e di distruggerlo alla fine della convivenza.
- **Un nome solo per ogni azione**: «Inserisci un’iscrizione» dall'elenco di TALITA-5 e dal modulo di Arrivi, «Segna come ricevuta» per la manleva cartacea.

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
- `<script>` inline, attributi `on*=`, `hx-on`, `style=""`: la Content-Security-Policy li blocca
  (`default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; base-uri 'none';
  frame-ancestors 'none'; form-action 'self'`, senza `unsafe-inline` né `unsafe-eval`), e un test e2e
  fallisce se la console del browser segnala una violazione.
- Testi tecnici rivolti alle persone (codici, eccezioni, nomi di tabelle).
- Le parole «Koinè», «Centro», «comunità» e «fratelli» nei testi fissi: sono di un altro progetto.
- Voci o schermate fuori dal perimetro di Talità, quello scritto in `CLAUDE.md`.
