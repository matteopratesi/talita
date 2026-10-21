Icone di Koinè: Lucide 1.59.0 (licenza ISC, nel file LICENSE.txt accanto a questo; una parte delle
icone viene da Feather, MIT, ed è coperta dallo stesso file).

Non sono file da servire: gli SVG sono scritti una volta sola, inline, nel template
src/main/jte/components/icon.jte, copiati dal pacchetto npm lucide-static 1.59.0
  https://registry.npmjs.org/lucide-static/-/lucide-static-1.59.0.tgz
  integrity sha512-MuHZYaXbHgzN2QO6D3OM19bj09A+8GJiFSdXIMlbnLLEGQQ9+NRH2KaCxuivevD3Y23vM74kV17XBifgtqhfEg==
cartella icons/, senza modifiche ai tracciati.

Usate nel guscio: house, users-round, contact-round, calendar-days, info, chevron-right,
hourglass, compass, triangle-alert, rotate-cw, wifi-off, clock, x. Ogni icona ha una voce
nell'enum talita.ui.Icon; un test controlla che il template le disegni tutte.

Per aggiungere un'icona: nuova voce in Icon, nuovo ramo in icon.jte con i tracciati del file
lucide-static della stessa versione, e il suo nome qui sopra. Se si cambia versione di Lucide
si aggiornano versione, integrity e questo elenco.

Il marchio (quadrato blu con la K) e favicon.svg non sono di Lucide: disegnati per Koinè.
