import Foundation
import HitherCore
import XCTest
@testable import Hither

/// Where a server's pictures come from (0.7.1, DECISIONS 382), against the run's fake-seerr (`scripts/tvos-app`): with
/// Image Caching on, through the server's `/imageproxy`; with it off (Seerr's default, where Seerr 3.5.2's proxy refuses
/// every picture), from TMDB's image server, here the fake's stand-in, which is sent nothing of the session. Each picture
/// is loaded by the app's own `ImageLoader`, as a card draws it.
@MainActor
final class PicturesTests: XCTestCase {
    private var fake: String {
        get throws {
            try XCTUnwrap(ProcessInfo.processInfo.environment["HITHER_FAKE_SEERR"].flatMap { $0.isEmpty ? nil : $0 },
                          "HITHER_FAKE_SEERR is not set: run the tests through scripts/tvos-app")
        }
    }

    /// The fake's TMDB stand-in, never the real image.tmdb.org.
    private var tmdb: String { get throws { try fake + "/tmdb" } }

    /// The recorded film (core's `movie` fixture) and its poster.
    private let film: Int32 = 1423191
    private let posterFile = "/gAgWJhUSQWCgtKR8170nMkYlBda.jpg"

    override func setUp() async throws {
        try await post("/fake/reset")
        try? AccountStore().clear()
    }

    override func tearDown() async throws {
        await SessionGateway().signOut()
        try await post("/fake/reset")
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
        let tmdb = try tmdb
        return SessionGateway(tmdbImages: { tmdb })
    }

    private func signIn(_ gateway: SessionGateway) async throws -> Session {
        try await gateway.signIn(SignInRequest(baseUrl: try fake, email: "family@example.invalid", password: "right-password"))
    }

    /// The recorded film's poster as its title page has it.
    private func poster(_ gateway: SessionGateway, _ session: Session) async throws -> String {
        let film = film
        let page = try await gateway.call { client in
            MediaPageKt.moviePage(details: try await client.movie(tmdbId: film), images: client.images, settings: session.settings, user: session.user)
        }
        return try XCTUnwrap(page.poster, "the recorded film has a poster")
    }

    /// What the TMDB stand-in was sent: each request's path and headers.
    private func seenAtTmdb() async throws -> [(path: String, headers: [String: String])] {
        let seen = try await post("/fake/tmdb")["seen"] as? [[String: Any]] ?? []
        return seen.map { ($0["path"] as? String ?? "", $0["headers"] as? [String: String] ?? [:]) }
    }

    /// A plain GET's status, outside the app's loader and its cache.
    private func status(_ url: String) async throws -> Int {
        let (_, response) = try await URLSession(configuration: .ephemeral).data(from: URL(string: url)!)
        return (response as? HTTPURLResponse)?.statusCode ?? 0
    }

    func testWithCachingOffPicturesComeFromTmdbAndCarryNothingOfTheSession() async throws {
        try await post("/fake/server?caching=0")
        let gateway = try gateway()
        let session = try await signIn(gateway)
        XCTAssertFalse(session.images.fromServer, "the server keeps no copies")
        XCTAssertEqual(session.images.base, try tmdb)

        let poster = try await poster(gateway, session)
        XCTAssertEqual(poster, try tmdb + "/t/p/w300_and_h450_face" + posterFile)
        let image = await ImageLoader.shared.image(URL(string: poster)!, filling: CGSize(width: 150, height: 225))
        XCTAssertNotNil(image, "the poster loads from the TMDB stand-in")

        // Where 0.7.0 went: Seerr 3.5.2's proxy refuses the same picture.
        let refused = try await status(try fake + "/imageproxy/tmdb/t/p/w300_and_h450_face" + posterFile)
        XCTAssertEqual(refused, 404)

        let seen = try await seenAtTmdb()
        XCTAssertTrue(seen.contains { $0.path == "/tmdb/t/p/w300_and_h450_face" + posterFile }, "the stand-in served it: \(seen.map(\.path))")
        for request in seen {
            let names = Set(request.headers.keys.map { $0.lowercased() })
            XCTAssertFalse(names.contains("cookie"), "a cookie went to TMDB: \(request.path)")
            XCTAssertFalse(names.contains("x-xsrf-token") || names.contains("x-api-key") || names.contains("authorization"),
                           "a header of the server's went to TMDB: \(request.headers.keys.sorted())")
        }
    }

    func testWithCachingOnPicturesComeThroughTheServerAndNothingGoesToTmdb() async throws {
        let gateway = try gateway()
        let session = try await signIn(gateway)
        XCTAssertTrue(session.images.fromServer, "the recorded server keeps copies")

        let poster = try await poster(gateway, session)
        XCTAssertEqual(poster, try fake + "/imageproxy/tmdb/t/p/w300_and_h450_face" + posterFile)
        let image = await ImageLoader.shared.image(URL(string: poster)!, filling: CGSize(width: 150, height: 226))
        XCTAssertNotNil(image, "the poster loads through the server's proxy")
        let seen = try await seenAtTmdb()
        XCTAssertTrue(seen.isEmpty, "nothing went to TMDB: \(seen.map(\.path))")
    }

    /// An owner who turns caching off is followed by the next start: the switch is read again as the session is restored.
    func testTheNextStartFollowsTheServersSwitch() async throws {
        let first = try gateway()
        let signedIn = try await signIn(first)
        XCTAssertTrue(signedIn.images.fromServer)

        try await post("/fake/server?caching=0")
        let second = try gateway()
        guard case .signedIn(let restored) = await second.restore() else { return XCTFail("the kept session restores") }
        XCTAssertFalse(restored.images.fromServer)
        XCTAssertEqual(restored.images.base, try tmdb)

        try await post("/fake/server?caching=1")
        let third = try gateway()
        guard case .signedIn(let again) = await third.restore() else { return XCTFail("the kept session restores") }
        XCTAssertTrue(again.images.fromServer)
    }
}
