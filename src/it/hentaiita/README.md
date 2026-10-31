# Hentai ITA — sorgente

Modulo Keiyoushi per Mihon/Tachimanga. Sostituisce la versione 1.6.3, di cui nel repository c'erano solo APK e JAR; ID della sorgente e formato degli URL sono gli stessi, quindi la libreria resta valida.

- Sito: https://hentai-ita.net/
- Lingua: italiano
- Libreria: KeiSource / libVersion 1.6
- Pacchetto: eu.kanade.tachiyomi.extension.it.hentaiita

## Da dove arrivano i dati

Tutte le liste usano l'API REST di WordPress (`/wp-json/wp/v2/posts`). Sul sito le categorie fanno da tag, e l'API permette di includerle in AND e di escluderle.

| Schermata | Contenuto |
|---|---|
| Popolari | Il sito non espone dati di popolarità: i commenti sono chiusi e non c'è un contatore di visite. Mostra prima i titoli "best" scelti dal sito, poi il resto del catalogo dal più recente, senza ripetizioni. |
| Recenti | Fumetti dal più recente. Giochi, video e doppioni "-mobile" esclusi; prima erano i giochi ripubblicati a riempire questa pagina. |
| Ricerca e filtri | Ordine, sezione e categorie incluse o escluse. |
| Lettura | L'elenco completo delle pagine dal JSON della galleria (`script.sgb-data`), servito pubblicamente nella pagina del titolo. |

## Filtri

- **Ordina per**: Consigliati dal sito e poi recenti, Data di uscita, Ultimo aggiornamento, Titolo, Pertinenza (con una ricerca testuale).
- **Sezione**: Tutti i fumetti, Manga hentai, Fumetti porno, Doujinshi, 3D porn comics.
- **Categorie**: tutte quelle del sito con almeno 2 titoli. Tocca una volta per includere, due volte per escludere; più categorie incluse si combinano in AND. L'elenco viene scaricato dal sito e tenuto in cache per 3 giorni. Al primo avvio compare dopo aver toccato "Reimposta".

## Pagine "area premium"

Molti titoli recenti mostrano nella scheda "Classic" immagini ospitate in `/area-premium/`, che non sono pubbliche. La stessa pagina ha però una galleria pubblica completa nella scheda "Mobile", e la lettura usa quella. Se un titolo ha solo immagini nell'area premium, compare un messaggio che lo spiega.

## Contenuti esclusi

Sono esclusi solo i contenuti che indicano esplicitamente minori, sia nelle richieste al sito sia sulle schede ricevute:

- la categoria schoolgirls;
- le serie con personaggi canonicamente bambini o liceali: My Hero Academia, Sailor Moon (e le sue sottocategorie), Fantagenitori, Boruto, Doraemon, Ben 10, Gravity Falls, Prison School, High School DxD, High School of the Dead, Teen Titans, Kim Possible, Totally Spies, Winx;
- i titoli che contengono parole come loli, shota, preteen, underage, minorenne, minori, bambina/bambino, child, liceale, elementari.

Queste voci non compaiono tra i filtri e non si possono riattivare. La categoria teen (18-19 anni) e i tag "school" e "scuola" restano visibili; si possono escludere a mano dai filtri.
