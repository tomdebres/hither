import XCTest

/// Sign-in for everyone (M8), driven with the Siri Remote against fake-seerr's made-up accounts and its fake plex.tv
/// (`/fake/signin`, `/fake/plex…`, `/fake/quickconnect…`): the ways in each server's settings offer, Jellyfin's and
/// Emby's form, Quick Connect's and Plex's codes, their failures in words, Cancel, Menu, focus, and a Plex session that
/// ends.
final class MediaSignInUITests: HitherUITestCase {
    // MARK: - What the form offers

    /// The recorded server has its own password sign-in on and Plex for its media server: the password form as before,
    /// then "Other ways to sign in" and Plex, a plain system button, under Sign In; nothing of it before the server is
    /// read.
    func testPasswordFormWithPlexUnderIt() {
        launchSignedOut()
        XCTAssertFalse(element("plex").exists, "no way in before the server is read")
        XCTAssertFalse(element("or sign in with").exists)
        enterServer()
        let plex = element("plex")
        XCTAssertTrue(plex.waitForExistence(timeout: 20), "Plex once the server is read")
        XCTAssertEqual(plex.label, "Plex")
        XCTAssertEqual(element("or sign in with").label, "Other ways to sign in")
        XCTAssertEqual(element("heading").label, "Sign in to Seerr", "the server's title heads the form")
        for id in ["email", "password", "sign in"] { XCTAssertTrue(element(id).exists, id) }
        let signIn = element("sign in").frame, or = element("or sign in with").frame
        XCTAssertGreaterThan(or.minY, signIn.maxY, "Other ways to sign in under Sign In: \(or), \(signIn)")
        XCTAssertGreaterThan(plex.frame.minY, or.maxY, "Plex under it: \(plex.frame)")
        XCTAssertEqual(plex.frame.midX, 960, accuracy: 4, "centred: \(plex.frame)")
        XCTAssertLessThan(plex.frame.width, 400, "a plain button sized to its words, not a second Sign In: \(plex.frame)")
        XCTAssertLessThanOrEqual(plex.frame.maxY, 1020, "on the screen: \(plex.frame)")
        assertNotProminent("plex")
        pressUntilFocus(.down, "plex")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-signin-plex-offered")
    }

    /// With Seerr's own sign-in off there are no email and password fields and no Sign In; the server field's Next goes
    /// to the way in, which no "Or" heads.
    func testLocalSignInOffLeavesTheWayIn() {
        Fake.set("/fake/signin?local=0")
        launchSignedOut()
        waitForFocus("server")
        press(.select)
        typeAndReturn(Fake.address, "next")
        waitForFocus("plex", timeout: 20, "Next on the server goes to the way in")
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 10), "no keyboard left open")
        for id in ["email", "password", "sign in", "or sign in with"] { XCTAssertFalse(element(id).exists, "\(id) with local sign-in off") }
        XCTAssertEqual(element("heading").label, "Sign in to your server")
        XCTAssertGreaterThan(element("plex").frame.minY, element("server").frame.maxY, "the way in under the server")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-signin-local-off")
        // The server's offer known, Next goes straight on to the way in.
        press(.up)
        waitForFocus("server")
        press(.select)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10))
        let next = app.buttons["next"]
        XCTAssertTrue(next.waitForExistence(timeout: 5))
        for _ in 0..<6 where !next.hasFocus { press(.down) }
        press(.select)
        waitForFocus("plex", timeout: 10, "Next with the offer known")
    }

    /// Media-server sign-in off: the password form alone, no "Other ways to sign in" and no button.
    func testMediaServerSignInOffOffersNothingMore() {
        Fake.set("/fake/signin?mediaserver=0")
        launchSignedOut()
        enterServer()
        XCTAssertTrue(waitForLabel("heading", "Sign in to Seerr"), "the server read")
        Thread.sleep(forTimeInterval: 2)
        XCTAssertFalse(element("plex").exists)
        XCTAssertFalse(element("media server").exists)
        XCTAssertFalse(element("or sign in with").exists)
    }

    // MARK: - Jellyfin and Emby

    /// A Jellyfin server: its button, its form under the server's name (Username, Password, Sign In, Quick Connect on
    /// Seerr 3.5.2), Menu back to the button. A wrong password first, in Seerr's words under Sign In; then Robin signs in.
    func testJellyfinFormAndSignIn() {
        Fake.set("/fake/signin?type=jellyfin")
        launchSignedOut()
        openMediaServerForm("Jellyfin")
        let title = assertPageTitle("Seerr", in: "page media server")
        XCTAssertEqual(element("heading").label, "Sign in with Jellyfin")
        XCTAssertFalse(element("wordmark").exists, "no mark under a page's title")
        XCTAssertGreaterThan(element("heading").frame.minY, title.maxY)
        waitForFocus("username", "the form arrives on Username")
        var above = element("heading").frame
        for id in ["username", "media password", "media sign in", "quick connect"] {
            let frame = element(id).frame
            XCTAssertEqual(frame.midX, 960, accuracy: 4, "\(id) centred: \(frame)")
            XCTAssertEqual(frame.width, 800, accuracy: 24, "\(id) the column's width: \(frame)")
            XCTAssertGreaterThan(frame.minY, above.maxY, "\(id) under what is above it")
            above = frame
        }
        assertIndigoButton("media sign in")
        assertNotProminent("quick connect")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-jellyfin-form")
        // Menu: back on the button that opened it.
        press(.menu)
        waitForFocus("media server", "Menu returns to the Jellyfin button")
        XCTAssertTrue(element("server").exists, "the sign-in form")
        // A wrong password first.
        press(.select)
        signInWithMediaServer("robin", "wrong-password")
        let error = element("media sign-in error")
        XCTAssertTrue(error.waitForExistence(timeout: 20), "an error under Sign In")
        XCTAssertEqual(error.label, "The server did not accept that username and password.")
        assertWordsUnder("media sign-in error", "media sign in")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-jellyfin-wrong-password")
        // What was typed, once: the system's field under Hither's drawing shows nothing of its own.
        assertNoGhostWords("username")
        assertNoGhostWords("media password")
        assertFieldsAlike(["username", "media password"])
        // Then the right one: Username keeps what was typed.
        pressUntilFocus(.up, "media password")
        press(.select)
        typeAndReturn("robin-password", "done")
        waitForFocus("media sign in")
        press(.select)
        waitForDiscover()
        assertSignedInAs("Robin")
    }

    /// An empty username is named under its field; a member Seerr has not imported is told in plain words.
    func testJellyfinUsernameRequiredAndNotImported() {
        Fake.set("/fake/signin?type=jellyfin&newlogin=0")
        launchSignedOut()
        openMediaServerForm("Jellyfin")
        waitForFocus("username")
        pressUntilFocus(.down, "media sign in")
        press(.select)
        XCTAssertTrue(waitForLabel("username error", "Enter a username."), "named under Username, as the other forms say it")
        assertWordsUnder("username error", "username")
        pressUntilFocus(.up, "username")
        press(.select)
        typeAndReturn("sasha", "next")
        typeAndReturn("sasha-password", "done")
        waitForFocus("media sign in")
        press(.select)
        XCTAssertTrue(waitForLabel("media sign-in error",
                                   "Seerr does not know this Jellyfin account yet. Ask whoever runs Seerr to import it as a user, then sign in again."))
        XCTAssertFalse(element("username error").exists, "the username's complaint gone")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-jellyfin-not-imported")
    }

    /// An Emby server: "Emby", its form without Quick Connect (Emby has none).
    func testEmbyHasNoQuickConnect() {
        Fake.set("/fake/signin?type=emby")
        launchSignedOut()
        openMediaServerForm("Emby")
        XCTAssertEqual(element("heading").label, "Sign in with Emby")
        waitForFocus("username")
        XCTAssertFalse(element("quick connect").exists)
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-emby-form")
        // Tom: "First text field doesn't look right": it grew out of the column, its placeholder barely read.
        assertFocusedFieldInColumn("username", beside: "media password")
    }

    /// Jellyfin on a Seerr before 3.4.0: username and password only.
    func testOldSeerrHasNoQuickConnect() {
        Fake.set("/fake/signin?type=jellyfin&version=3.3.0")
        launchSignedOut()
        openMediaServerForm("Jellyfin")
        waitForFocus("username")
        XCTAssertFalse(element("quick connect").exists, "no Quick Connect before Seerr 3.4.0")
    }

    // MARK: - Quick Connect

    /// Quick Connect: its code large, as two threes, where to approve it (the server's Jellyfin address), the waiting
    /// line, Cancel focused; Cancel and Menu back on Quick Connect; approved, Robin signs in.
    func testQuickConnect() {
        Fake.set("/fake/signin?type=jellyfin&external=https://jellyfin.example.invalid")
        launchSignedOut()
        openMediaServerForm("Jellyfin")
        waitForFocus("username")
        pressUntilFocus(.down, "quick connect")
        press(.select)
        let code = waitForCode()
        XCTAssertEqual(code.count, 6, code)
        XCTAssertEqual(element("code heading").label, "Quick Connect")
        XCTAssertEqual(element("approve where").label,
                       "Signed in to Jellyfin at jellyfin.example.invalid on a phone or computer: your profile, then Quick Connect.")
        XCTAssertEqual(element("waiting").label, "Waiting for the code to be approved…")
        assertCodeLarge()
        waitForFocus("cancel", "Cancel has focus while waiting")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-quick-connect-code")
        // Cancel: back on Quick Connect, on Jellyfin's form.
        press(.select)
        waitForFocus("quick connect", "Cancel returns to Quick Connect")
        XCTAssertFalse(liveCodes("/fake/quickconnect").isEmpty == false && element("code").exists)
        // Menu does the same.
        press(.select)
        _ = waitForCode()
        press(.menu)
        waitForFocus("quick connect", "Menu returns to Quick Connect")
        // Approved in Jellyfin (fake-seerr's, as robin): signed in.
        press(.select)
        let third = waitForCode()
        Fake.set("/fake/quickconnect/approve?code=\(third)&user=robin")
        waitForDiscover()
        assertSignedInAs("Robin")
    }

    /// A code that runs out says so, and Get a New Code (focused) asks for another; Quick Connect off in Jellyfin says
    /// that, with Try Again.
    func testQuickConnectExpiredAndOff() {
        Fake.set("/fake/signin?type=jellyfin")
        Fake.set("/fake/quickconnect?life=4")
        launchSignedOut()
        openMediaServerForm("Jellyfin")
        waitForFocus("username")
        pressUntilFocus(.down, "quick connect")
        press(.select)
        let first = waitForCode()
        XCTAssertTrue(element("new code").waitForExistence(timeout: 30), "the code runs out")
        XCTAssertEqual(stateWords("code trouble"), "Code Expired This code expired before it was approved in Jellyfin.")
        waitForFocus("new code", "Get a New Code has focus")
        XCTAssertEqual(element("new code").label, "Get a New Code")
        assertWordsWhole("new code", "cancel")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-quick-connect-expired")
        assertActionsRoom("code trouble")
        Fake.set("/fake/quickconnect?life=600")
        press(.select)
        let second = waitForCode()
        XCTAssertNotEqual(second, first, "a new code")
        press(.menu)
        waitForFocus("quick connect")
        Fake.set("/fake/quickconnect?off=1")
        press(.select)
        XCTAssertTrue(element("new code").waitForExistence(timeout: 30), "no code to be had")
        XCTAssertEqual(stateWords("code trouble"), "Quick Connect did not start. It may be switched off in Jellyfin's dashboard.")
        XCTAssertEqual(element("new code").label, "Try Again")
        waitForFocus("new code")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-quick-connect-off")
        // Tom: "Text looks too close to the button" (6 points).
        assertActionsRoom("code trouble")
    }

    // MARK: - Plex

    /// Plex: the code large with plex.tv/link, the waiting line, Cancel focused; entered at the (fake) plex.tv/link as a
    /// member, Seerr signs in; plex.tv was sent the product's name and this install's id and nothing else.
    func testPlexSignIn() {
        launchSignedOut()
        openPlex()
        let code = waitForCode()
        XCTAssertEqual(code.count, 4, code)
        assertPageTitle("Seerr", in: "page code")
        XCTAssertEqual(element("code heading").label, "Sign in with Plex")
        XCTAssertEqual(element("plex link").label, "plex.tv/link")
        XCTAssertEqual(element("waiting").label, "Waiting for the code to be entered…")
        assertCodeLarge()
        waitForFocus("cancel", "Cancel has focus while waiting")
        XCTAssertFalse(app.descendants(matching: .textField).firstMatch.exists, "not a column of fields")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-plex-code")
        Fake.set("/fake/plex/link?code=\(code)&account=member")
        waitForDiscover()
        assertSignedInAs("Plex Member")
        let seen = Fake.answer("/fake/plex")["seen"] as? [[String: Any]] ?? []
        XCTAssertGreaterThanOrEqual(seen.count, 2, "the code asked for, then looked at")
        for request in seen {
            let headers = request["headers"] as? [String: String] ?? [:]
            XCTAssertEqual(Set(headers.keys.map { $0.lowercased() }), ["x-plex-product", "x-plex-client-identifier"], "\(request)")
        }
    }

    /// Cancel and Menu end the wait at once: plex.tv is asked nothing more, and focus is back on Plex. Hither leaving the
    /// front (Home) ends it too, and coming back is the form, on Plex.
    func testPlexWaitEndsWithThePage() {
        launchSignedOut()
        openPlex()
        _ = waitForCode()
        waitForFocus("cancel")
        Thread.sleep(forTimeInterval: 3)
        press(.select)
        waitForFocus("plex", "Cancel returns to Plex")
        assertNoMoreLooks("after Cancel")
        press(.select)
        _ = waitForCode()
        Thread.sleep(forTimeInterval: 3)
        press(.menu)
        waitForFocus("plex", "Menu returns to Plex")
        assertNoMoreLooks("after Menu")
        // Home: Hither leaves the front, the page closes as Cancel closes it.
        press(.select)
        _ = waitForCode()
        Thread.sleep(forTimeInterval: 3)
        remote.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackgroundSuspended, timeout: 20) || app.wait(for: .runningBackground, timeout: 5), "Hither in the background")
        assertNoMoreLooks("with Hither in the background")
        app.activate()
        XCTAssertTrue(element("page Sign In").waitForExistence(timeout: 20), "back on the form")
        waitForFocus("plex", "back on Plex")
        XCTAssertFalse(element("code").exists, "the code's page closed")
    }

    /// Seerr refusing the Plex account closes the page back to the form, the reason under Sign In, focus on Plex.
    func testPlexAccountRefused() {
        launchSignedOut()
        openPlex()
        let code = waitForCode()
        Fake.set("/fake/plex/link?code=\(code)&account=stranger")
        XCTAssertTrue(waitForLabel("sign-in error",
                                   "Seerr refused this Plex account: it is not one of Seerr's users, or it has no access to the server's Plex. Ask whoever runs Seerr.", timeout: 30))
        assertWordsUnder("sign-in error", "sign in")
        waitForFocus("plex", "back on Plex")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-plex-refused")
    }

    /// A Plex code that runs out, and plex.tv not answering, each in words with the way on.
    func testPlexExpiredAndUnreachable() {
        Fake.set("/fake/plex?life=4")
        launchSignedOut()
        openPlex()
        _ = waitForCode()
        XCTAssertTrue(element("new code").waitForExistence(timeout: 30), "the code runs out")
        XCTAssertEqual(stateWords("code trouble"), "Code Expired This code expired before it was entered at plex.tv/link.")
        waitForFocus("new code")
        assertWordsWhole("new code", "cancel")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-plex-expired")
        press(.menu)
        waitForFocus("plex")
        Fake.set("/fake/plex?down=1&life=900")
        press(.select)
        XCTAssertTrue(element("new code").waitForExistence(timeout: 30), "no code from plex.tv")
        XCTAssertEqual(stateWords("code trouble"), "plex.tv could not be reached. Check the TV's internet connection, then try again.")
        XCTAssertEqual(element("new code").label, "Try Again")
        waitForFocus("new code")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-plex-unreachable")
        assertActionsRoom("code trouble")
        // Try Again once plex.tv is back: a code.
        Fake.set("/fake/plex?down=0")
        press(.select)
        _ = waitForCode()
    }

    /// A Plex session cannot be renewed: when Seerr ends it, Hither returns to the form with the server filled in and
    /// read, the reason, and focus on Plex: one Select and a new code. While running, and at a launch.
    func testPlexSessionEndsToTheForm() {
        launchSignedOut()
        signInWithPlex()
        // At a launch.
        app.terminate()
        Fake.set("/fake/expire")
        launchAsLeft()
        assertBackOnTheWayIn("plex", "at a launch")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-plex-session-ended")
        // While running: Seerr forgets the session, and the next call finds it over.
        signInAgainWithPlex()
        Fake.set("/fake/expire")
        press(.up)
        waitForFocus("tab Discover")
        press(.right)
        assertBackOnTheWayIn("plex", "while running")
    }

    /// A Quick Connect session ends the same way, on Jellyfin's button (its form holds Quick Connect).
    func testQuickConnectSessionEndsToTheForm() {
        Fake.set("/fake/signin?type=jellyfin")
        launchSignedOut()
        openMediaServerForm("Jellyfin")
        waitForFocus("username")
        pressUntilFocus(.down, "quick connect")
        press(.select)
        Fake.set("/fake/quickconnect/approve?code=\(waitForCode())&user=robin")
        waitForDiscover()
        app.terminate()
        Fake.set("/fake/expire")
        launchAsLeft()
        assertBackOnTheWayIn("media server", "Jellyfin's button")
    }

    /// The client identifier is kept across sign-out: two Plex sign-ins either side of one send plex.tv the same id.
    func testPlexClientIdKeptAcrossSignOut() {
        launchSignedOut()
        signInWithPlex()
        // A Plex account keeps no password: Sign Out says a new code is what signing in again takes (M8 part 2's report:
        // it said the password, as for every account).
        signOutFromSettings(says: "Signing in again takes a new code, entered at plex.tv/link on a phone or computer.", shot: "settings-sign-out-plex")
        Fake.set("/fake/expire")
        enterServer()
        pressUntilFocus(.down, "plex")
        press(.select)
        _ = waitForCode()
        let seen = Fake.answer("/fake/plex")["seen"] as? [[String: Any]] ?? []
        let posts = seen.filter { ($0["method"] as? String) == "POST" }
        XCTAssertEqual(posts.count, 2, "two codes asked for: \(posts)")
        let ids = Set(seen.compactMap { ($0["headers"] as? [String: String])?.first { $0.key.lowercased() == "x-plex-client-identifier" }?.value })
        XCTAssertEqual(ids.count, 1, "one client identifier either side of the sign-out: \(ids)")
    }
}

// MARK: - Helpers

extension Fake {
    /// POSTs one of fake-seerr's `/fake/...` switches and gives its JSON answer.
    static func answer(_ path: String, on base: String = address) -> [String: Any] {
        var request = URLRequest(url: URL(string: base + path)!)
        request.httpMethod = "POST"
        let done = XCTestExpectation(description: path)
        var body: [String: Any] = [:]
        URLSession.shared.dataTask(with: request) { data, _, _ in
            body = data.flatMap { try? JSONSerialization.jsonObject(with: $0) } as? [String: Any] ?? [:]
            done.fulfill()
        }.resume()
        XCTAssertEqual(XCTWaiter.wait(for: [done], timeout: 10), .completed, "fake-seerr \(path)")
        return body
    }
}

extension HitherUITestCase {
    /// Types [server] in the Server field and presses Next (the server is read), then closes the email field's keyboard
    /// that Next opened, with Menu: focus rests on Email.
    func enterServer(_ server: String = Fake.address) {
        waitForFocus("server")
        press(.select)
        typeAndReturn(server, "next")
        if app.keyboards.firstMatch.exists {
            press(.menu)
            XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 10), "the keyboard closed")
        }
    }

    /// Waits for [id]'s label to read [words].
    func waitForLabel(_ id: String, _ words: String, timeout: TimeInterval = 20) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if element(id).exists, element(id).label == words { return true }
            Thread.sleep(forTimeInterval: 0.5)
        }
        XCTFail("\(id) reads \"\(element(id).exists ? element(id).label : "(none)")\", not \"\(words)\"")
        return false
    }

    /// The server read, Select on its Jellyfin or Emby button ([words]): its form.
    func openMediaServerForm(_ words: String) {
        enterServer()
        let button = element("media server")
        XCTAssertTrue(button.waitForExistence(timeout: 20), "the media server's button")
        XCTAssertEqual(button.label, words)
        pressUntilFocus(.down, "media server")
        Thread.sleep(forTimeInterval: 1)
        shoot("m8-signin-\(words.lowercased())-offered")
        press(.select)
        XCTAssertTrue(element("page media server").waitForExistence(timeout: 10), "\(words)'s form")
    }

    /// The server read, Select on Plex: its code's page.
    func openPlex() {
        enterServer()
        XCTAssertTrue(element("plex").waitForExistence(timeout: 20), "Plex")
        pressUntilFocus(.down, "plex")
        press(.select)
        XCTAssertTrue(element("page code").waitForExistence(timeout: 10), "the code's page")
    }

    /// Fills Jellyfin's or Emby's form from Username and presses Sign In.
    func signInWithMediaServer(_ username: String, _ password: String) {
        waitForFocus("username")
        press(.select)
        typeAndReturn(username, "next")
        typeAndReturn(password, "done")
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 10))
        waitForFocus("media sign in", "Done lands on Sign In")
        press(.select)
    }

    /// The code on screen, once it is there: its label is "Code" and its characters one by one.
    func waitForCode(timeout: TimeInterval = 20) -> String {
        let code = element("code")
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if code.exists, code.label.hasPrefix("Code ") {
                return code.label.dropFirst(5).replacingOccurrences(of: " ", with: "")
            }
            Thread.sleep(forTimeInterval: 0.3)
        }
        XCTFail("no code on screen: \(code.exists ? code.label : "(none)")")
        return ""
    }

    /// The live codes fake-seerr holds at [path] (`/fake/plex`, `/fake/quickconnect`).
    func liveCodes(_ path: String) -> [String] {
        Fake.answer(path)["codes"] as? [String] ?? []
    }

    /// The code is the largest thing on the screen to read, at ten feet: its characters at least 76 points (tvOS's Title
    /// 1) tall in a box, centred.
    func assertCodeLarge(file: StaticString = #filePath, line: UInt = #line) {
        let code = element("code").frame
        XCTAssertEqual(code.midX, 960, accuracy: 4, "the code centred: \(code)", file: file, line: line)
        XCTAssertGreaterThanOrEqual(code.height, 120, "the code's box, Title 1 and its room: \(code)", file: file, line: line)
        for text in app.staticTexts.allElementsBoundByIndex where text.exists && text.frame.height > code.height {
            XCTFail("\(text.label) is taller than the code: \(text.frame)", file: file, line: line)
        }
    }

    /// [id] is a plain system button at rest, not the screen's indigo prominent one.
    func assertNotProminent(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        let button = element(id)
        XCTAssertTrue(button.waitForExistence(timeout: 10), id, file: file, line: line)
        guard !hasFocus(id), let colours = colours(in: button.frame.insetBy(dx: button.frame.height / 2, dy: 8).integral) else { return }
        var indigo = 0
        for y in 0..<colours.height {
            for x in 0..<colours.width {
                let (r, g, b) = colours.rgb(x, y)
                if abs(r - 0x4F) <= 16 && abs(g - 0x46) <= 16 && abs(b - 0xE5) <= 16 { indigo += 1 }
            }
        }
        XCTAssertLessThan(indigo, colours.width * colours.height / 10, "\(id) drawn in Seerr's indigo", file: file, line: line)
    }

    /// [longer]'s words are whole, not cut short with "…": its button is wider than [shorter]'s, whose words are fewer
    /// (a cut button was narrower: "Get a Ne…" beside Cancel).
    func assertWordsWhole(_ longer: String, _ shorter: String, file: StaticString = #filePath, line: UInt = #line) {
        let long = element(longer).frame, short = element(shorter).frame
        XCTAssertGreaterThan(long.width, short.width * 1.2, "\(longer) whole (\(long)) beside \(shorter) (\(short))", file: file, line: line)
    }

    /// No look at plex.tv for 6 seconds (it looks every 2 while waiting).
    func assertNoMoreLooks(_ when: String, file: StaticString = #filePath, line: UInt = #line) {
        let before = (Fake.answer("/fake/plex")["seen"] as? [Any])?.count ?? -1
        Thread.sleep(forTimeInterval: 6)
        let after = (Fake.answer("/fake/plex")["seen"] as? [Any])?.count ?? -1
        XCTAssertEqual(after, before, "plex.tv asked \(after - before) more times \(when)", file: file, line: line)
    }

    /// Plex from the form to Discover, as the fake's member.
    func signInWithPlex() {
        openPlex()
        Fake.set("/fake/plex/link?code=\(waitForCode())&account=member")
        waitForDiscover()
    }

    /// Plex again from the form after a session ended (focus already on Plex).
    func signInAgainWithPlex() {
        waitForFocus("plex")
        press(.select)
        Fake.set("/fake/plex/link?code=\(waitForCode())&account=member")
        waitForDiscover()
    }

    /// Back on the form after a session Hither could not renew: the server filled in and read, the reason under the
    /// form, focus on the way back in ([id]).
    func assertBackOnTheWayIn(_ id: String, _ when: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element("page Sign In").waitForExistence(timeout: 30), "the form \(when)", file: file, line: line)
        XCTAssertTrue(waitForLabel("sign-in error", "Your session on this server has ended. Sign in again.", timeout: 20), file: file, line: line)
        XCTAssertEqual(element("server").value as? String, Fake.address, "the server filled in \(when)", file: file, line: line)
        waitForFocus(id, timeout: 20, "focus on the way back in \(when)", file: file, line: line)
    }

    /// Settings › Sign Out, confirmed; the alert saying [says] when given.
    func signOutFromSettings(says: String? = nil, shot: String? = nil, file: StaticString = #filePath, line: UInt = #line) {
        press(.up)
        waitForFocus("tab Discover")
        press(.right, times: 3)
        waitForFocus("tab Account")
        press(.down)
        waitForFocus("about")
        pressUntilFocus(.down, "sign out")
        press(.select)
        XCTAssertTrue(element("confirm sign out").waitForExistence(timeout: 10))
        if let says {
            XCTAssertTrue(alertSays(says), "Sign Out's words for this account: \(says)", file: file, line: line)
            XCTAssertFalse(alertSays("password"), "no password asked of an account that kept none", file: file, line: line)
        }
        if let shot {
            Thread.sleep(forTimeInterval: 1)
            shoot(shot)
        }
        press(.right)
        waitForFocus("confirm sign out", timeout: 3)
        press(.select)
        XCTAssertTrue(app.textFields["server"].waitForExistence(timeout: 20), "the sign-in form after Sign Out")
    }
}
