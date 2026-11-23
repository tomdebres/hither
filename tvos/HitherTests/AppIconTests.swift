import UIKit
import XCTest
@testable import Hither

/// The app icon and the Top Shelf images (DECISIONS 214), written by `scripts/tvos-icons` from docs/brand. actool names
/// them in the built Info.plist only when the build points at the brand assets, and upload validation refuses a tvOS
/// build without them.
final class AppIconTests: XCTestCase {
    func testTheIconAndTheTopShelfImagesAreInTheBuild() throws {
        let info = try XCTUnwrap(Bundle.main.infoDictionary)
        let icons = try XCTUnwrap(info["CFBundleIcons"] as? [String: Any], "no CFBundleIcons: the build has no app icon")
        XCTAssertEqual(icons["CFBundlePrimaryIcon"] as? String, "App Icon")
        let shelf = try XCTUnwrap(info["TVTopShelfImage"] as? [String: Any], "no TVTopShelfImage: the build has no Top Shelf image")
        XCTAssertEqual(shelf["TVTopShelfPrimaryImage"] as? String, "Top Shelf Image")
        XCTAssertEqual(shelf["TVTopShelfPrimaryImageWide"] as? String, "Top Shelf Image Wide")
        // The images at tvOS's sizes, in points.
        XCTAssertEqual(UIImage(named: "Top Shelf Image")?.size, CGSize(width: 1920, height: 720))
        XCTAssertEqual(UIImage(named: "Top Shelf Image Wide")?.size, CGSize(width: 2320, height: 720))
    }
}
