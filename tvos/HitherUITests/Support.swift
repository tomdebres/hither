import UIKit
import XCTest

/// The fake-seerr the run started (`scripts/tvos-app`): its address from the simulator, and its switches.
enum Fake {
    /// `http://localhost:<port>`: the simulator reaches the Mac at localhost.
    static var address: String {
        guard let value = ProcessInfo.processInfo.environment["HITHER_FAKE_SEERR"], !value.isEmpty else {
            fatalError("HITHER_FAKE_SEERR is not set: run the UI tests through scripts/tvos-app")
        }
        return value
    }

    /// A second fake-seerr on the Mac's LAN address (`http://<address>:<port>`), for the ATS proof; nil when the run
    /// has none (no LAN address found).
    static var lanAddress: String? {
        ProcessInfo.processInfo.environment["HITHER_FAKE_SEERR_LAN"].flatMap { $0.isEmpty ? nil : $0 }
    }

    /// The localhost fake by the Mac's Bonjour name (`http://<name>.local:<port>`); nil when the run has none.
    static var localNameAddress: String? {
        ProcessInfo.processInfo.environment["HITHER_FAKE_SEERR_LOCAL_NAME"].flatMap { $0.isEmpty ? nil : $0 }
    }

    /// The family's made-up account (any email but the approver's) and its password.
    static let email = "family@example.invalid"
    static let password = "right-password"

    /// POSTs one of fake-seerr's `/fake/...` switches and waits for the answer.
    static func set(_ path: String, on base: String = address) {
        var request = URLRequest(url: URL(string: base + path)!)
        request.httpMethod = "POST"
        let done = XCTestExpectation(description: path)
        var failure: String?
        URLSession.shared.dataTask(with: request) { _, response, error in
            if let error { failure = "\(error)" }
            else if let status = (response as? HTTPURLResponse)?.statusCode, status != 200 { failure = "status \(status)" }
            done.fulfill()
        }.resume()
        XCTAssertEqual(XCTWaiter.wait(for: [done], timeout: 10), .completed, "fake-seerr \(path)")
        XCTAssertNil(failure, "fake-seerr \(path)")
    }

    /// Every request and switch back to as recorded (sessions are kept).
    static func reset(_ base: String = address) { set("/fake/reset", on: base) }
}

/// Driving Hither with the Siri Remote.
@MainActor
class HitherUITestCase: XCTestCase {
    let app = XCUIApplication()
    let remote = XCUIRemote.shared

    override func setUp() async throws {
        continueAfterFailure = false
        Fake.reset()
        // A Plex sign-in asks fake-seerr's fake plex.tv, never the real one (M8): a debug build's HITHER_PLEX_TV.
        app.launchEnvironment["HITHER_PLEX_TV"] = Fake.address + "/plextv"
    }

    /// Launches Hither signed out: its Keychain items cleared first (a debug build's `-hither-reset`).
    func launchSignedOut() {
        app.launchArguments = ["-hither-reset"]
        app.launch()
        XCTAssertTrue(app.textFields["server"].waitForExistence(timeout: 20), "the sign-in form")
    }

    /// Launches Hither as it was left (a relaunch). [slowMotion] runs every animation on its screen that many times
    /// slower (a debug build's `-hither-slow-motion`), so a screenshot can land in the middle of one.
    func launchAsLeft(slowMotion: Int? = nil) {
        app.launchArguments = slowMotion.map { ["-hither-slow-motion", "\($0)"] } ?? []
        app.launch()
    }

    /// The first element with [id], whatever its type. "tab <name>" is the tab bar's tab whose words start with the name
    /// (the system's tab buttons carry their words, not the label's identifier): "tab Requests", "tab Account".
    func element(_ id: String) -> XCUIElement {
        if id.hasPrefix("tab ") { return tab(String(id.dropFirst(4))) }
        return app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// The tab bar's tab whose words are [name], or start with "[name], " ("Requests, 2 changes to your requests"); the
    /// account's ("Account", or "Admin" while the TV's admin is active) as the bar's last tab, where it always is. Since
    /// polish part 7 it draws the person symbol alone (the review's F23): its label is the symbol's spoken name
    /// ("account") until focus has been on it, the account's name after, and the bar gives it the symbol's identifier
    /// only once focus has been on the bar.
    func tab(_ name: String) -> XCUIElement {
        if name == "Account" || name == "Admin" {
            let tabs = tabBar.buttons.allElementsBoundByIndex
            return tabs.max { $0.frame.minX < $1.frame.minX } ?? tabBar.buttons.firstMatch
        }
        return tabBar.buttons.matching(NSPredicate(format: "label == %@ OR label BEGINSWITH %@", name, name + ", ")).firstMatch
    }

    /// A tab button as the tests name it: "tab " and its words up to a comma ("tab Requests"); the bar's last is the
    /// account's, "tab Account", or "tab Admin" while it reads Admin (its label otherwise changes as focus visits it).
    private static func tabName(label: String, last: Bool) -> String {
        if last { return label == "Admin" || label.hasPrefix("Admin, ") ? "tab Admin" : "tab Account" }
        return "tab " + (label.components(separatedBy: ", ").first ?? label)
    }

    /// The tab bar's tabs, left to right, as the tests name them ("tab Discover", ..., "tab Account").
    var tabOrder: [String] {
        let tabs = tabBar.buttons.allElementsBoundByIndex.map { (x: $0.frame.minX, label: $0.label) }.sorted { $0.x < $1.x }
        return tabs.enumerated().map { Self.tabName(label: $0.element.label, last: $0.offset == tabs.count - 1) }
    }

    /// The account signed in is [name] (from Discover's rows): the account's tab draws its symbol alone since polish part
    /// 7, so its name is what VoiceOver reads once focus has been on it, and what Settings behind it shows.
    func assertSignedInAs(_ name: String, file: StaticString = #filePath, line: UInt = #line) {
        press(.up)
        waitForFocus("tab Discover", file: file, line: line)
        for _ in 0..<5 where !hasFocus("tab Account") { press(.right) }
        waitForFocus("tab Account", file: file, line: line)
        let row = element("signed in as")
        XCTAssertTrue(row.waitForExistence(timeout: 10), "Settings behind the account's tab", file: file, line: line)
        XCTAssertTrue(row.label.contains(name), "Settings shows \(name): \(row.label)", file: file, line: line)
        let account = element("tab Account"), deadline = Date().addingTimeInterval(10)
        while Date() < deadline, account.label != name { Thread.sleep(forTimeInterval: 0.5) }
        XCTAssertEqual(account.label, name, "VoiceOver's words for the account's tab", file: file, line: line)
    }

    /// Waits for the tab bar to read [expected], left to right (the bar's tabs come a moment after Discover).
    func waitForTabOrder(_ expected: [String], _ message: String = "", timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(timeout)
        var order = tabOrder
        while Date() < deadline, order != expected {
            Thread.sleep(forTimeInterval: 0.5)
            order = tabOrder
        }
        XCTAssertEqual(order, expected, message, file: file, line: line)
    }

    /// Whether [id] has focus: the element itself, or (a card, whose button sits inside the element that carries the
    /// card's identifier and carries its words' identifiers itself) the focusable inside it.
    func hasFocus(_ id: String) -> Bool {
        if id.hasPrefix("tab ") { return element(id).hasFocus }
        // A title can be on two rows (Resident Evil is in Trending and Popular Movies): any card of it will do.
        let targets = app.descendants(matching: .any).matching(identifier: id)
        for target in targets.allElementsBoundByIndex where target.exists {
            if target.hasFocus || target.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists {
                return true
            }
        }
        // A list row: the cell around the control takes focus, and the control with the id is inside it.
        let focused = app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
        return focused.exists && focused.identifier.isEmpty && focused.descendants(matching: .any).matching(identifier: id).firstMatch.exists
    }

    /// What has focus, by identifier: a tab ("tab <name>"), a control's own, or the card (`card `, `recent `,
    /// `see more `) holding it.
    var focusedId: String {
        let focused = app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
        guard focused.exists else { return "(none)" }
        if focused.elementType == .button, tabBar.exists, tabBar.frame.contains(focused.frame) {
            let lastX = tabBar.buttons.allElementsBoundByIndex.map(\.frame.minX).max() ?? 0
            return Self.tabName(label: focused.label, last: focused.frame.minX >= lastX)
        }
        let cards = app.descendants(matching: .any).matching(NSPredicate(
            format: "identifier BEGINSWITH 'card ' OR identifier BEGINSWITH 'recent request:' OR identifier BEGINSWITH 'see more '"))
        let frame = focused.frame
        for card in cards.allElementsBoundByIndex where card.frame.contains(CGPoint(x: frame.midX, y: frame.midY)) {
            if card.descendants(matching: .any).matching(NSPredicate(format: "hasFocus == true")).firstMatch.exists { return card.identifier }
        }
        return focused.identifier.isEmpty ? "label: \(focused.label)" : focused.identifier
    }

    /// The system tab bar (DECISIONS 181).
    var tabBar: XCUIElement { app.tabBars.firstMatch }

    /// Waits for focus to be on [id].
    func waitForFocus(_ id: String, timeout: TimeInterval = 10, _ message: String = "", file: StaticString = #filePath, line: UInt = #line) {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if hasFocus(id) { return }
            Thread.sleep(forTimeInterval: 0.3)
        }
        XCTFail("expected focus on \(id), it is on \(focusedId). \(message)", file: file, line: line)
    }

    func press(_ button: XCUIRemote.Button, times: Int = 1) {
        for _ in 0..<times {
            remote.press(button)
            // The focus engine's move and its animation; quicker presses can land before the last one settled.
            Thread.sleep(forTimeInterval: 0.6)
        }
    }

    /// On tvOS's keyboard: types [text], then moves down to the keyboard's own [key] ("next" or "done") and selects
    /// it, as a person would. Next opens the following field's keyboard at once; Done closes it.
    func typeAndReturn(_ text: String, _ key: String) {
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10), "the keyboard")
        app.typeText(text)
        let button = app.buttons[key]
        XCTAssertTrue(button.waitForExistence(timeout: 5), "the keyboard's \(key)")
        for _ in 0..<6 where !button.hasFocus { press(.down) }
        XCTAssertTrue(button.hasFocus, "focus on the keyboard's \(key), it is on \(focusedId)")
        press(.select)
        Thread.sleep(forTimeInterval: 0.8)
    }

    /// Fills in the form from the Server field and presses Sign In: Select opens the keyboard, Next carries on to Email
    /// and Password, Done lands on Sign In. Never anything real: fake-seerr's made-up account only.
    func signIn(server: String = Fake.address, email: String = Fake.email, password: String = Fake.password) {
        waitForFocus("server")
        press(.select)
        typeAndReturn(server, "next")
        typeAndReturn(email, "next")
        typeAndReturn(password, "done")
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 10), "the keyboard closing after Done")
        waitForFocus("sign in", "Done on the password lands on Sign In")
        press(.select)
    }

    /// Waits for Discover's page (signed in).
    func waitForDiscover(timeout: TimeInterval = 30, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element("page Discover").waitForExistence(timeout: timeout), "Discover", file: file, line: line)
    }

    /// A pushed page's title, where `PageScaffold` puts it and tvOS's Settings puts its own: [words], centred on the
    /// screen, at tvOS's 60-point top margin (the review's X1 and F23: titles drawn left and white, or 160 points in and
    /// 217 down inside a doubled margin). Its frame, for what comes under it.
    @discardableResult
    func assertPageTitle(_ words: String, in page: String? = nil, file: StaticString = #filePath, line: UInt = #line) -> CGRect {
        let title = page.map { element("page title", in: $0) } ?? element("page title")
        XCTAssertTrue(title.waitForExistence(timeout: 10), "the page's title, \(words)", file: file, line: line)
        XCTAssertEqual(title.label, words, file: file, line: line)
        let frame = title.frame
        XCTAssertEqual(frame.midX, 960, accuracy: 3, "\(words) centred: \(frame)", file: file, line: line)
        XCTAssertEqual(frame.minY, 60, accuracy: 8, "\(words) at the top margin: \(frame)", file: file, line: line)
        return frame
    }

    /// [element], a pushed page's first line, starts at tvOS's left margin (80 points) and just under the page's title.
    /// [inset]: where a line starts with a symbol, the symbol's own side bearing (its frame reads where its ink starts).
    func assertUnderTheTitle(_ element: XCUIElement, _ title: CGRect, _ what: String, inset: CGFloat = 0, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: 10), what, file: file, line: line)
        let frame = element.frame
        XCTAssertEqual(frame.minX, 80 + inset, accuracy: 2, "\(what) 80 points from the left: \(frame)", file: file, line: line)
        XCTAssertGreaterThanOrEqual(frame.minY, title.maxY, "\(what) under the title (\(title)): \(frame)", file: file, line: line)
        XCTAssertLessThanOrEqual(frame.minY - title.maxY, 100, "\(what) close under the title (\(title)): \(frame)", file: file, line: line)
    }

    /// A tab's page has no heading of its own: the tab bar's selected tab names it (the review's F23: Requests, Issues
    /// and Settings each said their tab's word again 80 points under it). Nothing on [page] reads [word] alone.
    func assertNoHeading(_ page: String, _ word: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element(page).waitForExistence(timeout: 10), page, file: file, line: line)
        let repeats = element(page).descendants(matching: .staticText).matching(NSPredicate(format: "label == %@", word))
        XCTAssertEqual(repeats.count, 0, "\(page) repeats its tab as a heading: \(repeats.allElementsBoundByIndex.map(\.frame))", file: file, line: line)
        XCTAssertFalse(element("heading", in: page).exists, "\(page) has a heading", file: file, line: line)
    }

    /// [element], a tab page's first line, starts a shelf's room (36 points) under the tab bar's 157, where Discover's
    /// first shelf starts: a toast of one or two lines in the corner beside the bar ends above it at 179 (DECISIONS 252).
    func assertUnderTheBar(_ element: XCUIElement, _ what: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element.waitForExistence(timeout: 10), what, file: file, line: line)
        let frame = element.frame
        XCTAssertGreaterThanOrEqual(frame.minY, 191, "\(what) a shelf's room under the bar: \(frame)", file: file, line: line)
        XCTAssertLessThanOrEqual(frame.minY, 220, "\(what) close under the bar: \(frame)", file: file, line: line)
    }

    /// The first [id] inside the element [container].
    func element(_ id: String, in container: String) -> XCUIElement {
        element(container).descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// The toast [id] where tvOS has room for it (the review's F10): the top-right corner of the safe area (60 points
    /// down, its right edge 80 from the screen's), sized to its words, beside the tab bar's tabs when the bar shows, and
    /// never over what has focus.
    func assertToastInTheCorner(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        let toast = element(id)
        XCTAssertTrue(toast.waitForExistence(timeout: 10), "the toast \(id)", file: file, line: line)
        let frame = toast.frame
        XCTAssertEqual(frame.minY, 60, accuracy: 4, "\(id) at the safe area's top: \(frame)", file: file, line: line)
        XCTAssertEqual(frame.maxX, 1840, accuracy: 4, "\(id) at the safe area's right edge: \(frame)", file: file, line: line)
        XCTAssertLessThanOrEqual(frame.width, 421, "\(id) sized to its words, in the corner's room: \(frame)", file: file, line: line)
        if tabBar.exists, tabBar.frame.maxY > 0 {
            let tabsEnd = tabBar.buttons.allElementsBoundByIndex.map(\.frame.maxX).max() ?? 0
            XCTAssertGreaterThan(frame.minX, tabsEnd, "\(id) beside the tab bar, not over it: \(frame); the tabs end at \(tabsEnd)", file: file, line: line)
        }
        let focused = app.descendants(matching: .any).element(matching: NSPredicate(format: "hasFocus == true"))
        if focused.exists {
            XCTAssertFalse(frame.intersects(focused.frame), "\(id) not over what has focus (\(focusedId) at \(focused.frame)): \(frame)", file: file, line: line)
        }
    }

    /// The largest change of colour between neighbouring points down the screen's column [x], from [top] to [bottom]
    /// (points), read off one screenshot: a hard edge across a surface shows as a step. The sum of the red, green and blue
    /// differences (0-765) between points 4 apart, and where it is.
    func largestStepDown(x: CGFloat, from top: CGFloat, to bottom: CGFloat) -> (step: Int, at: CGFloat) {
        guard let image = XCUIScreen.main.screenshot().image.cgImage else { return (0, 0) }
        let scale = CGFloat(image.width) / app.frame.width
        let column = CGRect(x: (x * scale).rounded(), y: (top * scale).rounded(), width: 1, height: ((bottom - top) * scale).rounded())
        guard let strip = image.cropping(to: column) else { return (0, 0) }
        let height = strip.height
        var pixels = [UInt8](repeating: 0, count: 4 * height)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: 1, height: height, bitsPerComponent: 8, bytesPerRow: 4,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            context.draw(strip, in: CGRect(x: 0, y: 0, width: 1, height: height))
            return true
        }
        guard drawn else { return (0, 0) }
        // The context's first row is the strip's top.
        let every = max(1, Int(4 * scale))
        var largest = (step: 0, at: top)
        for row in stride(from: every, to: height, by: every) {
            let a = 4 * (row - every), b = 4 * row
            let step = (0..<3).map { abs(Int(pixels[a + $0]) - Int(pixels[b + $0])) }.reduce(0, +)
            if step > largest.step { largest = (step, top + CGFloat(row) / scale) }
        }
        return largest
    }

    /// The words a `StateView` [id] holds (its title, then its line), one string.
    func stateWords(_ id: String) -> String {
        element(id).descendants(matching: .staticText).allElementsBoundByIndex.map(\.label).joined(separator: " ")
    }

    /// The colours of the screen in [rect] (points), read off [shot] (a new screenshot if none): each point's red, green
    /// and blue, rows from the top. Nil if the screenshot could not be read or [rect] is not on it.
    func colours(in rect: CGRect, of shot: XCUIScreenshot? = nil) -> ScreenColours? {
        guard rect.width >= 1, rect.height >= 1, app.frame.contains(rect),
              let image = (shot ?? XCUIScreen.main.screenshot()).image.cgImage else { return nil }
        let scale = CGFloat(image.width) / app.frame.width
        let pixels = CGRect(x: rect.minX * scale, y: rect.minY * scale, width: rect.width * scale, height: rect.height * scale).integral
        // Drawn at one pixel a point.
        return ScreenColours(image, pixels: pixels, width: Int(rect.width.rounded()), height: Int(rect.height.rounded()))
    }

    /// A screenshot kept with the result, and written to `HITHER_SHOTS` when the run gives a directory.
    func shoot(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
        if let dir = ProcessInfo.processInfo.environment["HITHER_SHOTS"], !dir.isEmpty {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? shot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        }
    }
}

/// Colours read off a screenshot (`HitherUITestCase.colours(in:)`), a point at a time, rows from the top.
struct ScreenColours {
    let width: Int, height: Int
    let bytes: [UInt8]

    /// The [pixels] of [image] (a rect in its pixels, from its top left) drawn into [width] x [height]; nil if the rect
    /// is not on the image.
    init?(_ image: CGImage, pixels: CGRect, width: Int, height: Int) {
        guard width >= 1, height >= 1, let crop = image.cropping(to: pixels) else { return nil }
        var bytes = [UInt8](repeating: 0, count: 4 * width * height)
        let drawn: Bool = bytes.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 4 * width,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            else { return false }
            context.interpolationQuality = .none
            context.draw(crop, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return nil }
        self.width = width
        self.height = height
        self.bytes = bytes
    }

    /// The red, green and blue at (x, y), 0-255 each.
    func rgb(_ x: Int, _ y: Int) -> (Int, Int, Int) {
        // The bitmap's first row in memory is the image's top (as in `largestStepDown`): it read rows upside down
        // until polish part 4, taking it for the bottom.
        let at = 4 * (y * width + x)
        return (Int(bytes[at]), Int(bytes[at + 1]), Int(bytes[at + 2]))
    }

    /// The largest difference in any of red, green and blue between this and [other] at (x, y).
    func difference(_ other: ScreenColours, _ x: Int, _ y: Int) -> Int {
        let (a, b) = (rgb(x, y), other.rgb(x, y))
        return max(abs(a.0 - b.0), abs(a.1 - b.1), abs(a.2 - b.2))
    }
}

// MARK: - Polish part 5: alerts, sheets, reading, the banner

extension HitherUITestCase {
    /// Waits for the system's alert titled [title] and returns it (tvOS 26 draws an alert's title as its label).
    @discardableResult
    func waitForAlert(_ title: String, timeout: TimeInterval = 10, file: StaticString = #filePath, line: UInt = #line) -> XCUIElement {
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: timeout), "the alert \"\(title)\"", file: file, line: line)
        let titled = alert.label == title || alert.staticTexts.matching(NSPredicate(format: "label == %@", title)).firstMatch.exists
        XCTAssertTrue(titled, "the alert titled \"\(title)\": \(alert.label), \(alert.staticTexts.allElementsBoundByIndex.map(\.label))", file: file, line: line)
        return alert
    }

    /// Whether the alert showing says [words] (its message, or part of it).
    func alertSays(_ words: String) -> Bool {
        app.alerts.firstMatch.descendants(matching: .staticText).matching(NSPredicate(format: "label CONTAINS %@", words)).firstMatch.exists
    }

    /// The alert's buttons [first] and [second] on one line, side by side (6b's lesson: a long title stacks them).
    func assertSideBySide(_ alert: XCUIElement, _ first: String, _ second: String, file: StaticString = #filePath, line: UInt = #line) {
        // Each button is read twice (the alert's cell and the button in it): either will do.
        let a = alert.buttons[first].firstMatch.frame, b = alert.buttons[second].firstMatch.frame
        XCTAssertEqual(a.midY, b.midY, accuracy: 4, "\(first) and \(second) side by side: \(a), \(b)", file: file, line: line)
    }

    /// Nothing toasts what was just done (Tom's D4): no banner, no Seerr "… successfully!" anywhere on screen. The
    /// notices on returning are the banner's only use.
    func assertNoToast(_ what: String, file: StaticString = #filePath, line: UInt = #line) {
        Thread.sleep(forTimeInterval: 1)
        XCTAssertFalse(element("notice").exists, "no banner after \(what)", file: file, line: line)
        let said = app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'successfully' OR label CONTAINS ' cancelled.' OR label CONTAINS ' approved.' OR label CONTAINS ' declined.'"))
        XCTAssertEqual(said.count, 0, "no toast after \(what): \(said.allElementsBoundByIndex.map(\.label))", file: file, line: line)
    }

    /// A `FormSheet`'s title, the system's navigation title over its rows.
    func assertSheetTitle(_ title: String, file: StaticString = #filePath, line: UInt = #line) {
        let bar = app.navigationBars.matching(NSPredicate(format: "identifier == %@ OR label == %@", title, title)).firstMatch
        let words = app.navigationBars.staticTexts.matching(NSPredicate(format: "label == %@", title)).firstMatch
        XCTAssertTrue(bar.waitForExistence(timeout: 10) || words.exists,
                      "the sheet's navigation title \"\(title)\": \(app.navigationBars.allElementsBoundByIndex.map { "\($0.identifier) \($0.label)" })", file: file, line: line)
    }

    /// Where a `FormSheet` is drawn: from its navigation bar's top to its list's foot, as wide as its list.
    func sheetFrame() -> CGRect {
        let bar = app.navigationBars.firstMatch.frame
        let form = app.collectionViews.firstMatch.exists ? app.collectionViews.firstMatch.frame : app.tables.firstMatch.frame
        return CGRect(x: form.minX, y: bar.minY, width: form.width, height: form.maxY - bar.minY)
    }

    /// Waits for the reading view [id] (`ReadingView`) to have focus: a debug build says so in its value.
    func waitForReadingFocus(_ id: String, timeout: TimeInterval = 10, file: StaticString = #filePath, line: UInt = #line) {
        let text = element(id)
        XCTAssertTrue(text.waitForExistence(timeout: timeout), "the reading view \(id)", file: file, line: line)
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline, text.value as? String != "focused" { Thread.sleep(forTimeInterval: 0.3) }
        XCTAssertEqual(text.value as? String, "focused", "focus on the text \(id) (what has focus by XCUITest: \(focusedId))", file: file, line: line)
    }

    /// The reading view [id] (`ReadingView`) has focus and moves with the remote: Down turns its words (what it draws
    /// changes) and focus stays on it; Up brings the start back.
    func assertReadsWithTheRemote(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        let text = element(id)
        waitForReadingFocus(id, file: file, line: line)
        Thread.sleep(forTimeInterval: 0.5)
        let before = text.screenshot().pngRepresentation
        press(.down)
        Thread.sleep(forTimeInterval: 1)
        let after = text.screenshot().pngRepresentation
        XCTAssertNotEqual(before, after, "Down turns the text", file: file, line: line)
        XCTAssertEqual(text.value as? String, "focused", "focus stays on the text as it moves, not on a stop of its own", file: file, line: line)
        press(.up)
        Thread.sleep(forTimeInterval: 1)
        XCTAssertEqual(text.screenshot().pngRepresentation, before, "Up brings the start back", file: file, line: line)
    }

    /// The focused row [id] can be read: tvOS lights a focused row's platter, so its words must turn dark. A row drawn
    /// white on white has no dark point in its left half, where its words are (DECISIONS 270, 273: in a sheet on tvOS
    /// 26.5, a `Form`'s button rows, a `Toggle`'s label and a list picker's items).
    func assertFocusedRowReads(_ id: String, file: StaticString = #filePath, line: UInt = #line) {
        waitForFocus(id, file: file, line: line)
        Thread.sleep(forTimeInterval: 0.5) // the focus animation
        let row = element(id).frame
        let words = CGRect(x: row.minX + 16, y: row.minY + 12, width: row.width / 2 - 16, height: row.height - 24)
        guard let colours = colours(in: words) else { return XCTFail("\(id)'s colours in \(words)", file: file, line: line) }
        var dark = 0, light = 0
        for y in 0..<colours.height {
            for x in 0..<colours.width {
                let (r, g, b) = colours.rgb(x, y)
                if max(r, g, b) < 100 { dark += 1 } else if min(r, g, b) > 200 { light += 1 }
            }
        }
        XCTAssertGreaterThan(light, dark, "\(id) focused, on the light platter (light points \(light), dark \(dark))", file: file, line: line)
        XCTAssertGreaterThan(dark, 20, "\(id) focused, its words dark on the platter (dark points \(dark), light \(light))", file: file, line: line)
    }
}
