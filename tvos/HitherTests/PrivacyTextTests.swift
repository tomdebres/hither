import XCTest
@testable import Hither

/// The privacy text About shows: `docs/privacy-tvos.md` itself, bundled, read as Android reads its own.
final class PrivacyTextTests: XCTestCase {
    func testTheBundledTextIsTheRepositorysFile() throws {
        let repository = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../docs/privacy-tvos.md").standardized
        let text = try String(contentsOf: repository, encoding: .utf8)
        XCTAssertEqual(PrivacyText.bundled(), PrivacyText.parse(text), "the app shows the file in the repository")
    }

    func testHeadingsParagraphsBulletsAndBold() throws {
        let blocks = PrivacyText.bundled()
        XCTAssertEqual(blocks.first, .title("Privacy"))
        let headings = blocks.compactMap { if case .heading(let text) = $0 { text } else { nil } }
        XCTAssertEqual(headings, [
            "What the app stores, on the device only",
            "What the app sends, and to whom",
            "On the Apple TV's Home Screen",
            "What the server does with it",
        ])
        guard case .bullet(let runs) = blocks[3] else { return XCTFail("a bullet after the first heading's paragraph: \(blocks[3])") }
        XCTAssertEqual(runs.first, PrivacyRun(text: "The server's address", bold: true), "bold runs from **…**")
        XCTAssertTrue(PrivacyText.plain(runs).hasSuffix("as you typed it when signing in."), "continuation lines joined")
    }

    func testCodeAndLinksArePlainText() {
        let blocks = PrivacyText.parse("Kept as `connect.sid`, read at <https://example.invalid/x>, **`bold code`**.")
        XCTAssertEqual(blocks, [.paragraph([
            PrivacyRun(text: "Kept as connect.sid, read at https://example.invalid/x, "),
            PrivacyRun(text: "bold code", bold: true),
            PrivacyRun(text: "."),
        ])])
    }

    /// Words for the repository's readers only stay off the TV (the review's F37: "it changes in the same pull request as
    /// any code that would make it untrue", `docs/privacy.md`): lines between `<!-- repository only -->` and
    /// `<!-- /repository only -->`, which GitHub does not draw, are not shown, and a paragraph they cut is two.
    func testRepositoryOnlyWordsStayOffTheTV() {
        let blocks = PrivacyText.parse("""
        # Privacy

        For everyone,
        on two lines.
        <!-- repository only -->
        For the repository's readers.
        <!-- /repository only -->
        After them.

        <!-- repository only -->
        ## A section of the repository's
        Its words.
        <!-- /repository only -->
        """)
        XCTAssertEqual(blocks, [
            .title("Privacy"),
            .paragraph([PrivacyRun(text: "For everyone, on two lines.")]),
            .paragraph([PrivacyRun(text: "After them.")]),
        ])
    }

    /// The bundled text names no file, pull request or repository: the TV's reader has none of them.
    func testTheBundledTextKeepsTheRepositoryOff() {
        let words = PrivacyText.bundled().map { block -> String in
            switch block {
            case .title(let text), .heading(let text): text
            case .paragraph(let runs), .bullet(let runs): PrivacyText.plain(runs)
            }
        }.joined(separator: "\n")
        XCTAssertFalse(words.isEmpty, "the bundled text")
        for repositoryWord in ["pull request", "repository", ".md", "<!--", "-->"] {
            XCTAssertFalse(words.contains(repositoryWord), "\"\(repositoryWord)\" on the TV")
        }
    }
}
