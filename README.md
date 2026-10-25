# Instagram

Repository di una sola estensione, per lettori compatibili con Keiyoushi / Mihon / Tachimanga.

## Indirizzo da incollare

```
https://raw.githubusercontent.com/adam72881/it-ext-repo/repo-instagram/index.min.json
```

L'app, se segue il formato attuale, legge anche `repo.json` e `index.pb` nella stessa cartella.

## Su iPhone (Tachimanga)

1. Apri Tachimanga.
2. Vai su Sfoglia, poi Estensioni.
3. Apri le impostazioni di quella schermata e scegli Repository estensioni.
4. Tocca + e incolla l'indirizzo qui sopra.
5. Torna all'elenco, aggiorna, e installa **Instagram** (lingua all).
6. In Sorgenti aprila.

## Uso

Estensione sperimentale, pacchetto `eu.kanade.tachiyomi.extension.all.instagram`, versione 1.6.3.

1. Apri la WebView della sorgente e accedi al tuo account. L'estensione riusa i cookie `sessionid`, `csrftoken` e `ds_user_id`. Se mancano, compare un errore che chiede di entrare dalla WebView.
2. **Popolari** è la home: ogni post del feed è una voce, con la sua copertina. **Recenti** sono gli account che segui.
3. La ricerca trova profili. Puoi incollare un URL di profilo o di post, oppure scrivere un nome utente. Il filtro Hashtag, se compilato, mostra i post recenti di quel tag.
4. In un profilo i capitoli sono i post, dal più nuovo, fino a 48. Le pagine sono le immagini del post, nell'ordine del carosello. Video e reel mostrano solo la copertina.

Le chiamate al sito sono una alla volta, con almeno 2,5 secondi di distanza e non più di 8 al minuto. Una risposta 429 viene ritentata. Storie, messaggi e la riproduzione video non ci sono. Gli URL delle immagini scadono: se una pagina non si apre, rientra nel capitolo.

L'APK e il JAR sono firmati con la chiave indicata in `repo.json` (`7a294ed5165763f074819bcd057723cf82f0d72935dcde26da8a7b3e45342e44`).

## File

- `index.min.json` indice legacy
- `repo.json` punta a `index.pb`
- `index.json` stesso indice in JSON
- `index.pb` indice binario compresso
- `apk/` pacchetto APK
- `jar/` pacchetto JAR (lib 1.6)
- `icon/` icona
