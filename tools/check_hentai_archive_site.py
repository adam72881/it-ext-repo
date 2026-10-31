#!/usr/bin/env python3
"""Checks the parts of hentai-archive.com the extension depends on before building."""
import re
from urllib.parse import quote
from urllib.request import Request, urlopen

BASE = "https://www.hentai-archive.com"
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"


def fetch(url, referer=None):
    headers = {"User-Agent": UA}
    if referer:
        headers["Referer"] = referer
    # Image URLs may hold raw non-ASCII characters, as the app's HTTP client accepts.
    with urlopen(Request(quote(url, safe=":/%?=&#"), headers=headers), timeout=30) as response:
        return response.headers, response.read().decode("utf-8", "replace")


def fail(message):
    raise SystemExit(f"Preflight failed: {message}; do not compile/publish.")


# Lists: the category archive, ordered by comments, with category classes on the cards.
_, page = fetch(f"{BASE}/category/hentai-recenti/?orderby=comment_count&order=desc")
cards = re.findall(r'<article[^>]*class="( masonry-blog-item[^"]*)"', page)
links = re.findall(r'<h3 class="title"><a href="(https://www\.hentai-archive\.com/[^"/]+/)"', page)
if len(cards) < 6 or len(links) < 6 or not all("category-" in c for c in cards):
    fail("archive cards, titles or category classes are missing")
if not re.search(r'page-numbers[^>]*href="[^"]*/page/\d+/', page):
    fail("archive pagination is missing")
print("Archive:", len(cards), "cards")

# Search, limited to the manga with the excluded category left out.
_, page = fetch(f"{BASE}/?s=madre&category_name=hentai-recenti&cat=-286")
if not re.search(r'<article class="result"', page):
    fail("search results are missing")

# Reader: page images inside the post content, served with the site as Referer.
_, post = fetch(links[0])
images = re.findall(r'<img[^>]+src="(https://[^"]+)"', post[post.find('class="content-inner"'):])
if len(images) < 3:
    fail("the post has no page images")
headers, _ = fetch(images[0], referer=BASE + "/")
if not headers.get("Content-Type", "").startswith("image/"):
    fail("page images are not served")
print("Reader:", len(images), "images, first:", images[0])

print("Site preflight passed.")
