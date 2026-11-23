import HitherCore
import SwiftUI

/// Which card has focus on a page of rows: the row (or Recent Requests) and the card's key.
struct CardFocus: Hashable {
    let row: String
    let key: String
}

/// Discover (Android's `DiscoverScreen`, ref 2.4 and 2.6): the server's rows in its order and words, Recent Requests
/// among them, each a shelf of tvOS lockups under its header (Tom's native look, DECISIONS 189, 191), under the system
/// tab bar (181). Every row is a focus section that comes back to the lockup it was left on; Menu from the rows goes to
/// the tab bar, the system's own doing.
struct DiscoverView: View {
    let model: DiscoverModel
    /// Whether Discover is the tab showing: arrival takes focus only then.
    var current: Bool
    var onSeeMore: (RowModel) -> Void
    var onTitle: (TitleCard) -> Void

    @FocusState private var cardFocus: CardFocus?
    /// Focus has come to the rows since this page appeared: arrival is done, nothing more is taken from the bar.
    @State private var arrived = false

    static let recentName = "RECENT_REQUESTS"
    /// The screen less a row of lockups: the last row can come up to the line too.
    private static let afterLastRow = 1080 - Metrics.topMargin - (Metrics.aboveShelf + 46 + Metrics.belowShelfTitle + Metrics.posterHeight + Metrics.captionHeight)

    private enum Section: Identifiable {
        case recent
        case row(RowModel)

        var id: String {
            switch self {
            case .recent: DiscoverView.recentName
            case .row(let row): row.id
            }
        }
    }

    private var sections: [Section] {
        var sections = model.rows.map(Section.row)
        let recent = model.recent
        if model.recentShown, recent.loading || !recent.rows.isEmpty {
            sections.insert(.recent, at: min(model.recentAt, sections.count))
        }
        return sections
    }

    /// Where focus goes on arriving at Discover (Android's arrival rule, 149): the card the rows were left on, else the
    /// top row's first card, once it is there. Nil while the plan is read and the top row has no cards yet.
    private var arrivalTarget: CardFocus? {
        if let last = model.lastFocus { return last }
        guard model.planned, let top = sections.first else { return nil }
        switch top {
        case .recent:
            return model.recent.rows.first.map { CardFocus(row: Self.recentName, key: $0.key) }
        case .row(let row):
            return row.cards.first.map { CardFocus(row: row.id, key: $0.key) }
        }
    }

    var body: some View {
        // The shelves run to the screen's sides; their own 80-point margin is tvOS's (the safe area would double it). The
        // first shelf's header brings the room under the bar, as every shelf's brings the room above it, so Down brings
        // each row to the same line (DECISIONS 252).
        PageScaffold(.shelves, bleed: .horizontal, id: "page Discover") { rows }
    }

    private var rows: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(sections) { section in
                        switch section {
                        case .recent:
                            RecentRow(model: model, focus: $cardFocus, onTitle: onTitle)
                        case .row(let row):
                            DiscoverRowView(row: row, model: model, focus: $cardFocus, onSeeMore: onSeeMore, onTitle: onTitle)
                        }
                    }
                }
                // Room after the last row, so that it too can come up to the line (Android's AfterLastRow).
                .padding(.bottom, Self.afterLastRow)
            }
            .scrollIndicators(.hidden)
            .onChange(of: arrivalTarget, initial: true) { _, target in
                // The tab bar holds focus while the rows come (on Discover, where tvOS puts it first); the card takes it
                // when it is there, unless focus has gone to another tab meanwhile.
                guard !arrived, current, let target, cardFocus == nil else { return }
                cardFocus = target
            }
            .onChange(of: cardFocus?.row) { _, row in
                // The focused row's header comes up to the top: Down brings every row to the same line, and the eye
                // stays put (Android's DECISIONS 40).
                guard let row else { return }
                withAnimation(.easeInOut(duration: 0.3)) { proxy.scrollTo(row, anchor: .top) }
            }
            .onChange(of: cardFocus) { _, now in
                guard let now else { return }
                arrived = true
                model.lastFocus = now
                if now.row == Self.recentName {
                    model.recentFocused(now.key)
                } else if let row = model.row(named: now.row) {
                    let index = now.key == RowModel.seeMoreKey ? row.shown.count : (row.cards.firstIndex { $0.key == now.key } ?? 0)
                    model.focused(row, key: now.key, index: index)
                }
            }
        }
    }
}

/// One of Discover's rows: its cards (paged as focus nears the end), See More where Seerr links the row, and while
/// loading, failed or empty what stands in their place.
private struct DiscoverRowView: View {
    let row: RowModel
    let model: DiscoverModel
    var focus: FocusState<CardFocus?>.Binding
    var onSeeMore: (RowModel) -> Void
    var onTitle: (TitleCard) -> Void

    var body: some View {
        let name = row.row.name
        let rowHeight = Metrics.posterHeight + Metrics.captionHeight
        VStack(alignment: .leading, spacing: 0) {
            ShelfTitle(title: row.row.title)
            switch row.body {
            case .cards:
                let mixed = mixesKinds(row.shown)
                Shelf {
                    ForEach(row.shown, id: \.key) { card in
                        TitleCardView(card: card, mixed: mixed) { onTitle(card) }
                            .focused(focus, equals: CardFocus(row: name, key: card.key))
                    }
                    if row.showsSeeMore {
                        SeeMoreCard(row: row.row, posters: row.seeMorePosters, onSelect: { onSeeMore(row) })
                            .focused(focus, equals: CardFocus(row: name, key: RowModel.seeMoreKey))
                    } else if row.loading {
                        PlaceholderCard()
                    } else if row.failed {
                        Button(Words.tryAgain) { model.retry(row) }
                            .frame(height: Metrics.posterHeight)
                    }
                }
            case .loading:
                // Inside a shelf, as the cards will be: its scroll view holds the row to the screen's width. A bare row of
                // seven placeholders was 2118 points wide, and the page centred it, 99 points to the left (the review's F1).
                Shelf(lazy: false) {
                    // Each the lockup's whole height, from the top: a shelf centres what is shorter than its row.
                    ForEach(0..<7, id: \.self) { _ in PlaceholderCard().frame(height: rowHeight, alignment: .top) }
                }
                .accessibilityIdentifier("row loading")
            case .failed:
                StateView(.failed, Words.rowFailed, id: "row failed") {
                    Button(Words.tryAgain) { model.retry(row) }
                        .accessibilityIdentifier("try again")
                }
                .frame(height: rowHeight)
            case .empty:
                StateView(.empty, Words.noResults, symbol: "magnifyingglass", id: "row empty")
                    .frame(height: rowHeight)
            }
        }
        .focusSection()
        .defaultFocus(focus, CardFocus(row: name, key: row.lastFocused ?? row.cards.first?.key ?? ""), priority: .userInitiated)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("row \(name)")
    }
}

/// Recent Requests as Seerr's slider (Android's `RecentRow`, DECISIONS 84, 118): its header, then the requests as
/// backdrop lockups.
private struct RecentRow: View {
    let model: DiscoverModel
    var focus: FocusState<CardFocus?>.Binding
    var onTitle: (TitleCard) -> Void

    var body: some View {
        let recent = model.recent
        let name = DiscoverView.recentName
        let height = Metrics.backdropHeight + Metrics.captionHeight
        VStack(alignment: .leading, spacing: 0) {
            ShelfTitle(title: Words.recentRequests)
            if recent.rows.isEmpty {
                Shelf(height: height, lazy: false) {
                    ForEach(0..<4, id: \.self) { _ in
                        PlaceholderCard(width: Metrics.backdropWidth, height: Metrics.backdropHeight)
                            .frame(height: height, alignment: .top)
                    }
                }
                .accessibilityIdentifier("row loading")
            } else {
                Shelf(height: height) {
                    ForEach(recent.rows.prefix(Int(RecentModel.take)), id: \.key) { row in
                        RequestCardView(
                            row: row,
                            title: recent.titles[row.titleKey],
                            failed: recent.failedTitles.contains(row.titleKey),
                            requester: model.seesRequesters ? row.requester : nil,
                            onSelect: onTitle
                        )
                        .focused(focus, equals: CardFocus(row: name, key: row.key))
                    }
                }
            }
        }
        .focusSection()
        .defaultFocus(focus, CardFocus(row: name, key: recent.lastFocused ?? recent.rows.first?.key ?? ""), priority: .userInitiated)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("row \(name)")
    }
}
