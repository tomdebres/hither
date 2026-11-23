import XCTest

/// Settings, About, the privacy text and Diagnostics (Android's `SettingsScreen`, `AboutScreen`, `PrivacyScreen`,
/// `DiagnosticsScreen`; DECISIONS 87, 107) as system forms and pages. Discover's row order, hiding and custom rows are
/// the server's Discover settings, which Discover follows (`DiscoverUITests.testCustomRowsInTheServersOrder`).
final class SettingsUITests: HitherUITestCase {
    private func openSettings() {
        openTab("Account", steps: 3, page: "Settings")
    }

    func testSettingsRowsInAndroidsOrder() {
        launchSignedOut()
        signIn()
        openSettings()
        let order = ["signed in as", "server address", "about", "admin account", "sign out"]
        let frames = order.map { element($0).frame.minY }
        XCTAssertTrue(order.allSatisfy { element($0).exists }, "every row: \(order.map { "\($0) \(element($0).exists)" })")
        XCTAssertEqual(frames, frames.sorted(), "Android's order: the account, the server, About, Admin Account, Sign Out")
        XCTAssertTrue(element("signed in as").label.contains("User 8"), element("signed in as").label)
        // The server by its name, its address without the scheme (the review's F37: "Seerr · http://localhost:55735").
        let server = element("server address").label
        XCTAssertTrue(server.contains("Seerr"), server)
        XCTAssertTrue(server.contains(Fake.address.replacingOccurrences(of: "http://", with: "")), server)
        XCTAssertFalse(server.contains("http"), "no scheme on the TV: \(server)")
        // Diagnostics is About's, for Tom, not the family's Settings (the review's S41).
        XCTAssertFalse(element("diagnostics", in: "page Settings").exists, "Diagnostics under About, not on Settings")
        // Under the account's tab, which names it: no "Settings" heading of its own (the review's F23).
        assertNoHeading("page Settings", "Settings")
        assertUnderTheBar(element("account header"), "the Account section's header, the page's first line")
        // Rows of words, as tvOS's Settings draws them: no symbol before the words, only the system's chevron after them
        // (the review's F13: each row's symbol had its own width, so the words started at three places).
        for id in ["about", "admin account"] {
            let symbols = element(id).descendants(matching: .image).matching(NSPredicate(format: "identifier != 'chevron.forward'"))
            XCTAssertEqual(symbols.count, 0, "\(id): words, no symbol: \(symbols.allElementsBoundByIndex.map(\.identifier))")
        }
        assertSettingsLayout("page Settings", rows: ["about", "admin account", "sign out"], mark: "settings mark")
        press(.down)
        waitForFocus("about", "focus arrives on About, the first row that does something")
        shoot("tvos-settings")
    }

    /// The pages Settings opens meet the screen as tvOS's Settings does: the title centred at the top margin, the page
    /// under it at tvOS's 80-point side (`PageScaffold`; the review's F6 and F23: inside the safe area they started at
    /// 160 and about 217, each under a title of its own drawn left and white).
    func testSettingsPagesAtTvOSsMargins() {
        launchSignedOut()
        signIn()
        openSettings()
        press(.down)
        waitForFocus("about")
        press(.select)
        let about = assertPageTitle("About", in: "page About")
        assertSettingsLayout("page About", rows: ["privacy", "diagnostics"], mark: "about wordmark", under: about)
        shoot("tvos-about")
        waitForFocus("privacy")
        press(.select)
        assertUnderTheTitle(element("privacy body"), assertPageTitle("Privacy", in: "privacy text"), "the privacy text")
        shoot("tvos-privacy")
        press(.menu)
        waitForFocus("privacy")
        press(.down)
        waitForFocus("diagnostics")
        press(.select)
        let diagnostics = assertPageTitle("Diagnostics", in: "page Diagnostics")
        assertSettingsLayout("page Diagnostics", rows: ["diagnostics none"], mark: "settings mark", under: diagnostics)
        shoot("tvos-diagnostics")
        press(.menu)
        waitForFocus("diagnostics")
        press(.menu)
        waitForFocus("about")
        pressUntilFocus(.down, "admin account")
        press(.select)
        // The approver's sign-in: one column, centred under the page's title, as the family's (`EntryColumn`).
        let admin = assertPageTitle("Admin Account", in: "page Admin")
        for id in ["admin heading", "admin intro", "admin email", "admin password", "admin sign in"] {
            let frame = element(id, in: "page Admin").frame
            XCTAssertEqual(frame.midX, 960, accuracy: 4, "\(id) centred: \(frame)")
            XCTAssertGreaterThan(frame.minY, admin.maxY, "\(id) under the title: \(frame)")
            XCTAssertLessThanOrEqual(frame.maxY, 1020, "\(id) on the screen: \(frame)")
        }
        assertIndigoButton("admin sign in")
        shoot("tvos-admin-form")
    }

    func testAboutCarriesTmdbsNoticeAndThePrivacyText() {
        launchSignedOut()
        signIn()
        openSettings()
        press(.down)
        waitForFocus("about")
        press(.select)
        XCTAssertTrue(element("page About").waitForExistence(timeout: 10), "About")
        // Android's words, TMDB's word for word (docs/attribution.md).
        XCTAssertEqual(element("unofficial").label, "Hither is an independent client for Seerr servers, not made by, affiliated with or endorsed by the Seerr project.")
        XCTAssertEqual(element("tmdb notice").label, "This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.")
        XCTAssertTrue(element("tmdb logo").exists, "TMDB's logo beside its notice")
        XCTAssertTrue(element("about wordmark").exists, "Hither's mark")
        XCTAssertTrue(element("version").label.hasPrefix("Version"), element("version").label)
        XCTAssertTrue(element("version").label.contains("0."), "the version's number: \(element("version").label)")
        XCTAssertTrue(element("credits").exists, "the credits")
        // TMDB's logo less prominent than Hither's own mark (TMDB's terms).
        XCTAssertLessThan(element("tmdb logo").frame.height, element("about wordmark").frame.height, "TMDB's logo under Hither's mark")
        waitForFocus("privacy", "focus on Privacy, the first row that opens something")
        shoot("tvos-about")

        // The privacy text, docs/privacy-tvos.md as bundled: one text view that scrolls with the remote, as tvOS's
        // Settings › Legal reads (the review's F20: each paragraph and bullet was a stop of its own).
        press(.select)
        XCTAssertTrue(element("privacy text").waitForExistence(timeout: 10), "the privacy text")
        let body = element("privacy body")
        XCTAssertTrue(body.waitForExistence(timeout: 10), "the privacy text in one view")
        let text = body.label
        for words in ["Hither for Apple TV is a client for a Seerr server", "What the app stores, on the device only", "The server's address", "Keychain"] {
            XCTAssertTrue(text.contains(words), "\(words) in the text: \(text.prefix(200))")
        }
        XCTAssertLessThan(text.range(of: "What the app stores")!.lowerBound, text.range(of: "The server's address")!.lowerBound, "in the file's order")
        XCTAssertFalse(text.contains("Android Keystore"), "not Android's")
        XCTAssertFalse(element("privacy block 1").exists, "no stop per paragraph")
        shoot("tvos-privacy")
        assertReadsWithTheRemote("privacy body")

        // Menu back through About to Settings, on the row it came from.
        press(.menu)
        waitForFocus("privacy")
        press(.menu)
        waitForFocus("about")
    }

    func testDiagnosticsListsWhatHitherRecoveredFrom() {
        // Popular Series answers 500: Discover shows the row's failure and Hither records it.
        Fake.set("/fake/rows?fail=tv")
        launchSignedOut()
        signIn()
        waitForDiscover()
        XCTAssertTrue(element("row POPULAR_SERIES").waitForExistence(timeout: 20))
        openSettings()
        press(.down)
        waitForFocus("about")
        press(.select)
        pressUntilFocus(.down, "diagnostics")
        press(.select)
        XCTAssertTrue(element("page Diagnostics").waitForExistence(timeout: 10), "Diagnostics")
        XCTAssertTrue(element("failure 0").waitForExistence(timeout: 5), "a failure listed")
        let tv = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'failure ' AND label CONTAINS 'HTTP 500' AND label CONTAINS 'GET /discover/tv'")).firstMatch
        XCTAssertTrue(tv.exists, "Popular Series' 500: what and the call, never the address")
        XCTAssertFalse(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", Fake.address)).firstMatch.exists, "never the server's address")
        waitForFocus("failure 0", "focus on the newest")
        assertSettingsLayout("page Diagnostics", rows: ["failure 0"], mark: "settings mark", under: element("page title", in: "page Diagnostics").frame)
        shoot("tvos-diagnostics")
    }
}

extension HitherUITestCase {
    /// [page] is laid out as tvOS's Settings app lays out its own (`SettingsScaffold`, the review's X4 and F22; Apple's
    /// Settings on the same simulator: the list x = 1060-1840, the tile centred in the left): each of [rows] in the list
    /// at the right, to the safe edge, under [under] (a pushed page's title) when given; the picture [mark] centred in
    /// the 900 points left of the list. Rows measured at rest (a focused row grows).
    func assertSettingsLayout(_ page: String, rows: [String], mark: String, under: CGRect? = nil, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element(page).waitForExistence(timeout: 10), page, file: file, line: line)
        for id in rows {
            let row = element(id, in: page)
            XCTAssertTrue(row.waitForExistence(timeout: 10), "\(page): \(id)", file: file, line: line)
            guard !hasFocus(id) else { continue }
            // The row is the form's cell round the control (a button's own frame is its words').
            let cell = app.cells.containing(.any, identifier: id).firstMatch
            let frame = cell.exists ? cell.frame : row.frame
            XCTAssertEqual(frame.minX, 1060, accuracy: 24, "\(id) in the list at the right: \(frame)", file: file, line: line)
            XCTAssertEqual(frame.maxX, 1840, accuracy: 24, "\(id) to the safe edge: \(frame)", file: file, line: line)
            if let under { XCTAssertGreaterThan(frame.minY, under.maxY, "\(id) under the title \(under): \(frame)", file: file, line: line) }
        }
        let picture = element(mark, in: page)
        XCTAssertTrue(picture.exists, "\(page): the picture \(mark)", file: file, line: line)
        XCTAssertEqual(picture.frame.midX, 530, accuracy: 24, "\(mark) centred in the left: \(picture.frame)", file: file, line: line)
    }
}
