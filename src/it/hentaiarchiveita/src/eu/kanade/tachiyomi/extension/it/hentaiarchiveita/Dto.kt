package eu.kanade.tachiyomi.extension.it.hentaiarchiveita

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.parser.Parser

@Serializable
class PostDto(
    val slug: String,
    private val title: RenderedDto,
    @SerialName("date_gmt") val dateGmt: String? = null,
    @SerialName("class_list") private val classListJson: JsonElement? = null,
    @SerialName("_embedded") private val embedded: EmbeddedDto? = null,
) {
    val plainTitle: String get() = Parser.unescapeEntities(title.rendered, false)

    val path: String get() = "/$slug/"

    // WordPress returns class_list as an array, or as an object keyed by index.
    val classList: List<String>
        get() = when (val classes = classListJson) {
            is JsonArray -> classes.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonObject -> classes.values.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }

    val terms: List<TermDto> get() = embedded?.terms.orEmpty().flatten()

    // media_details can be an empty array instead of an object, so it is read loosely.
    val thumbnail: String?
        get() {
            val media = embedded?.media?.firstOrNull() ?: return null
            val sizes = (media.details as? JsonObject)?.get("sizes") as? JsonObject
            val preferred = listOf("medium_large", "large", "medium").firstNotNullOfOrNull { size ->
                ((sizes?.get(size) as? JsonObject)?.get("source_url") as? JsonPrimitive)?.content
            }
            return preferred ?: media.sourceUrl
        }

    fun toSManga() = SManga.create().apply {
        url = path
        title = plainTitle
        thumbnail_url = thumbnail
    }
}

@Serializable
class RenderedDto(val rendered: String)

@Serializable
class EmbeddedDto(
    @SerialName("wp:featuredmedia") val media: List<MediaDto> = emptyList(),
    @SerialName("wp:term") val terms: List<List<TermDto>> = emptyList(),
)

@Serializable
class MediaDto(
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("media_details") val details: JsonElement? = null,
)

@Serializable
class TermDto(
    val id: Int,
    val slug: String,
    private val name: String,
    val count: Int = 0,
    val taxonomy: String? = null,
) {
    val plainName: String get() = Parser.unescapeEntities(name, false)
}

@Serializable
class FilterDataDto(val tags: List<TermDto>)

@Serializable
class GalleryDto(val images: List<GalleryUrlDto> = emptyList())

@Serializable
class GalleryUrlDto(val url: String? = null, val src: String? = null)
