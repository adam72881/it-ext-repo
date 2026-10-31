# Hentai Zone — sorgente

Modulo Keiyoushi per Mihon/Tachimanga, separato da Hentai ITA.

- Sito: https://hentaizone.me/
- Catalogo free: https://hentaizone.me/hentai-manga/
- Login ufficiale: https://hentaizone.me/wp-login.php
- Paginazione catalogo verificata: `/hentai-manga/page/2/` e archivio fino a pagina 220.
- Lingua: italiano
- Libreria: KeiSource / libVersion 1.6
- Pacchetto previsto: eu.kanade.tachiyomi.extension.it.hentaizone

## Modalità Free e Premium

- Il catalogo pubblico e i contenuti gratuiti sono la modalità predefinita.
- Per accedere a contenuti che richiedono un account, aprire la sorgente in Mihon e usare l'icona WebView (oppure il comando "Apri in WebView"). La pagina iniziale della WebView è impostata su `/wp-login.php`.
- Il login ufficiale offre l'accesso tramite Patreon e l'accesso con username/password Hentai Zone. Il sito dichiara che le credenziali Hentai Zone vengono fornite dopo l'attivazione dell'abbonamento Patreon.
- Dopo il login, tornare alla sorgente e riprovare il capitolo. La sessione è gestita dai cookie del sito e non viene richiesta o salvata dall'estensione. Le richieste dell'estensione usano il cookie jar della sorgente; non vengono memorizzate password nell'estensione.
- I capitoli premium restano soggetti ai controlli del sito: l'estensione non aggira abbonamenti, OAuth o restrizioni.

## Filtri di sicurezza

Il parser esclude i risultati i cui testi/tag espongono esplicitamente etichette come loli, shota, underage, minorenne e teen. Un filtro basato sui soli metadati non può garantire che ogni opera non etichettata sia priva di personaggi dall'età ambigua; il catalogo va verificato prima della pubblicazione.

## Stato e build

Il parser è una prima implementazione e deve ancora essere compilato e provato in Mihon. Questo repository di distribuzione non contiene il wrapper Gradle e il progetto root necessari: il modulo va compilato all'interno di `keiyoushi/extensions-source`.
