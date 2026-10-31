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
        self.anchor_samples = []
        self.image_samples = []
        self.stack = []
        self.gff_images = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = set((attrs.get("class") or "").split())
        self.classes.update(classes)
        self.stack.append((tag, classes))
        if tag == "title":
            self.title_text = True
        if tag == "img":
            self.images += 1
            if any("gff-item" in parent_classes for _, parent_classes in self.stack):
                self.gff_images += 1
            if len(self.image_samples) < 20:
                self.image_samples.append({k: v for k, v in attrs.items() if k in ("src", "data-src", "data-lazy-src", "data-original", "class")})
        if tag == "a" and attrs.get("href") and len(self.anchor_samples) < 30:
            self.anchor_samples.append((attrs.get("class", ""), attrs.get("href", "")))
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
        for i in range(len(self.stack) - 1, -1, -1):
            if self.stack[i][0] == tag:
                del self.stack[i:]
                break
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
print("FREE TEST:", "title=", " ".join(free.title), "gallery-links=", free.gallery_links, "gff-item-images=", free.gff_images,
      "images=", free.images, "classes=", free.classes.most_common(25),
      "anchors=", free.anchor_samples, "image_samples=", free.image_samples)

if free.gff_images == 0:
    final_url, html = fetch(KNOWN_DRIVER_TEST)
    known = SiteParser()
    known.feed(html)
    print("KNOWN DRIVER TEST:", "title=", " ".join(known.title), "gallery-links=", known.gallery_links, "gff-item-images=", known.gff_images,
          "images=", known.images, "classes=", known.classes.most_common(25),
          "anchors=", known.anchor_samples, "image_samples=", known.image_samples)
    if known.gff_images == 0:
        raise SystemExit("Reader check failed: neither test page exposes the current GFFolder image container; do not compile/publish.")
    else:
        print("Reader markup validated through the current GFFolder image container.")

print("Site preflight passed: catalog cards and current GFFolder reader images are present.")
