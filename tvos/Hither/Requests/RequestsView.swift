import HitherCore
import SwiftUI

/// The Requests tab's page (Android's `RequestsScreen`, DECISIONS 66, 101, 124) as tvOS's Library (Tom's D1, DECISIONS
/// 259): a side list of My Requests and, with the right, Waiting for Approval and its count; and the user's own
/// requests, newest first, as a grid of the app's lockups, each with its status and when under the title (the review's
/// X3). Select opens the title's page; Menu comes back to the lockup. A pending request's long-press menu has Cancel
/// Request, which asks first in the system's alert. While the first page loads the lockups' places stand in; a failure
/// offers Try Again; with no requests the page says so and offers the way back to Discover.
///
/// Down from the tab bar lands on the side list's My Requests, as on the Library's categories; Right goes to the grid.
struct RequestsView: View {
    let model: RequestsModel
    /// The tab's page is showing: every showing after the first reads the list again.
    let current: Bool
    /// "Waiting for Approval"'s count (DECISIONS 79, 94; the review's S33), above zero only; nil for nothing to say.
    var waiting: Int? = nil
    /// The way into everyone's requests while an admin is kept on the TV: the row is there whatever the count. Nil: the
    /// row is the count in words only, shown above zero (an account that may approve itself).
    var onWaiting: (() -> Void)? = nil
    var onTitle: (TitleCard) -> Void
    var onDiscover: () -> Void

    @FocusState private var focus: String?
    @State private var asking: RequestRow?
    @State private var now = Date()
    /// The lockup whose title was opened: Menu back from it puts focus there again, over the side list's default focus
    /// (6a's lesson: `.defaultFocus(priority: .userInitiated)` also wins on Menu-back).
    @State private var openedRow: String?
    /// Where focus last was on the page, so a state's action can take it when its answer replaces it (Try Again's).
    @State private var lastFocus: String?
    /// A cancel that did not go through, for the system's alert; and the lockup focus goes back to after it.
    @State private var problem: AlertText?
    @State private var problemRow: String?

    static let mine = "side my requests"

    var body: some View {
        PageScaffold(.tab, id: "page Requests") {
            LibrarySplit {
                SideList(id: "requests list") {
                    // The page's one category, so it is always the chosen one; Select goes into its lockups.
                    SideRow(words: Words.myRequests, chosen: true, id: Self.mine) { focus = model.rows.first?.key }
                        .focused($focus, equals: Self.mine)
                    waitingRow
                }
            } content: {
                content
            }
            .defaultFocus($focus, Self.mine, priority: .userInitiated)
        }
        .alert(Words.cancelRequestTitle, isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }), presenting: asking) { row in
            Button(Words.keepRequest, role: .cancel) { refocus(row.key) }
                .accessibilityIdentifier("keep request")
            Button(Words.cancelRequest, role: .destructive) { confirm(row) }
                .accessibilityIdentifier("confirm cancel request")
        } message: { row in
            Text(Words.cancelRequestBody(model.title(row)?.title))
        }
        .problemAlert(problem) {
            problem = nil
            if let key = problemRow { refocus(key) }
            problemRow = nil
        }
        .onChange(of: current, initial: true) { _, showing in
            guard showing else { return }
            model.open()
            if let back = openedRow {
                openedRow = nil
                refocus(back)
            }
        }
        .onChange(of: focus) { _, key in
            if key != nil { lastFocus = key }
            if let key, let index = model.rows.firstIndex(where: { $0.key == key }) {
                model.focused(index)
            } else {
                model.left()
            }
        }
        .onChange(of: model.body) { _, body in
            // Try Again had focus and its answer replaced it: the new state's action takes it, or the first lockup (a
            // control that goes leaves focus on the tab bar otherwise).
            guard lastFocus == "try again" else { return }
            let next: String? = switch body {
            case .failed: "try again"
            case .empty: "back to discover"
            case .rows: model.rows.first?.key
            case .loading: nil
            }
            guard let next else { return }
            Task {
                try? await Task.sleep(for: .milliseconds(150))
                focus = next
            }
        }
        .onChange(of: model.newTop) { _, _ in
            // Newer requests came above the top lockup while focus was on it: the newest takes focus.
            if let key = focus, model.rows.contains(where: { $0.key == key }) || key == "retry more" {
                focus = model.rows.first?.key
            }
        }
        .task(id: downloading) {
            // While a lockup shows a download, the list is read again once a minute (Seerr's download sync), and the
            // estimates move on with it.
            while downloading {
                try? await Task.sleep(for: .seconds(60))
                guard !Task.isCancelled else { return }
                model.refresh()
                now = Date()
            }
        }
    }

    private var downloading: Bool { model.rows.contains { $0.badge?.progress != nil } }

    /// Waiting for Approval (the review's S33, in place of the yellow-clock button over My Requests): while an admin is
    /// kept on the TV, a row that opens everyone's requests (asking for the PIN first when the admin is not active), its
    /// count at its right above zero; for an account that may approve itself, the count in words only, dimmed and no
    /// stop for focus; without the right, nothing.
    @ViewBuilder
    private var waitingRow: some View {
        if let onWaiting {
            SideRow(words: Words.waitingRow, value: waiting.map { "\($0)" }, opens: true, id: "waiting", action: onWaiting)
                .accessibilityHint(Words.waitingOpen)
                .focused($focus, equals: "waiting")
        } else if let waiting {
            SideRow(words: Words.waitingRow, value: "\(waiting)", id: "waiting") {}
                .disabled(true)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.body {
        case .loading:
            // The lockups' places, still: the shape of what is coming.
            LockupGrid {
                ForEach(0..<Metrics.listColumns, id: \.self) { _ in PlaceholderCard() }
            }
            .accessibilityIdentifier("loading")
        case .failed:
            StateView(.failed, Words.requestsFailed, id: "requests failed") {
                Button(model.retrying ? Words.trying : Words.tryAgain) { model.retry() }
                    .focused($focus, equals: "try again")
                    .accessibilityIdentifier("try again")
            }
        case .empty:
            StateView(.empty, Words.requestsEmpty, detail: Words.requestsEmptyHint, id: "requests empty") {
                Button(Words.backToDiscover, action: onDiscover)
                    .focused($focus, equals: "back to discover")
                    .accessibilityIdentifier("back to discover")
            }
        case .rows:
            ScrollView(.vertical) {
                LockupGrid {
                    ForEach(model.rows, id: \.key) { row in lockup(row) }
                    if model.moreFailed {
                        Button(model.retryingMore ? Words.trying : Words.tryAgain) { model.retry() }
                            .frame(width: Metrics.posterWidth, height: Metrics.posterHeight)
                            .focused($focus, equals: "retry more")
                            .accessibilityIdentifier("retry more")
                    } else if model.loadingMore {
                        PlaceholderCard()
                    }
                }
                .padding(.bottom, 120)
            }
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
        }
    }

    /// One request as a lockup: the title's poster and name, its status, and when (or who last moved it on, Seerr's
    /// "Modified … by …", Tom's 124; or the download's estimate while one runs). Long-press on a pending one offers
    /// Cancel Request.
    private func lockup(_ row: RequestRow) -> some View {
        let card = Self.card(row, title: model.title(row), failed: model.names.failed(row.titleKey))
        return TitleCardView(
            card: card,
            status: row.badge.map { LockupStatus(kind: .request($0), line: line(row), short: when(row)) },
            id: "request \(row.key)",
            details: details(row)
        ) {
            openedRow = row.key
            onTitle(RequestsKt.requestCard(row: row, title: model.title(row)))
        }
        .focused($focus, equals: row.key)
        .contextMenu {
            if RequestExtrasKt.canCancel(row: row) {
                Button(Words.cancelRequest, role: .destructive) {
                    if model.cancelling == nil { asking = row }
                }
                .accessibilityIdentifier("cancel \(row.key)")
            }
        }
    }

    /// What the line under a request's status says: "Cancelling…" while its DELETE is out; the download's estimate
    /// while one runs; "2 days ago by User 1" once somebody has moved it on; else when it was asked for.
    private func line(_ row: RequestRow) -> String? {
        if model.cancelling == row.id { return Words.cancellingRequest }
        if let estimate = row.badge?.progress.flatMap({ downloadEstimate($0, now: now) }) { return estimate }
        if let who = row.modifiedBy, let when = Formats.relative(row.modifiedAt, now: now) { return Words.whenBy(when, who) }
        return Formats.relative(row.requestedAt, now: now)
    }

    /// When alone, for a line that does not fit: when it was moved on, else asked for.
    private func when(_ row: RequestRow) -> String? {
        Formats.relative(row.modifiedBy == nil ? row.requestedAt : row.modifiedAt, now: now)
    }

    /// What VoiceOver reads after the status: a series' seasons, and when it was asked for where the line says who moved
    /// it on.
    private func details(_ row: RequestRow) -> String? {
        let seasons = row.seasons.isEmpty ? nil : "\(row.seasons.count == 1 ? Words.seasonOne : Words.seasons) \(row.seasons.joined(separator: ", "))"
        let asked = row.modifiedBy == nil ? nil : Formats.relative(row.requestedAt, now: now).map { "\(Words.requested) \($0)" }
        return Formats.join([seasons, asked])
    }

    /// A request's lockup card: the title as far as it has come; "Unknown Title" where its details could not be read.
    static func card(_ row: RequestRow, title: RequestTitle?, failed: Bool) -> TitleCard {
        let card = RequestsKt.requestCard(row: row, title: title)
        guard title == nil, failed else { return card }
        return TitleCard(key: card.key, tmdbId: card.tmdbId, type: card.type, title: Words.unknownTitle, year: nil, poster: nil, badge: card.badge, overview: nil)
    }

    /// Focus back on [key] once the page's own default has settled (the side list's would take it).
    private func refocus(_ key: String) {
        Task {
            try? await Task.sleep(for: .milliseconds(150))
            if model.rows.contains(where: { $0.key == key }) { focus = key }
        }
    }

    /// Cancel Request confirmed: one DELETE. Once it is gone the lockup goes, with focus moved first to the one that
    /// takes its place (the next, else the one before; the side list for the last), so it never lands on nothing: the
    /// lockup going is what it did (Tom's D4: no toast). A cancel that does not go through is the system's alert, and
    /// focus goes back to the lockup.
    private func confirm(_ row: RequestRow) {
        model.cancel(row) { outcome in
            switch outcome {
            case .cancelled(let id, _):
                let index = model.rows.firstIndex { $0.id == id } ?? 0
                let next = model.rows.indices.contains(index + 1) ? model.rows[index + 1] : (index > 0 ? model.rows[index - 1] : nil)
                focus = next?.key ?? Self.mine
                model.drop(id)
            case .notPending:
                problemRow = row.key
                problem = AlertText(title: Words.requestNotCancelled, message: Words.notCancellable)
            case .failed:
                problemRow = row.key
                problem = AlertText(title: Words.requestNotCancelled, message: Words.modifyFailed)
            }
        }
    }
}
