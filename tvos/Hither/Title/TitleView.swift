import HitherCore
import SwiftUI

/// The title page's focus stops, said by name so arrival, the modal's return and the tests can find them.
enum TitleStop: Hashable {
    case loading, retry
    case play(Bool)
    case request(Bool)
    case trailer
    /// Report an Issue, last of the actions (DECISIONS 106).
    case issue
    case overview, collection
    case keyword(Int32)
    case season(Int32)
    case cast(String)
    case related(RelatedKind, String)
}

/// The title page's sizes, after the Apple TV app's product page (Tom's native look, DECISIONS 193).
enum TitleMetrics {
    /// The backdrop: core's 1920 x 800 (DECISIONS 178), drawn across the top at its own size and decoded at it.
    static let backdropHeight: CGFloat = 800
    /// The first screen: the backdrop and the words over its foot, with the next section's header showing under it.
    static let heroHeight: CGFloat = 960
    /// The words over the backdrop: this wide at most, so the picture keeps its right side.
    static let heroWords: CGFloat = 1240
    /// Where a section's header comes to as focus goes into it: the eye stays on one line (Android's DECISIONS 40).
    static let sectionLine = UnitPoint(x: 0, y: 0.08)
    /// Room under the page's foot, so that every section above the last can come up to the line however short the rest
    /// of the page; the last brings the foot up to the screen's bottom margin instead.
    static let afterLast: CGFloat = 420
}

/// A title's page as the Apple TV app's product page (Tom's native look, DECISIONS 193): the backdrop full-bleed
/// across the top, fading into the page; over its foot the title, one line of what it is (year, runtime or seasons,
/// genres, certification), its status as glyphs with their words, the actions as system buttons in a row (focused on
/// arrival), and the tagline and overview. Below: the seasons a series can still be asked for (each opens the request
/// sheet for itself), Cast & Crew as portraits, the collection, Recommendations and Similar as poster lockups, the
/// keywords as a shelf, and Information at the foot, read and never focused (the ratings with their marks, Seerr's facts
/// in columns, the seasons that cannot be asked for, where it streams). While the title loads, the card's title and
/// status stand in and a placeholder holds focus; if it fails, Try Again does. A film's request button asks in the
/// system's alert; a series' opens the request sheet (`FormSheet`); Menu closes either. What a request did shows on the
/// page, in its status and its buttons; what went wrong is the system's alert (Tom's D4).
struct TitleView: View {
    @State var model: TitleModel
    var onPerson: (_ id: Int32, _ name: String) -> Void
    var onTitle: (TitleCard) -> Void
    var onCollection: (CollectionCard) -> Void
    var onKeyword: (Keyword) -> Void

    @FocusState private var focus: TitleStop?
    /// The copy the modal was opened for, so focus goes back to its button as the modal closes.
    @State private var openedFor: Bool?
    /// The season the modal was opened from, if a season's card opened it: focus goes back to the shelf.
    @State private var openedForSeason: Int32?
    /// While a copy downloads, the page is read again once a minute, and its estimate moves on with it.
    @State private var now = Date()
    /// The whole overview, open over the page (`ReadingScreen`).
    @State private var reading = false

    private static let top = "top"

    var body: some View {
        let page = model.page
        // The whole screen, no title: the backdrop runs to every edge and the words keep tvOS's margins themselves.
        PageScaffold(.pushed(nil), id: "title page") { scroller(page) }
        // A film's request has nothing to choose: the system's alert asks it (the review's F2, F19).
        .alert(
            filmQuestion?.title ?? "",
            isPresented: Binding(get: { filmQuestion != nil }, set: { if !$0 { model.cancel() } }),
            presenting: filmQuestion
        ) { question in
            if question.enabled {
                Button(Words.cancel, role: .cancel) { model.cancel() }
                    .accessibilityIdentifier("cancel")
                Button(Words.request) { model.send(question.draft) }
                    .accessibilityIdentifier("primary")
            } else {
                Button(Words.ok, role: .cancel) { model.cancel() }
                    .accessibilityIdentifier("cancel")
            }
        } message: { question in
            Text(question.message)
        }
        // A series' request chooses its seasons: the system's form in a sheet.
        .sheet(isPresented: Binding(get: { model.draft != nil && page?.type == .tv }, set: { if !$0 { model.cancel() } })) {
            if let page = model.page, let draft = model.draft {
                RequestSheet(model: model, page: page, draft: draft)
            }
        }
        .sheet(isPresented: Binding(get: { model.issue != nil }, set: { if !$0 { model.closeIssue() } }), onDismiss: {
            // Focus back on the button that opened the form, or the first action if it has gone (the right went).
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(600))
                if let page = model.page, IssuesKt.canReportIssue(page: page) { focus = .issue } else { focus = firstAction ?? .overview }
            }
        }) {
            if let page = model.page, let issue = model.issue {
                IssueSheet(model: model, page: page, draft: issue)
            }
        }
        .fullScreenCover(isPresented: $reading, onDismiss: {
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(400))
                focus = .overview
            }
        }) {
            if let page = model.page { WholeOverview(page: page) }
        }
        .problemAlert(model.problem) { model.problemSeen() }
        .task { model.start() }
        .task(id: downloading) {
            while downloading {
                try? await Task.sleep(for: .seconds(60))
                guard !Task.isCancelled else { return }
                model.reread()
                now = Date()
            }
        }
        .onChange(of: arrivalKey, initial: true) { _, _ in arrive() }
        .onChange(of: model.draft) { old, new in
            if let new {
                openedFor = new.is4k
                openedForSeason = new.season
            } else if old != nil {
                focusAfterModal()
            }
        }
    }

    /// The backdrop and the words over it, then the sections, scrolled to each stop as focus moves.
    private func scroller(_ page: MediaPage?) -> some View {
        ScrollViewReader { proxy in
            ScrollView(.vertical) {
                ZStack(alignment: .top) {
                    TitleBackdrop(url: page?.backdrop, poster: page == nil ? nil : (page?.backdrop == nil ? page?.poster : nil))
                        .id(Self.top)
                    VStack(alignment: .leading, spacing: 0) {
                        TitleHero(model: model, page: page, now: now, focus: $focus, onOverview: { reading = true })
                        if let page {
                            TitleSections(
                                page: page,
                                ratings: model.ratings,
                                related: model.related,
                                focus: $focus,
                                onSeason: { button, season in model.open(button, season: season) },
                                onPerson: onPerson,
                                onTitle: onTitle,
                                onCollection: onCollection,
                                onKeyword: onKeyword
                            )
                        }
                    }
                    .padding(.bottom, TitleMetrics.afterLast)
                }
            }
            .scrollIndicators(.hidden)
            .scrollClipDisabled()
            // The backdrop runs to the screen's edges: the scroll view's own safe-area inset left it short on the right.
            .ignoresSafeArea()
            .onChange(of: focus) { _, stop in
                guard let stop else { return }
                withAnimation(.easeInOut(duration: 0.3)) { scroll(proxy, to: stop) }
            }
        }
    }

    /// A film's request, while its alert is up. Not while the request is out: the alert has gone, and the button says
    /// "Requesting…".
    private var filmQuestion: RequestQuestion? {
        guard let page = model.page, page.type == .movie, let draft = model.draft, !model.sending else { return nil }
        return RequestQuestion(draft: draft, name: page.title, view: model.modal(draft, page))
    }

    private var downloading: Bool {
        model.page?.badges.contains { $0.progress != nil } ?? false
    }

    /// Changes as the page's state changes: loading, failed, ready.
    private var arrivalKey: Int {
        switch model.state {
        case .loading: 0
        case .failed: 1
        case .ready: 2
        }
    }

    /// Focus as each state arrives: the placeholder while loading, Try Again after a failure, and the first action
    /// once the page is here (the overview when it has none). Not on a quiet re-read, nor after focus has moved on.
    private func arrive() {
        switch model.state {
        case .loading:
            focus = .loading
        case .failed:
            focus = .retry
        case .ready:
            if focus == nil || focus == .loading || focus == .retry { focus = firstAction ?? .overview }
        }
    }

    /// Seerr's order: the play buttons, then the request buttons, then the trailer; the first has focus on arrival.
    private var firstAction: TitleStop? {
        guard let page = model.page else { return nil }
        if let play = playable(page).first { return .play(play.is4k) }
        if let button = page.buttons.first { return .request(button.is4k) }
        if trailerLink(page) != nil { return .trailer }
        if IssuesKt.canReportIssue(page: page) { return .issue }
        return nil
    }

    /// The modal gives focus back as it closes: to the button that opened it, or, when a request has left that copy
    /// nothing to ask for, to the first action left, else the overview. A season's card that opened it takes focus back
    /// while the season can still be asked for; once a request has taken it off the shelf, the next season along does
    /// (or the one before, the last on the shelf), and with none left, the first action.
    private func focusAfterModal() {
        let copy = openedFor
        let season = openedForSeason
        openedFor = nil
        openedForSeason = nil
        guard model.page != nil else { return }
        Task { @MainActor in
            // The sheet takes a moment to go, and hands focus back as it does.
            try? await Task.sleep(for: .milliseconds(600))
            guard let page = model.page else { return }
            if let season {
                let left = page.seasonsToAskFor.map(\.line.number)
                if let back = left.first(where: { $0 == season }) ?? left.first(where: { $0 < season }) ?? left.last {
                    focus = .season(back)
                    return
                }
                focus = firstAction ?? .overview
            } else if let copy, page.buttons.contains(where: { $0.is4k == copy }) {
                focus = .request(copy)
            } else {
                focus = firstAction ?? .overview
            }
        }
    }

    /// The focused stop's place on the screen: the first screen whole while focus is on the actions or the overview;
    /// a section's header on one line near the top as focus goes into it, as Discover's rows come up. The last section
    /// focus can reach brings the page's foot up instead, so Information, which takes no focus, is read whole under it.
    private func scroll(_ proxy: ScrollViewProxy, to stop: TitleStop) {
        guard let section = TitleSection(stop) else {
            proxy.scrollTo(Self.top, anchor: .top)
            return
        }
        if let page = model.page, section == TitleSection.last(page, related: model.related) {
            proxy.scrollTo(TitleSection.end, anchor: .bottom)
        } else {
            proxy.scrollTo(section, anchor: TitleMetrics.sectionLine)
        }
    }
}

/// "Play on Plex" for each copy whose link an app on this Apple TV opens (DECISIONS 68): never a button that opens
/// nothing.
@MainActor
func playable(_ page: MediaPage) -> [PlayAction] {
    page.plays.filter { play in AppLinks.plex(play).map(AppOpener.canOpen) ?? false }
}

/// "Watch Trailer"'s link where the YouTube app opens it, else nil (DECISIONS 96, 98).
@MainActor
func trailerLink(_ page: MediaPage) -> URL? {
    guard let key = page.trailer, let url = AppLinks.trailer(key), AppOpener.canOpen(url) else { return nil }
    return url
}

/// The backdrop across the top, full-bleed: core's 1920 x 800 picture at its own size (decoded at it, never larger),
/// fading into the page at its foot and darker towards the left, where the words sit. Two still gradients, no blur:
/// nothing here moves when focus does (the Apple TV HD, DECISIONS 178). A title with no backdrop shows its poster on
/// the right instead.
struct TitleBackdrop: View {
    let url: String?
    var poster: String?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            if url != nil {
                ProxyImage(url: url)
                    .frame(width: 1920, height: TitleMetrics.backdropHeight)
            } else if let poster {
                ProxyImage(url: poster)
                    .frame(width: 400, height: 600)
                    .clipShape(RoundedRectangle(cornerRadius: Metrics.imageRadius, style: .continuous))
                    .padding(.top, 120)
                    .padding(.trailing, Metrics.sideMargin)
                    .accessibilityIdentifier("title poster")
            }
            LinearGradient(
                stops: [.init(color: Tokens.scrim.opacity(0.75), location: 0), .init(color: Tokens.scrim.opacity(0), location: 0.7)],
                startPoint: .leading,
                endPoint: .trailing
            )
            LinearGradient(
                stops: [
                    .init(color: Tokens.scrim.opacity(0), location: 0.35),
                    .init(color: Tokens.scrim.opacity(0.6), location: 0.75),
                    .init(color: Tokens.scrim, location: 1),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .frame(width: 1920, height: TitleMetrics.backdropHeight)
        .clipped()
        .accessibilityHidden(url != nil)
    }
}

/// The first screen's words, at the foot of the backdrop: the title, its line, its status, the actions, the overview.
private struct TitleHero: View {
    let model: TitleModel
    let page: MediaPage?
    let now: Date
    var focus: FocusState<TitleStop?>.Binding
    var onOverview: () -> Void

    var body: some View {
        let card = model.card
        VStack(alignment: .leading, spacing: 0) {
            Spacer(minLength: 0)
            Text(page?.title ?? card.title)
                .font(.title2.bold())
                .foregroundStyle(.white)
                .lineLimit(2)
                .accessibilityIdentifier("title name")
            if let page {
                MetadataLine(page: page).padding(.top, 16)
            } else if let year = card.year {
                Text(year).font(.callout).foregroundStyle(.secondary).padding(.top, 16)
            }
            let badges = page?.badges ?? (card.badge.map { [$0] } ?? [])
            if !badges.isEmpty {
                StatusLine(badges: badges, now: now).padding(.top, 16)
            }
            Actions(model: model, page: page, focus: focus)
                .padding(.top, 40)
            if let page {
                OverviewStop(page: page, onSelect: onOverview)
                    .focused(focus, equals: .overview)
                    .padding(.top, 28)
            }
        }
        .frame(width: TitleMetrics.heroWords, alignment: .leading)
        .frame(height: TitleMetrics.heroHeight, alignment: .bottomLeading)
        .padding(.leading, Metrics.sideMargin)
    }
}

/// What the title is, on one line as the Apple TV app writes it: the year, the runtime or the seasons, the genres,
/// then the certification in its outlined box.
private struct MetadataLine: View {
    let page: MediaPage

    var body: some View {
        HStack(spacing: 20) {
            Text(Formats.join([page.year] + page.attributes) ?? "")
                .lineLimit(1)
                .accessibilityIdentifier("title attributes")
            if let certification = page.certification {
                Text(certification)
                    .font(.caption.weight(.semibold))
                    .lineLimit(1)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(.secondary, lineWidth: 2))
                    .accessibilityIdentifier("title certification")
            }
        }
        .font(.callout)
        .foregroundStyle(.secondary)
    }
}

/// The title's status: each copy's glyph and word ("Available", "4K Requested"), and while a copy downloads, Seerr's
/// estimate of when it is done.
private struct StatusLine: View {
    let badges: [StatusBadge]
    let now: Date

    var body: some View {
        HStack(spacing: 40) {
            ForEach(badges, id: \.text) { badge in
                HStack(spacing: 12) {
                    StatusLabel(badge: badge)
                    if let words = badge.progress.flatMap({ downloadEstimate($0, now: now) }) {
                        Text(words)
                            .foregroundStyle(.secondary)
                            .accessibilityIdentifier("download estimate")
                    }
                }
            }
        }
        .font(.callout)
        .foregroundStyle(.white)
    }
}

/// The actions in a row, system buttons in Seerr's order: "Play on Plex" for each copy Plex has, the request buttons
/// (Request or Request More, then the 4K copy's where the account may ask for it, read live), then "Watch Trailer".
/// The first request button is the prominent one, in the app's indigo tint; while its request is out it says
/// "Requesting…". Report an Issue says "Issue Reported" once one has been. While loading, a placeholder of a button's
/// size holds focus; after a failure, the reason and Try Again.
private struct Actions: View {
    let model: TitleModel
    let page: MediaPage?
    var focus: FocusState<TitleStop?>.Binding

    var body: some View {
        switch model.state {
        case .loading:
            LoadingButton()
                .focused(focus, equals: .loading)
        case .failed:
            StateView(.failed, Words.titleFailed, id: "title failed") {
                Button(Words.tryAgain) { model.retry() }
                    .focused(focus, equals: .retry)
                    .accessibilityIdentifier("try again")
            }
        case .ready(let page):
            let plays = playable(page)
            let trailer = trailerLink(page)
            let reports = IssuesKt.canReportIssue(page: page)
            if !plays.isEmpty || !page.buttons.isEmpty || trailer != nil || reports {
                HStack(spacing: 32) {
                    ForEach(plays, id: \.is4k) { play in
                        Button { Task { await open(AppLinks.plex(play), problem: AlertText(title: Words.plexNotOpenedTitle, message: Words.plexNotOpened)) } } label: {
                            Label(play.text, systemImage: "play.fill")
                        }
                        .focused(focus, equals: .play(play.is4k))
                        .accessibilityIdentifier("action \(play.text)")
                        .debugLink(AppLinks.plex(play))
                    }
                    ForEach(Array(page.buttons.enumerated()), id: \.element.is4k) { index, button in
                        RequestActionButton(button: button, prominent: index == 0 && plays.isEmpty, sending: model.sending && model.draft?.is4k == button.is4k) {
                            model.open(button)
                        }
                            .focused(focus, equals: .request(button.is4k))
                            .accessibilityIdentifier("action \(button.text)")
                    }
                    if let trailer {
                        Button { Task { await open(trailer, problem: AlertText(title: Words.trailerNotOpenedTitle, message: Words.trailerNotOpened)) } } label: {
                            Label(Words.watchTrailer, systemImage: "film")
                        }
                        .focused(focus, equals: .trailer)
                        .accessibilityIdentifier("action watch trailer")
                        .debugLink(trailer)
                    }
                    // Last among the actions (DECISIONS 106), an everyday action in the system's own symbol, not Seerr's
                    // yellow warning (the review's S22). Once an issue is reported here it says so, as tvOS's own buttons
                    // say what they did (Tom's D4), and still opens the form for another.
                    if reports {
                        Button { model.openIssue() } label: {
                            if model.issueReported {
                                Label(Words.issueReported, systemImage: "checkmark")
                            } else {
                                Label(Words.reportIssue, systemImage: "exclamationmark.bubble")
                            }
                        }
                        .focused(focus, equals: .issue)
                        .accessibilityIdentifier("action report issue")
                    }
                }
                .focusSection()
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("title actions")
            }
        }
    }

    /// Hands the link to its app; if tvOS will not open it (the app went since the page was read), the alert says so.
    private func open(_ url: URL?, problem: AlertText) async {
        guard let url, await AppOpener.open(url) else {
            model.report(problem)
            return
        }
    }
}

/// A request button: the system's, the first prominent in the indigo tint (the page's main action when Plex has no
/// copy to play), the rest plain. While its request is out it says so, where it was pressed (Tom's D4).
private struct RequestActionButton: View {
    let button: RequestButton
    let prominent: Bool
    let sending: Bool
    var action: () -> Void

    var body: some View {
        let label = Label(sending ? Words.requesting : button.text, systemImage: button.action == .requestMore ? "plus.circle" : "arrow.down.circle")
        if prominent {
            Button(action: action) { label }.buttonStyle(.borderedProminent).tint(Tokens.accent)
        } else {
            Button(action: action) { label }
        }
    }
}

private extension View {
    /// A debug build carries the link a button opens as its accessibility value, so a UI test can read what it builds;
    /// a release build carries nothing.
    @ViewBuilder
    func debugLink(_ url: URL?) -> some View {
        #if DEBUG
        accessibilityValue(url?.absoluteString ?? "")
        #else
        self
        #endif
    }
}

/// A button's place while the page loads: a still button-sized shape; it holds focus until the buttons come.
private struct LoadingButton: View {
    var body: some View {
        Button {} label: {
            Capsule()
                .fill(Tokens.placeholder)
                .frame(width: 240, height: 66)
        }
        .buttonStyle(.borderless)
        .accessibilityIdentifier("loading")
    }
}

/// The tagline and the overview, four lines of it with "More" where it goes on, under the actions: a stop focus can
/// rest on (DECISIONS 50: a title with nothing to ask for still has a place for it); Select opens the whole text, as the
/// Apple TV app's description does.
private struct OverviewStop: View {
    let page: MediaPage
    var onSelect: () -> Void

    var body: some View {
        TextStop(text: page.overview ?? Words.overviewUnavailable, id: "overview", textId: "overview text", onSelect: onSelect) {
            if let tagline = page.tagline {
                Text(tagline)
                    .font(.callout.italic())
                    .lineLimit(1)
                    .accessibilityIdentifier("tagline")
            }
        }
        .frame(width: TitleMetrics.heroWords, alignment: .leading)
    }
}

/// The whole overview over the page, as the Apple TV app opens a description: the title, the tagline, and every word in
/// the text view that scrolls with the remote (the review's F28: a long overview could not be scrolled in its sheet).
/// Menu closes it.
private struct WholeOverview: View {
    let page: MediaPage

    var body: some View {
        ReadingScreen(
            title: page.title,
            text: Self.text(page),
            id: "overview screen",
            textId: "overview whole"
        )
    }

    static func text(_ page: MediaPage) -> ReadingText {
        var text = ReadingText()
        if let tagline = page.tagline { text = text.paragraph(tagline, italic: true) }
        return text.paragraph(page.overview ?? Words.overviewUnavailable)
    }
}
