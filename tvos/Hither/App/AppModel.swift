import Foundation
import HitherCore
import Observation

/// The sign-in form's state: what was typed, what is wrong with it, and how the last try went.
struct SignInForm {
    var server = ""
    var email = ""
    var password = ""
    var problems: Set<SignInProblem> = []
    var failure: SignInFailure?
    var submitting = false
    /// The server's title once a try got far enough to learn it: "Login with <title>", as Seerr's own form.
    var serverTitle: String?

    // M8 (Android's `Screen.SignIn`): what the typed server offers, and the pages in front of the form.
    /// What the typed server offers, once read; nil until then, and the form is the password form.
    var offer: ServerOffer?
    /// The typed server's offer is being read.
    var checking = false
    /// A page in front of the form: Jellyfin's or Emby's, or a code.
    var page: SignInPage?
    /// The control focus goes to when the form shows again (the one that opened a page), or after a session that
    /// ended, the way back in. The form takes it once.
    var returnTo: SignInControl?
    /// Jellyfin's or Emby's form's fields, apart from Seerr's own.
    var username = ""
    var mediaPassword = ""

    /// The way in through the media server the offer gives a button to, if any: Plex, or Jellyfin's or Emby's form.
    var mediaServerWay: MediaServerKind? {
        guard let methods = offer?.methods else { return nil }
        if methods.plex { return .plex }
        if methods.mediaServerPassword { return methods.mediaServer }
        return nil
    }

    /// Seerr's own email and password show until the server says they are off.
    var local: Bool { offer?.methods.local != false }
}

/// The sign-in controls a page opens from (M8), for focus to come back to.
enum SignInControl: Equatable { case plex, mediaServer, quickConnect }

/// A page in front of the sign-in form (M8).
enum SignInPage {
    case mediaServer(MediaServerPage)
    case code(CodePage)
}

/// Jellyfin or Emby ([kind]): username and password, and Quick Connect where the server has it.
struct MediaServerPage {
    var kind: MediaServerKind
    var quickConnect: Bool
    var usernameMissing = false
    var failure: SignInFailure?
    var submitting = false
    var returnTo: SignInControl?
}

enum CodeKind: Equatable { case plex, quickConnect }

enum CodeState: Equatable {
    /// Asking for a code.
    case starting
    /// The code is on screen; waiting for it to be entered or approved.
    case waiting
    /// It ran out first: a new code is offered.
    case expired
    /// No code could be had (plex.tv unreachable, Quick Connect off): Try Again is offered.
    case failed
}

/// A code to enter or approve elsewhere: Plex's at plex.tv/link, or Jellyfin's Quick Connect. [code] is nil until it
/// has come; [approveAt] is where Jellyfin is for people, when the server says.
struct CodePage {
    var kind: CodeKind
    var code: String?
    var state: CodeState = .starting
    var failure: SignInFailure?
    var approveAt: String?
}

/// Which screen the app is on, as Android's `SessionViewModel` decides it.
@MainActor
@Observable
final class AppModel {
    enum Screen: Equatable {
        /// The stored account is being tried.
        case starting
        case signIn
        case home
        /// A stored account whose server is not answering: Try Again, Sign Out.
        case unavailable
    }

    private(set) var screen: Screen = .starting
    private(set) var session: Session?
    var form = SignInForm()
    /// The address to name on the unavailable screen; and whether it is the CSRF lock-out rather than silence.
    private(set) var unavailableServer: String?
    private(set) var csrfStuck = false
    /// A title a link asked for (the Top Shelf's `hither://title/<type>/<id>`), kept until the home has opened it: a link
    /// that comes while signed out or starting opens once signed in.
    var link: TitleLink?

    let gateway: SessionGateway
    /// The TV's admin account (M5): the app's one, so it outlives a page; it belongs to the family's account and server,
    /// and goes with them (Android's `onAccountGone`, DECISIONS 92).
    let admin: AdminGateway

    /// Reading the typed server's offer, and the address it is for (M8).
    private var offerTask: Task<Void, Never>?
    private var offerFor: String?
    /// A code's wait: Cancel, Menu and Hither leaving the front cancel it (M8).
    private var codeTask: Task<Void, Never>?

    init(gateway: SessionGateway? = nil, admin: AdminGateway? = nil) {
        let gateway = gateway ?? SessionGateway()
        self.gateway = gateway
        self.admin = admin ?? AdminGateway(idleMs: Self.adminIdleMs)
        gateway.onLost = { [weak self] failure, server in
            guard let self else { return }
            // The account is gone (its password refused, or a session Hither cannot renew ended): nothing of it stays
            // (Android's `onAccountGone`), the admin with it.
            ReadyLook.forget()
            Task { await self.admin.remove() }
            self.session = nil
            self.form = SignInForm(failure: failure)
            self.screen = .signIn
            // A Plex or Quick Connect session ended: the form offers its server again, read at once, so signing in again
            // is a button away (M8).
            if failure == .sessionEnded, let server, !server.isEmpty { self.offerAgain(server) }
        }
    }

    /// Core's 10 idle minutes; a debug build's `HITHER_ADMIN_IDLE_MS` shortens them for a UI test of the idle clock.
    private static var adminIdleMs: Int64 {
        #if DEBUG
        if let ms = ProcessInfo.processInfo.environment["HITHER_ADMIN_IDLE_MS"].flatMap(Int64.init) { return ms }
        #endif
        return AdminGuardKt.ADMIN_IDLE_MS
    }

    func start() async {
        screen = .starting
        apply(await gateway.restore())
    }

    func tryAgain() async {
        await start()
    }

    private func apply(_ restored: Restored) {
        switch restored {
        case .noAccount:
            screen = .signIn
        case .signedIn(let session):
            self.session = session
            screen = .home
        case .serverUnavailable(let server):
            unavailableServer = server
            csrfStuck = false
            screen = .unavailable
        case .csrfNeedsHttps:
            unavailableServer = gateway.storedServer()
            csrfStuck = true
            screen = .unavailable
        case .rejected(let failure, let server):
            Task { await admin.remove() }
            form = SignInForm(failure: failure)
            screen = .signIn
            if let server { offerAgain(server) }
        }
    }

    /// Sign In: core's form check first (`checkSignIn`), then the server.
    func submit() async {
        guard !form.submitting else { return }
        let checked = SignInFormKt.checkSignIn(server: form.server, email: form.email, password: form.password)
        form.problems = (checked.second as? Set<SignInProblem>) ?? []
        form.failure = nil
        guard let request = checked.first else { return }
        form.submitting = true
        do {
            await signedIn(try await gateway.signIn(request))
        } catch let failed as SignInFailed {
            form.submitting = false
            form.failure = failed.failure
            form.serverTitle = failed.serverTitle ?? form.serverTitle
        } catch {
            form.submitting = false
            form.failure = .other
        }
    }

    /// Signed in, by any way: the form's text forgotten, home.
    private func signedIn(_ session: Session) async {
        offerTask?.cancel()
        codeTask?.cancel()
        // A new sign-in: an admin of an account before it is not this one's.
        if admin.kept() != nil { await admin.remove() }
        form = SignInForm()
        self.session = session
        screen = .home
    }

    // MARK: - M8: what the server offers, and the ways in through its media server (Android's `SessionViewModel`)

    /// The server field changed: what another address offered no longer applies, and its buttons go until the new one is
    /// read. A read still out for the old address is dropped too, even when the new text is no address at all.
    func serverChanged() {
        guard screen == .signIn else { return }
        let typed = ServerAddress.shared.normalise(typed: form.server)
        if form.checking, typed != offerFor {
            offerTask?.cancel()
            offerFor = nil
            form.checking = false
        }
        if let offer = form.offer, typed != offer.baseUrl { form.offer = nil }
    }

    /// The server field is done with (its keyboard's Next, or focus moving off it): read what the server offers, once
    /// per address. The password form stays as it is meanwhile; the offer adds the other ways in under it.
    func serverDone() {
        guard screen == .signIn, let baseUrl = ServerAddress.shared.normalise(typed: form.server) else { return }
        if form.offer?.baseUrl == baseUrl || (form.checking && offerFor == baseUrl) { return }
        offerFor = baseUrl
        offerTask?.cancel()
        form.checking = true
        offerTask = Task { [gateway] in
            let offer: ServerOffer
            do {
                offer = try await gateway.offer(baseUrl)
            } catch {
                guard !Task.isCancelled, offerFor == baseUrl, screen == .signIn else { return }
                form.checking = false
                form.offer = nil
                form.failure = (error as? SignInFailed)?.failure ?? .other
                return
            }
            guard !Task.isCancelled, offerFor == baseUrl, screen == .signIn else { return }
            // A failed read's own complaint goes; any other reason (a session that ended) stays to be read. After a
            // session that ended (Plex, Quick Connect), focus goes to the media server's button: the way back in.
            if form.failure == .sessionEnded {
                if offer.methods.plex { form.returnTo = .plex } else if offer.methods.mediaServerPassword { form.returnTo = .mediaServer }
            }
            form.checking = false
            form.offer = offer
            form.serverTitle = offer.title
            if form.failure == .unreachable { form.failure = nil }
        }
    }

    /// The form with [baseUrl] in the server field and its offer read, after a session that could not be renewed.
    private func offerAgain(_ baseUrl: String) {
        form.server = baseUrl
        serverDone()
    }

    /// Select on the Jellyfin or Emby button: its form.
    func openMediaServer() {
        guard screen == .signIn, form.page == nil, let methods = form.offer?.methods, methods.mediaServerPassword else { return }
        form.page = .mediaServer(MediaServerPage(kind: methods.mediaServer, quickConnect: methods.quickConnect))
        form.failure = nil
    }

    /// Select on Sign In on the Jellyfin or Emby form. A second press while the first is out does nothing. Seerr's own
    /// form asks for the username only: a Jellyfin account may have no password.
    func submitMediaServer() async {
        guard case .mediaServer(var page) = form.page, let offer = form.offer, !page.submitting else { return }
        let username = form.username.trimmingCharacters(in: .whitespacesAndNewlines)
        if username.isEmpty {
            page.usernameMissing = true
            page.failure = nil
            form.page = .mediaServer(page)
            return
        }
        page.usernameMissing = false
        page.failure = nil
        page.submitting = true
        form.page = .mediaServer(page)
        let request = SignInRequest(baseUrl: offer.baseUrl, email: username, password: form.mediaPassword)
        do {
            await signedIn(try await gateway.signInWithMediaServer(request))
        } catch {
            guard screen == .signIn, case .mediaServer(var open) = form.page else { return }
            open.submitting = false
            open.failure = (error as? SignInFailed)?.failure ?? .other
            form.page = .mediaServer(open)
        }
    }

    /// Select on the Plex button: a link code from plex.tv, and the wait for it to be entered.
    func openPlex() {
        guard screen == .signIn, form.page == nil, form.offer?.methods.plex == true else { return }
        form.page = .code(CodePage(kind: .plex))
        form.failure = nil
        startCode()
    }

    /// Select on Quick Connect on the Jellyfin form: a code from Seerr, and the wait for it to be approved.
    func openQuickConnect() {
        guard case .mediaServer(let page) = form.page, page.quickConnect, !page.submitting else { return }
        form.page = .code(CodePage(kind: .quickConnect, approveAt: form.offer?.approveAt))
        startCode()
    }

    /// Get a New Code, or Try Again, on a code's page.
    func newCode() {
        guard case .code(let page) = form.page, page.state == .expired || page.state == .failed else { return }
        startCode()
    }

    /// Cancel or Menu on a page: a code's wait is cancelled (nothing more is asked of plex.tv or Seerr) and the page
    /// closes to where it was opened, focus on the control that opened it. Menu on the Jellyfin form while it signs in
    /// waits for the answer.
    func closePage() {
        switch form.page {
        case .code(let page):
            codeTask?.cancel()
            codeTask = nil
            if page.kind == .quickConnect {
                form.page = .mediaServer(mediaServerPage(returnTo: .quickConnect))
            } else {
                form.page = nil
                form.returnTo = .plex
            }
        case .mediaServer(let page):
            if !page.submitting {
                form.page = nil
                form.returnTo = .mediaServer
            }
        case nil:
            break
        }
    }

    /// Hither has left the front (Home, another app): a code's wait stops with it, so plex.tv is asked only while its
    /// code is on screen (DECISIONS 235). The page closes as Cancel closes it; coming back is a new code.
    func leftFront() {
        guard screen == .signIn, case .code = form.page else { return }
        closePage()
    }

    private func mediaServerPage(returnTo: SignInControl? = nil, failure: SignInFailure? = nil) -> MediaServerPage {
        let methods = form.offer?.methods
        return MediaServerPage(kind: methods?.mediaServer ?? .jellyfin, quickConnect: methods?.quickConnect == true, failure: failure, returnTo: returnTo)
    }

    private func updateCode(_ change: (inout CodePage) -> Void) {
        guard screen == .signIn, case .code(var page) = form.page else { return }
        change(&page)
        form.page = .code(page)
    }

    private func startCode() {
        guard case .code(var page) = form.page, let baseUrl = form.offer?.baseUrl else { return }
        codeTask?.cancel()
        page.code = nil
        page.state = .starting
        page.failure = nil
        form.page = .code(page)
        let kind = page.kind
        codeTask = Task { [gateway] in
            do {
                let code = kind == .plex ? try await gateway.newPlexCode() : try await gateway.newQuickConnectCode(baseUrl)
                try Task.checkCancellation()
                updateCode { $0.code = code.code; $0.state = .waiting }
                let session = kind == .plex
                    ? try await gateway.signInWithPlex(baseUrl, code)
                    : try await gateway.signInWithQuickConnect(baseUrl, code)
                // Signed in and kept, even if Cancel came as the answer did: the account is stored.
                guard screen == .signIn else { return }
                await signedIn(session)
            } catch {
                guard !Task.isCancelled, let failure = (error as? SignInFailed)?.failure else { return }
                switch failure {
                case .codeExpired:
                    updateCode { $0.state = .expired; $0.failure = failure }
                case .plexUnreachable, .quickConnectUnavailable, .unreachable:
                    updateCode { $0.state = .failed; $0.failure = failure }
                default:
                    // Seerr's answer about the account (not imported, switched off, ...): back where the page was opened,
                    // with the reason, as a refused password is.
                    guard screen == .signIn, case .code = form.page else { return }
                    if kind == .quickConnect {
                        form.page = .mediaServer(mediaServerPage(returnTo: .quickConnect, failure: failure))
                    } else {
                        form.page = nil
                        form.failure = failure
                        form.returnTo = .plex
                    }
                }
            }
        }
    }

    /// A link Hither was opened with: only core's exact `hither://title/<movie|tv>/<id>` names a title (`parseTitleLink`);
    /// anything else is ignored.
    func open(_ url: URL) {
        if let title = HomeRowsKt.parseTitleLink(uri: url.absoluteString) { link = title }
    }

    func signOut() async {
        offerTask?.cancel()
        codeTask?.cancel()
        // The admin goes with the family's account: its session signed out on the server, its item deleted.
        await admin.remove()
        await gateway.signOut()
        // Nothing of the last account's stays (Android's `ReadyLook.forget`): the requests' snapshot, the count, the
        // titles' names and the Top Shelf.
        ReadyLook.forget()
        session = nil
        form = SignInForm()
        screen = .signIn
    }
}
