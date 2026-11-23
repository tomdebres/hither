import HitherCore
import XCTest
@testable import Hither

/// The words in tvOS's voice (polish part 7, the review's X11): `core`'s values mapped in Swift, `core` and Android
/// keeping Seerr's.
final class WordsTests: XCTestCase {
    /// Seerr's eight sorts as tvOS says them (S35), each pair's usual way first, the pairs in Seerr's order; for films
    /// and series alike.
    func testSortsInTvOSsWords() {
        for type in [MediaType.movie, MediaType.tv] {
            let sorts = Words.sorts(BrowseKt.sortOptions(type: type))
            XCTAssertEqual(sorts.map(\.words), [
                "Most Popular", "Least Popular", "Newest", "Oldest", "Highest Rated", "Lowest Rated", "Title A to Z", "Title Z to A",
            ], "\(type)")
            XCTAssertEqual(Set(sorts.map(\.value)), Set(BrowseKt.sortOptions(type: type).map(\.value)), "every sort of core's, once")
        }
        XCTAssertEqual(Words.sorts(BrowseKt.sortOptions(type: .movie)).first?.value, BrowseKt.DEFAULT_SORT, "the page's own sort first")
        // A sort core adds later keeps core's words rather than vanishing.
        XCTAssertNil(Words.sortName("revenue.desc"))
        XCTAssertEqual(Words.sorts([SortOption(value: "revenue.desc", label: "Revenue Descending")]).map(\.words), ["Revenue Descending"])
    }

    /// The Filters button keeps its name and counts what is set (S36).
    func testFiltersCounted() {
        XCTAssertEqual(Words.filters(0), "Filters")
        XCTAssertEqual(Words.filters(1), "Filters (1)")
        XCTAssertEqual(Words.filters(3), "Filters (3)")
        XCTAssertEqual(Words.clearFilters, "Clear Filters")
    }

    /// A keyword's page heading starts with a capital (S38); the rest as TMDB has it.
    func testKeywordHeadings() {
        XCTAssertEqual(Words.keywordMovies("outbreak"), "Outbreak Movies")
        XCTAssertEqual(Words.keywordSeries("based on novel or book"), "Based on novel or book Series")
        XCTAssertEqual(Words.keywordMovies("DC Comics"), "DC Comics Movies")
        XCTAssertEqual(Words.keywordMovies(""), " Movies")
    }

    /// Sign Out says what signing in again takes, by the account's way in (M8 part 2's report).
    func testSignOutByTheWayIn() {
        XCTAssertTrue(Words.signOutBody(.local).contains("password"))
        XCTAssertTrue(Words.signOutBody(.mediaServer).contains("username and password"))
        for method in [AccountMethod.plex, .quickConnect] {
            XCTAssertFalse(Words.signOutBody(method).contains("password"), "\(method): nothing kept, no password asked")
        }
        XCTAssertTrue(Words.signOutBody(.plex).contains("plex.tv/link"))
        XCTAssertTrue(Words.signOutBody(.quickConnect).contains("Quick Connect"))
    }

    /// No word of the app's ends in an exclamation mark, and none is Seerr's web-form voice (S12, S14).
    func testNoWebFormVoice() {
        let words = [Words.errorServer, Words.errorEmail, Words.errorPassword, Words.errorUsername, Words.noResults,
                     Words.noResultsFor("Coco"), Words.readyToWatch, Words.readyToWatch4k, Words.signInHeading(server: "Seerr"),
                     Words.signInWithMediaServer("Jellyfin")]
        for word in words {
            XCTAssertFalse(word.hasSuffix("!"), word)
            XCTAssertFalse(word.contains("You must provide"), word)
            XCTAssertFalse(word.hasPrefix("Login"), word)
        }
        XCTAssertEqual(Words.noResultsFor("Coco"), "No Results for \u{201C}Coco\u{201D}")
    }
}
