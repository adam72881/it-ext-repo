#!/usr/bin/env python3
import gzip
import json
import shutil
import sys
from pathlib import Path
from google.protobuf import json_format
import index_pb2

if len(sys.argv) != 3:
    raise SystemExit("usage: publish_hentai_zone.py BUILD_DIR DISTRIBUTION_DIR")

build = Path(sys.argv[1])
dist = Path(sys.argv[2])
info_path = build / "keiyoushi-source-info.json"
if not info_path.is_file():
    raise SystemExit(f"Missing build metadata: {info_path}")
info = json.loads(info_path.read_text(encoding="utf-8"))

apk_files = list((build / "outputs/apk/release").glob("*.apk"))
jar_files = list((build / "outputs/jar/release").glob("*.jar"))
if len(apk_files) != 1 or len(jar_files) != 1:
    raise SystemExit(f"Expected exactly one APK and one JAR; found {len(apk_files)} APK, {len(jar_files)} JAR")

package_name = info["packageName"]
if package_name != "eu.kanade.tachiyomi.extension.it.hentaizone":
    raise SystemExit(f"Unexpected package name: {package_name}")

apk_name = apk_files[0].name
jar_name = jar_files[0].name
apk_dir, jar_dir = dist / "apk", dist / "jar"
apk_dir.mkdir(parents=True, exist_ok=True)
jar_dir.mkdir(parents=True, exist_ok=True)
shutil.copy2(apk_files[0], apk_dir / apk_name)
shutil.copy2(jar_files[0], jar_dir / jar_name)

base = "https://raw.githubusercontent.com/adam72881/it-ext-repo/repo"
icon_url = "https://cdn.jsdelivr.net/gh/keiyoushi/extensions-source@main/core/src/main/res/mipmap-xhdpi/ic_launcher.png"

index_json_path = dist / "index.json"
if not index_json_path.is_file():
    raise SystemExit("Missing existing index.json; refusing to replace the repository index")
index = json_format.Parse(index_json_path.read_text(encoding="utf-8"), index_pb2.Index())
if not index.HasField("extensionList"):
    raise SystemExit("Existing index.json has no extensionList; refusing to publish")
extensions = [e for e in index.extensionList.extensions if e.packageName != package_name]
ext = index_pb2.Extension(
    name=info["name"],
    packageName=package_name,
    resources=index_pb2.Resources(
        apkUrl=f"{base}/apk/{apk_name}",
        jarUrl=f"{base}/jar/{jar_name}",
        iconUrl=icon_url,
    ),
    extensionLib=info["extensionLib"],
    versionCode=int(info["versionCode"]),
    versionName=info["versionName"],
    contentWarning=int(info["contentWarning"]),
)
for source in info["sources"]:
    ext.sources.add(
        id=int(source["id"]),
        name=source["name"],
        language=source["lang"],
        homeUrl=source["baseUrl"],
        mirrorUrls=source.get("mirrorUrls", []),
    )
extensions.append(ext)
del index.extensionList.extensions[:]
index.extensionList.extensions.extend(sorted(extensions, key=lambda e: e.packageName))

json_bytes = json_format.MessageToJson(
    index,
    preserving_proto_field_name=True,
    indent=2,
).encode("utf-8") + b"\n"
index_json_path.write_bytes(json_bytes)
(dist / "index.pb").write_bytes(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

# Keep the legacy JSON index in sync as well, without dropping Hentai ITA.
legacy_path = dist / "index.min.json"
legacy = json.loads(legacy_path.read_text(encoding="utf-8")) if legacy_path.exists() else []
legacy = [e for e in legacy if e.get("pkg") != package_name]
legacy.append({
    "name": f"Tachiyomi: {info['name']}",
    "pkg": package_name,
    "apk": apk_name,
    "lang": info["sources"][0]["lang"],
    "code": int(info["versionCode"]),
    "version": info["versionName"],
    "nsfw": 1 if int(info["contentWarning"]) == 3 else 0,
    "sources": [{
        "name": s["name"],
        "lang": s["lang"],
        "id": str(s["id"]),
        "baseUrl": s["baseUrl"],
        "versionId": 1,
    } for s in info["sources"]],
})
legacy.sort(key=lambda e: (e.get("lang", ""), e.get("name", "")))
legacy_path.write_text(json.dumps(legacy, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")

# Read back the generated protobuf to catch malformed output before committing.
round_trip = index_pb2.Index()
round_trip.ParseFromString(gzip.decompress((dist / "index.pb").read_bytes()))
if not any(e.packageName == package_name for e in round_trip.extensionList.extensions):
    raise SystemExit("Generated index.pb failed its round-trip validation")
if not any(e.packageName == "eu.kanade.tachiyomi.extension.it.hentaiita" for e in round_trip.extensionList.extensions):
    raise SystemExit("Hentai ITA disappeared from the protobuf index")
print(f"Published {package_name} {info['versionName']} with APK and JAR; protobuf index validated.")
