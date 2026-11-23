import HitherCore

/// The app's words: Android's `res/values/strings.xml` where both say the same thing; Seerr's vocabulary (the status
/// words, its permission names) where it names Seerr's own things, and Hither's plain sentences everywhere else. English only, as Android (HANDOVER: the account's language decides the titles, not the app).
///
/// tvOS's own voice where a word is drawn by the system or reads as a web form's (polish part 7, the review's X11):
/// title case for what labels a control or heads a shelf, short plain sentences for what to type, no exclamation marks.
/// Words that come from `core` (the sorts, the filters' count) are mapped here by value, so `core` and Android keep
/// Seerr's. Since H1 Android says the sign-in words as tvOS does, and `SignInTests.testTheSameWordsAsAndroid` holds
/// the two apps to it; Hither's own plain words stand where Seerr's sentences were (DECISIONS 288).
enum Words {
    static let appName = "Hither"
    static let signInHeading = "Sign in to your server"
    /// The form's heading once the server has answered, by its title (Seerr's "Login with Seerr" was a second verb over
    /// Sign In: the review's S13).
    static func signInHeading(server: String) -> String { "Sign in to \(server)" }
    static let fieldServer = "Server Address"
    static let fieldEmail = "Email Address"
    static let fieldPassword = "Password"
    static let signIn = "Sign In"
    static let signingIn = "Signing In…"
    /// The line over the other ways in, under the password form (Android's `sign_in_or`).
    static let signInOr = "Other ways to sign in"

    // M8: signing in through the media server (Android's M8 block in `strings.xml`), in plain words.
    static let signInChecking = "Checking the server…"
    /// The Jellyfin or Emby form's heading: "Sign in with Jellyfin", as "Sign in with Plex" (Seerr's JellyfinLogin says
    /// "Login with Jellyfin"; S13). "with", not "to": the account is the media server's, the sign-in Seerr's.
    static func signInWithMediaServer(_ server: String) -> String { "Sign in with \(server)" }
    static let fieldUsername = "Username"
    static let errorUsername = "Enter a username."
    static let errorMediaCredentials = "The server did not accept that username and password."
    static let quickConnect = "Quick Connect"
    static func errorNotImported(_ server: String) -> String {
        "Seerr does not know this \(server) account yet. Ask whoever runs Seerr to import it as a user, then sign in again."
    }
    static let errorNotImportedPlex = "Seerr refused this Plex account: it is not one of Seerr's users, or it has no access to the server's Plex. Ask whoever runs Seerr."
    static func errorMethodOff(_ server: String) -> String { "Signing in with \(server) is switched off on this server." }
    static func errorMediaServerUnreachable(_ server: String) -> String { "Seerr could not reach its \(server) server. Try again later." }
    static let errorSessionEnded = "Your session on this server has ended. Sign in again."
    static let plexHeading = "Sign in with Plex"
    static let plexGoTo = "On a phone or computer, go to"
    static let plexLink = "plex.tv/link"
    static let plexEnter = "and enter this code:"
    static let plexWaiting = "Waiting for the code to be entered…"
    static let plexExpired = "This code expired before it was entered at plex.tv/link."
    static let plexUnreachable = "plex.tv could not be reached. Check the TV's internet connection, then try again."
    static let quickConnectHeading = "Quick Connect"
    static let quickConnectEnter = "Enter this code in your Jellyfin app"
    static let quickConnectWhere = "Signed in to Jellyfin on a phone or computer: your profile, then Quick Connect."
    static func quickConnectWhere(at place: String) -> String {
        "Signed in to Jellyfin at \(place) on a phone or computer: your profile, then Quick Connect."
    }
    static let quickConnectWaiting = "Waiting for the code to be approved…"
    static let quickConnectExpired = "This code expired before it was approved in Jellyfin."
    static let quickConnectUnavailable = "Quick Connect did not start. It may be switched off in Jellyfin's dashboard."
    static let codeGetting = "Getting a code…"
    static let codeExpired = "Code Expired"
    static let codeNew = "Get a New Code"
    static let codeCancel = "Cancel"
    /// A code as VoiceOver reads it: one character at a time ("Code 4 7 2 9").
    static func codeDescribed(_ code: String) -> String { "Code " + code.map(String.init).joined(separator: " ") }
    // What a field still needs, as a short plain sentence (the review's S14: Seerr's "You must provide…").
    static let errorServer = "Enter the server's address."
    static let errorEmail = "Enter an email address."
    static let errorPassword = "Enter a password."
    static let errorCredentials = "The email address or password is incorrect."
    static let errorSignIn = "Signing in did not work. Try again."
    static let errorUnreachable = "No Seerr server answered at that address."
    static let errorLocalLoginOff = "Password sign-in is switched off on this server."
    static let errorCsrf = "This server's CSRF protection needs https. Use its https address, or turn CSRF protection off in Seerr's network settings."
    static func errorUnreachableNamed(_ server: String) -> String {
        "Hither cannot reach the Seerr server at \(server). Your sign-in is kept: Try Again once it is back."
    }
    static let errorUnreachableKept = "Hither cannot reach the Seerr server. Your sign-in is kept: Try Again once it is back."
    static let errorCsrfLater = "This server's CSRF protection needs https, so Hither cannot sign in again over http. Turn CSRF protection off in Seerr's network settings, or sign out and use the server's https address."
    static let tryAgain = "Try Again"

    static let navDiscover = "Discover"
    static let navRequests = "Requests"
    static let navIssues = "Issues"
    static let searchPlaceholder = "Movies, Series and People"
    static let search = "Search"
    static let searchEmpty = "Find a movie or series by its title, or a person by name."
    static let searchFailed = "The search did not load."
    static let account = "Account"
    static let adminBadge = "Admin"
    static func requestsChanges(_ n: Int) -> String { "\(n) changes to your requests" }

    static let recentRequests = "Recent Requests"
    static let rowFailed = "This row did not load."
    /// A list with nothing in it, as tvOS's `ContentUnavailableView.search` titles it (the review's S37).
    static let noResults = "No Results"
    /// Search's: the system's own words for a search that found nothing.
    static func noResultsFor(_ words: String) -> String { "No Results for \u{201C}\(words)\u{201D}" }
    static let noResultsHint = "Check the spelling or try a new search."
    static let seeMore = "See More"
    static let seeMoreMovies = "See More Movies"
    static let seeMoreSeries = "See More Series"
    static func seeMoreRow(_ title: String) -> String { "See More \(title)" }
    static let status = "Status"
    static let unknownTitle = "Unknown Title"

    static let settings = "Settings"
    static let signOut = "Sign Out"
    static let signOutTitle = "Sign out?"
    /// What signing in again takes, by the account's way in (M8 part 2's report: the one sentence claimed a password for
    /// Plex and Quick Connect accounts too). Android says the first for every account.
    static func signOutBody(_ method: AccountMethod) -> String {
        switch method {
        case .local: "Signing in again takes this account's password, typed with the remote."
        case .mediaServer: "Signing in again takes this account's username and password, typed with the remote."
        case .plex: "Signing in again takes a new code, entered at plex.tv/link on a phone or computer."
        case .quickConnect: "Signing in again takes a new Quick Connect code, approved in Jellyfin on a phone or computer."
        }
    }
    static let cancel = "Cancel"
    static let signedInAs = "Signed in as"
    static let server = "Server"

    // The title page (Android's media page) and the request modal.
    static let titleFailed = "This page did not load."
    static let trying = "Trying…"
    static let overviewUnavailable = "Overview unavailable."
    static let castAndCrew = "Cast & Crew"
    static let collection = "Collection"
    static let viewCollection = "View Collection"
    static let information = "Information"
    static let keywords = "Keywords"
    static let movie = "Movie"
    static let seriesOne = "Series"
    static let recommendations = "Recommendations"
    static let similarTitles = "Similar Titles"
    static let similarSeries = "Similar Series"
    static let seasons = "Seasons"
    static let specials = "Specials"
    static func season(_ n: Int32) -> String { "Season \(n)" }
    /// Seasons side by side, as Information names a run of them (Tom's D2): "Seasons 2–4".
    static func seasons(_ first: Int32, _ last: Int32) -> String { "Seasons \(first)–\(last)" }
    static func episodes(_ n: Int32) -> String { n == 1 ? "1 Episode" : "\(n) Episodes" }
    static let streamingOn = "Currently Streaming On"
    static let watchTrailer = "Watch Trailer"
    static let plexNotOpened = "Plex could not be opened on this TV."
    static let trailerNotOpened = "The trailer could not be opened on this TV."
    static let plexNotOpenedTitle = "Can't Open Plex"
    static let trailerNotOpenedTitle = "Can't Open the Trailer"
    static let approvedAutomatically = "This request needs no approval."
    static let allSeasons = "All Seasons"
    /// A film's request, asked in the system's alert (the review's F2): the title, and its copy where it is the 4K one.
    static func requestQuestion(_ title: String, fourK: Bool) -> String { fourK ? "Request \(title) in 4K?" : "Request \(title)?" }
    /// The alert's button, short so tvOS sets it beside Cancel.
    static let request = "Request"
    /// The request button while its request is out (Tom's D4: said where it was pressed).
    static let requesting = "Requesting…"
    /// Seerr's quota line as a sentence (the review's S16: words, not its heat-coloured ring): "2 movie requests
    /// left. You may request 3 movies every 7 days."
    static func quota(_ left: String, _ allowance: String) -> String { "\(left.prefix(1).uppercased() + left.dropFirst()). \(allowance)" }
    /// A request that was not made: the alert's title over Seerr's or Android's sentence.
    static let requestNotSent = "Request Not Sent"
    static let ok = "OK"
    /// After a few lines of a longer text: Select opens the whole (the Apple TV app's word).
    static let more = "More"
    static let requestNothingLeft = "All of the selected seasons are already requested or available."
    static func requestAlready(_ title: String) -> String { "\(title) has already been requested." }
    static let requestRefused = "The server refused this request."
    static let requestUnreachable = "The server did not answer. Look at the status here before asking again."
    static let requestError = "The request did not go through. Try again."
    static let requestCsrf = "This server's CSRF protection needs https, so the request was not made."
    static func estimated(_ relative: String) -> String { "Estimated \(relative)" }

    // The person, collection, keyword and See More pages.
    static let personFailed = "This person did not load."
    static func born(_ date: String) -> String { "Born \(date)" }
    static func lifespan(_ born: String, _ died: String) -> String { "\(born) – \(died)" }
    static func alsoKnownAs(_ names: String) -> String { "Also Known As: \(names)" }
    static let appearances = "Appearances"
    static let crew = "Crew"
    static func playing(_ part: String) -> String { "as \(part)" }
    /// A keyword's titles, its first letter capitalised (TMDB's keywords are lower case: "outbreak Movies" was a
    /// heading starting in lower case, the review's S38). The rest as TMDB has it, so "DC" or "1970s" stay.
    static func keywordMovies(_ keyword: String) -> String { "\(capitalisedFirst(keyword)) Movies" }
    static func keywordSeries(_ keyword: String) -> String { "\(capitalisedFirst(keyword)) Series" }
    static func capitalisedFirst(_ words: String) -> String { words.prefix(1).uppercased() + words.dropFirst() }
    static let gridFailed = "These titles did not load."
    static let movies = "Movies"
    static let series = "Series"
    static let sortBy = "Sort By"
    /// One of Seerr's sorts in tvOS's words, by TMDB's value (the review's S35: "Popularity Descending" and "Title (A-Z)
    /// Ascending" read as a database's); nil for a value core may add later, which keeps core's words.
    static func sortName(_ value: String) -> String? {
        guard let dot = value.lastIndex(of: ".") else { return nil }
        let field = value[..<dot], down = value[value.index(after: dot)...] == "desc"
        return switch field {
        case "popularity": down ? "Most Popular" : "Least Popular"
        case "release_date", "first_air_date": down ? "Newest" : "Oldest"
        case "vote_average": down ? "Highest Rated" : "Lowest Rated"
        case "original_title", "original_name": down ? "Title Z to A" : "Title A to Z"
        default: nil
        }
    }
    /// The sort menu: core's sorts in tvOS's words, each pair's usual way first ("Most Popular" before "Least Popular",
    /// "Title A to Z" before "Title Z to A"), the pairs in Seerr's order.
    static func sorts(_ options: [SortOption]) -> [(value: String, words: String)] {
        let usual: Set = ["Most Popular", "Newest", "Highest Rated", "Title A to Z"]
        func field(_ value: String) -> Substring { value.lastIndex(of: ".").map { value[..<$0] } ?? Substring(value) }
        var fields: [Substring] = []
        for option in options where !fields.contains(field(option.value)) { fields.append(field(option.value)) }
        let named = options.map { (value: $0.value, words: sortName($0.value) ?? $0.label) }
        return named.enumerated().sorted { a, b in
            let fa = fields.firstIndex(of: field(a.element.value)) ?? 0, fb = fields.firstIndex(of: field(b.element.value)) ?? 0
            if fa != fb { return fa < fb }
            let ua = usual.contains(a.element.words), ub = usual.contains(b.element.words)
            return ua != ub ? ua : a.offset < b.offset
        }.map(\.element)
    }
    static let filters = "Filters"
    /// The Filters button and the sheet's title, counting what is set: the button keeps its name (the review's S36; Seerr's
    /// "1 Active Filter" from core renamed it).
    static func filters(_ count: Int32) -> String { count > 0 ? "Filters (\(count))" : filters }
    static let clearFilters = "Clear Filters"
    static let releaseDate = "Release Date"
    static let firstAirDate = "First Air Date"
    static let genres = "Genres"
    static let originalLanguage = "Original Language"
    static let allLanguages = "All Languages"
    static let streamingServices = "Streaming Services"
    static let filtersLoading = "Loading the filters…"
    static let filtersFailed = "The filters could not be loaded."

    // My Requests (Android's ui/requests, DECISIONS 66, 101, 124): Seerr's words where it has them, Hither's own for the
    // empty and failed lines.
    static let requested = "Requested"
    static let modified = "Modified"
    static func whenBy(_ time: String, _ who: String) -> String { "\(time) by \(who)" }
    static let seasonOne = "Season"
    static let requestsEmpty = "No requests yet."
    static let requestsEmptyHint = "What you request shows here, with its status."
    static let requestsFailed = "Your requests did not load."
    static let backToDiscover = "Back to Discover"
    static let cancelRequest = "Cancel Request"
    static let cancellingRequest = "Cancelling…"
    static let keepRequest = "Keep Request"
    static let cancelRequestTitle = "Cancel the request?"
    static func cancelRequestBody(_ title: String?) -> String {
        title.map { "The request for \($0) will be removed from Seerr. It can be asked for again." }
            ?? "The request will be removed from Seerr. It can be asked for again."
    }
    static let requestNotCancelled = "Request Not Cancelled"
    static let notCancellable = "This request is no longer pending, so it cannot be cancelled."
    /// Seerr's own words for a request that could not be changed (Android uses them for a failed cancel too).
    static let modifyFailed = "The request could not be changed. Try again."

    // The notices (Android's `Notices`, DECISIONS 79): a change to one of the user's requests, in Seerr's words.
    static let readyToWatch = "Ready to Watch"
    static let readyToWatch4k = "Ready to Watch in 4K"
    static let noticeApproved = "Approved"
    static let noticeDeclined = "Declined"

    // Issues (Android's ui/issues and the media page's issue form, DECISIONS 106).
    static let reportIssue = "Report an Issue"
    static let affectedSeason = "Affected Season"
    static let affectedEpisode = "Affected Episode"
    static let issueAllSeasons = "All Seasons"
    static let allEpisodes = "All Episodes"
    static func episode(_ n: Int32) -> String { "Episode \(n)" }
    static let issueKindHeader = "Type"
    static let whatsWrong = "What's wrong?"
    /// The words' placeholder (the review's S23: not Seerr's web-form sentence).
    static let issueDetail = "Describe the problem"
    static let submitIssue = "Submit Issue"
    static let submittingIssue = "Submitting…"
    /// Report an Issue once one has been reported from the page (Tom's D4: said where it was pressed).
    static let issueReported = "Issue Reported"
    static let issueNotSent = "Issue Not Sent"
    static let issueFailed = "The issue did not go through. Try again."
    static let issueNoRight = "This account may no longer report issues."
    static let issuesFailed = "The issues did not load."
    static let issuesLoading = "Loading issues…"
    static let showAllIssues = "Show All Issues"
    static let issuesNoRight = "This account may no longer see issues."
    static let issueType = "Type"
    static let issueOpened = "Opened"

    // Settings, About, Privacy, Diagnostics (Android's ui/settings, DECISIONS 87, 107; docs/attribution.md).
    static let about = "About"
    static let privacy = "Privacy"
    static let diagnostics = "Diagnostics"
    static let versionLabel = "Version"
    static let acknowledgements = "Acknowledgements"
    static let unofficial = "Hither is an independent client for Seerr servers, not made by, affiliated with or endorsed by the Seerr project."
    /// TMDB's terms of use, section 3, word for word (`docs/attribution.md`).
    static let tmdbNotice = "This application uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB."
    static let tmdb = "TMDB"
    /// What is still Seerr's (DECISIONS 289): its API, its colours (the indigo, the status colours) and the logo's
    /// gradient; the tvOS look is the Apple TV app's (189). Inter is not bundled here (186), but the logo's word is
    /// drawn from it.
    static let credits = "API and colours adapted from Seerr (MIT); the logo's gradient is Seerr's icon's. Some code adapted from SeerrTV (MIT). The logo's word is set in Inter (SIL Open Font License 1.1)."
    static func diagnosticsAbout(_ kept: Int32) -> String {
        "The last \(kept) problems Hither recovered from since it started, newest first: what, when, and the call. Never the server's address, a search, or anything the server sent."
    }
    static let diagnosticsNone = "Nothing has gone wrong since Hither started."

    // The TV's admin account (Android's M5 words, DECISIONS 92-94; "a press on the remote" for Android's "key press").
    static let adminAccount = "Admin Account"
    static let adminIntro = "Approve and decline the family's requests from the TV. Sign in once with the Seerr account made for approving on this server: one with Manage Requests and nothing else. Switching to it asks for a 4-digit PIN you choose now."
    static let adminHeadingSignIn = "Sign in as the approver"
    static let adminRefusedFamily = "That is this TV's own account. Sign in with the account made for approving. Nothing was kept."
    static let adminRefusedNoRight = "That account cannot approve requests on this server: it needs Manage Requests. Nothing was kept."
    static let adminAdministratorTitle = "This account is an administrator"
    static func adminAdministratorBody(_ name: String) -> String {
        "\(name) holds every right on this server. An account with Manage Requests only is what the TV's admin is meant to be: it approves and declines, and nothing more. This one works too."
    }
    static let adminCarryOn = "Carry On"
    static let pinChoose = "Choose a 4-digit PIN"
    static let pinAgain = "Enter the PIN again"
    static let pinMismatch = "The two PINs were not the same. Choose one again."
    static func pinChooseNote(_ name: String) -> String { "It is asked for each time the TV switches to \(name)." }
    static let pinSaveFailed = "The admin account could not be kept on this TV. Choose the PIN again."
    static let pinSaving = "Keeping the account…"
    static let adminKeptRole = "Manage Requests"
    static let adminKeptNote = "Switching to this account asks for its PIN. A forgotten PIN: remove the account and sign in again."
    static let adminActiveNote = "Active now. Hither goes back to the family's account after 10 minutes without a press on the remote, when it leaves the screen, or with Switch Back."
    static func adminActiveAs(_ name: String) -> String { "Approving as \(name) until Hither goes back to the family's account." }
    static let adminRemove = "Remove Admin Account"
    static let adminRemoving = "Removing…"
    static let adminRemoveTitle = "Remove the admin account?"
    static let adminRemoveBody = "The TV forgets the account, its password and the PIN. Approving from the TV again takes the account's password, typed with the remote."
    static func adminSwitchIn(_ name: String) -> String { "Switch to \(name)" }
    static let adminSwitchBack = "Switch Back"
    static func adminSwitchBackTo(_ name: String) -> String { "Switch Back to \(name)" }
    static let adminGoneTries = "Too many wrong PINs: the TV forgot the admin account. Sign in again with its password to choose a new PIN."
    static let adminGonePassword = "The admin account's password has changed, so the TV forgot it. Sign in again with the new password."
    static func switchTitle(_ name: String) -> String { "Switch to \(name)" }
    static let switchAsk = "Enter the PIN."
    static let switchChecking = "Checking…"
    static func switchWrong(_ left: Int) -> String {
        left == 1
            ? "That PIN is not right. 1 more wrong PIN and the TV forgets the admin account."
            : "That PIN is not right. \(left) tries left."
    }
    static func switchWait(_ seconds: Int) -> String {
        "Too many wrong PINs. Try again in \(seconds / 60):\(String(format: "%02d", seconds % 60))."
    }
    static func switchNoRight(_ name: String) -> String { "\(name) can no longer approve requests on this server: it needs Manage Requests." }
    static let switchFailed = "The server did not answer as expected. Try again."

    // Everyone's requests as the TV's admin (Android's approvals, DECISIONS 94); Seerr's words where it has them.
    static func waitingForApproval(_ n: Int) -> String { "\(n) waiting for approval" }
    static let waitingOpen = "Opens the requests waiting for approval"
    static func approvalsNoRight(_ name: String) -> String { "\(name) cannot approve requests on this server: it needs Manage Requests." }
    static let approvalsLoading = "Loading…"
    static let approvalsFailed = "The requests did not load."
    static let showAllRequests = "Show All Requests"
    static let approve = "Approve"
    static let approving = "Approving…"
    static let decline = "Decline"
    static let declining = "Declining…"
    static let declineTitle = "Decline the request?"
    static func declineBody(_ title: String, _ who: String) -> String {
        "\(title), asked for by \(who), is declined. Seerr does not take it back: it would have to be asked for again."
    }
    /// A decision that was not made: the alert's title over why (Tom's D4: no toast).
    static let notApproved = "Request Not Approved"
    static let notDeclined = "Request Not Declined"
    static func decisionNotPending(_ title: String) -> String { "\(title) is no longer pending: someone has decided it." }
    static let decisionNoRight = "This account can no longer approve requests: it needs Manage Requests."

    // The list pages as tvOS's Library (polish part 3, DECISIONS 259).
    static let myRequests = "My Requests"
    /// The Requests page's side-list row into everyone's requests, its count at its right (the review's S33).
    static let waitingRow = "Waiting for Approval"
    static let openTitle = "Open Title"
    /// A list lockup's place for its title while the title's name is on its way (drawn as a placeholder bar).
    static let loadingTitle = "Loading title"
    static func openedBy(_ who: String) -> String { "Opened by \(who)" }

    /// A sign-in failure in the form's words (Android's `SignInFailure.message`). [server] is the server's media server,
    /// named in the failures about it ("Jellyfin", "Emby"; "media server" when it is not known); Plex has its own
    /// sentences for an account Seerr refused and for a code that ran out.
    static func signInFailure(_ failure: SignInFailure, server: MediaServerKind? = nil) -> String {
        let name = server.map(\.title).flatMap { $0.isEmpty ? nil : $0 } ?? "media server"
        return switch failure {
        case .wrongCredentials: errorCredentials
        case .localLoginDisabled: errorLocalLoginOff
        case .unreachable: errorUnreachable
        case .csrfNeedsHttps: errorCsrf
        case .other: errorSignIn
        case .notImported: server == .plex ? errorNotImportedPlex : errorNotImported(name)
        case .methodOff: errorMethodOff(name)
        case .mediaServerUnreachable: errorMediaServerUnreachable(name)
        case .codeExpired: server == .plex ? plexExpired : quickConnectExpired
        case .plexUnreachable: plexUnreachable
        case .quickConnectUnavailable: quickConnectUnavailable
        case .sessionEnded: errorSessionEnded
        }
    }

    /// A failure on Jellyfin's or Emby's form: a wrong password is the username's, as Seerr's JellyfinLogin says it.
    static func mediaServerFailure(_ failure: SignInFailure, server: MediaServerKind) -> String {
        failure == .wrongCredentials ? errorMediaCredentials : signInFailure(failure, server: server)
    }
}
