import CoreGraphics
import XCTest

/// `ScreenColours`, the pixel reader the UI tests measure the screen with, read on an image whose rows are known: its
/// rows come from the top (polish part 6 found them read upside down for a rect taller than one point: a walk up from
/// a portrait's centre read black inside it).
final class ScreenColoursTests: XCTestCase {
    /// An image [width] wide whose rows from the top are [rows], each one colour.
    private func image(width: Int, rows: [(UInt8, UInt8, UInt8)]) throws -> CGImage {
        var bytes: [UInt8] = []
        for (r, g, b) in rows {
            for _ in 0..<width { bytes += [r, g, b, 255] }
        }
        let provider = try XCTUnwrap(CGDataProvider(data: Data(bytes) as CFData))
        return try XCTUnwrap(CGImage(width: width, height: rows.count, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: 4 * width,
                                     space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                                     provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent))
    }

    /// Red over green over blue, one row each: row 0 is the top's red, row 2 the foot's blue.
    func testRowsFromTheTop() throws {
        let shot = try image(width: 3, rows: [(255, 0, 0), (0, 255, 0), (0, 0, 255)])
        let colours = try XCTUnwrap(ScreenColours(shot, pixels: CGRect(x: 0, y: 0, width: 3, height: 3), width: 3, height: 3))
        XCTAssertEqual(colours.rgb(1, 0).0, 255, "the top row is red: \(colours.rgb(1, 0))")
        XCTAssertEqual(colours.rgb(1, 1).1, 255, "the middle row is green: \(colours.rgb(1, 1))")
        XCTAssertEqual(colours.rgb(1, 2).2, 255, "the foot is blue: \(colours.rgb(1, 2))")
    }

    /// A rect inside the image, as `colours(in:)` crops one off a screenshot: its first row is the rect's top.
    func testACropsRowsFromItsTop() throws {
        let shot = try image(width: 4, rows: [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 255)])
        let colours = try XCTUnwrap(ScreenColours(shot, pixels: CGRect(x: 1, y: 1, width: 2, height: 2), width: 2, height: 2))
        XCTAssertEqual(colours.rgb(0, 0).1, 255, "the crop's top is the image's second row, green: \(colours.rgb(0, 0))")
        XCTAssertEqual(colours.rgb(0, 1).2, 255, "the crop's foot is the image's third row, blue: \(colours.rgb(0, 1))")
    }
}
