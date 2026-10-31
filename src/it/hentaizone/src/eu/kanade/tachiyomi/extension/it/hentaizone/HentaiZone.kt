package eu.kanade.tachiyomi.extension.it.hentaizone

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException

/**
 * Catalogue adapter for the public HentaiZone.me pages.
 *
 * The site login can be opened in Mihon's WebView. Session sharing is not
 * assumed here; access is only granted when the site serves the requested pages.
 */
@Source
abstract class HentaiZone : KeiSource() {

    // The site exposes a catalogue but no independently verified latest feed.
    override val supportsLatest = false

    // Mihon's "Open in WebView" action lands directly on the site's real login page.
    // The user can choose Patreon OAuth or enter the credentials supplied by the site.
    override fun getHomeUrl(): String = "$baseUrl/wp-login.php"

    private val blockedLabels = Regex(
        pattern = """(?i)(^|[^a-z])(loli|lolicon|shota|shotacon|underage|minor|minorenne|teen|schoolgirl|schoolboy|bambina|bambino)([^a-z]|$)""",
    )

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = if (page <= 1) {
            "$baseUrl/hentai-manga/"
        } else {
            "$baseUrl/hentai-manga/page/$page/"
        }
        return parseListing(client.get(url).asJsoup(), page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .addQueryParameter("post_type", "post")
            .addQueryParameter("paged", page.toString())
            .build()
        return parseListing(client.get(url).asJsoup(), page)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(getMangaUrl(manga)).asJsoup()
        val updated = manga.apply {
            if (fetchDetails) {
                title = doc.selectFirst("h1.entry-title, h1")?.text().orEmpty().ifBlank { title }
                description = doc.selectFirst(".entry-content, .post-content, .description")?.text()
                genre = doc.select(".cat-links a, .tags-links a, .post-categories a").joinToString { it.text() }
                thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() } ?: thumbnail_url
                initialized = true
            }
        }

        val updatedChapters = if (fetchChapters) {
            extractChapters(doc).ifEmpty {
                // Some Hentai Zone posts are one-shot readers with no chapter list.
                listOf(
                    SChapter.create().apply {
                        name = "Lettura"
                        url = manga.url
                        chapter_number = 1f
                    },
                )
            }
        } else {
            chapters
        }
        return SMangaUpdate(updated, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(getChapterUrl(chapter))
        val doc = response.asJsoup()

        if (isLoginPage(doc)) {
            throw IOException(
                "Accesso richiesto. Apri Hentai Zone dalla WebView della sorgente, " +
                    "accedi con Patreon o con le credenziali Hentai Zone e riprova.",
            )
        }

        var pageImages = extractPageImages(doc)

        // Some reader templates place the actual page images in a same-site iframe.
        if (pageImages.isEmpty()) {
            val readerFrame = doc.select("iframe[src]")
                .map { it.absUrl("src") }
                .firstOrNull { it.startsWith(baseUrl) }
            if (readerFrame != null) {
                pageImages = runCatching {
                    extractPageImages(client.get(readerFrame).asJsoup())
                }.getOrDefault(emptyList())
            }
        }

        if (pageImages.isNotEmpty()) {
            return pageImages.mapIndexed { index, image -> Page(index, imageUrl = image) }
        }

        // A Patreon button may be displayed on a free manga page too. Only
        // classify an actual access-denied/login response as restricted.
        val pageText = doc.text()
        val accessDenied = listOf(
            "devi essere abbonato", "contenuto riservato agli abbonati",
            "solo per gli abbonati", "accesso riservato", "effettua il login per leggere",
            "devi effettuare l'accesso", "accesso richiesto",
        ).any { pageText.contains(it, ignoreCase = true) }
        if (accessDenied || isLoginPage(doc)) {
            throw IOException(
                "Il sito non ha restituito le pagine di lettura: potrebbe essere richiesto l'accesso. " +
                    "Accedi tramite il sito ufficiale e riprova. Nessun controllo Premium viene aggirato.",
            )
        }

        throw IOException("Nessuna immagine di lettura trovata nella pagina restituita dal sito.")
    }

    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val doc = client.get(url).asJsoup()
        val title = doc.selectFirst("h1.entry-title, h1")?.text().orEmpty()
        if (title.isBlank() || isBlocked(doc.text())) return null

        val thumbnail = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        return SManga.create().apply {
            this.title = title
            this.url = url.encodedPath
            thumbnail_url = thumbnail
        }
    }

    override fun getMangaUrl(manga: SManga): String =
        if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String =
        if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"

    private fun extractPageImages(doc: Document): List<String> {
        // Hentai Zone's gallery uses .fg_thumbnail anchors as direct image URLs.
        // Keep this selector first: the linked image can exist without an <img> src.
        val galleryImages = doc.select(".fg_thumbnail a[href], a.fg_thumbnail[href]")
            .map { it.absUrl("href") }
            .filter { it.startsWith("https://") || it.startsWith("http://") }

        val imageSelectors = listOf(
            "#reader img", ".reader img", ".reading-content img", ".chapter-content img",
            ".entry-content img", ".comic-reader img", ".pages img", ".gallery img",
            "[class*=reader] img", "[id*=reader] img",
        )
        return (galleryImages + imageSelectors
            .flatMap { selector -> doc.select(selector) }
            .mapNotNull { image ->
                listOf("data-src", "data-lazy-src", "data-original", "data-full-url", "src")
                    .asSequence()
                    .map { image.absUrl(it) }
                    .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
            })
            .distinct()
    }

    private fun parseListing(doc: Document, page: Int): MangasPage {
        // WordPress archives vary by theme: prefer real post containers and keep
        // generic fallbacks last so navigation/widgets do not become manga entries.
        val cards = doc.select(
            "article, .type-post, .hentry, .post, .blog-post, .manga-item, .item",
        )
        val mangas = cards.mapNotNull { card ->
            mangaFromElement(doc, card)?.takeUnless { isBlocked(card.text()) }
        }.distinctBy { it.url }

        val hasNext = doc.selectFirst(
            "a.next, a.next.page-numbers, .pagination a.next, a[rel=next], " +
                ".nav-links a.next, .page-numbers.next",
        ) != null || doc.select("a[href*='/page/'], a[href*='paged=']").any { link ->
            val href = link.absUrl("href")
            val linkedPage = Regex("""/page/(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""[?&]paged=(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
            linkedPage != null && linkedPage > page
        }

        return MangasPage(mangas, hasNext)
    }

    private fun mangaFromElement(doc: Document, element: Element): SManga? {
        val titleLink = element.selectFirst(
            "h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], " +
                ".post-title a[href], a[rel=bookmark][href]",
        )
        val link = titleLink?.takeIf { isContentUrl(it.absUrl("href")) }
            ?: element.select("a[href]").firstOrNull {
                it.text().isNotBlank() && isContentUrl(it.absUrl("href"))
            }
            ?: return null
        val href = link.absUrl("href").ifBlank { return null }
        val title = link.text().ifBlank {
            element.selectFirst("h1, h2, h3")?.text().orEmpty()
        }
        if (title.isBlank() || isBlocked(element.text())) return null

        val image = element.selectFirst("img")
        val thumbnail = image?.let {
            it.absUrl("data-src")
                .ifBlank { it.absUrl("data-lazy-src") }
                .ifBlank { it.absUrl("data-original") }
                .ifBlank { it.absUrl("src") }
        }.orEmpty().ifBlank {
            doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        }

        return SManga.create().apply {
            this.title = title
            url = href.removePrefix(baseUrl)
            thumbnail_url = thumbnail
        }
    }

    private fun extractChapters(doc: Document): List<SChapter> {
        val chapterLinks = doc.select(
            ".chapter-list a[href], .chapters a[href], .wp-manga-chapter a[href], " +
                ".listing-chapters a[href], a.chapter-link[href], " +
                ".entry-content a[href*='capitolo'], .entry-content a[href*='chapter']",
        )

        return chapterLinks.mapNotNull { link ->
            val href = link.absUrl("href")
            val title = link.text()
            if (href.isBlank() || title.isBlank() || !href.startsWith(baseUrl)) return@mapNotNull null
            if (isBlocked("$title ${link.parent()?.text().orEmpty()}")) return@mapNotNull null

            SChapter.create().apply {
                name = title
                url = href.removePrefix(baseUrl)
                chapter_number = Regex("""(?i)(?:cap(?:itolo)?\.?\s*)([0-9]+(?:[.,][0-9]+)?)""")
                    .find(title)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: -1f
            }
        }.distinctBy { it.url }
    }

    private fun isContentUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
        if (parsed.host != baseUrl.toHttpUrl().host) return false
        val path = parsed.encodedPath.lowercase()
        if (path.startsWith("/category/") || path.startsWith("/tag/") ||
            path.startsWith("/author/") || path.startsWith("/wp-") ||
            path.contains("/video") || path == "/hentai-manga/" ||
            path.matches(Regex(""".*/page/\d+/?"""))
        ) return false
        return !path.substringAfterLast('/').matches(Regex(""".+\.(jpg|jpeg|png|gif|webp|avif|pdf)$"""))
    }

    private fun isLoginPage(doc: Document): Boolean =
        doc.selectFirst("form#loginform, form[action*='wp-login.php']") != null ||
            doc.selectFirst("form[action*=login], input[type=password]") != null

    private fun isBlocked(text: String): Boolean = blockedLabels.containsMatchIn(text)
}
