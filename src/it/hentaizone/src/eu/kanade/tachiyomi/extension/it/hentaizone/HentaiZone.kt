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

/**
 * Public catalogue adapter for HentaiZone.me.
 *
 * This source deliberately does not automate Patreon login or attempt to access
 * subscriber-only reader pages. The site currently redirects its reader CTA to
 * Patreon OAuth; chapter pages are exposed only when the site itself returns
 * them to the current user/session.
 */
@Source
abstract class HentaiZone : KeiSource() {

    override val supportsLatest = true

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

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        // The manga archive is ordered by publication date and is the site's
        // public latest-manga listing as well.
        return getPopularManga(page)
    }

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
                title = doc.selectFirst("h1.entry-title, h1")?.text()?.trim().orEmpty().ifBlank { title }
                description = doc.selectFirst(".entry-content, .post-content, .description")?.text()?.trim()
                genre = doc.select(".cat-links a, .tags-links a, .post-categories a").joinToString { it.text() }
                thumbnail_url = doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() } ?: thumbnail_url
                initialized = true
            }
        }

        val updatedChapters = if (fetchChapters) {
            extractChapters(doc)
        } else {
            chapters
        }

        return SMangaUpdate(updated, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(getChapterUrl(chapter)).asJsoup()
        val pageImages = doc.select(
            "#reader img, .reader img, .reading-content img, .chapter-content img, .entry-content img",
        ).mapNotNull { image ->
            image.absUrl("data-src").ifBlank { image.absUrl("data-lazy-src") }
                .ifBlank { image.absUrl("src") }
                .takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }.distinct()

        if (pageImages.isNotEmpty()) {
            return pageImages.mapIndexed { index, image -> Page(index, imageUrl = image) }
        }

        val pageText = doc.text()
        if (pageText.contains("patreon", ignoreCase = true) ||
            pageText.contains("accesso", ignoreCase = true) ||
            pageText.contains("premium", ignoreCase = true)
        ) {
            throw IllegalStateException(
                "Hentai Zone richiede l'accesso Patreon per questa lettura. " +
                    "L'estensione non aggira login o contenuti riservati.",
            )
        }

        throw IllegalStateException("Nessuna pagina di lettura pubblica trovata per questo capitolo.")
    }

    override suspend fun getMangaByUrl(url: okhttp3.HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val doc = client.get(url).asJsoup()
        return mangaFromElement(doc, doc.selectFirst("article") ?: doc.body())
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
        ) != null || doc.select("a[href*='/page/']").any { it.text().trim().matches(Regex("(?i)next|successiva|»|›")) }

        return MangasPage(mangas, hasNext)
    }

    private fun mangaFromElement(doc: Document, element: Element): SManga? {
        val link = element.selectFirst(
            "h2 a[href], h3 a[href], .entry-title a[href], a[href*='/']",
        ) ?: return null
        val href = link.absUrl("href").ifBlank { return null }
        if (!href.startsWith(baseUrl) || href.contains("/video", ignoreCase = true)) return null

        val title = link.text().trim().ifBlank {
            element.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
        }
        if (title.isBlank() || isBlocked(element.text())) return null

        val image = element.selectFirst("img")
        val thumbnail = image?.let {
            it.absUrl("data-src").ifBlank { it.absUrl("data-lazy-src") }.ifBlank { it.absUrl("src") }
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
                ".listing-chapters a[href], a.chapter-link[href]",
        )

        return chapterLinks.mapNotNull { link ->
            val href = link.absUrl("href")
            val title = link.text().trim()
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

    private fun isBlocked(text: String): Boolean = blockedLabels.containsMatchIn(text)
}
