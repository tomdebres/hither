import CoreText
import Foundation
import SwiftUI
import UIKit
import XCTest
@testable import Hither

/// Hither's words are in tvOS's text styles, not sizes of its own (Tom's native look, DECISIONS 195): part 4's
/// `SFProTests` turned round. `HitherType` and its doubled Android sizes are gone; every font in the app is a system text
/// style (`.title2`, `.headline`, `.body`, `.caption`…), which draws SF Pro at tvOS's own size for the style. Inter
/// stays out of the app.
@MainActor
final class TextStylesTests: XCTestCase {
    /// The app's sources, beside this file's folder (the simulator reads the Mac's files).
    private var sources: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Hither")
    }

    /// No font in the app is made at a size: no `Font.system(size:)`, `UIFont.systemFont(ofSize:)`, custom font or
    /// type scale of Hither's own; every `.font(...)` is a system text style (or a `Font` handed down as one).
    func testEveryFontIsATextStyle() throws {
        let files = try swiftFiles(in: sources)
        XCTAssertGreaterThan(files.count, 20, "the app's sources at \(sources.path)")
        let sized = try NSRegularExpression(pattern: #"\.system\(size:|systemFont\(ofSize:|Font\.custom|\.custom\("|UIFont\(name:|HitherType|TextStyle\(size:"#)
        let fontCall = try NSRegularExpression(pattern: #"\.font\(([^)\n]*)"#)
        let styles = ["largeTitle", "title", "title2", "title3", "headline", "subheadline", "body", "callout", "footnote", "caption", "caption2"]
        var findings: [String] = []
        for file in files {
            let text = try String(contentsOf: file, encoding: .utf8)
            let name = file.lastPathComponent
            for (number, line) in text.components(separatedBy: "\n").enumerated() {
                let code = line.components(separatedBy: "//").first ?? line
                let range = NSRange(code.startIndex..., in: code)
                if sized.firstMatch(in: code, range: range) != nil {
                    findings.append("\(name):\(number + 1): a size of its own: \(line.trimmingCharacters(in: .whitespaces))")
                }
                for match in fontCall.matches(in: code, range: range) {
                    guard let argument = Range(match.range(at: 1), in: code).map({ String(code[$0]) }) else { continue }
                    let style = argument.drop { $0 == "." }.prefix { $0.isLetter || $0.isNumber }
                    // `.font(size)`: a Font the caller chose from the text styles (StatusGlyph's).
                    if argument == "size" { continue }
                    if !argument.hasPrefix(".") || !styles.contains(String(style)) {
                        findings.append("\(name):\(number + 1): not a text style: .font(\(argument))")
                    }
                }
            }
        }
        XCTAssertEqual(findings, [], "every font a tvOS text style")
    }

    /// The text styles Hither uses draw SF Pro at tvOS's own sizes for them (Title 2 57, Title 3 48, Headline 38,
    /// Callout 31, Body 29, Caption 25, Caption 2 23 on tvOS 26): SwiftUI's drawing of each is Core Text's drawing of the
    /// system's preferred font for the style, the same ink within 2% and the same width.
    func testTextStylesDrawTvOSsOwnSizes() throws {
        let styles: [(String, Font, UIFont.TextStyle)] = [
            ("Title 2", .title2, .title2), ("Title 3", .title3, .title3), ("Headline", .headline, .headline),
            ("Callout", .callout, .callout), ("Body", .body, .body), ("Caption", .caption, .caption1), ("Caption 2", .caption2, .caption2),
        ]
        for (name, font, style) in styles {
            let system = UIFont.preferredFont(forTextStyle: style)
            XCTAssertTrue(system.fontName.hasPrefix(".SF"), "\(name): SF Pro, not \(system.fontName)")
            let drawn = try XCTUnwrap(render(Text(sample).font(font)), "SwiftUI's drawing")
            let core = try XCTUnwrap(drawCoreText(sample, font: system as CTFont))
            let ink = self.ink(drawn), want = self.ink(core)
            XCTAssertEqual(ink, want, accuracy: want * 0.02, "\(name): ink \(ink), the system's \(want)")
            XCTAssertEqual(Double(drawn.width), Double(core.width), accuracy: 4, "\(name): the system's width")
        }
    }

    /// Inter is not in the app: no font file, no registration, no family the app could draw it from.
    func testNoInterLeft() {
        XCTAssertNil(Bundle.main.url(forResource: "inter_variable", withExtension: "ttf"), "Inter's file in the bundle")
        XCTAssertNil(Bundle.main.object(forInfoDictionaryKey: "UIAppFonts"), "fonts registered by the Info.plist")
        let inter = UIFont.familyNames.filter { $0.localizedCaseInsensitiveContains("Inter") }
        XCTAssertEqual(inter, [], "Inter among the app's font families")
    }

    // MARK: - Reading and drawing

    private let sample = "Hither Discover Requests 2026"

    private func swiftFiles(in folder: URL) throws -> [URL] {
        let walker = FileManager.default.enumerator(at: folder, includingPropertiesForKeys: nil)
        return (walker?.allObjects as? [URL] ?? []).filter { $0.pathExtension == "swift" }
    }

    private func render(_ view: some View) -> CGImage? {
        let renderer = ImageRenderer(content: view.foregroundStyle(.white).fixedSize().padding(8))
        renderer.scale = 2
        return renderer.cgImage
    }

    private func drawCoreText(_ text: String, font: CTFont) -> CGImage? {
        let attributed = NSAttributedString(string: text, attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): UIColor.white.cgColor,
        ])
        let line = CTLineCreateWithAttributedString(attributed)
        var ascent: CGFloat = 0, descent: CGFloat = 0, leading: CGFloat = 0
        let width = CTLineGetTypographicBounds(line, &ascent, &descent, &leading)
        let scale: CGFloat = 2, pad: CGFloat = 8
        let w = Int(((CGFloat(width) + 2 * pad) * scale).rounded(.up)), h = Int(((ascent + descent + 2 * pad) * scale).rounded(.up))
        guard let context = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
        else { return nil }
        context.scaleBy(x: scale, y: scale)
        context.textPosition = CGPoint(x: pad, y: pad + descent)
        CTLineDraw(line, context)
        return context.makeImage()
    }

    /// The text's ink: the sum of its alpha, in whole pixels.
    private func ink(_ image: CGImage) -> Double {
        let w = image.width, h = image.height
        var alpha = [UInt8](repeating: 0, count: w * h)
        guard let context = CGContext(data: &alpha, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.alphaOnly.rawValue)
        else { return 0 }
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return alpha.reduce(0) { $0 + Double($1) } / 255
    }
}
