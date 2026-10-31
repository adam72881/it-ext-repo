#!/usr/bin/env python3
"""Build the Hentai Zone page-count index used by "Più pagine" and "Pagine minime".

usage: build_page_index.py OUTPUT.json

The site lists no page counts, but the REST API returns each post's gallery
(data-images), so one pass over the posts counts them all. Premium posts have
an empty gallery for guests, so their count is unknown and they are left out.
Excluded content (minors, videos, games) never enters the index.
"""
import html
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

BASE = "https://hentaizone.me"
UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"

# Must match HentaiZoneFilters.kt.
SKIP_CATEGORIES = {752, 367, 617, 926, 1884}
SKIP_TAGS = {504, 2000, 2054, 418, 782}
BLOCKED_TEXT = re.compile(
    r"(?i)\b(loli\w*|shota\w*|preteen\w*|pre-teen\w*|underage|minorenn\w*|bambin[aeio]|liceal[ei]|elementari)\b"
)


def fetch(url):
    for attempt in range(4):
        try:
            with urlopen(Request(url, headers={"User-Agent": UA}), timeout=60) as response:
                return response.headers, response.read().decode("utf-8", "replace")
        except OSError:
            if attempt == 3:
                raise
            time.sleep(5 * (attempt + 1))


def thumbnail(post):
    media = (post.get("_embedded") or {}).get("wp:featuredmedia") or []
    if not media:
        return None
    details = media[0].get("media_details")
    sizes = details.get("sizes", {}) if isinstance(details, dict) else {}
    for size in ("medium_large", "large", "medium"):
        if size in sizes:
            return sizes[size]["source_url"]
    return media[0].get("source_url")


def main():
    output = Path(sys.argv[1])
    entries, page, total_pages = [], 1, 1
    while page <= total_pages:
        query = urlencode({
            "per_page": 50,
            "page": page,
            "orderby": "id",
            "order": "asc",
            "categories_exclude": ",".join(map(str, sorted(SKIP_CATEGORIES))),
            "tags_exclude": ",".join(map(str, sorted(SKIP_TAGS))),
            "_embed": "wp:featuredmedia",
            "_fields": "slug,title,date_gmt,categories,tags,content,_links.wp:featuredmedia,_embedded",
        })
        headers, body = fetch(f"{BASE}/wp-json/wp/v2/posts?{query}")
        total_pages = int(headers.get("X-WP-TotalPages", 1))
        for post in json.loads(body):
            title = html.unescape(post["title"]["rendered"])
            galleries = re.findall(r'data-images="([^"]*)"', post["content"]["rendered"])
            pages = sum(len(json.loads(html.unescape(g))) for g in galleries)
            if not pages or BLOCKED_TEXT.search(title):
                continue
            entries.append({
                "u": f"/{post['slug']}/",
                "t": title,
                "i": thumbnail(post),
                "p": pages,
                "d": post["date_gmt"],
                "c": post["categories"],
                "g": post["tags"],
            })
        page += 1
        time.sleep(0.5)

    output.write_text(
        json.dumps(
            {"generated": datetime.now(timezone.utc).isoformat(timespec="seconds"), "entries": entries},
            ensure_ascii=False,
            separators=(",", ":"),
        ) + "\n",
        encoding="utf-8",
    )
    print(f"{len(entries)} entries, largest {max(e['p'] for e in entries)} pages")


if __name__ == "__main__":
    main()
