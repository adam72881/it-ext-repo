# Hentai Zone — sorgente

Modulo Keiyoushi per Mihon/Tachimanga, separato da Hentai ITA.

- Sito: https://hentaizone.me/
- Lingua: italiano
- Libreria: KeiSource / libVersion 1.6
- Pacchetto previsto: eu.kanade.tachiyomi.extension.it.hentaizone

## Stato tecnico

Il catalogo pubblico del sito espone la sezione `/hentai-manga/` con paginazione (la pagina verificata mostra 220 pagine) e una pagina di ricerca avanzata.

La pagina dettaglio campionata presenta il pulsante di accesso tramite Patreon. Il lettore non è stato verificato come contenuto pubblico. Il codice non aggira OAuth, login o contenuti riservati: restituisce le immagini solo quando il sito le espone direttamente nella pagina del capitolo.

## Filtri di sicurezza

Il parser esclude i risultati i cui testi/tag espongono esplicitamente etichette come loli, shota, underage, minorenne e teen. Un filtro basato sui soli metadati non può garantire che ogni opera non etichettata sia priva di personaggi dall'età ambigua; per questo il catalogo deve essere verificato prima della pubblicazione.

## Build

Questo modulo va compilato all'interno del progetto Keiyoushi extensions-source (guida: https://github.com/keiyoushi/extensions-source/blob/main/CONTRIBUTING.md). Il repository di distribuzione `it-ext-repo` non contiene al momento il wrapper Gradle e il progetto root necessari a produrre APK/JAR.
