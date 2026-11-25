package com.tomdebres.hither.core.rules

import com.tomdebres.hither.core.model.MediaResult
import com.tomdebres.hither.core.model.PersonCredit
import com.tomdebres.hither.core.model.PersonCredits
import com.tomdebres.hither.core.model.PersonDetails

// People (M6, DECISIONS 100): Seerr's person page (`src/components/PersonDetails` at 3f7d5c3) and its
// PersonCard among search results.

/** A person among search results: Seerr's `PersonCard`, the photo and the name. [key] is "person:<id>". */
data class PersonCard(
    override val key: String,
    val personId: Int,
    val name: String,
    val photo: String?,
) : GridCard

/** A person result as Seerr's search draws it, or null for anything else. */
fun personCard(result: MediaResult, images: ImageSource): PersonCard? {
    if (!result.isPerson) return null
    val name = result.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return PersonCard("person:${result.id}", result.id, name, imageUrl(images, result.profilePath, TmdbImage.PROFILE))
}

/** A title on a person's page, with their part in it: "as {character}" for an appearance, the jobs for crew. */
data class CreditCard(val card: TitleCard, val part: String?)

/** Which of Seerr's two lists a person's credits are in. */
enum class CreditKind { APPEARANCES, CREW }

data class CreditSection(val kind: CreditKind, val cards: List<CreditCard>)

/**
 * A person's page, as Seerr's `PersonDetails` works it out: the portrait, the name, born (and died) and
 * where, the other names, the biography in paragraphs, then "Appearances" and "Crew", the one the person
 * is known for first (acting puts Appearances first).
 */
data class PersonPage(
    val id: Int,
    val name: String,
    val photo: String?,
    /** ISO dates, `yyyy-mm-dd`, for the app to write long; null where TMDB has none. */
    val birthday: String?,
    val deathday: String?,
    val placeOfBirth: String?,
    val alsoKnownAs: List<String>,
    val biography: List<String>,
    val sections: List<CreditSection>,
    /**
     * The backdrop across the page's top (the polish 2 audit's item 18): Seerr's `PersonDetails` fades through up
     * to six of the person's titles' backdrops, cast before crew; Hither shows the first of them, still.
     */
    val backdrop: String? = null,
)

/** The person's page from their details and credits. */
fun personPage(details: PersonDetails, credits: PersonCredits, images: ImageSource): PersonPage {
    val appearances = creditCards(credits.cast, images) { parts -> parts.mapNotNull { it.character?.trim()?.ifEmpty { null } } }
    val crew = creditCards(credits.crew, images) { parts -> parts.mapNotNull { it.job?.trim()?.ifEmpty { null } } }
    val sections = listOf(CreditSection(CreditKind.APPEARANCES, appearances), CreditSection(CreditKind.CREW, crew))
        .filter { it.cards.isNotEmpty() }
    val ordered = if (details.knownForDepartment == "Acting") sections else sections.sortedBy { it.kind != CreditKind.CREW }
    return PersonPage(
        id = details.id,
        name = details.name,
        photo = imageUrl(images, details.profilePath, TmdbImage.PROFILE),
        birthday = details.birthday?.trim()?.ifEmpty { null },
        deathday = details.deathday?.trim()?.ifEmpty { null },
        placeOfBirth = details.placeOfBirth?.trim()?.ifEmpty { null },
        alsoKnownAs = details.alsoKnownAs.map { it.trim() }.filter { it.isNotEmpty() },
        biography = biographyParagraphs(details.biography),
        sections = ordered,
        backdrop = (byVotes(credits.cast) + byVotes(credits.crew))
            .firstNotNullOfOrNull { imageUrl(images, it.backdropPath, TmdbImage.BACKDROP) },
    )
}

/** Credits in the cards' order (the most voted first), adult titles left out. */
private fun byVotes(credits: List<PersonCredit>): List<PersonCredit> = credits.filter { !it.adult }.sortedByDescending { it.voteCount }

/** TMDB's biography in paragraphs: its line breaks, blank lines dropped. */
fun biographyParagraphs(biography: String?): List<String> =
    biography.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Seerr's grouping and order: one card per title (a film and a series apart), its parts joined, then
 * the most voted first, ties as TMDB lists them; adult titles left out, as Seerr's route leaves them.
 */
private fun creditCards(credits: List<PersonCredit>, images: ImageSource, parts: (List<PersonCredit>) -> List<String>): List<CreditCard> =
    credits.filter { !it.adult }
        .groupBy { "${it.mediaType}:${it.id}" }
        .values
        .sortedByDescending { it.first().voteCount }
        .mapNotNull { same ->
            val card = titleCard(same.first().asResult(), images) ?: return@mapNotNull null
            CreditCard(card, parts(same).distinct().joinToString(", ").ifEmpty { null })
        }

/** Which of Seerr's two sliders under a title's cast: Recommendations, and Similar. */
enum class RelatedKind { RECOMMENDATIONS, SIMILAR }
