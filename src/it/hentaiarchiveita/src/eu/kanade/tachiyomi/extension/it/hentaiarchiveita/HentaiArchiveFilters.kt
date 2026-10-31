package eu.kanade.tachiyomi.extension.it.hentaiarchiveita

import eu.kanade.tachiyomi.source.model.Filter

enum class SortMode(val label: String, val orderBy: String) {
    POPULAR("Popolari (più commentati)", "comment_count"),
    DATE("Data di uscita", "date"),
    TITLE("Titolo", "title"),
    RANDOM("Casuale", "date"),
}

class SortFilter(selection: Filter.Sort.Selection = Filter.Sort.Selection(0, false)) :
    Filter.Sort("Ordina per", SortMode.entries.map { it.label }.toTypedArray(), selection) {
    val mode: SortMode get() = SortMode.entries[state?.index ?: 0]
    val ascending: Boolean get() = state?.ascending ?: false
}

class Category(val label: String, val path: String) {
    val slug: String get() = path.substringAfterLast('/')
}

class SectionFilter : Filter.Select<String>("Sezione", SECTIONS.map { it.label }.toTypedArray()) {
    val section: Category get() = SECTIONS[state]
}

class CategoryFilter :
    Filter.Select<String>("Categoria", (listOf("Qualsiasi") + CATEGORIES.map { it.label }).toTypedArray()) {
    val category: Category? get() = CATEGORIES.getOrNull(state - 1)
}

val SECTIONS = listOf(
    Category("Tutti i manga", "hentai-recenti"),
    Category("Doujinshi", "hentai-recenti/doujinshi"),
    Category("Fumetti porno", "hentai-recenti/fumetti-porno"),
    Category("Fumetti porno 3D", "hentai-recenti/fumetti-porno-3d"),
    Category("Volumi", "hentai-recenti/volumi-hentai"),
)

// Only labels that explicitly mean children or preteens: Sailor Moon, whose
// main characters are middle-schoolers. Videos have no pages to read.
const val BLOCKED_CATEGORY_ID = 286
val BLOCKED_CLASSES = setOf("category-hentai-ita-sailor-moon", "category-hentai-video")

val BLOCKED_TEXT = Regex(
    """(?i)\b(loli\w*|shota\w*|preteen\w*|pre-teen\w*|underage|minorenn\w*|bambin[aeio]|elementari)\b""",
)

val CATEGORIES = listOf(
    Category("Ahegao", "hentai-recenti/ahegao-hentai-recenti"),
    Category("Anale", "hentai-recenti/hentai-sesso-anale"),
    Category("BDSM", "hentai-recenti/bdsm"),
    Category("Bikini", "hentai-recenti/hentai-ita-bikini"),
    Category("Bondage", "hentai-recenti/hentai-bondage"),
    Category("Bukkake", "hentai-recenti/hentai-bukkake"),
    Category("Capezzoli invertiti", "hentai-recenti/hentai-capezzoli-invertiti"),
    Category("Controllo mentale", "hentai-recenti/hentai-ita-controllo-mentale"),
    Category("Costume da bagno", "hentai-recenti/hentai-costume-da-bagno"),
    Category("Culi Grossi", "hentai-recenti/hentai-culi-grossi"),
    Category("Demon Slayer", "hentai-recenti/doujinshi/demon-slayer"),
    Category("Domestiche", "hentai-recenti/hentai-ita-domestiche"),
    Category("Donne incinta", "hentai-recenti/donne-incinta"),
    Category("Dragon Ball", "hentai-recenti/doujinshi/hentai-ita-dragon-ball"),
    Category("Esibizioniste", "hentai-recenti/hentai-esibizioniste"),
    Category("Famiglia", "hentai-recenti/hentai-famiglia"),
    Category("Fantasy", "hentai-recenti/hentai-fantasy"),
    Category("Fate/Grand Order", "hentai-recenti/doujinshi/fate-grand-order"),
    Category("Femdom", "hentai-recenti/hentai-femdom"),
    Category("Final Fantasy", "hentai-recenti/doujinshi/hentai-ita-final-fantasy"),
    Category("Follia", "hentai-recenti/hentai-follia"),
    Category("Formose", "hentai-recenti/hentai-formose"),
    Category("Frieren", "hentai-recenti/doujinshi/frieren"),
    Category("Full Color", "hentai-recenti/hentai-full-color"),
    Category("Fullmetal Alchemist", "hentai-recenti/doujinshi/hentai-fullmetal-alchemist"),
    Category("Furry", "hentai-recenti/hentai-ita-furry"),
    Category("Futanari", "hentai-recenti/hentai-futanari"),
    Category("Genshin Impact", "hentai-recenti/doujinshi/hentai-genshin-impact"),
    Category("Goblin Slayer", "hentai-recenti/doujinshi/hentai-ita-goblin-slayer"),
    Category("Gola Profonda", "hentai-recenti/hentai-gola-profonda"),
    Category("Gyaru", "hentai-recenti/hentai-gyaru"),
    Category("Harem", "hentai-recenti/hentai-ita-harem"),
    Category("Highschool of the Dead", "hentai-recenti/doujinshi/hentai-ita-highschool-of-the-dead"),
    Category("Hunter x Hunter", "hentai-recenti/doujinshi/hentai-ita-hunter-x-hunter"),
    Category("Infermiere", "hentai-recenti/hentai-ita-infermiere"),
    Category("Insegnanti", "hentai-recenti/hentai-insegnanti"),
    Category("Jujutsu Kaisen", "hentai-recenti/doujinshi/hentai-jujutsu-kaisen"),
    Category("L'attacco dei giganti", "hentai-recenti/doujinshi/hentai-ita-lattacco-dei-giganti"),
    Category("Lesbiche", "hentai-recenti/hentai-lesbiche"),
    Category("MILF", "hentai-recenti/hentai-milf"),
    Category("Monster Girls", "hentai-recenti/hentai-monster-girl"),
    Category("Mostri", "hentai-recenti/hentai-mostri"),
    Category("Muscoli", "hentai-recenti/hentai-muscoli"),
    Category("My Dress-Up Darling", "hentai-recenti/doujinshi/hentai-my-dress-up-darling"),
    Category("My Hero Academia", "hentai-recenti/doujinshi/hentai-ita-my-hero-academia"),
    Category("Naruto", "hentai-recenti/doujinshi/hentai-ita-naruto"),
    Category("Neon Genesis Evangelion", "hentai-recenti/doujinshi/hentai-ita-neon-genesis-evangelion"),
    Category("One Piece", "hentai-recenti/doujinshi/hentai-ita-one-piece"),
    Category("Pelle Scura", "hentai-recenti/hentai-pelle-scura"),
    Category("Pelose", "hentai-recenti/hentai-pelose"),
    Category("Pissing", "hentai-recenti/hentai-pissing"),
    Category("Pokémon", "hentai-recenti/doujinshi/hentai-ita-pokemon"),
    Category("Prostitute", "hentai-recenti/hentai-prostitute"),
    Category("Queen's Blade", "hentai-recenti/doujinshi/hentai-ita-queens-blade"),
    Category("Ricatti", "hentai-recenti/hentai-ricatti"),
    Category("Seno piccolo", "hentai-recenti/hentai-seno-piccolo"),
    Category("Senza Censura", "hentai-recenti/hentai-senza-censura"),
    Category("Sesso di gruppo", "hentai-recenti/hentai-group"),
    Category("Sesso Estremo", "hentai-recenti/hentai-ita-sesso-estremo"),
    Category("Sex Toys", "hentai-recenti/hentai-sex-toys"),
    Category("Squirting", "hentai-recenti/hentai-squirting"),
    Category("Stomaco deformato", "hentai-recenti/hentai-stomaco-deformato"),
    Category("Studentesse", "hentai-recenti/hentai-studentesse"),
    Category("Super Mario", "hentai-recenti/doujinshi/hentai-ita-super-mario"),
    Category("Superdotati", "hentai-recenti/hentai-superdotati"),
    Category("Tette Grosse", "hentai-recenti/hentai-tette-grosse"),
    Category("Tomboy", "hentai-recenti/hentai-tomboy"),
    Category("Tradimento", "hentai-recenti/hentai-tradimento"),
    Category("Uomini Maturi", "hentai-recenti/hentai-uomini-maturi"),
    Category("Vergini", "hentai-recenti/hentai-vergini"),
    Category("X-Ray", "hentai-recenti/hentai-x-ray"),
    Category("Yandere", "hentai-recenti/hentai-yandere"),
    Category("Yaoi", "hentai-recenti/hentai-yaoi"),
)
