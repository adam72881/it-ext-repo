#!/usr/bin/env python3
import json
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit("usage: publish_hentai_zone.py BUILD_DIR DISTRIBUTION_DIR")
build, dist = map(Path, sys.argv[1:])
info = json.loads((build / "keiyoushi-source-info.json").read_text(encoding="utf-8"))
pkg = "eu.kanade.tachiyomi.extension.it.hentaizone"
if info.get("packageName") != pkg:
    raise SystemExit("Unexpected package name")
apks = list((build / "outputs/apk/release").glob("*.apk"))
jars = list((build / "outputs/jar/release").glob("*.jar"))
if len(apks) != 1 or len(jars) != 1:
    raise SystemExit("Expected exactly one APK and one JAR")
(dist / "apk").mkdir(parents=True, exist_ok=True)
(dist / "jar").mkdir(parents=True, exist_ok=True)
shutil.copy2(apks[0], dist / "apk" / apks[0].name)
shutil.copy2(jars[0], dist / "jar" / jars[0].name)
index_path = dist / "index.min.json"
if not index_path.is_file():
    raise SystemExit("Missing index.min.json; refusing to replace repository index")
index = json.loads(index_path.read_text(encoding="utf-8"))
if not isinstance(index, list):
    raise SystemExit("Unexpected index.min.json format")
sources = info.get("sources") or []
if len(sources) != 1:
    raise SystemExit("Expected exactly one Hentai Zone source")
s = sources[0]
entry = {
    "name": "Tachiyomi: " + info["name"],
    "pkg": pkg,
    "apk": apks[0].name,
    "lang": s["lang"],
    "code": int(info["versionCode"]),
    "version": info["versionName"],
    "nsfw": 1 if int(info["contentWarning"]) == 3 else 0,
    "sources": [{
        "name": s["name"],
        "lang": s["lang"],
        "id": str(s["id"]),
        "baseUrl": s["baseUrl"],
        "versionId": int(s.get("versionId", 1)),
    }],
}
# Replace only Hentai Zone; preserve Hentai ITA and all other entries.
index = [x for x in index if x.get("pkg") != pkg]
index.append(entry)
index.sort(key=lambda x: (x.get("lang", ""), x.get("name", "")))
index_path.write_text(json.dumps(index, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
packages = {x.get("pkg") for x in json.loads(index_path.read_text(encoding="utf-8"))}
if pkg not in packages or "eu.kanade.tachiyomi.extension.it.hentaiita" not in packages:
    raise SystemExit("Index validation failed; both Hentai Zone and Hentai ITA must be present")
print("APK, JAR and index.min.json prepared; Hentai ITA entry preserved.")
