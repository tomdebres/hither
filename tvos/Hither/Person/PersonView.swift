import Foundation
import HitherCore
import Observation
import SwiftUI

/// A person's page (Android's `PersonModel`): their details and credits read together on arrival and on Try Again.
@MainActor
@Observable
final class PersonModel: TakesBadges {
    enum State {
        case loading, failed
        case ready(PersonPage)
    }

    let personId: Int32
    /// The name the card that opened the page carried, shown while the page loads.
    let name: String
    private(set) var state: State = .loading
    private let gateway: SessionGateway
    private var started = false
    private var generation = 0

    init(personId: Int32, name: String, gateway: SessionGateway, hub: BadgeHub) {
        self.personId = personId
        self.name = name
        self.gateway = gateway
        hub.join(self)
    }

    func start() {
        guard !started else { return }
        started = true
        load()
    }

    func retry() {
        if case .failed = state { load() }
    }

    func setBadge(_ key: String, _ badge: StatusBadge?) {
        guard case .ready(let page) = state else { return }
        var changed = false
        let sections = page.sections.map { section in
            let cards = section.cards.map { credit in
                guard credit.card.key == key, credit.card.badge != badge else { return credit }
                changed = true
                return CreditCard(card: credit.card.doCopy(key: credit.card.key, tmdbId: credit.card.tmdbId, type: credit.card.type, title: credit.card.title, year: credit.card.year, poster: credit.card.poster, badge: badge, overview: credit.card.overview), part: credit.part)
            }
            return CreditSection(kind: section.kind, cards: cards)
        }
        if changed {
            state = .ready(page.doCopy(id: page.id, name: page.name, photo: page.photo, birthday: page.birthday, deathday: page.deathday, placeOfBirth: page.placeOfBirth, alsoKnownAs: page.alsoKnownAs, biography: page.biography, sections: sections, backdrop: page.backdrop))
        }
    }

    private func load() {
        generation += 1
        let generation = generation
        state = .loading
        let id = personId
        Task {
            do {
                let page = try await gateway.call { client in
                    async let details = client.person(personId: id)
                    async let credits = client.personCredits(personId: id)
                    return PeopleKt.personPage(details: try await details, credits: try await credits, baseUrl: client.baseUrl)
                }
                if self.generation == generation { state = .ready(page) }
            } catch {
                if self.generation == generation { state = .failed }
            }
        }
    }
}

/// A person's focus stops.
private enum PersonStop: Hashable {
    case loading, retry
    case biography
    case credit(CreditKind, String)
}

/// A person's page (Android's `PersonScreen`, Seerr's PersonDetails, in tvOS's way: DECISIONS 193): the first title's
/// backdrop across the top, fading into the page, the portrait in a circle, the name, born (and died) and where, the
/// other names, then the biography's first lines as one stop with "More" (Select: the whole of it in the text view that
/// scrolls with the remote, the review's F20; it was a stop per paragraph), then "Appearances" and "Crew" as shelves of
/// poster lockups with the person's part under each. Focus arrives on the biography, or the first lockup where there is
/// none, with the page at its top.
struct PersonView: View {
    @State var model: PersonModel
    var onTitle: (TitleCard) -> Void

    @FocusState private var focus: PersonStop?
    /// The whole biography, open over the page (`ReadingScreen`).
    @State private var reading = false

    private static let readingWidth: CGFloat = ReadingScreen.width
    private static let top = "top"

    var body: some View {
        // The whole screen, no title: the backdrop runs to every edge and the header keeps tvOS's margins itself.
        PageScaffold(.pushed(nil), id: "person page") { scroller }
        .fullScreenCover(isPresented: $reading, onDismiss: {
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(400))
                focus = .biography
            }
        }) {
            if case .ready(let page) = model.state {
                ReadingScreen(title: page.name, text: Self.biography(page), id: "biography screen", textId: "biography whole")
            }
        }
        .task { model.start() }
        .onChange(of: arrivalKey, initial: true) { _, _ in arrive() }
    }

    private var scroller: some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                ZStack(alignment: .top) {
                    // The page's very top (above the header's 140 points), so scrolling to it shows the page as it opens.
                    Color.clear.frame(height: 0).id(Self.top)
                    if case .ready(let page) = model.state, page.backdrop != nil {
                        TitleBackdrop(url: page.backdrop)
                    }
                    VStack(alignment: .leading, spacing: 0) {
                        content
                    }
                    .padding(.top, 140)
                    .padding(.bottom, 600)
                }
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
            .ignoresSafeArea()
            .onChange(of: focus) { _, stop in
                guard let stop else { return }
                withAnimation(.easeInOut(duration: 0.3)) {
                    switch stop {
                    // The biography keeps the page at its top and the header with it (the review's F5: arrival scrolled
                    // the header up into the top margin).
                    case .loading, .retry, .biography: proxy.scrollTo(Self.top, anchor: .top)
                    case .credit(let kind, _): proxy.scrollTo(kind, anchor: TitleMetrics.sectionLine)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.state {
        case .loading:
            header(name: model.name, photo: nil, facts: [])
            Button {} label: {
                RoundedRectangle(cornerRadius: 16).fill(Tokens.placeholder).frame(width: Self.readingWidth, height: 120)
            }
            .buttonStyle(.borderless)
            .focused($focus, equals: .loading)
            .padding(.leading, Metrics.sideMargin)
            .padding(.top, 48)
            .accessibilityIdentifier("loading")
        case .failed:
            header(name: model.name, photo: nil, facts: [])
            StateView(.failed, Words.personFailed, id: "person failed") {
                Button(Words.tryAgain) { model.retry() }
                    .focused($focus, equals: .retry)
                    .accessibilityIdentifier("try again")
            }
            .padding(.top, 40)
        case .ready(let page):
            header(name: page.name, photo: page.photo, facts: Self.facts(page))
            if !page.biography.isEmpty {
                // Its first lines run on from paragraph to paragraph, as the Apple TV app's description does; the whole keeps them.
                TextStop(text: page.biography.joined(separator: " "), id: "biography", textId: "biography text") { reading = true }
                    .focused($focus, equals: .biography)
                    .frame(width: Self.readingWidth, alignment: .leading)
                    .padding(.leading, Metrics.sideMargin)
                    .padding(.bottom, 16)
            }
            ForEach(page.sections, id: \.kind) { section in
                CreditsRow(section: section, focus: $focus, onTitle: onTitle)
            }
        }
    }

    private var arrivalKey: Int {
        switch model.state {
        case .loading: 0
        case .failed: 1
        case .ready: 2
        }
    }

    private func arrive() {
        switch model.state {
        case .loading: focus = .loading
        case .failed: focus = .retry
        case .ready(let page):
            guard focus == nil || focus == .loading || focus == .retry else { return }
            if !page.biography.isEmpty {
                focus = .biography
            } else if let section = page.sections.first, let card = section.cards.first {
                focus = .credit(section.kind, card.card.key)
            }
        }
    }

    /// The header: the portrait in a circle, the name, and the facts under it.
    private func header(name: String, photo: String?, facts: [String]) -> some View {
        HStack(spacing: 48) {
            ProxyImage(url: photo)
                .frame(width: 240, height: 240)
                .clipShape(Circle())
                .accessibilityIdentifier("person photo")
            VStack(alignment: .leading, spacing: 12) {
                Text(name)
                    .font(.title2.bold())
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .accessibilityIdentifier("person name")
                ForEach(facts, id: \.self) { fact in
                    Text(fact).font(.callout).foregroundStyle(.secondary).lineLimit(2)
                }
            }
        }
        .padding(.horizontal, Metrics.sideMargin)
        .padding(.bottom, 32)
    }

    /// The whole biography for `ReadingScreen`, a paragraph at a time.
    static func biography(_ page: PersonPage) -> ReadingText {
        page.biography.reduce(ReadingText()) { text, paragraph in text.paragraph(paragraph) }
    }

    /// "Born {date} · {place}", or "{born} – {died} · {place}", then "Also Known As: …": dates and the separator as
    /// Hither writes them everywhere (`Formats`; the review's F29: a long date and a bar here).
    static func facts(_ page: PersonPage) -> [String] {
        let born = page.birthday.map { Formats.day($0) ?? $0 }
        let died = page.deathday.map { Formats.day($0) ?? $0 }
        var life: String?
        if let born, let died { life = Words.lifespan(born, died) } else if let born { life = Words.born(born) }
        var lines: [String] = []
        if let first = Formats.join([life, page.placeOfBirth]) { lines.append(first) }
        if !page.alsoKnownAs.isEmpty { lines.append(Words.alsoKnownAs(page.alsoKnownAs.joined(separator: ", "))) }
        return lines
    }
}

/// "Appearances" or "Crew": a shelf of poster lockups, the person's part under each title, coming back to the lockup
/// left.
private struct CreditsRow: View {
    let section: CreditSection
    var focus: FocusState<PersonStop?>.Binding
    var onTitle: (TitleCard) -> Void
    @State private var left: String?

    var body: some View {
        let kind = section.kind
        VStack(alignment: .leading, spacing: 0) {
            ShelfTitle(title: kind == .appearances ? Words.appearances : Words.crew)
            Color.clear.frame(height: 0).id(kind)
            let mixed = mixesKinds(section.cards.map(\.card))
            Shelf {
                ForEach(section.cards, id: \.card.key) { credit in
                    TitleCardView(card: credit.card, mixed: mixed, subtitle: part(credit)) { onTitle(credit.card) }
                        .focused(focus, equals: .credit(kind, credit.card.key))
                }
            }
        }
        .focusSection()
        .defaultFocus(focus, .credit(kind, left ?? section.cards.first?.card.key ?? ""), priority: .userInitiated)
        .onChange(of: focus.wrappedValue) { _, stop in
            if case .credit(let at, let key) = stop, at == kind { left = key }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("credits \(kind.name)")
    }

    /// The part, under the title in place of the year: "as Leon", a job.
    private func part(_ credit: CreditCard) -> String? {
        guard let part = credit.part else { return nil }
        return section.kind == .appearances ? Words.playing(part) : part
    }
}
