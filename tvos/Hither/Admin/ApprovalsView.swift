import HitherCore
import SwiftUI

/// Everyone's requests as the TV's admin (Android's `ApprovalsScreen`, M5, DECISIONS 94) as tvOS's Library (Tom's D1
/// and D3, DECISIONS 259): Seerr's "Requests" as the page's title; on the left the approver it acts as and the filters
/// Pending, Approved and All (Tom's three of Seerr's nine), the chosen one ticked; on the right the requests as a grid of
/// the app's lockups, each with its status and "3 days ago by User 8" under the title. Select on a request still to
/// decide opens the system's dialog: Approve, Decline (destructive, and it asks first) and Open Title; on any other it
/// opens the title, as before. When a decision is in, focus moves on to the next request still to decide (else the
/// chosen filter) before the lockup takes its new status. The page is the admin's: the home takes it away when the admin
/// goes.
struct ApprovalsView: View {
    /// Kept by the page itself, so the home drawing it again does not start the list again.
    @State var model: ApprovalsModel
    let approverName: String
    var onTitle: (TitleCard) -> Void

    @FocusState private var focus: String?
    /// The request whose dialog is open.
    @State private var choosing: RequestRow?
    /// The request Decline is asking about.
    @State private var asking: RequestRow?
    /// Focus has arrived on the page's first list (once the rights are read): later lists leave it where it is.
    @State private var arrived = false
    @State private var now = Date()
    /// The lockup whose title was opened: Menu back from it puts focus there again, over the side list's default focus
    /// (6a's lesson: `.defaultFocus(priority: .userInitiated)` also wins on Menu-back).
    @State private var openedRow: String?

    /// Round the grid inside its scroll view: a lifted poster grows about 5% each way (about 19 points up, 12 to a side).
    static let liftRoom: CGFloat = 24

    /// Tom's three of Seerr's filters (D3), the queue's own first.
    static let filters: [RequestFilter] = [.pending, .approved, .all]

    var body: some View {
        PageScaffold(.pushed(Words.navRequests), id: "page Approvals") {
            LibrarySplit {
                side
            } content: {
                content
            }
        }
        .task { model.open() }
        .onAppear {
            guard let back = openedRow else { return }
            openedRow = nil
            refocus(back)
        }
        .onChange(of: arrival) { _, _ in arrive() }
        .onChange(of: focus) { _, key in
            guard let key, let index = model.rows.firstIndex(where: { $0.key == key }) else { return }
            model.focused(index)
        }
        .onChange(of: model.focusAfter) { _, after in
            // A decision is in: focus moves on first, then the lockup changes, so nothing changes under it.
            guard let after else { return }
            switch after {
            case .row(let key): focus = key
            case .filters: focus = "filter \(model.filter.api)"
            }
            Task {
                try? await Task.sleep(for: .milliseconds(150))
                model.focusMoved()
            }
        }
        .confirmationDialog(
            choosing.map { model.title($0)?.title ?? Words.unknownTitle } ?? "",
            isPresented: Binding(get: { choosing != nil }, set: { if !$0 { choosing = nil } }),
            titleVisibility: .visible,
            presenting: choosing
        ) { row in
            Button(Words.approve) { model.decide(row.id, approve: true) }
                .accessibilityIdentifier("approve \(row.key)")
            Button(Words.decline, role: .destructive) { askDecline(row) }
                .accessibilityIdentifier("decline \(row.key)")
            Button(Words.openTitle) { open(row) }
                .accessibilityIdentifier("open title \(row.key)")
            Button(Words.cancel, role: .cancel) { refocus(row.key) }
                .accessibilityIdentifier("close decision")
        } message: { row in
            Text(Formats.join([requestedWords(row), seasons(row)]) ?? "")
        }
        .alert(Words.declineTitle, isPresented: Binding(get: { asking != nil }, set: { if !$0 { asking = nil } }), presenting: asking) { row in
            Button(Words.cancel, role: .cancel) { refocus(row.key) }
                .accessibilityIdentifier("cancel decline")
            Button(Words.decline, role: .destructive) { model.decide(row.id, approve: false) }
                .accessibilityIdentifier("confirm decline")
        } message: { row in
            Text(Words.declineBody(model.title(row)?.title ?? Words.unknownTitle, row.requester ?? ""))
        }
        // A decision that did not go through (Tom's D4: an alert, no toast); focus goes back to its lockup.
        .problemAlert(model.problem) {
            let back = model.problemRow
            model.problemSeen()
            if let back { refocus(back) }
        }
    }

    /// The approver the queue acts as, a line if its rights no longer allow deciding, and the filters.
    private var side: some View {
        VStack(alignment: .leading, spacing: 32) {
            Label(approverName, systemImage: "person.badge.shield.checkmark")
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Words.adminActiveAs(approverName))
                .accessibilityIdentifier("approvals as")
            if model.approver != nil, !model.mayDecide {
                Text(Words.approvalsNoRight(model.approver?.displayName ?? approverName))
                    .font(.callout)
                    .foregroundStyle(Tokens.errorText)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("approvals no right")
            }
            SideList(id: "request filters") {
                ForEach(Self.filters, id: \.self) { filter in
                    SideRow(words: filter.words, chosen: filter == model.filter, id: "request filter \(filter.api)") {
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
        }
    }

    /// Changes when the list has come (or failed) and the rights are read.
    private var arrival: String { "\(model.body) \(model.rightsRead)" }

    /// The first place for focus on the page: the first request to decide, else the first request, else the chosen
    /// filter. Only once the rights are read, since they decide which requests can be decided; and once per page.
    private func arrive() {
        guard !arrived, model.body != .loading, model.rightsRead else { return }
        arrived = true
        let target = model.rows.first(where: { model.decisions($0) })?.key ?? model.rows.first?.key ?? "filter \(model.filter.api)"
        focus = target
        // Once more after the lockups have been drawn: the first ask can come before they take focus.
        Task {
            try? await Task.sleep(for: .milliseconds(150))
            if focus != target { focus = target }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.body {
        case .loading:
            StateView(.loading, Words.approvalsLoading, id: "approvals loading")
        case .failed:
            StateView(.failed, Words.approvalsFailed, id: "approvals failed") {
                Button(Words.tryAgain) { model.retry() }
                    .accessibilityIdentifier("try again")
            }
        case .empty:
            StateView(.empty, Words.noResults, id: "approvals empty") {
                if model.filter != .all {
                    Button(Words.showAllRequests) {
                        model.choose(.all)
                        focus = "filter all"
                    }
                    .accessibilityIdentifier("show all")
                }
            }
        case .rows:
            // Cut at the title's foot as it scrolls (scrolled lockups would draw over the title), so the scroll view
            // keeps the lift's room round the lockups: above the first line, and at the sides, where a lifted lockup
            // in the first or last column grows past the grid's edge.
            ScrollView(.vertical) {
                LockupGrid {
                    ForEach(model.rows, id: \.key) { row in lockup(row) }
                    if model.loading {
                        PlaceholderCard()
                    }
                }
                .padding(.horizontal, Self.liftRoom)
                .padding(.top, Self.liftRoom)
                .padding(.bottom, 120)
            }
            .padding(.horizontal, -Self.liftRoom)
            .scrollIndicators(.hidden)
        }
    }

    /// One of everyone's requests as a lockup: its status, and when and who asked under it ("Approving…" while a
    /// decision is out). Select asks what to do where there is a decision to make, else opens the title.
    private func lockup(_ row: RequestRow) -> some View {
        let card = RequestsView.card(row, title: model.title(row), failed: model.names.failed(row.titleKey))
        return TitleCardView(
            card: card,
            status: row.badge.map { LockupStatus(kind: .request($0), line: line(row), short: model.deciding?.id == row.id ? nil : row.requester) },
            id: "approval \(row.key)",
            details: Formats.join([seasons(row), modifiedWords(row)])
        ) {
            if model.decisions(row) {
                if model.deciding == nil { choosing = row }
            } else {
                open(row)
            }
        }
        .focused($focus, equals: row.key)
    }

    /// "Approving…" or "Declining…" while the decision is out; else when and who asked.
    private func line(_ row: RequestRow) -> String? {
        if let out = model.deciding, out.id == row.id { return out.approve ? Words.approving : Words.declining }
        return requestedWords(row)
    }

    /// "3 days ago by User 8", or either alone.
    private func requestedWords(_ row: RequestRow) -> String? {
        switch (Formats.relative(row.requestedAt, now: now), row.requester) {
        case let (when?, who?): Words.whenBy(when, who)
        case let (when?, nil): when
        case let (nil, who?): who
        default: nil
        }
    }

    /// A series request's seasons: "Seasons 1, 2".
    private func seasons(_ row: RequestRow) -> String? {
        row.seasons.isEmpty ? nil : "\(row.seasons.count == 1 ? Words.seasonOne : Words.seasons) \(row.seasons.joined(separator: ", "))"
    }

    /// Seerr's "Modified 2 days ago by User 1", once somebody has moved it on.
    private func modifiedWords(_ row: RequestRow) -> String? {
        guard let who = row.modifiedBy, let when = Formats.relative(row.modifiedAt, now: now) else { return nil }
        return "\(Words.modified) \(Words.whenBy(when, who))"
    }

    private func open(_ row: RequestRow) {
        openedRow = row.key
        onTitle(RequestsKt.requestCard(row: row, title: model.title(row)))
    }

    /// Decline asks first (DECISIONS 94: Seerr cannot take a decline back), once the dialog has gone.
    private func askDecline(_ row: RequestRow) {
        Task {
            try? await Task.sleep(for: .milliseconds(300))
            asking = row
        }
    }

    /// Focus back on [key] once the dialog's or the page's own focus has settled.
    private func refocus(_ key: String) {
        Task {
            try? await Task.sleep(for: .milliseconds(150))
            if model.rows.contains(where: { $0.key == key }) { focus = key }
        }
    }
}
