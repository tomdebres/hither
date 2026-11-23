import XCTest

/// Discover's rows on a new server, top to bottom: Recent Requests (where focus arrives), then these.
enum Row: Int {
    case trending = 1, popularMovies, upcomingMovies, popularSeries, upcomingSeries

    /// The row's name on Discover (core's `DiscoverRow.name`): its element is `row <name>`.
    var name: String {
        switch self {
        case .trending: "TRENDING"
        case .popularMovies: "POPULAR_MOVIES"
        case .upcomingMovies: "UPCOMING_MOVIES"
        case .popularSeries: "POPULAR_SERIES"
        case .upcomingSeries: "UPCOMING_SERIES"
        }
    }
}

/// The made-up titles fake-seerr serves (its fixtures): where each sits on Discover.
enum Fixture {
    /// The recorded film, in the library, in fake-seerr's one collection: Popular Movies' first card.
    static let film = (row: Row.popularMovies, index: 0, key: "card movie:1423191", title: "Resident Evil")
    /// A film nobody has asked for: Popular Movies' fifth card.
    static let coco = (row: Row.popularMovies, index: 4, key: "card movie:354912", title: "Coco")
    /// The recorded series, partly in the library: Popular Series' first card.
    static let series = (row: Row.popularSeries, index: 0, key: "card tv:1877", title: "Phineas and Ferb")
    /// A series nobody has asked for (the recorded series' seasons): Popular Series' seventh card.
    static let freshSeries = (row: Row.popularSeries, index: 6, key: "card tv:1421", title: "Modern Family")
}

extension HitherUITestCase {
    /// From Discover's arrival, Down to [row] and Right to the card at [index], waiting for each.
    func focusCard(_ key: String, row: Row, index: Int, file: StaticString = #filePath, line: UInt = #line) {
        waitForDiscover(file: file, line: line)
        XCTAssertTrue(element(key).waitForExistence(timeout: 30), "\(key) on Discover", file: file, line: line)
        // Arrival: focus on the top row's first card. Then Down until the row has focus (an account's rights can add
        // rows above it, as Recently Added for an approver), and Right to the card.
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline, focusedId == "(none)" { Thread.sleep(forTimeInterval: 0.3) }
        let rowId = "row \(row.name)"
        let inRow = { self.element(rowId).descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists }
        for _ in 0..<8 where !inRow() { press(.down) }
        XCTAssertTrue(inRow(), "focus in \(rowId), it is on \(focusedId)", file: file, line: line)
        for _ in 0..<(index + 3) where !hasFocus(key) { press(.right) }
        waitForFocus(key, "the card to open", file: file, line: line)
    }

    /// Signs in and opens a title's page from its card on Discover.
    func openTitle(_ key: String, row: Row, index: Int, title: String, file: StaticString = #filePath, line: UInt = #line) {
        focusCard(key, row: row, index: index, file: file, line: line)
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10), "the title page", file: file, line: line)
        waitForLabel("title name", contains: title, file: file, line: line)
    }

    /// Waits for the element [id] to exist with words containing [words].
    func waitForLabel(_ id: String, contains words: String, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        let target = element(id)
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if target.exists, target.label.contains(words) { return }
            Thread.sleep(forTimeInterval: 0.3)
        }
        XCTFail("\(id) to read \"\(words)\"; it reads \"\(target.exists ? target.label : "(gone)")\"", file: file, line: line)
    }

    /// Presses [button] until [id] has focus, at most [times] presses.
    func pressUntilFocus(_ button: XCUIRemote.Button, _ id: String, times: Int = 8, file: StaticString = #filePath, line: UInt = #line) {
        for _ in 0..<times where !hasFocus(id) { press(button) }
        waitForFocus(id, timeout: 3, file: file, line: line)
    }
}

/// The title page: opening it from a card, the Apple TV app's product page, its actions and facts, a series' seasons,
/// and Menu back to the card.
final class TitleUITests: HitherUITestCase {
    /// Select on a TitleCard opens its page; Menu returns to the very card (Android's Back, DECISIONS 129).
    func testSelectOpensTheTitlePageAndMenuReturnsToTheCard() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.coco.key, row: Fixture.coco.row, index: Fixture.coco.index, title: Fixture.coco.title)
        // The page's first action has focus on arrival: Request, as Android's.
        waitForFocus("action Request", "focus on the first action")
        press(.menu)
        XCTAssertTrue(element("title page").waitForNonExistence(timeout: 10), "Menu leaves the page")
        waitForFocus(Fixture.coco.key, "Menu returns to the card the page came from")
    }

    /// Recent Requests' cards open their title's page too.
    func testRecentRequestOpensItsTitle() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline, !focusedId.hasPrefix("recent ") { Thread.sleep(forTimeInterval: 0.3) }
        XCTAssertTrue(focusedId.hasPrefix("recent "), "focus on Recent Requests, it is on \(focusedId)")
        let card = focusedId
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10), "the request's title page")
        press(.menu)
        waitForFocus(card, "Menu returns to the request card")
    }

    /// A film's page as the Apple TV app's product page (Tom's native look, DECISIONS 193): the backdrop across the
    /// screen's width, the title, one line of what it is, its status as a glyph with its word, the actions, then the
    /// overview under them; below, Cast & Crew, the collection, Recommendations and Similar, the keywords as one shelf,
    /// then Information at the foot, read at rest: focus never stops on it, and the last shelf brings it up whole.
    func testFilmProductPage() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.film.key, row: Fixture.film.row, index: Fixture.film.index, title: Fixture.film.title)
        waitForLabel("title attributes", contains: "2026 · 1 hr 34 min · Horror, Science Fiction, Adventure")
        let status = element("status Available")
        XCTAssertTrue(status.waitForExistence(timeout: 10), "the film's status")
        let glyph = status.descendants(matching: .image).matching(identifier: "badge Available").firstMatch
        XCTAssertTrue(glyph.exists, "the status as a glyph")
        XCTAssertEqual(glyph.label, "Available", "the glyph carries its word for VoiceOver")
        XCTAssertTrue(status.staticTexts["Available"].exists, "and the word beside it")
        for id in ["overview", "cast", "collection", "facts", "ratings", "keywords"] {
            XCTAssertTrue(element(id).waitForExistence(timeout: 10), id)
        }
        XCTAssertTrue(element("related RECOMMENDATIONS").waitForExistence(timeout: 20), "Recommendations")
        XCTAssertTrue(element("related SIMILAR").waitForExistence(timeout: 20), "Similar Titles")
        XCTAssertFalse(element("seasons").exists, "no seasons on a film")
        Thread.sleep(forTimeInterval: 1)
        shoot("title-film")
        let name = element("title name").frame
        let actions = element("title actions").frame
        let overview = element("overview").frame
        // The first screen: the title over the actions over the overview, all on it.
        XCTAssertLessThan(name.maxY, actions.minY, "the title above the actions")
        XCTAssertLessThan(actions.maxY, overview.minY + 16, "the overview under the actions")
        XCTAssertLessThanOrEqual(overview.maxY, 1080, "the overview on the first screen")
        // Then the sections in the TV app's order, Information last, under the keywords.
        let order = ["cast", "collection", "related RECOMMENDATIONS", "related SIMILAR", "keywords", "facts"].map { element($0).frame.minY }
        XCTAssertEqual(order, order.sorted(), "Cast & Crew, the collection, Recommendations, Similar, Keywords, Information: \(order)")
        XCTAssertGreaterThan(order[0], overview.maxY, "the sections under the first screen")
        XCTAssertLessThan(element("ratings").frame.minY, element("fact Status").frame.minY, "the ratings at Information's top")
        XCTAssertLessThan(element("keywords").frame.maxY, element("fact Status").frame.minY, "the keywords above Information")
        // "Keywords" is a section's header as "Information" is: the same style, on the same line from the left (the
        // review's F7: a grey Caption label, 24 points in).
        let information = app.descendants(matching: .any).matching(NSPredicate(format: "identifier == 'row title' AND label == 'Information'")).firstMatch
        let keywordsHeader = element("row title", in: "keywords")
        XCTAssertTrue(keywordsHeader.exists, "Keywords under a section's header")
        XCTAssertEqual(keywordsHeader.label, "Keywords")
        XCTAssertEqual(keywordsHeader.frame.minX, information.frame.minX, accuracy: 1, "Keywords on Information's line: \(keywordsHeader.frame), \(information.frame)")
        XCTAssertEqual(keywordsHeader.frame.height, information.frame.height, accuracy: 2, "Keywords in Information's style")
        // The facts in columns: Status and Release Date side by side.
        XCTAssertEqual(element("fact Status").frame.minY, element("fact Release Date").frame.minY, accuracy: 2, "facts in columns")
        XCTAssertLessThan(element("fact Status").frame.maxX, element("fact Release Date").frame.minX, "facts in columns")
        // The keywords as one shelf (the review's S26): the system's buttons on one line, in a row that scrolls sideways,
        // not a cloud of tags wrapping across the page.
        assertKeywordShelf(["outbreak", "survival", "decapitation", "zombie", "based on video game"])

        // Down from the actions goes through the sections to the keywords, never stopping on Information; the last
        // shelf brings the page's foot up, Information whole under it.
        assertDownNeverFocuses("facts", until: "keyword outbreak")
        press(.down)
        waitForFocus("keyword outbreak", "nothing under the keywords takes focus")
        assertWholeOnScreen("facts", "Information read whole from the last shelf")
        Thread.sleep(forTimeInterval: 1)
        shoot("title-film-facts")
        // Along the shelf to its last keyword: the row scrolls sideways, the page stays.
        let foot = element("facts").frame.minY
        pressUntilFocus(.right, "keyword based on video game", times: 6)
        XCTAssertEqual(element("facts").frame.minY, foot, accuracy: 2, "the page still while focus moves along the keywords")
        shoot("title-film-keywords-last")
    }

    /// The actions are system buttons in Seerr's order, the 4K copy's by the account's rights read live (DECISIONS
    /// 193): Request then Request in 4K for a film nobody asked for; Play on Plex, Request More and Watch Trailer for the
    /// recorded series (4K series off on the server); Request alone once the 4K right goes.
    func testActionsInOrderWith4KByRights() {
        app.launchEnvironment["HITHER_APPS"] = "youtube,plex"
        launchSignedOut()
        signIn()
        openTitle(Fixture.coco.key, row: Fixture.coco.row, index: Fixture.coco.index, title: Fixture.coco.title)
        waitForActions(["action Request", "action Request in 4K"], "a film nobody asked for")
        waitForFocus("action Request", "the first action has focus")
        press(.menu)
        waitForFocus(Fixture.coco.key)
        Fake.set("/fake/account?fourk=0")
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10))
        waitForActions(["action Request"], "no 4K without the right")
        press(.menu)
        waitForFocus(Fixture.coco.key)
        // Popular Series is three rows down: Down until its row, Right to the recorded series.
        for _ in 0..<4 where !element("row \(Row.popularSeries.name)").descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists {
            press(.down)
        }
        pressUntilFocus(.left, Fixture.series.key, times: 10)
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10))
        waitForLabel("title name", contains: Fixture.series.title)
        waitForActions(["action Play on Plex", "action Request More", "action watch trailer"], "the recorded series")
        Thread.sleep(forTimeInterval: 1)
        shoot("title-series-actions")
    }

    /// Every fact Android's page shows is on the product page: the year, runtime and genres, the status, the tagline
    /// and the whole overview (Select opens it over the page in the text view that scrolls with the remote, the review's
    /// F28; Menu closes it), the crew with their jobs, the cast with their parts,
    /// Seerr's facts, the ratings with their marks, the keywords and the collection.
    func testEveryFactPresent() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.film.key, row: Fixture.film.row, index: Fixture.film.index, title: Fixture.film.title)
        waitForLabel("title attributes", contains: "2026 · 1 hr 34 min · Horror, Science Fiction, Adventure")
        XCTAssertTrue(element("status Available").exists, "the status")
        waitForLabel("tagline", contains: "A new era of evil.")
        let overview = "Medical courier Bryan unwittingly finds himself fighting for survival as one fateful, horrifying night collapses around him in chaos."
        waitForLabel("overview text", contains: overview)
        // The crew, as Seerr lists it, with their jobs; the cast with their parts.
        for (index, line) in [("Director", "Zach Cregger"), ("Producer", "Robert Kulzer"), ("Producer", "Roy Lee"), ("Producer", "Miri Yoon")].enumerated() {
            let person = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", "person crew:\(index):")).firstMatch
            XCTAssertTrue(person.waitForExistence(timeout: 10), "crew \(index)")
            XCTAssertEqual(person.label, line.1, "crew \(index)")
            XCTAssertEqual(person.value as? String, line.0, "crew \(index)'s job")
        }
        let cast = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'person cast:'"))
        XCTAssertEqual(cast.count, 5, "the five of the cast")
        XCTAssertFalse((cast.firstMatch.value as? String ?? "").isEmpty, "a part with the name: \(cast.firstMatch.label)")
        // Seerr's facts, written as Seerr's web writes them.
        let facts = [
            ("Status", "Released"), ("Release Date", "2026"), ("Revenue", "$400,290,973"), ("Budget", "$75,000,000"),
            ("Original Language", "English"), ("Production Countries", "Germany"), ("Studios", "Constantin Film"),
        ]
        for (name, value) in facts {
            waitForLabel("fact \(name)", contains: value)
        }
        // Money in whole dollars (the review's F29: "$400,290,973.00").
        for name in ["Revenue", "Budget"] {
            XCTAssertFalse(element("fact \(name)").label.contains(".00"), "\(name) without pence: \(element("fact \(name)").label)")
        }
        // The ratings, each with its mark.
        let ratings = element("ratings")
        XCTAssertTrue(ratings.waitForExistence(timeout: 20))
        for kind in ["RT_FRESH", "RT_UPRIGHT", "IMDB", "TMDB"] {
            XCTAssertTrue(ratings.descendants(matching: .any).matching(identifier: "rating \(kind)").firstMatch.waitForExistence(timeout: 10), "rating \(kind)")
        }
        XCTAssertEqual(ratings.images.count, 4, "a mark for each")
        for keyword in ["outbreak", "survival", "decapitation", "zombie", "based on video game"] {
            XCTAssertTrue(element("keyword \(keyword)").exists, "keyword \(keyword)")
        }
        waitForLabel("collection name", contains: "Example Collection")

        // The whole overview: Select on it, the text view over the page with focus on it, Menu back to it.
        pressUntilFocus(.down, "overview", times: 3)
        press(.select)
        XCTAssertTrue(element("overview screen").waitForExistence(timeout: 10), "the whole overview")
        let whole = element("overview whole")
        XCTAssertTrue(whole.waitForExistence(timeout: 10), "the overview in a reading view")
        XCTAssertTrue(whole.label.contains(overview), "every word: \(whole.label)")
        XCTAssertTrue(whole.label.hasPrefix("A new era of evil."), "the tagline first")
        XCTAssertEqual(element("page title", in: "overview screen").label, Fixture.film.title, "the title, as a page's")
        waitForReadingFocus("overview whole")
        shoot("title-overview")
        press(.menu)
        XCTAssertTrue(element("overview screen").waitForNonExistence(timeout: 10))
        waitForFocus("overview", "Menu back to the overview")
    }

    /// Waits for the title page's actions to be [expected], left to right.
    private func waitForActions(_ expected: [String], _ message: String, file: StaticString = #filePath, line: UInt = #line) {
        var found: [String] = []
        let deadline = Date().addingTimeInterval(20)
        repeat {
            if let row = try? element("title actions").snapshot() {
                var actions: [(CGFloat, String)] = []
                func walk(_ node: XCUIElementSnapshot) {
                    if node.identifier.hasPrefix("action ") { actions.append((node.frame.minX, node.identifier)) }
                    node.children.forEach(walk)
                }
                walk(row)
                found = actions.sorted { $0.0 < $1.0 }.map(\.1)
                if found == expected { return }
            }
            Thread.sleep(forTimeInterval: 0.5)
        } while Date() < deadline
        XCTAssertEqual(found, expected, message, file: file, line: line)
    }

    /// A series' page shows the seasons that can still be asked for, newest first, as a shelf of the system's cards
    /// (polish 2's item 13, DECISIONS 193); a season already in the library takes no focus and is read in Information
    /// with its status (Tom's D2).
    func testSeriesPageListsItsSeasons() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.series.key, row: Fixture.series.row, index: Fixture.series.index, title: Fixture.series.title)
        XCTAssertTrue(element("seasons").waitForExistence(timeout: 10), "the season list")
        XCTAssertFalse(element("collection").exists, "no collection on a series")
        // The recorded record: season 1 in the library, the rest open. The shelf holds the open ones, newest first.
        XCTAssertEqual(shelfSeasons(), ["series season 6", "series season 5", "series season 4", "series season 3", "series season 2"],
                       "the seasons to ask for, newest first, left to right, no specials, not season 1")
        XCTAssertFalse(element("series season 1").exists, "season 1, in the library, is not on the shelf")
        // Season 1 in Information, with Seerr's status as a glyph and its word, at rest.
        let inLibrary = element("fact Season 1")
        XCTAssertTrue(inLibrary.waitForExistence(timeout: 10), "season 1 among Information's facts")
        XCTAssertTrue(element("facts").descendants(matching: .any).matching(identifier: "fact Season 1").firstMatch.exists, "inside Information")
        XCTAssertTrue(inLibrary.descendants(matching: .any).matching(identifier: "status Available").firstMatch.exists, "season 1 Available")
        XCTAssertEqual(inLibrary.descendants(matching: .button).count, 0, "nothing in it to press")
        // Every card on the shelf is a button: Select asks for that season.
        for number in 2...6 {
            let card = element("series season \(number)")
            XCTAssertTrue(card.elementType == .button || card.descendants(matching: .button).firstMatch.exists, "season \(number) is a button")
        }
        shoot("title-series")
        pressUntilFocus(.down, "series season 6", times: 10)
        Thread.sleep(forTimeInterval: 1)
        shoot("title-series-seasons")
        // The series' last shelf (its keywords) brings Information up whole, season 1 in it; nothing there takes focus.
        assertDownNeverFocuses("facts", until: "keyword secret agent")
        assertWholeOnScreen("facts", "Information read whole from the last shelf")
        Thread.sleep(forTimeInterval: 1)
        shoot("title-series-information")
    }

    /// Select on a season's card opens the request sheet (the system's form, the review's X6) for that season, chosen,
    /// with the primary focused (Tom's D2): Menu brings focus back to the card; Select twice asks for that season alone,
    /// which then leaves the shelf for Information (no toast: Tom's D4), and focus goes on along the shelf.
    func testASeasonOpensTheRequestSheetForIt() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.series.key, row: Fixture.series.row, index: Fixture.series.index, title: Fixture.series.title)
        pressUntilFocus(.down, "series season 6", times: 10)
        pressUntilFocus(.right, "series season 4", times: 3)
        press(.select)
        XCTAssertTrue(element("request modal").waitForExistence(timeout: 10), "the request sheet")
        assertSheetTitle("Request Series")
        waitForFocus("primary", "the primary has focus: a second Select asks for the season")
        XCTAssertEqual(element("season 4").value as? String, "on", "season 4 chosen")
        for number in [2, 3, 5, 6] {
            XCTAssertEqual(element("season \(number)").value as? String, "off", "season \(number) not chosen")
        }
        XCTAssertEqual(element("season 1").value as? String, "on", "season 1 in the library, on and dimmed")
        waitForLabel("primary", contains: "Request 1 Season")
        // The chosen season shows in the sheet's form, above the primary.
        let sheet = sheetFrame()
        let row = element("season 4").frame
        let primary = element("primary").frame
        XCTAssertTrue(sheet.insetBy(dx: 0, dy: -4).contains(row), "season 4's row in the sheet's view: \(row) in \(sheet)")
        XCTAssertLessThan(row.maxY, primary.minY + 4, "above the primary: \(row), \(primary)")
        shoot("request-season-from-card")
        press(.menu)
        XCTAssertTrue(element("request modal").waitForNonExistence(timeout: 10), "Menu closes the sheet")
        waitForFocus("series season 4", "Menu back to the season's card")

        press(.select)
        XCTAssertTrue(element("request modal").waitForExistence(timeout: 10))
        waitForFocus("primary")
        press(.select)
        XCTAssertTrue(element("request modal").waitForNonExistence(timeout: 10), "the sheet goes with the answer")
        assertNoToast("a season requested")
        // Asked for, season 4 leaves the shelf for Information; focus goes on to the next season along.
        XCTAssertTrue(element("series season 4").waitForNonExistence(timeout: 10), "season 4 off the shelf")
        XCTAssertEqual(shelfSeasons(), ["series season 6", "series season 5", "series season 3", "series season 2"])
        let asked = element("fact Season 4")
        XCTAssertTrue(asked.waitForExistence(timeout: 10), "season 4 in Information")
        XCTAssertTrue(asked.descendants(matching: .any).matching(identifier: "status Requested").firstMatch.exists, "season 4 Requested")
        waitForFocus("series season 3", "focus on along the shelf")
        shoot("request-season-from-card-after")
    }

    /// The season cards on the shelf, left to right.
    private func shelfSeasons() -> [String] {
        guard let page = try? element("title page").snapshot() else { XCTFail("no snapshot"); return [] }
        var seasons: [(CGFloat, String)] = []
        func walk(_ node: XCUIElementSnapshot) {
            if node.identifier.hasPrefix("series season ") { seasons.append((node.frame.minX, node.identifier)) }
            node.children.forEach(walk)
        }
        walk(page)
        return seasons.sorted { $0.0 < $1.0 }.map(\.1)
    }
}

extension HitherUITestCase {
    /// The keywords [names] as one shelf (the review's S26): every one there from the start (built whole), on one line in
    /// the server's order, inside a row that scrolls sideways, under a section's header.
    func assertKeywordShelf(_ names: [String], file: StaticString = #filePath, line: UInt = #line) {
        let shelf = element("keywords")
        XCTAssertTrue(shelf.waitForExistence(timeout: 10), "the keywords", file: file, line: line)
        let tags = names.map { element("keyword \($0)") }
        for (name, tag) in zip(names, tags) { XCTAssertTrue(tag.exists, "keyword \(name)", file: file, line: line) }
        let tops = tags.map(\.frame.minY)
        XCTAssertEqual((tops.max() ?? 0) - (tops.min() ?? 0), 0, accuracy: 2, "the keywords on one line: \(tops)", file: file, line: line)
        let lefts = tags.map(\.frame.minX)
        XCTAssertEqual(lefts, lefts.sorted(), "in the server's order, left to right", file: file, line: line)
        XCTAssertTrue(shelf.scrollViews.firstMatch.exists, "in a row that scrolls sideways, a shelf", file: file, line: line)
        XCTAssertEqual(element("row title", in: "keywords").label, "Keywords", "under a section's header", file: file, line: line)
    }

    /// Presses Down until [target] has focus, failing if focus ever lands on [id] on the way (or [target] never comes).
    func assertDownNeverFocuses(_ id: String, until target: String, times: Int = 14, file: StaticString = #filePath, line: UInt = #line) {
        for _ in 0..<times where !hasFocus(target) {
            press(.down)
            XCTAssertFalse(hasFocus(id), "focus on \(id), which takes none", file: file, line: line)
        }
        waitForFocus(target, timeout: 3, file: file, line: line)
    }

    /// [id] whole on the screen, inside the 60-point margins at its top and foot.
    func assertWholeOnScreen(_ id: String, _ message: String, file: StaticString = #filePath, line: UInt = #line) {
        Thread.sleep(forTimeInterval: 1)
        let frame = element(id).frame
        XCTAssertGreaterThanOrEqual(frame.minY, 60 - 2, "\(message): \(id) at \(frame)", file: file, line: line)
        XCTAssertLessThanOrEqual(frame.maxY, 1080 - 60 + 2, "\(message): \(id) at \(frame)", file: file, line: line)
    }
}
