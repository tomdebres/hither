package com.tomdebres.hither.ui.browse

import androidx.annotation.StringRes
import com.tomdebres.hither.R
import com.tomdebres.hither.core.rules.SortOption

/**
 * One of Seerr's sorts in the Apple TV app's words (A8, DECISIONS 348; tvOS's `Words.sortName`, polish part 7's S35):
 * "Popularity Descending" and "Title (A-Z) Ascending" read as a database's. By TMDB's value, so `core` keeps Seerr's
 * labels and both apps say the same; [usual] is each pair's usual way, listed first.
 */
enum class SortName(@StringRes val words: Int, val usual: Boolean) {
    MOST_POPULAR(R.string.sort_most_popular, true),
    LEAST_POPULAR(R.string.sort_least_popular, false),
    NEWEST(R.string.sort_newest, true),
    OLDEST(R.string.sort_oldest, false),
    HIGHEST_RATED(R.string.sort_highest_rated, true),
    LOWEST_RATED(R.string.sort_lowest_rated, false),
    TITLE_A_TO_Z(R.string.sort_title_a_z, true),
    TITLE_Z_TO_A(R.string.sort_title_z_a, false),
    ;

    companion object {
        /** The words for a sort's value ("popularity.desc"); null for one `core` may add later, which keeps core's label. */
        fun of(value: String): SortName? {
            val dot = value.lastIndexOf('.').takeIf { it > 0 } ?: return null
            val down = value.substring(dot + 1) == "desc"
            return when (value.substring(0, dot)) {
                "popularity" -> if (down) MOST_POPULAR else LEAST_POPULAR
                "release_date", "first_air_date" -> if (down) NEWEST else OLDEST
                "vote_average" -> if (down) HIGHEST_RATED else LOWEST_RATED
                "original_title", "original_name" -> if (down) TITLE_Z_TO_A else TITLE_A_TO_Z
                else -> null
            }
        }
    }
}

/** A sort as the page offers it: core's [option], and its words where Hither has them ([name]). */
data class SortChoice(val option: SortOption, val name: SortName?)

/**
 * The sort list (tvOS's `Words.sorts`): core's sorts, the pairs in Seerr's order, each pair's usual way first ("Most
 * Popular" before "Least Popular", "Title A to Z" before "Title Z to A"), so the page's own sort leads.
 */
fun sortChoices(options: List<SortOption>): List<SortChoice> {
    fun field(value: String) = value.substringBeforeLast('.')
    val fields = options.map { field(it.value) }.distinct()
    return options.mapIndexed { index, option -> IndexedValue(index, SortChoice(option, SortName.of(option.value))) }
        .sortedWith(
            compareBy<IndexedValue<SortChoice>> { fields.indexOf(field(it.value.option.value)) }
                .thenBy { if (it.value.name?.usual == true) 0 else 1 }
                .thenBy { it.index },
        )
        .map { it.value }
}
