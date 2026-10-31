# Hentai Zone — sorgente

Modulo Keiyoushi per Mihon/Tachimanga, separato da Hentai ITA.

- Sito: https://hentaizone.me/
- Lingua: italiano
- Libreria: KeiSource / libVersion 1.6
- Pacchetto: eu.kanade.tachiyomi.extension.it.hentaizone

## Da dove arrivano i dati

| Schermata | Fonte | Ordine |
|---|---|---|
| Popolari | archivio HTML della categoria (`?category_name=…&orderby=comment_count`) | più commentati, l'unico dato di popolarità che il sito espone |
| Recenti | API REST di WordPress (`/wp-json/wp/v2/posts`) | data di uscita |
| Ricerca e filtri | API REST, oppure archivio HTML se l'ordine è "Popolari" | a scelta |
| Dettagli | API REST con categorie e tag (`_embed=wp:term`), più la pagina del titolo per l'elenco dei capitoli della serie | — |
| Lettura | pagina dell'articolo, elenco immagini da `.gff-gallery[data-images]` | — |

Una pagina "Popolari" dell'app corrisponde a due pagine dell'archivio, così le esclusioni non lasciano pagine vuote che fermerebbero lo scorrimento.

## Filtri

- **Ordina per**: Popolari (più commentati), Data di uscita, Ultimo aggiornamento, Titolo, Pertinenza (con una ricerca testuale). Crescente o decrescente.
- **Sezione**: Manga hentai, Doujinshi, Fumetti porno, Tutti i fumetti.
- **Accesso**: Tutti, Solo gratuiti, Solo Premium.
- **Categorie** e **Tag**: tocca una volta per includere, due volte per escludere. Più voci incluse si combinano in AND. L'elenco viene scaricato dal sito e tenuto in cache per 3 giorni. Al primo avvio compare dopo aver toccato "Reimposta".

## Capitoli

Ogni capitolo di una serie è un articolo a sé. La pagina dell'articolo elenca tutti i capitoli della serie (`ul.hzc-list`), quindi aprendo un titolo l'app mostra tutti i capitoli. Un titolo che fa parte di una serie viene ricontrollato agli aggiornamenti della libreria, così i capitoli nuovi compaiono da soli; i titoli singoli no.

## Premium

I titoli Premium compaiono nelle liste insieme a quelli gratuiti. Nei dettagli sono segnati come Premium, e il capitolo si chiama "Capitolo unico (Premium)".

Il sito mostra le pagine di un titolo Premium solo a chi ha effettuato l'accesso con un account Patreon abbonato. L'app condivide i cookie della WebView con la sorgente, quindi basta:

1. aprire un titolo Premium e toccare l'icona del mappamondo (WebView);
2. toccare «Entra con Patreon» e completare l'accesso;
3. tornare indietro e aprire il capitolo.

La lettura rilegge sempre la pagina dal sito, senza cache, così dopo l'accesso funziona subito. Se l'accesso manca, o l'abbonamento non è attivo, l'errore lo dice. Nessun controllo del sito viene aggirato: le pagine arrivano solo se il sito le serve all'account collegato.

## Contenuti esclusi

Sono esclusi solo i contenuti che indicano esplicitamente bambini o preadolescenti, sia nelle richieste al sito sia sulle schede ricevute:

- il tag Fantagenitori e la categoria Sailor Moon (protagonisti bambini o alle medie);
- titoli che contengono loli, shota, preteen, underage, minorenne, bambina/bambino, elementari.

Queste voci non compaiono tra i filtri e non si possono riattivare. Teen, school girl, liceale, uniforme scolastica e i tag su scuola e studenti restano visibili, perché indicano spesso un costume o un'ambientazione e non l'età; si possono escludere a mano dai filtri.

Video (Video Hentai Sub Ita) e giochi non hanno pagine da leggere e sono esclusi.

## Controlli prima della build

`tools/check_hentai_zone_site.py` verifica l'API REST (paginazione ed esclusioni), l'archivio ordinato per commenti con le classi delle categorie sulle schede e la galleria `data-images` di un articolo gratuito. Solo se questi controlli passano parte la compilazione.

La build usa la workflow comune `build-extension.yml` e pubblica sul ramo `repo` solo dai push su `hentai-zone-dev`. Sugli altri rami compila e fa il lint senza pubblicare.
