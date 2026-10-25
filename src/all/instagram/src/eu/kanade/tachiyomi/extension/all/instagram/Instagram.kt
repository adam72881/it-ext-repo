package eu.kanade.tachiyomi.extension.all.instagram

import android.webkit.CookieManager
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.getArrayOrNull
import keiyoushi.utils.getBooleanOrNull
import keiyoushi.utils.getIntOrNull
import keiyoushi.utils.getObjectOrNull
import keiyoushi.utils.getStringOrNull
import keiyoushi.utils.jsonInstance
import keiyoushi.utils.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Personal, experimental Instagram reader.
 *
 * The owner logs in through this source's WebView. Later requests reuse that
 * WebView's sessionid, csrftoken and ds_user_id. Nothing here asks for the
 * password, and API calls are serialized so Instagram is not hit in parallel.
 *
 * Popolari is the logged-in home timeline: each post is one entry, and opening
 * it shows that post's images. Recenti is the accounts that user follows.
 * Search returns profiles. A profile URL or a plain username opens that
 * profile directly. The optional Hashtag filter lists recent posts for a tag.
 *
 * A profile's chapters are its newest posts, capped. Pages are the post's
 * images in carousel order. Video and reel playback is not possible here, so
 * those posts contribute their cover image only.
 */
@Source
abstract class Instagram : KeiSource() {

    private val profiles = ConcurrentHashMap<String, JsonObject>()
    private val medias = ConcurrentHashMap<String, JsonObject>()
    private val cursors = ConcurrentHashMap<String, PageCursor>()

    @Volatile
    private var wwwClaim: String = "0"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        // One web API call at a time, at least 900ms apart, and at most 24 a minute.
        rateLimit(
            permits = 24,
            period = 60.seconds,
            interval = 900.milliseconds,
        ) { url -> isInstagramApi(url.host) }
        readTimeout(30, TimeUnit.SECONDS)
        connectTimeout(20, TimeUnit.SECONDS)
        callTimeout(45, TimeUnit.SECONDS)
        addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build(),
            )
        }
        addNetworkInterceptor(::stampInstagramRequest)
        return this
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Accept", "*/*")
        set("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
        set("X-IG-App-ID", IG_APP_ID)
        set("X-ASBD-ID", "129477")
        set("X-IG-WWW-Claim", "0")
        set("X-Requested-With", "XMLHttpRequest")
        set("Sec-Fetch-Dest", "empty")
        set("Sec-Fetch-Mode", "cors")
        set("Sec-Fetch-Site", "same-origin")
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header(
            "Hashtag opzionale, senza #. Se lo compili, la ricerca mostra i post recenti di quel tag al posto dei profili.",
        ),
        HashtagFilter(),
    )

    override suspend fun getPopularManga(page: Int): MangasPage {
        val cursor = cursorFor("home", page)
        val data = fetchTimeline(cursor)
        val posts = timelineMedias(data).map { postToManga(it) }.distinctBy { it.url }
        val next = nextMaxCursor(data)
        saveCursor("home", page, next)
        return MangasPage(posts, next != null)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val userId = loggedInUserId()
        val cursor = cursorFor("following", page)
        val query = mutableMapOf("count" to "24")
        if (cursor != null) query["max_id"] = cursor.maxId
        val data = getJson(apiUrl("api/v1/friendships/$userId/following/", query))
        val accounts = data.getArrayOrNull("users").orEmpty().mapNotNull { element ->
            element.asObject()?.let { runCatching { profileToManga(it) }.getOrNull() }
        }.distinctBy { it.url.lowercase() }
        val next = nextFollowingCursor(data)
        saveCursor("following", page, next)
        return MangasPage(accounts, next != null)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val typedTag = filters.filterIsInstance<HashtagFilter>().firstOrNull()?.state.orEmpty()
        val rawQuery = query.trim()
        val tag = when {
            typedTag.isNotBlank() -> typedTag
            rawQuery.startsWith("#") -> rawQuery
            else -> ""
        }
        if (tag.isNotBlank()) {
            return searchHashtag(page, tag)
        }

        mangaFromLoose(rawQuery)?.let { manga ->
            return MangasPage(listOf(manga), false)
        }
        if (rawQuery.isEmpty() || page > 1) {
            return MangasPage(emptyList(), false)
        }

        val bare = rawQuery.removePrefix("@").trim()
        val exact = if (!bare.contains(' ') && !bare.contains('/')) {
            runCatching { profileToManga(fetchProfile(bare)) }.getOrNull()
        } else {
            null
        }
        val searched = runCatching { topSearch(bare) }.getOrElse { error ->
            if (exact == null) throw error
            emptyList()
        }
        val mangas = buildList {
            if (exact != null) add(exact)
            addAll(searched)
        }.distinctBy { it.url.lowercase() }
        return MangasPage(mangas, false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!isInstagramSite(url.host)) return null
        return mangaFromSegments(url.pathSegments.filter { it.isNotBlank() })
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val segments = manga.url.substringBefore('?').split('/').filter { it.isNotBlank() }
        if (isPostPath(segments)) {
            val media = fetchMedia(shortcodeFrom(segments))
            val updated = if (fetchDetails) postToManga(media) else manga
            val loaded = if (fetchChapters) {
                listOf(
                    mediaToChapter(media)?.apply { chapter_number = 1f }
                        ?: throw IOException("Post non disponibile."),
                )
            } else {
                chapters
            }
            return SMangaUpdate(updated, loaded)
        }

        val user = fetchProfile(usernameFrom(segments))
        val updated = if (fetchDetails) profileToManga(user) else manga
        if (!fetchChapters) return SMangaUpdate(updated, chapters)

        val loaded = loadProfileChapters(user)
        if (fetchDetails && loaded.truncated) {
            updated.description = listOfNotNull(
                updated.description?.takeIf { it.isNotBlank() },
                "Sono mostrati solo gli ultimi ${loaded.chapters.size} post, per non fare troppe richieste a Instagram.",
            ).joinToString("\n\n")
        }
        return SMangaUpdate(updated, loaded.chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val segments = chapter.url.substringBefore('?').split('/').filter { it.isNotBlank() }
        val media = fetchMedia(shortcodeFrom(segments))
        val urls = pageUrls(media)
        if (urls.isEmpty()) {
            throw IOException(
                "Questo post non ha un'immagine. I video non si riproducono nel lettore e qui manca anche la copertina.",
            )
        }
        return urls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headers.newBuilder()
            .removeAll("Cookie")
            .removeAll("X-CSRFToken")
            .removeAll("X-IG-App-ID")
            .removeAll("X-ASBD-ID")
            .removeAll("X-IG-WWW-Claim")
            .removeAll("X-Requested-With")
            .set("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        return Request.Builder()
            .url(page.imageUrl!!)
            .headers(imageHeaders)
            .get()
            .build()
    }

    override fun getMangaUrl(manga: SManga): String = absolute(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = absolute(chapter.url)

    private suspend fun searchHashtag(page: Int, rawTag: String): MangasPage {
        val tag = rawTag.trim().removePrefix("#").substringBefore(' ').substringBefore('/').trim()
        if (!HASHTAG.matches(tag)) {
            throw IOException("Hashtag non valido. Usa lettere, numeri o underscore, senza #.")
        }
        val cursor = cursorFor("tag:$tag", page)
        val data = fetchTagSections(tag, cursor)
        val posts = sectionMedias(data).map { postToManga(it) }.distinctBy { it.url }
        val next = nextTagCursor(data)
        saveCursor("tag:$tag", page, next)
        return MangasPage(posts, next != null)
    }

    private suspend fun topSearch(query: String): List<SManga> {
        val data = getJson(apiUrl("web/search/topsearch/", mapOf("query" to query)))
        return data.getArrayOrNull("users").orEmpty().mapNotNull { element ->
            val user = element.asObject()?.getObjectOrNull("user") ?: return@mapNotNull null
            runCatching { profileToManga(user) }.getOrNull()
        }
    }

    private suspend fun mangaFromLoose(query: String): SManga? {
        val match = LOOSE_URL.matchEntire(query.trim()) ?: return null
        val segments = match.groupValues[1].split('/').filter { it.isNotBlank() }
        return mangaFromSegments(segments)
    }

    private suspend fun mangaFromSegments(segments: List<String>): SManga {
        if (segments.isEmpty()) {
            throw IOException("Incolla il link di un profilo o di un post.")
        }
        return when (segments[0].lowercase()) {
            "p", "reel", "reels", "tv" -> postToManga(fetchMedia(shortcodeFrom(segments)))
            "stories", "direct" -> {
                throw IOException("Storie e messaggi non sono supportati. Puoi aprire profili, post e il feed.")
            }
            "explore" -> {
                throw IOException("Per un hashtag compila il filtro Hashtag nella ricerca, senza #.")
            }
            "accounts", "about", "legal", "emails", "developer" -> {
                throw IOException("Questo URL di Instagram non è un profilo.")
            }
            else -> {
                if (segments.getOrNull(1)?.lowercase() in POST_MARKERS && segments.getOrNull(2) != null) {
                    postToManga(fetchMedia(segments[2]))
                } else {
                    profileToManga(fetchProfile(usernameFrom(segments)))
                }
            }
        }
    }

    private suspend fun fetchTimeline(cursor: PageCursor?): JsonObject {
        val form = linkedMapOf(
            "device_status" to "{}",
            "feed_view_info" to "[]",
            "is_async_ads_double_request" to "0",
            "is_async_ads_rti" to "0",
            "is_pull_to_refresh" to "0",
            "reason" to if (cursor == null) "cold_start_fetch" else "pagination",
        )
        if (cursor != null) form["max_id"] = cursor.maxId
        return postForm(apiUrl("api/v1/feed/timeline/"), form)
    }

    private suspend fun fetchTagSections(tag: String, cursor: PageCursor?): JsonObject {
        val form = linkedMapOf(
            "include_persistent" to "0",
            "surface" to "grid",
            "tab" to "recent",
        )
        if (cursor != null) {
            form["max_id"] = cursor.maxId
            cursor.page?.let { form["page"] = it }
        }
        return postForm(apiUrl("api/v1/tags/$tag/sections/"), form)
    }

    private suspend fun fetchProfile(username: String): JsonObject {
        val clean = cleanUsername(username)
        profiles[clean.lowercase()]?.let { return it }
        if (profiles.size > 100) profiles.clear()
        val data = getJson(apiUrl("api/v1/users/web_profile_info/", mapOf("username" to clean)))
        val user = data.getObjectOrNull("data")?.getObjectOrNull("user")
            ?: throw IOException("Profilo \"$clean\" non trovato.")
        profiles[clean.lowercase()] = user
        return user
    }

    private suspend fun fetchMedia(code: String): JsonObject {
        val clean = code.trim()
        medias[clean]?.let { return it }
        if (medias.size > 80) medias.clear()
        val data = getJson(apiUrl("api/v1/media/${idFromShortcode(clean)}/info/"))
        val items = data.getArrayOrNull("items").orEmpty().mapNotNull { it.asObject() }
        val media = items.firstOrNull { it.getStringOrNull("code") == clean }
            ?: items.firstOrNull()
            ?: throw IOException("Post non trovato o non disponibile.")
        medias[clean] = media
        return media
    }

    private suspend fun loadProfileChapters(user: JsonObject): ChapterLoad {
        if (user.getBooleanOrNull("is_private") == true && user.getBooleanOrNull("followed_by_viewer") == false) {
            throw IOException(
                "Questo profilo è privato. Seguilo dall'account con cui hai fatto l'accesso, poi riprova.",
            )
        }
        val userId = user.getStringOrNull("id")
            ?: user.getStringOrNull("pk")
            ?: user.longFlexible("pk")?.toString()
            ?: throw IOException("Impossibile leggere l'id del profilo.")

        val chapters = mutableListOf<SChapter>()
        var cursor: String? = null
        var truncated = false
        var guard = 0
        while (chapters.size < MAX_PROFILE_POSTS && guard < 6) {
            guard++
            val query = mutableMapOf("count" to PROFILE_PAGE_SIZE.toString())
            if (cursor != null) query["max_id"] = cursor
            val data = getJson(apiUrl("api/v1/feed/user/$userId/", query))
            val items = data.getArrayOrNull("items").orEmpty().mapNotNull { it.asObject() }
            if (items.isEmpty()) break
            for (item in items) {
                if (chapters.size >= MAX_PROFILE_POSTS) {
                    truncated = true
                    break
                }
                mediaToChapter(item)?.let { chapters += it }
            }
            val next = data.getStringOrNull("next_max_id")
            val more = data.getBooleanOrNull("more_available") == true && !next.isNullOrBlank()
            if (chapters.size >= MAX_PROFILE_POSTS && more) truncated = true
            if (!more) break
            cursor = next
        }

        val unique = chapters.distinctBy { it.url }
        val total = unique.size
        unique.forEachIndexed { index, chapter ->
            chapter.chapter_number = (total - index).toFloat()
        }
        return ChapterLoad(unique, truncated)
    }

    private fun profileToManga(user: JsonObject): SManga {
        val username = user.getStringOrNull("username")
            ?: throw IOException("Profilo senza nome utente.")
        val fullName = user.getStringOrNull("full_name").orEmpty()
        val followers = followerCount(user)
        val following = followingCount(user)
        val bio = user.getStringOrNull("biography").orEmpty().trim()
        return SManga.create().apply {
            url = "/$username/"
            title = if (fullName.isBlank()) username else "$username · $fullName"
            author = fullName.ifBlank { username }
            artist = username
            thumbnail_url = profilePic(user)
            genre = "Profilo"
            description = buildString {
                if (bio.isNotBlank()) {
                    append(bio)
                    append("\n\n")
                }
                if (followers != null) append("Follower: ").append(formatCount(followers))
                if (following != null) {
                    if (followers != null) append(" · ")
                    append("Seguiti: ").append(formatCount(following))
                }
                if (isNotEmpty() && (user.getBooleanOrNull("is_private") == true || user.getBooleanOrNull("is_verified") == true)) {
                    append('\n')
                }
                if (user.getBooleanOrNull("is_private") == true) append("Account privato.")
                if (user.getBooleanOrNull("is_verified") == true) {
                    if (user.getBooleanOrNull("is_private") == true) append(' ')
                    append("Account verificato.")
                }
            }.trim().ifBlank { null }
        }
    }

    private fun postToManga(media: JsonObject): SManga {
        val code = media.getStringOrNull("code")
            ?: media.getStringOrNull("shortcode")
            ?: throw IOException("Post senza codice.")
        val user = media.getObjectOrNull("user") ?: media.getObjectOrNull("owner")
        val username = user?.getStringOrNull("username").orEmpty().ifBlank { "instagram" }
        val fullName = user?.getStringOrNull("full_name").orEmpty()
        val caption = captionOf(media)
        val preview = snippet(caption, 42)
        return SManga.create().apply {
            url = "/p/$code/"
            title = if (preview.isBlank()) "@$username" else "@$username · $preview"
            author = username
            artist = fullName.ifBlank { username }
            thumbnail_url = bestImage(media)
            genre = if (isVideo(media)) "Video" else "Post"
            description = buildString {
                if (caption.isNotBlank()) {
                    append(caption.trim())
                    append("\n\n")
                }
                if (isVideo(media)) {
                    append("Video: nel lettore si vede solo la copertina.\n")
                }
                append("Per gli altri post del profilo, cerca @")
                append(username)
                append('.')
            }.trim()
        }
    }

    private fun mediaToChapter(media: JsonObject): SChapter? {
        val code = media.getStringOrNull("code") ?: media.getStringOrNull("shortcode") ?: return null
        val takenAt = media.longFlexible("taken_at") ?: media.longFlexible("taken_at_timestamp")
        return SChapter.create().apply {
            url = "/p/$code/"
            name = chapterName(media)
            if (takenAt != null) date_upload = takenAt * 1000
        }
    }

    private fun chapterName(media: JsonObject): String {
        val carousel = media.getArrayOrNull("carousel_media")
        val carouselCount = carousel?.size ?: 0
        val childVideo = carousel?.any { element ->
            element.asObject()?.let { isVideo(it) } == true
        } == true
        val video = isVideo(media) || childVideo
        val kind = when {
            carouselCount > 1 && video -> "Carosello · copertine"
            carouselCount > 1 -> "Carosello · $carouselCount foto"
            video -> "Video · copertina"
            else -> "Foto"
        }
        val takenAt = media.longFlexible("taken_at") ?: media.longFlexible("taken_at_timestamp")
        val date = takenAt?.let { chapterDate.format(Instant.ofEpochSecond(it)) }
        val caption = snippet(captionOf(media), 48)
        return listOfNotNull(kind, date, caption.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun pageUrls(media: JsonObject): List<String> {
        val carousel = media.getArrayOrNull("carousel_media")
        if (carousel != null && carousel.isNotEmpty()) {
            val urls = carousel.mapNotNull { element -> element.asObject()?.let { bestImage(it) } }
            if (urls.isNotEmpty()) return urls
        }
        return listOfNotNull(bestImage(media))
    }

    private fun bestImage(media: JsonObject): String? {
        val ranked = media.getObjectOrNull("image_versions2")
            ?.getArrayOrNull("candidates")
            .orEmpty()
            .mapNotNull { element ->
                val node = element.asObject() ?: return@mapNotNull null
                val url = node.getStringOrNull("url") ?: return@mapNotNull null
                url to (node.getIntOrNull("width") ?: 0)
            }
            .sortedByDescending { it.second }
        val preferred = ranked.firstOrNull { !it.first.contains(".heic", ignoreCase = true) } ?: ranked.firstOrNull()
        if (preferred != null) return preferred.first
        return media.getStringOrNull("display_url")
            ?: media.getStringOrNull("thumbnail_url")
            ?: media.getStringOrNull("thumbnail_src")
    }

    private fun profilePic(user: JsonObject): String? = user.getStringOrNull("profile_pic_url_hd")
        ?: user.getStringOrNull("profile_pic_url")
        ?: user.getObjectOrNull("hd_profile_pic_url_info")?.getStringOrNull("url")

    private fun followerCount(user: JsonObject): Long? = user.getObjectOrNull("edge_followed_by")?.longFlexible("count")
        ?: user.longFlexible("follower_count")

    private fun followingCount(user: JsonObject): Long? = user.getObjectOrNull("edge_follow")?.longFlexible("count")
        ?: user.longFlexible("following_count")

    private fun captionOf(media: JsonObject): String {
        val caption = media["caption"] ?: return ""
        if (caption is JsonNull) return ""
        return caption.asObject()?.getStringOrNull("text") ?: caption.stringOrNull.orEmpty()
    }

    private fun isVideo(media: JsonObject): Boolean {
        if (media.getIntOrNull("media_type") == 2) return true
        if (media.getBooleanOrNull("is_video") == true) return true
        val product = media.getStringOrNull("product_type")
        return product == "clips" || product == "igtv"
    }

    private fun timelineMedias(data: JsonObject): List<JsonObject> {
        val feedItems = data.getArrayOrNull("feed_items")
        val raw = if (feedItems != null) {
            feedItems.mapNotNull { element ->
                element.asObject()?.getObjectOrNull("media_or_ad")
            }
        } else {
            data.getArrayOrNull("items").orEmpty().mapNotNull { element ->
                val obj = element.asObject() ?: return@mapNotNull null
                obj.getObjectOrNull("media") ?: obj
            }
        }
        return raw.mapNotNull { asPost(it) }
    }

    private fun sectionMedias(data: JsonObject): List<JsonObject> = data.getArrayOrNull("sections").orEmpty().flatMap { sectionElement ->
        val medias = sectionElement.asObject()
            ?.getObjectOrNull("layout_content")
            ?.getArrayOrNull("medias")
            ?: JsonArray(emptyList())
        medias.mapNotNull { mediaElement ->
            mediaElement.asObject()?.getObjectOrNull("media")?.let { asPost(it) }
        }
    }

    private fun asPost(media: JsonObject): JsonObject? {
        val ad = media["ad_id"]
        if (ad != null && ad !is JsonNull) return null
        val code = media.getStringOrNull("code") ?: media.getStringOrNull("shortcode")
        if (code.isNullOrBlank()) return null
        return media
    }

    private fun nextMaxCursor(data: JsonObject): PageCursor? {
        if (data.getBooleanOrNull("more_available") == false) return null
        val maxId = data.getStringOrNull("next_max_id") ?: return null
        return PageCursor(maxId, null)
    }

    private fun nextFollowingCursor(data: JsonObject): PageCursor? {
        val maxId = data.getStringOrNull("next_max_id") ?: return null
        return PageCursor(maxId, null)
    }

    private fun nextTagCursor(data: JsonObject): PageCursor? {
        if (data.getBooleanOrNull("more_available") == false) return null
        val maxId = data.getStringOrNull("next_max_id") ?: return null
        val page = data["next_page"]?.let { element ->
            if (element is JsonNull) null else element.stringOrNull
        }
        return PageCursor(maxId, page)
    }

    private suspend fun getJson(url: HttpUrl): JsonObject = openApi(url, null)

    private suspend fun postForm(url: HttpUrl, form: Map<String, String>): JsonObject = openApi(url, form)

    private suspend fun openApi(url: HttpUrl, form: Map<String, String>?): JsonObject {
        requireSession()
        val response = if (form == null) {
            client.get(url, headers, CacheControl.FORCE_NETWORK, ensureSuccess = false)
        } else {
            val body = FormBody.Builder().apply {
                form.forEach { (key, value) -> add(key, value) }
            }.build()
            client.post(url, headers, body, ensureSuccess = false)
        }
        return response.use { parseResponse(it) }
    }

    private fun parseResponse(response: Response): JsonObject {
        val text = response.body.string()
        val path = response.request.url.encodedPath
        if ("/accounts/login" in path) throw IOException(LOGIN_MESSAGE)
        if ("/challenge/" in path) throw IOException(CHECKPOINT_MESSAGE)
        if (response.code == 429) throw IOException(RATE_MESSAGE)
        if (text.isBlank() || text.trimStart().startsWith("<")) throw IOException(LOGIN_MESSAGE)

        val data = try {
            jsonInstance.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            throw IOException(
                "Risposta di Instagram non riconosciuta. Se è comparso un controllo, completalo nella WebView e riprova.",
            )
        }
        val message = data.getStringOrNull("message").orEmpty()
        if (data.getBooleanOrNull("require_login") == true || message == "login_required") {
            throw IOException(LOGIN_MESSAGE)
        }
        if (message == "checkpoint_required") throw IOException(CHECKPOINT_MESSAGE)
        if (message == "feedback_required" || message.contains("Please wait a few minutes", ignoreCase = true)) {
            throw IOException(RATE_MESSAGE)
        }
        if (data.getStringOrNull("status") == "fail" || !response.isSuccessful) {
            throw IOException(failMessage(message.ifBlank { "HTTP ${response.code}" }))
        }
        return data
    }

    private fun failMessage(message: String): String {
        val short = message.replace(Regex("\\s+"), " ").take(180)
        return when {
            short.contains("login", ignoreCase = true) -> LOGIN_MESSAGE
            short.contains("wait", ignoreCase = true) || short.contains("feedback", ignoreCase = true) -> RATE_MESSAGE
            short.contains("not found", ignoreCase = true) -> "Profilo o post non trovato."
            short.isBlank() -> "Instagram ha rifiutato la richiesta."
            else -> "Instagram: $short"
        }
    }

    private fun stampInstagramRequest(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!isInstagramApi(request.url.host)) return chain.proceed(request)

        val cookies = readCookies()
        val builder = request.newBuilder().header("X-IG-WWW-Claim", wwwClaim.ifBlank { "0" })
        if (cookies.isNotEmpty()) {
            builder.header("Cookie", cookies.entries.joinToString("; ") { (name, value) -> "$name=$value" })
        }
        cookies["csrftoken"]?.takeIf { it.isNotBlank() }?.let { builder.header("X-CSRFToken", it) }
        val response = chain.proceed(builder.build())
        response.header("x-ig-set-www-claim")?.takeIf { it.isNotBlank() }?.let { wwwClaim = it }
        return response
    }

    private fun requireSession() {
        if (readCookies()["sessionid"].isNullOrBlank()) throw IOException(LOGIN_MESSAGE)
    }

    private fun loggedInUserId(): String {
        requireSession()
        val id = readCookies()["ds_user_id"]
        if (id.isNullOrBlank() || id.any { !it.isDigit() }) {
            throw IOException(
                "Sessione incompleta: manca l'utente connesso. Apri la WebView, accedi di nuovo a Instagram e riprova.",
            )
        }
        return id
    }

    private fun readCookies(): Map<String, String> {
        val raw = listOf("https://www.instagram.com/", "https://instagram.com/").firstNotNullOfOrNull { url ->
            try {
                CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
        }.orEmpty()
        if (raw.isBlank()) return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = part.substring(0, separator).trim()
            val value = part.substring(separator + 1).trim()
            if (name.isEmpty()) null else name to value
        }.toMap()
    }

    private fun apiUrl(path: String, query: Map<String, String> = emptyMap()): HttpUrl = baseUrl.toHttpUrl().newBuilder().apply {
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { addPathSegment(it) }
        query.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()

    private fun cursorFor(bucket: String, page: Int): PageCursor? {
        val safePage = if (page < 1) 1 else page
        if (safePage <= 1) {
            val prefix = "$bucket#"
            cursors.keys.filter { it.startsWith(prefix) }.toList().forEach(cursors::remove)
            return null
        }
        return cursors["$bucket#$safePage"]
            ?: throw IOException("Scorri le pagine in ordine, partendo dalla prima.")
    }

    private fun saveCursor(bucket: String, page: Int, cursor: PageCursor?) {
        if (cursor == null || cursor.maxId.isBlank()) return
        val safePage = if (page < 1) 1 else page
        cursors["$bucket#${safePage + 1}"] = cursor
    }

    private fun isPostPath(segments: List<String>): Boolean = segments.firstOrNull()?.lowercase() in POST_MARKERS

    private fun shortcodeFrom(segments: List<String>): String {
        val marker = segments.indexOfLast { it.lowercase() in POST_MARKERS }
        val code = if (marker >= 0) segments.getOrNull(marker + 1) else segments.lastOrNull()
        if (code.isNullOrBlank()) throw IOException("URL del post non valido.")
        return code
    }

    private fun usernameFrom(segments: List<String>): String {
        val name = segments.firstOrNull()?.trim().orEmpty()
        if (name.lowercase() in RESERVED) throw IOException("Questo URL di Instagram non è un profilo.")
        return cleanUsername(name)
    }

    private fun cleanUsername(raw: String): String {
        val name = raw.trim().removePrefix("@").trim('/')
        if (!USERNAME.matches(name)) throw IOException("Nome utente non valido.")
        return name
    }

    private fun idFromShortcode(shortcode: String): String {
        var value = BigInteger.ZERO
        val base = BigInteger.valueOf(64)
        for (char in shortcode) {
            val index = SHORTCODE_ALPHABET.indexOf(char)
            if (index < 0) throw IOException("Codice del post non valido.")
            value = value.multiply(base).add(BigInteger.valueOf(index.toLong()))
        }
        return value.toString()
    }

    private fun absolute(url: String): String = if (url.startsWith("http://") || url.startsWith("https://")) url else baseUrl + url

    private fun snippet(text: String, max: Int): String {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        if (oneLine.length <= max) return oneLine
        return oneLine.take(max - 1).trimEnd() + "…"
    }

    private fun formatCount(value: Long): String = "%,d".format(Locale.ITALY, value)

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.longFlexible(key: String): Long? {
        val element = this[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.longOrNull ?: element.jsonPrimitive.content.toLongOrNull()
    }

    private class HashtagFilter : Filter.Text("Hashtag")

    private data class PageCursor(val maxId: String, val page: String?)

    private data class ChapterLoad(val chapters: List<SChapter>, val truncated: Boolean)

    private companion object {
        const val IG_APP_ID = "936619743392459"
        const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"
        const val PROFILE_PAGE_SIZE = 12
        const val MAX_PROFILE_POSTS = 48
        const val LOGIN_MESSAGE =
            "Non sei connesso a Instagram. Apri la WebView di questa sorgente, accedi al tuo account, poi torna indietro e riprova."
        const val CHECKPOINT_MESSAGE =
            "Instagram chiede una verifica. Aprilo nella WebView della sorgente, completa il controllo, poi riprova."
        const val RATE_MESSAGE =
            "Instagram sta limitando le richieste. Aspetta qualche minuto e riprova."
        const val SHORTCODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        val USERNAME = Regex("^[A-Za-z0-9._]{1,30}$")
        val HASHTAG = Regex("^[\\p{L}\\p{N}_]{1,100}$")
        val LOOSE_URL = Regex(
            """^(?:https?://)?(?:www\.)?(?:instagram\.com|instagr\.am|m\.instagram\.com)(/[^\s#]+)/*$""",
            RegexOption.IGNORE_CASE,
        )
        val POST_MARKERS = setOf("p", "reel", "reels", "tv")
        val RESERVED = setOf(
            "p",
            "reel",
            "reels",
            "tv",
            "stories",
            "explore",
            "accounts",
            "direct",
            "about",
            "legal",
            "emails",
            "developer",
        )

        val chapterDate: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALIAN).withZone(ZoneId.systemDefault())

        fun isInstagramApi(host: String): Boolean = host.equals("www.instagram.com", ignoreCase = true) || host.equals("instagram.com", ignoreCase = true)

        fun isInstagramSite(host: String): Boolean {
            val normalized = host.lowercase()
            if ("cdn" in normalized) return false
            return normalized == "instagram.com" ||
                normalized.endsWith(".instagram.com") ||
                normalized == "instagr.am"
        }
    }
}
