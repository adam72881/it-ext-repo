#!/usr/bin/env python3
from collections import Counter
from html.parser import HTMLParser
from urllib.request import Request, urlopen
from urllib.parse import urljoin

BASE = "https://hentaizone.me"
CATALOG = BASE + "/hentai-manga/"
FREE_TEST = BASE + "/lezioni-di-fitness/"
KNOWN_DRIVER_TEST = BASE + "/mind-control-ch-4/"

class SiteParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.classes = Counter()
        self.links = []
        self.images = 0
        self.gallery_links = 0
        self._gallery_depth = 0
        self._post_depth = 0
        self.post_links = 0
        self.title_text = False
        self.title = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = set((attrs.get("class") or "").split())
        self.classes.update(classes)
        if tag == "title":
            self.title_text = True
        if tag == "img":
            self.images += 1
        if tag in ("article",) or classes.intersection({"post", "type-post", "hentry", "manga-item"}):
            self._post_depth += 1
        if "fg_thumbnail" in classes:
            self._gallery_depth += 1
        if tag == "a" and attrs.get("href"):
            self.links.append(attrs["href"])
            if self._gallery_depth:
                self.gallery_links += 1
            if self._post_depth:
                self.post_links += 1

    def handle_endtag(self, tag):
        if tag == "title":
            self.title_text = False
        if tag in ("article", "div", "li"):
            self._post_depth = max(0, self._post_depth - 1)
        if tag in ("div", "figure"):
            self._gallery_depth = max(0, self._gallery_depth - 1)

    def handle_data(self, data):
        if self.title_text:
            self.title.append(data.strip())

def fetch(url):
    req = Request(url, headers={"User-Agent": "Mozilla/5.0 (compatible; HentaiZoneExtensionCheck/1.0)"})
    with urlopen(req, timeout=25) as response:
        if response.status != 200:
            raise RuntimeError(f"{url}: HTTP {response.status}")
        return response.geturl(), response.read().decode("utf-8", "replace")

final_url, html = fetch(CATALOG)
catalog = SiteParser()
catalog.feed(html)
internal = [urljoin(final_url, href) for href in catalog.links]
internal = [u for u in internal if u.startswith(BASE + "/") and u.rstrip("/") != BASE]
print("CATALOG:", "title=", " ".join(catalog.title), "articles/post-links=", catalog.post_links,
      "images=", catalog.images, "classes=", catalog.classes.most_common(20))
if not internal:
    raise SystemExit("Catalog check failed: no internal links found; do not compile/publish.")

final_url, html = fetch(FREE_TEST)
free = SiteParser()
free.feed(html)
print("FREE TEST:", "title=", " ".join(free.title), "gallery-links=", free.gallery_links,
      "images=", free.images, "classes=", free.classes.most_common(25))

if free.gallery_links == 0:
    final_url, html = fetch(KNOWN_DRIVER_TEST)
    known = SiteParser()
    known.feed(html)
    print("KNOWN DRIVER TEST:", "title=", " ".join(known.title), "gallery-links=", known.gallery_links,
          "images=", known.images, "classes=", known.classes.most_common(25))
    if known.gallery_links == 0:
        raise SystemExit("Reader check failed: neither the user free test page nor the known Yamete test page exposes .fg_thumbnail links; do not compile/publish.")

print("Site preflight passed: the catalog and at least one known gallery page expose links.")
