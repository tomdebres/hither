import HitherCore
import SwiftUI

// The list pages in tvOS's Library way (the review's F15 and F16, X3; Tom's D1 and D3, DECISIONS 259): My Requests,
// Issues and the approval queue are each a narrow side list on the left (what to show, and the ways in) and a grid of
// the app's own lockups on the right, as the Apple TV app's Library lays out its categories and their titles. Before
// this they were Seerr's web table rows with coloured buttons beside them, and filters as rows of pills.

extension Metrics {
    /// The side list's width: room for "Waiting for Approval" and its count in a row of the system's list.
    static let sideListWidth: CGFloat = 440
    /// Between the side list and the grid.
    static let splitGap: CGFloat = 80
    /// A list's lockups across the grid: tvOS's six, less the side list's room.
    static let listColumns = 4
    /// The width the split leaves the grid, between the side list and the right margin.
    static let listGridWidth: CGFloat = 1920 - 2 * sideMargin - sideListWidth - splitGap
    /// A list's lockups on the screen at once, two lines of four, and so how near the end focus comes before the next
    /// page is asked for (a list's model's paging: core's `wantsNextPage`).
    static let listPageAhead: Int32 = 8
}

/// The split: [side] on the left, [content] (a `LockupGrid` or the state in its place) on the right. Each side is a
/// focus section the page's height (6a's lesson: a section is only as wide and tall as its content), so Left from any
/// lockup reaches the list and Right from the list reaches the grid.
struct LibrarySplit<Side: View, Content: View>: View {
    @ViewBuilder var side: Side
    @ViewBuilder var content: Content

    var body: some View {
        HStack(alignment: .top, spacing: Metrics.splitGap) {
            VStack(alignment: .leading, spacing: 0) { side }
                .frame(width: Metrics.sideListWidth, alignment: .topLeading)
                .frame(maxHeight: .infinity, alignment: .topLeading)
                .focusSection()
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .focusSection()
        }
    }
}

/// The side list: the system's list of rows, as tvOS's Settings draws its own (words, a value at the right, the tick
/// on the one chosen), with no scrolling of its own and no background.
struct SideList<Rows: View>: View {
    let id: String
    @ViewBuilder var rows: Rows

    var body: some View {
        VStack(alignment: .leading, spacing: 12) { rows }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(id)
    }
}

/// A row of the side list: its words, and at its right what it holds (a count), the system's tick on the one chosen
/// (tvOS's way of saying which of several is chosen: rule 3, never a fill), or the chevron of a row that opens a page.
/// Select chooses it, or opens what it leads to.
struct SideRow: View {
    let words: String
    var chosen = false
    /// What the row holds, at its right in secondary (a count).
    var value: String?
    /// The row opens a page of its own (the queue): the system's chevron.
    var opens = false
    let id: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Text(words)
                    .lineLimit(1)
                Spacer(minLength: 16)
                if let value {
                    Text(value)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                if chosen {
                    Image(systemName: "checkmark")
                        .fontWeight(.semibold)
                        .accessibilityHidden(true)
                }
                if opens {
                    Image(systemName: "chevron.forward")
                        .foregroundStyle(.secondary)
                        .accessibilityHidden(true)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        // The words as its label and what it holds as its value: VoiceOver reads "Waiting for Approval, 3".
        .accessibilityLabel(words)
        .accessibilityAddTraits(chosen ? .isSelected : [])
        .accessibilityValue(chosen ? "selected" : (value ?? ""))
        .accessibilityIdentifier(id)
    }
}

/// The split's lockups, four across (the six of tvOS's grid less the side list's room), with the gaps the width leaves.
/// Lazy, as a list can run long; it pages as focus nears its end (the page's model).
struct LockupGrid<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        let count = Metrics.listColumns
        let gap = (Metrics.listGridWidth - CGFloat(count) * Metrics.posterWidth) / CGFloat(count - 1)
        let columns = Array(repeating: GridItem(.fixed(Metrics.posterWidth), spacing: gap, alignment: .top), count: count)
        LazyVGrid(columns: columns, alignment: .leading, spacing: Metrics.gridRowGap) { content }
            .frame(width: Metrics.listGridWidth, alignment: .leading)
    }
}

/// A list lockup's status caption (the review's X3), under its title in place of the year: the status as its glyph and
/// word (`StatusLabel`, or an issue's Open or Resolved), and a line under it saying when, and who or what where the list
/// has it ("3 days ago by User 8", "Subtitle · 3 days ago"), every time through `Formats`.
struct LockupStatus {
    enum Kind {
        /// A request's status: Seerr's badge.
        case request(StatusBadge)
        /// An issue's: Open (Seerr's warning yellow) or Resolved (its green).
        case issue(open: Bool, words: String)
    }

    let kind: Kind
    /// The line under the status; nil for none.
    let line: String?
    /// The part of [line] that matters most, drawn when the whole does not fit a poster's width ("33 seconds ago by
    /// User 1" does not): when, for the user's own requests; who asked, in the queue. VoiceOver reads the whole.
    var short: String?

    var words: String {
        switch kind {
        case .request(let badge): badge.text
        case .issue(_, let words): words
        }
    }
}

/// The status caption's two lines, in the caption's own style (Caption 2, secondary).
struct LockupStatusLines: View {
    let status: LockupStatus

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            switch status.kind {
            case .request(let badge):
                StatusLabel(badge: badge)
            case .issue(let open, let words):
                IssueStatusLabel(open: open, words: words)
            }
            ViewThatFits(in: .horizontal) {
                Text(status.line ?? " ")
                if let short = status.short { Text(short) }
            }
            .accessibilityElement(children: .combine)
            .accessibilityHidden(status.line == nil)
            .accessibilityIdentifier("card when")
        }
    }
}

/// An issue's status as a glyph and its word: Seerr's warning yellow for Open, its green for Resolved, drawn as every
/// status glyph is (a disc, the mark that reads on it: the review's F27).
struct IssueStatusLabel: View {
    let open: Bool
    let words: String

    var body: some View {
        HStack(spacing: 10) {
            Glyph(symbol: open ? "exclamationmark.circle.fill" : "checkmark.circle.fill", kind: open ? .warning : .success)
            Text(words).lineLimit(1)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(words)
        .accessibilityIdentifier("issue status \(words)")
    }
}
