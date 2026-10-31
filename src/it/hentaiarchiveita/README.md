# Hentai Archive (Premium) — sorgente

Modulo Keiyoushi per Mihon/Tachimanga.

- Sito: https://membri.premium-archive.com/ (l'area membri di Hentai Archive)
- Lingua: italiano
- Libreria: KeiSource / libVersion 1.6
- Pacchetto: eu.kanade.tachiyomi.extension.it.hentaiarchiveita

Keiyoushi ha già una sua estensione "HentaiArchive" (pacchetto `…it.hentaiarchive`) per il sito gratuito: questa usa un pacchetto diverso, così le due non vanno in conflitto.

## Accesso

Dalla versione 1.6.3 l'estensione usa solo l'area membri. Il catalogo si sfoglia senza account, ma le pagine di ogni titolo si vedono solo con un abbonamento attivo:

1. apri un titolo e tocca l'icona del mappamondo (WebView);
2. tocca «Accedi con Patreon» (o «Accedi» con l'account del sito) e completa il login;
3. torna indietro e apri il capitolo.

L'app condivide i cookie della WebView con la sorgente. La lettura rilegge sempre la pagina dal sito, senza cache, così dopo l'accesso funziona subito. Se l'accesso manca, o l'abbonamento non è attivo, l'errore lo dice. Nessun controllo del sito viene aggirato.

Il formato delle pagine per gli abbonati non è stato verificato senza un account: l'estensione cerca le immagini nelle gallerie (`script.sgb-data`, `[data-images]`) e poi nel contenuto dell'articolo.

## Da dove arrivano i dati

| Schermata | Fonte |
|---|---|
| Popolari | API REST, ordine casuale nuovo a ogni apertura (il sito non ha dati di popolarità) |
| Recenti | API REST per data |
| Ricerca e filtri | API REST: ordine, sezione, tag inclusi (AND) o esclusi |
| Dettagli | API REST con categorie e tag |
| Lettura | pagina dell'articolo, con l'accesso dell'abbonato |

Video e giochi non hanno pagine da leggere e sono esclusi.

## Filtri

- **Ordina per**: Casuale, Data di uscita, Ultimo aggiornamento, Titolo, Pertinenza (con una ricerca testuale).
- **Sezione**: Tutto, Hentai manga, Fumetti porno.
- **Tag**: quelli del sito con almeno 3 titoli. L'elenco viene scaricato dal sito; al primo avvio compare dopo aver toccato "Reimposta".

## Capitoli delle serie

Il sito non collega i capitoli di una serie: l'estensione li riconosce dal titolo ("Titolo 2", "[Parte 1]", "Prima Parte"…) e dall'indirizzo ("-2"), cercando il titolo base (`SeriesTitle.kt`).

## Contenuti esclusi

Solo i titoli che indicano esplicitamente bambini o preadolescenti (loli, shota, preteen, underage, minorenne, bambina/bambino, elementari).
