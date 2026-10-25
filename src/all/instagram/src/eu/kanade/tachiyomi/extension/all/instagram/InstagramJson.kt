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
    val carousel = media.getArrayOrNull("carousel_media").orEmpty()
    if (carousel.isNotEmpty()) {
        val urls = carousel.mapNotNull { element -> element.asObject()?.let { bestImage(it) } }
        if (urls.isNotEmpty()) return urls
    }
    val sidecar = media.getObjectOrNull("edge_sidecar_to_children")?.getArrayOrNull("edges").orEmpty()
    if (sidecar.isNotEmpty()) {
        val urls = sidecar.mapNotNull { edge ->
            edge.asObject()?.getObjectOrNull("node")?.let { bestImage(it) }
        }
        if (urls.isNotEmpty()) return urls
    }
    return listOfNotNull(bestImage(media))
}

internal fun bestImage(media: JsonObject): String? {
    val ranked = media.getObjectOrNull("image_versions2")
        ?.getArrayOrNull("candidates")
        .orEmpty()
        .mapNotNull { element ->
            val node = element.asObject() ?: return@mapNotNull null
            val url = node.getStringOrNull("url") ?: return@mapNotNull null
            val width = node.getIntOrNull("width") ?: 0
            val height = node.getIntOrNull("height") ?: 0
            Candidate(url, width, if (height > 0) height else 1)
        }
        .sortedByDescending { it.width.toLong() * it.height }
    val jpeg = ranked.filter { !it.url.contains(".heic", ignoreCase = true) }
    val best = (if (jpeg.isNotEmpty()) jpeg else ranked).firstOrNull()
    val display = media.getStringOrNull("display_url")
    if (best != null && best.width >= 480) return best.url
    if (!display.isNullOrBlank()) return display
    val resource = media.getArrayOrNull("display_resources")
        .orEmpty()
        .mapNotNull { element ->
            val node = element.asObject() ?: return@mapNotNull null
            val src = node.getStringOrNull("src") ?: return@mapNotNull null
            Candidate(src, node.getIntOrNull("config_width") ?: 0, node.getIntOrNull("config_height") ?: 1)
        }
        .maxByOrNull { it.width.toLong() * it.height }
    if (resource != null && resource.width >= (best?.width ?: 0)) return resource.url
    if (best != null) return best.url
    return media.getStringOrNull("thumbnail_url") ?: media.getStringOrNull("thumbnail_src")
}

private data class Candidate(val url: String, val width: Int, val height: Int)
