package eu.kanade.tachiyomi.extension.it.hentaizone

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.parser.Parser

@Serializable
class PostDto(
    val slug: String,
    private val title: RenderedDto,
    @SerialName("date_gmt") val dateGmt: String? = null,
    @SerialName("class_list") val classList: List<String> = emptyList(),
    @SerialName("_embedded") private val embedded: EmbeddedDto? = null,
) {
    val plainTitle: String get() = Parser.unescapeEntities(title.rendered, false)

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
        url = "/$slug/"
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
class FilterDataDto(
    val categories: List<TermDto>,
    val tags: List<TermDto>,
)

@Serializable
class GalleryImageDto(val src: String)

// Page-count index built daily by tools/build_page_index.py.
@Serializable
class PageIndexDto(val entries: List<PageIndexEntryDto>)

@Serializable
class PageIndexEntryDto(
    @SerialName("u") val url: String,
    @SerialName("t") val title: String,
    @SerialName("i") private val thumbnail: String? = null,
    @SerialName("p") val pages: Int,
    @SerialName("d") val date: String,
    @SerialName("c") val categories: List<Int> = emptyList(),
    @SerialName("g") val tags: List<Int> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        url = this@PageIndexEntryDto.url
        title = this@PageIndexEntryDto.title
        thumbnail_url = thumbnail
    }
}
