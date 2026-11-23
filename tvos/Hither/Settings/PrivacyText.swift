import Foundation

/// A block of the privacy text (Android's `PrivacyBlock`).
enum PrivacyBlock: Equatable {
    case title(String)
    case heading(String)
    case paragraph([PrivacyRun])
    case bullet([PrivacyRun])
}

/// A stretch of text, bold or not.
struct PrivacyRun: Equatable {
    let text: String
    var bold = false
}

/// `docs/privacy-tvos.md`, the policy a store listing points at, read for the screen (Android's `parsePrivacy` in
/// Swift). Only what the file uses is understood: `#` and `##` headings, paragraphs, `- ` bullets with indented
/// continuation lines, `**bold**`, `` `code` `` and `<links>`. Lines between `<!-- repository only -->` and
/// `<!-- /repository only -->` are for the repository's readers (a file's name, how the text changes) and are not shown:
/// GitHub draws neither marker, and the TV draws nothing between them (the review's F37).
enum PrivacyText {
    static let repositoryOnly = "<!-- repository only -->"
    static let repositoryOnlyEnd = "<!-- /repository only -->"

    /// The file the build bundled; none if it is missing (the page is then empty).
    static func bundled() -> [PrivacyBlock] {
        guard let url = Bundle.main.url(forResource: "privacy-tvos", withExtension: "md"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return parse(text)
    }

    static func parse(_ markdown: String) -> [PrivacyBlock] {
        var blocks: [PrivacyBlock] = []
        var pending = ""
        var pendingIsBullet = false

        func flush() {
            if !pending.isEmpty {
                let runs = inline(pending)
                blocks.append(pendingIsBullet ? .bullet(runs) : .paragraph(runs))
                pending = ""
            }
            pendingIsBullet = false
        }

        var skipping = false
        for raw in markdown.components(separatedBy: "\n") {
            let line = raw.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
            let marker = line.trimmingCharacters(in: .whitespaces)
            if marker == repositoryOnly || marker == repositoryOnlyEnd {
                // A marker ends the paragraph it cuts, as on GitHub.
                flush()
                skipping = marker == repositoryOnly
            } else if skipping {
                continue
            } else if marker.isEmpty {
                flush()
            } else if line.hasPrefix("## ") {
                flush()
                blocks.append(.heading(plain(inline(String(line.dropFirst(3))))))
            } else if line.hasPrefix("# ") {
                flush()
                blocks.append(.title(plain(inline(String(line.dropFirst(2))))))
            } else if line.hasPrefix("- ") {
                flush()
                pendingIsBullet = true
                pending = line.dropFirst(2).trimmingCharacters(in: .whitespaces)
            } else {
                if !pending.isEmpty { pending += " " }
                pending += line.trimmingCharacters(in: .whitespaces)
            }
        }
        flush()
        return blocks
    }

    /// The text of [runs] without the emphasis, for tests and headings.
    static func plain(_ runs: [PrivacyRun]) -> String { runs.map(\.text).joined() }

    /// The runs as one attributed string, the bold ones semibold.
    static func attributed(_ runs: [PrivacyRun]) -> AttributedString {
        var result = AttributedString()
        for run in runs {
            var part = AttributedString(run.text)
            if run.bold { part.inlinePresentationIntent = .stronglyEmphasized }
            result += part
        }
        return result
    }

    private static let pattern = try! NSRegularExpression(pattern: #"\*\*(.+?)\*\*|`([^`]+)`|<(https?://[^>]+)>"#)

    /// Bold runs from `**…**`; code and links become plain text; neighbours of the same weight are one run.
    private static func inline(_ text: String) -> [PrivacyRun] {
        var runs: [PrivacyRun] = []
        let whole = text as NSString
        var at = 0
        for match in pattern.matches(in: text, range: NSRange(location: 0, length: whole.length)) {
            if match.range.location > at {
                runs.append(PrivacyRun(text: whole.substring(with: NSRange(location: at, length: match.range.location - at))))
            }
            if match.range(at: 1).location != NSNotFound {
                runs.append(PrivacyRun(text: whole.substring(with: match.range(at: 1)).replacingOccurrences(of: "`", with: ""), bold: true))
            } else if match.range(at: 2).location != NSNotFound {
                runs.append(PrivacyRun(text: whole.substring(with: match.range(at: 2))))
            } else {
                runs.append(PrivacyRun(text: whole.substring(with: match.range(at: 3))))
            }
            at = match.range.location + match.range.length
        }
        if at < whole.length { runs.append(PrivacyRun(text: whole.substring(from: at))) }
        var merged: [PrivacyRun] = []
        for run in runs {
            if let last = merged.last, last.bold == run.bold {
                merged[merged.count - 1] = PrivacyRun(text: last.text + run.text, bold: run.bold)
            } else {
                merged.append(run)
            }
        }
        return merged
    }
}
