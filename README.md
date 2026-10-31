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

`tools/publish_extension.py` aggiorna solo l'estensione appena compilata. Si rifiuta di pubblicare se la versione non sale, se cambia l'ID della sorgente, se l'APK non è firmato con la chiave del repository o se un'altra estensione dell'indice risulterebbe modificata.

## Chiave di firma

Il repository dichiara la chiave `0708bb69…4a9a85` (`signingKeyFingerprint` in `repo.json`). L'app accetta un aggiornamento solo se l'APK nuovo è firmato con la stessa chiave di quello installato.

Senza chiave la build firma con una chiave di debug usa e getta, diversa a ogni build: per questo la pubblicazione si blocca finché non sono impostati questi secret, in Settings → Secrets and variables → Actions:

| Secret | Valore |
|---|---|
| `SIGNING_KEY` | il keystore `.jks` codificato in base64 (`base64 -w0 chiave.jks`) |
| `ALIAS` | l'alias della chiave nel keystore |
| `KEY_STORE_PASSWORD` | la password del keystore |
| `KEY_PASSWORD` | la password della chiave |

La chiave `0708bb…` è un certificato "Android Debug": di solito sta in `~/.android/debug.keystore` sul computer dove è stato compilato Hentai ITA, con alias `androiddebugkey` e password `android`.

Se quella chiave non c'è più, se ne crea una nuova e si aggiorna l'impronta in `repo.json` e `index.json` sul ramo `repo`. Su Android le estensioni vanno poi disinstallate e reinstallate una volta.

## File sul ramo `repo`

- `index.min.json` indice legacy
- `repo.json` punta a `index.pb`
- `index.json` stesso indice in JSON
- `index.pb` indice Protocol Buffers compresso
- `apk/`, `jar/` pacchetti
- `icon/` icone
