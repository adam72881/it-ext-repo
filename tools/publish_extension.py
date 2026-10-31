#!/usr/bin/env python3
"""Publish one built extension into the distribution branch, keeping every other entry.

usage: publish_extension.py BUILD_DIR DISTRIBUTION_DIR OFFICIAL_SCRIPTS_DIR APKSIGNER
"""
import gzip
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

if len(sys.argv) != 5:
    raise SystemExit(__doc__)

build, dist, official_scripts, apksigner = sys.argv[1:]
build, dist, official_scripts = Path(build), Path(dist), Path(official_scripts)
sys.path.insert(0, str(official_scripts.resolve()))
from google.protobuf import json_format  # noqa: E402
import index_pb2  # noqa: E402

RAW_BASE = "https://raw.githubusercontent.com/adam72881/it-ext-repo/repo"

info = json.loads((build / "keiyoushi-source-info.json").read_text(encoding="utf-8"))
pkg = info["packageName"]
apks = list((build / "outputs/apk/release").glob("*.apk"))
jars = list((build / "outputs/jar/release").glob("*.jar"))
if len(apks) != 1 or len(jars) != 1:
    raise SystemExit("Expected exactly one APK and one JAR")
sources = info.get("sources") or []
if len(sources) != 1:
    raise SystemExit("Expected exactly one source")
source = sources[0]

index_path = dist / "index.json"
index = json_format.Parse(index_path.read_text(encoding="utf-8"), index_pb2.Index())
before = {ext.packageName: ext.SerializeToString(deterministic=True) for ext in index.extensionList.extensions}
old = next((ext for ext in index.extensionList.extensions if ext.packageName == pkg), None)

# The app only accepts updates signed with the repository key.
certs = subprocess.run([apksigner, "verify", "--print-certs", str(apks[0])], capture_output=True, text=True, check=True).stdout
digest = re.search(r"certificate SHA-256 digest: ([0-9a-f]{64})", certs)
if not digest:
    raise SystemExit("Could not read the APK signing certificate")
if digest.group(1) != index.signingKey:
    raise SystemExit(
        f"APK signed with {digest.group(1)}, repository expects {index.signingKey}. "
        "Add the repository key as the SIGNING_KEY secret (see README) before publishing."
    )

if old is not None and int(info["versionCode"]) <= old.versionCode:
    raise SystemExit(f"Refusing non-upgrade versionCode {info['versionCode']} (published: {old.versionCode})")

apk_dir, jar_dir = dist / "apk", dist / "jar"
apk_dir.mkdir(parents=True, exist_ok=True)
jar_dir.mkdir(parents=True, exist_ok=True)
prefix = apks[0].name.rsplit("-v", 1)[0] + "-v"
for stale in list(apk_dir.glob(prefix + "*.apk")) + list(jar_dir.glob(prefix + "*.jar")):
    stale.unlink()
shutil.copy2(apks[0], apk_dir / apks[0].name)
shutil.copy2(jars[0], jar_dir / jars[0].name)

ext = index_pb2.Extension(
    name=info["name"],
    packageName=pkg,
    resources=index_pb2.Resources(
        apkUrl=f"{RAW_BASE}/apk/{apks[0].name}",
        iconUrl=old.resources.iconUrl if old is not None else f"{RAW_BASE}/icon/{pkg}.png",
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
if old is not None and int(source["id"]) != old.sources[0].id:
    raise SystemExit("Source id changed; existing libraries would break")

kept = [x for x in index.extensionList.extensions if x.packageName != pkg]
kept.append(ext)
del index.extensionList.extensions[:]
index.extensionList.extensions.extend(sorted(kept, key=lambda x: x.packageName))

index_path.write_text(
    json_format.MessageToJson(index, always_print_fields_with_no_presence=False, preserving_proto_field_name=True) + "\n",
    encoding="utf-8",
)
(dist / "index.pb").write_bytes(gzip.compress(index.SerializeToString(deterministic=True), mtime=0))

legacy_path = dist / "index.min.json"
legacy = [x for x in json.loads(legacy_path.read_text(encoding="utf-8")) if x.get("pkg") != pkg]
legacy.append({
    "name": f"Tachiyomi: {info['name']}",
    "pkg": pkg,
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

# Round trip: this package updated, every other package byte-identical.
check = index_pb2.Index()
check.ParseFromString(gzip.decompress((dist / "index.pb").read_bytes()))
after = {x.packageName: x for x in check.extensionList.extensions}
if after[pkg].versionCode != int(info["versionCode"]):
    raise SystemExit("Protobuf index validation failed")
for other, data in before.items():
    if other != pkg and after[other].SerializeToString(deterministic=True) != data:
        raise SystemExit(f"{other} changed unexpectedly; refusing publication")
print(f"Published {info['name']} {info['versionName']} ({info['versionCode']}); kept {sorted(set(before) - {pkg})}.")
