package com.tomdebres.hither.ui.browse

import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.SortOption
import com.tomdebres.hither.core.rules.sortOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The sorts in tvOS's words and order (A8; tvOS's `WordsTests` for `Words.sorts`). */
class SortsTest {
    private val tvosOrder = listOf(
        SortName.MOST_POPULAR,
        SortName.LEAST_POPULAR,
        SortName.NEWEST,
        SortName.OLDEST,
        SortName.HIGHEST_RATED,
        SortName.LOWEST_RATED,
        SortName.TITLE_A_TO_Z,
        SortName.TITLE_Z_TO_A,
    )

    @Test
    fun filmsAndSeriesListTheirEightSortsEachPairsUsualWayFirst() {
        for (type in MediaType.entries) {
            val choices = sortChoices(sortOptions(type))
            assertEquals("$type", tvosOrder, choices.map { it.name })
            assertEquals("$type: the page's own sort leads", "popularity.desc", choices.first().option.value)
        }
    }

    @Test
    fun eachValueHasItsWords() {
        assertEquals(SortName.NEWEST, SortName.of("first_air_date.desc"))
        assertEquals(SortName.OLDEST, SortName.of("release_date.asc"))
        assertEquals(SortName.TITLE_A_TO_Z, SortName.of("original_name.asc"))
        assertEquals(SortName.TITLE_Z_TO_A, SortName.of("original_title.desc"))
        assertEquals(SortName.LOWEST_RATED, SortName.of("vote_average.asc"))
    }

    @Test
    fun aSortCoreAddsLaterKeepsCoresWordsAndComesAfterThoseBefore() {
        assertNull(SortName.of("revenue.desc"))
        assertNull(SortName.of("popularity"))
        val choices = sortChoices(listOf(SortOption("revenue.desc", "Revenue Descending"), SortOption("popularity.asc", "Popularity Ascending"), SortOption("popularity.desc", "Popularity Descending")))
        assertEquals(listOf("revenue.desc", "popularity.desc", "popularity.asc"), choices.map { it.option.value })
        assertNull(choices.first().name)
    }
}
