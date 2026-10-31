# it-ext

Sorgenti e build delle estensioni italiane per lettori compatibili con Keiyoushi / Mihon / Tachimanga.

## Indirizzo da incollare nell'app

```
https://raw.githubusercontent.com/adam72881/it-ext-repo/repo/index.min.json
```

L'app, se segue il formato attuale, legge anche `repo.json` e `index.pb` nella stessa cartella.

## Estensioni

| Estensione | Sorgente | Ramo che pubblica |
|---|---|---|
| Hentai Zone (hentaizone.me) | `src/it/hentaizone` | `hentai-zone-dev` |
| Hentai ITA (hentai-ita.net) | `src/it/hentaiita` | `hentai-ita-dev` |

Un push su uno di quei rami compila l'estensione con il sistema di build di Keiyoushi e pubblica APK, JAR e indici sul ramo `repo`. Sugli altri rami la workflow compila e fa il lint senza pubblicare. Prima della build, `tools/check_hentai_*_site.py` controlla che il sito abbia ancora la struttura che l'estensione usa.

`tools/publish_extension.py` aggiorna solo l'estensione appena compilata. Si rifiuta di pubblicare se la versione non sale, se cambia l'ID della sorgente o se un'altra estensione dell'indice risulterebbe modificata.

## Chiave di firma

Su iPhone (Tachimanga) la firma non conta: non serve fare niente.

Su Android l'app considera attendibile un'estensione, e la aggiorna senza disinstallarla, solo se l'APK è firmato con la chiave dichiarata in `repo.json`. Senza chiave la build firma con una chiave di debug usa e getta, diversa a ogni build: la pubblicazione avviene comunque, con un avviso nel log.

Per firmare sempre con la stessa chiave basta un secret, in Settings → Secrets and variables → Actions → New repository secret:

- nome: `SIGNING_KEY`
- valore: il contenuto del file `SIGNING_KEY.txt` (il keystore `it-ext.jks` in base64, alias `it-ext`, password `it-ext-key`)

Alla prima pubblicazione firmata l'impronta in `repo.json` e negli indici passa da sola alla nuova chiave. Su Android le estensioni installate prima vanno disinstallate e reinstallate una volta.

Il keystore è la chiave privata del repository: non va mai messo nel repository, che è pubblico.

## File sul ramo `repo`

- `index.min.json` indice legacy
- `repo.json` punta a `index.pb`
- `index.json` stesso indice in JSON
- `index.pb` indice Protocol Buffers compresso
- `apk/`, `jar/` pacchetti
- `icon/` icone
