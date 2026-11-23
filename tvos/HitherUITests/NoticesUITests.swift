import XCTest

/// What Hither shows coming to the front (Android's `Notices` and `LifecycleStartEffect`, DECISIONS 79): each change to
/// the user's requests since the last look as a notice, shown once, in the one banner left (Tom's D4: a small glass
/// capsule top right, opaque where glass would not hold the words up); and the statuses on Discover read again.
final class NoticesUITests: HitherUITestCase {
    func testANoticeIsShownOnce() {
        Fake.set("/fake/account?autoapprove=0")
        Fake.set("/fake/ask?title=movie:354912")
        // Every arrival looks (core's floor is a minute; a debug build only).
        app.launchEnvironment["HITHER_LOOK_DUE"] = "always"
        launchSignedOut()
        signIn()
        waitForDiscover()
        // The first look records the requests and announces nothing.
        XCTAssertFalse(element("notice").waitForExistence(timeout: 6), "no notice after the first look")

        // The server's owner approves it; Hither comes back: one notice, Seerr's word and the title.
        Fake.set("/fake/move?title=movie:354912&to=approved")
        app.terminate()
        launchAsLeft()
        waitForDiscover()
        waitForLabel("notice", contains: "Approved: Coco", timeout: 30)
        shoot("tvos-notice")
        assertToastInTheCorner("notice")
        // The system's Liquid Glass on an Apple TV 4K, a capsule sized to its words.
        XCTAssertEqual(element("notice").value as? String, "glass", "glass where tvOS 26 draws it")
        assertCapsule(element("notice"))

        // Taken once shown: the next arrival has nothing to say.
        app.terminate()
        launchAsLeft()
        waitForDiscover()
        XCTAssertFalse(element("notice").waitForExistence(timeout: 8), "a notice is shown once")

        // On the Apple TV HD (the tester's: an A8, where glass may not draw), the same banner on the opaque surface.
        Fake.set("/fake/move?title=movie:354912&to=available")
        app.terminate()
        app.launchEnvironment["HITHER_DEVICE_MODEL"] = "AppleTV5,3"
        launchAsLeft()
        waitForDiscover()
        waitForLabel("notice", contains: "Coco", timeout: 30)
        XCTAssertEqual(element("notice").value as? String, "opaque", "the Apple TV HD's banner is opaque")
        assertToastInTheCorner("notice")
        // Opaque: the surface's one grey (Tokens.surface, 20% white) at the banner's middle left, nothing through it.
        let frame = element("notice").frame
        let shot = XCUIScreen.main.screenshot()
        for x in [frame.minX + frame.height / 2, frame.maxX - frame.height / 2] {
            let colour = colours(in: CGRect(x: x.rounded(), y: (frame.maxY - 6).rounded(), width: 1, height: 1), of: shot)?.rgb(0, 0)
            XCTAssertNotNil(colour)
            if let colour {
                for channel in [colour.0, colour.1, colour.2] {
                    XCTAssertEqual(channel, 51, accuracy: 3, "the opaque surface's grey at x = \(x): \(colour)")
                }
            }
        }
        shoot("tvos-notice-opaque")
    }

    /// The banner is a capsule: its ends are round, so the corner of its frame shows the page, not the banner.
    private func assertCapsule(_ banner: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        let frame = banner.frame
        let shot = XCUIScreen.main.screenshot()
        let corner = colours(in: CGRect(x: frame.maxX.rounded() - 3, y: frame.minY.rounded() + 2, width: 1, height: 1), of: shot)?.rgb(0, 0)
        let middle = colours(in: CGRect(x: frame.midX.rounded(), y: frame.minY.rounded() + 4, width: 1, height: 1), of: shot)?.rgb(0, 0)
        XCTContext.runActivity(named: "the banner at \(frame): corner \(String(describing: corner)), top middle \(String(describing: middle))") { _ in }
        if let corner {
            XCTAssertLessThanOrEqual(max(corner.0, corner.1, corner.2), 16, "the frame's corner is the page's black: a round end", file: file, line: line)
        }
    }

    func testComingBackReadsTheStatusesAgain() {
        launchSignedOut()
        signIn()
        let coco = Fixture.coco
        focusCard(coco.key, row: coco.row, index: coco.index)
        XCTAssertFalse(inside(coco.key, "badge Requested").exists, "nobody has asked for Coco yet")

        // Asked for while Hither is away (another TV, the web): coming back, the card shows it.
        remote.press(.home)
        XCTAssertTrue(app.wait(for: .runningBackgroundSuspended, timeout: 10) || app.state == .runningBackground, "Hither in the background")
        Fake.set("/fake/ask?title=movie:354912")
        app.activate()
        XCTAssertTrue(inside(coco.key, "badge Requested").waitForExistence(timeout: 20), "the card's status read again on coming back")
        waitForFocus(coco.key, "focus kept on the card")
    }
}
