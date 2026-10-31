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
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonElement
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/**
 * Hentai Archive's members site (membri.premium-archive.com). The catalogue is
 * public through the WordPress REST API; the pages of every title are served
 * only to a logged-in subscriber. The app shares the WebView cookies with this
 * source, so logging in from the WebView (Patreon or the site's account) is
 * enough; nothing here bypasses the site's own access check.
 */
@Source
abstract class HentaiArchive : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage = randomListing(page, Query())

    override suspend fun getLatestUpdates(page: Int): MangasPage = apiListing(page, Query(sort = SortMode.DATE))

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()
        val tags = filters.firstInstanceOrNull<TagGroup>()?.state.orEmpty()
        val search = Query(
            text = query.trim(),
            sort = sort?.mode ?: SortMode.RANDOM,
            ascending = sort?.ascending ?: false,
            section = filters.firstInstanceOrNull<SectionFilter>()?.section ?: SECTIONS[0],
            includedTags = tags.filter { it.state == Filter.TriState.STATE_INCLUDE }.map { it.id },
            excludedTags = tags.filter { it.state == Filter.TriState.STATE_EXCLUDE }.map { it.id },
        )
        return if (search.sort == SortMode.RANDOM) randomListing(page, search) else apiListing(page, search)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val slug = url.pathSegments.singleOrNull { it.isNotEmpty() } ?: return null
        return fetchPost(slug)?.takeIf { isAllowed(it) }?.toSManga()
    }

    // The site has no popularity data: Popolari is a random order. Opening the
    // list again (page 1) draws a new order; later pages keep it.
    @Volatile
    private var randomSeed = 0L

    private suspend fun randomListing(page: Int, query: Query): MangasPage {
        if (page == 1) randomSeed = Random.nextLong()
        val ordered = query.copy(sort = SortMode.DATE)
        val total = client.get(postsUrl(ordered, page = 1, perPage = 1), Fetch.XHR).use { response ->
            response.header("X-WP-Total")?.toIntOrNull() ?: 0
        }
        val totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE
        if (page > totalPages) return MangasPage(emptyList(), false)
        val sitePage = (1..totalPages).shuffled(Random(randomSeed))[page - 1]
        val result = apiListing(sitePage, ordered)
        return MangasPage(result.mangas.shuffled(Random(randomSeed + page)), page < totalPages)
    }

    private suspend fun apiListing(page: Int, query: Query): MangasPage {
        val response = client.get(postsUrl(query, page, PAGE_SIZE), Fetch.XHR)
        val totalPages = response.header("X-WP-TotalPages")?.toIntOrNull() ?: 0
        val mangas = response.parseAs<List<PostDto>>().filter(::isAllowed).map { it.toSManga() }
        return MangasPage(mangas, page < totalPages)
    }

    private fun postsUrl(query: Query, page: Int, perPage: Int): HttpUrl {
        val orderBy = if (query.sort == SortMode.RELEVANCE && query.text.isEmpty()) "date" else query.sort.apiOrderBy
        return "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("per_page", perPage.toString())
            query.section.id?.let { addQueryParameter("categories", it.toString()) }
            addQueryParameter("categories_exclude", NON_READING_CATEGORIES.joinToString(","))
            if (query.includedTags.isNotEmpty()) {
                addQueryParameter("tags[terms]", query.includedTags.joinToString(","))
                addQueryParameter("tags[operator]", "AND")
            }
            if (query.excludedTags.isNotEmpty()) addQueryParameter("tags_exclude", query.excludedTags.joinToString(","))
            if (query.text.isNotEmpty()) addQueryParameter("search", query.text)
            addQueryParameter("orderby", orderBy)
            addQueryParameter("order", if (query.ascending) "asc" else "desc")
            addQueryParameter("_embed", "wp:featuredmedia")
            addQueryParameter("_fields", LIST_FIELDS)
        }.build()
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.trim('/').substringAfterLast('/')
        val post = fetchPost(slug, details = true) ?: throw IOException("Titolo non trovato su Hentai Archive Premium.")
        if (!isAllowed(post)) throw IOException("Questo titolo è escluso dall'estensione.")
        val series = seriesChapters(post, manga.url)

        manga.apply {
            title = post.plainTitle
            post.thumbnail?.let { thumbnail_url = it }
            genre = post.terms.filter { it.slug !in HIDDEN_GENRES }.joinToString { it.plainName }
            description = LOGIN_HINT
            status = SManga.COMPLETED
            // A series gets new chapters; a one-shot never changes.
            update_strategy = if (series.isEmpty()) UpdateStrategy.ONLY_FETCH_ONCE else UpdateStrategy.ALWAYS_UPDATE
        }
        val chapterList = series.ifEmpty {
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = "Capitolo unico"
                    chapter_number = 1f
                    date_upload = DATE_FORMAT.tryParse(post.dateGmt)
                },
            )
        }
        return SMangaUpdate(manga, chapterList)
    }

    private suspend fun fetchPost(slug: String, details: Boolean = false): PostDto? {
        val postUrl = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("slug", slug)
            .addQueryParameter("_embed", if (details) "wp:featuredmedia,wp:term" else "wp:featuredmedia")
            .addQueryParameter("_fields", if (details) DETAIL_FIELDS else LIST_FIELDS)
            .build()
        return client.get(postUrl, Fetch.XHR).parseAs<List<PostDto>>().firstOrNull()
    }

    // The site does not link the chapters of a series: the posts found by
    // searching the base title whose own title has the same base.
    private suspend fun seriesChapters(post: PostDto, mangaUrl: String): List<SChapter> {
        val base = SeriesTitle.split(post.plainTitle).base
        if (base.isEmpty()) return emptyList()
        val key = SeriesTitle.key(base)
        val searchUrl = "$baseUrl/wp-json/wp/v2/posts".toHttpUrl().newBuilder()
            .addQueryParameter("search", base)
            .addQueryParameter("per_page", "100")
            .addQueryParameter("categories_exclude", NON_READING_CATEGORIES.joinToString(","))
            .addQueryParameter("_fields", "slug,title,date_gmt,class_list")
            .build()
        val found = client.get(searchUrl, Fetch.XHR).parseAs<List<PostDto>>()
            .filter(::isAllowed)
            .mapNotNull { candidate ->
                val split = SeriesTitle.split(candidate.plainTitle)
                val number = split.number ?: SLUG_NUMBER.find(candidate.slug)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                (candidate to number).takeIf { SeriesTitle.key(split.base) == key }
            }
            .distinctBy { it.first.path }
        if (found.size < 2 || found.none { it.first.path == mangaUrl }) return emptyList()

        val duplicateNames = found.groupingBy { it.first.plainTitle }.eachCount().filterValues { it > 1 }.keys
        return found.sortedBy { it.second }.map { (chapterPost, number) ->
            SChapter.create().apply {
                url = chapterPost.path
                name = if (chapterPost.plainTitle in duplicateNames) "${chapterPost.plainTitle} – Capitolo $number" else chapterPost.plainTitle
                chapter_number = number.toFloat()
                date_upload = DATE_FORMAT.tryParse(chapterPost.dateGmt)
            }
        }.asReversed()
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        // Never reuse a cached copy: after logging in, the same URL must be read again.
        val doc = client.get(getChapterUrl(chapter), Fetch.DOCUMENT, CacheControl.FORCE_NETWORK).asJsoup()
        val images = galleryImages(doc).ifEmpty { contentImages(doc) }
        if (images.isNotEmpty()) return images.mapIndexed { index, image -> Page(index, imageUrl = image) }

        val gated = doc.body().hasClass("members-gate-page") || doc.selectFirst("a[href*=patreon-flow]") != null
        when {
            gated && !doc.body().hasClass("logged-in") -> throw IOException(LOGIN_REQUIRED)
            gated -> throw IOException(SUBSCRIPTION_REQUIRED)
            else -> return emptyList()
        }
    }

    // Gallery plugins that keep the page list as JSON next to the gallery.
    private fun galleryImages(doc: Document): List<String> {
        val fromScripts = doc.select("script.sgb-data").flatMap { script ->
            script.data().parseAs<GalleryDto>().images.mapNotNull { it.url ?: it.src }
        }
        val fromAttributes = doc.select("[data-images]").flatMap { element ->
            runCatching { element.attr("data-images").parseAs<List<GalleryUrlDto>>() }.getOrDefault(emptyList())
                .mapNotNull { it.src ?: it.url }
        }
        return (fromScripts + fromAttributes).map { it.toHttpUrl().toString() }.distinct()
    }

    // Plain images inside the post content.
    private fun contentImages(doc: Document): List<String> {
        val content = doc.selectFirst(".entry-content, .post-content, .content-inner, article") ?: return emptyList()
        return content.select("img")
            .mapNotNull { img ->
                listOf("data-src", "data-lazy-src", "data-nectar-img-src", "src")
                    .map { img.absUrl(it) }
                    .firstOrNull { it.startsWith("http") }
            }
            .filterNot { SITE_IMAGE.containsMatchIn(it) }
            .map { it.toHttpUrl().toString() }
            .distinct()
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement {
        val tags = buildList {
            for (page in 1..TAG_PAGES) {
                val tagsUrl = "$baseUrl/wp-json/wp/v2/tags".toHttpUrl().newBuilder()
                    .addQueryParameter("per_page", "100")
                    .addQueryParameter("page", page.toString())
                    .addQueryParameter("orderby", "count")
                    .addQueryParameter("order", "desc")
                    .addQueryParameter("hide_empty", "true")
                    .addQueryParameter("_fields", "id,slug,name,count")
                    .build()
                val batch = client.get(tagsUrl, Fetch.XHR).parseAs<List<TermDto>>()
                addAll(batch)
                if (batch.size < 100) break
            }
        }
        return FilterDataDto(tags).toJsonElement()
    }

    override fun getFilterList(data: JsonElement?): FilterList {
        val terms = data?.let { runCatching { it.parseAs<FilterDataDto>() }.getOrNull() }
        return FilterList(
            buildList {
                add(SortFilter())
                add(SectionFilter())
                if (terms != null) {
                    add(Filter.Separator())
                    add(Filter.Header("Tocca una volta per includere, due volte per escludere"))
                    add(
                        TagGroup(
                            terms.tags
                                .filter { it.count >= MIN_TAG_COUNT && !BLOCKED_TEXT.containsMatchIn(it.slug) }
                                .sortedBy { it.plainName.lowercase(Locale.ITALIAN) }
                                .map { TagOption(it.plainName, it.id) },
                        ),
                    )
                }
                add(Filter.Separator())
                add(Filter.Header("Per leggere serve un abbonamento: accedi dalla WebView (icona del mappamondo). Popolari è in ordine casuale."))
            },
        )
    }

    private fun isAllowed(post: PostDto): Boolean = !BLOCKED_TEXT.containsMatchIn(post.plainTitle)

    private data class Query(
        val text: String = "",
        val sort: SortMode = SortMode.RANDOM,
        val ascending: Boolean = false,
        val section: Section = SECTIONS[0],
        val includedTags: List<Int> = emptyList(),
        val excludedTags: List<Int> = emptyList(),
    )

    companion object {
        private const val PAGE_SIZE = 24
        private const val TAG_PAGES = 2
        private const val MIN_TAG_COUNT = 3

        private const val LIST_FIELDS = "id,slug,title,date_gmt,class_list,_links.wp:featuredmedia,_embedded"
        private const val DETAIL_FIELDS = "id,slug,title,date_gmt,class_list,_links.wp:featuredmedia,_links.wp:term,_embedded"

        private val SLUG_NUMBER = Regex("""-(\d+)$""")
        private val SITE_IMAGE = Regex("""(?i)logo|favicon|avatar|banner|icon""")
        private val HIDDEN_GENRES = setOf("hentai", "fumetti", "uncategorized")

        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        private const val LOGIN_HINT =
            "Hentai Archive Premium: le pagine si vedono solo con un abbonamento. " +
                "Per accedere apri la WebView (icona del mappamondo), tocca «Accedi con Patreon» o «Accedi» e completa il login."

        private const val LOGIN_REQUIRED =
            "Serve l'accesso: apri la WebView, tocca «Accedi con Patreon» o «Accedi», completa il login, torna indietro e riprova."

        private const val SUBSCRIPTION_REQUIRED =
            "Hai effettuato l'accesso, ma il sito non mostra le pagine: controlla che l'abbonamento sia attivo."
    }
}
