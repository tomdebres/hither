import XCTest

/// The Top Shelf (standing in for Android's "Ready to Watch" channel and Watch Next, DECISIONS 199) and the links its
/// items open: `hither://title/<movie|tv>/<id>`, core's `TitleLink`.
final class TopShelfUITests: HitherUITestCase {
    /// The family's ready titles in fake-seerr's records.
    private let ready = ["The Good Lawyer", "Model Citizen", "Other Mommy"]

    func testTheTopShelfShowsTheReadyTitlesAndOpensThem() {
        app.launchEnvironment["HITHER_LOOK_DUE"] = "always"
        launchSignedOut()
        signIn()
        waitForDiscover()
        // The look on arrival has written the shelf; Home shows it above Hither's icon in the top row.
        Thread.sleep(forTimeInterval: 4)
        remote.press(.home)
        let home = XCUIApplication(bundleIdentifier: "com.apple.HeadBoard")
        // The Top Shelf shows the focused app's: Right along the top row to Hither's icon.
        let icon = home.icons["Hither"]
        XCTAssertTrue(icon.waitForExistence(timeout: 10), "Hither's icon on Home")
        for _ in 0..<8 where !icon.hasFocus {
            remote.press(.right)
            Thread.sleep(forTimeInterval: 0.8)
        }
        XCTAssertTrue(icon.hasFocus, "focus on Hither's icon")
        let shelf = home.descendants(matching: .any).matching(NSPredicate(format: "label == %@", ready[0])).firstMatch
        XCTAssertTrue(shelf.waitForExistence(timeout: 20), "the ready titles on the Top Shelf over Hither's icon")
        for title in ready {
            XCTAssertTrue(home.descendants(matching: .any).matching(NSPredicate(format: "label == %@", title)).firstMatch.exists, "\(title) on the Top Shelf")
        }
        Thread.sleep(forTimeInterval: 2)
        shoot("tvos-top-shelf")

        // Up into the shelf, Select on its first title: Hither opens on that title's page.
        let focusedTitle = { home.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.label }
        for _ in 0..<3 where !ready.contains(focusedTitle()) {
            remote.press(.up)
            Thread.sleep(forTimeInterval: 1)
        }
        let chosen = focusedTitle()
        XCTAssertTrue(ready.contains(chosen), "focus on a shelf item, it is on \"\(chosen)\"")
        shoot("tvos-top-shelf-focused")
        remote.press(.select)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20), "Hither comes to the front")
        XCTAssertTrue(element("title page").waitForExistence(timeout: 20), "the item's title page")
        waitForLabel("title name", contains: chosen)
        // Menu from it comes back to Discover.
        press(.menu)
        XCTAssertTrue(element("page Discover").waitForExistence(timeout: 10))
    }

    /// Hither's icon on Home (DECISIONS 214), and the Top Shelf over it signed out: the extension has nothing to show, so
    /// tvOS shows the brand assets' Top Shelf image, and none of the family's titles.
    func testTheIconAndTheTopShelfImageSignedOut() {
        launchSignedOut()
        remote.press(.home)
        let home = XCUIApplication(bundleIdentifier: "com.apple.HeadBoard")
        let icon = home.icons["Hither"]
        XCTAssertTrue(icon.waitForExistence(timeout: 10), "Hither's icon on Home")
        for _ in 0..<8 where !icon.hasFocus {
            remote.press(.right)
            Thread.sleep(forTimeInterval: 0.8)
        }
        XCTAssertTrue(icon.hasFocus, "focus on Hither's icon")
        // The shelf's image fades in after the icon takes focus.
        Thread.sleep(forTimeInterval: 4)
        XCTAssertEqual(home.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", ready)).count, 0,
                       "no titles on the Top Shelf while signed out")
        shoot("tvos-icon-focused")
        // One step back along the row (Hither's is the last icon), Hither's at rest.
        remote.press(.left)
        Thread.sleep(forTimeInterval: 2)
        XCTAssertFalse(icon.hasFocus, "focus moved on from Hither's icon")
        shoot("tvos-icon-home")
    }

    func testALinkOpensItsTitleAndNothingElse() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        // Opened through the system, as a Top Shelf item opens it, while Hither runs: a link that is not exactly Hither's
        // title link opens nothing.
        let system = XCUIDevice.shared.system
        system.open(URL(string: "hither://title/movie/12abc")!)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 10), "Hither at the front")
        Thread.sleep(forTimeInterval: 3)
        XCTAssertFalse(element("title page").exists, "a malformed link opens nothing")
        XCTAssertTrue(element("page Discover").exists, "Discover as it was")
        // The Top Shelf's form opens the title's page over Discover; Menu comes back to Discover.
        system.open(URL(string: "hither://title/movie/354912")!)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 20), "the link's title page")
        waitForLabel("title name", contains: "Coco")
        press(.menu)
        XCTAssertTrue(element("page Discover").waitForExistence(timeout: 10), "Menu comes back to Discover")

        // From a cold start: the title's page once the session is up.
        app.terminate()
        app.launchArguments = []
        app.open(URL(string: "hither://title/tv/1877")!)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 30), "the link's title page at a cold start")
        waitForLabel("title name", contains: "Phineas and Ferb")
        press(.menu)
        XCTAssertTrue(element("page Discover").waitForExistence(timeout: 10), "Menu comes back to Discover")
    }
}
