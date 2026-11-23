import XCTest

/// Discover: the server's rows in its order, Recent Requests and who asked, the lockups and their status glyphs, See More.
final class DiscoverUITests: HitherUITestCase {
    /// The row titles on the page, top to bottom, from one snapshot of the page (an element at a time is slow).
    private var rowTitles: [String] {
        guard let page = try? element("page Discover").snapshot() else { return [] }
        var titles: [(CGFloat, String)] = []
        func walk(_ node: XCUIElementSnapshot) {
            if node.identifier == "row title" { titles.append((node.frame.minY, node.label)) }
            node.children.forEach(walk)
        }
        walk(page)
        return titles.sorted { $0.0 < $1.0 }.map(\.1)
    }

    /// Each row title's left edge, top to bottom, from one snapshot.
    private var rowTitleStarts: [(title: String, x: CGFloat)] {
        guard let page = try? element("page Discover").snapshot() else { return [] }
        var titles: [(CGFloat, String, CGFloat)] = []
        func walk(_ node: XCUIElementSnapshot) {
            if node.identifier == "row title" { titles.append((node.frame.minY, node.label, node.frame.minX)) }
            node.children.forEach(walk)
        }
        walk(page)
        return titles.sorted { $0.0 < $1.0 }.map { ($0.1, $0.2) }
    }

    private func waitForRows(_ expected: [String], file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(30)
        while Date() < deadline, rowTitles != expected { Thread.sleep(forTimeInterval: 0.5) }
        XCTAssertEqual(rowTitles, expected, file: file, line: line)
    }

    /// A new server's Discover settings (Seerr's defaultSliders): Recently Added hidden from an account without
    /// Recent View, Recent Requests first, the built-ins in Seerr's order.
    func testRowsInTheServersOrder() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        waitForRows(["Recent Requests", "Trending", "Popular Movies", "Upcoming Movies", "Popular Series", "Upcoming Series"])
    }

    /// An admin's own order: built-ins moved, Popular Series hidden, a custom slider of every kind under its title,
    /// Recently Added for an account with Recent View (DECISIONS 99, 119).
    func testCustomRowsInTheServersOrder() {
        Fake.set("/fake/discover?preset=custom")
        Fake.set("/fake/account?recent=1")
        launchSignedOut()
        signIn()
        waitForDiscover()
        waitForRows([
            "Trending", "Recent Requests", "Recently Added", "Zombies", "Popular Movies", "Star Wars", "Animated Series",
            "Upcoming Movies", "Constantin Film", "Disney Channel", "Films on Netflix", "Series on Disney Plus", "Horror",
            "Secret Agents", "Upcoming Series",
        ])
    }

    /// A row still loading keeps its place: its placeholders sit in a shelf as its lockups will, and every header stays on
    /// tvOS's left margin (the review's F1: a loading row's seven placeholders were 2118 points wide, the page centred
    /// them, and every header moved 99 points left, cut off, for as long as the server took: the first thing a launch shows).
    func testALoadingRowKeepsItsPlace() {
        // Trending, the second row, on the first screen, takes 15 seconds to answer; the other rows answer at once.
        Fake.set("/fake/rows?slow=trending&delay=15")
        launchSignedOut()
        signIn()
        waitForDiscover()
        let trending = element("row TRENDING")
        XCTAssertTrue(trending.waitForExistence(timeout: 10), "Trending's row")
        let popular = element("row \(Row.popularMovies.name)").descendants(matching: .any).matching(identifier: Fixture.film.key).firstMatch
        XCTAssertTrue(popular.waitForExistence(timeout: 10), "Popular Movies' lockups in")
        Thread.sleep(forTimeInterval: 1)
        let starts = rowTitleStarts
        let cards = trending.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'card '"))
        XCTAssertEqual(cards.count, 0, "Trending still loading as the headers are read")
        XCTAssertGreaterThanOrEqual(starts.count, 5, "the rows' headers: \(starts)")
        for (title, x) in starts {
            XCTAssertEqual(x, Self.margin, accuracy: 1, "\(title)'s header on the left margin while a row loads: \(starts)")
        }
        // The placeholders where the lockups will be, 40 points under their header: read down the first one's middle
        // for the step from the page's black to the placeholder's grey (a shelf centres what is shorter than its row).
        let header = element("row title", in: "row TRENDING").frame
        let top = largestStepDown(x: header.minX + 125, from: header.maxY + 4, to: header.maxY + 120)
        XCTAssertEqual(top.at - header.maxY, 40, accuracy: 5, "the first placeholder's top under the header at \(header): y = \(top.at), a step of \(top.step)")
        shoot("discover-loading")
        // And the row comes in place.
        XCTAssertTrue(cards.firstMatch.waitForExistence(timeout: 30), "Trending's lockups after the wait")
        XCTAssertEqual(rowTitleStarts.map(\.x).max() ?? 0, Self.margin, accuracy: 1, "the headers where they were")
    }

    /// tvOS's left margin.
    private static let margin: CGFloat = 80

    /// Recent Requests names who asked for an account that may see others' requests (DECISIONS 118), and not otherwise.
    func testRecentRequestsNameWhoAsked() {
        Fake.set("/fake/account?view=1")
        Fake.set("/fake/requests?household=1")
        launchSignedOut()
        signIn()
        waitForDiscover()
        let requester = app.descendants(matching: .any).matching(identifier: "recent requester").firstMatch
        XCTAssertTrue(requester.waitForExistence(timeout: 20), "who asked, on a request card")
        XCTAssertFalse(requester.label.isEmpty)
        shoot("discover-requesters")
    }

    func testRecentRequestsUnnamedWithoutTheRight() {
        Fake.set("/fake/requests?household=1")
        launchSignedOut()
        signIn()
        waitForDiscover()
        let title = app.descendants(matching: .any).matching(identifier: "recent title").firstMatch
        XCTAssertTrue(title.waitForExistence(timeout: 20), "a request card with its title")
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "recent requester").firstMatch.exists)
    }

    /// Every title is a tvOS poster lockup (Tom's native look, DECISIONS 191): its title under its picture, at rest and
    /// with focus (the picture lifts, the words step down and stay under it), the year under the title, and the kind of
    /// title only where the row mixes films and series. No words over the poster, no MOVIE or SERIES chip, no overview
    /// panel: VoiceOver reads the overview as the lockup's hint instead.
    func testLockupTitleUnderItsImage() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        // Trending mixes films and series; its first card is a series.
        let first = "card tv:1489"
        let card = element(first)
        XCTAssertTrue(card.waitForExistence(timeout: 20), "Trending's first card: Jimmy Kimmel Live!")
        assertTitleUnderImage(card, title: "Jimmy Kimmel Live!", subtitle: "2003 · Series", "at rest")
        press(.down)
        waitForFocus(first)
        Thread.sleep(forTimeInterval: 1)
        assertTitleUnderImage(card, title: "Jimmy Kimmel Live!", subtitle: "2003 · Series", "with focus")
        // The row's header clears its lockups by 40 points, room for a lifted poster (the review's F31: at 24 a focused
        // poster came within 11 points of "Trending").
        let header = element("row title", in: "row TRENDING")
        let picture = card.descendants(matching: .any).matching(identifier: "card image").firstMatch
        XCTAssertGreaterThanOrEqual(picture.frame.minY - header.frame.maxY, 39, "the header over the lifted poster: \(header.frame) over \(picture.frame)")
        shoot("discover-lockup-focused")
        XCTAssertFalse(card.descendants(matching: .staticText).matching(identifier: "card overview").firstMatch.exists, "no overview panel")
        for chip in ["MOVIE", "SERIES"] {
            XCTAssertFalse(card.staticTexts[chip].exists, "no \(chip) chip")
        }
        // Popular Movies is films only: the year alone.
        let film = element(Fixture.film.key)
        let inPopular = element("row \(Row.popularMovies.name)").descendants(matching: .any).matching(identifier: Fixture.film.key).firstMatch
        XCTAssertTrue(inPopular.waitForExistence(timeout: 20), "the film on Popular Movies")
        XCTAssertTrue(film.exists)
        assertTitleUnderImage(inPopular, title: Fixture.film.title, subtitle: "2026", "a row of films only")
    }

    /// A title's status on its lockup is a glyph in Seerr's colour, an image VoiceOver reads by its word, not a pill of
    /// words (DECISIONS 192).
    func testStatusGlyphCarriesItsWord() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        let card = element("row \(Row.popularMovies.name)").descendants(matching: .any).matching(identifier: Fixture.film.key).firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20), "the recorded film on Popular Movies")
        let glyph = card.descendants(matching: .any).matching(identifier: "badge Available").firstMatch
        XCTAssertTrue(glyph.waitForExistence(timeout: 10), "the film's status on its lockup")
        XCTAssertEqual(glyph.elementType, .image, "a glyph, not a pill")
        XCTAssertEqual(glyph.label, "Available", "VoiceOver reads its word")
        XCTAssertFalse(card.staticTexts["Available"].exists, "no word drawn on the poster")
        // On the poster, lifting with it: the picture's element holds it. (Inside the system's highlight its frame reads
        // as the whole picture's, so its corner and size are the screenshots' to show.)
        let image = card.descendants(matching: .any).matching(identifier: "card image").firstMatch
        XCTAssertTrue(image.descendants(matching: .image).matching(identifier: "badge Available").firstMatch.exists, "the glyph on the poster")
    }

    /// A picture fades in over its grey as it comes (the review's F36: pictures popped in). Slowed twenty times, a
    /// poster read soon after its row comes is part way from the grey to its own colours; read again later it has them.
    func testPicturesFadeIn() {
        // Trending (on the first screen) comes 6 seconds after the rest, long after every press: its pictures arrive on
        // their own.
        launchSignedOut()
        signIn()
        waitForDiscover()
        app.terminate()
        Fake.set("/fake/rows?slow=trending&delay=6")
        launchAsLeft(slowMotion: 20)
        waitForDiscover()
        let card = element("card tv:1489")
        XCTAssertTrue(card.waitForExistence(timeout: 30), "Trending's first card")
        Thread.sleep(forTimeInterval: 1)
        let early = XCUIScreen.main.screenshot()
        shoot("picture-fading-in")
        Thread.sleep(forTimeInterval: 10)
        let picture = card.descendants(matching: .any).matching(identifier: "card image").firstMatch.frame
        let spot = CGRect(x: picture.midX - 10, y: picture.midY - 10, width: 20, height: 20)
        guard let before = colours(in: spot, of: early), let after = colours(in: spot) else {
            return XCTFail("no screenshot of \(spot) (the picture at \(picture))")
        }
        let change = before.difference(after, 10, 10)
        XCTAssertGreaterThan(change, 8, "part way in at first: \(before.rgb(10, 10)), then \(after.rgb(10, 10))")
    }

    /// [card]'s title and subtitle sit under its picture, inside its width, the title first.
    private func assertTitleUnderImage(_ card: XCUIElement, title: String, subtitle: String, _ when: String, file: StaticString = #filePath, line: UInt = #line) {
        let image = card.descendants(matching: .any).matching(identifier: "card image").firstMatch
        let words = card.descendants(matching: .staticText).matching(identifier: "card title").firstMatch
        let under = card.descendants(matching: .staticText).matching(identifier: "card subtitle").firstMatch
        XCTAssertTrue(image.exists && words.exists && under.exists, "picture, title and subtitle \(when)", file: file, line: line)
        XCTAssertEqual(words.label, title, file: file, line: line)
        XCTAssertEqual(under.label, subtitle, file: file, line: line)
        XCTAssertGreaterThanOrEqual(words.frame.minY, image.frame.maxY, "the title under the picture \(when): \(words.frame) vs \(image.frame)", file: file, line: line)
        XCTAssertGreaterThanOrEqual(under.frame.minY, words.frame.maxY - 1, "the subtitle under the title \(when)", file: file, line: line)
        XCTAssertGreaterThanOrEqual(words.frame.minX, image.frame.minX - 1, "inside the picture's width \(when)", file: file, line: line)
        XCTAssertLessThanOrEqual(words.frame.maxX, image.frame.maxX + 1, "inside the picture's width \(when)", file: file, line: line)
    }

    /// See More ends a linked row (Trending: 20 cards, then See More) and opens a plain list of the row's titles.
    func testSeeMoreOpensTheRowsList() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        press(.down)
        waitForFocus("card tv:1489")
        for _ in 0..<24 where !hasFocus("see more TRENDING") { press(.right) }
        waitForFocus("see more TRENDING", "See More after Trending's 20 cards")
        press(.select)
        let page = element("page grid TRENDING")
        XCTAssertTrue(page.waitForExistence(timeout: 10), "Trending's list")
        let cards = page.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'card '"))
        XCTAssertTrue(cards.firstMatch.waitForExistence(timeout: 20))
        XCTAssertGreaterThanOrEqual(cards.count, 8, "the row's titles")
        press(.menu)
        waitForFocus("see more TRENDING", "Menu returns to the See More card")
    }
}
