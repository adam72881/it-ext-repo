# Hentai Zone — sorgente

Modulo Keiyoushi per Mihon/Tachimanga, separato da Hentai ITA.

- Sito: https://hentaizone.me/
- Catalogo: https://hentaizone.me/hentai-manga/
- Pagina gratuita di prova: https://hentaizone.me/lezioni-di-fitness/
- Login ufficiale: https://hentaizone.me/wp-login.php
- Lingua: italiano
- Libreria: KeiSource / libVersion 1.6
- Pacchetto: eu.kanade.tachiyomi.extension.it.hentaizone

## Confronto dei parser

Il driver pubblico Yamete per Hentai Zone usa la galleria `.fg_thumbnail a` come elenco dei collegamenti alle immagini originali; il test pubblico del driver su `/mind-control-ch-4/` si aspetta 16 immagini. Il parser Kotlin usa lo stesso selettore per il lettore.

Il driver Yamete per Hentai ITA usa invece `.blocks-gallery-item figure a` e apre ogni pagina allegata per leggere `.entry-attachment img`. Sono strutture diverse: non è corretto copiare alla cieca il selettore di Hentai ITA dentro Hentai Zone.

Il codice sorgente di Hentai ITA non è presente in questo repository: sono disponibili il suo APK/JAR e i metadati di distribuzione. Il confronto del parser è quindi basato sul driver pubblico Yamete e non su un confronto sorgente-a-sorgente dei due APK.

## Catalogo e lettura

- Catalogo WordPress su `/hentai-manga/`, con paginazione.
- Ricerca WordPress tramite `s`, `post_type` e `paged`.
- Il catalogo usa card WordPress/Elementor; il lettore estrae le immagini del plugin GFFolder da `.gff-item img` e dagli URL `/wp-content/uploads/gffolder-cache/`.
- I post senza un elenco di capitoli sono trattati come letture singole.
- Le pagine premium non vengono sbloccate né aggirate.

## Login

La sorgente si apre sul catalogo gratuito. Il login ufficiale è disponibile dal sito tramite `/wp-login.php`; la condivisione dei cookie WebView → OkHttp non è stata verificata, quindi il login non va considerato funzionante finché non viene provato in Mihon.

## Filtri

Il parser esclude risultati con etichette esplicite come loli, shota, underage, minorenne e teen. I metadati da soli non garantiscono che tutte le opere con età ambigua siano etichettate correttamente; non è possibile promettere un filtro perfetto senza revisione dei contenuti.

## Controlli prima della build

La workflow esegue prima una verifica HTTP del catalogo e della pagina gratuita di prova, controlla che il markup della galleria sia presente, valida lo schema Protocol Buffers e il publisher. Solo se questi controlli passano avvia la compilazione.

La pubblicazione genera APK, JAR, `index.json`, `index.min.json` e `index.pb`. L'indice `index.pb` viene serializzato come Protocol Buffers e poi compresso GZIP, seguendo lo schema ufficiale Keiyoushi; non viene più creato comprimendo JSON.
