package eu.kanade.tachiyomi.extension.all.instagram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Keiyoushi's getArrayOrNull calls [JsonElement.jsonArray], which throws when the
 * value is JSON null. Logged-in profile payloads use null for edges and for
 * carousel_media, so every cast here treats null as "absent".
 */
internal fun JsonObject.getArrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.getObjectOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.getStringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.getIntOrNull(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.getBooleanOrNull(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

internal fun JsonObject.longFlexible(key: String): Long? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.content.toLongOrNull()
}

internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

internal val JsonElement.stringOrNull: String?
    get() = (this as? JsonPrimitive)?.contentOrNull

internal fun profilePostNodes(user: JsonObject): List<JsonObject> {
    val timeline = user.getObjectOrNull("edge_owner_to_timeline_media") ?: return emptyList()
    return timeline.getArrayOrNull("edges").orEmpty().mapNotNull { edge ->
        runCatching { edge.asObject()?.getObjectOrNull("node") }.getOrNull()
    }
}

internal fun isVideo(media: JsonObject): Boolean {
    if (media.getIntOrNull("media_type") == 2) return true
    if (media.getBooleanOrNull("is_video") == true) return true
    val product = media.getStringOrNull("product_type")
    return product == "clips" || product == "igtv"
}

internal fun carouselCount(media: JsonObject): Int {
    val carousel = media.getArrayOrNull("carousel_media")?.size ?: 0
    if (carousel > 0) return carousel
    return media.getObjectOrNull("edge_sidecar_to_children")?.getArrayOrNull("edges")?.size ?: 0
}

internal fun countsAsPhoto(media: JsonObject): Boolean {
    if (carouselCount(media) > 1) return true
    return !isVideo(media)
}

internal fun captionOf(media: JsonObject): String {
    val fromEdge = media.getObjectOrNull("edge_media_to_caption")
        ?.getArrayOrNull("edges")
        ?.firstOrNull()
        ?.asObject()
        ?.getObjectOrNull("node")
        ?.getStringOrNull("text")
    if (!fromEdge.isNullOrBlank()) return fromEdge
    val caption = media["caption"]?.asObject()?.getStringOrNull("text")
        ?: media["caption"]?.stringOrNull
    return caption.orEmpty()
}

internal fun pageUrls(media: JsonObject): List<String> {
    val raw = carouselImageUrls(media).ifEmpty { listOfNotNull(bestImage(media)) }
    val seen = linkedSetOf<String>()
    raw.forEach { url ->
        val clean = usableImageUrl(url) ?: return@forEach
        seen += clean
    }
    return seen.toList()
}

private fun carouselImageUrls(media: JsonObject): List<String> {
    val carousel = media.getArrayOrNull("carousel_media").orEmpty()
    if (carousel.isNotEmpty()) {
        return carousel.mapNotNull { element -> element.asObject()?.let { bestImage(it) } }
    }
    val sidecar = media.getObjectOrNull("edge_sidecar_to_children")?.getArrayOrNull("edges").orEmpty()
    if (sidecar.isEmpty()) return emptyList()
    return sidecar.mapNotNull { edge ->
        edge.asObject()?.getObjectOrNull("node")?.let { bestImage(it) }
    }
}

internal fun bestImage(media: JsonObject): String? {
    val ranked = imageCandidates(media.getObjectOrNull("image_versions2")?.getArrayOrNull("candidates"), "url", "width", "height")
    val jpeg = ranked.filter { !it.url.contains(".heic", ignoreCase = true) }
    val pool = if (jpeg.isNotEmpty()) jpeg else ranked
    val cdnLarge = pool.filter { it.width >= 480 && isInstagramCdnUrl(it.url) }.maxByOrNull { it.pixels }
    if (cdnLarge != null) return cdnLarge.url
    val display = usableImageUrl(media.getStringOrNull("display_url"))
    if (display != null && isInstagramCdnUrl(display)) return display
    val anyLarge = pool.filter { it.width >= 480 }.maxByOrNull { it.pixels }
    if (anyLarge != null) return anyLarge.url
    if (display != null) return display
    val resource = imageCandidates(media.getArrayOrNull("display_resources"), "src", "config_width", "config_height")
    val resourceCdn = resource.filter { isInstagramCdnUrl(it.url) }.maxByOrNull { it.pixels }
    if (resourceCdn != null) return resourceCdn.url
    val resourceBest = resource.maxByOrNull { it.pixels }
    if (resourceBest != null) return resourceBest.url
    val cdnAny = pool.filter { isInstagramCdnUrl(it.url) }.maxByOrNull { it.pixels }
    if (cdnAny != null) return cdnAny.url
    pool.maxByOrNull { it.pixels }?.url?.let { return it }
    return usableImageUrl(media.getStringOrNull("thumbnail_url"))
        ?: usableImageUrl(media.getStringOrNull("thumbnail_src"))
}

internal fun usableImageUrl(raw: String?): String? {
    val url = raw?.trim().orEmpty()
    if (!url.startsWith("https://")) return null
    val lower = url.lowercase()
    if ("placeholder" in lower || "/rsrc.php" in lower || lower.startsWith("data:")) return null
    return url
}

internal fun isInstagramCdnUrl(url: String): Boolean {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore(':').lowercase()
    return "cdninstagram" in host || "scontent" in host || "fbcdn.net" in host
}

private fun imageCandidates(nodes: JsonArray?, urlKey: String, widthKey: String, heightKey: String): List<Candidate> {
    if (nodes == null) return emptyList()
    return nodes.mapNotNull { element ->
        val node = element.asObject() ?: return@mapNotNull null
        val url = usableImageUrl(node.getStringOrNull(urlKey)) ?: return@mapNotNull null
        val width = node.getIntOrNull(widthKey) ?: 0
        val height = node.getIntOrNull(heightKey) ?: 0
        Candidate(url, width, if (height > 0) height else 1)
    }
}

private data class Candidate(val url: String, val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}
