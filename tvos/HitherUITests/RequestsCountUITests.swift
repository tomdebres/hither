import XCTest

/// The Requests tab's count (Android's count circle, DECISIONS 79, 146; the tab's own symbol on tvOS, 184), from
/// fake-seerr's data by core's rules: the changes to the user's own requests since My Requests was last opened.
final class RequestsCountUITests: HitherUITestCase {
    private let requests = "tab Requests"

    func testTheCountFollowsTheUsersRequests() {
        // Two of the family's own requests wait for approval before Hither first looks.
        Fake.set("/fake/account?autoapprove=0")
        Fake.set("/fake/ask?title=movie:354912")
        Fake.set("/fake/ask?title=tv:1421&seasons=1")
        // Every arrival looks (core's floor is a minute; a debug build only).
        app.launchEnvironment["HITHER_LOOK_DUE"] = "always"
        launchSignedOut()
        signIn()
        waitForDiscover()
        // The first look records the requests and announces nothing: no count.
        Thread.sleep(forTimeInterval: 3)
        XCTAssertEqual(element(requests).label, "Requests", "no count after the first look")

        // On the server, one approved and one declined; Hither comes back and looks again: two changes.
        Fake.set("/fake/move?title=movie:354912&to=approved")
        Fake.set("/fake/move?title=tv:1421&to=declined")
        app.terminate()
        launchAsLeft()
        waitForDiscover()
        waitForLabel(requests, equals: "Requests, 2 changes to your requests")
        XCTAssertTrue(element(requests).descendants(matching: .image)["2.circle.fill"].exists, "the count's circle on the tab")

        // Walking the bar past Requests to Search keeps the count: the page was not looked at.
        press(.up)
        waitForFocus("tab Discover")
        press(.right)
        shoot("tvos-bar-requests-count")
        press(.right)
        waitForFocus("tab Search")
        Thread.sleep(forTimeInterval: 3)
        XCTAssertEqual(element(requests).label, "Requests, 2 changes to your requests", "passing over Requests keeps the count")

        // Resting on Requests opens My Requests: the count goes, and stays gone after a relaunch.
        press(.left)
        waitForFocus(requests)
        waitForLabel(requests, equals: "Requests", "the count goes once Requests' page has been looked at")
        app.terminate()
        launchAsLeft()
        waitForDiscover()
        Thread.sleep(forTimeInterval: 3)
        XCTAssertEqual(element(requests).label, "Requests", "opening My Requests cleared the count on the device")
    }
}

extension HitherUITestCase {
    /// Waits for [id]'s label to be [words] exactly.
    func waitForLabel(_ id: String, equals words: String, _ message: String = "", timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        let target = element(id)
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if target.exists, target.label == words { return }
            Thread.sleep(forTimeInterval: 0.3)
        }
        XCTFail("\(id): expected \"\(words)\", it reads \"\(target.exists ? target.label : "(gone)")\". \(message)", file: file, line: line)
    }
}
