Inter 4.1, sottoinsiemi woff2 serviti dall'app (nessun font da servizi esterni).

Licenza: SIL Open Font License 1.1, nel file LICENSE.txt accanto a questo (è quello della
release; il font non ha nome riservato, quindi si può ridurre e servire).

Fonte: https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip
SHA-256 dello zip: 9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e

File prodotti, a mano, con fonttools (Python), senza Node né npm. Partendo da InterVariable.ttf
dello zip (solo il dritto, il corsivo non si usa):

  fonttools varLib.instancer InterVariable.ttf opsz=14 -o inter-wght.ttf
  pyftsubset inter-wght.ttf --flavor=woff2 --layout-features+=cv08,tnum,case \
    --unicodes="U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD" \
    --output-file=inter-latin.woff2
  pyftsubset inter-wght.ttf --flavor=woff2 --layout-features+=cv08,tnum,case \
    --unicodes="U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF" \
    --output-file=inter-latin-ext.woff2

(fonttools 4.46 con brotli.) Gli intervalli sono quelli di Google Fonts. Il peso è variabile
(100-900), la variante cv08 (I maiuscola con le grazie) è inclusa e accesa dal CSS.

  inter-latin.woff2      47.308 byte, SHA-256 1f04d9c03355168e84fbf089575ea36c734e4c0e179c42f9f45fd34f87185054
                         italiano e lingue dell'Europa occidentale: l'unico che scarica la home
  inter-latin-ext.woff2  80.680 byte, SHA-256 e1cda607b846ae78286cbcd5fd3afac33b07c0daf46f5fef4e22b96d0635c083
                         lettere come ł, ș, ő: il browser lo scarica solo se una pagina le contiene

Se inter-latin.woff2 supera i 60 KB il sottoinsieme è sbagliato. Se un file cambia, va aggiornata
anche la riga "/*! fonts ... */" in src/main/css/app.css: un test lo verifica, e serve a far
cambiare l'indirizzo del CSS (che nasconde gli indirizzi dei font) quando cambiano i font.
