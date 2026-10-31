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
| Più pagine / Pagine minime | indice `hentaizone-pages.json` sul ramo `data`, rigenerato ogni giorno da `tools/build_page_index.py` | numero di pagine |
| Lettura | pagina dell'articolo, elenco immagini da `.gff-gallery[data-images]` | — |

Una pagina "Popolari" dell'app corrisponde a due pagine dell'archivio, così le esclusioni non lasciano pagine vuote che fermerebbero lo scorrimento.

## Filtri

- **Ordina per**: Popolari (più commentati), Data di uscita, Ultimo aggiornamento, Titolo, Pertinenza (con una ricerca testuale), Più pagine. Crescente o decrescente.
- **Pagine minime**: Qualsiasi, 20+, 40+, 60+, 100+, 200+.
- **Sezione**: Manga hentai, Doujinshi, Fumetti porno, Tutti i fumetti.
- **Accesso**: Tutti, Solo gratuiti, Solo Premium.
- **Categorie** e **Tag**: tocca una volta per includere, due volte per escludere. Più voci incluse si combinano in AND. L'elenco viene scaricato dal sito e tenuto in cache per 3 giorni. Al primo avvio compare dopo aver toccato "Reimposta".

## Capitoli e pagine

Ogni capitolo di una serie è un articolo a sé. La pagina dell'articolo elenca tutti i capitoli della serie (`ul.hzc-list`), quindi aprendo un titolo l'app mostra tutti i capitoli. Un titolo che fa parte di una serie viene ricontrollato agli aggiornamenti della libreria, così i capitoli nuovi compaiono da soli; i titoli singoli no.

Il sito non indica il numero di pagine negli elenchi, ma l'API restituisce la galleria di ogni articolo. `tools/build_page_index.py` le conta tutte una volta al giorno (workflow `page-index.yml`, circa 50 richieste) e pubblica l'indice sul ramo `data`. "Più pagine" e "Pagine minime" leggono quell'indice e si combinano con sezione, accesso, categorie, tag e ricerca nel titolo. I titoli Premium non ci sono, perché senza accesso il sito non ne mostra la galleria; i titoli usciti dopo l'ultimo aggiornamento compaiono il giorno dopo.

## Premium

I titoli Premium compaiono nelle liste insieme a quelli gratuiti. Nei dettagli sono segnati come Premium, e il capitolo si chiama "Capitolo unico (Premium)".

Il sito mostra le pagine di un titolo Premium solo a chi ha effettuato l'accesso con un account Patreon abbonato. L'app condivide i cookie della WebView con la sorgente, quindi basta:

1. aprire un titolo Premium e toccare l'icona del mappamondo (WebView);
2. toccare «Entra con Patreon» e completare l'accesso;
3. tornare indietro e aprire il capitolo.

La lettura rilegge sempre la pagina dal sito, senza cache, così dopo l'accesso funziona subito. Se l'accesso manca, o l'abbonamento non è attivo, l'errore lo dice. Nessun controllo del sito viene aggirato: le pagine arrivano solo se il sito le serve all'account collegato.

## Contenuti esclusi

Sono esclusi solo i contenuti che indicano esplicitamente minori, sia nelle richieste al sito sia sulle schede ricevute:

- tag: school girl, liceale, uniforme scolastica, Fantagenitori, Teen Titans;
- categorie di serie con personaggi canonicamente minorenni: My Hero Academia, Sailor Moon;
- titoli che contengono loli, shota, preteen, underage, minorenne, bambina/bambino, liceale/liceali, elementari.

Queste voci non compaiono tra i filtri e non si possono riattivare. La categoria Teen (18-19 anni) e i tag su università e studenti restano visibili; si possono escludere a mano dai filtri.

Video (Video Hentai Sub Ita) e giochi non hanno pagine da leggere e sono esclusi.

## Controlli prima della build

`tools/check_hentai_zone_site.py` verifica l'API REST (paginazione ed esclusioni), l'archivio ordinato per commenti con le classi delle categorie sulle schede e la galleria `data-images` di un articolo gratuito. Solo se questi controlli passano parte la compilazione.

La build usa la workflow comune `build-extension.yml` e pubblica sul ramo `repo` solo dai push su `hentai-zone-dev`. Sugli altri rami compila e fa il lint senza pubblicare.
