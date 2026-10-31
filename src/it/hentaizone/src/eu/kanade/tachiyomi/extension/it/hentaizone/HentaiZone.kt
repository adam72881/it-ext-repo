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
 * Public and authenticated catalogue adapter for HentaiZone.me.
 *
 * Login is performed by the user in Mihon's built-in WebView. Requests use the
 * source cookie jar, so the site session established there is reused normally.
 */
@Source
abstract class HentaiZone : KeiSource() {

    override val supportsLatest = true

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
        return parseListing(client.get(url).asJsoup())
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
        return parseListing(client.get(url).asJsoup())
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

        val updatedChapters = if (fetchChapters) extractChapters(doc) else chapters
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

        val pageImages = doc.select(
            "#reader img, .reader img, .reading-content img, .chapter-content img, " +
                ".entry-content img, .comic-reader img, .pages img",
        ).mapNotNull { image ->
            image.absUrl("data-src")
                .ifBlank { image.absUrl("data-lazy-src") }
                .ifBlank { image.absUrl("data-original") }
                .ifBlank { image.absUrl("src") }
                .takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }.distinct()

        if (pageImages.isNotEmpty()) {
            return pageImages.mapIndexed { index, image -> Page(index, imageUrl = image) }
        }

        val pageText = doc.text()
        if (pageText.contains("patreon", ignoreCase = true) ||
            pageText.contains("premium", ignoreCase = true) ||
            pageText.contains("abbonati", ignoreCase = true) ||
            pageText.contains("accesso richiesto", ignoreCase = true)
        ) {
            throw IOException(
                "Questo contenuto richiede l'accesso Premium. " +
                    "Accedi dal WebView ufficiale di Hentai Zone e riprova. " +
                    "L'estensione non aggira abbonamenti o controlli di accesso.",
            )
        }

        throw IOException("Nessuna pagina di lettura trovata. Il capitolo potrebbe essere gratuito ma non disponibile.")
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

    private fun parseListing(doc: Document): MangasPage {
        val cards = doc.select("article, .post, .manga-item, .item")
        val mangas = cards.mapNotNull { card ->
            mangaFromElement(doc, card)?.takeUnless { isBlocked(card.text()) }
        }.distinctBy { it.url }

        val hasNext = doc.selectFirst(
            "a.next, .pagination a.next, a[rel=next], .nav-links a.next",
        ) != null || doc.select("a[href*='/page/']").any {
            it.text().matches(Regex("(?i)next|successiva|»|›"))
        }

        return MangasPage(mangas, hasNext)
    }

    private fun mangaFromElement(doc: Document, element: Element): SManga? {
        val link = element.selectFirst(
            "h2 a[href], h3 a[href], .entry-title a[href], a[href*='/']",
        ) ?: return null
        val href = link.absUrl("href").ifBlank { return null }
        if (!href.startsWith(baseUrl) || href.contains("/video", ignoreCase = true)) return null

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
                chapter_number = Regex("""(?i)(?:cap(?:itolo)?\\.?\\s*)([0-9]+(?:[.,][0-9]+)?)""")
                    .find(title)?.groupValues?.get(1)?.replace(',', '.')?.toFloatOrNull() ?: -1f
            }
        }.distinctBy { it.url }
    }

    private fun isLoginPage(doc: Document): Boolean =
        doc.selectFirst("form#loginform, form[action*='wp-login.php']") != null ||
            doc.text().contains("accedi con patreon", ignoreCase = true)

    private fun isBlocked(text: String): Boolean = blockedLabels.containsMatchIn(text)
}
