import HitherCore
import SwiftUI

/// Jellyfin's or Emby's sign-in (M8), Seerr's `JellyfinLogin` as a pushed page: the server's name as the page's title,
/// then another `EntryColumn` without the mark: "Sign in with Jellyfin", Username and Password, Sign In, what went wrong
/// under it, and for Jellyfin on Seerr 3.4.0+ Quick Connect (core decides). The fields behave as the sign-in form's:
/// Select opens tvOS's keyboard, Next carries on, Done lands on Sign In. Menu returns to the form, on the button that
/// opened this; back from Quick Connect's code, focus is on Quick Connect.
struct MediaServerSignInView: View {
    @Bindable var model: AppModel
    let page: MediaServerPage
    @FocusState private var focus: Field?
    /// Where focus goes once tvOS's keyboard has handed it back (`KeyboardHandOff`).
    @State private var handOff: Field?

    private enum Field: Hashable { case username, password, submit, quickConnect }

    var body: some View {
        PageScaffold(.pushed(model.form.offer?.title ?? model.form.serverTitle), id: "page media server") {
            EntryColumn(mark: false, title: Words.signInWithMediaServer(page.kind.title)) {
                EntryField(
                    Words.fieldUsername, text: model.form.username, focused: focus == .username, error: page.usernameMissing ? Words.errorUsername : nil,
                    errorId: "username error"
                ) {
                    TextField(Words.fieldUsername, text: $model.form.username)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .submitLabel(.next)
                        .disabled(page.submitting)
                        .focused($focus, equals: .username)
                        .onSubmit { focus = .password }
                        .accessibilityIdentifier("username")
                }
                EntryField(Words.fieldPassword, text: model.form.mediaPassword, secure: true, focused: focus == .password, error: nil, errorId: "media password error") {
                    SecureField(Words.fieldPassword, text: $model.form.mediaPassword)
                        .textContentType(.password)
                        .submitLabel(.done)
                        .disabled(page.submitting)
                        .focused($focus, equals: .password)
                        // As the sign-in form: Sign In takes focus once tvOS's keyboard has handed it back.
                        .onSubmit { handOn(to: .submit) }
                        .accessibilityIdentifier("media password")
                }
            } action: {
                // Not disabled while it signs in: a busy button keeps focus (a disabled one sends it nowhere, every other
                // control here being disabled too); the model takes one press at a time.
                EntryButton(title: page.submitting ? Words.signingIn : Words.signIn) {
                    Task { await model.submitMediaServer() }
                }
                .focused($focus, equals: .submit)
                .accessibilityIdentifier("media sign in")
                if let failure = page.failure {
                    EntryProblem(text: Words.mediaServerFailure(failure, server: page.kind), id: "media sign-in error")
                }
                if page.quickConnect {
                    // Seerr's quiet button under the form (JellyfinLogin, Seerr 3.4.0+): the system's own, the column's width.
                    Button {
                        model.openQuickConnect()
                    } label: {
                        Text(Words.quickConnect).frame(maxWidth: .infinity)
                    }
                    .disabled(page.submitting)
                    .focused($focus, equals: .quickConnect)
                    .accessibilityIdentifier("quick connect")
                }
            }
            .defaultFocus($focus, .username)
            .onChange(of: focus) { old, new in
                if let target = KeyboardHandOff.target(handOff, from: old, to: new) {
                    handOff = nil
                    Task { focus = target }
                }
            }
        }
        .onExitCommand { model.closePage() }
        .task {
            guard page.returnTo == .quickConnect, page.quickConnect else { return }
            try? await Task.sleep(for: .milliseconds(150))
            focus = .quickConnect
        }
    }

    private func handOn(to target: Field) {
        handOff = target
        Task { await KeyboardHandOff.fallback(target, pending: { handOff }, focused: { focus != nil }) { handOff = nil; focus = target } }
    }
}

/// A code to enter or approve elsewhere (M8), read from across the room: Plex's at plex.tv/link, or Jellyfin's Quick
/// Connect. A pushed page with the server's name as its title, then the way's heading ("Sign in with Plex", "Quick
/// Connect"), where to go, the code (Title 1, bold, in a box of a form row's grey: the largest words on the screen),
/// the system's spinner with "Waiting for the code to be entered…", and Cancel, which has focus. A code that ran out or
/// could not be had is a `StateView`: what happened, Get a New Code (or Try Again) and Cancel. Cancel and Menu cancel
/// the wait: nothing more is asked of plex.tv or Seerr.
struct CodeSignInView: View {
    let model: AppModel
    let page: CodePage
    @FocusState private var focus: Control?

    private enum Control: Hashable { case cancel, again }

    private var plex: Bool { page.kind == .plex }
    private var stuck: Bool { page.state == .expired || page.state == .failed }

    var body: some View {
        PageScaffold(.pushed(model.form.offer?.title ?? model.form.serverTitle), id: "page code") {
            VStack(spacing: 0) {
                Text(plex ? Words.plexHeading : Words.quickConnectHeading)
                    .font(.title3.bold())
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("code heading")
                if stuck { trouble } else { waiting }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.bottom, Metrics.topMargin)
            .focusSection()
        }
        .onExitCommand { model.closePage() }
        .task(id: stuck) {
            try? await Task.sleep(for: .milliseconds(150))
            focus = stuck ? .again : .cancel
        }
    }

    /// Where to go, the code, the waiting line and Cancel.
    private var waiting: some View {
        VStack(spacing: 40) {
            if plex {
                VStack(spacing: 8) {
                    Text(Words.plexGoTo)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    Text(Words.plexLink)
                        .font(.title2.bold())
                        .accessibilityIdentifier("plex link")
                    Text(Words.plexEnter)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
            } else {
                VStack(spacing: 12) {
                    Text(Words.quickConnectEnter)
                        .font(.headline)
                    Text(page.approveAt.map { Words.quickConnectWhere(at: $0) } ?? Words.quickConnectWhere)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: Metrics.entryWidth)
                        .accessibilityIdentifier("approve where")
                }
            }
            CodeBox(code: page.code)
            HStack(spacing: 20) {
                ProgressView()
                Text(page.code == nil ? Words.codeGetting : plex ? Words.plexWaiting : Words.quickConnectWaiting)
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("waiting")
            Button(Words.codeCancel) { model.closePage() }
                .focused($focus, equals: .cancel)
                .accessibilityIdentifier("cancel")
        }
        .padding(.top, 48)
    }

    /// The code ran out, or none could be had: in the system's way of saying a thing is not there, with the way on.
    private var trouble: some View {
        let words = page.failure.map { Words.signInFailure($0, server: plex ? .plex : .jellyfin) } ?? Words.errorSignIn
        let expired = page.state == .expired
        return StateView(
            .failed, expired ? Words.codeExpired : words, detail: expired ? words : nil,
            symbol: CodeTrouble.symbol(expired: expired, failure: page.failure), id: "code trouble"
        ) {
            HStack(spacing: 32) {
                Button(expired ? Words.codeNew : Words.tryAgain) { model.newCode() }
                    .buttonStyle(.borderedProminent)
                    .tint(Tokens.accent)
                    .focused($focus, equals: .again)
                    .accessibilityIdentifier("new code")
                Button(Words.codeCancel) { model.closePage() }
                    .focused($focus, equals: .cancel)
                    .accessibilityIdentifier("cancel")
            }
            // Each button its words' width: in the state's actions "Get a New Code" was cut to "Get a Ne…".
            .fixedSize()
        }
        .frame(maxHeight: .infinity)
    }
}

/// The symbol over a code that ran out or could not be had: a clock for one that ran out; the network's for Seerr, the
/// media server or plex.tv not answering; the state's own warning for the rest. Quick Connect switched off in Jellyfin
/// is no network fault (Tom, M8 part 2: it showed the wifi symbol).
enum CodeTrouble {
    static func symbol(expired: Bool, failure: SignInFailure?) -> String? {
        if expired { return "clock.badge.exclamationmark" }
        return switch failure {
        case .unreachable, .mediaServerUnreachable, .plexUnreachable: "wifi.exclamationmark"
        default: nil
        }
    }
}

/// The code itself, for reading across a room: Title 1 (tvOS's largest text style), bold, monospaced, spaced out, in a
/// box of a form row's grey. A Quick Connect code's six digits as two threes; Plex's four as they are. VoiceOver spells
/// it. Empty while the code is on its way, at the same size.
private struct CodeBox: View {
    let code: String?

    var body: some View {
        Text(code.map(Self.spaced) ?? "0000")
            .font(.title.bold())
            .monospaced()
            .tracking(12)
            .opacity(code == nil ? 0 : 1)
            .padding(.horizontal, 64)
            .padding(.vertical, 20)
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Tokens.field))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(code.map(Words.codeDescribed) ?? Words.codeGetting)
            .accessibilityIdentifier("code")
    }

    static func spaced(_ code: String) -> String {
        code.count == 6 && code.allSatisfy(\.isNumber) ? String(code.prefix(3)) + " " + String(code.suffix(3)) : code
    }
}
