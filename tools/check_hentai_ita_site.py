#!/usr/bin/env python3
"""Checks the parts of hentai-ita.net the extension depends on before building."""
import json
import re
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE = "https://hentai-ita.net"
READER_TEST = BASE + "/il-sindaco-1/"
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"


def fetch(url):
    with urlopen(Request(url, headers={"User-Agent": UA}), timeout=30) as response:
        return response.headers, response.read().decode("utf-8", "replace")


def fail(message):
    raise SystemExit(f"Preflight failed: {message}; do not compile/publish.")


# Lists: REST API with AND categories, exclusions and the "best" picks.
query = urlencode({
    "categories[terms]": "16", "categories[operator]": "AND",
    "categories_exclude": "145,53,317", "per_page": 5, "orderby": "date", "order": "desc",
    "_embed": "wp:featuredmedia",
    "_fields": "id,slug,link,title,date_gmt,class_list,_links.wp:featuredmedia,_embedded",
})
headers, body = fetch(f"{BASE}/wp-json/wp/v2/posts?{query}")
posts = json.loads(body)
if not posts or not headers.get("X-WP-TotalPages"):
    fail("REST post listing is empty or not paginated")
for post in posts:
    classes = post["class_list"]
    classes = classes.values() if isinstance(classes, dict) else classes
    if "category-hentai-manga" not in classes or "category-sailor-moon" in classes or "category-mobile" in classes:
        fail("REST category filtering was ignored")
print("REST listing:", headers.get("X-WP-Total"), "posts,", [p["slug"] for p in posts])

headers, _ = fetch(f"{BASE}/wp-json/wp/v2/posts?categories=498&per_page=1&_fields=id")
if int(headers.get("X-WP-Total", 0)) == 0:
    fail("the 'best' category used by Popolari is empty")

_, body = fetch(f"{BASE}/wp-json/wp/v2/categories?per_page=5&orderby=count&order=desc&_fields=id,slug,name,count")
if not json.loads(body):
    fail("REST category list is empty")

# Reader: the Simply Gallery JSON with the public page list.
_, page = fetch(READER_TEST)
galleries = re.findall(r'(?s)<script[^>]*class="sgb-data"[^>]*>(.*?)</script>', page)
images = [img["url"] for gallery in galleries for img in json.loads(gallery).get("images", [])]
if not images:
    fail("reader test post has no Simply Gallery data")
print("Reader:", len(images), "images, first:", images[0])

print("Site preflight passed.")
