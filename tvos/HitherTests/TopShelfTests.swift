import HitherCore
import TVServices
import XCTest
@testable import Hither

/// The Top Shelf's content as the extension builds it from the file the app writes (DECISIONS 199), and the links its
/// items open.
final class TopShelfTests: XCTestCase {
    private let items = [
        ShelfItem(key: "movie:1616419", title: "The Good Lawyer", link: "hither://title/movie/1616419", image: "movie-1616419.jpg"),
        ShelfItem(key: "tv:1877", title: "Phineas and Ferb", link: "hither://title/tv/1877", image: nil),
    ]

    func testOneSectionOfPostersInOrderEachOpeningItsTitle() throws {
        let folder = URL(fileURLWithPath: NSTemporaryDirectory())
        let content = try XCTUnwrap(ShelfFile.content(items, folder: folder) as? TVTopShelfSectionedContent, "sectioned content")
        XCTAssertEqual(content.sections.count, 1)
        let section = content.sections[0]
        XCTAssertEqual(section.title, "Ready to Watch", "Android's channel name, in title case as tvOS titles a shelf")
        XCTAssertEqual(section.items.map(\.identifier), ["movie:1616419", "tv:1877"], "the app's order, newest first")
        XCTAssertEqual(section.items.map(\.title), ["The Good Lawyer", "Phineas and Ferb"])
        for (item, expected) in zip(section.items, items) {
            XCTAssertEqual(item.imageShape, .poster)
            XCTAssertEqual(item.displayAction?.url.absoluteString, expected.link, "Select opens the title in Hither")
            XCTAssertEqual(item.playAction?.url.absoluteString, expected.link, "Play does too")
        }
        XCTAssertEqual(section.items[0].imageURL(for: .screenScale2x), folder.appendingPathComponent("movie-1616419.jpg"), "the poster from the shared folder")
        XCTAssertNil(section.items[1].imageURL(for: .screenScale2x), "no poster until it has been fetched")
    }

    /// Each item's link is core's `TitleLink.uri`, and it opens exactly that title (core's `parseTitleLink`).
    func testEachLinkNamesItsTitle() throws {
        for (type, id) in [(MediaType.movie, Int32(550)), (.tv, 1877)] {
            let uri = TitleLink(type: type, tmdbId: id).uri
            let parsed = try XCTUnwrap(HomeRowsKt.parseTitleLink(uri: uri))
            XCTAssertEqual(parsed.type, type)
            XCTAssertEqual(parsed.tmdbId, id)
        }
        XCTAssertNil(HomeRowsKt.parseTitleLink(uri: "hither://title/movie/12abc"))
        XCTAssertNil(HomeRowsKt.parseTitleLink(uri: "hither://title/person/12"))
    }

    func testNothingReadyIsNoContent() {
        XCTAssertNil(ShelfFile.content([], folder: URL(fileURLWithPath: NSTemporaryDirectory())), "tvOS shows the app's own image")
        let foreign = [ShelfItem(key: "movie:1", title: "Elsewhere", link: "https://example.invalid/1", image: nil)]
        XCTAssertNil(ShelfFile.content(foreign, folder: URL(fileURLWithPath: NSTemporaryDirectory())), "only Hither's own title links")
    }

    /// The app and the extension share the App Group's folder: granted on the simulator, and the file round-trips.
    func testTheSharedFolderRoundTrips() throws {
        let folder = try XCTUnwrap(ShelfFile.folder, "the App Group is granted")
        let test = folder.appendingPathComponent("test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: test, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: test) }
        try ShelfFile.write(items, to: test)
        XCTAssertEqual(ShelfFile.read(from: test), items)
    }
}
