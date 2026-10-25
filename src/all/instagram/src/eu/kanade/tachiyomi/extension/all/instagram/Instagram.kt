package eu.kanade.tachiyomi.extension.all.instagram

import android.util.Log
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
import keiyoushi.utils.jsonInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
 * Popolari is the logged-in home timeline. Più Recenti (the app's Latest tab)
 * is posts from followed accounts, taken from the same timeline when those
 * items are not ads or suggestions. Search returns profiles. A profile URL or
 * a plain username opens that profile directly. The optional Hashtag filter
 * lists recent posts for a tag.
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
    private var followingCache: List<FollowedAccount>? = null

    @Volatile
    private var wwwClaim: String = "0"

    @Volatile
    private var webTokens: WebTokens? = null

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        // One web API call at a time. About 0.6s apart, up to 30 a minute. The first call is not delayed.
        // A real 429 still backs off inside openApi.
        rateLimit(
            permits = 30,
            period = 60.seconds,
            interval = 600.milliseconds,
        ) { url -> isInstagramApi(url.host) }
        readTimeout(20, TimeUnit.SECONDS)
        connectTimeout(15, TimeUnit.SECONDS)
        callTimeout(25, TimeUnit.SECONDS)
        addInterceptor { chain ->
            val request = chain.request()
            if (request.header("User-Agent").isNullOrBlank()) {
                chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
            } else {
                chain.proceed(request)
            }
        }
        addNetworkInterceptor(::stampInstagramRequest)
        return this
    }

    override fun Headers.Builder.configureHeaders(): Headers.Builder = apply {
        set("User-Agent", USER_AGENT)
        set("Accept", "*/*")
        set("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Origin", "https://www.instagram.com")
        set("Referer", "https://www.instagram.com/")
        set("X-IG-App-ID", IG_APP_ID)
        set("X-ASBD-ID", ASBD_ID)
        set("X-IG-WWW-Claim", "0")
        set("X-Requested-With", "XMLHttpRequest")
        set("Sec-Fetch-Dest", "empty")
        set("Sec-Fetch-Mode", "cors")
        set("Sec-Fetch-Site", "same-origin")
    }

    override val supportsLatest: Boolean = true

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header(
            "Hashtag opzionale, senza #. Se lo compili, la ricerca mostra i post recenti di quel tag al posto dei profili.",
        ),
        HashtagFilter(),
    )

    override suspend fun getPopularManga(page: Int): MangasPage = guardPage("La home non è disponibile.") {
        loadFeedPage("home", page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = loadLatest(page)

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
        if (rawQuery.isEmpty() || page > 1) return MangasPage(emptyList(), false)

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
            val media = fetchMedia(shortcodeFrom(segments), allowNetwork = false)
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

        val username = usernameFrom(segments)
        val user = try {
            fetchProfile(username)
        } catch (error: InstagramHttpException) {
            if (error.kind == Failure.LOGIN || error.kind == Failure.CHECKPOINT || !fetchChapters) throw error
            null
        }
        val updated = if (fetchDetails && user != null) profileToManga(user) else manga
        if (!fetchChapters) return SMangaUpdate(updated, chapters)

        val loaded = loadProfileChapters(user, username)
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
        val media = fetchMedia(shortcodeFrom(segments), allowNetwork = true)
        val urls = pageUrls(media)
        if (urls.isEmpty()) {
            throw IOException(
                "Questo post non ha un'immagine. I video non si riproducono nel lettore e qui manca anche la copertina.",
            )
        }
        return urls.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = Headers.Builder().apply {
            cdnImageHeaders(USER_AGENT).forEach { (name, value) -> set(name, value) }
        }.build()
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

    private suspend fun loadFeedPage(bucket: String, page: Int): MangasPage {
        val collected = collectTimeline(FEED_PAGE_SIZE, 3, cursorFor(bucket, page)?.maxId) { cursor ->
            fetchTimeline(cursor?.let { PageCursor(it, null) })
        }
        val mangas = collected.posts.mapNotNull { media ->
            val manga = runCatching { postToManga(media) }.getOrNull() ?: return@mapNotNull null
            remember(media)
            manga
        }
        saveCursor(bucket, page, collected.nextCursor?.let { PageCursor(it, null) })
        return MangasPage(mangas, collected.nextCursor != null && mangas.isNotEmpty())
    }

    private suspend fun loadLatest(page: Int): MangasPage {
        val notes = mutableListOf<String>()
        try {
            val saved = latestCursor(page)
            if (saved != null && saved.startsWith(ACCOUNT_CURSOR)) {
                return loadOneFollowedAccount(page, saved.removePrefix(ACCOUNT_CURSOR).toIntOrNull() ?: 0, notes)
            }
            var calls = 0
            var cursor = saved
            val posts = linkedMapOf<String, JsonObject>()
            var next: String? = null
            while (calls < 2 && posts.size < FEED_PAGE_SIZE) {
                calls++
                val data = try {
                    fetchTimeline(cursor?.let { PageCursor(it, null) })
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    note(notes, "timeline", error)
                    break
                }
                val found = latestTimelinePosts(data)
                note(notes, "timeline", 200, "${found.size} post dopo filtro ads/suggested")
                found.forEach { media ->
                    val code = codeOf(media) ?: return@forEach
                    posts.putIfAbsent(code, media)
                }
                next = nextCursorId(data)
                if (posts.isNotEmpty() || next == null) break
                cursor = next
            }
            if (posts.isNotEmpty()) {
                saveCursor("latest", page, next?.let { PageCursor(it, null) })
                val ready = postsToPage(posts.values, next != null)
                if (ready.mangas.isEmpty()) throw IOException(latestFailure(notes))
                return ready
            }
            if (calls < LATEST_MAX_CALLS) {
                val accountPage = loadFollowedFallback(page, notes, calls)
                if (accountPage != null) return accountPage
            }
            throw IOException(latestFailure(notes))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            if (error.message.orEmpty().startsWith("Più Recenti non ha caricato")) throw error
            note(notes, "eccezione", null, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            throw IOException(latestFailure(notes))
        } catch (error: Exception) {
            note(notes, "eccezione", null, "${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            throw IOException(latestFailure(notes))
        }
    }

    private suspend fun loadFollowedFallback(page: Int, notes: MutableList<String>, callsIn: Int): MangasPage? {
        var calls = callsIn
        val userId = resolveUserId(notes, calls)
        if (userId == null) return null
        if (resolveUserIdUsedCall) calls++
        var accounts = followingCache?.takeIf { it.isNotEmpty() }
        if (accounts == null && calls < LATEST_MAX_CALLS) {
            calls++
            accounts = requestFollowing(notes, userId, "www.instagram.com", "following-www")
        }
        if (accounts.isNullOrEmpty() && calls < LATEST_MAX_CALLS) {
            calls++
            accounts = requestFollowing(notes, userId, "i.instagram.com", "following-i")
        }
        val list = accounts.orEmpty()
        if (list.isEmpty() || calls >= LATEST_MAX_CALLS) return null
        return loadOneFollowedAccount(page, 0, notes)
    }

    private var resolveUserIdUsedCall: Boolean = false

    private suspend fun resolveUserId(notes: MutableList<String>, calls: Int): String? {
        resolveUserIdUsedCall = false
        val fromCookies = userIdFromCookies(readCookies())
        if (fromCookies != null) {
            val source = if (readCookies()["ds_user_id"]?.all { it.isDigit() } == true) "ds_user_id" else "sessionid"
            note(notes, "utente", 200, source)
            return fromCookies
        }
        if (calls >= LATEST_MAX_CALLS) {
            note(notes, "utente", null, "manca ds_user_id e sessionid non ha un id")
            return null
        }
        resolveUserIdUsedCall = true
        return try {
            val data = getJson(apiUrl("api/v1/accounts/current_user/", mapOf("edit" to "true")))
            val id = currentUserId(data)
            note(notes, "current_user", 200, if (id == null) "id assente" else "id ok")
            id
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            note(notes, "current_user", error)
            null
        }
    }

    private suspend fun loadOneFollowedAccount(page: Int, index: Int, notes: MutableList<String>): MangasPage {
        val accounts = followingCache ?: run {
            val userId = resolveUserId(notes, 0) ?: throw IOException(latestFailure(notes))
            try {
                fetchFollowing(userId, "www.instagram.com").also { followingCache = it }
            } catch (error: Exception) {
                note(notes, "following-www", error)
                try {
                    fetchFollowing(userId, "i.instagram.com").also { followingCache = it }
                } catch (fallback: Exception) {
                    note(notes, "following-i", fallback)
                    throw IOException(latestFailure(notes))
                }
            }
        }
        if (index >= accounts.size) {
            note(notes, "feed-utente", null, "nessun altro account")
            throw IOException(latestFailure(notes))
        }
        val account = accounts[index]
        val posts = try {
            fetchUserFeed(account.id)
        } catch (error: Exception) {
            note(notes, "feed-utente", error)
            throw IOException(latestFailure(notes))
        }
        note(notes, "feed-utente", 200, "@${account.username} ${posts.size} post")
        if (posts.isEmpty()) throw IOException(latestFailure(notes))
        val next = if (index + 1 < accounts.size) "$ACCOUNT_CURSOR${index + 1}" else null
        saveCursor("latest", page, next?.let { PageCursor(it, null) })
        return postsToPage(posts, next != null)
    }

    private suspend fun fetchTimeline(cursor: PageCursor?): JsonObject {
        val form = linkedMapOf(
            "device_status" to "{}",
            "feed_view_info" to "[]",
            "is_async_ads_double_request" to "0",
            "is_async_ads_rti" to "0",
            "is_pull_to_refresh" to if (cursor == null) "1" else "0",
            "has_stories" to "0",
            "reason" to if (cursor == null) "cold_start_fetch" else "pagination",
        )
        if (cursor != null) form["max_id"] = cursor.maxId
        return postForm(
            apiUrl("api/v1/feed/timeline/"),
            form,
            referer = "$baseUrl/",
        )
    }

    private suspend fun requestFollowing(
        notes: MutableList<String>,
        userId: String,
        host: String,
        step: String,
    ): List<FollowedAccount>? = try {
        val accounts = fetchFollowing(userId, host)
        note(notes, step, 200, "${accounts.size} account")
        if (accounts.isNotEmpty()) followingCache = accounts
        accounts
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        note(notes, step, error)
        null
    }

    private suspend fun fetchFollowing(userId: String, host: String): List<FollowedAccount> {
        val extra = if (host == "i.instagram.com") {
            mapOf("User-Agent" to MOBILE_USER_AGENT, "X-IG-App-ID" to MOBILE_APP_ID)
        } else {
            emptyMap()
        }
        val data = getJson(
            apiUrl("api/v1/friendships/$userId/following/", mapOf("count" to "12"), host),
            referer = "$baseUrl/",
            extraHeaders = extra,
        )
        return followingAccounts(data)
    }

    private suspend fun fetchUserFeed(userId: String): List<JsonObject> {
        val data = getJson(
            apiUrl("api/v1/feed/user/$userId/", mapOf("count" to "6")),
            referer = "$baseUrl/",
        )
        return userFeedMedias(data)
    }

    private fun postsToPage(posts: Collection<JsonObject>, hasNext: Boolean): MangasPage {
        val mangas = posts.mapNotNull { media ->
            val manga = runCatching { postToManga(media) }.getOrNull() ?: return@mapNotNull null
            remember(media)
            manga
        }
        return MangasPage(mangas, hasNext && mangas.isNotEmpty())
    }

    private fun latestCursor(page: Int): String? = cursorFor("latest", page)?.maxId

    private fun note(notes: MutableList<String>, step: String, error: Exception) {
        val http = error as? InstagramHttpException
        note(notes, step, http?.status, "${error.javaClass.simpleName}: ${http?.snippet ?: error.message.orEmpty()}")
    }

    private fun note(notes: MutableList<String>, step: String, status: Int?, detail: String) {
        val line = diagnosticLine(step, status, detail)
        notes += line
        Log.w(LOG_TAG, line)
    }

    private suspend fun <T> guardPage(fallback: String, block: suspend () -> T): T {
        try {
            return block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: InstagramHttpException) {
            throw error
        } catch (error: IOException) {
            throw error
        } catch (_: Exception) {
            throw IOException(fallback)
        }
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
        val data = getJson(
            apiUrl("api/v1/users/web_profile_info/", mapOf("username" to clean)),
            referer = "$baseUrl/$clean/",
        )
        val user = data.getObjectOrNull("data")?.getObjectOrNull("user")
            ?: throw IOException("Profilo \"$clean\" non trovato.")
        profiles[clean.lowercase()] = user
        return user
    }

    private suspend fun fetchMedia(code: String, allowNetwork: Boolean = true): JsonObject {
        val clean = code.trim()
        val cached = medias[clean]
        // List rows already have title and thumbnail. Opening a chapter may refresh the full images.
        if (cached != null && !allowNetwork) return cached
        val fresh = try {
            val data = getJson(apiUrl("api/v1/media/${idFromShortcode(clean)}/info/"))
            val items = data.getArrayOrNull("items").orEmpty().mapNotNull { it.asObject() }
            items.firstOrNull { it.getStringOrNull("code") == clean || it.getStringOrNull("shortcode") == clean }
                ?: items.firstOrNull()
        } catch (error: InstagramHttpException) {
            if (cached != null && error.kind != Failure.LOGIN && error.kind != Failure.CHECKPOINT) {
                null
            } else {
                throw error
            }
        }
        val chosen = when {
            fresh == null -> cached
            cached == null -> fresh
            pageUrls(fresh).size < pageUrls(cached).size -> cached
            pageUrls(fresh).size > pageUrls(cached).size -> fresh
            else -> if (pageUrls(fresh).all { isInstagramCdnUrl(it) }) fresh else cached
        } ?: throw IOException("Post non trovato o non disponibile.")
        medias[clean] = chosen
        return chosen
    }

    private suspend fun loadProfileChapters(user: JsonObject?, username: String): ChapterLoad {
        if (user?.getBooleanOrNull("is_private") == true && user.getBooleanOrNull("followed_by_viewer") == false) {
            throw IOException(
                "Questo profilo è privato. Seguilo dall'account con cui hai fatto l'accesso, poi riprova.",
            )
        }

        val chapters = mutableListOf<SChapter>()
        var sawPhoto = false
        fun take(media: JsonObject) {
            val chapter = runCatching { mediaToChapter(media) }.getOrNull() ?: return
            remember(media)
            if (countsAsPhoto(media)) sawPhoto = true
            addChapter(chapters, chapter)
        }
        // Logged-in web_profile_info often sets edges to null. That must not abort the list.
        if (user != null) {
            runCatching { profilePostNodes(user) }.getOrDefault(emptyList()).forEach(::take)
        }

        val userId = user?.let(::userIdOf)
        var feedError: InstagramHttpException? = null
        if (chapters.isEmpty() && userId != null) {
            try {
                appendFeed(userId, username, ::take)
            } catch (error: InstagramHttpException) {
                if (error.kind == Failure.LOGIN || error.kind == Failure.CHECKPOINT) throw error
                feedError = error
            }
        }

        // A reels-only feed must not hide the photo grid. Ask the web posts query and keep its order.
        if (!sawPhoto) {
            val videosOnly = chapters.toList()
            chapters.clear()
            try {
                appendGraphqlPosts(username, ::take)
            } catch (error: InstagramHttpException) {
                if (chapters.isEmpty()) {
                    chapters.addAll(videosOnly)
                    throw feedError ?: error
                }
            }
            videosOnly.forEach { addChapter(chapters, it) }
        }
        if (chapters.isEmpty()) {
            throw feedError ?: IOException("Questo profilo non ha post visibili.")
        }

        val total = chapters.size
        val truncated = total >= MAX_PROFILE_POSTS
        chapters.forEachIndexed { index, chapter ->
            chapter.chapter_number = (total - index).toFloat()
        }
        return ChapterLoad(chapters, truncated)
    }

    private suspend fun appendFeed(userId: String, username: String, take: (JsonObject) -> Unit) {
        var cursor: String? = null
        var guard = 0
        var added = 0
        while (added < MAX_PROFILE_POSTS && guard < 4) {
            guard++
            val query = mutableMapOf("count" to PROFILE_PAGE_SIZE.toString())
            if (cursor != null) query["max_id"] = cursor
            val data = getJson(
                apiUrl("api/v1/feed/user/$userId/", query),
                referer = "$baseUrl/$username/",
            )
            val items = data.getArrayOrNull("items").orEmpty().mapNotNull { it.asObject() }
            if (items.isEmpty()) break
            items.forEach { item ->
                take(item)
                added++
            }
            val next = data.getStringOrNull("next_max_id")
            val more = data.getBooleanOrNull("more_available") == true && !next.isNullOrBlank()
            if (!more) break
            cursor = next
        }
    }

    private suspend fun appendGraphqlPosts(username: String, take: (JsonObject) -> Unit) {
        val tokens = webApiTokens(username)
        var after: String? = null
        var guard = 0
        while (guard < 4) {
            guard++
            val form = linkedMapOf(
                "av" to tokens.actorId,
                "fb_dtsg" to tokens.fbDtsg,
                "lsd" to tokens.lsd,
                "fb_api_caller_class" to "RelayModern",
                "fb_api_req_friendly_name" to POSTS_QUERY_NAME,
                "server_timestamps" to "true",
                "doc_id" to POSTS_DOC_ID,
                "variables" to postsVariables(username, after),
            )
            val data = postForm(
                apiUrl("api/graphql"),
                form,
                referer = "$baseUrl/$username/",
                extraHeaders = mapOf(
                    "X-FB-LSD" to tokens.lsd,
                    "X-FB-Friendly-Name" to POSTS_QUERY_NAME,
                ),
            )
            val connection = data.getObjectOrNull("data")?.getObjectOrNull(POSTS_CONNECTION)
                ?: data.getObjectOrNull("data")
                    ?.getObjectOrNull("user")
                    ?.getObjectOrNull("edge_owner_to_timeline_media")
                ?: break
            val edges = connection.getArrayOrNull("edges").orEmpty()
            if (edges.isEmpty()) break
            edges.forEach { edge ->
                val node = edge.asObject()?.getObjectOrNull("node") ?: return@forEach
                take(node)
            }
            val pageInfo = connection.getObjectOrNull("page_info")
            val next = pageInfo?.getStringOrNull("end_cursor")
            val more = pageInfo?.getBooleanOrNull("has_next_page") == true && !next.isNullOrBlank()
            if (!more) break
            after = next
        }
    }

    private suspend fun webApiTokens(username: String): WebTokens {
        val session = readCookies()["sessionid"].orEmpty()
        webTokens?.takeIf { it.session == session }?.let { return it }
        val response = client.get(
            "$baseUrl/$username/".toHttpUrl(),
            requestHeaders("$baseUrl/$username/", mapOf("Accept" to "text/html,application/xhtml+xml")),
            CacheControl.FORCE_NETWORK,
            ensureSuccess = false,
        )
        val html = response.use { loaded ->
            if (loaded.code == 429) throw InstagramHttpException(Failure.RATE, RATE_MESSAGE)
            loaded.body.string()
        }
        if ("DTSGInitialData" !in html && ("/accounts/login" in html || "login" in html.lowercase())) {
            throw InstagramHttpException(Failure.LOGIN, LOGIN_MESSAGE)
        }
        val dtsg = DTSG_TOKEN.find(html)?.groupValues?.get(1)
        val lsd = LSD_TOKEN.find(html)?.groupValues?.get(1) ?: LSD_INPUT.find(html)?.groupValues?.get(1)
        val actor = EIMU_TOKEN.find(html)?.groupValues?.get(1) ?: readCookies()["ds_user_id"]
        if (dtsg.isNullOrBlank() || lsd.isNullOrBlank() || actor.isNullOrBlank()) {
            throw InstagramHttpException(Failure.OTHER, SESSION_PAGE_MESSAGE)
        }
        return WebTokens(session, dtsg, lsd, actor).also { webTokens = it }
    }

    private fun remember(media: JsonObject) {
        val code = media.getStringOrNull("code") ?: media.getStringOrNull("shortcode") ?: return
        if (medias.size > 200 && !medias.containsKey(code)) medias.clear()
        val existing = medias[code]
        if (existing == null || pageUrls(media).size > pageUrls(existing).size) {
            medias[code] = media
        }
    }

    private fun addChapter(chapters: MutableList<SChapter>, chapter: SChapter?) {
        if (chapter == null || chapters.size >= MAX_PROFILE_POSTS) return
        if (chapters.any { it.url == chapter.url }) return
        chapters += chapter
    }

    private fun userIdOf(user: JsonObject): String? = user.getStringOrNull("id")
        ?: user.getStringOrNull("pk")
        ?: user.longFlexible("pk")?.toString()

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
        val username = authorOf(media).orEmpty().ifBlank { "instagram" }
        val user = media.getObjectOrNull("user") ?: media.getObjectOrNull("owner")
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
        val code = codeOf(media) ?: return null
        val takenAt = takenAtOf(media)
        return SChapter.create().apply {
            url = "/p/$code/"
            name = chapterName(media)
            if (takenAt != null) date_upload = takenAt * 1000
        }
    }

    private fun chapterName(media: JsonObject): String {
        val count = carouselCount(media)
        val childVideo = media.getArrayOrNull("carousel_media").orEmpty().any { element ->
            element.asObject()?.let { isVideo(it) } == true
        } || media.getObjectOrNull("edge_sidecar_to_children")?.getArrayOrNull("edges").orEmpty().any { edge ->
            edge.asObject()?.getObjectOrNull("node")?.let { isVideo(it) } == true
        }
        val video = isVideo(media) || childVideo
        val kind = when {
            count > 1 && video -> "Carosello · copertine"
            count > 1 -> "Carosello · $count foto"
            video -> "Video · copertina"
            else -> "Foto"
        }
        val takenAt = media.longFlexible("taken_at") ?: media.longFlexible("taken_at_timestamp")
        val date = takenAt?.let { chapterDate.format(Instant.ofEpochSecond(it)) }
        val caption = snippet(captionOf(media), 48)
        return listOfNotNull(kind, date, caption.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun profilePic(user: JsonObject): String? = user.getStringOrNull("profile_pic_url_hd")
        ?: user.getStringOrNull("profile_pic_url")
        ?: user.getObjectOrNull("hd_profile_pic_url_info")?.getStringOrNull("url")

    private fun followerCount(user: JsonObject): Long? = user.getObjectOrNull("edge_followed_by")?.longFlexible("count")
        ?: user.longFlexible("follower_count")

    private fun followingCount(user: JsonObject): Long? = user.getObjectOrNull("edge_follow")?.longFlexible("count")
        ?: user.longFlexible("following_count")

    private fun sectionMedias(data: JsonObject): List<JsonObject> = data.getArrayOrNull("sections").orEmpty().flatMap { sectionElement ->
        val medias = sectionElement.asObject()
            ?.getObjectOrNull("layout_content")
            ?.getArrayOrNull("medias")
            ?: JsonArray(emptyList())
        medias.mapNotNull { mediaElement ->
            mediaElement.asObject()?.getObjectOrNull("media")?.let { organicPost(it) }
        }
    }

    private fun nextTagCursor(data: JsonObject): PageCursor? {
        if (data.getBooleanOrNull("more_available") == false) return null
        val maxId = data.getStringOrNull("next_max_id") ?: return null
        val page = data["next_page"]?.let { element ->
            if (element is JsonNull) null else element.stringOrNull
        }
        return PageCursor(maxId, page)
    }

    private suspend fun getJson(
        url: HttpUrl,
        referer: String? = null,
        http: OkHttpClient = client,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject = openApi(url, null, referer, extraHeaders, http)

    private suspend fun postForm(
        url: HttpUrl,
        form: Map<String, String>,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject = openApi(url, form, referer, extraHeaders)

    private suspend fun openApi(
        url: HttpUrl,
        form: Map<String, String>?,
        referer: String?,
        extraHeaders: Map<String, String>,
        http: OkHttpClient = client,
    ): JsonObject {
        requireSession()
        var attempt = 0
        while (true) {
            val callHeaders = requestHeaders(referer, extraHeaders)
            val response = if (form == null) {
                http.get(url, callHeaders, CacheControl.FORCE_NETWORK, ensureSuccess = false)
            } else {
                val body = FormBody.Builder().apply {
                    form.forEach { (key, value) -> add(key, value) }
                }.build()
                http.post(url, callHeaders, body, ensureSuccess = false)
            }
            try {
                return response.use { parseResponse(it) }
            } catch (error: InstagramHttpException) {
                val retry = error.kind == Failure.RATE && attempt < 2
                if (!retry) throw error
                attempt++
                delay(if (attempt == 1) 4_000 else 12_000)
            }
        }
    }

    private fun requestHeaders(referer: String?, extraHeaders: Map<String, String>): Headers = headers.newBuilder().apply {
        if (!referer.isNullOrBlank()) set("Referer", referer)
        extraHeaders.forEach { (name, value) -> set(name, value) }
    }.build()

    private fun parseResponse(response: Response): JsonObject {
        val text = response.body.string()
        val path = response.request.url.encodedPath
        fun raise(kind: Failure, message: String): Nothing = throw InstagramHttpException(kind, message, response.code, clipDetail(text.ifBlank { message }))
        if ("/accounts/login" in path) raise(Failure.LOGIN, LOGIN_MESSAGE)
        if ("/challenge/" in path || "/auth_platform/" in path) raise(Failure.CHECKPOINT, CHECKPOINT_MESSAGE)
        if (response.code == 401) raise(Failure.LOGIN, LOGIN_MESSAGE)
        if (response.code == 429 || text.contains("Please wait a few minutes", ignoreCase = true)) {
            raise(Failure.RATE, RATE_MESSAGE)
        }
        if (text.isBlank()) raise(Failure.OTHER, EMPTY_MESSAGE)
        if (text.trimStart().startsWith("<")) raise(Failure.OTHER, htmlMessage(text))

        val data = try {
            jsonInstance.parseToJsonElement(text).asObject()
                ?: throw IllegalArgumentException("not an object")
        } catch (_: Exception) {
            raise(
                Failure.OTHER,
                "Risposta di Instagram non riconosciuta. Se è comparso un controllo, completalo nella WebView e riprova.",
            )
        }
        classifyFailure(data, response.code)?.let { error ->
            error.status = response.code
            error.snippet = clipDetail(text)
            throw error
        }
        if (data.getStringOrNull("status") == "fail" || !response.isSuccessful) {
            raise(Failure.OTHER, otherMessage(data, response.code))
        }
        return data
    }

    private fun classifyFailure(data: JsonObject, code: Int): InstagramHttpException? {
        val message = data.getStringOrNull("message").orEmpty()
        val feedback = data.getStringOrNull("feedback_message").orEmpty()
        val checkpointUrl = data.getStringOrNull("checkpoint_url").orEmpty()
        val combined = "$message $feedback ${data.getStringOrNull("feedback_title").orEmpty()}"
        if (combined.contains("Please wait a few minutes", ignoreCase = true) || code == 429) {
            return InstagramHttpException(Failure.RATE, RATE_MESSAGE)
        }
        if (data.getBooleanOrNull("require_login") == true || message.equals("login_required", ignoreCase = true)) {
            return InstagramHttpException(Failure.LOGIN, LOGIN_MESSAGE)
        }
        if (
            message == "checkpoint_required" ||
            message == "challenge_required" ||
            checkpointUrl.isNotBlank()
        ) {
            return InstagramHttpException(Failure.CHECKPOINT, CHECKPOINT_MESSAGE)
        }
        if (message == "feedback_required") {
            val detail = feedback.replace(Regex("\\s+"), " ").trim().take(160)
            val text = if (detail.isBlank()) FEEDBACK_MESSAGE else "$FEEDBACK_MESSAGE\n$detail"
            return InstagramHttpException(Failure.OTHER, text)
        }
        return null
    }

    private fun htmlMessage(text: String): String {
        val lower = text.lowercase()
        return when {
            "checkpoint" in lower || "challenge" in lower -> CHECKPOINT_MESSAGE
            "/accounts/login" in lower || "login" in lower -> LOGIN_MESSAGE
            else -> HTML_MESSAGE
        }
    }

    private fun otherMessage(data: JsonObject, code: Int): String {
        val message = data.getStringOrNull("message").orEmpty().ifBlank { "HTTP $code" }
        val short = message.replace(Regex("\\s+"), " ").trim().take(180)
        return when {
            short.contains("login", ignoreCase = true) -> LOGIN_MESSAGE
            short.contains("checkpoint", ignoreCase = true) || short.contains("challenge", ignoreCase = true) -> CHECKPOINT_MESSAGE
            short.contains("not found", ignoreCase = true) -> "Profilo o post non trovato."
            short.contains("schema", ignoreCase = true) || short.contains("asset://", ignoreCase = true) -> SCHEMA_MESSAGE
            short.isBlank() -> "Instagram ha rifiutato la richiesta (HTTP $code)."
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

    private fun apiUrl(
        path: String,
        query: Map<String, String> = emptyMap(),
        host: String = "www.instagram.com",
    ): HttpUrl = "https://$host".toHttpUrl().newBuilder().apply {
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

    private class HashtagFilter : Filter.Text("Hashtag")

    private data class PageCursor(val maxId: String, val page: String?)

    private data class ChapterLoad(val chapters: List<SChapter>, val truncated: Boolean)

    private data class WebTokens(val session: String, val fbDtsg: String, val lsd: String, val actorId: String)

    private enum class Failure { LOGIN, CHECKPOINT, RATE, OTHER }

    private class InstagramHttpException(
        val kind: Failure,
        message: String,
        var status: Int? = null,
        var snippet: String? = null,
    ) : IOException(message)

    private companion object {
        const val IG_APP_ID = "936619743392459"
        const val MOBILE_APP_ID = "567067343352427"
        const val LOG_TAG = "Instagram"
        const val ACCOUNT_CURSOR = "followed-account:"
        const val MOBILE_USER_AGENT =
            "Instagram 361.0.0.46.88 Android (31/12; 420dpi; 1080x2400; Google/google; Pixel 6; oriole; oriole; en_US; 674674275)"
        const val ASBD_ID = "359341"
        const val POSTS_DOC_ID = "29104886249145907"
        const val POSTS_QUERY_NAME = "PolarisProfilePostsTabContentQuery_connection"
        const val POSTS_CONNECTION = "xdt_api__v1__feed__user_timeline_graphql_connection"
        const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Mobile/15E148 Safari/604.1"
        const val FEED_PAGE_SIZE = 12
        const val PROFILE_PAGE_SIZE = 12
        const val MAX_PROFILE_POSTS = 48
        const val LOGIN_MESSAGE =
            "Non sei connesso a Instagram. Apri la WebView di questa sorgente, accedi al tuo account, poi torna indietro e riprova."
        const val CHECKPOINT_MESSAGE =
            "Instagram chiede una verifica. Aprilo nella WebView della sorgente, completa il controllo, poi riprova."
        const val RATE_MESSAGE =
            "Instagram sta limitando le richieste. Aspetta qualche minuto e riprova."
        const val FEEDBACK_MESSAGE =
            "Instagram ha rifiutato l'elenco dei post. Non è un limite di velocità: apri la WebView della sorgente, ricarica il profilo, poi riprova."
        const val HTML_MESSAGE =
            "Instagram ha risposto con una pagina web invece dei dati. Apri la WebView della sorgente, accedi di nuovo, poi riprova."
        const val EMPTY_MESSAGE =
            "Instagram ha risposto vuoto. Apri la WebView della sorgente e riprova."
        const val SCHEMA_MESSAGE =
            "Instagram ha rifiutato il formato della richiesta. Apri la WebView della sorgente, ricarica la pagina, poi riprova."
        const val SESSION_PAGE_MESSAGE =
            "Instagram non ha fornito i dati della sessione web. Apri la WebView della sorgente, ricarica il profilo, poi riprova."
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

        val DTSG_TOKEN = Regex("\"DTSGInitialData\",\\[\\],\\{[^{}]*\"token\":\"([^\"]+)\"")
        val LSD_TOKEN = Regex("\"LSD\",\\[\\],\\{[^{}]*\"token\":\"([^\"]+)\"")
        val LSD_INPUT = Regex("name=\"lsd\" value=\"([^\"]+)\"")
        val EIMU_TOKEN = Regex("\"IG_USER_EIMU\":\"?(\\d+)")

        fun postsVariables(username: String, after: String?): String {
            val afterJson = if (after == null) "null" else "\"${after.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            return "{" +
                "\"after\":$afterJson," +
                "\"before\":null," +
                "\"data\":{" +
                "\"count\":$PROFILE_PAGE_SIZE," +
                "\"include_reel_media_seen_timestamp\":true," +
                "\"include_relationship_info\":true," +
                "\"latest_besties_reel_media\":true," +
                "\"latest_reel_media\":true" +
                "}," +
                "\"first\":$PROFILE_PAGE_SIZE," +
                "\"include_multi_captions\":true," +
                "\"last\":null," +
                "\"username\":\"$username\"," +
                "\"__relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider\":true," +
                "\"__relay_internal__pv__PolarisShortDramaEnabledrelayprovider\":true," +
                "\"__relay_internal__pv__PolarisReelsRecoDebugOverlayEnabledrelayprovider\":false," +
                "\"__relay_internal__pv__PolarisSEOIsCrawlerrelayprovider\":false," +
                "\"__relay_internal__pv__PolarisThreadsStyleCommentsEnabledrelayprovider\":false" +
                "}"
        }

        fun isInstagramApi(host: String): Boolean {
            val normalized = host.lowercase()
            return normalized == "www.instagram.com" ||
                normalized == "instagram.com" ||
                normalized == "i.instagram.com"
        }

        fun isInstagramSite(host: String): Boolean {
            val normalized = host.lowercase()
            if ("cdn" in normalized) return false
            return normalized == "instagram.com" ||
                normalized.endsWith(".instagram.com") ||
                normalized == "instagr.am"
        }
    }
}
