import HitherCore
import SwiftUI

/// The pages opened from a tab page and from each other, left with Menu (Android's `Place`). Each opening is a page of
/// its own on its tab's stack, with its own model, so Menu finds the page under it as it was left.
enum Place: Hashable {
    /// A title's page, from any card (Discover's rows, Recent Requests, a grid, a search result, a person's credits, a
    /// related row).
    case title(TitleCard)
    /// A person's page, from a cast card or a search result.
    case person(id: Int32, name: String)
    /// A collection's films, from the collection card on a film's page.
    case collection(id: Int32, name: String)
    /// A keyword's titles of one type, from a keyword tag on a title's page.
    case keyword(Keyword, MediaType)
    /// A linked row's See More where Seerr links it to the Movies or Series page: the row's query under Seerr's sort and
    /// filters, headed by the row's title (none for Popular Movies and Series: "Movies", "Series").
    case browse(row: String, type: MediaType, base: DiscoverQuery, heading: String?)
    /// A row's own titles as a grid (Trending, a studio, a network, a search), from its See More.
    case rowGrid(String)
    /// Everyone's requests as the TV's admin (M5, DECISIONS 94), from "N waiting for approval" on My Requests.
    case approvals
}

/// The system tab bar's tabs, in Tom's order (the hybrid look, DECISIONS 181): Discover, Requests, Issues (with an
/// issue right, read live), Search (the system's search tab) and the account, which opens Settings.
enum HomeTab: String, Hashable {
    case discover, requests, issues, search, account

    var words: String {
        switch self {
        case .discover: Words.navDiscover
        case .requests: Words.navRequests
        case .issues: Words.navIssues
        case .search: Words.search
        case .account: Words.account
        }
    }
}

/// Signed in: the system's frame (DECISIONS 181) around tvOS-native content with Seerr's accent (Tom's native look,
/// DECISIONS 189). The system tab bar across the
/// tab pages, each tab with its own navigation stack for the pages opened from it, so Menu pops a page, Menu from a
/// tab page's content goes to the bar, and Menu on the bar leaves the app, all the system's own doing.
struct HomeView: View {
    let session: Session
    let gateway: SessionGateway
    /// How the account signed in (M8), read once: Sign Out's question says what signing in again takes.
    private let method: AccountMethod
    /// The TV's admin account (M5): kept by the app, so it outlives the home and goes with the family's account.
    let admin: AdminGateway
    /// A title a Top Shelf item (or any `hither://title/...` link) asked for; [onLinkTaken] once its page is open.
    let link: TitleLink?
    var onLinkTaken: () -> Void = {}
    let onSignOut: () -> Void

    @State private var tab: HomeTab = .discover
    @State private var discoverPath: [Place] = []
    @State private var requestsPath: [Place] = []
    @State private var issuesPath: [Place] = []
    @State private var searchPath: [Place] = []
    @State private var accountPath: [SettingsPlace] = []
    @State private var discover: DiscoverModel
    @State private var search: SearchModel
    @State private var requests: RequestsModel
    @State private var issues: IssuesModel
    @State private var hub: BadgeHub
    @State private var alerts: RequestAlerts
    @State private var notices: Notices
    /// Hither has been in the background since the page was last at the front (Android's `backgrounded`).
    @State private var backgrounded = false
    /// The way into the approval queue asking for the PIN first (the admin not active).
    @State private var switchingIn = false
    @Environment(\.scenePhase) private var scenePhase

    init(
        session: Session, gateway: SessionGateway, admin: AdminGateway, link: TitleLink? = nil,
        onLinkTaken: @escaping () -> Void = {}, onSignOut: @escaping () -> Void
    ) {
        self.session = session
        self.gateway = gateway
        method = gateway.storedMethod() ?? .local
        self.admin = admin
        self.link = link
        self.onLinkTaken = onLinkTaken
        self.onSignOut = onSignOut
        let hub = BadgeHub()
        let notices = Notices()
        let discover = DiscoverModel(session: session, gateway: gateway)
        let search = SearchModel(gateway: gateway, baseUrl: session.baseUrl)
        hub.join(discover)
        hub.join(search)
        _hub = State(initialValue: hub)
        _notices = State(initialValue: notices)
        _discover = State(initialValue: discover)
        _search = State(initialValue: search)
        _requests = State(initialValue: RequestsModel(
            source: .server(gateway, userId: session.user.id),
            names: TitleNames(gateway: gateway, baseUrl: session.baseUrl)
        ))
        _issues = State(initialValue: IssuesModel(source: .server(gateway), names: TitleNames(gateway: gateway, baseUrl: session.baseUrl)))
        _alerts = State(initialValue: RequestAlerts(session: session, gateway: gateway, notices: notices))
    }

    var body: some View {
        TabView(selection: $tab) {
            Tab(value: HomeTab.discover) {
                NavigationStack(path: $discoverPath) {
                    DiscoverView(
                        model: discover,
                        current: tab == .discover,
                        onSeeMore: seeMore,
                        onTitle: { discoverPath.append(.title($0)) }
                    )
                    .navigationDestination(for: Place.self) { destination($0, path: $discoverPath) }
                }
            } label: {
                Text(Words.navDiscover)
            }
            Tab(value: HomeTab.requests) {
                NavigationStack(path: $requestsPath) {
                    RequestsView(
                        model: requests,
                        current: tab == .requests && requestsPath.isEmpty,
                        waiting: alerts.waitingShown,
                        // The way into everyone's requests while an admin is kept on the TV (DECISIONS 94).
                        onWaiting: admin.keptName == nil ? nil : openApprovals,
                        onTitle: { requestsPath.append(.title($0)) },
                        onDiscover: { tab = .discover }
                    )
                    .navigationDestination(for: Place.self) { destination($0, path: $requestsPath) }
                }
            } label: {
                RequestsTabLabel(count: alerts.count)
            }
            // Only with an issue right, read live with Discover's plan (Android's rule, 106); its own page keeps it, so
            // the current tab never goes from under focus.
            if discover.seesIssues || tab == .issues {
                Tab(value: HomeTab.issues) {
                    NavigationStack(path: $issuesPath) {
                        IssuesView(model: issues, current: tab == .issues && issuesPath.isEmpty, onTitle: { issuesPath.append(.title($0)) })
                            .navigationDestination(for: Place.self) { destination($0, path: $issuesPath) }
                    }
                } label: {
                    Text(Words.navIssues)
                }
            }
            Tab(value: HomeTab.search, role: .search) {
                NavigationStack(path: $searchPath) {
                    SearchView(model: search, onTitle: { searchPath.append(.title($0)) }, onPerson: { searchPath.append(.person(id: $0.personId, name: $0.name)) })
                        .navigationDestination(for: Place.self) { destination($0, path: $searchPath) }
                }
            } label: {
                // The lone magnifying glass, as Apple's own bars end (the review's F23): the system's search tab, its
                // words for VoiceOver only.
                Label(Words.search, systemImage: "magnifyingglass")
                    .labelStyle(.iconOnly)
            }
            Tab(value: HomeTab.account) {
                NavigationStack(path: $accountPath) {
                    SettingsView(session: session, admin: admin, method: method, onSignOut: onSignOut)
                        .navigationDestination(for: SettingsPlace.self) { place in
                            SettingsPage(place: place, session: session, admin: admin, onBack: { if !accountPath.isEmpty { accountPath.removeLast() } })
                        }
                }
            } label: {
                AccountTabLabel(displayName: session.user.displayName, admin: admin.active?.displayName)
            }
        }
        // The notices on returning, top right on every page (DECISIONS 211; Tom's D4: the one banner left).
        .overlay { NoticeBanner(notices: notices) }
        // The PIN on tvOS's own digit entry, full screen (DECISIONS 93; polish part 4); Menu closes it.
        .fullScreenCover(isPresented: $switchingIn) {
            SwitchPin(admin: admin, name: admin.keptName ?? "", onIn: { _ in
                switchingIn = false
                requestsPath.append(.approvals)
            }, onGone: { _ in
                switchingIn = false
                _ = admin.kept()
            })
        }
        .onChange(of: admin.active) { _, active in
            // The queue is the admin's: when Hither goes back to the family (idle, leaving the front, Switch Back), it goes.
            if active == nil, let at = requestsPath.firstIndex(of: .approvals) { requestsPath.removeSubrange(at...) }
        }
        .onChange(of: admin.keptName) { _, name in alerts.readAdminWaiting(kept: name != nil) }
        .task(id: tab) {
            // Opening My Requests starts the count again (Android's 79). On tvOS a tab's page shows as focus passes over
            // its tab, so the page counts as opened once it has stayed for a moment: walking the bar from Discover to
            // Search keeps the count (DECISIONS 184).
            guard tab == .requests else { return }
            try? await Task.sleep(for: RequestAlerts.openedAfter)
            if !Task.isCancelled, tab == .requests { alerts.opened() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .background:
                backgrounded = true
                // Hither leaving the front is Hither closed, for the admin's guard (Tom's, DECISIONS 93).
                admin.closed()
                BackgroundLook.schedule()
            case .active:
                // Live status (Android's LifecycleStartEffect): coming back to the front, the cards' badges, Recent
                // Requests and My Requests are read again; and what changed since, no more than a look is due.
                if backgrounded {
                    backgrounded = false
                    discover.refreshBadges()
                    discover.refreshRecent()
                    requests.refresh()
                    alerts.readAdminWaiting(kept: admin.keptName != nil)
                }
                alerts.front()
            default:
                break
            }
        }
        .onChange(of: discoverShowing) { _, showing in
            // Recent Requests is read again as Discover comes up, as Seerr's slider reads it whenever Discover mounts
            // (DECISIONS 118).
            if showing { discover.refreshRecent() }
        }
        .onChange(of: link?.uri, initial: true) { _, _ in openLink() }
        .task {
            discover.start()
            alerts.front()
            alerts.readAdminWaiting(kept: admin.kept() != nil)
        }
    }

    /// "N waiting for approval": the queue while the admin is active, else the PIN first (DECISIONS 94).
    private func openApprovals() {
        if admin.active != nil {
            requestsPath.append(.approvals)
        } else {
            switchingIn = true
        }
    }

    /// Discover's own page is on screen (its tab, nothing pushed over it).
    private var discoverShowing: Bool { tab == .discover && discoverPath.isEmpty }

    /// A title's link (the Top Shelf's): its page on Discover's stack, over Discover, with the kept name and poster
    /// standing in while it loads (Android's `Place.Media(link, leaves = true)`). Menu from it comes back to Discover:
    /// a tvOS app does not leave itself, as Android's Back from a link's page does.
    private func openLink() {
        guard let link else { return }
        let art = alerts.ready.art.get(link.key)
        let card = TitleCard(key: link.key, tmdbId: link.tmdbId, type: link.type, title: art?.title ?? "", year: art?.year, poster: art?.poster, badge: nil, overview: nil)
        tab = .discover
        discoverPath = [.title(card)]
        onLinkTaken()
    }

    /// A page opened from a tab page, full screen without the tab bar (each page's `PageScaffold` hides it), as the Apple
    /// TV app opens a title (DECISIONS 193); Menu takes it away and the bar comes back with the tab page.
    @ViewBuilder
    private func destination(_ place: Place, path: Binding<[Place]>) -> some View {
        let open: (TitleCard) -> Void = { path.wrappedValue.append(.title($0)) }
        switch place {
        case .title(let card):
            TitleView(
                model: TitleModel(card: card, session: session, gateway: gateway, hub: hub),
                onPerson: { id, name in path.wrappedValue.append(.person(id: id, name: name)) },
                onTitle: open,
                onCollection: { path.wrappedValue.append(.collection(id: $0.id, name: $0.name)) },
                onKeyword: { path.wrappedValue.append(.keyword($0, card.type)) }
            )
        case .person(let id, let name):
            PersonView(model: PersonModel(personId: id, name: name, gateway: gateway, hub: hub), onTitle: open)
        case .collection(let id, let name):
            TitleGridView(
                model: TitleGridModel(gateway: gateway, baseUrl: session.baseUrl, hub: hub, source: GridSources.collection(id)),
                heading: name,
                id: "collection \(id)",
                onTitle: open
            )
        case .keyword(let keyword, let type):
            TitleGridView(
                model: TitleGridModel(gateway: gateway, baseUrl: session.baseUrl, hub: hub, source: GridSources.keyword(keyword.id, type: type)),
                heading: type == .movie ? Words.keywordMovies(keyword.name) : Words.keywordSeries(keyword.name),
                id: "keyword \(keyword.id)",
                onTitle: open
            )
        case .browse(let row, let type, let base, let heading):
            BrowseView(
                model: BrowseModel(type: type, base: base, heading: heading, session: session, gateway: gateway, hub: hub),
                id: row,
                onTitle: open
            )
        case .approvals:
            ApprovalsView(
                model: ApprovalsModel(source: .admin(admin, baseUrl: session.baseUrl), onDecided: decided),
                approverName: admin.active?.displayName ?? "",
                onTitle: open
            )
        case .rowGrid(let name):
            if let row = discover.row(named: name) {
                TitleGridView(
                    model: TitleGridModel(gateway: gateway, baseUrl: session.baseUrl, hub: hub, source: GridSources.row(row.row, discover: discover)),
                    heading: row.row.title,
                    id: name,
                    onTitle: open
                )
            }
        }
    }

    /// A row's See More (Android's `seeMoreTarget`): the Movies or Series page with the row's query where Seerr links it
    /// there (Popular Movies and Series under their plain names, every other row under its title), else the row's own
    /// titles as a grid.
    private func seeMore(_ row: RowModel) {
        guard let target = DiscoverKt.seeMoreTarget(row: row.row, today: Self.today()) else { return }
        if let browse = target as? SeeMoreBrowse {
            let plain = row.row.source is RowSourcePopularMovies || row.row.source is RowSourcePopularSeries
            discoverPath.append(.browse(row: row.row.name, type: browse.type, base: browse.base, heading: plain ? nil : row.row.title))
        } else {
            discoverPath.append(.rowGrid(row.row.name))
        }
    }

    /// The waiting count following a decision. What the decision came to shows in the queue itself: the lockup's new
    /// status, or the queue's alert (Tom's D4).
    private func decided(_ outcome: Decided) {
        switch outcome {
        case .done:
            alerts.decided(adminKept: admin.keptName != nil)
        case .notPending:
            alerts.readAdminWaiting(kept: admin.keptName != nil)
        case .noRight, .failed:
            break
        }
    }

    /// The device's date, as Seerr's `upcomingDate`: the Upcoming rows' first date.
    private static func today() -> String {
        let format = DateFormatter()
        format.calendar = Calendar(identifier: .gregorian)
        format.locale = Locale(identifier: "en_US_POSIX")
        format.dateFormat = "yyyy-MM-dd"
        return format.string(from: Date())
    }
}

/// The Requests tab: its word, and while there are changes to the user's requests since My Requests was last opened, their
/// number in a circle as the tab's symbol (Android's count circle, DECISIONS 146). tvOS draws no badge on a tab bar item
/// (SwiftUI's `badge` is not available on tvOS, and UIKit's `badgeValue` is not drawn there), so the count is the tab's own
/// symbol, drawn by the system with the tab (DECISIONS 184). Core keeps at most 20 changes, and SF Symbols number circles
/// to 50, so every count has its symbol.
struct RequestsTabLabel: View {
    let count: Int

    var body: some View {
        if count > 0 {
            Label(Words.navRequests, systemImage: "\(min(count, 50)).circle.fill")
                .accessibilityLabel("\(Words.navRequests), \(Words.requestsChanges(count))")
        } else {
            Text(Words.navRequests)
        }
    }
}

/// The account's tab, last on the bar: the person symbol alone, as tvOS's own apps end their bar beside search's glass
/// (the review's F23: the bar mixed words, a symbol with words, and the account's name with a symbol). The account's
/// name is its words for VoiceOver, and Settings shows it. While the TV's admin is active it is the Admin badge's place
/// (DECISIONS 183), fed by the admin's real state (`AdminGateway.active`, part 6b): "Admin", Seerr's badge word, with the
/// person-and-shield symbol, the one worded state of the bar's end, so the switch can't be missed.
struct AccountTabLabel: View {
    let displayName: String
    /// The TV's admin while it is active (its name is on Settings); nil for the family.
    let admin: String?

    var body: some View {
        if admin != nil {
            Label(Words.adminBadge, systemImage: "person.badge.shield.checkmark")
        } else {
            Label(displayName.isEmpty ? Words.account : displayName, systemImage: "person.crop.circle")
                .labelStyle(.iconOnly)
        }
    }
}
