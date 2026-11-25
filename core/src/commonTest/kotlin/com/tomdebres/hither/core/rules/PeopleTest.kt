package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaInfo
import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.PersonCredit
import com.tomdebres.hither.core.model.PersonCredits
import com.tomdebres.hither.core.model.PersonDetails
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.Test

/** People (M6, DECISIONS 100) against Seerr's PersonDetails and search at 3f7d5c3. */
class PeopleTest {
    private val base = "http://seerr.test:5055"
    private val images = ImageSource(base, cacheImages = true, tmdbImages = ImageSource.TMDB_IMAGES)

    private fun credit(id: Int, type: String, votes: Int, character: String? = null, job: String? = null, adult: Boolean = false) = PersonCredit(
        id = id, mediaType = type, title = if (type == "movie") "Film $id" else null, name = if (type == "tv") "Show $id" else null,
        voteCount = votes, character = character, job = job, adult = adult, mediaInfo = MediaInfo(tmdbId = id, status = 5),
    )

    @Test
    fun `credits are one card per title - parts joined - the most voted first - adult titles out`() {
        val credits = PersonCredits(
            id = 1,
            cast = listOf(
                credit(10, "movie", 50, "Bryan"),
                credit(20, "tv", 900, "Narrator"),
                credit(20, "tv", 900, "Himself"),
                credit(10, "tv", 10, "Same id, a series"),
                credit(30, "movie", 9999, "Hidden", adult = true),
            ),
            crew = listOf(credit(40, "movie", 5, job = "Director"), credit(40, "movie", 5, job = "Writer")),
        )
        val page = personPage(PersonDetails(id = 1, name = "Pat", knownForDepartment = "Acting"), credits, images)
        val appearances = page.sections.first()
        assertEquals(CreditKind.APPEARANCES, appearances.kind)
        assertEquals(listOf("tv:20", "movie:10", "tv:10"), appearances.cards.map { it.card.key })
        assertEquals("Narrator, Himself", appearances.cards.first().part)
        assertEquals("Available", appearances.cards.first().card.badge?.text)
        assertEquals("Director, Writer", page.sections[1].cards.single().part)
    }

    @Test
    fun `the backdrop is the first title's with one - the most voted first - cast before crew - adult titles out`() {
        val withBackdrop = { c: PersonCredit, path: String -> c.copy(backdropPath = path) }
        val credits = PersonCredits(
            id = 1,
            cast = listOf(
                credit(10, "movie", 50, "Bryan"),
                withBackdrop(credit(11, "movie", 70, "Lead"), "/eleven.jpg"),
                withBackdrop(credit(12, "movie", 90, "Lead"), "/twelve.jpg"),
                withBackdrop(credit(13, "movie", 999, "Hidden", adult = true), "/adult.jpg"),
                credit(14, "movie", 500, "No backdrop"),
            ),
            crew = listOf(withBackdrop(credit(40, "movie", 9000, job = "Director"), "/crew.jpg")),
        )
        // Crew-known: the crew's section comes first on the page, but the backdrop still starts with the cast
        val page = personPage(PersonDetails(id = 1, name = "Pat", knownForDepartment = "Directing"), credits, images)
        assertEquals("$base/imageproxy/tmdb/t/p/w1920_and_h800_multi_faces/twelve.jpg", page.backdrop)
        val crewOnly = personPage(PersonDetails(id = 1, name = "Pat"), PersonCredits(id = 1, crew = credits.crew), images)
        assertEquals("$base/imageproxy/tmdb/t/p/w1920_and_h800_multi_faces/crew.jpg", crewOnly.backdrop)
        assertNull(personPage(PersonDetails(id = 1, name = "Pat"), PersonCredits(id = 1, cast = listOf(credit(10, "movie", 5, "A"))), images).backdrop)
    }

    @Test
    fun `the section the person is known for comes first - and an empty one is not shown`() {
        val credits = PersonCredits(id = 1, cast = listOf(credit(10, "movie", 1, "Part")), crew = listOf(credit(40, "movie", 1, job = "Director")))
        assertEquals(listOf(CreditKind.CREW, CreditKind.APPEARANCES), personPage(PersonDetails(id = 1, knownForDepartment = "Directing"), credits, images).sections.map { it.kind })
        assertEquals(listOf(CreditKind.APPEARANCES, CreditKind.CREW), personPage(PersonDetails(id = 1, knownForDepartment = "Acting"), credits, images).sections.map { it.kind })
        assertEquals(listOf(CreditKind.APPEARANCES), personPage(PersonDetails(id = 1), credits.copy(crew = emptyList()), images).sections.map { it.kind })
    }

    @Test
    fun `the header's facts and the biography's paragraphs`() {
        val page = personPage(
            PersonDetails(
                id = 7, name = "Pat", birthday = "1980-02-03", deathday = " ", placeOfBirth = "Example City", alsoKnownAs = listOf(" P. ", ""),
                biography = "First paragraph.\n\nSecond paragraph.\n \nThird.", profilePath = "/pat.jpg",
            ),
            PersonCredits(),
            images,
        )
        assertEquals("1980-02-03", page.birthday)
        assertNull(page.deathday)
        assertEquals(listOf("P."), page.alsoKnownAs)
        assertEquals(listOf("First paragraph.", "Second paragraph.", "Third."), page.biography)
        assertEquals("$base/imageproxy/tmdb/t/p/w600_and_h900_bestv2/pat.jpg", page.photo)
        assertEquals(emptyList<CreditSection>(), page.sections)
    }

    @Test
    fun `search keeps people - as Seerr's does - each once - in the server's order`() {
        val results = listOf(
            MediaResult(id = 1, mediaType = "movie", title = "Film"),
            MediaResult(id = 2, mediaType = "person", name = "Pat", profilePath = "/pat.jpg"),
            MediaResult(id = 3, mediaType = "collection", name = "A collection"),
            MediaResult(id = 4, mediaType = "person", name = " "),
        )
        val cards = appendResults(emptyList(), results, images)
        assertEquals(listOf("movie:1", "person:2"), cards.map { it.key })
        val person = cards[1] as PersonCard
        assertEquals("Pat" to 2, person.name to person.personId)
        assertEquals("$base/imageproxy/tmdb/t/p/w600_and_h900_bestv2/pat.jpg", person.photo)
        assertEquals(cards, appendResults(cards, results.take(2), images), "a repeat is not added")
        val requested = mediaBadge(3)
        assertEquals(listOf(requested, null), withGridBadge(cards, "movie:1", requested).map { (it as? TitleCard)?.badge })
        assertEquals(cards, withGridBadge(cards, "person:2", requested), "a person carries no badge")
    }
}
