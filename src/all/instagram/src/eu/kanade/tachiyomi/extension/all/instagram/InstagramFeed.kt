package eu.kanade.tachiyomi.extension.all.instagram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException

internal const val IMAGE_REFERER = "https://www.instagram.com/"

internal const val FOLLOW_BATCH = 4

internal const val LATEST_MAX_CALLS = 3

internal const val SEGUITI_BUDGET_MS = 9_000L

internal const val SEGUITI_NONE =
    "Non risultano account seguiti. Seguili su Instagram, poi apri la WebView della sorgente e riprova."

internal const val SEGUITI_EMPTY =
    "Nessun post recente dai seguiti. Apri la WebView della sorgente, controlla l'accesso, poi riprova."

internal const val SEGUITI_FAIL =
    "Seguiti non disponibili. Apri la WebView della sorgente e riprova."

internal data class FollowedAccount(val id: String, val username: String)

internal data class TimelinePage(val posts: List<JsonObject>, val nextCursor: String?)

internal data class FollowedPage(val posts: List<JsonObject>, val nextIndex: Int, val hasNext: Boolean)

internal data class AccountBatch(val accounts: List<FollowedAccount>, val nextIndex: Int, val hasNext: Boolean)

internal class FollowedSink {
    val completed = mutableSetOf<Int>()
    val tagged = mutableListOf<Pair<Int, JsonObject>>()

    fun add(index: Int, posts: List<JsonObject>) {
        synchronized(this) {
            posts.forEach { tagged += index to it }
            completed += index
        }
    }

    fun fail(index: Int) {
        synchronized(this) { completed += index }
    }
}

internal fun cdnImageHeaders(userAgent: String): List<Pair<String, String>> = listOf(
    "User-Agent" to userAgent,
    "Referer" to IMAGE_REFERER,
    "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
)

internal fun codeOf(media: JsonObject): String? = media.getStringOrNull("code")?.takeIf { it.isNotBlank() }
    ?: media.getStringOrNull("shortcode")?.takeIf { it.isNotBlank() }

internal fun authorOf(media: JsonObject): String? {
    val user = media.getObjectOrNull("user") ?: media.getObjectOrNull("owner") ?: return null
    return user.getStringOrNull("username")?.takeIf { it.isNotBlank() }
}

internal fun authorId(media: JsonObject): String? {
    val user = media.getObjectOrNull("user") ?: media.getObjectOrNull("owner") ?: return null
    return user.getStringOrNull("pk_id")
        ?: user.getStringOrNull("pk")
        ?: user.getStringOrNull("id")
        ?: user.longFlexible("pk")?.toString()
        ?: user.longFlexible("id")?.toString()
}

internal fun takenAtOf(media: JsonObject): Long? = media.longFlexible("taken_at")
    ?: media.longFlexible("taken_at_timestamp")

internal fun organicPost(media: JsonObject): JsonObject? {
    val ad = media["ad_id"]
    if (ad is JsonPrimitive) {
        val value = ad.content
        if (value.isNotBlank() && value != "0" && !value.equals("null", ignoreCase = true)) return null
    }
    if (media.getStringOrNull("product_type") == "ad") return null
    if (codeOf(media) == null) return null
    return media
}

internal fun timelineMedias(data: JsonObject): List<JsonObject> {
    val found = linkedMapOf<String, JsonObject>()
    fun keep(media: JsonObject?) {
        val post = media?.let { runCatching { organicPost(it) }.getOrNull() } ?: return
        val code = codeOf(post) ?: return
        found.putIfAbsent(code, post)
    }
    fun walk(element: JsonElement?) {
        val obj = runCatching { element?.asObject() }.getOrNull() ?: return
        keep(obj)
        keep(obj.getObjectOrNull("media_or_ad"))
        keep(obj.getObjectOrNull("media"))
        keep(obj.getObjectOrNull("explore_story")?.getObjectOrNull("media"))
    }
    runCatching { data.getArrayOrNull("feed_items").orEmpty().forEach(::walk) }
    runCatching { data.getArrayOrNull("items").orEmpty().forEach(::walk) }
    return found.values.toList()
}

internal fun friendshipFollowing(media: JsonObject): Boolean? {
    val direct = media.getObjectOrNull("friendship_status")?.getBooleanOrNull("following")
    if (direct != null) return direct
    val user = media.getObjectOrNull("user") ?: media.getObjectOrNull("owner")
    return user?.getObjectOrNull("friendship_status")?.getBooleanOrNull("following")
}

internal fun isSuggestionMarker(media: JsonObject): Boolean {
    if (media.getObjectOrNull("suggested_users") != null) return true
    if (media.getObjectOrNull("explore_story") != null) return true
    if (media.getObjectOrNull("explore") != null) return true
    val reason = listOf("reason", "inventory_source", "feed_item_type", "title")
        .mapNotNull { media.getStringOrNull(it) }
        .joinToString(" ")
        .lowercase()
    return "explore" in reason || "suggest" in reason
}

/**
 * Latest keeps a post when the author is followed, or when the item is not an ad
 * and not a suggestion. Ads never pass [organicPost].
 */
internal fun isLatestPost(media: JsonObject): Boolean {
    if (organicPost(media) == null) return false
    if (friendshipFollowing(media) == true) return true
    return !isSuggestionMarker(media)
}

internal fun latestTimelinePosts(data: JsonObject): List<JsonObject> {
    val found = linkedMapOf<String, JsonObject>()
    fun keep(wrapper: JsonObject, media: JsonObject?) {
        val post = media?.let { runCatching { organicPost(it) }.getOrNull() } ?: return
        val code = codeOf(post) ?: return
        if (friendshipFollowing(post) == true) {
            found.putIfAbsent(code, post)
            return
        }
        if (isSuggestionMarker(wrapper) || isSuggestionMarker(post)) return
        found.putIfAbsent(code, post)
    }
    fun walk(element: JsonElement?) {
        val obj = runCatching { element?.asObject() }.getOrNull() ?: return
        if (obj.getObjectOrNull("suggested_users") != null && obj.getObjectOrNull("media_or_ad") == null) return
        keep(obj, obj.getObjectOrNull("media_or_ad"))
        keep(obj, obj.getObjectOrNull("media"))
        val exploreMedia = obj.getObjectOrNull("explore_story")?.getObjectOrNull("media")
        if (exploreMedia != null && friendshipFollowing(exploreMedia) == true) keep(obj, exploreMedia)
        if (obj.getObjectOrNull("media_or_ad") == null && obj.getObjectOrNull("media") == null) keep(obj, obj)
    }
    runCatching { data.getArrayOrNull("feed_items").orEmpty().forEach(::walk) }
    runCatching { data.getArrayOrNull("items").orEmpty().forEach(::walk) }
    return found.values.toList()
}

internal fun userIdFromSession(session: String?): String? {
    if (session.isNullOrBlank()) return null
    val decoded = runCatching { java.net.URLDecoder.decode(session, Charsets.UTF_8.name()) }.getOrDefault(session)
    val head = decoded.substringBefore(':').trim()
    return head.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
}

internal fun userIdFromCookies(cookies: Map<String, String>): String? {
    cookies["ds_user_id"]?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.let { return it }
    return userIdFromSession(cookies["sessionid"])
}

internal fun currentUserId(data: JsonObject): String? {
    val user = data.getObjectOrNull("user") ?: data
    return user.getStringOrNull("pk_id")
        ?: user.longFlexible("pk")?.toString()
        ?: user.getStringOrNull("pk")
        ?: user.getStringOrNull("id")
        ?: user.longFlexible("id")?.toString()
}

internal fun clipDetail(text: String, limit: Int = 120): String {
    val clean = text.replace(Regex("\\s+"), " ").trim()
        .replace(Regex("sessionid=[^\\s;&]+", RegexOption.IGNORE_CASE), "sessionid=***")
    return if (clean.length <= limit) clean else clean.take(limit)
}

internal fun diagnosticLine(step: String, status: Int?, detail: String): String {
    val code = status?.toString() ?: "-"
    return "$step: HTTP $code ${clipDetail(detail)}"
}

internal fun latestFailure(lines: List<String>): String {
    val body = lines.ifEmpty { listOf(diagnosticLine("timeline", null, "nessun dettaglio")) }
    return "Più Recenti non ha caricato post.\n" + body.joinToString("\n")
}

internal fun userFeedMedias(data: JsonObject): List<JsonObject> {
    val items = data.getArrayOrNull("items") ?: return emptyList()
    return items.mapNotNull { element ->
        runCatching { element.asObject()?.let { organicPost(it) } }.getOrNull()
    }
}

internal fun followingAccounts(data: JsonObject): List<FollowedAccount> {
    val users = data.getArrayOrNull("users")
        ?: data.getObjectOrNull("data")?.getArrayOrNull("users")
        ?: return emptyList()
    val found = linkedMapOf<String, FollowedAccount>()
    users.forEach { element ->
        val user = element.asObject() ?: return@forEach
        val id = user.getStringOrNull("pk_id")
            ?: user.longFlexible("pk")?.toString()
            ?: user.getStringOrNull("pk")
            ?: user.getStringOrNull("id")
            ?: user.longFlexible("id")?.toString()
            ?: return@forEach
        val username = user.getStringOrNull("username") ?: return@forEach
        if (id.isBlank() || username.isBlank()) return@forEach
        found.putIfAbsent(id, FollowedAccount(id, username))
    }
    return found.values.toList()
}

internal fun nextCursorId(data: JsonObject): String? {
    val paging = data.getObjectOrNull("paging_info")
    val pageInfo = data.getObjectOrNull("page_info") ?: paging?.getObjectOrNull("page_info")
    val more = data.getBooleanOrNull("more_available")
        ?: paging?.getBooleanOrNull("more_available")
        ?: pageInfo?.getBooleanOrNull("has_next_page")
    if (more == false) return null
    val maxId = data.getStringOrNull("next_max_id")
        ?: paging?.getStringOrNull("next_max_id")
        ?: data.longFlexible("next_max_id")?.toString()
        ?: paging?.longFlexible("next_max_id")?.toString()
        ?: pageInfo?.getStringOrNull("end_cursor")
        ?: data.getStringOrNull("end_cursor")
    return maxId?.takeIf { it.isNotBlank() }
}

internal fun isFollowedPost(media: JsonObject, followed: List<FollowedAccount>): Boolean {
    val status = media.getObjectOrNull("friendship_status")?.getBooleanOrNull("following")
    if (status == true) return true
    if (followed.isEmpty()) return false
    val id = authorId(media)
    val name = authorOf(media)
    if (id != null && followed.any { it.id == id }) return true
    if (name != null && followed.any { it.username.equals(name, ignoreCase = true) }) return true
    return false
}

internal suspend fun collectTimeline(
    target: Int,
    maxCalls: Int,
    initialCursor: String?,
    fetch: suspend (cursor: String?) -> JsonObject,
): TimelinePage {
    var cursor = initialCursor
    val posts = linkedMapOf<String, JsonObject>()
    var next: String? = null
    var guard = 0
    while (posts.size < target && guard < maxCalls) {
        guard++
        val data = fetch(cursor)
        timelineMedias(data).forEach { media ->
            val code = codeOf(media) ?: return@forEach
            posts.putIfAbsent(code, media)
        }
        next = nextCursorId(data)
        if (next == null) break
        cursor = next
    }
    return TimelinePage(posts.values.toList(), next)
}

internal fun accountBatch(accounts: List<FollowedAccount>, startIndex: Int, batchSize: Int = FOLLOW_BATCH): AccountBatch {
    val start = startIndex.coerceAtLeast(0)
    if (start >= accounts.size || batchSize <= 0) return AccountBatch(emptyList(), start, false)
    val end = (start + batchSize).coerceAtMost(accounts.size)
    return AccountBatch(accounts.subList(start, end), end, end < accounts.size)
}

internal fun prefixEnd(start: Int, completed: Set<Int>): Int {
    var next = start
    while (next in completed) next++
    return next
}

internal fun mergeFollowedPosts(posts: Collection<JsonObject>): List<JsonObject> {
    val unique = linkedMapOf<String, JsonObject>()
    posts.forEach { media ->
        val post = runCatching { organicPost(media) }.getOrNull() ?: return@forEach
        val code = codeOf(post) ?: return@forEach
        unique.putIfAbsent(code, post)
    }
    return unique.values.sortedByDescending { takenAtOf(it) ?: Long.MIN_VALUE }
}

internal fun followedPage(sink: FollowedSink, start: Int, accountCount: Int): FollowedPage {
    val next = prefixEnd(start, sink.completed)
    val posts = mergeFollowedPosts(sink.tagged.filter { it.first < next }.map { it.second })
    return FollowedPage(posts, next, next < accountCount)
}

/**
 * How many account feeds fit in [budgetMs] when each call waits out the previous one.
 * The following-list request is included once, unless it is already cached.
 */
internal fun plannedFollowFetches(
    accountCount: Int,
    start: Int,
    batchSize: Int,
    budgetMs: Long,
    spacingMs: Long,
    perCallMs: Long,
    listAlreadyCached: Boolean,
    listCallMs: Long = perCallMs,
): Int {
    if (accountCount <= start || batchSize <= 0 || perCallMs <= 0) return 0
    var elapsed = if (listAlreadyCached) 0L else listCallMs
    if (!listAlreadyCached) elapsed += spacingMs
    var fetched = 0
    val room = (accountCount - start).coerceAtMost(batchSize)
    while (fetched < room) {
        if (elapsed + perCallMs > budgetMs) break
        elapsed += perCallMs
        fetched++
        if (fetched < room) elapsed += spacingMs
    }
    return fetched
}

internal suspend fun collectFollowed(
    accounts: List<FollowedAccount>,
    start: Int,
    batchSize: Int,
    sink: FollowedSink,
    fetch: suspend (FollowedAccount) -> List<JsonObject>,
): FollowedPage {
    val batch = accountBatch(accounts, start, batchSize)
    if (batch.accounts.isEmpty()) return FollowedPage(emptyList(), start, false)
    coroutineScope {
        batch.accounts.mapIndexed { offset, account ->
            async {
                val index = start + offset
                try {
                    sink.add(index, fetch(account))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: IOException) {
                    throw error
                } catch (_: Exception) {
                    sink.fail(index)
                }
            }
        }.awaitAll()
    }
    return followedPage(sink, start, accounts.size)
}
