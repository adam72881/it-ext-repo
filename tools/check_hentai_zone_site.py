#!/usr/bin/env python3
from html.parser import HTMLParser
from urllib.request import Request, urlopen
from urllib.parse import urljoin

BASE = "https://hentaizone.me"
CATALOG = BASE + "/hentai-manga/"
FREE_TEST = BASE + "/lezioni-di-fitness/"

class SiteParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.cards = 0
        self.gallery_links = 0
        self.links = []
        self._in_gallery = 0

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = set((attrs.get("class") or "").split())
        if tag in ("article",) or classes.intersection({"post", "type-post", "hentry", "manga-item"}):
            self.cards += 1
        if tag == "a" and attrs.get("href"):
            self.links.append(attrs["href"])
            if "fg_thumbnail" in classes or self._in_gallery:
                self.gallery_links += 1
        if "fg_thumbnail" in classes:
            self._in_gallery += 1

    def handle_endtag(self, tag):
        if self._in_gallery and tag in ("div", "figure", "a"):
            self._in_gallery = max(0, self._in_gallery - 1)

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
if not internal:
    raise SystemExit("Catalog check failed: no internal post links found; do not compile/publish.")

final_url, html = fetch(FREE_TEST)
reader = SiteParser()
reader.feed(html)
if reader.gallery_links == 0:
    raise SystemExit("Free test page check failed: no .fg_thumbnail gallery links; do not compile/publish.")

print(f"Site preflight OK: catalog has {len(internal)} internal links; free test page has {reader.gallery_links} gallery links.")
