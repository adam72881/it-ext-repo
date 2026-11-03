# Passaggio di consegne — Manga ITA (`adam72881/manga-ita-extensions`)

Stato al 1 novembre 2026. Per chi riprende il lavoro (persona o IA): **continua dai file esistenti, non riscrivere le estensioni**.

## Cos'è

Estensioni manga per **Mihon / Tachimanga** (sistema Keiyoushi, Kotlin), compilate e firmate da GitHub Actions.
In app il repository si chiama **«Manga ITA»**.

```
https://raw.githubusercontent.com/adam72881/manga-ita-extensions/repo/index.min.json
```

Le estensioni **anime/video per Mangayomi** non sono qui: stanno in
[mangayomi-anime-extensions](https://github.com/adam72881/mangayomi-anime-extensions), con il loro HANDOFF.

## Estensioni

| Estensione | Sito | Versione | Sorgente | `versionCode` |
|---|---|---|---|---|
| Hentai Zone | hentaizone.me | 1.6.10 | `src/it/hentaizone` | 10 |
| Hentai ITA | hentai-ita.net | 1.6.10 | `src/it/hentaiita` | 10 |
| Hentai Archive | membri.premium-archive.com | 1.6.6 | `src/it/hentaiarchiveita` | 6 |

La versione è `1.6.<versionCode>` (`libVersion = "1.6"` in `build.gradle.kts`).

## Rami

| Ramo | A cosa serve |
|---|---|
| `main` | Codice sorgente, script (`tools/`), workflow (`.github/workflows/`), README, CHANGELOG, HANDOFF. **Un push qui non compila niente.** |
| `hentai-zone-dev`, `hentai-ita-dev`, `hentai-archive-dev` | Rami di build: un push qui compila e pubblica quell'estensione. |
| `repo` | Quello che legge l'app: `index.min.json`, `index.json`, `index.pb`, `repo.json`, `apk/`, `jar/`, `icon/`. Lo scrive la build. |

Tieni i tre rami di build allineati a `main` (fast-forward).

## Pubblicare una nuova versione

1. Modifica il codice in `src/it/<estensione>/` su `main`.
2. Alza `versionCode` in `src/it/<estensione>/build.gradle.kts`.
3. Aggiungi in cima a `CHANGELOG.md` una sezione `## <Nome> <versione>` (es. `## Hentai Zone 1.6.10`): diventa il testo della release.
4. Push su `main`, poi porta lo stesso commit sul ramo di build: `git push origin main:hentai-zone-dev`.
5. La Action controlla il sito (`tools/check_hentai_*_site.py`), compila con il sistema Keiyoushi, firma, aggiorna il ramo `repo` e crea la release su GitHub (una sola per estensione: le vecchie vengono tolte).
6. In app: aggiorna l'elenco estensioni e installa l'aggiornamento.

`tools/publish_extension.py` non ripubblica una versione già presente, rifiuta una versione più bassa e non tocca le altre estensioni.

## Firma

- Chiave nel secret `SIGNING_KEY` del repository (keystore in base64, alias `it-ext`). **Non metterla mai nel repo, che è pubblico.**
- Impronta pubblicata in `repo.json`: `66c46c68b48766609a6840d9a844cad945515be920b5614a753e988f3599bc2f`.
- Su Android serve per aggiornare senza disinstallare; su iPhone (Tachimanga) non conta.

## Regole da rispettare

- Contenuti che indicano esplicitamente bambini o preadolescenti sempre esclusi (`BLOCKED_*` in `Filters.kt` / `Dto.kt`). Teen, school girl e cosplay restano visibili: è stata una richiesta esplicita dell'utente.
- Hentai Archive: l'area membri richiede un abbonamento. L'estensione usa la sessione della WebView e **non aggira** il controllo del sito.
- Niente bypass di Cloudflare o anti-bot.

## In sospeso

- **Pull Request a Keiyoushi**, una per sorgente (la #20586 è stata chiusa e divisa come chiesto dal revisore): [#20648 Hentai ITA](https://github.com/keiyoushi/extensions-source/pull/20648) (ramo `add-hentai-ita`) e [#20649 Hentai Zone](https://github.com/keiyoushi/extensions-source/pull/20649) (ramo `add-hentai-zone`) sul fork `adam72881/extensions-source`. Sono versioni **semplificate** su richiesta del revisore (niente raggruppamento per titolo, niente numero di pagine, Popolari con una sola richiesta). 3 novembre: applicate le seconde richieste (icona stile generatore, `wholeText()`, helper JSON, Hentai Zone scarica la pagina solo per i capitoli). Se chiede altro: correggi sul ramo e fai push, la PR si aggiorna.
- Nessun bug noto aperto sulle tre estensioni.

## Storia

- **1 novembre 2026:** repository rinominato da `it-ext-repo` a `manga-ita-extensions`; estensioni Mangayomi spostate nel loro repo; tolti i rami Instagram e i rami di lavoro temporanei; nome in app «Manga ITA».
- Le novità di ogni versione sono in [CHANGELOG.md](CHANGELOG.md).
