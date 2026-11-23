import HitherCore
import SwiftUI
import XCTest
@testable import Hither

/// The tvOS polish's rules that a unit test can hold (polish parts 1 and 2, the look and feel review's findings).
@MainActor
final class PolishTests: XCTestCase {
    /// The app's sources, beside this file's folder (the simulator reads the Mac's files).
    private var sources: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Hither")
    }

    /// Money in whole dollars: a film's takings and budget without pence (the review's F29: "$400,290,973.00").
    func testMoneyWithoutPence() {
        XCTAssertEqual(FactCell.value(Fact(name: "Revenue", value: "400290973", kind: .money)), "$400,290,973")
        XCTAssertEqual(FactCell.value(Fact(name: "Budget", value: "75000000", kind: .money)), "$75,000,000")
        XCTAssertEqual(FactCell.value(Fact(name: "Budget", value: "0", kind: .money)), "$0")
        XCTAssertEqual(FactCell.value(Fact(name: "Budget", value: "not a number", kind: .money)), "not a number", "as given when it is no amount")
    }

    /// Seerr's indigo (or any colour) tints only a `.borderedProminent` button, white words on the fill: never a form, a
    /// page or the app (M7 part 5's trap: an app-wide tint blanks tvOS's buttons; the review's F3: a form's tint became
    /// its Sign In's words, indigo on dark grey at 2.8:1). Each `.tint(` must sit in a modifier chain that also sets
    /// `.buttonStyle(.borderedProminent)`.
    func testTintOnlyOnProminentButtons() throws {
        let files = try FileManager.default.subpathsOfDirectory(atPath: sources.path)
            .filter { $0.hasSuffix(".swift") }
            .map { sources.appendingPathComponent($0) }
        XCTAssertGreaterThan(files.count, 20, "the app's sources at \(sources.path)")
        var tints = 0
        var findings: [String] = []
        for file in files {
            let lines = try String(contentsOf: file, encoding: .utf8).components(separatedBy: "\n")
                .map { ($0.components(separatedBy: "//").first ?? $0).trimmingCharacters(in: .whitespaces) }
            for (number, line) in lines.enumerated() where line.contains(".tint(") {
                tints += 1
                // The chain: this line, and the modifier lines above it up to the view they modify.
                var chain = [line]
                var above = number - 1
                while above >= 0, lines[above].hasPrefix(".") || lines[above].isEmpty {
                    chain.append(lines[above])
                    above -= 1
                }
                if above >= 0 { chain.append(lines[above]) }
                if !chain.contains(where: { $0.contains(".buttonStyle(.borderedProminent)") }) {
                    findings.append("\(file.lastPathComponent):\(number + 1): \(line)")
                }
            }
        }
        XCTAssertGreaterThan(tints, 3, "the prominent buttons' tints were found") // four since part 5: the sheets' share `FormAction`
        XCTAssertEqual(findings, [], "a tint on something other than a prominent button")
    }

    // MARK: - Polish part 2

    /// A fact's date as every date on the TV: `Date.FormatStyle`'s abbreviated date in the device's language (the
    /// review's F29: "16 September 2026" beside "Jan 1, 2026" on one simulator, `dateStyle .long` following the region
    /// and a template following the language).
    func testFactDatesAsEveryDate() {
        let day = DateComponents(calendar: Calendar(identifier: .gregorian), year: 2026, month: 9, day: 16).date!
        XCTAssertEqual(FactCell.value(Fact(name: "Release Date", value: "2026-09-16", kind: .date)), day.formatted(date: .abbreviated, time: .omitted))
        XCTAssertEqual(FactCell.value(Fact(name: "Release Date", value: "soon", kind: .date)), "soon", "as given when it is no date")
    }

    /// Every status glyph's mark reads on its disc, 3:1 at least (WCAG's for a graphic; the review's F27: a white bell
    /// on Seerr's yellow measured 1.9:1, a white tick on its green 2.3:1). Read off the glyph as SwiftUI draws it: the
    /// two colours most of its opaque pixels are, the disc's and the mark's.
    func testStatusGlyphMarksRead() throws {
        let kinds: [(StatusWord, BadgeKind)] = [(.pending, .warning), (.available, .success), (.requested, .primary), (.declined, .danger)]
        for (word, kind) in kinds {
            let badge = StatusBadge(word: word, kind: kind, is4k: false, inProgress: false, progress: nil)
            let pixels = try XCTUnwrap(Self.render(StatusGlyph(badge: badge, size: .system(size: 160)), size: 200))
            let colours = pixels.opaqueColours()
            XCTAssertGreaterThanOrEqual(colours.count, 2, "\(word): a disc and a mark")
            guard colours.count >= 2 else { continue }
            let ratio = Self.contrast(colours[0], colours[1])
            XCTAssertGreaterThanOrEqual(ratio, 3, "\(word)'s mark on its disc: \(colours[0]) and \(colours[1]), \(String(format: "%.1f", ratio)):1")
        }
    }

    /// A glyph on a picture of its own colour keeps its edge (the review's F35: the green Available disc on a green
    /// poster had none): a darker ring between the picture and the disc, read along the glyph's middle line.
    func testStatusGlyphKeepsItsEdgeOnItsOwnColour() throws {
        let badge = StatusBadge(word: .available, kind: .success, is4k: false, inProgress: false, progress: nil)
        let glyph = ZStack {
            Tokens.success
            StatusGlyph(badge: badge, size: .system(size: 160))
        }
        let pixels = try XCTUnwrap(Self.render(glyph, size: 240))
        let ground = pixels.luminance(x: 2, y: 120)
        // From the picture's edge to a quarter of the way in: past the disc's edge, short of the mark in its middle.
        let darkest = (2..<60).map { pixels.luminance(x: $0, y: 120) }.min() ?? ground
        XCTAssertLessThan(darkest, ground * 0.8, "a ring darker than the picture round the disc: the picture \(ground), the darkest \(darkest)")
    }

    /// A picture waits on the grey a loading row's placeholders are, so nothing steps in brightness as lockups replace
    /// them (the review's F36: 20% white under a picture, 16% in a placeholder).
    func testPicturesWaitOnThePlaceholdersGrey() throws {
        let picture = try XCTUnwrap(Self.render(ProxyImage(url: nil), size: 100))
        let placeholder = try XCTUnwrap(Self.render(PlaceholderCard(width: 100, height: 100).frame(width: 100, height: 100, alignment: .top), size: 100))
        XCTAssertEqual(picture.rgb(x: 50, y: 50), placeholder.rgb(x: 50, y: 50), "a picture's ground and a placeholder's grey")
    }

    // MARK: - The button and glyph rules (X10, written in Tokens.swift)

    /// Fills left, by name, for the polish parts that redo their screens; each goes when its part lands.
    /// None since part 4 made Remove Admin Account a destructive row; the list stays for a part that leaves one.
    private static let leftFills: [(file: String, id: String, part: String)] = []

    /// Rules 1 and 2: every prominent button is the app's indigo; nothing destructive is a red fill, nothing a green one.
    func testProminentButtonsAreIndigo() throws {
        var findings: [String] = []
        for (file, lines) in try sourceLines() {
            for (number, line) in lines.enumerated() where line.contains(".buttonStyle(.borderedProminent)") {
                let chain = Self.chain(lines, at: number)
                let tint = chain.first { $0.contains(".tint(") } ?? "(no tint)"
                guard !tint.contains(".tint(Tokens.accent)") else { continue }
                if !Self.isLeftFill(file: file, chain: chain) { findings.append("\(file):\(number + 1): \(tint)") }
            }
        }
        XCTAssertEqual(findings, [], "a prominent button in another colour than the app's indigo")
    }

    /// Rule 3: selection is never a button's fill (a button turned prominent while it is the chosen one). Every file:
    /// the Issues and queue filters, the last bars of pills, became the Library split's side list (part 3).
    func testSelectionIsNeverAFill() throws {
        var findings: [String] = []
        for (file, lines) in try sourceLines() {
            for (number, line) in lines.enumerated() where line.contains(".borderedProminent") {
                // The `if` the prominent button sits under, a few lines up: a comparison is a selection.
                let above = lines[max(0, number - 3)..<number]
                if let condition = above.last(where: { $0.hasPrefix("if ") }), condition.contains("==") {
                    findings.append("\(file):\(number + 1): \(condition)")
                }
            }
        }
        XCTAssertEqual(findings, [], "a button made prominent to say it is selected")
    }

    /// Rule 4: Seerr's status colours on glyphs only (`Glyph`, through `BadgeKind.colour`), never as a fill behind words
    /// or a symbol of a page's own; the fills left above aside. Part 5 took the last two others away: Report an Issue's
    /// yellow triangle (S22) and the quota's heat-coloured ring (S16).
    func testStatusColoursOnGlyphsOnly() throws {
        let status = try NSRegularExpression(pattern: #"Tokens\.(success|primary|warning|danger)\b"#)
        var findings: [String] = []
        for (file, lines) in try sourceLines() where !["Tokens.swift", "Badges.swift"].contains(file) {
            for (number, line) in lines.enumerated() where status.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)) != nil {
                let chain = Self.chain(lines, at: number)
                let leftFill = line.contains(".tint(") && Self.isLeftFill(file: file, chain: chain)
                if !leftFill { findings.append("\(file):\(number + 1): \(line)") }
            }
        }
        XCTAssertEqual(findings, [], "a status colour off a glyph")
    }

    // MARK: - Polish part 6

    /// A person's portrait is the system's circular lockup (the review's F25): the button's border shape a circle and the
    /// portrait taking the system's highlight in it (it lifts, tilts and catches the light, as a poster does), not a scale
    /// and a shadow of Hither's own. `PagesUITests.testPersonPage` reads the pixels round the circle for a square.
    func testPortraitsTakeTheSystemsHighlight() throws {
        let lines = try String(contentsOf: sources.appendingPathComponent("Theme/Lockups.swift"), encoding: .utf8)
            .components(separatedBy: "\n")
        // A struct's lines, from its declaration to the brace that closes it at the file's top level.
        func body(of name: String) -> [String] {
            guard let start = lines.firstIndex(where: { $0.contains("struct \(name)") }) else { return [] }
            let end = lines[(start + 1)...].firstIndex { $0 == "}" } ?? lines.count - 1
            return lines[start...end].map { ($0.components(separatedBy: "//").first ?? $0).trimmingCharacters(in: .whitespaces) }
        }
        let card = body(of: "PersonCardView: View"), portrait = body(of: "Portrait: View")
        XCTAssertFalse(card.isEmpty || portrait.isEmpty, "PersonCardView and Portrait in Lockups.swift")
        XCTAssertTrue(card.contains(".buttonBorderShape(.circle)"), "the person's button in a circle")
        XCTAssertTrue(portrait.contains(".hoverEffect(.highlight)"), "the system's highlight on the portrait")
        XCTAssertTrue(portrait.contains(".contentShape(.hoverEffect, Circle())"), "the highlight in the portrait's circle")
        XCTAssertFalse(portrait.contains { $0.contains("scaleEffect") || $0.contains(".shadow(") }, "no lift of Hither's own")
    }

    // MARK: - Polish part 4

    /// A server's address as Settings shows it, under its name: the host, its port and any path, without the scheme or
    /// a trailing slash (the review's F37: "Seerr · http://localhost:55735").
    func testServerAddressWithoutTheScheme() {
        XCTAssertEqual(SettingsView.address("http://localhost:55735"), "localhost:55735")
        XCTAssertEqual(SettingsView.address("https://seerr.example.invalid/"), "seerr.example.invalid")
        XCTAssertEqual(SettingsView.address("http://192.0.2.10:5055/seerr"), "192.0.2.10:5055/seerr")
        XCTAssertEqual(SettingsView.address("not an address"), "not an address", "as given when it is no address")
    }

    /// The pixel reader these tests use reads rows from the top (polish part 6 found the UI tests' one upside down; this
    /// one had the same flip): red over blue, read at the top and at the foot.
    func testPixelsRowsFromTheTop() throws {
        let halves = VStack(spacing: 0) {
            Color(hex: 0xFF0000).frame(height: 50)
            Color(hex: 0x0000FF).frame(height: 50)
        }
        let pixels = try XCTUnwrap(Self.render(halves, size: 100))
        XCTAssertEqual(pixels.rgb(x: 50, y: 10), Pixels.RGB(r: 255, g: 0, b: 0), "the top is red")
        XCTAssertEqual(pixels.rgb(x: 50, y: 90), Pixels.RGB(r: 0, g: 0, b: 255), "the foot is blue")
    }

    // MARK: - Helpers

    /// The app's Swift files by name, each as lines with comments and leading space taken off.
    private func sourceLines() throws -> [(String, [String])] {
        let files = try FileManager.default.subpathsOfDirectory(atPath: sources.path).filter { $0.hasSuffix(".swift") }
        XCTAssertGreaterThan(files.count, 20, "the app's sources at \(sources.path)")
        return try files.map { path in
            let lines = try String(contentsOf: sources.appendingPathComponent(path), encoding: .utf8).components(separatedBy: "\n")
                .map { ($0.components(separatedBy: "//").first ?? $0).trimmingCharacters(in: .whitespaces) }
            return ((path as NSString).lastPathComponent, lines)
        }
    }

    /// Whether the button whose modifier chain is [chain] is one of the fills left for a later part.
    private static func isLeftFill(file: String, chain: [String]) -> Bool {
        leftFills.contains { fill in
            fill.file == file && chain.contains { $0.contains("accessibilityIdentifier(\"\(fill.id)") }
        }
    }

    /// The modifier chain line [at] is part of: the modifier lines above it up to the view, and those under it.
    private static func chain(_ lines: [String], at: Int) -> [String] {
        var chain = [lines[at]]
        var above = at - 1
        while above >= 0, lines[above].hasPrefix(".") || lines[above].isEmpty {
            chain.append(lines[above])
            above -= 1
        }
        if above >= 0 { chain.append(lines[above]) }
        var below = at + 1
        while below < lines.count, lines[below].hasPrefix(".") {
            chain.append(lines[below])
            below += 1
        }
        return chain
    }

    /// [view] drawn [size] points square at one pixel a point, as its RGBA bytes.
    private static func render(_ view: some View, size: CGFloat) -> Pixels? {
        let renderer = ImageRenderer(content: view.frame(width: size, height: size).environment(\.colorScheme, .dark))
        renderer.scale = 1
        guard let image = renderer.cgImage else { return nil }
        return Pixels(image)
    }

    /// WCAG's contrast ratio between two sRGB colours.
    private static func contrast(_ a: Pixels.RGB, _ b: Pixels.RGB) -> Double {
        let (la, lb) = (a.luminance, b.luminance)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}

/// A rendered image's pixels, RGBA, eight bits each.
private struct Pixels {
    struct RGB: Hashable, CustomStringConvertible {
        let r: UInt8, g: UInt8, b: UInt8

        /// WCAG's relative luminance.
        var luminance: Double {
            func linear(_ c: UInt8) -> Double {
                let v = Double(c) / 255
                return v <= 0.03928 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
        }

        var description: String { String(format: "#%02X%02X%02X", r, g, b) }
    }

    let width: Int, height: Int
    let bytes: [UInt8]

    init?(_ image: CGImage) {
        width = image.width
        height = image.height
        var bytes = [UInt8](repeating: 0, count: 4 * width * height)
        let drawn: Bool = bytes.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: image.width, height: image.height, bitsPerComponent: 8,
                                          bytesPerRow: 4 * image.width, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            return true
        }
        guard drawn else { return nil }
        self.bytes = bytes
    }

    /// The colour at (x, y), from the top left.
    func rgb(x: Int, y: Int) -> RGB {
        // The bitmap's first row in memory is the image's top.
        let at = 4 * (y * width + x)
        return RGB(r: bytes[at], g: bytes[at + 1], b: bytes[at + 2])
    }

    func luminance(x: Int, y: Int) -> Double { rgb(x: x, y: y).luminance }

    /// The colours of the fully opaque pixels, the commonest first (edges, which blend, fall out).
    func opaqueColours() -> [RGB] {
        var counts: [RGB: Int] = [:]
        for at in stride(from: 0, to: bytes.count, by: 4) where bytes[at + 3] == 255 {
            counts[RGB(r: bytes[at], g: bytes[at + 1], b: bytes[at + 2]), default: 0] += 1
        }
        return counts.filter { $0.value >= 50 }.sorted { $0.value > $1.value }.map(\.key)
    }
}
