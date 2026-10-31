#!/usr/bin/env python3
"""Checks the parts of Hentai Archive's members site the extension depends on before building."""
import json
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE = "https://membri.premium-archive.com"
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"


def fetch(url):
    with urlopen(Request(url, headers={"User-Agent": UA}), timeout=30) as response:
        return response.headers, response.read().decode("utf-8", "replace")


def fail(message):
    raise SystemExit(f"Preflight failed: {message}; do not compile/publish.")


# Catalogue: public REST API, manga and comics without videos and games.
query = urlencode({
    "categories_exclude": "3,109", "per_page": 5, "orderby": "date", "order": "desc",
    "_embed": "wp:featuredmedia",
    "_fields": "id,slug,title,date_gmt,class_list,_links.wp:featuredmedia,_embedded",
})
headers, body = fetch(f"{BASE}/wp-json/wp/v2/posts?{query}")
posts = json.loads(body)
if not posts or not headers.get("X-WP-TotalPages"):
    fail("REST post listing is empty or not paginated")
if not all({"slug", "title"} <= p.keys() for p in posts):
    fail("REST post fields changed")
if not any(p.get("_embedded", {}).get("wp:featuredmedia") for p in posts):
    fail("covers are missing")
print("Catalogue:", headers.get("X-WP-Total"), "posts,", [p["slug"] for p in posts])

_, body = fetch(f"{BASE}/wp-json/wp/v2/tags?per_page=5&orderby=count&order=desc&_fields=id,slug,name,count")
if not json.loads(body):
    fail("REST tag list is empty")

# Reader: a guest sees the members gate, which the extension turns into a login hint.
_, page = fetch(f"{BASE}/{posts[0]['slug']}/")
if "members-gate-page" not in page and "patreon-flow" not in page:
    fail("the members gate changed; check the reader")
print("Members gate present for guests.")

print("Site preflight passed.")
