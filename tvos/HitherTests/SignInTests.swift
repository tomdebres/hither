import Foundation
import HitherCore
import XCTest
@testable import Hither

/// plex.tv's client identifier on tvOS (M8): made once per install, kept across sign-out, in `UserDefaults`.
final class PlexIdentityTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suite = "hither-tests-plex-identity"

    override func setUp() {
        UserDefaults().removePersistentDomain(forName: suite)
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suite)
    }

    /// The first ask makes a random UUID; every later ask, by this store or a new one on the same defaults (a relaunch),
    /// gives the same.
    func testMadeOnceAndKept() throws {
        XCTAssertNil(defaults.string(forKey: PlexIdentity.key), "nothing made before a Plex sign-in asks")
        let first = PlexIdentity(defaults: defaults).clientIdentifier()
        XCTAssertNotNil(UUID(uuidString: first), "a UUID: \(first)")
        XCTAssertEqual(PlexIdentity(defaults: defaults).clientIdentifier(), first, "kept for the install")
        XCTAssertEqual(defaults.string(forKey: PlexIdentity.key), first)
    }

    /// Another install (other defaults) gets its own.
    func testEachInstallItsOwn() {
        let other = "hither-tests-plex-identity-other"
        UserDefaults().removePersistentDomain(forName: other)
        defer { UserDefaults().removePersistentDomain(forName: other) }
        XCTAssertNotEqual(PlexIdentity(defaults: defaults).clientIdentifier(), PlexIdentity(defaults: UserDefaults(suiteName: other)!).clientIdentifier())
    }

    /// Signing out keeps it (DECISIONS 235): the gateway's sign-out forgets the account and its session, not the id.
    @MainActor
    func testSignOutKeepsIt() async {
        let identity = PlexIdentity(defaults: defaults)
        let before = identity.clientIdentifier()
        await SessionGateway(identity: identity).signOut()
        XCTAssertEqual(identity.clientIdentifier(), before)
        XCTAssertEqual(defaults.string(forKey: PlexIdentity.key), before)
    }
}

/// Where plex.tv is: plex.tv itself unless a debug build's environment names the fake (release: always plex.tv, which
/// `scripts/tvos-testflight` checks in the built binary).
final class PlexAddressTests: XCTestCase {
    func testPlexTvItselfByDefault() {
        XCTAssertEqual(PlexAddress.base([:]), "https://plex.tv")
        XCTAssertEqual(PlexAddress.base(["HITHER_PLEX_TV": ""]), "https://plex.tv", "an empty switch is no switch")
        XCTAssertEqual(PlexAddress.base(["HITHER_PLEX_TV": "  "]), "https://plex.tv")
    }

    func testTheDebugSwitchNamesTheFake() {
        XCTAssertEqual(PlexAddress.base(["HITHER_PLEX_TV": "http://localhost:5056/plextv"]), "http://localhost:5056/plextv")
    }

    /// The test run's own switch (scripts/tvos-app): this process asks the fake, never the real plex.tv.
    func testTheRunAsksTheFake() throws {
        let named = try XCTUnwrap(ProcessInfo.processInfo.environment["HITHER_PLEX_TV"], "scripts/tvos-app names the fake plex.tv")
        XCTAssertTrue(named.hasPrefix("http://localhost:"), named)
        XCTAssertEqual(PlexAddress.base, named)
    }
}

/// The sign-in's words (M8): each failure in its own plain words, and the same words as Android's.
final class SignInWordsTests: XCTestCase {
    /// Every failure but `.other` has words of its own, never the generic sentence (M8 part 1 mapped the new ones to it).
    func testEveryFailureInItsOwnWords() {
        let cases: [SignInFailure] = [
            .wrongCredentials, .localLoginDisabled, .unreachable, .csrfNeedsHttps, .notImported, .methodOff,
            .mediaServerUnreachable, .codeExpired, .plexUnreachable, .quickConnectUnavailable, .sessionEnded,
        ]
        for server in [MediaServerKind.plex, .jellyfin, .emby] {
            for failure in cases {
                XCTAssertNotEqual(Words.signInFailure(failure, server: server), Words.errorSignIn, "\(failure) on \(server)")
            }
        }
        XCTAssertEqual(Words.signInFailure(.other), Words.errorSignIn)
    }

    /// The media server is named where it matters; Plex has its own sentences for a refused account and a code run out.
    func testTheServerNamed() {
        XCTAssertEqual(Words.signInFailure(.notImported, server: .jellyfin),
                       "Seerr does not know this Jellyfin account yet. Ask whoever runs Seerr to import it as a user, then sign in again.")
        XCTAssertEqual(Words.signInFailure(.notImported, server: .emby),
                       "Seerr does not know this Emby account yet. Ask whoever runs Seerr to import it as a user, then sign in again.")
        XCTAssertEqual(Words.signInFailure(.notImported, server: .plex),
                       "Seerr refused this Plex account: it is not one of Seerr's users, or it has no access to the server's Plex. Ask whoever runs Seerr.")
        XCTAssertEqual(Words.signInFailure(.methodOff, server: .emby), "Signing in with Emby is switched off on this server.")
        XCTAssertEqual(Words.signInFailure(.mediaServerUnreachable, server: .jellyfin), "Seerr could not reach its Jellyfin server. Try again later.")
        XCTAssertEqual(Words.signInFailure(.methodOff), "Signing in with media server is switched off on this server.", "no server known")
        XCTAssertEqual(Words.signInFailure(.codeExpired, server: .plex), "This code expired before it was entered at plex.tv/link.")
        XCTAssertEqual(Words.signInFailure(.codeExpired, server: .jellyfin), "This code expired before it was approved in Jellyfin.")
        XCTAssertEqual(Words.signInFailure(.sessionEnded), "Your session on this server has ended. Sign in again.")
        // On Jellyfin's form a wrong password is the username's, as Seerr's JellyfinLogin says it.
        XCTAssertEqual(Words.mediaServerFailure(.wrongCredentials, server: .jellyfin), "The server did not accept that username and password.")
        XCTAssertEqual(Words.signInFailure(.wrongCredentials), "The email address or password is incorrect.")
        XCTAssertEqual(Words.codeDescribed("4F7Q"), "Code 4 F 7 Q")
    }

    /// The M8 words are Android's (`app/src/main/res/values/strings.xml`, its M8 block), so the two apps say the same.
    func testTheSameWordsAsAndroid() throws {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("app/src/main/res/values/strings.xml")
        let xml = try String(contentsOf: file, encoding: .utf8)
        let pattern = try NSRegularExpression(pattern: #"<string name="([a-z_0-9]+)">(.*?)</string>"#)
        var android: [String: String] = [:]
        for match in pattern.matches(in: xml, range: NSRange(xml.startIndex..., in: xml)) {
            let name = String(xml[Range(match.range(at: 1), in: xml)!])
            android[name] = String(xml[Range(match.range(at: 2), in: xml)!])
                .replacingOccurrences(of: "\\'", with: "'").replacingOccurrences(of: "&amp;", with: "&")
        }
        func one(_ name: String, _ argument: String? = nil) -> String? {
            guard let text = android[name] else { return nil }
            return argument.map { text.replacingOccurrences(of: "%1$s", with: $0) } ?? text
        }
        let pairs: [(String, String?, String)] = [
            ("sign_in_or", nil, Words.signInOr),
            ("sign_in_checking", nil, Words.signInChecking),
            ("field_username", nil, Words.fieldUsername),
            ("error_media_credentials", nil, Words.errorMediaCredentials),
            ("quick_connect", nil, Words.quickConnect),
            ("error_not_imported", "Emby", Words.errorNotImported("Emby")),
            ("error_not_imported_plex", nil, Words.errorNotImportedPlex),
            ("error_method_off", "Plex", Words.errorMethodOff("Plex")),
            ("error_media_server_unreachable", "Jellyfin", Words.errorMediaServerUnreachable("Jellyfin")),
            ("error_session_ended", nil, Words.errorSessionEnded),
            ("plex_heading", nil, Words.plexHeading),
            ("plex_go_to", nil, Words.plexGoTo),
            ("plex_link", nil, Words.plexLink),
            ("plex_enter", nil, Words.plexEnter),
            ("plex_waiting", nil, Words.plexWaiting),
            ("plex_expired", nil, Words.plexExpired),
            ("plex_unreachable", nil, Words.plexUnreachable),
            ("quick_connect_heading", nil, Words.quickConnectHeading),
            ("quick_connect_enter", nil, Words.quickConnectEnter),
            ("quick_connect_where", nil, Words.quickConnectWhere),
            ("quick_connect_where_at", "jellyfin.example.invalid", Words.quickConnectWhere(at: "jellyfin.example.invalid")),
            ("quick_connect_waiting", nil, Words.quickConnectWaiting),
            ("quick_connect_expired", nil, Words.quickConnectExpired),
            ("quick_connect_unavailable", nil, Words.quickConnectUnavailable),
            ("code_getting", nil, Words.codeGetting),
            ("code_expired", nil, Words.codeExpired),
            ("code_new", nil, Words.codeNew),
            ("code_try_again", nil, Words.tryAgain),
            ("code_cancel", nil, Words.codeCancel),
            ("code_described", "4 F 7 Q", Words.codeDescribed("4F7Q")),
            // Back in parity since H1 (DECISIONS 288): Android took the words tvOS says in its own voice.
            ("sign_in_heading_server", "Seerr", Words.signInHeading(server: "Seerr")),
            ("sign_in_with_media_server", "Jellyfin", Words.signInWithMediaServer("Jellyfin")),
            ("error_server", nil, Words.errorServer),
            ("error_email", nil, Words.errorEmail),
            ("error_password", nil, Words.errorPassword),
            ("error_username", nil, Words.errorUsername),
            // Hither's own words where Seerr's sentences were (H1), the same on both apps.
            ("sign_in_heading", nil, Words.signInHeading),
            ("error_sign_in", nil, Words.errorSignIn),
            ("search_placeholder", nil, Words.searchPlaceholder),
            ("approved_automatically", nil, Words.approvedAutomatically),
            ("request_error", nil, Words.requestError),
            ("modify_failed", nil, Words.modifyFailed),
            ("issue_failed", nil, Words.issueFailed),
            ("unofficial", nil, Words.unofficial),
            ("no_results", nil, Words.noResults),
            ("clear_filters", nil, Words.clearFilters),
            ("ready_to_watch", nil, Words.readyToWatch),
            ("ready_to_watch_4k", nil, Words.readyToWatch4k),
            ("sign_out_body", nil, Words.signOutBody(.local)),
            ("sign_out_body_media_server", nil, Words.signOutBody(.mediaServer)),
            ("sign_out_body_plex", nil, Words.signOutBody(.plex)),
            ("sign_out_body_quick_connect", nil, Words.signOutBody(.quickConnect)),
        ]
        // Dropped from parity on purpose: none since H1 (polish part 7 dropped six until Android took tvOS's words). A
        // word added here must still be Android's and must differ, so the day Android catches up this goes red.
        let onPurpose: [(String, String?, String)] = []
        XCTAssertGreaterThan(android.count, 100, "Android's strings at \(file.path)")
        for (name, argument, words) in pairs {
            XCTAssertEqual(words, one(name, argument), name)
        }
        for (name, argument, words) in onPurpose {
            XCTAssertNotNil(one(name, argument), "\(name) is still Android's")
            XCTAssertNotEqual(words, one(name, argument), "\(name): Android says it as tvOS does now; move it back into the pairs")
        }
    }
}

/// The kept account knows how it signed in (M8); a record from before M8 is Seerr's own password.
final class AccountMethodTests: XCTestCase {
    func testARecordFromBeforeIsALocalAccount() throws {
        let old = #"{"baseUrl":"http://localhost:1","email":"family@example.invalid","password":"right-password"}"#
        let account = try JSONDecoder().decode(Account.self, from: Data(old.utf8))
        XCTAssertEqual(account.method, .local)
        XCTAssertEqual(account.email, "family@example.invalid")
    }

    func testTheMethodKept() throws {
        let account = Account(baseUrl: "http://localhost:1", email: "robin", password: "robin-password", method: .mediaServer)
        let read = try JSONDecoder().decode(Account.self, from: JSONEncoder().encode(account))
        XCTAssertEqual(read, account)
    }

    /// Only an account with a password kept signs in again by itself (DECISIONS 235).
    func testWhichRenew() {
        XCTAssertTrue(AccountMethod.local.renews)
        XCTAssertTrue(AccountMethod.mediaServer.renews)
        XCTAssertFalse(AccountMethod.plex.renews)
        XCTAssertFalse(AccountMethod.quickConnect.renews)
    }
}

/// The symbol over a code page's trouble: the network's only for what does not answer (Tom, M8 part 2: Quick Connect
/// switched off in Jellyfin showed the wifi symbol).
final class CodeTroubleTests: XCTestCase {
    func testTheNetworksSymbolOnlyForWhatDoesNotAnswer() {
        XCTAssertEqual(CodeTrouble.symbol(expired: true, failure: .codeExpired), "clock.badge.exclamationmark")
        for failure: SignInFailure in [.unreachable, .mediaServerUnreachable, .plexUnreachable] {
            XCTAssertEqual(CodeTrouble.symbol(expired: false, failure: failure), "wifi.exclamationmark", "\(failure)")
        }
        // Nil: the state's own warning.
        for failure: SignInFailure? in [.quickConnectUnavailable, .methodOff, .notImported, .other, nil] {
            XCTAssertNil(CodeTrouble.symbol(expired: false, failure: failure), "\(String(describing: failure))")
        }
    }
}

/// The gateway's M8 ways in against the run's fake-seerr and its fake plex.tv (`scripts/tvos-app`): what a server
/// offers, Jellyfin's password kept and renewed through `/auth/jellyfin`, and a Plex or Quick Connect session that ends.
@MainActor
final class MediaSignInGatewayTests: XCTestCase {
    private var fake: String {
        get throws {
            try XCTUnwrap(ProcessInfo.processInfo.environment["HITHER_FAKE_SEERR"].flatMap { $0.isEmpty ? nil : $0 },
                          "HITHER_FAKE_SEERR is not set: run the tests through scripts/tvos-app")
        }
    }

    private let suite = "hither-tests-gateway-identity"
    private var lost: [(SignInFailure, String?)] = []

    override func setUp() async throws {
        try await post("/fake/reset")
        UserDefaults().removePersistentDomain(forName: suite)
        try? AccountStore().clear()
        lost = []
    }

    override func tearDown() async throws {
        await SessionGateway().signOut()
        UserDefaults().removePersistentDomain(forName: suite)
    }

    @discardableResult
    private func post(_ path: String) async throws -> [String: Any] {
        var request = URLRequest(url: URL(string: try fake + path)!)
        request.httpMethod = "POST"
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200, path)
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }

    private func gateway() throws -> SessionGateway {
        let plex = try fake + "/plextv"
        let gateway = SessionGateway(identity: PlexIdentity(defaults: UserDefaults(suiteName: suite)!), plexAddress: { plex })
        gateway.onLost = { [weak self] failure, server in self?.lost.append((failure, server)) }
        return gateway
    }

    /// What a server offers, as core reads it: Quick Connect for Jellyfin on Seerr 3.4.0+, Emby never, the server's own
    /// password where it is on; and where Jellyfin is for people.
    func testWhatEachServerOffers() async throws {
        let gateway = try gateway()
        var offer = try await gateway.offer(try fake)
        XCTAssertTrue(offer.methods.local)
        XCTAssertTrue(offer.methods.plex, "the recorded server: Plex")
        XCTAssertFalse(offer.methods.mediaServerPassword)
        XCTAssertEqual(offer.title, "Seerr")
        try await post("/fake/signin?type=jellyfin&local=0&external=https://jellyfin.example.invalid/")
        offer = try await gateway.offer(try fake)
        XCTAssertFalse(offer.methods.local)
        XCTAssertTrue(offer.methods.mediaServerPassword)
        XCTAssertTrue(offer.methods.quickConnect, "Jellyfin on 3.5.2")
        XCTAssertEqual(offer.methods.mediaServer, .jellyfin)
        XCTAssertEqual(offer.approveAt, "jellyfin.example.invalid")
        try await post("/fake/signin?version=3.3.0")
        offer = try await gateway.offer(try fake)
        XCTAssertFalse(offer.methods.quickConnect, "no Quick Connect before 3.4.0")
        try await post("/fake/signin?type=emby&version=3.5.2")
        offer = try await gateway.offer(try fake)
        XCTAssertFalse(offer.methods.quickConnect, "Emby has none")
        XCTAssertEqual(offer.methods.mediaServer, .emby)
        do {
            _ = try await gateway.offer("http://localhost:1")
            XCTFail("nothing answers on port 1")
        } catch let failed as SignInFailed {
            XCTAssertEqual(failed.failure, .unreachable)
        }
    }

    /// A Jellyfin account is kept with its password, sealed in the Keychain as Seerr's own is, and signed in again
    /// through `/auth/jellyfin` when Seerr ends the session (DECISIONS 236).
    func testJellyfinKeptAndRenewedThroughJellyfin() async throws {
        try await post("/fake/signin?type=jellyfin")
        let gateway = try gateway()
        let session = try await gateway.signInWithMediaServer(SignInRequest(baseUrl: try fake, email: "robin", password: "robin-password"))
        XCTAssertEqual(session.user.displayName, "Robin")
        let kept = try XCTUnwrap(try AccountStore().load())
        XCTAssertEqual(kept.method, .mediaServer)
        XCTAssertEqual(kept.email, "robin")
        try await post("/fake/expire")
        let user = try await gateway.call { try await $0.me() }
        XCTAssertEqual(user.displayName, "Robin", "renewed as the same account")
        XCTAssertTrue(lost.isEmpty, "nothing lost")
        // And on a launch.
        try await post("/fake/expire")
        guard case .signedIn(let restored) = await gateway.restore() else { return XCTFail("signed in again on a launch") }
        XCTAssertEqual(restored.user.displayName, "Robin")
    }

    /// A Jellyfin account whose way in Seerr has since switched off is forgotten on renewal, with Seerr's reason
    /// (DECISIONS 237): Seerr's own word about the account, as a refused password is.
    func testWayInSwitchedOffForgetsTheAccount() async throws {
        try await post("/fake/signin?type=jellyfin&newlogin=0")
        let gateway = try gateway()
        _ = try await gateway.signInWithMediaServer(SignInRequest(baseUrl: try fake, email: "robin", password: "robin-password"))
        try await post("/fake/expire")
        try await post("/fake/signin?mediaserver=0")
        do {
            _ = try await gateway.call { try await $0.me() }
            XCTFail("the session cannot be renewed")
        } catch {}
        XCTAssertEqual(lost.map { $0.0 }, [.methodOff])
        XCTAssertNil(lost.first?.1, "no server offered again: the account was refused")
        XCTAssertNil(try AccountStore().load(), "forgotten")
    }

    /// A Plex account keeps nothing to sign in with: when Seerr ends its session the account goes, and the form is to
    /// offer its server again (DECISIONS 235). The token went to Seerr once and is in no record.
    func testPlexSessionEndsTheAccount() async throws {
        let gateway = try gateway()
        let base = try fake
        let code = try await gateway.newPlexCode()
        XCTAssertEqual(code.code.count, 4, "plex.tv/link's short code")
        async let signedIn = gateway.signInWithPlex(base, code)
        try await Task.sleep(for: .seconds(1))
        try await post("/fake/plex/link?code=\(code.code)&account=member")
        let session = try await signedIn
        XCTAssertEqual(session.user.displayName, "Plex Member")
        let kept = try XCTUnwrap(try AccountStore().load())
        XCTAssertEqual(kept.method, .plex)
        XCTAssertEqual(kept.email, "")
        XCTAssertEqual(kept.password, "", "no token kept")
        try await post("/fake/expire")
        do {
            _ = try await gateway.call { try await $0.me() }
            XCTFail("a Plex session is not renewed")
        } catch {}
        XCTAssertEqual(lost.map { $0.0 }, [.sessionEnded])
        XCTAssertEqual(lost.first?.1, try fake, "the server offered again")
        XCTAssertNil(try AccountStore().load())
    }

    /// The same at a launch: the account is gone, and the form gets the server back.
    func testPlexSessionEndedAtLaunch() async throws {
        let gateway = try gateway()
        let base = try fake
        let code = try await gateway.newPlexCode()
        async let signedIn = gateway.signInWithPlex(base, code)
        try await Task.sleep(for: .seconds(1))
        try await post("/fake/plex/link?code=\(code.code)&account=member")
        _ = try await signedIn
        try await post("/fake/expire")
        guard case .rejected(let failure, let server) = await gateway.restore() else { return XCTFail("rejected at launch") }
        XCTAssertEqual(failure, .sessionEnded)
        XCTAssertEqual(server, try fake)
    }

    /// plex.tv is sent the product name and this install's client identifier and nothing else, the same id each time;
    /// and cancelling the wait ends the looks.
    func testWhatPlexTvIsSentAndCancel() async throws {
        let gateway = try gateway()
        let code = try await gateway.newPlexCode()
        let wait = Task { try await gateway.signInWithPlex(try fake, code) }
        try await Task.sleep(for: .seconds(5))
        wait.cancel()
        // Not awaited: a wait that ignored Cancel would hold the test to the code's 15 minutes, then pass.
        try await Task.sleep(for: .seconds(1))
        let seen = try await post("/fake/plex")["seen"] as? [[String: Any]] ?? []
        XCTAssertGreaterThanOrEqual(seen.count, 2, "the code and a look at least: \(seen)")
        let ids = Set(seen.compactMap { ($0["headers"] as? [String: String])?.first { $0.key.lowercased() == "x-plex-client-identifier" }?.value })
        XCTAssertEqual(ids, [PlexIdentity(defaults: UserDefaults(suiteName: suite)!).clientIdentifier()], "one id, this install's")
        for request in seen {
            let headers = request["headers"] as? [String: String] ?? [:]
            XCTAssertEqual(Set(headers.keys.map { $0.lowercased() }), ["x-plex-product", "x-plex-client-identifier"], "only these, no cookie: \(request)")
            XCTAssertEqual(headers.first { $0.key.lowercased() == "x-plex-product" }?.value, "Hither")
        }
        try await Task.sleep(for: .seconds(5))
        let after = try await post("/fake/plex")["seen"] as? [[String: Any]] ?? []
        XCTAssertEqual(after.count, seen.count, "no look after Cancel")
    }

    /// Quick Connect: the code from Seerr, approved in Jellyfin, signs in; the account keeps nothing to sign in with.
    func testQuickConnectSignsInAndKeepsNothing() async throws {
        try await post("/fake/signin?type=jellyfin")
        let gateway = try gateway()
        let code = try await gateway.newQuickConnectCode(try fake)
        let base = try fake
        XCTAssertEqual(code.code.count, 6)
        async let signedIn = gateway.signInWithQuickConnect(base, code)
        try await Task.sleep(for: .seconds(1))
        try await post("/fake/quickconnect/approve?code=\(code.code)&user=robin")
        let session = try await signedIn
        XCTAssertEqual(session.user.displayName, "Robin")
        let kept = try XCTUnwrap(try AccountStore().load())
        XCTAssertEqual(kept.method, .quickConnect)
        XCTAssertEqual(kept.password, "")
        try await post("/fake/expire")
        guard case .rejected(.sessionEnded, let server) = await gateway.restore() else { return XCTFail("its session's end is the account's") }
        XCTAssertEqual(server, try fake)
    }
}
