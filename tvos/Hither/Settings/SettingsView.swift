import HitherCore
import SwiftUI

/// The pages Settings opens, left with Menu.
enum SettingsPlace: Hashable {
    case about, privacy, diagnostics
    /// The TV's admin account (M5, DECISIONS 92).
    case admin
}

/// Settings, the account's tab at the bar's end (DECISIONS 181), as tvOS's Settings app draws its own
/// (`SettingsScaffold`, the review's X4): Hither's mark on the left; on the right, Android's rows in Android's order
/// (`SettingsScreen`): who is signed in and on which server (its name, its address without the scheme: the review's
/// F37); About (the version, the privacy text, Diagnostics, TMDB's notice) and Admin Account; then Sign Out, red words
/// by its role, which asks first in the system's alert (Android's `ConfirmSignOut`), Cancel first. While the admin is
/// active, "Switch Back to {family}" comes first and takes focus, one Select from the account's tab, and the account
/// shown is the approver's, as Seerr shows whoever is signed in (93, 109).
struct SettingsView: View {
    let session: Session
    let admin: AdminGateway
    /// How the account signed in, for what Sign Out says signing in again takes.
    var method: AccountMethod = .local
    let onSignOut: () -> Void

    @State private var confirming = false
    @FocusState private var focus: SettingsPlace?

    var body: some View {
        let active = admin.active
        // Under the account's tab, which names it: no heading of its own (the review's F23).
        PageScaffold(.tab, id: "page Settings") {
            SettingsScaffold {
                if let active {
                    Section {
                        // Focus moves to About before the row goes with the admin: a focused row removed would leave focus
                        // on nothing (Android's 93).
                        Button {
                            focus = .about
                            Task {
                                try? await Task.sleep(for: .milliseconds(100))
                                admin.back()
                            }
                        } label: {
                            Text(Words.adminSwitchBackTo(session.user.displayName))
                        }
                        .accessibilityIdentifier("switch back")
                    } footer: {
                        Text(Words.adminActiveAs(active.displayName))
                    }
                }
                Section {
                    LabeledContent(Words.signedInAs) {
                        VStack(alignment: .trailing, spacing: 4) {
                            Text(active?.displayName ?? session.user.displayName)
                            if active == nil, let email = session.user.email, !email.isEmpty, email != session.user.displayName {
                                Text(email).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("signed in as")
                    // The server by its name, its address under it without the scheme: "http://" is the repository's
                    // word, not the family's (the review's F37).
                    LabeledContent(Words.server) {
                        VStack(alignment: .trailing, spacing: 4) {
                            Text(session.settings.applicationTitle)
                            Text(Self.address(session.baseUrl)).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("server address")
                } header: {
                    Text(Words.account)
                        .accessibilityIdentifier("account header")
                }
                // Rows of words, as tvOS's Settings draws them: each symbol had its own width, so no two labels lined up
                // (the review's F13).
                Section {
                    NavigationLink(value: SettingsPlace.about) {
                        Text(Words.about)
                    }
                    .focused($focus, equals: .about)
                    .accessibilityIdentifier("about")
                    NavigationLink(value: SettingsPlace.admin) {
                        Text(Words.adminAccount)
                    }
                    .accessibilityIdentifier("admin account")
                }
                Section {
                    Button(Words.signOut, role: .destructive) { confirming = true }
                        .accessibilityIdentifier("sign out")
                }
            }
        }
        .alert(Words.signOutTitle, isPresented: $confirming) {
            Button(Words.cancel, role: .cancel) {}
                .accessibilityIdentifier("cancel sign out")
            Button(Words.signOut, role: .destructive, action: onSignOut)
                .accessibilityIdentifier("confirm sign out")
        } message: {
            Text(Words.signOutBody(method))
        }
    }

    /// A server's address as the family reads it: the host, its port and any path, without the scheme or a trailing
    /// slash ("http://192.0.2.10:5055" is "192.0.2.10:5055").
    static func address(_ baseUrl: String) -> String {
        guard let url = URL(string: baseUrl), url.scheme != nil, let host = url.host(percentEncoded: false) else { return baseUrl }
        let port = url.port.map { ":\($0)" } ?? ""
        let path = url.path(percentEncoded: false)
        return host + port + (path == "/" ? "" : path)
    }
}

/// A page Settings opens, full screen without the tab bar, as the title page is (DECISIONS 193).
struct SettingsPage: View {
    let place: SettingsPlace
    let session: Session
    let admin: AdminGateway
    /// Takes the page away (the admin page's Cancel, and Menu on an empty PIN pad).
    var onBack: () -> Void

    var body: some View {
        switch place {
        case .about: AboutView()
        case .privacy: PrivacyView()
        case .diagnostics: DiagnosticsView(log: Diagnostics.log)
        case .admin: AdminView(setup: AdminSetup(admin: admin, family: session.user, baseUrl: session.baseUrl), onBack: onBack)
        }
    }
}

/// About (Android's `AboutScreen`, `docs/attribution.md`) as tvOS's Settings draws its own About (`SettingsScaffold`):
/// Hither's mark on the left; on the right the version, the privacy text and Diagnostics as rows, that Hither is
/// unofficial as their footer, then the acknowledgements: TMDB's logo and notice word for word, and the credits. Focus
/// lands on Privacy, the first row that opens something. Diagnostics is here, not on Settings: it is for Tom, not the
/// family (the review's S41).
struct AboutView: View {
    var body: some View {
        PageScaffold(.pushed(Words.about), id: "page About") {
            SettingsScaffold(markId: "about wordmark") {
                Section {
                    LabeledContent(Words.versionLabel, value: Self.version)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("version")
                    NavigationLink(value: SettingsPlace.privacy) {
                        Text(Words.privacy)
                    }
                    .accessibilityIdentifier("privacy")
                    // For Tom, not the family (DECISIONS 107).
                    NavigationLink(value: SettingsPlace.diagnostics) {
                        Text(Words.diagnostics)
                    }
                    .accessibilityIdentifier("diagnostics")
                } footer: {
                    Text(Words.unofficial)
                        .accessibilityIdentifier("unofficial")
                }
                Section {
                    // TMDB's logo beside its notice, the app's own mark the page's most prominent (TMDB's terms).
                    HStack(alignment: .center, spacing: 32) {
                        Image("tmdb_logo")
                            .resizable()
                            .scaledToFit()
                            .frame(height: 22)
                            .accessibilityLabel(Words.tmdb)
                            .accessibilityIdentifier("tmdb logo")
                        Text(Words.tmdbNotice)
                            .font(.callout)
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("tmdb notice")
                    }
                } header: {
                    Text(Words.acknowledgements)
                } footer: {
                    Text(Words.credits)
                        .accessibilityIdentifier("credits")
                }
            }
        }
    }

    static var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
    }
}

/// The privacy text, `docs/privacy-tvos.md` as the build bundled it (Android's `PrivacyScreen`): the app shows the file
/// itself, so the text inside the app and the text in the repository cannot drift apart; its title is the page's. The
/// rest is one view that takes focus and moves through the text with the remote, as tvOS's Settings › Legal reads
/// (`ReadingView`; the review's F20: each paragraph and bullet was a stop of its own, about twenty presses of Down).
struct PrivacyView: View {
    @State private var blocks: [PrivacyBlock] = []

    /// The file's title ("Privacy"), the page's.
    private var title: String {
        for block in blocks { if case .title(let text) = block { return text } }
        return Words.privacy
    }

    var body: some View {
        PageScaffold(.pushed(title), id: "privacy text") {
            ReadingView(text: Self.reading(blocks), id: "privacy body")
                .frame(maxWidth: ReadingScreen.width, alignment: .leading)
                .padding(.bottom, Metrics.topMargin)
        }
        .task { blocks = PrivacyText.bundled() }
    }

    /// The blocks after the title as the text view's text: headings in Title 3, paragraphs, bullets with their lines
    /// hanging, bold where the file is.
    static func reading(_ blocks: [PrivacyBlock]) -> ReadingText {
        let runs: ([PrivacyRun]) -> [ReadingText.Run] = { $0.map { ReadingText.Run(text: $0.text, bold: $0.bold) } }
        return blocks.reduce(ReadingText()) { text, block in
            switch block {
            case .title: text
            case .heading(let words): text.heading(words)
            case .paragraph(let parts): text.paragraph(runs(parts))
            case .bullet(let parts): text.bullet(runs(parts))
            }
        }
    }
}

/// About › Diagnostics (Android's `DiagnosticsScreen`, DECISIONS 107) as a Settings list (`SettingsScaffold`): the
/// failures Hither recovered from since it started, newest first, each a row to read by the remote: what ("HTTP 500")
/// and when, the call ("GET /movie/123") under it. For Tom, not the family; never the server's address, a query or a
/// body. The list as it was when the page opened, so a failure while it is open does not move the rows under focus;
/// with none, a row saying so takes focus.
struct DiagnosticsView: View {
    let log: FailureLog
    @State private var failures: [Failure]?

    var body: some View {
        PageScaffold(.pushed(Words.diagnostics), id: "page Diagnostics") {
            SettingsScaffold {
                Section {
                    if let failures {
                        if failures.isEmpty {
                            Text(Words.diagnosticsNone)
                                .foregroundStyle(.secondary)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .focusable()
                                .accessibilityIdentifier("diagnostics none")
                        }
                        ForEach(Array(failures.enumerated()), id: \.offset) { index, failure in
                            row(failure)
                                .focusable()
                                .accessibilityElement(children: .combine)
                                .accessibilityIdentifier("failure \(index)")
                        }
                    }
                } header: {
                    // A sentence, not a label: kept in its own case (the header style shouts it in capitals).
                    Text(Words.diagnosticsAbout(DiagnosticsKt.DIAGNOSTICS_KEPT))
                        .textCase(nil)
                        .accessibilityIdentifier("diagnostics about")
                }
            }
        }
        .task { if failures == nil { failures = log.failures.value } }
    }

    /// What and when on one line, the call under it.
    private func row(_ failure: Failure) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(failure.what).fontWeight(.semibold)
                Spacer(minLength: 24)
                Text(Self.time(failure.atMillis)).font(.callout).foregroundStyle(.secondary)
            }
            Text("\(failure.method) \(failure.path)")
                .font(.callout)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// The day and the time, as Hither writes a moment (`Formats.dateTime`).
    private static func time(_ millis: Int64) -> String {
        Formats.dateTime(Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

/// The one failure log of the process (Android's `HitherApplication.failures`, DECISIONS 107): every Seerr client records
/// into it; in memory only, from the start of the process.
enum Diagnostics {
    static let log = FailureLog(now: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) }, capacity: DiagnosticsKt.DIAGNOSTICS_KEPT)
}
