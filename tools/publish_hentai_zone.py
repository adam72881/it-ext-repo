#!/usr/bin/env python3
"""Publish only Hentai Zone while preserving the existing repository index."""
import gzip
import json
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 4:
    raise SystemExit("usage: publish_hentai_zone.py BUILD_DIR DISTRIBUTION_DIR OFFICIAL_SCRIPTS_DIR")

build, dist, official_scripts = map(Path, sys.argv[1:])
sys.path.insert(0, str(official_scripts.resolve()))
from google.protobuf import json_format
import index_pb2

PKG = "eu.kanade.tachiyomi.extension.it.hentaizone"
PRESERVE_PKG = "eu.kanade.tachiyomi.extension.it.hentaiita"
RAW_BASE = "https://raw.githubusercontent.com/adam72881/it-ext-repo/repo"

info = json.loads((build / "keiyoushi-source-info.json").read_text(encoding="utf-8"))
if info.get("packageName") != PKG:
    raise SystemExit("Unexpected package name")
apks = list((build / "outputs/apk/release").glob("*.apk"))
jars = list((build / "outputs/jar/release").glob("*.jar"))
if len(apks) != 1 or len(jars) != 1:
    raise SystemExit("Expected exactly one APK and one JAR")
if int(info["versionCode"]) <= 106002:
    raise SystemExit(f"Refusing non-upgrade versionCode: {info['versionCode']}")

apk_dir, jar_dir = dist / "apk", dist / "jar"
apk_dir.mkdir(parents=True, exist_ok=True)
jar_dir.mkdir(parents=True, exist_ok=True)
shutil.copy2(apks[0], apk_dir / apks[0].name)
shutil.copy2(jars[0], jar_dir / jars[0].name)

index_path = dist / "index.json"
if not index_path.is_file():
    raise SystemExit("Missing index.json; refusing to create a replacement repository index")
index = json_format.Parse(index_path.read_text(encoding="utf-8"), index_pb2.Index())
existing = {ext.packageName: ext for ext in index.extensionList.extensions}
if PRESERVE_PKG not in existing:
    raise SystemExit("Hentai ITA is missing; refusing to publish")
old = existing.get(PKG)
if old is None:
    raise SystemExit("Existing Hentai Zone entry missing; refusing to invent repository metadata")
sources = info.get("sources") or []
if len(sources) != 1:
    raise SystemExit("Expected exactly one Hentai Zone source")
source = sources[0]
if source["lang"] != "it" or source["baseUrl"].rstrip("/") != "https://hentaizone.me":
    raise SystemExit("Unexpected Hentai Zone source metadata")

ext = index_pb2.Extension(
    name=info["name"],
    packageName=PKG,
    resources=index_pb2.Resources(
        apkUrl=f"{RAW_BASE}/apk/{apks[0].name}",
        iconUrl=old.resources.iconUrl,
        jarUrl=f"{RAW_BASE}/jar/{jars[0].name}",
    ),
    extensionLib=info["extensionLib"],
    versionCode=int(info["versionCode"]),
    versionName=info["versionName"],
    contentWarning=int(info["contentWarning"]),
    sources=[index_pb2.Source(
        id=int(source["id"]),
        name=source["name"],
        language=source["lang"],
        homeUrl=source["baseUrl"],
        mirrorUrls=source.get("mirrorUrls", []),
    )],
)
kept = [x for x in index.extensionList.extensions if x.packageName != PKG]
kept.append(ext)
del index.extensionList.extensions[:]
index.extensionList.extensions.extend(sorted(kept, key=lambda x: x.packageName))

# Official Keiyoushi schema + protobuf JSON representation.
index_path.write_text(
    json_format.MessageToJson(
        index,
        always_print_fields_with_no_presence=False,
        preserving_proto_field_name=True,
    ) + "\n",
    encoding="utf-8",
)
(dist / "index.pb").write_bytes(
    gzip.compress(index.SerializeToString(deterministic=True), mtime=0)
)

# Keep the legacy compact index in sync for clients that still consume it.
legacy_path = dist / "index.min.json"
legacy = json.loads(legacy_path.read_text(encoding="utf-8"))
legacy = [x for x in legacy if x.get("pkg") != PKG]
legacy.append({
    "name": f"Tachiyomi: {info['name']}",
    "pkg": PKG,
    "apk": apks[0].name,
    "lang": source["lang"],
    "code": int(info["versionCode"]),
    "version": info["versionName"],
    "nsfw": 1 if int(info["contentWarning"]) == 3 else 0,
    "sources": [{
        "name": source["name"],
        "lang": source["lang"],
        "id": str(source["id"]),
        "baseUrl": source["baseUrl"],
        "versionId": int(source.get("versionId", 1)),
    }],
})
legacy.sort(key=lambda x: (x.get("lang", ""), x.get("name", "")))
legacy_path.write_text(json.dumps(legacy, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")

# Round-trip validation: protobuf gzip, JSON index, and both extension identities.
check = index_pb2.Index()
check.ParseFromString(gzip.decompress((dist / "index.pb").read_bytes()))
packages = {x.packageName: x for x in check.extensionList.extensions}
if packages[PKG].versionCode != int(info["versionCode"]):
    raise SystemExit("Protobuf index version validation failed")
if PRESERVE_PKG not in packages or packages[PRESERVE_PKG].versionCode != 106003:
    raise SystemExit("Hentai ITA changed or missing; refusing publication")
if not (apk_dir / apks[0].name).is_file() or not (jar_dir / jars[0].name).is_file():
    raise SystemExit("APK/JAR copy validation failed")
print(f"Published Hentai Zone {info['versionName']} ({info['versionCode']}); Hentai ITA preserved.")
