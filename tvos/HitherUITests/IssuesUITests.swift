import XCTest

/// Seerr's issues (Android's `IssuesScreen` and `IssueModal`, DECISIONS 106) in the native look: the Issues page and
/// Report an Issue on a title in the library, each only with the rights read live.
final class IssuesUITests: HitherUITestCase {
    /// fake-seerr's one made-up issue: on the recorded series, season 1 episode 2, by the server's owner.
    private let issue = "issue:41"

    func testIssuesByTheRight() {
        launchSignedOut()
        signIn()
        // Without an issue right: no Report an Issue on a title in the library (and no Issues tab, BarUITests).
        let film = Fixture.film
        openTitle(film.key, row: film.row, index: film.index, title: film.title)
        XCTAssertTrue(element("title actions").waitForExistence(timeout: 10))
        XCTAssertFalse(element("action report issue").exists, "no Report an Issue without the right")

        // With the rights, read live on the next start: the Issues page as the Library's split, on Open.
        Fake.set("/fake/account?createissues=1&viewissues=1")
        app.terminate()
        launchAsLeft()
        openTab("Issues", steps: 2, page: "Issues")
        waitForRows("issue:", ["Phineas and Ferb"], "the open issue on the recorded series")
        assertNoHeading("page Issues", "Issues")
        assertUnderTheBar(element("issue filter open"), "the side list's first row, the page's first line")
        assertLibrarySplit(side: "issue filters", lockups: [issue])
        // Seerr's three filters as the side list's rows, in its order, the chosen one ticked: no pills.
        let filters = ["issue filter open", "issue filter resolved", "issue filter all"]
        XCTAssertEqual(filters.map { element($0).label }, ["Open", "Resolved", "All"])
        XCTAssertEqual(filters.map { element($0).value as? String ?? "" }, ["selected", "", ""], "the page opens on Open")
        let tops = filters.map { element($0).frame.minY }
        XCTAssertTrue(tops[0] < tops[1] && tops[1] < tops[2], "one under another, not side by side: \(tops)")
        // The lockup: Open as Seerr's warning, then what and when; the season, episode, who and what they wrote for
        // VoiceOver.
        XCTAssertTrue(inside(issue, "issue status Open").exists, "Open, as Seerr's warning")
        XCTAssertTrue(inside(issue, "card when").label.hasPrefix("Subtitle · "), "the type, then when: \(inside(issue, "card when").label)")
        let says = element(issue).value as? String ?? ""
        for part in ["Season 1", "Episode 2", "Opened by User 1", "The subtitles run two seconds late."] {
            XCTAssertTrue(says.contains(part), "VoiceOver reads \(part): \(says)")
        }

        // Down from the tab to the chosen filter, Right to the issue; Select to its title and Menu back to the lockup.
        press(.down)
        waitForFocus("issue filter open")
        press(.right)
        waitForFocus(issue)
        shoot("tvos-issues")
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10), "the title page")
        waitForLabel("title name", contains: "Phineas and Ferb")
        press(.menu)
        waitForFocus(issue, "Menu comes back to the lockup")

        // Left lands on the chosen filter; Down to Resolved shows focus and the tick apart; Select chooses it.
        press(.left)
        waitForFocus("issue filter open", "Left from the lockups to the chosen filter")
        press(.down)
        waitForFocus("issue filter resolved")
        shoot("tvos-issues-filter-focused")
        press(.select)
        // The system's way of saying so (`StateView`): its symbol, Seerr's words and Seerr's way out, as one view.
        XCTAssertTrue(element("issues empty").waitForExistence(timeout: 10), "No Results under Resolved")
        XCTAssertTrue(stateWords("issues empty").contains("No Results"), stateWords("issues empty"))
        XCTAssertTrue(element("show all", in: "issues empty").exists, "Show All Issues inside the state")
        XCTAssertTrue(element("issues empty").images.firstMatch.exists, "the state's symbol")
        waitForValue("issue filter resolved", "selected")
        XCTAssertEqual(element("issue filter open").value as? String ?? "", "", "one chosen at a time")
        waitForFocus("issue filter resolved", "focus stays on the filter chosen")
        shoot("tvos-issues-empty")
        press(.right)
        waitForFocus("show all", "Right from the list to the state's way out")
        press(.select)
        waitForRows("issue:", ["Phineas and Ferb"], "Show All Issues lists every issue")
        waitForValue("issue filter all", "selected")
    }

    func testReportAnIssueOnAFilm() {
        Fake.set("/fake/account?createissues=1&viewissues=1")
        launchSignedOut()
        signIn()
        let film = Fixture.film
        openTitle(film.key, row: film.row, index: film.index, title: film.title)
        // Last among the actions, in the system's symbol for it, not Seerr's yellow warning (the review's S22).
        XCTAssertTrue(element("action report issue").waitForExistence(timeout: 10), "Report an Issue with the right")
        let actions = element("title actions").buttons.allElementsBoundByIndex.sorted { $0.frame.minX < $1.frame.minX }
        XCTAssertEqual(actions.last?.identifier, "action report issue", "last among the actions")
        XCTAssertTrue(element("action report issue").images["exclamationmark.bubble"].exists,
                      "the speech bubble: \(element("action report issue").images.allElementsBoundByIndex.map(\.identifier))")
        pressUntilFocus(.right, "action report issue")
        shoot("tvos-title-report-issue")
        press(.select)
        // The system's form in a sheet (the review's X6, F19): its title, the title's name over the rows.
        XCTAssertTrue(element("issue modal").waitForExistence(timeout: 5), "Seerr's form")
        assertSheetTitle("Report an Issue")
        waitForLabel("issue subject", contains: "Resident Evil")
        XCTAssertFalse(element("issue season").exists, "no season for a film")
        XCTAssertFalse(element("issue cancel").exists, "no Cancel: Menu closes the sheet")
        // The type is a row of the form (F16), Video chosen as Seerr opens it, its list a page of the sheet: no pills.
        waitForFocus("issue type", "focus on the type")
        waitForValue("issue type", "Video")
        assertFocusedRowReads("issue type")
        XCTAssertFalse(element("issue type Audio").exists, "no pill per type")
        XCTAssertFalse(element("issue submit").isEnabled, "Submit Issue needs words")
        XCTAssertFalse(app.staticTexts["*"].exists, "no red asterisk (S23)")
        press(.select)
        XCTAssertEqual(listItems("issue type"), ["Video", "Audio", "Subtitle", "Other"], "Seerr's four types, in its order")
        XCTAssertTrue(element("issue type: Video").isSelected, "the current one ticked")
        assertFocusedRowReads("issue type: Video")
        shoot("tvos-report-issue-type")
        chooseInList("issue type", "Audio")
        waitForValue("issue type", "Audio")
        pressUntilFocus(.down, "issue message")
        XCTAssertEqual(element("issue message").placeholderValue, "Describe the problem", "S23's words")
        press(.select)
        typeAndReturn("The sound drops out after an hour.", "done")
        waitForFocus("issue message")
        shoot("tvos-report-issue")
        pressUntilFocus(.down, "issue submit")
        press(.select)
        XCTAssertTrue(element("issue modal").waitForNonExistence(timeout: 10), "the form closes once sent")
        // What it did, where it was pressed (Tom's D4): the button says so; no toast.
        waitForFocus("action report issue", "focus back on the button")
        waitForLabel("action report issue", contains: "Issue Reported")
        assertNoToast("an issue reported")
        shoot("tvos-issue-reported")

        // The issue is on the server as chosen: Audio, the words, by the family.
        press(.menu)
        press(.menu)
        openTabFromContent("Issues", page: "Issues")
        waitForRows("issue:", ["Resident Evil", "Phineas and Ferb"], "the new issue first")
        let row = rows("issue:")[0].id
        XCTAssertTrue(inside(row, "card when").label.hasPrefix("Audio · "), inside(row, "card when").label)
        let says = element(row).value as? String ?? ""
        XCTAssertTrue(says.contains("The sound drops out after an hour."), says)
        XCTAssertTrue(says.contains("Opened by User 8"), says)
    }

    func testReportAnIssueOnASeriesNamesSeasonAndEpisode() {
        Fake.set("/fake/account?createissues=1&viewissues=1")
        launchSignedOut()
        signIn()
        let series = Fixture.series
        openTitle(series.key, row: series.row, index: series.index, title: series.title)
        pressUntilFocus(.right, "action report issue", times: 10)
        press(.select)
        XCTAssertTrue(element("issue modal").waitForExistence(timeout: 5))
        waitForFocus("issue season", "focus on the season, for a series")
        // Only Season 1 is in the library, so it is chosen already (Seerr's rule); the menu lists "All Seasons" first.
        waitForValue("issue season", "Season 1")
        XCTAssertTrue(app.staticTexts["Affected Season"].exists && app.staticTexts["Affected Episode"].exists, "the rows' words on screen")
        shoot("tvos-report-issue-series")
        press(.select)
        XCTAssertEqual(listItems("issue season"), ["All Seasons", "Season 1"], "the seasons in the library, All first")
        // Menu goes back a page, not out of the form; Select on the one ticked goes back with it too.
        press(.menu)
        waitForFocus("issue season", "back on the season's row")
        XCTAssertTrue(element("issue modal").exists, "Menu went back a page, not out of the form")
        press(.select)
        XCTAssertTrue(element("issue season: Season 1").waitForExistence(timeout: 5))
        XCTAssertTrue(element("issue season: Season 1").isSelected, "the season chosen ticked")
        chooseInList("issue season", "Season 1")
        waitForValue("issue season", "Season 1")
        // Then the episodes of the season chosen, the row under it.
        press(.down)
        waitForFocus("issue episode")
        press(.select)
        XCTAssertEqual(listItems("issue episode").prefix(3), ["All Episodes", "Episode 1", "Episode 2"], "the season's episodes, All first")
        chooseInList("issue episode", "Episode 2")
        waitForValue("issue episode", "Episode 2")
        pressUntilFocus(.down, "issue message")
        press(.select)
        typeAndReturn("Episode two has no sound.", "done")
        pressUntilFocus(.down, "issue submit")
        press(.select)
        XCTAssertTrue(element("issue modal").waitForNonExistence(timeout: 10), "the form closes once sent")
        waitForLabel("action report issue", contains: "Issue Reported")

        press(.menu)
        press(.menu)
        openTabFromContent("Issues", page: "Issues")
        waitForRows("issue:", ["Phineas and Ferb", "Phineas and Ferb"])
        let row = rows("issue:")[0].id
        let says = element(row).value as? String ?? ""
        for part in ["Season 1", "Episode 2", "Episode two has no sound."] {
            XCTAssertTrue(says.contains(part), "VoiceOver reads \(part): \(says)")
        }
    }

    /// A report the server will not take keeps the form, words and all, and the form's own alert says why (Tom's D4);
    /// OK goes back to the form to try again.
    func testAReportThatFailsKeepsTheForm() {
        Fake.set("/fake/account?createissues=1&viewissues=1")
        launchSignedOut()
        signIn()
        let film = Fixture.film
        openTitle(film.key, row: film.row, index: film.index, title: film.title)
        pressUntilFocus(.right, "action report issue", times: 10)
        press(.select)
        XCTAssertTrue(element("issue modal").waitForExistence(timeout: 5))
        pressUntilFocus(.down, "issue message")
        press(.select)
        typeAndReturn("The picture freezes.", "done")
        // The server's CSRF protection with Secure cookies, over http: the report cannot be made.
        Fake.set("/fake/server?csrf=1&secure=1")
        pressUntilFocus(.down, "issue submit")
        press(.select)
        let alert = waitForAlert("Issue Not Sent", timeout: 15)
        XCTAssertTrue(alertSays("The issue did not go through. Try again."), "Hither's words")
        shoot("tvos-issue-failed")
        press(.select)
        XCTAssertTrue(alert.waitForNonExistence(timeout: 5), "OK closes it")
        XCTAssertTrue(element("issue modal").exists, "the form is still there")
        XCTAssertEqual(element("issue message").value as? String, "The picture freezes.", "the words kept")
        waitForFocus("issue submit", "back on Submit Issue, to try again")
        assertNoToast("a report that failed")
        press(.menu)
        XCTAssertTrue(element("issue modal").waitForNonExistence(timeout: 5))
        waitForLabel("action report issue", contains: "Report an Issue")
    }
}

extension HitherUITestCase {
    /// The choices of the `FormPick` [id]'s list, now open, top to bottom: those on its screen (a long list keeps rows
    /// it has drawn off it, with frames of their own).
    func listItems(_ id: String, timeout: TimeInterval = 5, file: StaticString = #filePath, line: UInt = #line) -> [String] {
        let list = element("\(id) list")
        XCTAssertTrue(list.waitForExistence(timeout: timeout), "\(id)'s list", file: file, line: line)
        let prefix = "\(id): "
        return app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).allElementsBoundByIndex
            .filter { list.frame.contains(CGPoint(x: $0.frame.midX, y: $0.frame.midY)) }
            .sorted { $0.frame.minY < $1.frame.minY }
            .map { String($0.identifier.dropFirst(prefix.count)) }
    }

    /// Down through the `FormPick` [id]'s open list to [item], then Select: the list goes back to the form.
    func chooseInList(_ id: String, _ item: String, file: StaticString = #filePath, line: UInt = #line) {
        let choice = "\(id): \(item)"
        XCTAssertTrue(element(choice).waitForExistence(timeout: 5), "\(item) in the list", file: file, line: line)
        pressUntilFocus(.down, choice, times: 12, file: file, line: line)
        press(.select)
        XCTAssertTrue(element("\(id) list").waitForNonExistence(timeout: 5), "the list goes back", file: file, line: line)
    }

    /// From a tab page's content (Discover's rows, back from a title), Up to the bar and along it to [tab].
    func openTabFromContent(_ tab: String, page: String, file: StaticString = #filePath, line: UInt = #line) {
        for _ in 0..<6 where !tabBar.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists { press(.up) }
        for _ in 0..<5 where !hasFocus("tab \(tab)") { press(.right) }
        waitForFocus("tab \(tab)", file: file, line: line)
        XCTAssertTrue(element("page \(page)").waitForExistence(timeout: 10), "\(page)'s page", file: file, line: line)
    }

    /// Waits for [id]'s accessibility value to be [value].
    func waitForValue(_ id: String, _ value: String, timeout: TimeInterval = 10, file: StaticString = #filePath, line: UInt = #line) {
        let target = element(id)
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if target.exists, target.value as? String == value { return }
            Thread.sleep(forTimeInterval: 0.3)
        }
        XCTFail("\(id): expected the value \"\(value)\", it is \"\(target.value ?? "(none)")\"", file: file, line: line)
    }
}
