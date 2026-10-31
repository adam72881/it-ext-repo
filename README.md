<div align="center">

# it-ext

**Estensioni italiane per adulti per Mihon / Tachimanga**

Sorgenti hentai in italiano, compilate e firmate in automatico, pronte da aggiungere al tuo lettore manga.

🔞 Solo per maggiorenni · 🇮🇹 In italiano · 🔄 Aggiornate da sole

</div>

---

## Installazione

Copia questo indirizzo:

```
https://raw.githubusercontent.com/adam72881/it-ext-repo/repo/index.min.json
```

**Tachimanga (iPhone)** → Sfoglia → Estensioni → impostazioni → *Repository estensioni* → **+** → incolla l'indirizzo.

**Mihon / Komikku (Android)** → Altro → Impostazioni → Estensioni → *Repository estensioni* → **+** → incolla l'indirizzo.

Poi torna in Estensioni, aggiorna l'elenco e installa quelle che vuoi.

---

## Estensioni disponibili

<table>
  <tr>
    <th>Logo</th><th>Estensione</th><th>Sito</th><th>Contenuto</th>
  </tr>
  <tr>
    <td align="center"><img src="https://raw.githubusercontent.com/adam72881/it-ext-repo/repo/icon/eu.kanade.tachiyomi.extension.it.hentaizone.png" width="44"></td>
    <td><b>Hentai Zone</b></td>
    <td><a href="https://hentaizone.me">hentaizone.me</a></td>
    <td>Gratuito, con titoli Premium leggibili dopo l'accesso Patreon</td>
  </tr>
  <tr>
    <td align="center"><img src="https://raw.githubusercontent.com/adam72881/it-ext-repo/repo/icon/eu.kanade.tachiyomi.extension.it.hentaiita.png" width="44"></td>
    <td><b>Hentai ITA</b></td>
    <td><a href="https://hentai-ita.net">hentai-ita.net</a></td>
    <td>Gratuito</td>
  </tr>
  <tr>
    <td align="center"><img src="https://raw.githubusercontent.com/adam72881/it-ext-repo/repo/icon/eu.kanade.tachiyomi.extension.it.hentaiarchiveita.png" width="44"></td>
    <td><b>Hentai Archive</b></td>
    <td><a href="https://membri.premium-archive.com">area membri</a></td>
    <td>Richiede un abbonamento (accesso dalla WebView)</td>
  </tr>
</table>

Tutte in italiano, con Popolari, Recenti, ricerca, filtri per categoria/tag e capitoli delle serie. Le novità di ogni versione sono nel [CHANGELOG](CHANGELOG.md).

### Una nota sui contenuti

I contenuti che indicano esplicitamente bambini o preadolescenti (loli, shota, preteen, serie con protagonisti minorenni…) sono **sempre esclusi** e non si possono riattivare dai filtri.

---

## Come funziona

Il repository si costruisce da solo. Ogni estensione ha il suo codice sorgente in `src/it/<nome>`; un push sul ramo di pubblicazione la compila con il sistema di build di [Keiyoushi](https://github.com/keiyoushi/extensions-source) e mette APK, JAR e indici aggiornati sul ramo `repo`, quello che l'app legge.

| Estensione | Sorgente | Ramo che pubblica |
|---|---|---|
| Hentai Zone | `src/it/hentaizone` | `hentai-zone-dev` |
| Hentai ITA | `src/it/hentaiita` | `hentai-ita-dev` |
| Hentai Archive | `src/it/hentaiarchiveita` | `hentai-archive-dev` |

<details>
<summary><b>Dettagli tecnici</b> (firma, pubblicazione, file dell'indice)</summary>

<br>

**Controlli prima della build.** `tools/check_hentai_*_site.py` verifica che il sito abbia ancora la struttura che l'estensione usa; se è cambiata, la build si ferma invece di pubblicare qualcosa di rotto.

**Pubblicazione.** `tools/publish_extension.py` aggiorna solo l'estensione appena compilata: non tocca l'indice se la versione è già pubblicata, e rifiuta di pubblicare se la versione scende, se cambia l'ID della sorgente o se un'altra estensione dell'indice risulterebbe modificata. Poi `tools/sync_releases.py` crea la release su GitHub (tag come `hentaizone-v1.6.8`) con APK, JAR e le novità dal CHANGELOG.

**Chiave di firma.** Su iPhone (Tachimanga) la firma non conta. Su Android l'app aggiorna un'estensione senza disinstallarla solo se l'APK è firmato con la chiave dichiarata in `repo.json`. La chiave sta nel secret `SIGNING_KEY` del repository (keystore in base64); senza, la build firma con una chiave usa e getta e avvisa nel log. Il keystore è privato e non va mai messo nel repository, che è pubblico.

**File sul ramo `repo`:**

- `index.min.json` — indice (formato legacy)
- `index.json` — stesso indice in JSON
- `index.pb` — indice Protocol Buffers compresso
- `repo.json` — punta a `index.pb` e dichiara la chiave di firma
- `apk/`, `jar/` — pacchetti delle estensioni
- `icon/` — icone

</details>

---

<div align="center">
<sub>Progetto non ufficiale, senza alcun legame con i siti sorgente né con Mihon/Tachimanga.</sub>
</div>
