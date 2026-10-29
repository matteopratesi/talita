Icone di Talità: Lucide 1.59.0 (licenza ISC, nel file LICENSE.txt accanto a questo; una parte delle
icone viene da Feather, MIT, ed è coperta dallo stesso file).

Non sono file da servire: gli SVG sono scritti una volta sola, inline, nel template
src/main/jte/components/icon.jte, copiati dal pacchetto npm lucide-static 1.59.0
  https://registry.npmjs.org/lucide-static/-/lucide-static-1.59.0.tgz
  integrity sha512-MuHZYaXbHgzN2QO6D3OM19bj09A+8GJiFSdXIMlbnLLEGQQ9+NRH2KaCxuivevD3Y23vM74kV17XBifgtqhfEg==
cartella icons/, senza modifiche ai tracciati.

Aggiunte da TALITA-4: plus, pencil (convivenza), copy, share-2, printer, eye (link per i genitori e anteprima).

Aggiunte da TALITA-7: archive (conservata fino al, stato vuoto di quello che è stato cancellato), shield-check (per quanto tempo teniamo i dati).

Aggiunte da TALITA-6: phone (Chiama), list (elenco per la struttura), clipboard-list (registro arrivi), chevron-left (Torna agli arrivi), search (ricerca nella lista).

Aggiunte da TALITA-11: bed-double (camere: la scheda nella Convivenza, la pagina, lo stato vuoto, la scheda del bambino in Arrivi).

Usate nel guscio: house, users-round, clipboard-check, calendar-days, info, chevron-right,
hourglass, compass, triangle-alert, rotate-cw, wifi-off, clock, x. Ogni icona ha una voce
nell'enum talita.ui.Icon; un test controlla che il template le disegni tutte.

Per aggiungere un'icona: nuova voce in Icon, nuovo ramo in icon.jte con i tracciati del file
lucide-static della stessa versione, e il suo nome qui sopra. Se si cambia versione di Lucide
si aggiornano versione, integrity e questo elenco.

Il marchio (quadrato blu con la T) e favicon.svg non sono di Lucide: disegnati per Talità.
