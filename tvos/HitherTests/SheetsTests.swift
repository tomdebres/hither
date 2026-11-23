import HitherCore
import SwiftUI
import XCTest
@testable import Hither

/// Polish part 5, sheets and feedback (Tom's D4, the review's X6, X7, F18, S15, S16): what a unit test can hold.
@MainActor
final class SheetsTests: XCTestCase {
    /// The notices' banner is glass only where glass holds the words up (Tom's D4): tvOS 26, Reduce Transparency off,
    /// not the Apple TV HD; anywhere else the opaque surface.
    func testTheBannerIsOpaqueWhereGlassWouldNotHold() {
        XCTAssertEqual(BannerSurface.choose(reduceTransparency: false, model: "AppleTV14,1", glassAvailable: true), .glass)
        XCTAssertEqual(BannerSurface.choose(reduceTransparency: true, model: "AppleTV14,1", glassAvailable: true), .opaque, "Reduce Transparency")
        XCTAssertEqual(BannerSurface.choose(reduceTransparency: false, model: "AppleTV5,3", glassAvailable: true), .opaque, "the Apple TV HD")
        XCTAssertEqual(BannerSurface.choose(reduceTransparency: false, model: "AppleTV11,1", glassAvailable: false), .opaque, "before tvOS 26")
        XCTAssertEqual(BannerSurface.choose(reduceTransparency: false, model: "AppleTV6,2", glassAvailable: true), .glass, "the first Apple TV 4K")
    }

    /// The model this process reads is the simulator's device (an Apple TV 4K), not "arm64".
    func testTheDeviceModelIsAnAppleTV() {
        XCTAssertTrue(BannerSurface.model.hasPrefix("AppleTV"), BannerSurface.model)
    }

    /// A film's request is a question with short buttons (the review's F2): "Request Coco?" or "... in 4K?", with what
    /// it will come to as sentences (S15, S16: no coloured mark, no heat-coloured ring); past the quota it says why
    /// instead, and cannot be asked.
    func testAFilmsRequestIsAQuestion() {
        let quota = QuotaLine(remaining: 2, limit: 3, days: KotlinInt(int: 7), seasons: false)
        let open = RequestModalView(
            title: "Request Movie", subtitle: "Coco", approvedAutomatically: true, rows: [], picker: false, picked: [],
            primary: "Request", primaryEnabled: true, quota: quota
        )
        let question = RequestQuestion(draft: RequestDraft(is4k: false), name: "Coco", view: open)
        XCTAssertTrue(question.enabled)
        XCTAssertEqual(question.title, "Request Coco?")
        XCTAssertEqual(question.message, "This request needs no approval.\n2 movie requests left. You may request 3 movies every 7 days.")
        XCTAssertEqual(RequestQuestion(draft: RequestDraft(is4k: true), name: "Coco", view: open).title, "Request Coco in 4K?")

        let spent = RequestModalView(
            title: "Request Movie", subtitle: "Coco", approvedAutomatically: false, rows: [], picker: false, picked: [],
            primary: "No Movie Requests Remaining", primaryEnabled: false,
            quota: QuotaLine(remaining: 0, limit: 3, days: KotlinInt(int: 7), seasons: false)
        )
        let none = RequestQuestion(draft: RequestDraft(is4k: false), name: "Coco", view: spent)
        XCTAssertFalse(none.enabled)
        XCTAssertEqual(none.title, "No Movie Requests Remaining")
        XCTAssertEqual(none.message, "No movie requests left. You may request 3 movies every 7 days.")
        XCTAssertEqual(RequestQuestion(draft: RequestDraft(is4k: false), name: "Coco", view: RequestModalView(
            title: "Request Movie", subtitle: "Coco", approvedAutomatically: false, rows: [], picker: false, picked: [],
            primary: "Request", primaryEnabled: true, quota: nil
        )).message, "", "nothing to say: no message")
    }

    /// The privacy text is one text for the reading view (the review's F20): every block after the title, in order, the
    /// headings and the bold runs bold, the bullets as bullets.
    func testThePrivacyTextIsOneText() {
        let blocks = PrivacyText.parse("""
        # Privacy

        Hither keeps **nothing** it does not need.

        ## What the app stores

        - The server's address,
          on the device only.
        - The session.
        """)
        let text = PrivacyView.reading(blocks)
        XCTAssertEqual(text.plain, "Hither keeps nothing it does not need.\nWhat the app stores\n• The server's address, on the device only.\n• The session.")
        XCTAssertFalse(text.plain.contains("Privacy"), "the title is the page's, not the text's")
        XCTAssertTrue(text.isBold("nothing"), "a bold run")
        XCTAssertFalse(text.isBold("Hither keeps"), "plain words")
        XCTAssertTrue(text.isBold("What the app stores"), "a heading")
        guard text.blocks.count == 4, case .bullet = text.blocks[3] else { return XCTFail("the last block a bullet: \(text.blocks)") }
    }
}
