package eu.kanade.tachiyomi.extension.it.hentaiarchiveita

import eu.kanade.tachiyomi.source.model.Filter

enum class SortMode(val label: String, val apiOrderBy: String) {
    RANDOM("Casuale", "date"),
    DATE("Data di uscita", "date"),
    MODIFIED("Ultimo aggiornamento", "modified"),
    TITLE("Titolo", "title"),
    RELEVANCE("Pertinenza (con testo)", "relevance"),
}

class SortFilter(selection: Filter.Sort.Selection = Filter.Sort.Selection(0, false)) :
    Filter.Sort("Ordina per", SortMode.entries.map { it.label }.toTypedArray(), selection) {
    val mode: SortMode get() = SortMode.entries[state?.index ?: 0]
    val ascending: Boolean get() = state?.ascending ?: false
}

class Section(val label: String, val id: Int?)

class SectionFilter : Filter.Select<String>("Sezione", SECTIONS.map { it.label }.toTypedArray()) {
    val section: Section get() = SECTIONS[state]
}

class TagOption(name: String, val id: Int) : Filter.TriState(name)

class TagGroup(options: List<TagOption>) : Filter.Group<TagOption>("Tag", options)

val SECTIONS = listOf(
    Section("Tutto (manga e fumetti)", null),
    Section("Hentai manga", 1),
    Section("Fumetti porno", 6),
)

// Videos and games have no pages to read.
val NON_READING_CATEGORIES = setOf(3, 109)

// Only labels that explicitly mean children or preteens.
val BLOCKED_TEXT = Regex(
    """(?i)\b(loli\w*|shota\w*|preteen\w*|pre-teen\w*|underage|minorenn\w*|bambin[aeio]|elementari)\b""",
)
