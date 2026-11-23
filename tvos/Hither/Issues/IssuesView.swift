import HitherCore
import SwiftUI

/// Seerr's Issues page, the Issues tab's (Android's `IssuesScreen`, DECISIONS 106), as tvOS's Library (Tom's D1,
/// DECISIONS 259): Seerr's filters (Open, Resolved, All) as the side list, the chosen one ticked; and the issues as a
/// grid of the app's lockups, each with its status (Open as a warning, Resolved as done) under the title and a line of
/// what and when ("Subtitle · 3 days ago"). Select on a filter chooses it; Select on a lockup opens the title's page and
/// Menu comes back to the lockup. An empty filter offers Seerr's "Show All Issues"; a failure Try Again; Seerr's refusal
/// says the account may no longer see issues.
///
/// Down from the tab bar lands on the chosen filter; Right goes to the grid, Left from it back to the list.
struct IssuesView: View {
    let model: IssuesModel
    /// The tab's page is showing: each showing reads the list again (not coming back from a lockup's title).
    let current: Bool
    var onTitle: (TitleCard) -> Void

    @FocusState private var focus: String?
    /// The lockup whose title was opened: Menu back from it puts focus there again, over the side list's default focus.
    @State private var openedRow: String?

    var body: some View {
        PageScaffold(.tab, id: "page Issues") {
            LibrarySplit {
                SideList(id: "issue filters") {
                    ForEach(IssueFilter.allCases, id: \.self) { filter in
                        SideRow(words: filter.label, chosen: filter == model.filter, id: "issue filter \(filter.api)") {
                            if filter == model.filter {
                                // The chosen one again: into its lockups.
                                if let first = model.rows.first { focus = first.key }
                            } else {
                                model.choose(filter)
                            }
                        }
                        .focused($focus, equals: "filter \(filter.api)")
                    }
                }
                .defaultFocus($focus, "filter \(model.filter.api)", priority: .userInitiated)
            } content: {
                content
            }
            .defaultFocus($focus, "filter \(model.filter.api)", priority: .userInitiated)
        }
        .onChange(of: current, initial: true) { _, showing in
            guard showing else { return }
            let back = openedRow
            openedRow = nil
            model.open()
            if let back, model.rows.contains(where: { $0.key == back }) {
                Task {
                    // After the page's own focus has settled (the side list's default would take it).
                    try? await Task.sleep(for: .milliseconds(150))
                    focus = back
                }
            }
        }
        .onChange(of: focus) { _, key in
            if let key, let index = model.rows.firstIndex(where: { $0.key == key }) { model.focused(index) }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.body {
        case .loading:
            StateView(.loading, Words.issuesLoading, id: "issues loading")
        case .failed:
            StateView(.failed, Words.issuesFailed, id: "issues failed") {
                Button(Words.tryAgain) { model.retry() }
                    .accessibilityIdentifier("try again")
            }
        case .noRight:
            StateView(.noRight, Words.issuesNoRight, id: "issues no right")
        case .empty:
            StateView(.empty, Words.noResults, id: "issues empty") {
                if model.filter != .all {
                    // Seerr's own way out of an empty filter.
                    Button(Words.showAllIssues) { model.choose(.all) }
                        .accessibilityIdentifier("show all")
                }
            }
        case .rows:
            ScrollView(.vertical) {
                LockupGrid {
                    ForEach(model.rows, id: \.key) { row in
                        lockup(row)
                    }
                }
                .padding(.bottom, 120)
            }
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
        }
    }

    /// One issue as a lockup: the title's poster and name, Open or Resolved, and what and when.
    private func lockup(_ row: IssueRow) -> some View {
        let title = row.titleKey.flatMap(model.names.title)
        let failed = row.titleKey.map(model.names.failed) ?? true
        let name = title?.title ?? (failed ? Words.unknownTitle : "")
        let card = TitleCard(key: row.titleKey ?? row.key, tmdbId: row.tmdbId, type: row.type ?? .movie, title: name, year: title?.year, poster: title?.poster, badge: nil, overview: nil)
        return TitleCardView(
            card: card,
            status: LockupStatus(kind: .issue(open: row.open, words: row.status), line: Formats.join([row.kind, Formats.relative(row.createdAt)]), short: row.kind),
            id: row.key,
            details: details(row)
        ) {
            openedRow = row.key
            open(row)
        }
        .focused($focus, equals: row.key)
    }

    /// What VoiceOver reads after the line: a series' season and episode, who opened it, and what they wrote.
    private func details(_ row: IssueRow) -> String? {
        let place = Formats.join(row.problem.map { "\($0.name) \($0.value)" })
        let by = row.by.map(Words.openedBy)
        return Formats.join([place, by, row.reportText])
    }

    private func open(_ row: IssueRow) {
        guard let type = row.type, let key = row.titleKey else { return }
        let title = model.names.title(key)
        model.returnToRow = true
        onTitle(TitleCard(key: key, tmdbId: row.tmdbId, type: type, title: title?.title ?? "", year: title?.year, poster: title?.poster, badge: nil, overview: nil))
    }
}
