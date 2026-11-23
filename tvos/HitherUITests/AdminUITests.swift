import XCTest

extension HitherUITestCase {
    /// fake-seerr's made-up approver (Manage Requests only) and the PIN the tests choose.
    static let approverEmail = "approver@example.invalid"
    static let approverPassword = "approver-password"
    static let pin = "2580"

    /// From Discover, the account's tab, then Settings' Admin Account row.
    func openAdminAccount(file: StaticString = #filePath, line: UInt = #line) {
        openTab("Account", steps: 3, page: "Settings", file: file, line: line)
        press(.down)
        pressUntilFocus(.down, "admin account", file: file, line: line)
        press(.select)
        XCTAssertTrue(element("page Admin").waitForExistence(timeout: 10), "the Admin Account page", file: file, line: line)
    }

    /// tvOS's digit entry's keys, left to right, as its row draws them (`DigitEntry`).
    private static let digitKeys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "delete"]

    /// The digit entry's key with focus: the one the system draws lifted (its frame the row's largest).
    func focusedDigitKey() -> String? {
        let keys = app.keyboards.firstMatch.keys.allElementsBoundByIndex
        if let focused = keys.first(where: { $0.hasFocus }) { return focused.label }
        return keys.max { $0.frame.height < $1.frame.height }?.label
    }

    /// Types [pin] on tvOS's digit entry by the remote, as a person would: along its row of keys to each digit, then
    /// Select. (`typeText` cannot reach it: XCUITest finds no keyboard focus on the system's digits keyboard.)
    func typePin(_ pin: String, file: StaticString = #filePath, line: UInt = #line) {
        for character in pin { pressDigitKey(String(character), file: file, line: line) }
    }

    /// Moves along the digit entry's row of keys to [key] ("0" to "9", "delete") and selects it.
    func pressDigitKey(_ key: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10), "the digit entry's keys", file: file, line: line)
        guard let from = focusedDigitKey().flatMap(Self.digitKeys.firstIndex(of:)), let to = Self.digitKeys.firstIndex(of: key) else {
            return XCTFail("no key with focus on the digit entry, or no key \(key): \(focusedId)", file: file, line: line)
        }
        press(to > from ? .right : .left, times: abs(to - from))
        let deadline = Date().addingTimeInterval(3)
        while Date() < deadline, focusedDigitKey() != key { Thread.sleep(forTimeInterval: 0.2) }
        XCTAssertEqual(focusedDigitKey(), key, "focus on the key \(key)", file: file, line: line)
        press(.select)
    }

    /// The digit entry's places, left to right (each a static text the height of the place).
    var places: [XCUIElement] {
        element("pin entry").staticTexts.allElementsBoundByIndex
            .filter { $0.frame.height > 120 && $0.frame.width < 200 }
            .sorted { $0.frame.minX < $1.frame.minX }
    }

    /// What the digit entry's places show, left to right: "•" for a typed digit, "" for none.
    var placeWords: [String] { places.map(\.label) }

    /// How many places show a typed digit.
    var typedPlaces: Int { placeWords.filter { $0 == "•" }.count }

    /// Waits for the digit entry's title, or the line under it, to read [words] (the system draws them: no identifiers).
    func waitForDigitEntry(_ words: String, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(app.staticTexts[words].waitForExistence(timeout: timeout),
                      "the digit entry reading \"\(words)\"; it reads \(digitEntryWords)", file: file, line: line)
    }

    /// Waits for a line on the digit entry starting with [words] (a countdown's).
    func waitForDigitEntry(containing words: String, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        let text = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", words)).firstMatch
        XCTAssertTrue(text.waitForExistence(timeout: timeout), "the digit entry reading \"\(words)…\"; it reads \(digitEntryWords)", file: file, line: line)
    }

    /// No point of [id] on the screen is near [colour] (each of red, green and blue within 24): words with no fill of
    /// that colour behind them.
    func assertNoColour(_ id: String, near colour: (Int, Int, Int), _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        let frame = element(id).frame.integral
        guard let colours = colours(in: frame) else { return XCTFail("\(id) not on the screen: \(frame)", file: file, line: line) }
        var found = 0
        for y in 0..<colours.height {
            for x in 0..<colours.width {
                let (r, g, b) = colours.rgb(x, y)
                if abs(r - colour.0) <= 24, abs(g - colour.1) <= 24, abs(b - colour.2) <= 24 { found += 1 }
            }
        }
        XCTAssertLessThan(found, colours.width * colours.height / 20, "\(id) on \(what): \(found) of \(colours.width * colours.height) points", file: file, line: line)
    }

    /// [id] is a destructive row at rest, as tvOS draws `Button(role: .destructive)`: red words on the system's neutral
    /// row, never a red fill (the review's F26, X10's second rule).
    func assertDestructiveRow(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertFalse(hasFocus(id), "\(id) at rest", file: file, line: line)
        let frame = element(id).frame.integral
        guard let colours = colours(in: frame) else { return XCTFail("\(id) not on the screen: \(frame)", file: file, line: line) }
        var counts: [Int: Int] = [:]
        var red = 0
        for y in 0..<colours.height {
            for x in 0..<colours.width {
                let (r, g, b) = colours.rgb(x, y)
                counts[r << 16 | g << 8 | b, default: 0] += 1
                if r > 150, r - g > 70, r - b > 70 { red += 1 }
            }
        }
        let common = counts.max { $0.value < $1.value }?.key ?? 0
        let fill = (common >> 16 & 0xFF, common >> 8 & 0xFF, common & 0xFF)
        XCTAssertFalse(fill.0 - fill.1 > 70 && fill.0 - fill.2 > 70, "\(id) is no red fill: its commonest colour is \(fill)", file: file, line: line)
        XCTAssertGreaterThan(red, 20, "\(id)'s words red (\(red) red points on \(fill))", file: file, line: line)
    }

    /// The words on the digit entry: its title and the line under it.
    var digitEntryWords: [String] {
        element("pin entry").staticTexts.allElementsBoundByIndex.map(\.label).filter { !$0.isEmpty }
    }

    /// Signs the approver in on Admin Account and sets [pin]: the form, the PIN twice, the account kept.
    func setUpApprover(pin: String = HitherUITestCase.pin, shots: Bool = false, file: StaticString = #filePath, line: UInt = #line) {
        openAdminAccount(file: file, line: line)
        waitForFocus("admin email", "focus arrives on the email field", file: file, line: line)
        if shots {
            Thread.sleep(forTimeInterval: 1)
            shoot("tvos-admin-form")
            // The focused field in the column as the one under it, its placeholder readable on it (Tom, M8 part 2).
            assertFocusedFieldInColumn("admin email", beside: "admin password", file: file, line: line)
        }
        press(.select)
        typeAndReturn(Self.approverEmail, "next")
        typeAndReturn(Self.approverPassword, "done")
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 10), "the keyboard closing after Done", file: file, line: line)
        waitForFocus("admin sign in", "Done lands on Sign In", file: file, line: line)
        if shots {
            Thread.sleep(forTimeInterval: 1)
            shoot("tvos-admin-form-typed")
            assertFieldsAlike(["admin email", "admin password"], file: file, line: line)
        }
        press(.select)
        waitForDigitEntry("Choose a 4-digit PIN", file: file, line: line)
        if shots {
            Thread.sleep(forTimeInterval: 1)
            shoot("tvos-admin-pin-choose")
        }
        typePin(pin, file: file, line: line)
        waitForDigitEntry("Enter the PIN again", file: file, line: line)
        typePin(pin, file: file, line: line)
        XCTAssertTrue(element("admin switch in").waitForExistence(timeout: 20), "the account kept", file: file, line: line)
        waitForFocus("admin switch in", "Switch to Approver focused", file: file, line: line)
    }

    /// Moves to an alert's [id] from Cancel: tvOS 26 sets an alert's buttons side by side, or, when a title is long
    /// ("Remove Admin Account"), one above the other with Cancel at the foot.
    func focusAlertButton(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        for button in [XCUIRemote.Button.right, .up, .down] where !hasFocus(id) { press(button) }
        waitForFocus(id, timeout: 3, file: file, line: line)
    }

    /// Switch to Approver from the Admin Account page: the PIN sheet, the right PIN, the admin active.
    func switchIn(pin: String = HitherUITestCase.pin, file: StaticString = #filePath, line: UInt = #line) {
        waitForFocus("admin switch in", file: file, line: line)
        press(.select)
        waitForDigitEntry("Switch to Approver", file: file, line: line)
        typePin(pin, file: file, line: line)
        XCTAssertTrue(element("pin entry").waitForNonExistence(timeout: 20), "the digit entry gone", file: file, line: line)
        waitForFocus("admin switch back", "Switch Back focused once in", file: file, line: line)
    }
}

/// The TV's admin behind a PIN (Android's M5, DECISIONS 91-94), with fake-seerr's made-up approver: setting it up, the PIN
/// and its tries, switching in and out with the Admin badge, the approval queue with Approve and Decline, the waiting
/// count, and removing it.
final class AdminUITests: HitherUITestCase {
    /// Settings › Admin Account: the approver signed in once, the PIN chosen and entered again, the account kept with its
    /// right; Menu back lands on the row.
    func testSetUpTheApprover() {
        launchSignedOut()
        signIn()
        setUpApprover(shots: true)
        XCTAssertTrue(element("admin name").label.contains("Approver"), element("admin name").label)
        XCTAssertEqual(element("admin role").label, "Manage Requests")
        XCTAssertTrue(element("admin remove").exists, "Remove Admin Account")
        // Settings' rows (the review's F22): who it is with its right, Switch to, Remove; the approver's person and shield
        // on the left.
        let page = element("page Admin")
        let rows = ["admin name", "admin switch in", "admin remove"].map { element($0, in: "page Admin").frame.minY }
        XCTAssertEqual(rows, rows.sorted(), "who, Switch to, Remove, in that order")
        assertSettingsLayout("page Admin", rows: ["admin remove"], mark: "admin mark", under: element("page title", in: "page Admin").frame)
        XCTAssertTrue(page.exists)
        Thread.sleep(forTimeInterval: 1)
        // The right in plain secondary words: Seerr's indigo role pill is gone (the review's S30).
        assertNoColour("admin role", near: (0x4F, 0x46, 0xE5), "an indigo pill")
        // Remove by its role, red words on the system's row, never a red fill (the review's F26).
        assertDestructiveRow("admin remove")
        shoot("tvos-admin-kept")
        press(.menu)
        waitForFocus("admin account", "Menu back to the row")
    }

    /// The PIN is chosen on tvOS's own digit entry, full screen (the review's F14, F17: a hand-made pad, its keys
    /// squeezed to capsules on the page): its title and the line under it, the system's digits keyboard, and a place per
    /// digit that shows a dot once it is typed, never the digit. The keyboard's delete takes one off; Menu leaves,
    /// keeping nothing.
    func testThePinOnTheSystemsDigitEntry() {
        launchSignedOut()
        signIn()
        openAdminAccount()
        waitForFocus("admin email")
        press(.select)
        typeAndReturn(Self.approverEmail, "next")
        typeAndReturn(Self.approverPassword, "done")
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 10))
        waitForFocus("admin sign in")
        press(.select)
        waitForDigitEntry("Choose a 4-digit PIN")
        waitForDigitEntry("It is asked for each time the TV switches to Approver.")
        let entry = element("pin entry").frame
        XCTAssertEqual(entry.size, app.frame.size, "the whole screen: \(entry)")
        let keys = app.keyboards.firstMatch.keys.allElementsBoundByIndex.map(\.label)
        XCTAssertEqual(keys, ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "delete"], "the system's digits keyboard")
        XCTAssertEqual(typedPlaces, 0, "nothing typed yet")
        Thread.sleep(forTimeInterval: 1)
        shoot("tvos-admin-pin-choose")
        // The places are drawn: darker wells on the blur's grey (its grey varies with what is under it: #121212 and
        // #0F0F0F were read, the places 4 to 9 darker). Embedded without its blur the system's entry drew them with
        // nothing to draw on, black on black.
        let place = places.last?.frame ?? .zero
        let shot = XCUIScreen.main.screenshot()
        let well = colours(in: CGRect(x: place.midX.rounded(), y: place.midY.rounded(), width: 1, height: 1), of: shot)?.rgb(0, 0)
        let ground = colours(in: CGRect(x: place.midX.rounded(), y: (place.maxY + 40).rounded(), width: 1, height: 1), of: shot)?.rgb(0, 0)
        if let well, let ground {
            XCTAssertGreaterThanOrEqual(ground.0, 8, "the blur's grey under the places, not black (\(ground))")
            XCTAssertLessThan(well.0, ground.0, "a place (\(well)) darker than the ground under it (\(ground)): \(place)")
        } else {
            XCTFail("no pixels at the last place \(place)")
        }
        typePin("25")
        XCTAssertEqual(typedPlaces, 2, "a dot in two places")
        XCTAssertEqual(placeWords.filter { $0 != "•" && !$0.isEmpty }, [], "the digits never drawn: \(placeWords)")
        XCTAssertFalse(app.staticTexts["2"].exists || app.staticTexts["5"].exists, "the digits never drawn")
        shoot("tvos-admin-pin-two")
        pressDigitKey("delete")
        XCTAssertEqual(typedPlaces, 1, "the keyboard's delete takes one off")
        // Menu leaves at once, as tvOS's own digit entry does, keeping nothing.
        press(.menu)
        waitForFocus("admin account", "Menu leaves the page, keeping nothing")
        press(.select)
        XCTAssertTrue(element("admin email").waitForExistence(timeout: 10), "nothing kept: the form again")
    }

    /// Four wrong PINs cost nothing but the try; the fifth makes a 30-second wait, during which the pad takes nothing;
    /// after it the right PIN switches in.
    func testAWrongPinAndItsWait() {
        launchSignedOut()
        signIn()
        setUpApprover()
        press(.select)
        waitForDigitEntry("Switch to Approver")
        waitForDigitEntry("Enter the PIN.")
        typePin("1111")
        waitForDigitEntry("That PIN is not right. 9 tries left.")
        XCTAssertTrue(element("pin entry").exists, "the digit entry stays for the next try")
        Thread.sleep(forTimeInterval: 1)
        shoot("tvos-admin-pin-wrong")
        for left in [8, 7, 6] {
            typePin("1111")
            waitForDigitEntry("That PIN is not right. \(left) tries left.")
        }
        typePin("1111")
        waitForDigitEntry(containing: "Too many wrong PINs. Try again in 0:")
        // A whole PIN typed during the wait is cleared and dropped unchecked, and after the wait no try was spent (still 5
        // left; a checked one would leave 4). Focus is on 1 after the last try, so four Selects type 1111 at once: walking
        // the row for another PIN, or pausing for a screenshot first, outlasted the 30 seconds under load.
        XCTAssertEqual(focusedDigitKey(), "1", "focus on 1 after the last try")
        press(.select, times: 4)
        XCTAssertTrue(digitEntryWords.contains { $0.hasPrefix("Too many wrong PINs. Try again in 0:") }, "typed during the wait: \(digitEntryWords)")
        Thread.sleep(forTimeInterval: 1)
        XCTAssertEqual(typedPlaces, 0, "a PIN typed during the wait is cleared: \(placeWords)")
        XCTAssertTrue(element("pin entry").exists, "still on the digit entry: nothing switched in during the wait")
        shoot("tvos-admin-pin-wait")
        waitForDigitEntry("That PIN is not right. 5 tries left.", timeout: 45)
        typePin(Self.pin)
        XCTAssertTrue(element("pin entry").waitForNonExistence(timeout: 20), "the right PIN after the wait switches in")
        waitForFocus("admin switch back")
    }

    /// The tenth wrong PIN in a row removes the account from the TV: the sheet closes over the form, which says why
    /// (a debug build starts the account at nine wrong ones, `HITHER_ADMIN_TRIES`; the unit tests walk the waits).
    func testTheTenthWrongPinForgetsTheAccount() {
        app.launchEnvironment["HITHER_ADMIN_TRIES"] = "9"
        launchSignedOut()
        signIn()
        setUpApprover()
        press(.select)
        waitForDigitEntry("Switch to Approver")
        typePin("1111")
        XCTAssertTrue(element("pin entry").waitForNonExistence(timeout: 20), "the digit entry closes")
        waitForLabel("admin gone", equals: "Too many wrong PINs: the TV forgot the admin account. Sign in again with its password to choose a new PIN.")
        XCTAssertTrue(element("admin email").exists, "the form again")
        shoot("tvos-admin-gone")
        // Gone from the Keychain too: a new start finds no account.
        app.terminate()
        launchAsLeft()
        openAdminAccount()
        XCTAssertTrue(element("admin email").waitForExistence(timeout: 10), "no account kept")
    }

    /// Switching in turns the account's tab into the Admin badge (DECISIONS 183) and Settings into the approver's, with
    /// Switch Back first; Switch Back, and Hither leaving the front, go back to the family.
    func testSwitchInAndOutWithTheAdminBadge() {
        launchSignedOut()
        signIn()
        setUpApprover()
        switchIn()
        shoot("tvos-admin-active")
        press(.menu)
        waitForFocus("admin account")
        press(.menu)
        waitForFocus("tab Account")
        waitForTabOrder(["tab Discover", "tab Requests", "tab Search", "tab Admin"], "the account's tab reads Admin")
        let account = element("tab Account")
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline, account.identifier.isEmpty { Thread.sleep(forTimeInterval: 0.5) }
        XCTAssertEqual(account.identifier, "person.badge.shield.checkmark", "the person-and-shield symbol")
        shoot("tvos-admin-account-tab")
        press(.down)
        waitForFocus("switch back", "Switch Back first and focused")
        XCTAssertEqual(element("switch back").label, "Switch Back to User 8")
        XCTAssertTrue(element("signed in as").label.contains("Approver"), "Settings shows the approver: \(element("signed in as").label)")
        shoot("tvos-admin-settings")
        press(.select)
        waitForFocus("about", "focus moves to About as the row goes")
        XCTAssertFalse(element("switch back").exists, "the row gone")
        waitForTabOrder(["tab Discover", "tab Requests", "tab Search", "tab Account"], "the family's again")

        // In again, then Hither leaves the front: the family's when it comes back.
        pressUntilFocus(.down, "admin account")
        press(.select)
        switchIn()
        XCUIRemote.shared.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackgroundSuspended, timeout: 10) || app.state == .runningBackground, "Hither left the front")
        app.activate()
        XCTAssertTrue(element("page Admin").waitForExistence(timeout: 10))
        XCTAssertTrue(element("admin switch in").waitForExistence(timeout: 10), "the family's again: Switch to Approver")
        press(.menu)
        press(.menu)
        waitForTabOrder(["tab Discover", "tab Requests", "tab Search", "tab Account"], "no Admin badge after leaving the front")
    }

    /// Without a press on the remote the admin goes back to the family after the idle time; a press anywhere starts it
    /// again (the window's `PressWatcher`). A debug build's `HITHER_ADMIN_IDLE_MS` makes 10 minutes 20 seconds.
    func testThePressesKeepTheAdminAndIdleEndsIt() {
        app.launchEnvironment["HITHER_ADMIN_IDLE_MS"] = "20000"
        launchSignedOut()
        signIn()
        setUpApprover()
        switchIn()
        // Presses for 30 seconds, more than the idle time: Right and Left between the two buttons.
        let pressing = Date().addingTimeInterval(30)
        while Date() < pressing {
            press(.right)
            Thread.sleep(forTimeInterval: 4)
            press(.left)
            Thread.sleep(forTimeInterval: 4)
        }
        XCTAssertTrue(element("admin switch back").exists, "presses kept the admin active")
        // No press: back to the family.
        XCTAssertTrue(element("admin switch in").waitForExistence(timeout: 40), "idle: back to the family")
        XCTAssertFalse(element("admin switch back").exists)
    }

    /// Waiting for Approval in the Requests page's side list opens the queue after the PIN, as the Library's split: Tom's
    /// three filters, the requests as lockups, focus on the first to decide. Select on one asks what to do (Approve,
    /// Decline, Open Title); Approve moves focus on and the lockup reads its new status; Decline asks first, Cancel keeps
    /// it, and the decision goes under the approver's name. The count follows.
    func testApproveAndDecline() {
        Fake.set("/fake/account?autoapprove=0")
        Fake.set("/fake/ask?title=movie:354912")
        Fake.set("/fake/ask?title=tv:1421")
        Fake.set("/fake/ask?title=movie:1423191")
        launchSignedOut()
        signIn()
        setUpApprover()
        press(.menu)
        waitForFocus("admin account")
        press(.menu)
        waitForFocus("tab Account")
        press(.left, times: 2)
        waitForFocus("tab Requests")
        // The row and its count, in place of the yellow-clock button over My Requests (the review's S33).
        waitForValue("waiting", "3")
        XCTAssertEqual(element("waiting").label, "Waiting for Approval")
        press(.down)
        waitForFocus("side my requests")
        press(.down)
        waitForFocus("waiting", "the way in is the side list's second row")
        shoot("tvos-waiting")
        press(.select)
        waitForDigitEntry("Switch to Approver")
        typePin(Self.pin)
        XCTAssertTrue(element("page Approvals").waitForExistence(timeout: 20), "the queue")
        // Newest first: Resident Evil, Modern Family, Coco.
        waitForRows("approval request:", ["Resident Evil", "Modern Family", "Coco"], "everyone's pending requests")
        let ids = rows("approval request:").map { String($0.id.dropFirst("approval ".count)) }
        let lockups = ids.map { "approval \($0)" }
        waitForFocus(lockups[0], "focus arrives on the first request to decide")
        // "33 seconds ago by User 8" is wider than a poster: the line keeps who asked, and VoiceOver reads the whole.
        XCTAssertTrue(inside(lockups[0], "card when").label.hasSuffix("User 8"), "who asked, and when where it fits: \(inside(lockups[0], "card when").label)")
        XCTAssertTrue((element(lockups[0]).value as? String ?? "").contains(" ago by User 8"), "VoiceOver reads when and who")
        // Seerr's badge for a film's request is its copy's status: Resident Evil is in the library already.
        XCTAssertTrue(inside(lockups[0], "status Available").exists, "Resident Evil, in the library")
        XCTAssertTrue(inside(lockups[1], "status Pending").exists, "Modern Family waits")
        // Tom's three filters (D3), the side list's rows, on Pending: not Seerr's nine pills.
        let filters = ["request filter pending", "request filter approved", "request filter all"]
        XCTAssertEqual(filters.map { element($0).label }, ["Pending", "Approved", "All"])
        XCTAssertEqual(filters.map { element($0).value as? String ?? "" }, ["selected", "", ""], "on Pending")
        let tops = filters.map { element($0).frame.minY }
        XCTAssertTrue(tops[0] < tops[1] && tops[1] < tops[2], "one under another, not side by side: \(tops)")
        for gone in ["completed", "processing", "failed", "available", "unavailable", "deleted"] {
            XCTAssertFalse(element("request filter \(gone)").exists, "no \(gone) filter")
        }
        shoot("tvos-approvals")
        // The approver's symbol draws 4 points in from the margin, where the list under it starts (its bearing).
        assertUnderTheTitle(element("approvals as", in: "page Approvals"), assertPageTitle("Requests", in: "page Approvals"), "the approver the queue acts as", inset: 4)
        assertLibrarySplit(side: "request filters", lockups: lockups, top: "approvals as")

        // Select asks what to do; Open Title opens the title's page, and Menu comes back to the lockup.
        press(.select)
        XCTAssertTrue(element("approve \(ids[0])").waitForExistence(timeout: 5), "the dialog")
        XCTAssertTrue(element("decline \(ids[0])").exists && element("open title \(ids[0])").exists, "Approve, Decline and Open Title")
        shoot("tvos-approval-dialog")
        pressUntilFocus(.down, "open title \(ids[0])")
        press(.select)
        XCTAssertTrue(element("title actions").waitForExistence(timeout: 20), "the title's page")
        press(.menu)
        waitForFocus(lockups[0], "Menu back to the lockup")

        // Approve: focus moves on to the next request to decide, and the decided one is no longer pending: that is what
        // it did, and nothing toasts it (Tom's D4).
        press(.select)
        XCTAssertTrue(element("approve \(ids[0])").waitForExistence(timeout: 5))
        waitForFocus("approve \(ids[0])", "Approve first in the dialog")
        press(.select)
        waitForFocus(lockups[1], "focus on to the next request")
        XCTAssertFalse(inside(lockups[0], "card when").label.hasPrefix("Approving"), "decided: \(inside(lockups[0], "card when").label)")
        assertNoToast("an approval")
        shoot("tvos-approved")

        // Decline asks first; Cancel keeps it.
        press(.select)
        XCTAssertTrue(element("decline \(ids[1])").waitForExistence(timeout: 5))
        pressUntilFocus(.down, "decline \(ids[1])")
        press(.select)
        XCTAssertTrue(element("confirm decline").waitForExistence(timeout: 5), "Decline asks first")
        waitForFocus("cancel decline", "Cancel has focus")
        shoot("tvos-decline-question")
        press(.select)
        waitForFocus(lockups[1], "back on the lockup, nothing declined")
        XCTAssertTrue(inside(lockups[1], "status Pending").exists, "still pending")
        press(.select)
        XCTAssertTrue(element("decline \(ids[1])").waitForExistence(timeout: 5))
        pressUntilFocus(.down, "decline \(ids[1])")
        press(.select)
        XCTAssertTrue(element("confirm decline").waitForExistence(timeout: 5))
        focusAlertButton("confirm decline")
        press(.select)
        waitForFocus(lockups[2], "focus on to Coco")
        XCTAssertTrue(inside(lockups[1], "status Declined").waitForExistence(timeout: 5), "Modern Family declined")

        // The last one: focus goes to the chosen filter, and Coco (not in the library) reads Requested.
        press(.select)
        XCTAssertTrue(element("approve \(ids[2])").waitForExistence(timeout: 5))
        waitForFocus("approve \(ids[2])")
        press(.select)
        waitForFocus("request filter pending", "none left to decide: focus on the chosen filter")
        XCTAssertTrue(inside(lockups[2], "status Requested").waitForExistence(timeout: 5), "Coco approved: Requested")
        // Nothing left to decide: Select on a decided request opens its title, no dialog.
        press(.right)
        waitForFocus(lockups[0])
        press(.select)
        XCTAssertTrue(element("title actions").waitForExistence(timeout: 20), "a decided request opens its title")
        press(.menu)
        waitForFocus(lockups[0])

        // Approved lists the two approved here, newest first.
        press(.left)
        waitForFocus("request filter pending")
        press(.down)
        waitForFocus("request filter approved")
        press(.select)
        waitForValue("request filter approved", "selected")
        waitForRows("approval request:", ["Resident Evil", "Coco"], "the approved requests")
        waitForFocus("request filter approved", "focus stays on the filter chosen")

        // Menu back to My Requests: none left waiting, so the row's count goes; the way in stays while an admin is kept.
        press(.menu)
        XCTAssertTrue(element("page Requests").waitForExistence(timeout: 10))
        waitForValue("waiting", "")
    }

    /// Without Manage Requests and no admin kept, no Waiting for Approval; with the right (the family's own, read live
    /// with the look), the row is its count in words only, dimmed and not a way in.
    func testTheWaitingCountOnlyWithTheRight() {
        Fake.set("/fake/requests?others=2")
        app.launchEnvironment["HITHER_LOOK_DUE"] = "always"
        launchSignedOut()
        signIn()
        openTab("Requests", steps: 1, page: "Requests")
        Thread.sleep(forTimeInterval: 3)
        XCTAssertFalse(element("waiting").exists, "no count without the right")
        Fake.set("/fake/account?manage=1")
        app.terminate()
        launchAsLeft()
        openTab("Requests", steps: 1, page: "Requests")
        waitForValue("waiting", "2")
        XCTAssertEqual(element("waiting").label, "Waiting for Approval")
        XCTAssertFalse(element("waiting").isEnabled, "words only: no admin kept, no way in")
        press(.down)
        waitForFocus("side my requests")
        press(.down)
        Thread.sleep(forTimeInterval: 1)
        XCTAssertFalse(hasFocus("waiting"), "no stop for focus")
        shoot("tvos-waiting-words")
    }

    /// Remove asks first (Cancel keeps it, focus back on Remove), then signs the session out and forgets the account.
    func testRemoveTheAdminAccount() {
        launchSignedOut()
        signIn()
        setUpApprover()
        // Remove is the row under Switch to.
        press(.down)
        waitForFocus("admin remove")
        press(.select)
        XCTAssertTrue(element("confirm remove admin").waitForExistence(timeout: 5), "Remove asks first")
        waitForFocus("cancel remove admin")
        shoot("tvos-admin-remove-question")
        press(.select)
        waitForFocus("admin remove", "back on Remove, kept")
        press(.select)
        XCTAssertTrue(element("confirm remove admin").waitForExistence(timeout: 5))
        focusAlertButton("confirm remove admin")
        press(.select)
        XCTAssertTrue(element("admin email").waitForExistence(timeout: 10), "the form again: nothing kept")
    }

    /// The admin goes with the family's account: signing out takes it too.
    func testSigningOutTakesTheAdmin() {
        launchSignedOut()
        signIn()
        setUpApprover()
        press(.menu)
        waitForFocus("admin account")
        pressUntilFocus(.down, "sign out")
        press(.select)
        XCTAssertTrue(element("confirm sign out").waitForExistence(timeout: 5))
        press(.right)
        waitForFocus("confirm sign out")
        press(.select)
        XCTAssertTrue(app.textFields["server"].waitForExistence(timeout: 20), "signed out")
        signIn()
        openAdminAccount()
        XCTAssertTrue(element("admin email").waitForExistence(timeout: 10), "no admin kept after the family signed out")
    }
}
