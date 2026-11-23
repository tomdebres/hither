import XCTest

/// The Search tab (DECISIONS 100, 181): tvOS's own search field and keyboard, Seerr's TitleCards with their badges under
/// it, a result opening its title page and Menu coming back to the card, and Android's words when nothing is found.
final class SearchUITests: HitherUITestCase {
    /// Up to the bar and along it to Search; Down into its keyboard.
    private func openSearch() {
        waitForDiscover()
        press(.up)
        waitForFocus("tab Discover")
        for _ in 0..<4 where !hasFocus("tab Search") { press(.right) }
        waitForFocus("tab Search")
        XCTAssertTrue(element("page Search").waitForExistence(timeout: 10), "Search's page")
        press(.down)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10), "tvOS's keyboard")
    }

    func testTypingShowsCardsThatOpenTheirTitle() {
        launchSignedOut()
        signIn()
        openSearch()
        XCTAssertTrue(element("page Search").staticTexts["Find a movie or series by its title, or a person by name."].exists, "the prompt's words")
        app.typeText("Res")
        let card = element(Fixture.film.key)
        XCTAssertTrue(card.waitForExistence(timeout: 20), "Resident Evil's card among the results")
        XCTAssertTrue(element("search results").exists, "the results as a grid of cards")
        XCTAssertTrue(card.descendants(matching: .any)["badge Available"].exists, "the card's status badge")
        Thread.sleep(forTimeInterval: 1)
        shoot("tvos-search-results")
        // Down from the keyboard into the results, to the film's card, and Select: its page.
        for _ in 0..<8 where !hasFocus(Fixture.film.key) {
            guard hasFocusInResults else { press(.down); continue }
            // Down from the keyboard lands on the card under the key it left: steer to the film's card.
            let focused = app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true")).frame
            press(focused.midX > card.frame.midX ? .left : .right)
        }
        waitForFocus(Fixture.film.key, "the film's card in the results")
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10), "the title page")
        waitForLabel("title name", contains: Fixture.film.title)
        press(.menu)
        waitForFocus(Fixture.film.key, "Menu returns to the result's card")
    }

    func testNoResultsInSeerrsWords() {
        launchSignedOut()
        signIn()
        openSearch()
        app.typeText("Qwxzv")
        // The system's way of saying so (`StateView`): its symbol and the system's own words for it, as
        // `ContentUnavailableView.search` has them (the review's S37: Seerr's "No results." as a bare line). (The page
        // holds nothing else, and SwiftUI folds the state's own container into the page's: its words and symbol are
        // the page's.)
        let page = element("page Search")
        XCTAssertTrue(page.staticTexts["No Results for \u{201C}Qwxzv\u{201D}"].waitForExistence(timeout: 20), "the system's words")
        XCTAssertTrue(page.staticTexts["Check the spelling or try a new search."].exists, "the system's hint")
        XCTAssertTrue(page.images["magnifyingglass"].exists, "the state's symbol")
        XCTAssertFalse(element("search results").exists)
        shoot("tvos-search-no-results")
    }

    private var hasFocusInResults: Bool {
        element("search results").descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists
    }
}
