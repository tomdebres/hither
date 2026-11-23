import XCTest

/// Sign-in against fake-seerr's made-up accounts, driven with the Siri Remote: the form, Seerr's words for each
/// failure, and ATS for a LAN server over plain http by IP address.
final class SignInUITests: HitherUITestCase {
    func testGoodSignInOpensDiscover() {
        launchSignedOut()
        XCTAssertEqual(element("heading").label, "Sign in to your server")
        Thread.sleep(forTimeInterval: 1)
        shoot("tvos-signin")
        // The focused field in the column as the one under it, its placeholder readable on it (Tom, M8 part 2).
        assertFocusedFieldInColumn("server", beside: "email")
        // One centred column, as tvOS's own sign-in screens (the review's F21, F34): the mark small over the heading, the
        // three fields and Sign In each the column's 800 points, one under the other, centred on the screen. The form was
        // 960 points wide at the right of a mark 520 wide, the biggest thing in the app.
        let mark = element("wordmark").frame, heading = element("heading").frame
        XCTAssertLessThanOrEqual(mark.width, 200, "the mark small: \(mark)")
        XCTAssertEqual(mark.midX, 960, accuracy: 4, "the mark centred: \(mark)")
        XCTAssertLessThan(mark.maxY, heading.minY, "the mark over the heading")
        var above = heading
        // A field as drawn: its capsule on the screen (since M8 part 2 the text field sits inside it, 30 points from its ends).
        let shot = XCUIScreen.main.screenshot()
        for id in ["server", "email", "password", "sign in"] {
            let frame = id == "sign in" ? element(id).frame : (capsule(around: element(id).frame, in: shot) ?? .zero)
            XCTAssertEqual(frame.midX, 960, accuracy: 4, "\(id) centred: \(frame)")
            XCTAssertEqual(frame.width, 800, accuracy: 24, "\(id) the column's width: \(frame)")
            XCTAssertGreaterThan(frame.minY, above.maxY, "\(id) under what is above it: \(frame), \(above)")
            above = frame
        }
        XCTAssertLessThanOrEqual(above.maxY, 1020, "Sign In on the screen, inside its foot's margin: \(above)")
        // Sign In is the screen's one prominent button: Seerr's indigo with white words (the review's F3: in the form its
        // words were indigo on dark grey, 2.8:1).
        assertIndigoButton("sign in")
        signIn()
        waitForDiscover()
        XCTAssertTrue(tabBar.exists, "the tab bar over Discover")
        waitForTabOrder(["tab Discover", "tab Requests", "tab Search", "tab Account"], "the account's tab last")
    }

    func testWrongPasswordInSeerrsWords() {
        launchSignedOut()
        signIn(password: "wrong-password")
        let error = element("sign-in error")
        XCTAssertTrue(error.waitForExistence(timeout: 20), "an error under the form")
        XCTAssertEqual(error.label, "The email address or password is incorrect.")
        // Words under Sign In, not a row of a form (the review's F8: the server's answer was an eighth capsule).
        assertWordsUnder("sign-in error", "sign in")
        Thread.sleep(forTimeInterval: 1)
        shoot("signin-wrong-password")
        // Each field its column's size once the keyboard has gone (Server Address, where it opened, came back 82 high).
        assertFieldsAlike(["server", "email", "password"])
        // The server answered, so its title heads the form, in tvOS's verb (Seerr's "Sign in to Seerr" over Sign In was
        // two verbs on one screen: the review's S13).
        XCTAssertEqual(element("heading").label, "Sign in to Seerr")
        XCTAssertFalse(element("page Discover").exists)
    }

    func testUnreachableServerInSeerrsWords() {
        launchSignedOut()
        // Nothing listens on port 1.
        signIn(server: "http://localhost:1")
        let error = element("sign-in error")
        XCTAssertTrue(error.waitForExistence(timeout: 40), "an error under the form")
        XCTAssertEqual(error.label, "No Seerr server answered at that address.")
    }

    func testEmptyFormNamesEachField() {
        launchSignedOut()
        press(.down, times: 3)
        waitForFocus("sign in")
        press(.select)
        XCTAssertTrue(element("server error").waitForExistence(timeout: 10))
        // Short plain sentences saying what to type (the review's S14: Seerr's "You must provide…").
        XCTAssertEqual(element("server error").label, "Enter the server's address.")
        XCTAssertEqual(element("email error").label, "Enter an email address.")
        XCTAssertEqual(element("password error").label, "Enter a password.")
        // Each in red words under its own field, not a row of its own in a form, which drew it as one more capsule like
        // the fields (the review's F8).
        for field in ["server", "email", "password"] {
            assertWordsUnder("\(field) error", field)
        }
        XCTAssertLessThan(element("password error").frame.maxY, element("sign in").frame.minY, "the errors above Sign In")
        Thread.sleep(forTimeInterval: 1)
        shoot("signin-field-errors")
    }

    /// ATS (DECISIONS 167): a Seerr on the household's network by its local name (`.local`) over plain http is reached,
    /// with no ATS exception: ATS does not judge `.local` names (a public name over http is refused, -1022).
    func testLocalNameOverHttp() throws {
        let named = try XCTUnwrap(Fake.localNameAddress, "no Bonjour name for the Mac")
        XCTAssertTrue(named.contains(".local:"), named)
        launchSignedOut()
        signIn(server: named)
        waitForDiscover()
    }

    /// ATS (DECISIONS 167): a Seerr on the household's network by IP address over plain http is reached, its posters
    /// too, with no ATS exception: ATS does not judge IP addresses.
    func testLanAddressOverHttp() throws {
        let lan = try XCTUnwrap(Fake.lanAddress, "no LAN fake-seerr: the Mac has no LAN address")
        XCTAssertFalse(lan.contains("localhost") || lan.contains("127.0.0.1"), "the proof needs a LAN address, not \(lan)")
        Fake.reset(lan)
        launchSignedOut()
        signIn(server: lan)
        waitForDiscover()
        // The posters come over http from the same address, through its image proxy.
        let poster = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'card '")).firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 20), "a card on Discover")
    }
}

extension HitherUITestCase {
    /// [id] is a screen's prominent button, at rest: Seerr's indigo (`#4F46E5`) filling it, white words on it at 4.5:1
    /// at least, read off the screen (the review's F3 and X10's first rule).
    func assertIndigoButton(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        let button = element(id)
        XCTAssertTrue(button.waitForExistence(timeout: 10), id, file: file, line: line)
        XCTAssertFalse(hasFocus(id), "\(id) at rest", file: file, line: line)
        // Inside the capsule's round ends.
        let frame = button.frame.insetBy(dx: button.frame.height / 2, dy: 8).integral
        guard let colours = colours(in: frame) else { return XCTFail("\(id) not on the screen: \(button.frame)", file: file, line: line) }
        var counts: [Int: Int] = [:]
        var brightest = (0, 0, 0)
        for y in 0..<colours.height {
            for x in 0..<colours.width {
                let (r, g, b) = colours.rgb(x, y)
                counts[r << 16 | g << 8 | b, default: 0] += 1
                if Self.luminance(r, g, b) > Self.luminance(brightest.0, brightest.1, brightest.2) { brightest = (r, g, b) }
            }
        }
        let common = counts.max { $0.value < $1.value }?.key ?? 0
        let fill = (common >> 16 & 0xFF, common >> 8 & 0xFF, common & 0xFF)
        let indigo = (0x4F, 0x46, 0xE5)
        XCTAssertTrue(abs(fill.0 - indigo.0) <= 16 && abs(fill.1 - indigo.1) <= 16 && abs(fill.2 - indigo.2) <= 16,
                      "\(id) filled with Seerr's indigo (79, 70, 229): its commonest colour is \(fill)", file: file, line: line)
        let ratio = (Self.luminance(brightest.0, brightest.1, brightest.2) + 0.05) / (Self.luminance(fill.0, fill.1, fill.2) + 0.05)
        XCTAssertGreaterThanOrEqual(ratio, 4.5, "\(id)'s words \(brightest) on its fill \(fill): \(String(format: "%.1f", ratio)):1", file: file, line: line)
    }

    /// WCAG's relative luminance of an sRGB colour.
    static func luminance(_ r: Int, _ g: Int, _ b: Int) -> Double {
        func linear(_ c: Int) -> Double {
            let v = Double(c) / 255
            return v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    }

    /// [words] (an error) sits under [control] as plain words: below it and starting where it does, and in no row of a
    /// form (a cell), as it was when the sign-in was a `Form` (the review's F8).
    func assertWordsUnder(_ words: String, _ control: String, file: StaticString = #filePath, line: UInt = #line) {
        let text = element(words), above = element(control)
        XCTAssertTrue(text.waitForExistence(timeout: 10), words, file: file, line: line)
        XCTAssertGreaterThanOrEqual(text.frame.minY, above.frame.maxY, "\(words) under \(control): \(text.frame), \(above.frame)", file: file, line: line)
        XCTAssertLessThanOrEqual(text.frame.minY - above.frame.maxY, 40, "\(words) close under \(control): \(text.frame), \(above.frame)", file: file, line: line)
        XCTAssertEqual(text.frame.minX, above.frame.minX, accuracy: 40, "\(words) starting where \(control) does: \(text.frame), \(above.frame)", file: file, line: line)
        XCTAssertEqual(app.cells.containing(.any, identifier: words).count, 0, "\(words) a row of a form", file: file, line: line)
    }
}

/// The session in the Keychain: kept across a relaunch, renewed from the stored account when Seerr forgets it, and
/// gone with Sign Out.
final class SessionUITests: HitherUITestCase {
    func testSessionSurvivesRelaunch() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        app.terminate()
        launchAsLeft()
        waitForDiscover()
        XCTAssertFalse(element("server").exists, "no sign-in form after a relaunch")
    }

    func testSessionRenewedWhenTheServerForgetsIt() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        app.terminate()
        // Seerr's own 403 for every session it had: the stored account signs in again on launch.
        Fake.set("/fake/expire")
        launchAsLeft()
        waitForDiscover()
        XCTAssertFalse(element("server").exists, "no sign-in form after the server forgot the session")
    }

    func testSignOutForgetsTheAccount() {
        launchSignedOut()
        signIn()
        waitForDiscover()
        // The account's tab ends the bar: Up from the rows, then Right to its end; Settings shows, Down into it.
        press(.up)
        waitForFocus("tab Discover")
        press(.right, times: 3)
        waitForFocus("tab Account")
        XCTAssertTrue(element("page Settings").waitForExistence(timeout: 10), "Settings behind the account's tab")
        // Down into it lands on About (Android's order, DECISIONS 203); Sign Out is the last row.
        press(.down)
        waitForFocus("about")
        pressUntilFocus(.down, "sign out")
        press(.select)
        // Sign Out asks first, in the system's alert (DECISIONS 194): Cancel has focus, Sign Out confirms.
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 10), "Sign Out asks first")
        XCTAssertTrue(element("confirm sign out").waitForExistence(timeout: 5), "the alert's Sign Out")
        waitForFocus("cancel sign out", "Cancel has focus (DECISIONS 80)")
        // Seerr's own account: signing in again takes its password (the words go by the way in, DECISIONS 282).
        XCTAssertTrue(alertSays("Signing in again takes this account's password, typed with the remote."), "the password, for a Seerr account")
        Thread.sleep(forTimeInterval: 1)
        shoot("settings-sign-out")
        // tvOS 26's alert sets them side by side, Cancel first, as Android's panel.
        press(.right)
        waitForFocus("confirm sign out", timeout: 3, "Sign Out right of Cancel")
        press(.select)
        XCTAssertTrue(app.textFields["server"].waitForExistence(timeout: 20), "the sign-in form after Sign Out")
        app.terminate()
        launchAsLeft()
        XCTAssertTrue(app.textFields["server"].waitForExistence(timeout: 20), "still signed out after a relaunch")
        XCTAssertFalse(element("page Discover").exists)
    }
}
