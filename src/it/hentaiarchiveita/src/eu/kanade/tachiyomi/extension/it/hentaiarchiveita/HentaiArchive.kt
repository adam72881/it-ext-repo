package eu.kanade.tachiyomi.extension.it.hentaiarchiveita

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParse
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Hentai Archive is a WordPress site with its REST API turned off, so every list
 * is read from the public HTML archives. They show 9 entries per page; one app
 * page reads two of them. The archives take one category at a time (combined
 * categories are redirected to a single one).
 *
 * Page images live on a CDN that only answers with the site as Referer, which
 * the default headers already send.
 */
@Source
abstract class HentaiArchive : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override fun getHomeUrl(): String = "$baseUrl/category/hentai-recenti/"

    override suspend fun getPopularManga(page: Int): MangasPage = listing(page, Query(sort = SortMode.POPULAR))

    override suspend fun getLatestUpdates(page: Int): MangasPage = listing(page, Query(sort = SortMode.DATE))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()
        return listing(
            page,
            Query(
                text = query.trim(),
                sort = sort?.mode ?: SortMode.POPULAR,
                ascending = sort?.ascending ?: false,
                category = filters.firstInstanceOrNull<CategoryFilter>()?.category
                    ?: filters.firstInstanceOrNull<SectionFilter>()?.section
                    ?: SECTIONS[0],
            ),
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.singleOrNull { it.isNotEmpty() } ?: return null
        val doc = client.get("$baseUrl/$slug/", Fetch.DOCUMENT).asJsoup()
        val title = doc.selectFirst("h1")?.text() ?: return null
        if (!isAllowed(doc.selectFirst("article")?.classNames().orEmpty(), title)) return null
        return SManga.create().apply {
            this.url = "/$slug/"
            this.title = title
            thumbnail_url = LD_THUMBNAIL.find(doc.html())?.groupValues?.get(1)?.replace("\\/", "/")
        }
    }

    // Random keeps the date order but shuffles which archive pages each app
    // page reads; page 1 draws a new order, so scrolling never repeats.
    @Volatile
    private var randomSeed = 0L

    private suspend fun listing(page: Int, query: Query): MangasPage {
        val archivePages = if (query.sort == SortMode.RANDOM) {
            if (page == 1) randomSeed = Random.nextLong()
            val lastPage = lastArchivePage(client.get(archiveUrl(1, query), Fetch.DOCUMENT).asJsoup())
            val order = (1..lastPage).shuffled(Random(randomSeed))
            listOfNotNull(order.getOrNull(page * 2 - 2), order.getOrNull(page * 2 - 1))
        } else {
            listOf(page * 2 - 1, page * 2)
        }
        if (archivePages.isEmpty()) return MangasPage(emptyList(), false)

        // The second archive page may not exist: the site answers 404 past the end.
        val docs = coroutineScope {
            archivePages.map { archivePage ->
                async {
                    client.get(archiveUrl(archivePage, query), Fetch.DOCUMENT, ensureSuccess = false).use { response ->
                        when {
                            response.isSuccessful -> response.asJsoup()
                            response.code == 404 -> null
                            else -> throw IOException("HTTP ${response.code}")
                        }
                    }
                }
            }.map { it.await() }
        }.filterNotNull()
        if (docs.isEmpty()) return MangasPage(emptyList(), false)

        val mangas = docs.flatMap(::parseCards).distinctBy { it.url }
        val lastPage = docs.maxOf(::lastArchivePage)
        val hasNext = if (query.sort == SortMode.RANDOM) page * 2 < lastPage else archivePages.last() < lastPage
        return MangasPage(if (query.sort == SortMode.RANDOM) mangas.shuffled(Random(randomSeed + page)) else mangas, hasNext)
    }

    private fun archiveUrl(archivePage: Int, query: Query): HttpUrl {
        val order = if (query.ascending) "asc" else "desc"
        if (query.text.isNotEmpty()) {
            // Search results carry no category classes: the excluded category
            // is left out by the query itself.
            return "$baseUrl/".toHttpUrl().newBuilder().apply {
                if (archivePage > 1) addPathSegments("page/$archivePage/")
                addQueryParameter("s", query.text)
                addQueryParameter("category_name", query.category.slug)
                addQueryParameter("cat", "-$BLOCKED_CATEGORY_ID")
                addQueryParameter("orderby", query.sort.orderBy)
                addQueryParameter("order", order)
            }.build()
        }
        return "$baseUrl/category/${query.category.path}/".toHttpUrl().newBuilder().apply {
            if (archivePage > 1) addPathSegments("page/$archivePage/")
            if (query.sort.orderBy != "date" || query.ascending) {
                addQueryParameter("orderby", query.sort.orderBy)
                addQueryParameter("order", order)
            }
        }.build()
    }

    private fun parseCards(doc: Document): List<SManga> = doc.select("article.masonry-blog-item, article.result")
        .mapNotNull { article ->
            val link = article.selectFirst("h3.title a[href], h2.title a[href]") ?: return@mapNotNull null
            val slug = link.absUrl("href").toHttpUrlOrNull()?.pathSegments?.singleOrNull { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = link.ownText().ifEmpty { link.text() }.ifEmpty { return@mapNotNull null }
            if (!isAllowed(article.classNames(), title)) return@mapNotNull null
            val image = article.selectFirst("img")
            SManga.create().apply {
                url = "/$slug/"
                this.title = title
                thumbnail_url = image?.let { img ->
                    img.absUrl("data-nectar-img-src").ifEmpty { img.absUrl("src") }
                }?.takeIf { it.startsWith("http") }
            }
        }

    // The highest page linked from this page, or this page itself.
    private fun lastArchivePage(doc: Document): Int {
        val current = ARCHIVE_PAGE.find(doc.location())?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val linked = doc.select("a.page-numbers[href]")
            .mapNotNull { ARCHIVE_PAGE.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        return (linked + current).max()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val doc = client.get(getMangaUrl(manga), Fetch.DOCUMENT).asJsoup()
        val title = doc.selectFirst("h1")?.text() ?: throw IOException("Titolo non trovato su Hentai Archive.")
        if (!isAllowed(doc.selectFirst("article")?.classNames().orEmpty(), title)) {
            throw IOException("Questo titolo è escluso dall'estensione.")
        }
        val html = doc.html()

        manga.apply {
            this.title = title
            LD_THUMBNAIL.find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.let { thumbnail_url = it }
            genre = doc.select(".meta-category a")
                .map { it.text() }
                .filter { it.isNotEmpty() && it != "Hentai Recenti" }
                .distinct()
                .joinToString()
            // The page description holds a short synopsis written by the site.
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
                ?.takeUnless { it.isEmpty() || it.startsWith(GENERIC_DESCRIPTION) }
            status = SManga.COMPLETED
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
        val chapter = SChapter.create().apply {
            url = manga.url
            name = "Capitolo unico"
            chapter_number = 1f
            date_upload = DATE_FORMAT.tryParse(LD_DATE.find(html)?.groupValues?.get(1))
        }
        return SMangaUpdate(manga, listOf(chapter))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val doc = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        return doc.select("article .content-inner img")
            .mapNotNull { img ->
                listOf("data-src", "data-lazy-src", "src")
                    .map { img.absUrl(it) }
                    .firstOrNull { it.startsWith("http") }
            }
            .distinct()
            .mapIndexed { index, image -> Page(index, imageUrl = image.toHttpUrl().toString()) }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        SectionFilter(),
        CategoryFilter(),
        Filter.Separator(),
        Filter.Header("Una categoria alla volta: il sito non permette di combinarle. Se scegli una categoria, vale quella invece della sezione."),
        Filter.Header("Il sito non ha un contatore di visite: Popolari sono i più commentati. I contenuti con bambini o preadolescenti sono sempre esclusi."),
    )

    private fun isAllowed(classes: Collection<String>, title: String): Boolean =
        classes.none { it in BLOCKED_CLASSES } && !BLOCKED_TEXT.containsMatchIn(title)

    private class Query(
        val text: String = "",
        val sort: SortMode = SortMode.POPULAR,
        val ascending: Boolean = false,
        val category: Category = SECTIONS[0],
    )

    companion object {
        // Posts without their own text fall back to the site's generic one.
        private const val GENERIC_DESCRIPTION = "Hentai Gratuiti, Fumetti Manga XXX"
        private val ARCHIVE_PAGE = Regex("""/page/(\d+)""")
        private val LD_DATE = Regex(""""datePublished":"([^"]+)"""")
        private val LD_THUMBNAIL = Regex(""""thumbnailUrl":"([^"]+)"""")

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
    }
}
