import XCTest

/// The pages a title page opens, and See More: the person page, the collection page, a keyword's titles, and the
/// Movies page with Seerr's sort and filters. Each card opens a title's page, and Menu comes back to where it was.
final class PagesUITests: HitherUITestCase {
    /// A cast card opens the person's page: the name, the biography as one stop (Select: the whole of it in the text view
    /// that scrolls with the remote, the review's F20), Appearances; a credit opens its title; Menu comes back to the
    /// credit, and again to the cast card.
    func testPersonPage() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.film.key, row: Fixture.film.row, index: Fixture.film.index, title: Fixture.film.title)
        XCTAssertTrue(element("cast").waitForExistence(timeout: 10))
        let firstCast = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'person cast:0:'")).firstMatch
        XCTAssertTrue(firstCast.waitForExistence(timeout: 10), "the first cast card")
        let castId = firstCast.identifier
        let name = firstCast.label
        pressUntilFocus(.down, castId, times: 10)
        Thread.sleep(forTimeInterval: 1)
        shoot("title-film-cast")
        // The system's circular lockup (the review's F25), lifted and at rest: nothing drawn round the circle.
        assertNothingRoundTheCircle(castId, "lifted")
        let secondCast = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'person cast:1:'")).firstMatch
        assertNothingRoundTheCircle(secondCast.identifier, "at rest")
        press(.select)
        XCTAssertTrue(element("person page").waitForExistence(timeout: 10), "the person's page")
        waitForLabel("person name", contains: name)
        // Born and where on one line, the date as every date and the middle dot between (the review's F29: a long date
        // and a bar).
        let born = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Born '")).firstMatch
        XCTAssertTrue(born.waitForExistence(timeout: 10), "when and where they were born")
        let parts = String(born.label.dropFirst("Born ".count)).components(separatedBy: " · ")
        XCTAssertEqual(parts.count, 2, "the date and the place, between a middle dot: \(born.label)")
        XCTAssertEqual(parts.last, "Example City, Nowhere", born.label)
        XCTAssertNotNil(try? Date.FormatStyle(date: .abbreviated, time: .omitted).parse(parts[0]), "the date as every date: \(born.label)")
        waitForFocus("biography", "focus on the biography")
        XCTAssertFalse(element("biography 1").exists, "one stop, not a stop per paragraph")
        XCTAssertTrue(element("credits APPEARANCES").waitForExistence(timeout: 10), "Appearances")
        assertHeaderWhole("on arrival")
        shoot("person")
        // Select: the whole biography over the page, every paragraph, focus on the text; Menu back to the stop.
        press(.select)
        XCTAssertTrue(element("biography screen").waitForExistence(timeout: 10), "the whole biography")
        let whole = element("biography whole")
        XCTAssertTrue(whole.waitForExistence(timeout: 10))
        let first = element("biography text").label
        XCTAssertTrue(whole.label.hasPrefix(String(first.prefix(40))), "the biography whole: \(whole.label)")
        XCTAssertGreaterThanOrEqual(whole.label.components(separatedBy: "\n").count, 2, "every paragraph")
        XCTAssertEqual(element("page title", in: "biography screen").label, name, "the name, as a page's")
        waitForReadingFocus("biography whole")
        shoot("person-biography")
        press(.menu)
        XCTAssertTrue(element("biography screen").waitForNonExistence(timeout: 10))
        waitForFocus("biography", "Menu back to the biography")
        assertHeaderWhole("back from the whole biography")
        press(.down)
        waitForFocus("credits APPEARANCES", "Down from the biography to Appearances")
        let credit = focusedId
        XCTAssertTrue(credit.hasPrefix("card "), "a credit's card has focus, not \(credit)")
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10), "the credit's title page")
        press(.menu)
        waitForFocus(credit, "Menu returns to the credit")
        press(.menu)
        waitForFocus(castId, "and again to the cast card")
    }

    /// The portrait of the person [id] in its circle and nothing round it (the review's F25; M7 part 5's trap: `.borderless`
    /// and the system's highlight each drew a rounded square round the circle). Walking out from the picture's centre,
    /// what is drawn ends as far out on the diagonals up-left and up-right as straight up: a circle, at any lift. A square
    /// reaches about 1.4 times as far on the diagonals. (Up, not down: the lift's shadow falls below.)
    private func assertNothingRoundTheCircle(_ id: String, _ when: String, file: StaticString = #filePath, line: UInt = #line) {
        let image = element(id).descendants(matching: .any).matching(identifier: "card image").firstMatch
        XCTAssertTrue(image.exists, "\(id)'s portrait", file: file, line: line)
        let frame = image.frame
        let reach = frame.width
        let shot = XCUIScreen.main.screenshot()
        // How far out from the centre the drawing goes towards (dx, dy): the first of three ground points in a row. Each
        // point read alone off the one screenshot (a 1-point rect: the rows of a taller one read upside down here).
        func extent(_ dx: CGFloat, _ dy: CGFloat) -> CGFloat {
            var ground = 0
            for step in Int(frame.width * 0.3)..<Int(reach) {
                let point = CGRect(x: (frame.midX + dx * CGFloat(step)).rounded(), y: (frame.midY + dy * CGFloat(step)).rounded(), width: 1, height: 1)
                guard let colour = colours(in: point, of: shot)?.rgb(0, 0) else { break }
                ground = max(colour.0, colour.1, colour.2) <= 16 ? ground + 1 : 0
                if ground == 3 { return CGFloat(step - 2) }
            }
            return reach
        }
        let half = CGFloat(0.5).squareRoot()
        let up = extent(0, -1), left = extent(-half, -half), right = extent(half, -half)
        XCTContext.runActivity(named: "\(when): \(id) reaches \(up) up, \(left) up-left, \(right) up-right from its centre") { _ in }
        XCTAssertGreaterThan(up, frame.width * 0.4, "\(when): \(id)'s portrait drawn (\(up) up from its centre)", file: file, line: line)
        for (name, diagonal) in [("up-left", left), ("up-right", right)] {
            XCTAssertLessThanOrEqual(diagonal, up + 6, "\(when): \(id) reaches \(diagonal) \(name) and \(up) up: a square round the circle (\(frame))", file: file, line: line)
        }
    }

    /// The person page at its top: the header where the page lays it out, 140 points down beside the portrait, whole on the
    /// screen (the review's F5: arrival scrolled the page 118 points, the header up into tvOS's top margin, and one Down
    /// cut it off at the screen's edge).
    private func assertHeaderWhole(_ when: String, file: StaticString = #filePath, line: UInt = #line) {
        Thread.sleep(forTimeInterval: 1)
        let name = element("person name").frame
        XCTAssertGreaterThanOrEqual(name.minY, 140, "the header at the page's top \(when): the name at \(name)", file: file, line: line)
    }

    /// The collection card opens the collection's films; a film opens its page; Menu comes back to the film, then to
    /// the collection card.
    func testCollectionPage() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.film.key, row: Fixture.film.row, index: Fixture.film.index, title: Fixture.film.title)
        // Down from the actions, past the overview and Cast & Crew, to the collection's lockup.
        pressUntilFocus(.down, "collection", times: 6)
        press(.select)
        let page = element("page grid collection 900001")
        XCTAssertTrue(page.waitForExistence(timeout: 10), "the collection's page")
        let title = assertPageTitle("Example Collection", in: "page grid collection 900001")
        let cards = page.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'card movie:'"))
        XCTAssertTrue(cards.firstMatch.waitForExistence(timeout: 20), "the collection's films")
        XCTAssertGreaterThan(cards.firstMatch.frame.minY, title.maxY, "the grid under the title")
        XCTAssertTrue(page.descendants(matching: .any).matching(identifier: Fixture.film.key).firstMatch.exists, "the film itself among them")
        shoot("collection")
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline, !focusedId.hasPrefix("card ") { Thread.sleep(forTimeInterval: 0.3) }
        let film = focusedId
        XCTAssertTrue(film.hasPrefix("card movie:"), "focus on a film, not \(film)")
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10))
        press(.menu)
        waitForFocus(film, "Menu returns to the film")
        press(.menu)
        waitForFocus("collection", "and again to the collection card")
    }

    /// A keyword opens its titles of the page's type, under "<Keyword> Movies": TMDB's lower-case keyword with its first
    /// letter capitalised (the review's S38: "outbreak Movies").
    func testKeywordTitles() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.film.key, row: Fixture.film.row, index: Fixture.film.index, title: Fixture.film.title)
        // The recorded film's first keyword: Down through the shelves lands on it, the keywords' shelf's first, without
        // stopping on Information (it takes no focus).
        let tagId = "keyword outbreak"
        XCTAssertTrue(element(tagId).waitForExistence(timeout: 10))
        let keyword = "outbreak"
        assertDownNeverFocuses("facts", until: tagId)
        press(.select)
        XCTAssertTrue(element("page title").waitForExistence(timeout: 10))
        waitForLabel("page title", contains: "Outbreak Movies")
        assertPageTitle("Outbreak Movies")
        Thread.sleep(forTimeInterval: 1)
        shoot("keyword-heading")
        XCTAssertFalse(element("page title").label.contains(keyword), "no heading starting in lower case")
        press(.menu)
        waitForFocus(tagId, "Menu returns to the tag")
    }

    /// Popular Movies' See More opens Seerr's Movies page (Android's, DECISIONS 102, 112): "Movies", the sort and the
    /// filters over the grid. A new sort reads the grid again and focus returns to its button; a filter set counts on
    /// the Filters button.
    func testSeeMoreOpensTheMoviesPageWithSortAndFilters() {
        launchSignedOut()
        signIn()
        focusCard(Fixture.film.key, row: .popularMovies, index: 0)
        for _ in 0..<24 where !hasFocus("see more POPULAR_MOVIES") { press(.right) }
        waitForFocus("see more POPULAR_MOVIES", "See More after Popular Movies' 20 cards")
        press(.select)
        let page = element("page browse POPULAR_MOVIES")
        XCTAssertTrue(page.waitForExistence(timeout: 10), "the Movies page")
        let title = assertPageTitle("Movies", in: "page browse POPULAR_MOVIES")
        // The sort and the filters in tvOS's words (the review's S35, S36, F38): "Popularity Descending" was a database's.
        XCTAssertEqual(element("sort").label, "Most Popular")
        XCTAssertEqual(element("filters").label, "Filters")
        // Sort and Filters on the title's line, at its right, their words kept.
        XCTAssertEqual(element("sort").frame.midY, title.midY, accuracy: 12, "the sort on the title's line: \(element("sort").frame)")
        XCTAssertEqual(element("filters").frame.maxX, 1840, accuracy: 4, "Filters at the right margin: \(element("filters").frame)")
        XCTAssertGreaterThan(element("sort").frame.width, 250, "the sort's words drawn, not its symbol alone: \(element("sort").frame)")
        let cards = page.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'card '"))
        XCTAssertTrue(cards.firstMatch.waitForExistence(timeout: 20), "the titles")
        let firstBefore = cards.firstMatch.identifier
        shoot("browse-movies")

        // Up from the grid's first row reaches the controls; the sort is the system's menu of Seerr's eight.
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline, !focusedId.hasPrefix("card ") { Thread.sleep(forTimeInterval: 0.3) }
        pressUntilFocus(.up, "sort", times: 3)
        press(.select)
        let pick = element("pick original_title.asc")
        XCTAssertTrue(pick.waitForExistence(timeout: 5), "the sort menu")
        XCTAssertEqual(pick.label, "Title A to Z")
        XCTAssertTrue(element("pick popularity.desc").isSelected, "the current sort ticked")
        // The eight in tvOS's words, each pair's usual way first, the pairs in Seerr's order.
        let picks = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'pick '")).allElementsBoundByIndex
            .sorted { $0.frame.minY < $1.frame.minY }.map(\.label)
        XCTAssertEqual(picks, ["Most Popular", "Least Popular", "Newest", "Oldest", "Highest Rated", "Lowest Rated", "Title A to Z", "Title Z to A"])
        shoot("browse-sort")
        pressUntilFocus(.down, "pick original_title.asc", times: 10)
        press(.select)
        XCTAssertTrue(pick.waitForNonExistence(timeout: 5), "the menu closes")
        waitForFocus("sort", "focus back on the sort")
        waitForLabel("sort", contains: "Title A to Z")
        let after = page.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'card '"))
        let changed = Date().addingTimeInterval(20)
        while Date() < changed, after.firstMatch.identifier == firstBefore { Thread.sleep(forTimeInterval: 0.5) }
        XCTAssertNotEqual(after.firstMatch.identifier, firstBefore, "the grid read again in the new order")

        // The filters, a system sheet: a decade on, Menu applies it, and the button counts it.
        press(.right)
        waitForFocus("filters")
        press(.select)
        XCTAssertTrue(element("filter panel").waitForExistence(timeout: 5))
        waitForFocus("years TWENTY_TWENTIES", "the sheet opens on the first decade")
        press(.down)
        waitForFocus("years TWENTY_TENS")
        press(.select)
        XCTAssertEqual(element("years TWENTY_TENS").value as? String, "on")
        XCTAssertTrue(app.staticTexts["Filters (1)"].waitForExistence(timeout: 5), "the sheet's title counts it, keeping its name")
        XCTAssertTrue(element("clear filters").exists, "the way to clear them, once one is set")
        XCTAssertEqual(element("clear filters").label, "Clear Filters")
        XCTAssertTrue(element("genre 28").waitForExistence(timeout: 10), "the genres, read as the sheet opened")
        shoot("browse-filters")
        press(.menu)
        XCTAssertTrue(element("filter panel").waitForNonExistence(timeout: 5))
        waitForFocus("filters", "focus back on Filters")
        waitForLabel("filters", contains: "Filters (1)")

        press(.menu)
        waitForFocus("see more POPULAR_MOVIES", "Menu returns to the See More card")
    }
}
