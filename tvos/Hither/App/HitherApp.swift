import Security
import SwiftUI

@main
struct HitherApp: App {
    @State private var model = AppModel()

    init() {
        #if DEBUG
        // The UI tests start signed out: the account and every session cookie forgotten. Debug builds only.
        if CommandLine.arguments.contains("-hither-reset") {
            for service in [AccountStore.service, "com.tomdebres.heerr.session", AdminStore.service] {
                SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
            }
            // And what a sign-out forgets: the look's snapshot, the kept names and the Top Shelf's file.
            ReadyLook.forget()
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
                // Dark, as Seerr is (DECISIONS 190). Seerr's indigo is a tint only where tvOS draws one legibly (189):
                // given to the whole app, it fills every unfocused system button and hides its words.
                .preferredColorScheme(.dark)
                .task { await model.start() }
                // A Top Shelf item's link (DECISIONS 199).
                .onOpenURL { model.open($0) }
        }
        // Android's 30-minute ReadyWorker, as tvOS allows it (DECISIONS 199).
        .backgroundTask(.appRefresh(BackgroundLook.id)) { await BackgroundLook.run() }
    }
}

/// The screen the model is on: starting, the sign-in form, the signed-in home, or "the server is not answering".
struct RootView: View {
    let model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            Tokens.background.ignoresSafeArea()
            switch model.screen {
            case .starting:
                StartingView()
            case .signIn:
                SignInView(model: model)
            case .home:
                if let session = model.session {
                    HomeView(
                        session: session,
                        gateway: model.gateway,
                        admin: model.admin,
                        link: model.link,
                        onLinkTaken: { model.link = nil },
                        onSignOut: { Task { await model.signOut() } }
                    )
                        // A new session is a new home: nothing of the last account's pages is kept.
                        .id(session.user.id)
                }
            case .unavailable:
                UnavailableView(model: model)
            }
        }
        // Every press on the remote puts the admin's idle clock back to zero (Android's `onUserInteraction`, DECISIONS 93).
        .background(PressWatcher { model.admin.key() })
        // Hither leaving the front ends a code's wait (M8): plex.tv is asked only while its code is on screen.
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { model.leftFront() }
        }
    }
}

/// The stacked logo while the stored account is tried; a spinner if it takes longer than half a second (Android's
/// `StartingScreen`).
struct StartingView: View {
    @State private var slow = false

    var body: some View {
        PageScaffold(.screen, id: "starting") {
            VStack(spacing: 48) {
                Image("LogoStacked")
                    .resizable()
                    .scaledToFit()
                    .frame(height: 256)
                    .accessibilityLabel(Words.appName)
                ProgressView().opacity(slow ? 1 : 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .task {
            try? await Task.sleep(for: .milliseconds(500))
            slow = true
        }
    }
}

/// A stored account whose server is not answering: the reason, Try Again (the screen's one prominent button) and Sign
/// Out (the account is kept), as the system says a thing cannot be shown (`StateView`).
struct UnavailableView: View {
    let model: AppModel

    var body: some View {
        PageScaffold(.screen, id: "unavailable page") {
            StateView(.failed, model.csrfStuck ? Words.errorCsrfLater : (model.unavailableServer.map(Words.errorUnreachableNamed) ?? Words.errorUnreachableKept), symbol: "wifi.exclamationmark", id: "unavailable") {
                HStack(spacing: 32) {
                    Button(Words.tryAgain) { Task { await model.tryAgain() } }
                        .buttonStyle(.borderedProminent)
                        .tint(Tokens.accent)
                        .accessibilityIdentifier("try again")
                    Button(Words.signOut) { Task { await model.signOut() } }
                        .accessibilityIdentifier("sign out")
                }
            }
            .frame(maxHeight: .infinity)
        }
    }
}
