import XCTest

/// Requesting from the title page: Request and Request in 4K by the account's live rights, a film's question in the
/// system's alert and a series' seasons in the system's form, the state straight after on the page and on Discover's
/// card (no toast: Tom's D4), a request that fails as an alert, and auto-approval as the account's rights say.
final class RequestUITests: HitherUITestCase {
    /// A film nobody has asked for: Request, the system's alert asking it with "approved automatically" (the account
    /// auto-approves), the request made, the page's badge "Requested" at once and the button gone, and nothing else to
    /// say so; Menu, and Discover's card shows it too.
    func testRequestFilmShowsTheStateOnThePageAndTheCard() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.coco.key, row: Fixture.coco.row, index: Fixture.coco.index, title: Fixture.coco.title)
        XCTAssertFalse(element("status Requested").exists, "nothing asked for yet")
        waitForFocus("action Request")
        press(.select)
        // A question with nothing to choose is an alert, not a sheet (the review's F2, F19), its buttons short.
        let alert = waitForAlert("Request Coco?")
        XCTAssertTrue(alertSays("This request needs no approval."), "the account's requests are approved at once")
        XCTAssertFalse(element("request modal").exists, "no sheet for a film")
        // (Each button is read twice: the alert's cell and the button inside it.)
        XCTAssertEqual(Set(alert.buttons.allElementsBoundByIndex.map(\.label)), ["Cancel", "Request"], "two short buttons")
        assertSideBySide(alert, "Cancel", "Request")
        shoot("request-film-modal")
        focusAlertButton("primary")
        press(.select)
        XCTAssertTrue(alert.waitForNonExistence(timeout: 10), "the alert goes as it is answered")
        XCTAssertTrue(element("status Requested").waitForExistence(timeout: 10), "the page's badge at once")
        assertNoToast("a request made")
        XCTAssertFalse(element("action Request").exists, "nothing left to ask for in the regular copy")
        waitForFocus("action Request in 4K", "focus on the button left")
        shoot("request-film-after")
        press(.menu)
        waitForFocus(Fixture.coco.key, "back on the card")
        let badge = element(Fixture.coco.key).descendants(matching: .any).matching(identifier: "badge Requested").firstMatch
        XCTAssertTrue(badge.waitForExistence(timeout: 10), "Discover's card shows the request")
        shoot("request-film-card")
    }

    /// An account that does not auto-approve: no notice in the alert, and the badge reads "Pending" (Android's).
    func testRequestThatWaitsReadsPending() {
        Fake.set("/fake/account?autoapprove=0")
        launchSignedOut()
        signIn()
        openTitle(Fixture.coco.key, row: Fixture.coco.row, index: Fixture.coco.index, title: Fixture.coco.title)
        waitForFocus("action Request")
        press(.select)
        waitForAlert("Request Coco?")
        XCTAssertFalse(alertSays("This request needs no approval."), "no notice without auto-approval")
        focusAlertButton("primary")
        press(.select)
        XCTAssertTrue(element("status Pending").waitForExistence(timeout: 10), "Pending at once")
        press(.menu)
        waitForFocus(Fixture.coco.key)
        XCTAssertTrue(element(Fixture.coco.key).descendants(matching: .any).matching(identifier: "badge Pending").firstMatch.waitForExistence(timeout: 10))
    }

    /// An approver (Manage Requests, no Auto-Approve of its own) has its requests approved at once, as Seerr and
    /// Android have it: the notice in the modal, and "Requested" straight after, never "Pending".
    func testApproversRequestIsApprovedAtOnce() {
        Fake.set("/fake/account?autoapprove=0&manage=1")
        launchSignedOut()
        signIn()
        openTitle(Fixture.coco.key, row: Fixture.coco.row, index: Fixture.coco.index, title: Fixture.coco.title)
        waitForFocus("action Request")
        press(.select)
        waitForAlert("Request Coco?")
        XCTAssertTrue(alertSays("This request needs no approval."), "an approver's request is approved at once")
        focusAlertButton("primary")
        press(.select)
        XCTAssertTrue(element("status Requested").waitForExistence(timeout: 10), "Requested, not Pending")
        XCTAssertFalse(element("status Pending").exists)
    }

    /// Request in 4K shows where the account may ask for 4K films (read live on the page), and not where it may not.
    func test4KButtonFollowsTheRights() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.coco.key, row: Fixture.coco.row, index: Fixture.coco.index, title: Fixture.coco.title)
        XCTAssertTrue(element("action Request in 4K").waitForExistence(timeout: 10), "4K with the right")
        press(.right)
        waitForFocus("action Request in 4K")
        press(.select)
        let alert = waitForAlert("Request Coco in 4K?")
        press(.menu)
        XCTAssertTrue(alert.waitForNonExistence(timeout: 5), "Menu closes the alert")
        waitForFocus("action Request in 4K", "focus back on the button that opened it")
        XCTAssertFalse(element("status Requested").exists, "nothing asked for")
        press(.menu)
        waitForFocus(Fixture.coco.key)

        // The right goes: the page, read again on opening, has no 4K button.
        Fake.set("/fake/account?fourk=0")
        press(.select)
        XCTAssertTrue(element("title page").waitForExistence(timeout: 10))
        XCTAssertTrue(element("action Request").waitForExistence(timeout: 10))
        XCTAssertFalse(element("action Request in 4K").exists, "no 4K without the right")
    }

    /// A series' request is the system's form in a sheet (the review's X6): Seerr's title as the sheet's, the series'
    /// name over its seasons as ticked rows, focus on the first open season, Select ticks a row, the primary (the last row)
    /// counts what is picked, and no Cancel (Menu is). The request is for those seasons, and the page shows them at once:
    /// off the shelf of seasons to ask for, and in Information as "Seasons 1–2", Requested (Tom's D2); no toast.
    func testSeasonsPicker() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.freshSeries.key, row: Fixture.freshSeries.row, index: Fixture.freshSeries.index, title: Fixture.freshSeries.title)
        XCTAssertFalse(element("action Request in 4K").exists, "series 4K is off on the server")
        waitForFocus("action Request")
        press(.select)
        XCTAssertTrue(element("request modal").waitForExistence(timeout: 10), "the request sheet")
        assertSheetTitle("Request Series")
        waitForLabel("request subject", contains: "Modern Family")
        XCTAssertFalse(element("cancel").exists, "no Cancel: Menu closes the sheet")
        waitForFocus("season 1", "the first open season")
        // The seasons as ticked rows, "All Seasons" first; an open season says nothing of its status (the review's S6:
        // "Not Requested" on every one).
        XCTAssertEqual(element("season all").label, "All Seasons")
        for number in 1...6 {
            XCTAssertFalse(element("season \(number)").descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'status '")).firstMatch.exists,
                           "season \(number), open, shows no status")
        }
        XCTAssertFalse(element("primary").isEnabled, "nothing picked: Select Season(s)")
        XCTAssertEqual(element("primary").label, "Select Season(s)")
        press(.select)
        press(.down)
        waitForFocus("season 2")
        assertFocusedRowReads("season 2")
        press(.select)
        XCTAssertEqual(element("season 1").value as? String, "on")
        XCTAssertEqual(element("season 2").value as? String, "on")
        XCTAssertEqual(element("season 3").value as? String, "off")
        waitForLabel("primary", contains: "Request 2 Seasons")
        shoot("request-seasons")
        // Down past the last season to the primary, the form's last row.
        pressUntilFocus(.down, "primary", times: 8)
        Thread.sleep(forTimeInterval: 1) // the form's scroll settled
        shoot("request-seasons-primary")
        press(.select)
        XCTAssertTrue(element("request modal").waitForNonExistence(timeout: 10), "the sheet goes with the answer")
        assertNoToast("a request made")
        let asked = element("fact Seasons 1–2")
        XCTAssertTrue(asked.waitForExistence(timeout: 10), "seasons 1 and 2 in Information, as one run")
        XCTAssertTrue(asked.descendants(matching: .any).matching(identifier: "status Requested").firstMatch.exists, "seasons 1 and 2 requested")
        XCTAssertFalse(element("series season 1").exists, "season 1 off the shelf")
        XCTAssertFalse(element("series season 2").exists, "season 2 off the shelf")
        let three = element("series season 3")
        XCTAssertTrue(three.exists, "season 3 still to ask for")
        XCTAssertFalse(three.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'status '")).firstMatch.exists, "season 3 not asked for")
        XCTAssertTrue(element("action Request More").waitForExistence(timeout: 10), "more seasons left to ask for")
        shoot("request-seasons-after")
    }

    /// A request the server will not take is the system's alert (Tom's D4: failures are alerts), in Android's words, and
    /// the page is as it was: the button there to try again, focus back on it.
    func testARequestThatFailsIsAnAlert() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.coco.key, row: Fixture.coco.row, index: Fixture.coco.index, title: Fixture.coco.title)
        waitForFocus("action Request")
        // The server's CSRF protection with Secure cookies, over http: the request cannot be made (core's csrfNeedsHttps).
        Fake.set("/fake/server?csrf=1&secure=1")
        press(.select)
        waitForAlert("Request Coco?")
        focusAlertButton("primary")
        press(.select)
        let failed = waitForAlert("Request Not Sent", timeout: 15)
        XCTAssertTrue(alertSays("This server's CSRF protection needs https, so the request was not made."), "why, in Android's words")
        assertNoToast("a request that failed")
        shoot("request-failed")
        press(.select)
        XCTAssertTrue(failed.waitForNonExistence(timeout: 5), "OK closes it")
        XCTAssertFalse(element("status Requested").exists, "nothing asked for")
        waitForFocus("action Request", "back on the button, to try again")
    }
}

/// Watch Trailer and Play on Plex hand their links to the tvOS apps by URL scheme: shown only where an app opens them
/// (as Android asks the package manager), the link each builds, and the system's alert when tvOS will not open it.
final class AppLinkUITests: HitherUITestCase {
    func testButtonsHiddenWithoutTheApps() {
        launchSignedOut()
        signIn()
        openTitle(Fixture.series.key, row: Fixture.series.row, index: Fixture.series.index, title: Fixture.series.title)
        XCTAssertTrue(element("overview").waitForExistence(timeout: 10))
        XCTAssertFalse(element("action watch trailer").exists, "no YouTube on the simulator: no trailer button")
        XCTAssertFalse(element("action Play on Plex").exists, "no Plex on the simulator: no play button")
    }

    func testTheLinksEachButtonBuilds() {
        // A debug build told the apps are there (the simulator has neither).
        app.launchEnvironment["HITHER_APPS"] = "youtube,plex"
        launchSignedOut()
        signIn()
        openTitle(Fixture.series.key, row: Fixture.series.row, index: Fixture.series.index, title: Fixture.series.title)
        let plex = element("action Play on Plex")
        XCTAssertTrue(plex.waitForExistence(timeout: 10), "Play on Plex for a title Plex has")
        // Seerr's own iOS link for the record's rating key and fake-seerr's machine id.
        XCTAssertEqual(plex.value as? String, "plex://preplay/?metadataKey=%2Flibrary%2Fmetadata%2F1&server=f00dfacecafe0000000000000000000000000000")
        let trailer = element("action watch trailer")
        XCTAssertTrue(trailer.exists, "Watch Trailer for a title with a YouTube trailer")
        XCTAssertEqual(trailer.value as? String, "youtube://watch/th9hxCGH_ms")
        waitForFocus("action Play on Plex", "the play button first, as Seerr's")
        shoot("title-apps")
        // The simulator opens neither: the system's alert says so, in Android's words (Tom's D4: failures are alerts).
        press(.select)
        var alert = waitForAlert("Can't Open Plex", timeout: 15)
        XCTAssertTrue(alertSays("Plex could not be opened on this TV."))
        press(.select)
        XCTAssertTrue(alert.waitForNonExistence(timeout: 5), "OK closes it")
        waitForFocus("action Play on Plex", "back on the button")
        pressUntilFocus(.right, "action watch trailer", times: 4)
        press(.select)
        alert = waitForAlert("Can't Open the Trailer", timeout: 15)
        XCTAssertTrue(alertSays("The trailer could not be opened on this TV."))
        assertNoToast("an app that would not open")
    }
}
