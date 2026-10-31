package eu.kanade.tachiyomi.extension.it.hentaizone

import eu.kanade.tachiyomi.source.model.Filter

enum class SortMode(val label: String, val apiOrderBy: String?) {
    POPULAR("Popolari (più commentati)", null),
    DATE("Data di uscita", "date"),
    MODIFIED("Ultimo aggiornamento", "modified"),
    TITLE("Titolo", "title"),
    RELEVANCE("Pertinenza (con testo)", "relevance"),
    PAGES("Più pagine", null),
}

class SortFilter(selection: Filter.Sort.Selection = Filter.Sort.Selection(0, false)) :
    Filter.Sort("Ordina per", SortMode.entries.map { it.label }.toTypedArray(), selection) {
    val mode: SortMode get() = SortMode.entries[state?.index ?: 0]
    val ascending: Boolean get() = state?.ascending ?: false
}

class Section(val label: String, val id: Int?, val slug: String?)

class SectionFilter : Filter.Select<String>("Sezione", SECTIONS.map { it.label }.toTypedArray()) {
    val section: Section get() = SECTIONS[state]
}

enum class Access(val label: String) {
    ALL("Tutti"),
    FREE("Solo gratuiti"),
    PREMIUM("Solo Premium"),
}

class AccessFilter : Filter.Select<String>("Accesso", Access.entries.map { it.label }.toTypedArray()) {
    val access: Access get() = Access.entries[state]
}

class MinPages(val label: String, val pages: Int)

class MinPagesFilter : Filter.Select<String>("Pagine minime", MIN_PAGES.map { it.label }.toTypedArray()) {
    val pages: Int get() = MIN_PAGES[state].pages
}

val MIN_PAGES = listOf(
    MinPages("Qualsiasi", 0),
    MinPages("20+", 20),
    MinPages("40+", 40),
    MinPages("60+", 60),
    MinPages("100+", 100),
    MinPages("200+", 200),
)

class TermOption(name: String, val id: Int, val slug: String) : Filter.TriState(name)

class CategoryGroup(options: List<TermOption>) : Filter.Group<TermOption>("Categorie", options)

class TagGroup(options: List<TermOption>) : Filter.Group<TermOption>("Tag", options)

val SECTIONS = listOf(
    Section("Manga hentai", 342, "hentai-manga"),
    Section("Doujinshi", 11, "doujinshi-hentai"),
    Section("Fumetti porno", 363, "fumetti-porno"),
    Section("Tutti i fumetti", null, null),
)

const val PREMIUM_CATEGORY_ID = 920
const val PREMIUM_CATEGORY_SLUG = "premium"

// Video and game posts have no readable gallery.
val NON_READING_CATEGORIES = mapOf(
    617 to "hentai-sub-ita",
    926 to "giochi-hentai",
    1884 to "default_post",
)

// Only labels that explicitly mean minors: preteen/child keywords, high or
// primary school tags, and series whose characters are canonically minors.
// Always excluded, both from the requests and again on the returned cards.
val BLOCKED_CATEGORIES = mapOf(
    752 to "my-hero-academia",
    367 to "sailor-moon",
)

val BLOCKED_TAGS = mapOf(
    504 to "school-girl",
    2000 to "uniforme-scolastica",
    2054 to "liceale",
    418 to "fantagenitori",
    782 to "teen-titans",
)

val BLOCKED_TEXT = Regex(
    """(?i)\b(loli\w*|shota\w*|preteen\w*|pre-teen\w*|underage|minorenn\w*|bambin[aeio]|liceal[ei]|elementari)\b""",
)
