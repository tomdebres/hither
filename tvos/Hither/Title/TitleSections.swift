import HitherCore
import SwiftUI

/// The title page's sections under the first screen, each scrolled to by its header; `end` is the page's foot, under
/// Information.
enum TitleSection: Hashable {
    case seasons, cast, collection, keywords, information, end
    case related(RelatedKind)

    /// The section a focus stop is in; nil for the first screen's.
    init?(_ stop: TitleStop) {
        switch stop {
        case .season: self = .seasons
        case .cast: self = .cast
        case .collection: self = .collection
        case .related(let kind, _): self = .related(kind)
        case .keyword: self = .keywords
        case .loading, .retry, .play, .request, .trailer, .issue, .overview: return nil
        }
    }

    /// The last section focus can go to on [page], whose foot shows Information at rest (Tom's D2 and the review's part
    /// 6: Information takes no focus); nil when focus has nowhere to go under the first screen.
    static func last(_ page: MediaPage, related: [RelatedKind: [TitleCard]]) -> TitleSection? {
        if !page.keywords.isEmpty { return .keywords }
        for kind in [RelatedKind.similar, .recommendations] where !(related[kind] ?? []).isEmpty { return .related(kind) }
        if page.collection != nil { return .collection }
        if !page.cast.isEmpty || !page.crew.isEmpty { return .cast }
        if !page.seasonsToAskFor.isEmpty { return .seasons }
        return nil
    }
}

/// Below the first screen, as the Apple TV app's product page goes on (DECISIONS 193): the seasons a series can still be
/// asked for, Cast & Crew, the collection, Recommendations and Similar, the keywords, then Information, which is read and
/// never focused, at the page's foot. Each shelf is a focus section that comes back to the lockup it was left on.
struct TitleSections: View {
    let page: MediaPage
    let ratings: Ratings?
    let related: [RelatedKind: [TitleCard]]
    var focus: FocusState<TitleStop?>.Binding
    var onSeason: (_ button: RequestButton, _ season: Int32) -> Void
    var onPerson: (_ id: Int32, _ name: String) -> Void
    var onTitle: (TitleCard) -> Void
    var onCollection: (CollectionCard) -> Void
    var onKeyword: (Keyword) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            let seasons = page.seasonsToAskFor
            if !seasons.isEmpty {
                SeasonsShelf(seasons: seasons, focus: focus, onSeason: onSeason)
            }
            if !page.cast.isEmpty || !page.crew.isEmpty {
                CastShelf(cast: page.cast, crew: page.crew, focus: focus, onPerson: onPerson)
            }
            if let collection = page.collection {
                CollectionShelf(card: collection, focus: focus) { onCollection(collection) }
            }
            ForEach([RelatedKind.recommendations, RelatedKind.similar], id: \.self) { kind in
                if let cards = related[kind], !cards.isEmpty {
                    RelatedShelf(kind: kind, title: relatedTitle(kind, page.type), cards: cards, focus: focus, onTitle: onTitle)
                }
            }
            if !page.keywords.isEmpty {
                KeywordShelf(keywords: page.keywords, focus: focus, onKeyword: onKeyword)
            }
            Information(page: page, ratings: ratings)
            // The page's foot: the bottom margin under Information, where the last shelf's focus scrolls the page to.
            Color.clear
                .frame(height: Metrics.topMargin)
                .id(TitleSection.end)
        }
    }

    private func relatedTitle(_ kind: RelatedKind, _ type: MediaType) -> String {
        if kind == .recommendations { return Words.recommendations }
        return type == .movie ? Words.similarTitles : Words.similarSeries
    }
}

/// A season the page offers to ask for, and the request button whose sheet it opens.
struct SeasonToAskFor {
    let line: SeasonLine
    let button: RequestButton
}

extension MediaPage {
    /// The seasons that can still be asked for (Tom's D2), newest first as Seerr lists them: each with the first of the
    /// page's request buttons (Seerr's order: the regular copy's, then the 4K one's) whose copy has it open, as core's
    /// request sheet would list it (`seasonRows`: not in a live request, not in the library or on its way there).
    var seasonsToAskFor: [SeasonToAskFor] {
        let open = buttons.map { button in
            (button, Set(RequestFlowKt.seasonRows(page: self, is4k: button.is4k).filter { !$0.taken }.map(\.seasonNumber)))
        }
        return seasonLines.compactMap { line in
            open.first { $0.1.contains(line.number) }.map { SeasonToAskFor(line: line, button: $0.0) }
        }
    }

    /// The seasons that cannot be asked for, oldest first: in the library, asked for already, or out of the account's
    /// reach. They take no focus; Information lists those with a status (Tom's D2).
    var seasonsAtRest: [SeasonLine] {
        let asked = Set(seasonsToAskFor.map(\.line.number))
        return Array(seasonLines.filter { !asked.contains($0.number) }.reversed())
    }
}

/// The seasons a series can still be asked for (polish 2's item 13; Tom's D2), newest first, as a shelf of the system's
/// cards: the season, its episodes and each copy's status as glyph and word. Select opens the request sheet for that
/// season, with it chosen.
private struct SeasonsShelf: View {
    let seasons: [SeasonToAskFor]
    var focus: FocusState<TitleStop?>.Binding
    var onSeason: (_ button: RequestButton, _ season: Int32) -> Void
    @State private var left: Int32?

    var body: some View {
        // Back to the season left, or the first, once a request has taken the one left off the shelf.
        let back = left.flatMap { number in seasons.contains { $0.line.number == number } ? number : nil }
        VStack(alignment: .leading, spacing: 0) {
            ShelfTitle(title: Words.seasons).id(TitleSection.seasons)
            Shelf(height: 230, lazy: false) {
                ForEach(seasons, id: \.line.number) { offer in
                    let season = offer.line
                    Button { onSeason(offer.button, season.number) } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(season.number == 0 ? Words.specials : Words.season(season.number))
                                .font(.headline)
                            Text(Words.episodes(season.episodes))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Spacer(minLength: 0)
                            VStack(alignment: .leading, spacing: 6) {
                                ForEach(season.badges, id: \.text) { StatusLabel(badge: $0) }
                            }
                            .font(.caption)
                        }
                        .padding(24)
                        .frame(width: 320, height: 190, alignment: .topLeading)
                        .background(Tokens.placeholder)
                    }
                    .buttonStyle(.card)
                    .focused(focus, equals: .season(season.number))
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("series season \(season.number)")
                }
            }
        }
        .focusSection()
        .defaultFocus(focus, .season(back ?? seasons.first?.line.number ?? 0), priority: .userInitiated)
        .onChange(of: focus.wrappedValue) { _, stop in
            if case .season(let number) = stop { left = number }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("seasons")
    }
}

/// Cast & Crew as the Apple TV app shows them: portraits in circles, the cast with their parts, then Seerr's crew with
/// their jobs (initials where there is no picture). Select opens the person's page.
private struct CastShelf: View {
    let cast: [CastCard]
    let crew: [CrewLine]
    var focus: FocusState<TitleStop?>.Binding
    var onPerson: (_ id: Int32, _ name: String) -> Void
    @State private var left: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ShelfTitle(title: Words.castAndCrew).id(TitleSection.cast)
            Shelf(height: Metrics.portrait + Metrics.captionHeight + 30, lazy: false) {
                ForEach(cast, id: \.key) { person in
                    PersonCardView(id: "person \(person.key)", name: person.name, part: person.character, photo: person.photo) {
                        onPerson(person.personId, person.name)
                    }
                    .focused(focus, equals: .cast(person.key))
                }
                ForEach(Array(crew.enumerated()), id: \.offset) { index, line in
                    let key = "crew:\(index):\(line.personId)"
                    PersonCardView(id: "person \(key)", name: line.name, part: line.job, photo: nil) {
                        onPerson(line.personId, line.name)
                    }
                    .focused(focus, equals: .cast(key))
                }
            }
        }
        .focusSection()
        .defaultFocus(focus, .cast(left ?? cast.first?.key ?? crew.first.map { "crew:0:\($0.personId)" } ?? ""), priority: .userInitiated)
        .onChange(of: focus.wrappedValue) { _, stop in
            if case .cast(let key) = stop { left = key }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("cast")
    }
}

/// The film's collection (polish 2's item 12): a backdrop lockup with its name; Select opens the collection's films.
private struct CollectionShelf: View {
    let card: CollectionCard
    var focus: FocusState<TitleStop?>.Binding
    var onSelect: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ShelfTitle(title: Words.collection).id(TitleSection.collection)
            Button(action: onSelect) {
                VStack(alignment: .leading, spacing: 0) {
                    LockupImage(url: card.backdrop, width: Metrics.backdropWidth, height: Metrics.backdropHeight)
                    LockupCaption(title: card.name, width: Metrics.backdropWidth, titleId: "collection name") {
                        Text(Words.viewCollection)
                    }
                }
            }
            .buttonStyle(.borderless)
            .focused(focus, equals: .collection)
            .accessibilityElement(children: .contain)
            .accessibilityLabel(card.name)
            .accessibilityIdentifier("collection")
            .padding(.leading, Metrics.sideMargin)
            .frame(height: Metrics.backdropHeight + Metrics.captionHeight, alignment: .topLeading)
        }
        .focusSection()
    }
}

/// Seerr's "Recommendations" or "Similar Titles" ("Similar Series"): a shelf of poster lockups, Select opening the
/// title's page.
private struct RelatedShelf: View {
    let kind: RelatedKind
    let title: String
    let cards: [TitleCard]
    var focus: FocusState<TitleStop?>.Binding
    var onTitle: (TitleCard) -> Void
    @State private var left: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ShelfTitle(title: title).id(TitleSection.related(kind))
            let mixed = mixesKinds(cards)
            Shelf {
                ForEach(cards, id: \.key) { card in
                    TitleCardView(card: card, mixed: mixed) { onTitle(card) }
                        .focused(focus, equals: .related(kind, card.key))
                }
            }
        }
        .focusSection()
        .defaultFocus(focus, .related(kind, left ?? cards.first?.key ?? ""), priority: .userInitiated)
        .onChange(of: focus.wrappedValue) { _, stop in
            if case .related(let at, let key) = stop, at == kind { left = key }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("related \(kind.name)")
    }
}

/// Information, laid out as the Apple TV app's, at the page's foot and read at rest (it takes no focus: the review's
/// part 6): the ratings with their marks, then Seerr's facts (`.media-facts`) in columns, each fact's name over its
/// value, the seasons that cannot be asked for with their status (Tom's D2), and where the title streams.
private struct Information: View {
    let page: MediaPage
    let ratings: Ratings?

    private static let column: CGFloat = 400

    /// Information's cells in order: Seerr's facts, the seasons at rest, then where the title streams.
    private var cells: [InfoCell] {
        var cells = page.facts.enumerated().map { index, fact in
            InfoCell(id: index, view: AnyView(
                FactCell(name: fact.name, value: FactCell.value(fact)).accessibilityIdentifier("fact \(fact.name)")
            ))
        }
        for run in SeasonRun.runs(page.seasonsAtRest) {
            cells.append(InfoCell(id: cells.count, view: AnyView(SeasonFactCell(run: run))))
        }
        if !page.streaming.isEmpty {
            cells.append(InfoCell(id: cells.count, view: AnyView(Streaming(services: page.streaming))))
        }
        return cells
    }

    var body: some View {
        let scores = MediaFactsKt.ratingScores(ratings: ratings, voteAverage: page.voteAverage, voteCount: page.voteCount)
        VStack(alignment: .leading, spacing: 0) {
            ShelfTitle(title: Words.information).id(TitleSection.information)
            VStack(alignment: .leading, spacing: 40) {
                if !scores.isEmpty { RatingsLine(scores: scores) }
                // Four columns, row by row; a plain grid, built whole (a lazy one leaves an unseen section empty).
                Grid(alignment: .topLeading, horizontalSpacing: 40, verticalSpacing: 36) {
                    ForEach(Array(cells.chunked(4).enumerated()), id: \.offset) { _, row in
                        GridRow {
                            ForEach(row) { cell in
                                cell.view.frame(width: Self.column, alignment: .topLeading)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, Metrics.sideMargin)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("facts")
        }
    }
}

/// Seasons side by side that share their status, as Information names them: "Season 1", "Seasons 2–4", "Specials".
struct SeasonRun: Equatable {
    let first: Int32
    let last: Int32
    let badges: [StatusBadge]

    var name: String {
        if first == 0 { return Words.specials }
        return first == last ? Words.season(first) : Words.seasons(first, last)
    }

    /// [seasons] (oldest first) that have a status, in runs of neighbours with the same status; the specials alone.
    static func runs(_ seasons: [SeasonLine]) -> [SeasonRun] {
        var runs: [SeasonRun] = []
        for season in seasons where !season.badges.isEmpty {
            if let run = runs.last, run.first != 0, season.number != 0, season.number == run.last + 1, run.badges == season.badges {
                runs[runs.count - 1] = SeasonRun(first: run.first, last: season.number, badges: run.badges)
            } else {
                runs.append(SeasonRun(first: season.number, last: season.number, badges: season.badges))
            }
        }
        return runs
    }
}

/// Seasons at rest in Information, as a fact: their name over each copy's status, glyph and word.
private struct SeasonFactCell: View {
    let run: SeasonRun

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(run.name)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            ForEach(run.badges, id: \.text) { StatusLabel(badge: $0) }
                .font(.callout)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("fact \(run.name)")
    }
}

private struct InfoCell: Identifiable {
    let id: Int
    let view: AnyView
}

extension Array {
    /// The array in runs of [size], the last perhaps shorter.
    func chunked(_ size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0..<Swift.min($0 + size, count)]) }
    }
}

/// One fact: its name in Caption, secondary, over its value.
struct FactCell: View {
    let name: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(name)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.callout)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// A fact's value as Hither writes it (`Formats`): a date as every date on the TV, money in whole dollars, a language
    /// by its English name.
    static func value(_ fact: Fact) -> String {
        switch fact.kind {
        case .text:
            return fact.value
        case .date:
            return Formats.day(fact.value) ?? fact.value
        case .money:
            guard let amount = Int64(fact.value) else { return fact.value }
            return Formats.money(amount)
        case .language:
            let name = Locale(identifier: "en").localizedString(forLanguageCode: fact.value) ?? ""
            return name.isEmpty ? fact.value : name
        }
    }
}

/// Seerr's ratings (`.media-ratings`), each mark and its score: Rotten Tomatoes' tomato or splat and popcorn, IMDb's
/// and TMDB's marks.
private struct RatingsLine: View {
    let scores: [RatingScore]

    var body: some View {
        HStack(spacing: 48) {
            ForEach(Array(scores.enumerated()), id: \.offset) { _, score in
                HStack(spacing: 14) {
                    Image(Self.mark(score.kind))
                        .resizable()
                        .scaledToFit()
                        .frame(width: Self.width(score.kind), height: 44)
                        .accessibilityLabel(Self.name(score.kind))
                    Text(score.value)
                        .font(.callout)
                        .lineLimit(1)
                        .fixedSize()
                        .accessibilityIdentifier("rating \(score.kind.name)")
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("ratings")
    }

    static func mark(_ kind: RatingKind) -> String {
        switch kind {
        case .rtFresh: "rating_rt_fresh"
        case .rtRotten: "rating_rt_rotten"
        case .rtUpright: "rating_rt_upright"
        case .rtSpilled: "rating_rt_spilled"
        case .imdb: "rating_imdb"
        case .tmdb: "tmdb_logo"
        }
    }

    /// What VoiceOver calls each mark.
    static func name(_ kind: RatingKind) -> String {
        switch kind {
        case .rtFresh, .rtRotten: "Rotten Tomatoes"
        case .rtUpright, .rtSpilled: "Rotten Tomatoes audience"
        case .imdb: "IMDb"
        case .tmdb: "TMDB"
        }
    }

    /// Seerr's marks share a width; the wide ones (IMDb, TMDB) get more, to stay legible (Android's).
    static func width(_ kind: RatingKind) -> CGFloat {
        switch kind {
        case .imdb: 72
        case .tmdb: 92
        default: 44
        }
    }
}

/// "Currently Streaming On", a fact of its own: each service's logo (core's `imageUrl`), and its name.
private struct Streaming: View {
    let services: [StreamingService]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(Words.streamingOn)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            ForEach(Array(services.enumerated()), id: \.offset) { _, service in
                HStack(spacing: 12) {
                    ProxyImage(url: service.logo)
                        .frame(width: 44, height: 44)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                    Text(service.name).font(.callout)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("streaming")
    }
}

/// The keywords as one shelf under their header (the review's S26, D5: a wrapping cloud of tags is a web page's), the
/// system's capsule buttons in a row that scrolls sideways as focus moves along it; Select opens the keyword's titles.
/// Built whole, not lazily, so every keyword is there for VoiceOver and the tests from the start.
private struct KeywordShelf: View {
    let keywords: [Keyword]
    var focus: FocusState<TitleStop?>.Binding
    var onKeyword: (Keyword) -> Void
    /// The keyword focus was last on: coming back into the shelf lands there, the first until then (Android's
    /// `keywordBack` and `firstKeyword`).
    @State private var left: Int32?

    /// A system button's height in Caption, and the room it grows into when focused.
    private static let height: CGFloat = 90

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ShelfTitle(title: Words.keywords).id(TitleSection.keywords)
            Shelf(height: Self.height, lazy: false) {
                // Buttons sit closer than lockups: one row of them, as the title's actions.
                HStack(spacing: 32) {
                    ForEach(keywords, id: \.id) { keyword in
                        Button { onKeyword(keyword) } label: {
                            Text(keyword.name).font(.caption).lineLimit(1)
                        }
                        .buttonBorderShape(.capsule)
                        .focused(focus, equals: .keyword(keyword.id))
                        .accessibilityIdentifier("keyword \(keyword.name)")
                    }
                }
            }
        }
        .focusSection()
        .defaultFocus(focus, .keyword(left ?? keywords.first?.id ?? 0), priority: .userInitiated)
        .onChange(of: focus.wrappedValue) { _, stop in
            if case .keyword(let id) = stop { left = id }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("keywords")
    }
}
