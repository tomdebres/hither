import XCTest

extension HitherUITestCase {
    /// From Discover's arrival, Up to the bar and along it to [tab]'s page ("Requests", "Issues", "Account").
    func openTab(_ tab: String, steps: Int, page: String, file: StaticString = #filePath, line: UInt = #line) {
        waitForDiscover(file: file, line: line)
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline, focusedId == "(none)" { Thread.sleep(forTimeInterval: 0.3) }
        press(.up)
        waitForFocus("tab Discover", file: file, line: line)
        press(.right, times: steps)
        waitForFocus("tab \(tab)", file: file, line: line)
        XCTAssertTrue(element("page \(page)").waitForExistence(timeout: 10), "\(page)'s page", file: file, line: line)
    }

    /// The lockups whose identifiers start with [prefix] in reading order (row by row, left to right in each), with
    /// each one's title as it reads now ("" while it loads), from one snapshot of the page.
    func rows(_ prefix: String) -> [(id: String, title: String)] {
        guard let snapshot = try? app.snapshot() else { return [] }
        var found: [(id: String, title: String, frame: CGRect)] = []
        func walk(_ node: XCUIElementSnapshot) {
            if node.identifier.hasPrefix(prefix) {
                let title = node.children.lazy.compactMap { Self.find("card title", in: $0) }.first ?? ""
                found.append((node.identifier, title, node.frame))
                return
            }
            node.children.forEach(walk)
        }
        walk(snapshot)
        // A lifted lockup sits a little higher than its neighbours: a line of the grid is anything within 40 points.
        return found.sorted { abs($0.frame.minY - $1.frame.minY) < 40 ? $0.frame.minX < $1.frame.minX : $0.frame.minY < $1.frame.minY }
            .map { ($0.id, $0.title) }
    }

    private static func find(_ id: String, in node: XCUIElementSnapshot) -> String? {
        if node.identifier == id { return node.label }
        for child in node.children { if let label = find(id, in: child) { return label } }
        return nil
    }

    /// Waits for the rows starting with [prefix] to read [titles], top to bottom.
    func waitForRows(_ prefix: String, _ titles: [String], _ message: String = "", timeout: TimeInterval = 30, file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(timeout)
        var now = rows(prefix).map(\.title)
        while Date() < deadline, now != titles {
            Thread.sleep(forTimeInterval: 0.5)
            now = rows(prefix).map(\.title)
        }
        XCTAssertEqual(now, titles, message, file: file, line: line)
    }

    /// The element [id] inside the lockup [row].
    func inside(_ row: String, _ id: String) -> XCUIElement {
        element(row).descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// The Library's split (the review's F15, DECISIONS 259): the side list [side] at the left margin and the lockups
    /// [lockups] (identifiers, in reading order) in a grid beside it, four to a line, the first line level with the
    /// side column's top ([top]: the list itself, or what heads it) and each lockup a poster's width. A lifted
    /// lockup's picture reads bigger: measure with focus off them, or on the first one.
    func assertLibrarySplit(side: String, lockups: [String], top: String? = nil, file: StaticString = #filePath, line: UInt = #line) {
        let list = element(side).frame
        let columnTop = top.map { element($0).frame.minY } ?? list.minY
        XCTAssertEqual(list.minX, 80, accuracy: 4, "the side list at tvOS's left margin", file: file, line: line)
        // Each lockup's picture at rest; a lifted one (focus on it) is left out of the measures.
        let frames: [CGRect?] = lockups.map { id in
            hasFocus(id) ? nil : element(id).descendants(matching: .any).matching(identifier: "card image").firstMatch.frame
        }
        guard let firstAtRest = frames.firstIndex(where: { $0 != nil }), let reference = frames[firstAtRest] else {
            return XCTFail("no lockup at rest", file: file, line: line)
        }
        let column = { (index: Int) in index % 4 }
        XCTAssertGreaterThanOrEqual(reference.minX, list.maxX + 40, "the lockups beside the list, not under it", file: file, line: line)
        if firstAtRest < 4 {
            XCTAssertEqual(reference.minY, columnTop, accuracy: 30, "the first line of lockups level with the side column's top", file: file, line: line)
        }
        for (index, frame) in frames.enumerated() {
            guard let frame else { continue }
            XCTAssertEqual(frame.width, 250, accuracy: 2, "lockup \(index) a poster's width", file: file, line: line)
            XCTAssertLessThanOrEqual(frame.maxX, 1840 + 1, "lockup \(index) inside the right margin", file: file, line: line)
            if index < 4, index != firstAtRest, firstAtRest < 4 {
                XCTAssertEqual(frame.minY, reference.minY, accuracy: 2, "lockup \(index) on the first line", file: file, line: line)
            }
            if index > 0, let before = frames[index - 1], column(index) > 0 {
                XCTAssertGreaterThan(frame.minX, before.maxX, "lockup \(index) right of the one before", file: file, line: line)
            }
            if index >= 4, let above = frames[index - 4] {
                XCTAssertEqual(frame.minX, above.minX, accuracy: 2, "lockup \(index) under lockup \(index - 4)", file: file, line: line)
                XCTAssertGreaterThan(frame.minY, above.maxY, "lockup \(index) on the next line", file: file, line: line)
            }
        }
    }

    /// Holds Select, as a person does for a lockup's menu (tvOS's long press).
    func longPress() {
        remote.press(.select, forDuration: 1.2)
        Thread.sleep(forTimeInterval: 0.6)
    }

    /// An item of the open context menu or dialog by its words: tvOS draws the items as cells or buttons, the identifiers
    /// given in SwiftUI reaching them or not, so they are found by their words.
    func menuItem(_ words: String) -> XCUIElement {
        let predicate = NSPredicate(format: "label == %@", words)
        let button = app.buttons.matching(predicate).firstMatch
        return button.exists ? button : app.cells.containing(predicate).firstMatch
    }
}

/// My Requests (Android's `RequestsScreen`, DECISIONS 66, 101, 124) as tvOS's Library (Tom's D1, DECISIONS 259): the side
/// list and the user's own requests, newest first, as lockups with their status and when; Select to the title and Menu
/// back to the lockup; read again on each arrival; Cancel Request in a pending one's long-press menu, asked first.
final class RequestsUITests: HitherUITestCase {
    /// The recorded requests of the family's account (fake-seerr's, all three ready and moved on by the owner).
    private let recorded = ["The Good Lawyer", "Model Citizen", "Other Mommy"]
    private let mine = "side my requests"

    func testMyRequestsWithTheirDetails() {
        // A film waiting for approval, and a series' two seasons the server's owner has approved.
        Fake.set("/fake/account?autoapprove=0")
        Fake.set("/fake/ask?title=movie:354912")
        Fake.set("/fake/ask?title=tv:1421&seasons=1,2")
        Fake.set("/fake/move?title=tv:1421&to=approved")
        launchSignedOut()
        signIn()
        openTab("Requests", steps: 1, page: "Requests")
        waitForRows("request request:", ["Modern Family", "Coco"] + recorded, "the user's own requests, newest first")
        assertNoHeading("page Requests", "Requests")
        let all = rows("request request:")
        // The Library's split: My Requests first in the side list, chosen, under the bar; the lockups beside it.
        assertUnderTheBar(element(mine), "My Requests, the side list's first row")
        XCTAssertEqual(element(mine).label, "My Requests")
        XCTAssertEqual(element(mine).value as? String, "selected", "My Requests is the one shown")
        assertLibrarySplit(side: "requests list", lockups: all.map(\.id))
        let (series, film, ready) = (all[0].id, all[1].id, all[2].id)

        // Each lockup: its status, and when under it; who moved it on where somebody has (Seerr's "Modified … by …").
        XCTAssertTrue(inside(series, "status Requested").exists, "the approved series reads Requested")
        // "33 seconds ago by User 1" is wider than a poster: the line keeps when, and VoiceOver reads the whole.
        let seriesLine = inside(series, "card when").label
        XCTAssertTrue(seriesLine.hasSuffix(" ago by User 1") || seriesLine.hasSuffix(" ago"), "when it was moved on, and by whom where it fits: \(seriesLine)")
        let seriesSays = element(series).value as? String ?? ""
        XCTAssertTrue(seriesSays.contains(" ago by User 1"), "VoiceOver reads who moved it on: \(seriesSays)")
        XCTAssertTrue(seriesSays.contains("Seasons 1, 2"), "VoiceOver reads the series' seasons: \(seriesSays)")
        XCTAssertTrue(inside(film, "status Pending").exists, "the waiting film reads Pending")
        XCTAssertTrue(inside(film, "card when").label.hasSuffix(" ago"), "when it was asked for: \(inside(film, "card when").label)")
        XCTAssertFalse(inside(film, "card when").label.contains(" by "), "nobody has moved it on")
        // A ready one, moved on by the owner on 1 January (the fake's date): beyond a week the date, as every date on the TV.
        XCTAssertTrue(inside(ready, "status Available").exists, "a ready request reads Available")
        let newYear = Date(timeIntervalSince1970: 1_767_225_600).formatted(date: .abbreviated, time: .omitted)
        XCTAssertEqual(inside(ready, "card when").label, "\(newYear) by User 1", "the date, not \"10 months ago\", and who: the whole line fits")
        // No buttons beside the lockups: nothing coloured, nothing but lockups and the list to focus.
        XCTAssertFalse(app.buttons.matching(NSPredicate(format: "label == %@", "Cancel Request")).firstMatch.exists, "no Cancel Request on the page")

        // Down from the tab lands on My Requests; Right on the newest; Select opens its title, Menu comes back to it.
        press(.down)
        waitForFocus(mine, "Down from the tab to the side list's My Requests")
        press(.right)
        waitForFocus(series, "Right to the newest request")
        shoot("tvos-my-requests")
        press(.right)
        waitForFocus(film)
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10), "the title page")
        waitForLabel("title name", contains: "Coco")
        press(.menu)
        waitForFocus(film, "Menu comes back to the lockup")
        // Left from the grid's first column goes back to the list.
        press(.left, times: 2)
        waitForFocus(mine, "Left from the lockups to the side list")

        // Every arrival reads the list again: a request made meanwhile is first when the page comes back.
        press(.menu)
        waitForFocus("tab Requests")
        press(.left)
        waitForFocus("tab Discover")
        Fake.set("/fake/ask?title=tv:1877&seasons=2")
        press(.right)
        waitForFocus("tab Requests")
        waitForRows("request request:", ["Phineas and Ferb", "Modern Family", "Coco"] + recorded, "read again on arrival, the new request first")
    }

    func testCancelRequestAsksFirst() {
        Fake.set("/fake/account?autoapprove=0")
        Fake.set("/fake/ask?title=movie:354912")
        launchSignedOut()
        signIn()
        openTab("Requests", steps: 1, page: "Requests")
        waitForRows("request request:", ["Coco"] + recorded)
        let film = rows("request request:")[0].id
        let ready = rows("request request:")[1].id
        press(.down)
        waitForFocus(mine)
        press(.right)
        waitForFocus(film)

        // A ready request's menu has no Cancel Request: only a pending one can be cancelled.
        press(.right)
        waitForFocus(ready)
        longPress()
        XCTAssertFalse(menuItem("Cancel Request").exists, "no Cancel Request for a ready request")
        // With nothing in it the menu does not open; if the system opened an empty one, Menu closes it.
        if !hasFocus(ready) { press(.menu) }
        waitForFocus(ready)
        press(.left)
        waitForFocus(film)

        // Long-press: the lockup's menu, with Cancel Request; it asks first, Keep Request first and focused.
        longPress()
        let cancel = menuItem("Cancel Request")
        XCTAssertTrue(cancel.waitForExistence(timeout: 5), "the long-press menu offers Cancel Request")
        shoot("tvos-cancel-menu")
        press(.select)
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5), "Cancel Request asks first")
        waitForFocus("keep request", "Keep Request focused")
        shoot("tvos-cancel-request")
        press(.select)
        XCTAssertTrue(app.alerts.firstMatch.waitForNonExistence(timeout: 5))
        XCTAssertEqual(rows("request request:").map(\.title), ["Coco"] + recorded, "kept")
        waitForFocus(film, "back on the lockup")

        // Confirmed: one DELETE, the lockup goes with focus on the one that took its place: that is what it did, and
        // nothing toasts it (Tom's D4).
        longPress()
        XCTAssertTrue(menuItem("Cancel Request").waitForExistence(timeout: 5))
        press(.select)
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5))
        press(.right)
        waitForFocus("confirm cancel request")
        press(.select)
        waitForRows("request request:", recorded, "the cancelled request's lockup is gone")
        waitForFocus(rows("request request:")[0].id, "focus on the lockup that took its place")
        assertNoToast("a cancelled request")
        shoot("tvos-cancelled")
    }

    /// A cancel the server will not take is the system's alert (Tom's D4: failures are alerts), and the lockup stays,
    /// focus back on it.
    func testACancelThatFailsIsAnAlert() {
        Fake.set("/fake/account?autoapprove=0")
        Fake.set("/fake/ask?title=movie:354912")
        launchSignedOut()
        signIn()
        openTab("Requests", steps: 1, page: "Requests")
        waitForRows("request request:", ["Coco"] + recorded)
        let film = rows("request request:")[0].id
        press(.down)
        waitForFocus(mine)
        press(.right)
        waitForFocus(film)
        longPress()
        XCTAssertTrue(menuItem("Cancel Request").waitForExistence(timeout: 5))
        press(.select)
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5))
        press(.right)
        waitForFocus("confirm cancel request")
        // The server's CSRF protection with Secure cookies, over http: the DELETE cannot be made.
        Fake.set("/fake/server?csrf=1&secure=1")
        press(.select)
        let alert = waitForAlert("Request Not Cancelled", timeout: 15)
        XCTAssertTrue(alertSays("The request could not be changed. Try again."), "Hither's words")
        shoot("tvos-cancel-failed")
        press(.select)
        XCTAssertTrue(alert.waitForNonExistence(timeout: 5), "OK closes it")
        XCTAssertEqual(rows("request request:").map(\.title), ["Coco"] + recorded, "the request stays")
        waitForFocus(film, "back on the lockup")
        assertNoToast("a cancel that failed")
    }
}
