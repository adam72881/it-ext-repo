# Hentai Archive — sorgente

Modulo Keiyoushi per Mihon/Tachimanga.

- Sito: https://www.hentai-archive.com/
- Lingua: italiano
- Libreria: KeiSource / libVersion 1.6
- Pacchetto: eu.kanade.tachiyomi.extension.it.hentaiarchiveita

Keiyoushi ha già una sua estensione "HentaiArchive" (pacchetto `…it.hentaiarchive`), molto più semplice: questa usa un pacchetto diverso, così le due non vanno in conflitto.

## Da dove arrivano i dati

Il sito ha l'API REST di WordPress disattivata, quindi l'estensione legge le pagine HTML pubbliche.

| Schermata | Fonte |
|---|---|
| Popolari | archivio della categoria ordinato per numero di commenti (`?orderby=comment_count`) |
| Recenti | archivio della categoria per data |
| Ricerca | ricerca del sito limitata ai manga (`category_name`), senza video |
| Dettagli | pagina del titolo: categorie, data e copertina dai dati strutturati (JSON-LD) |
| Lettura | le immagini dentro l'articolo, servite da una CDN che vuole il sito come Referer |

Gli archivi hanno 9 titoli per pagina: ogni pagina dell'app ne legge due.

## Filtri

- **Ordina per**: Popolari (più commentati), Data di uscita, Titolo, Casuale. Crescente o decrescente.
- **Sezione**: Tutti i manga, Doujinshi, Fumetti porno, Fumetti porno 3D, Volumi.
- **Categoria**: una delle 73 categorie del sito. Il sito non permette di combinarle: più categorie insieme vengono ridotte a una. Se scegli una categoria, vale quella invece della sezione.

## Capitoli delle serie

Il sito non collega i capitoli di una serie: sono articoli separati con lo stesso titolo base e un segno finale ("Titolo 2", "Titolo [Parte 1]", "Titolo – Prima Parte"), a volte con lo stesso identico titolo e un "-2" nell'indirizzo. Aprendo un titolo, l'estensione cerca il titolo base (due pagine di risultati) e mostra come capitoli gli articoli con la stessa base (`SeriesTitle.kt`).

## Contenuti esclusi

Solo i contenuti che indicano esplicitamente bambini o preadolescenti: la categoria Sailor Moon (protagoniste alle medie) e i titoli con loli, shota, preteen, underage, minorenne, bambina/bambino, elementari. I video non hanno pagine da leggere e sono esclusi.
