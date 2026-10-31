#!/usr/bin/env python3
"""Checks the parts of hentaizone.me the extension depends on before building."""
import html
import json
import re
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE = "https://hentaizone.me"
FREE_TEST = BASE + "/lezioni-di-fitness/"
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"


def fetch(url):
    with urlopen(Request(url, headers={"User-Agent": UA}), timeout=30) as response:
        return response.headers, response.read().decode("utf-8", "replace")


def fail(message):
    raise SystemExit(f"Preflight failed: {message}; do not compile/publish.")


# Latest and search: REST API with AND terms and exclusions.
query = urlencode({
    "categories[terms]": "342", "categories[operator]": "AND",
    "categories_exclude": "920,617,926", "tags_exclude": "418",
    "per_page": 5, "orderby": "date", "order": "desc",
    "_embed": "wp:featuredmedia",
    "_fields": "id,slug,title,date_gmt,class_list,_links.wp:featuredmedia,_embedded",
})
headers, body = fetch(f"{BASE}/wp-json/wp/v2/posts?{query}")
posts = json.loads(body)
if not posts or not headers.get("X-WP-TotalPages"):
    fail("REST post listing is empty or not paginated")
if any("category-premium" in p["class_list"] for p in posts):
    fail("REST category exclusion was ignored")
if not all({"slug", "title", "class_list"} <= p.keys() for p in posts):
    fail("REST post fields changed")
print("REST listing:", headers.get("X-WP-Total"), "posts,", [p["slug"] for p in posts])

for taxonomy in ("categories", "tags"):
    _, body = fetch(f"{BASE}/wp-json/wp/v2/{taxonomy}?per_page=5&orderby=count&order=desc&_fields=id,slug,name,count")
    if not json.loads(body):
        fail(f"REST {taxonomy} list is empty")

# Popular: category archive ordered by comment count, with term classes on the cards.
_, page = fetch(f"{BASE}/?category_name=hentai-manga&orderby=comment_count&order=desc")
cards = re.findall(r'<article[^>]*class="([^"]*)"', page)
if len(cards) < 6 or not all("category-" in c for c in cards):
    fail("archive cards or their category classes are missing")
if "elementor-post__title" not in page:
    fail("archive card titles changed")
print("Popular archive:", len(cards), "cards")

# Reader: free post with the GFFolder gallery and its data-images list.
_, page = fetch(FREE_TEST)
galleries = re.findall(r'<div class="gff-gallery"[^>]*data-images="([^"]*)"', page)
images = [img["src"] for gallery in galleries for img in json.loads(html.unescape(gallery))]
if not images:
    fail("free test post has no GFFolder data-images")
print("Reader:", len(images), "images, first:", images[0])

print("Site preflight passed.")
