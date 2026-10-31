#!/usr/bin/env python3
"""Keep exactly one GitHub release per extension: the version in the index.

usage: sync_releases.py DISTRIBUTION_DIR CHANGELOG.md

Creates the release for each published version that has none, and deletes every
other release (older versions, or extensions no longer in the index) together
with its tag. Tags look like hentaizone-v1.6.8. The APK and JAR come from the
distribution (repo) branch; the notes are the matching "## <name> <version>"
section of the changelog. Runs inside the Action with GH_TOKEN set.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

dist, changelog = Path(sys.argv[1]), Path(sys.argv[2]).read_text(encoding="utf-8")
REPO_URL = "https://raw.githubusercontent.com/adam72881/it-ext-repo/repo/index.min.json"

existing = set(subprocess.run(
    ["gh", "release", "list", "--limit", "200", "--json", "tagName", "--jq", ".[].tagName"],
    capture_output=True, text=True, check=True,
).stdout.split())

extensions = json.loads((dist / "index.min.json").read_text(encoding="utf-8"))
# The one release each extension should keep: its current version in the index.
wanted = {f"{ext['pkg'].rsplit('.', 1)[-1]}-v{ext['version']}" for ext in extensions}

# Remove older versions and releases of extensions that left the index.
for tag in sorted(existing - wanted):
    subprocess.run(["gh", "release", "delete", tag, "--cleanup-tag", "--yes"], check=True)
    print(f"Deleted release {tag}")

for ext in extensions:
    name = ext["name"].removeprefix("Tachiyomi: ")
    module = ext["pkg"].rsplit(".", 1)[-1]
    tag = f"{module}-v{ext['version']}"
    if tag in existing:
        continue
    section = re.search(
        rf"^## {re.escape(name)} {re.escape(ext['version'])}\n(.*?)(?=^## |\Z)", changelog, re.M | re.S,
    )
    notes = (section.group(1).strip() + "\n\n") if section else ""
    notes += f"Per installarla aggiungi in Tachimanga o Mihon il repository:\n\n```\n{REPO_URL}\n```"
    apk = dist / "apk" / ext["apk"]
    jar = dist / "jar" / (ext["apk"].removesuffix(".apk") + ".jar")
    created = subprocess.run(
        ["gh", "release", "create", tag, str(apk), str(jar),
         "--title", f"{name} {ext['version']}", "--notes", notes, "--target", "repo"],
    )
    # The other extension's workflow may have just created it.
    if created.returncode != 0:
        subprocess.run(["gh", "release", "view", tag], check=True, capture_output=True)
        print(f"Release {tag} already exists")
    else:
        print(f"Created release {tag}")
