import HitherCore
import XCTest
@testable import Hither

/// The links the title page hands to other apps (DECISIONS 174): what each builds from core's checked values, and what
/// it refuses to build.
final class AppLinksTests: XCTestCase {
    private let machine = "f00dfacecafe0000000000000000000000000000"

    /// Seerr's iOS link, rebuilt from core's checked play action: a film's and a series' are the same form on tvOS.
    func testPlexLinkIsSeerrsIOSLink() {
        for type in [MediaType.movie, MediaType.tv] {
            let ios = "plex://preplay/?metadataKey=%2Flibrary%2Fmetadata%2F4242&server=\(machine)"
            guard let link = PlexKt.plexAppLink(iOSPlexUrl: ios, type: type) else { return XCTFail("core refused Seerr's own link") }
            let play = PlayAction(is4k: false, link: link)
            XCTAssertEqual(AppLinks.plex(play)?.absoluteString, ios, "\(type)")
        }
    }

    /// Nothing but a machine id of letters and digits and a numeric rating key reaches a link the TV opens.
    func testPlexLinkRefusesAnythingElse() {
        for link in [
            "plex://libraries/abc/unmatched/%2Flibrary%2Fmetadata%2F12x",
            "plex://libraries/ab-c/unmatched/%2Flibrary%2Fmetadata%2F12",
            "plex://libraries//unmatched/%2Flibrary%2Fmetadata%2F12",
            "https://app.plex.tv/desktop#!/server/abc/details?key=%2Flibrary%2Fmetadata%2F12",
            "plex://libraries/abc/unmatched/%2Flibrary%2Fmetadata%2F",
        ] {
            XCTAssertNil(AppLinks.plex(PlayAction(is4k: false, link: link)), link)
        }
    }

    /// The YouTube app's own scheme with the trailer's id, and nothing for an id that is not YouTube's eleven.
    func testTrailerLink() {
        XCTAssertEqual(AppLinks.trailer("th9hxCGH_ms")?.absoluteString, "youtube://watch/th9hxCGH_ms")
        XCTAssertNil(AppLinks.trailer("th9hxCGH_m"), "ten characters")
        XCTAssertNil(AppLinks.trailer("th9hxCGH_ms&x=1"), "anything after the id")
        XCTAssertNil(AppLinks.trailer("../watch?v=1"), "not an id")
    }
}
