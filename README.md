# it-ext

Repository di estensioni per lettori compatibili con Keiyoushi / Mihon / Tachimanga.

## Indirizzo da incollare

```
https://raw.githubusercontent.com/adam72881/it-ext-repo/repo/index.min.json
```

L'app, se segue il formato attuale, legge anche `repo.json` e `index.pb` nella stessa cartella.

## Su iPhone (Tachimanga)

1. Apri Tachimanga.
2. Vai su Sfoglia, poi Estensioni.
3. Apri le impostazioni di quella schermata e scegli Repository estensioni.
4. Tocca + e incolla l'indirizzo qui sopra.
5. Torna all'elenco, aggiorna, e installa l'estensione.
6. In Sorgenti aprila.

## Hentai ITA

Sorgente italiana per la sezione manga di hentai-ita.net. I tag loli, shota e gli altri che indicano minori sono esclusi e non si possono riattivare. I titoli con tag teen restano in elenco. Le pagine nell'area premium non vengono aperte. Le richieste al sito sono rallentate di proposito.

## Instagram

Estensione sperimentale, lingua `all`, pacchetto `eu.kanade.tachiyomi.extension.all.instagram`. Serve a sfogliare il proprio account Instagram dentro il lettore. Non modifica Hentai ITA.

1. Installa **Instagram** e aprilo in Sorgenti.
2. Apri la WebView della sorgente e accedi al tuo account. L'estensione riusa i cookie `sessionid`, `csrftoken` e `ds_user_id`. Se mancano, compare un errore che chiede di entrare dalla WebView.
3. **Popolari** è la home: ogni post del feed è una voce, con la sua copertina. **Recenti** sono gli account che segui.
4. La ricerca trova profili. Puoi incollare un URL di profilo o di post, oppure scrivere un nome utente. Il filtro Hashtag, se compilato, mostra i post recenti di quel tag.
5. In un profilo i capitoli sono i post, dal più nuovo, fino a 48. Le pagine sono le immagini del post, nell'ordine del carosello. Video e reel mostrano solo la copertina.

Le chiamate al sito sono una alla volta, con almeno 0,9 secondi di distanza e non più di 24 al minuto. Storie, messaggi e la riproduzione video non ci sono. Gli URL delle immagini scadono: se una pagina non si apre, rientra nel capitolo.

Il sorgente sta in `src/all/instagram` e si compila con il plugin `kei.plugins.extension` di [extensions-source](https://github.com/keiyoushi/extensions-source), lib `1.6`. L'APK e il JAR vanno firmati con la stessa chiave già indicata in `repo.json`, altrimenti l'app rifiuta l'installazione.

## File

- `index.min.json` indice legacy
- `repo.json` punta a `index.pb`
- `index.json` stesso indice in JSON
- `index.pb` indice binario compresso
- `apk/` pacchetto APK
- `jar/` pacchetto JAR (lib 1.6)
- `icon/` icona
