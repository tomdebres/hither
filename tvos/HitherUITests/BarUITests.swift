import XCTest

/// The system tab bar (Tom's hybrid look, DECISIONS 181), rewritten from part 2's custom bar's tests: its tabs and their
/// order, Issues only with the right, Up landing on the current tab, Left and Right walking it, Menu from content to the
/// bar and back to the tab left, Menu on the bar leaving the app, the bar going as the rows scroll.
final class BarUITests: HitherUITestCase {
    func testTabsInOrderWithoutIssues() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        waitForTabOrder(["tab Discover", "tab Requests", "tab Search", "tab Account"], "no Issues without an issue right; the account's last")
        XCTAssertEqual(element("tab Search").label, "Search")
    }

    /// One kind of label across the bar (the review's F23), as Apple's own bars: the pages' tabs in words alone, then
    /// search's lone magnifying glass and the account's person alone at the end. The bar mixed words, a symbol with words
    /// ("Search") and the account's name with a symbol. The account's name is what VoiceOver reads, and Settings shows it.
    func testOneKindOfLabelAcrossTheBar() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        waitForTabOrder(["tab Discover", "tab Requests", "tab Search", "tab Account"])
        Thread.sleep(forTimeInterval: 2)
        for name in ["Discover", "Requests"] {
            let tab = element("tab \(name)")
            XCTAssertEqual(tab.images.count, 0, "\(name) in words alone")
            XCTAssertGreaterThan(tab.frame.width, 80, "\(name)'s words drawn: \(tab.frame)")
        }
        for (name, symbol) in [("Search", "magnifyingglass"), ("Account", "person.crop.circle")] {
            let tab = element("tab \(name)")
            XCTAssertTrue(tab.images[symbol].exists, "\(name)'s symbol: \(tab.images.allElementsBoundByIndex.map(\.identifier))")
            XCTAssertLessThan(tab.frame.width, 60, "\(name)'s symbol alone, no words beside it: \(tab.frame)")
        }
        XCTAssertEqual(element("tab Search").label, "Search", "VoiceOver's word for search")
        shoot("tvos-bar")
        assertSignedInAs("User 8")
        Thread.sleep(forTimeInterval: 1)
        shoot("tvos-bar-account")
    }

    func testUpLandsOnTheCurrentTabAndTheBarWalks() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        // Arrival: the top row's first card (Recent Requests', the newest request) takes focus once it is there.
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline, !focusedId.hasPrefix("recent request:") { Thread.sleep(forTimeInterval: 0.5) }
        let first = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'recent request:'"))
            .allElementsBoundByIndex.min { $0.frame.minX < $1.frame.minX }
        XCTAssertEqual(focusedId, first?.identifier, "arrival on Discover: the top row's first card")
        Thread.sleep(forTimeInterval: 2)
        shoot("tvos-discover")

        // From the third request card, under the Search tab, Up lands on the current tab, not the nearest one.
        press(.right, times: 2)
        let card = focusedId
        XCTAssertTrue(card.hasPrefix("recent request:"), "focus on a request card, it is on \(card)")
        XCTAssertGreaterThan(element(card).frame.midX, element("tab Search").frame.minX, "the card sits under the Search tab")
        press(.up)
        waitForFocus("tab Discover", "Up from the rows lands on the current tab")

        // Right walks the bar to its end and stops there; each tab's page shows as focus comes to it.
        press(.right)
        waitForFocus("tab Requests")
        XCTAssertTrue(element("page Requests").waitForExistence(timeout: 10), "Requests' page")
        press(.right)
        waitForFocus("tab Search")
        XCTAssertTrue(element("page Search").waitForExistence(timeout: 10), "Search's page")
        press(.right)
        waitForFocus("tab Account")
        XCTAssertTrue(element("page Settings").waitForExistence(timeout: 10), "Settings behind the account's tab")
        press(.right)
        waitForFocus("tab Account", "the bar ends at the account")

        // Left back to Discover, then Down: the card the rows were left on.
        press(.left, times: 3)
        waitForFocus("tab Discover")
        waitForDiscover()
        press(.down)
        waitForFocus(card, "Down lands on the card the rows were left on")
    }

    /// Menu from a tab page's content goes to the bar, on the tab it was left from; the bar has gone as the rows scrolled
    /// and is back with Menu, the page at its top, so Down lands on the top row's card left (Android's Back from the rows).
    func testMenuFromContentReturnsToTheTabLeft() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline, !focusedId.hasPrefix("recent request:") { Thread.sleep(forTimeInterval: 0.5) }
        let arrival = focusedId
        XCTAssertGreaterThanOrEqual(tabBar.frame.minY, 0, "the bar over the top of Discover")
        // Down three rows: the bar goes as the rows scroll.
        press(.down, times: 3)
        let card = focusedId
        XCTAssertTrue(card.hasPrefix("card "), "focus on a card three rows down, it is on \(card)")
        XCTAssertLessThanOrEqual(tabBar.frame.maxY, 0, "the bar scrolled away with the rows")
        // Menu: the bar is back, on Discover's tab, and Hither is still at the front.
        press(.menu)
        waitForFocus("tab Discover", "Menu from the rows goes to the tab left")
        XCTAssertGreaterThanOrEqual(tabBar.frame.minY, 0, "the bar back on screen")
        XCTAssertEqual(app.state, .runningForeground, "Menu from the content stays in Hither")
        press(.down)
        waitForFocus(arrival, "Down from the bar: the top row's card left, the page at its top")

        // The same from another tab's content: Settings' first row (About, DECISIONS 203), Menu, the account's tab.
        press(.up)
        waitForFocus("tab Discover")
        press(.right, times: 3)
        waitForFocus("tab Account")
        press(.down)
        waitForFocus("about")
        press(.menu)
        waitForFocus("tab Account", "Menu from Settings goes to the account's tab, the one left")
    }

    func testMenuOnTheBarLeavesTheApp() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        press(.up)
        waitForFocus("tab Discover")
        press(.menu)
        XCTAssertTrue(app.wait(for: .runningBackground, timeout: 10) || app.state == .runningBackgroundSuspended,
                      "Menu on the bar leaves Hither (state \(app.state.rawValue))")
    }

    func testIssuesTabWithTheRightReadLive() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        XCTAssertFalse(element("tab Issues").exists, "no Issues tab without an issue right")
        // The right given on the server: Discover's plan reads it again on the next start, from auth/me.
        Fake.set("/fake/account?createissues=1&viewissues=1")
        app.terminate()
        launchAsLeft()
        waitForDiscover()
        XCTAssertTrue(element("tab Issues").waitForExistence(timeout: 20), "the Issues tab with the right")
        waitForTabOrder(["tab Discover", "tab Requests", "tab Issues", "tab Search", "tab Account"], "Tom's order")
        press(.up)
        waitForFocus("tab Discover")
        press(.right, times: 2)
        waitForFocus("tab Issues")
        XCTAssertTrue(element("page Issues").waitForExistence(timeout: 10), "Issues' page")
    }
}
