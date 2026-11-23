import Foundation
import HitherCore
import Observation
import SwiftUI

/// What the filters offer for one type: TMDB's genres, the languages, the region's first streaming services.
struct FilterOptions {
    let genres: [Genre]
    let languages: [Language]
    let providers: [ProviderTile]
    /// The region the services are listed for, and asked for in.
    let region: String
}

/// Seerr's Movies or Series page (Android's `BrowseModel`, DECISIONS 102), opened from a row's See More: a titles grid
/// under Seerr's sort and the short filter set, the row's own query kept under them (polish 2, DECISIONS 131). A new
/// sort or filter set is a new grid, read from its first page. The filters' choices are read the first time the panel
/// opens, and again there after a failure.
@MainActor
@Observable
final class BrowseModel {
    let type: MediaType
    /// The row's title, or none for "Movies" or "Series".
    let heading: String?
    private(set) var sort = BrowseKt.DEFAULT_SORT
    private(set) var filters = BrowseFilters.none
    private(set) var options: FilterOptions?
    private(set) var optionsLoading = false
    private(set) var optionsFailed = false
    /// The grid for the sort and filters now: replaced, not refilled, when either changes.
    private(set) var grid: TitleGridModel!
    /// Bumped with every new grid, so the page draws it afresh.
    private(set) var gridNumber = 0

    private let base: DiscoverQuery
    private let gateway: SessionGateway
    private let session: Session
    private let hub: BadgeHub
    /// The region the services were listed for; a query only names one once services are picked from it.
    private var region = "US"

    init(type: MediaType, base: DiscoverQuery, heading: String?, session: Session, gateway: SessionGateway, hub: BadgeHub) {
        self.type = type
        self.base = base
        self.heading = heading
        self.session = session
        self.gateway = gateway
        self.hub = hub
        grid = newGrid()
    }

    /// The current sort in tvOS's words ("Most Popular").
    var sortLabel: String {
        Words.sorts(BrowseKt.sortOptions(type: type)).first { $0.value == sort }?.words ?? ""
    }

    func start() { grid.start() }

    /// One of Seerr's sorts for this type; the same one again changes nothing.
    func sortBy(_ value: String) {
        guard value != sort, BrowseKt.sortOptions(type: type).contains(where: { $0.value == value }) else { return }
        sort = value
        renew()
    }

    /// The filters as the panel left them; unchanged, nothing is read again.
    func apply(_ next: BrowseFilters) {
        guard next != filters else { return }
        filters = next
        renew()
    }

    /// Reads the filters' choices, once (again after a failure); nothing while a read is out.
    func loadOptions() {
        guard options == nil, !optionsLoading else { return }
        optionsLoading = true
        optionsFailed = false
        let type = type
        Task {
            do {
                let read = try await gateway.call { client -> FilterOptions in
                    async let settings = client.publicSettings()
                    async let user = client.me()
                    async let genres = client.genres(type: type)
                    async let languages = client.languages()
                    let region = BrowseKt.browseRegion(userRegion: try await user.settings?.streamingRegion, serverRegion: try await settings.streamingRegion)
                    let providers = try await client.watchProviders(type: type, region: region)
                    return FilterOptions(
                        genres: try await genres.filter { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty },
                        languages: BrowseKt.languageOptions(languages: try await languages),
                        providers: BrowseKt.providerTiles(providers: providers, baseUrl: client.baseUrl),
                        region: region
                    )
                }
                region = read.region
                options = read
            } catch {
                optionsFailed = true
            }
            optionsLoading = false
        }
    }

    private func newGrid() -> TitleGridModel {
        let query = BrowseKt.browseQuery(type: type, sort: sort, filters: filters, region: region, base: base)
        let type = type
        return TitleGridModel(gateway: gateway, baseUrl: session.baseUrl, hub: hub) { gateway, page in
            try await gateway.call { client in try await client.discover(type: type, query: query, page: page) }
        }
    }

    private func renew() {
        grid = newGrid()
        gridNumber += 1
        grid.start()
    }
}

extension BrowseFilters {
    /// No filter: Kotlin's `BrowseFilters()`.
    static var none: BrowseFilters { BrowseFilters(genres: [], years: nil, providers: [], language: nil) }

    func with(genres: Set<KotlinInt>? = nil, years: Years?? = nil, providers: Set<KotlinInt>? = nil, language: String?? = nil) -> BrowseFilters {
        BrowseFilters(
            genres: genres ?? self.genres,
            years: years ?? self.years,
            providers: providers ?? self.providers,
            language: language ?? self.language
        )
    }
}

/// Seerr's Movies or Series page (Android's `BrowseScreen`): the page's title, the sort and Filters at the right of its
/// line, over the titles grid. Focus arrives on the first card; Up from the grid's first row reaches the controls. The
/// sort is the system's menu of Seerr's eight; the filters a system sheet with the short set, applied as it closes.
/// After either the grid is read again from the top, and focus is back on its control.
struct BrowseView: View {
    @State var model: BrowseModel
    let id: String
    var onTitle: (TitleCard) -> Void

    @State private var filtering = false
    /// What the sheet shows while it is open; applied as it closes.
    @State private var draft = BrowseFilters.none

    var body: some View {
        // Sort and Filters keep their words, on the title's line at the right (the system's toolbar drew them as bare
        // symbols).
        PageScaffold(.pushed(model.heading ?? (model.type == .movie ? Words.movies : Words.series)), bleed: .horizontal, id: "page browse \(id)") {
            CardGrid(model: model.grid, onTitle: onTitle)
                .id(model.gridNumber)
        } trailing: {
            sortMenu
            filtersButton
        }
        .sheet(isPresented: $filtering, onDismiss: { model.apply(draft) }) {
            FilterSheet(model: model, draft: $draft)
        }
        .task { model.start() }
    }

    /// Seerr's eight sorts in tvOS's words, as the system's menu; the current one ticked.
    private var sortMenu: some View {
        Menu {
            ForEach(Words.sorts(BrowseKt.sortOptions(type: model.type)), id: \.value) { option in
                Button {
                    model.sortBy(option.value)
                } label: {
                    if option.value == model.sort {
                        Label(option.words, systemImage: "checkmark")
                    } else {
                        Text(option.words)
                    }
                }
                .accessibilityIdentifier("pick \(option.value)")
            }
        } label: {
            Label(model.sortLabel, systemImage: "arrow.up.arrow.down")
        }
        .accessibilityLabel(model.sortLabel)
        .accessibilityIdentifier("sort")
    }

    /// The filter sheet, its button counting what is set.
    private var filtersButton: some View {
        Button {
            draft = model.filters
            filtering = true
            model.loadOptions()
        } label: {
            Label(Words.filters(model.filters.count), systemImage: "line.3.horizontal.decrease")
        }
        .accessibilityLabel(Words.filters(model.filters.count))
        .accessibilityIdentifier("filters")
    }
}

/// Seerr's filter slide-over with the short set (Android's `FilterPanel`, DECISIONS 102), as a system sheet: the
/// release (or first air) date as decades, the genres, the original language (a list of its own), the streaming
/// services, and "Clear Filters" while any is set. Each choice is a row that Select ticks or clears; Menu closes the
/// sheet, and the page applies what it shows. A `FormSheet`, as the sheets of choices since polish part 5; its title
/// counts what is set, as the button does ("Filters (1)").
private struct FilterSheet: View {
    let model: BrowseModel
    @Binding var draft: BrowseFilters

    var body: some View {
        FormSheet(Words.filters(draft.count), id: "filter panel") {
            if draft.count > 0 {
                Button(Words.clearFilters) { draft = .none }
                    .accessibilityIdentifier("clear filters")
            }
            Section {
                ForEach(Years.allCases, id: \.self) { years in
                    choice(years.label, on: draft.years == years, id: "years \(years.name)") {
                        draft = draft.with(years: .some(draft.years == years ? nil : years))
                    }
                }
            } header: {
                header(model.type == .movie ? Words.releaseDate : Words.firstAirDate)
            }
            if let options = model.options {
                if !options.genres.isEmpty {
                    Section {
                        ForEach(options.genres, id: \.id) { genre in
                            let key = KotlinInt(int: genre.id)
                            choice(genre.name, on: draft.genres.contains(key), id: "genre \(genre.id)") {
                                var genres = draft.genres
                                if genres.contains(key) { genres.remove(key) } else { genres.insert(key) }
                                draft = draft.with(genres: genres)
                            }
                        }
                    } header: {
                        header(Words.genres)
                    }
                }
                Section {
                    NavigationLink {
                        LanguageList(options: options, draft: $draft)
                    } label: {
                        LabeledContent(Words.originalLanguage) {
                            Text(draft.language.map { code in options.languages.first { $0.code == code }?.englishName ?? code } ?? Words.allLanguages)
                        }
                    }
                    .accessibilityIdentifier("language")
                }
                if !options.providers.isEmpty {
                    Section {
                        ForEach(options.providers, id: \.id) { tile in
                            let key = KotlinInt(int: tile.id)
                            FormChoice(on: draft.providers.contains(key), id: "provider \(tile.id)") {
                                var providers = draft.providers
                                if providers.contains(key) { providers.remove(key) } else { providers.insert(key) }
                                draft = draft.with(providers: providers)
                            } label: {
                                HStack(spacing: 24) {
                                    ProxyImage(url: tile.logo)
                                        .frame(width: 56, height: 56)
                                        .clipShape(RoundedRectangle(cornerRadius: 10))
                                    Text(tile.name)
                                }
                            }
                        }
                    } header: {
                        header(Words.streamingServices)
                    }
                }
            } else if model.optionsFailed {
                // The state as everywhere else (StateView): a section's header set the sentence in capitals.
                StateView(.failed, Words.filtersFailed, id: "filters failed") {
                    Button(Words.tryAgain) { model.loadOptions() }
                        .accessibilityIdentifier("filters try again")
                }
            } else {
                StateView(.loading, Words.filtersLoading, id: "filters loading")
            }
        }
    }

    /// A choice that Select ticks or clears: the system's row (`FormChoice`), Seerr's words.
    private func choice(_ label: String, on: Bool, id: String, toggle: @escaping () -> Void) -> some View {
        FormChoice(on: on, id: id, action: toggle) { Text(label) }
    }

    private func header(_ text: String) -> some View {
        Text(text)
    }
}

/// The original languages to pick one from, "All Languages" first, the current one ticked; Select picks and goes back.
private struct LanguageList: View {
    let options: FilterOptions
    @Binding var draft: BrowseFilters
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            row(Words.allLanguages, code: nil, id: "all")
            ForEach(options.languages, id: \.code) { language in
                row(language.englishName, code: language.code, id: language.code)
            }
        }
        .navigationTitle(Words.originalLanguage)
        .accessibilityIdentifier("language list")
    }

    private func row(_ label: String, code: String?, id: String) -> some View {
        Button {
            draft = draft.with(language: .some(code))
            dismiss()
        } label: {
            HStack {
                Text(label)
                Spacer(minLength: 0)
                if draft.language == code { Image(systemName: "checkmark") }
            }
        }
        .accessibilityIdentifier("pick \(id)")
    }
}
