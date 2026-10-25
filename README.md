# it-ext

Repository di estensioni per lettori compatibili con Keiyoushi / Mihon / Tachimanga.

## Hentai ITA

```
https://raw.githubusercontent.com/adam72881/it-ext-repo/repo/index.min.json
```

L'app, se segue il formato attuale, legge anche `repo.json` e `index.pb` nella stessa cartella.

1. Apri Tachimanga.
2. Vai su Sfoglia, poi Estensioni.
3. Apri le impostazioni di quella schermata e scegli Repository estensioni.
4. Tocca + e incolla l'indirizzo qui sopra.
5. Torna all'elenco, aggiorna, e installa **Hentai ITA** (lingua it, contenuto per adulti).
6. In Sorgenti apri Hentai ITA.

Sorgente italiana per la sezione manga di hentai-ita.net. I tag loli, shota e gli altri che indicano minori sono esclusi e non si possono riattivare. I titoli con tag teen restano in elenco. Le pagine nell'area premium non vengono aperte. Le richieste al sito sono rallentate di proposito.

## Instagram

Repository separato, solo questa estensione:

```
https://raw.githubusercontent.com/adam72881/it-ext-repo/repo-instagram/index.min.json
```

Estensione sperimentale, lingua `all`, pacchetto `eu.kanade.tachiyomi.extension.all.instagram`, versione 1.6.4. Serve a sfogliare il proprio account Instagram dentro il lettore.

1. Aggiungi il repository qui sopra, aggiorna, e installa **Instagram**.
2. Apri la WebView della sorgente e accedi al tuo account. L'estensione riusa i cookie `sessionid`, `csrftoken` e `ds_user_id`. Se mancano, compare un errore che chiede di entrare dalla WebView.
3. **Popolari** è la home: ogni post del feed è una voce, con la sua copertina, e lo scorrimento carica le pagine successive. **Recenti** sono i post degli account che segui, dalla stessa timeline.
4. La ricerca trova profili. Puoi incollare un URL di profilo o di post, oppure scrivere un nome utente. Il filtro Hashtag, se compilato, mostra i post recenti di quel tag.
5. In un profilo i capitoli sono i post, dal più nuovo, fino a 48. Le pagine sono le immagini del post, nell'ordine del carosello. Video e reel mostrano solo la copertina.

Le chiamate al sito sono una alla volta, con almeno 0,6 secondi di distanza e non più di 30 al minuto. Una risposta 429 viene ritentata. Storie, messaggi e la riproduzione video non ci sono. Gli URL delle immagini scadono: se una pagina non si apre, rientra nel capitolo.

Il sorgente sta in `src/all/instagram` e si compila con il plugin `kei.plugins.extension` di [extensions-source](https://github.com/keiyoushi/extensions-source), lib `1.6`. È firmato con una chiave sua, indicata nel `repo.json` del branch `repo-instagram`.

## File

- `index.min.json` indice legacy
- `repo.json` punta a `index.pb`
- `index.json` stesso indice in JSON
- `index.pb` indice binario compresso
- `apk/` pacchetto APK
- `jar/` pacchetto JAR (lib 1.6)
- `icon/` icona
