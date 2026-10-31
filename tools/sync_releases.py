#!/usr/bin/env python3
"""Create a GitHub release for every published extension version that has none.

usage: sync_releases.py DISTRIBUTION_DIR CHANGELOG.md

Tags look like hentaizone-v1.6.6. The APK and JAR come from the distribution
(repo) branch; the notes are the matching "## <name> <version>" section of the
changelog. Needs the gh CLI with GH_TOKEN and GITHUB_REPOSITORY set.
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

for ext in json.loads((dist / "index.min.json").read_text(encoding="utf-8")):
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
