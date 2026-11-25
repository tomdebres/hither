import HitherCore
import SwiftUI

/// The Search tab (Android's `SearchScreen`, DECISIONS 100; the system's search on tvOS, 181): tvOS's own search field,
/// keyboard, dictation and suggestions (`searchable`), and under it the results six across as tvOS lockups (DECISIONS
/// 193): titles as poster lockups with their status glyphs, people as portraits, or Android's words for why there are
/// none. A title's card opens its page
/// on this tab's stack, a person's theirs; Menu comes back to the card.
struct SearchView: View {
    @Bindable var model: SearchModel
    var onTitle: (TitleCard) -> Void
    var onPerson: (PersonCard) -> Void

    var body: some View {
        PageScaffold(.tab, id: "page Search") { content }
            .searchable(text: $model.typed, prompt: Words.searchPlaceholder)
            .searchSuggestions {
                ForEach(model.suggestions, id: \.self) { name in
                    Text(name).searchCompletion(name)
                }
            }
            .onSubmit(of: .search) { model.submit() }
    }

    @ViewBuilder
    private var content: some View {
        switch model.body {
        case .empty:
            StateView(.empty, Words.searchEmpty, symbol: "magnifyingglass", id: "search notice")
                .padding(.top, 80)
        case .noResults:
            // The system's own words for a search that found nothing (`ContentUnavailableView.search`; the review's S37).
            StateView(.empty, Words.noResultsFor(model.query ?? model.typed), detail: Words.noResultsHint, symbol: "magnifyingglass", id: "no results")
                .padding(.top, 80)
        case .loading:
            HStack(spacing: CardGrid.gap) {
                ForEach(0..<TitleGridModel.columns, id: \.self) { _ in PlaceholderCard() }
            }
            .padding(.horizontal, Metrics.sideMargin)
            .padding(.top, 48)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("search loading")
        case .failed:
            StateView(.failed, Words.searchFailed, id: "search failed") {
                Button(model.retrying ? Words.trying : Words.tryAgain) { model.retry() }
                    .accessibilityIdentifier("try again")
            }
            .padding(.top, 80)
        case .results:
            ScrollView(.vertical) {
                SearchGrid(model: model, onTitle: onTitle, onPerson: onPerson)
            }
            .scrollClipDisabled()
            .scrollIndicators(.hidden)
        }
    }
}

/// The results six across (Android's `CardGrid` with search's people): poster lockups and portraits, paged as focus
/// nears the end, Try Again at the end when a later page failed.
private struct SearchGrid: View {
    let model: SearchModel
    var onTitle: (TitleCard) -> Void
    var onPerson: (PersonCard) -> Void
    @FocusState private var focus: String?

    var body: some View {
        let columns = Array(repeating: GridItem(.fixed(Metrics.posterWidth), spacing: CardGrid.gap, alignment: .top), count: TitleGridModel.columns)
        let mixed = mixesKinds(model.cards.compactMap { $0 as? TitleCard })
        LazyVGrid(columns: columns, alignment: .leading, spacing: Metrics.gridRowGap) {
            ForEach(Array(model.cards.enumerated()), id: \.element.key) { _, card in
                cell(card, mixed: mixed).focused($focus, equals: card.key)
            }
            ListEnd(loadingMore: false, failed: model.moreFailed, retrying: model.retryingMore, focus: $focus, id: "try again") { model.retry() }
        }
        .listEndHandOff($focus, failed: model.moreFailed, keys: model.cards.map(\.key))
        .padding(.horizontal, Metrics.sideMargin)
        .padding(.top, 48)
        .padding(.bottom, 96)
        .focusSection()
        .onChange(of: focus) { _, key in
            if let key, let index = model.cards.firstIndex(where: { $0.key == key }) { model.focused(index) }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("search results")
    }

    @ViewBuilder
    private func cell(_ card: any GridCard, mixed: Bool) -> some View {
        if let title = card as? TitleCard {
            TitleCardView(card: title, mixed: mixed) { onTitle(title) }
        } else if let person = card as? PersonCard {
            // A portrait in a poster's column: the circle the poster's width, the name under it.
            PersonCardView(id: "card \(person.key)", name: person.name, part: nil, photo: person.photo) { onPerson(person) }
                .frame(width: Metrics.posterWidth)
        }
    }
}
