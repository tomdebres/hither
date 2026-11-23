import XCTest

/// What a page or a row shows in place of what it could not show (`StateView`, the review's X2 and F24): the system's
/// way (a symbol, the words, one action that takes focus), for Discover's failed and empty rows and My Requests failed
/// and empty. Before it, each was a grey line and a button.
final class StatesUITests: HitherUITestCase {
    /// Popular Series fails and Upcoming Movies is empty: each row says so in its place, with its symbol; the failed
    /// one offers Try Again, which focus reaches as it walks down the rows.
    func testDiscoverRowsFailedAndEmpty() {
        Fake.set("/fake/rows?fail=tv&empty=upcoming")
        launchSignedOut()
        signIn()
        waitForDiscover()
        let failed = element("row failed", in: "row \(Row.popularSeries.name)")
        XCTAssertTrue(failed.waitForExistence(timeout: 20), "Popular Series' failure in its row")
        XCTAssertTrue(stateWords("row failed").contains("This row did not load."), stateWords("row failed"))
        XCTAssertTrue(failed.images.firstMatch.exists, "its symbol")
        XCTAssertTrue(element("try again", in: "row failed").exists, "Try Again inside it")
        let empty = element("row empty", in: "row \(Row.upcomingMovies.name)")
        XCTAssertTrue(empty.waitForExistence(timeout: 20), "Upcoming Movies' nothing in its row")
        XCTAssertTrue(stateWords("row empty").contains("No Results"), stateWords("row empty"))
        XCTAssertTrue(empty.images.firstMatch.exists, "its symbol")
        pressUntilFocus(.down, "row \(Row.popularSeries.name)", times: 8)
        Thread.sleep(forTimeInterval: 1)
        shoot("tvos-row-failed")
        // Room between the words and Try Again, as the system spaces its actions (Tom, M8 part 2).
        assertActionsRoom("row failed")
    }

    /// My Requests when the list fails (Try Again, focused from the tab), then when there is none (the way back to
    /// Discover), each in the system's way.
    func testMyRequestsFailedThenEmpty() {
        Fake.set("/fake/requests?fail=1")
        launchSignedOut()
        signIn()
        openTab("Requests", steps: 1, page: "Requests")
        let failed = element("requests failed")
        XCTAssertTrue(failed.waitForExistence(timeout: 20), "the failure")
        XCTAssertTrue(stateWords("requests failed").contains("Your requests did not load."), stateWords("requests failed"))
        XCTAssertTrue(failed.images.firstMatch.exists, "its symbol")
        press(.down)
        waitForFocus("side my requests", "Down from the tab to the side list")
        press(.right)
        waitForFocus("try again", "Right to Try Again")
        shoot("tvos-requests-failed")
        // Room between the words and Try Again, as the system spaces its actions (Tom, M8 part 2).
        assertActionsRoom("requests failed")

        Fake.set("/fake/requests?empty=1")
        press(.select)
        let empty = element("requests empty")
        XCTAssertTrue(empty.waitForExistence(timeout: 20), "none")
        XCTAssertTrue(stateWords("requests empty").contains("No requests yet."), stateWords("requests empty"))
        XCTAssertTrue(stateWords("requests empty").contains("What you request shows here"), "the line under it: \(stateWords("requests empty"))")
        XCTAssertTrue(empty.images.firstMatch.exists, "its symbol")
        XCTAssertTrue(element("back to discover", in: "requests empty").exists, "the way back inside it")
        waitForFocus("back to discover", "focus on its one action")
        shoot("tvos-requests-empty")
        assertActionsRoom("requests empty")
    }
}
