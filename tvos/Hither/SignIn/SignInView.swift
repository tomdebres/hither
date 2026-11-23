import HitherCore
import SwiftUI

/// Signing in: the form, or a page in front of it (M8): Jellyfin's or Emby's form (`MediaServerSignInView`), or a code
/// to enter elsewhere (`CodeSignInView`: Plex's link code, Jellyfin's Quick Connect).
struct SignInView: View {
    @Bindable var model: AppModel

    var body: some View {
        switch model.form.page {
        case .mediaServer(let page):
            MediaServerSignInView(model: model, page: page)
        case .code(let page):
            CodeSignInView(model: model, page: page)
        case nil:
            SignInFormView(model: model)
        }
    }
}

/// First run, as tvOS's own sign-in screens are (the review's X5, F3, F8, F21, F34): one centred column (`EntryColumn`)
/// of Hither's mark, small, the heading, Server Address, Email Address and Password (Seerr's sign-in with a Server field
/// above its two: `docs/design/seerr-reference.md` 2.11, Android's `SignInScreen`), then Sign In, indigo with white
/// words. Focus starts on Server; Select on a field opens tvOS's keyboard, whose Next carries on to the following field
/// and whose Done lands on Sign In. Errors are Seerr's own sentences in red words under what they are about.
///
/// Since M8 the server is read once its field is done with (its keyboard's Next, or focus leaving it), and the form
/// offers what it enables (`ServerOffer`, core's `signInMethods`): the password form while Seerr's own sign-in is on (or
/// not known yet), then under "Other ways to sign in" the media server's button, Plex or Jellyfin or Emby, a plain system
/// button in `EntryColumn`'s methods. With Seerr's own sign-in off there are no email and password fields and no Sign
/// In: the button is the way in, and the server field's Next goes to it. A page that closes brings focus back to its
/// button (`returnTo`), and so does a Plex or Quick Connect session that ended.
private struct SignInFormView: View {
    @Bindable var model: AppModel
    @FocusState private var focus: Field?
    /// Next on the server field before its offer came: focus went on to the email field, which may yet go.
    @State private var advanceOnOffer = false
    /// Where focus goes once tvOS's keyboard has handed it back (`KeyboardHandOff`).
    @State private var handOff: Field?

    private enum Field: Hashable { case server, email, password, submit, method }

    /// What the offer changes on the form: the password form, and the media server's button.
    private struct Offered: Equatable {
        let local: Bool
        let way: MediaServerKind?
    }

    /// When focus may go back to a way in: a page closed, or a session that ended, once its button is there.
    private struct Arrival: Equatable {
        let returnTo: SignInControl?
        let way: MediaServerKind?
    }

    var body: some View {
        PageScaffold(.screen, id: "page Sign In") { page }
    }

    private var page: some View {
        let form = model.form
        let local = form.local
        let way = form.mediaServerWay
        return EntryColumn(
            // With no password form (Seerr's own sign-in off), "Sign in to your server" heads the buttons.
            title: local ? (form.serverTitle.map(Words.signInHeading(server:)) ?? Words.signInHeading) : Words.signInHeading,
            methodsLead: local ? Words.signInOr : nil,
            showsMethods: way != nil
        ) {
            EntryField(
                Words.fieldServer, text: form.server, focused: focus == .server, error: form.problems.contains(.server) ? Words.errorServer : nil,
                errorId: "server error"
            ) {
                TextField(Words.fieldServer, text: $model.form.server)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.next)
                    .focused($focus, equals: .server)
                    .onSubmit {
                        model.serverDone()
                        if local {
                            // The offer may yet take the password form away: then focus goes on to the way in.
                            if form.offer == nil { advanceOnOffer = true }
                            focus = .email
                        } else if way != nil {
                            handOn(to: .method)
                        }
                    }
                    .accessibilityIdentifier("server")
            }
            if form.checking {
                Text(Words.signInChecking)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("checking")
            }
            if local {
                EntryField(
                    Words.fieldEmail, text: form.email, focused: focus == .email, error: form.problems.contains(.email) ? Words.errorEmail : nil,
                    errorId: "email error"
                ) {
                    TextField(Words.fieldEmail, text: $model.form.email)
                        .textContentType(.username)
                        .keyboardType(.emailAddress)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .submitLabel(.next)
                        .focused($focus, equals: .email)
                        .onSubmit { focus = .password }
                        .accessibilityIdentifier("email")
                }
                EntryField(
                    Words.fieldPassword, text: form.password, secure: true, focused: focus == .password, error: form.problems.contains(.password) ? Words.errorPassword : nil,
                    errorId: "password error"
                ) {
                    SecureField(Words.fieldPassword, text: $model.form.password)
                        .textContentType(.password)
                        .submitLabel(.done)
                        .focused($focus, equals: .password)
                        .onSubmit { handOn(to: .submit) }
                        .accessibilityIdentifier("password")
                }
            }
        } action: {
            if local {
                EntryButton(title: form.submitting ? Words.signingIn : Words.signIn) {
                    Task { await model.submit() }
                }
                .disabled(form.submitting)
                .focused($focus, equals: .submit)
                .accessibilityIdentifier("sign in")
            }
            if let failure = form.failure {
                EntryProblem(text: Words.signInFailure(failure, server: form.offer?.methods.mediaServer), id: "sign-in error")
            }
        } methods: {
            if let way {
                Button(way.title) {
                    if way == .plex { model.openPlex() } else { model.openMediaServer() }
                }
                .disabled(form.submitting)
                .focused($focus, equals: .method)
                .accessibilityIdentifier(way == .plex ? "plex" : "media server")
            }
        }
        .padding(.top, Metrics.entryTop)
        .defaultFocus($focus, .server)
        .onChange(of: model.form.server) { model.serverChanged() }
        // Focus leaving the server field is the field done with, as its Next is.
        .onChange(of: focus) { old, new in
            if old == .server, new != .server { model.serverDone() }
            if let target = KeyboardHandOff.target(handOff, from: old, to: new) {
                handOff = nil
                Task { focus = target }
            }
        }
        .onChange(of: Offered(local: local, way: way)) { _, offered in
            // The password form going (the server has it off) while focus was in it, or on its way there from the
            // server field: focus to the way in that is offered, once the form has gone.
            let wasInForm = advanceOnOffer || focus == nil || focus == .email || focus == .password || focus == .submit
            if model.form.offer != nil { advanceOnOffer = false }
            guard !offered.local, offered.way != nil, wasInForm else { return }
            handOn(to: .method)
        }
        // Back from a page, or after a session that ended: focus on the button that is the way back in, once.
        .task(id: Arrival(returnTo: form.returnTo, way: way)) {
            guard model.form.returnTo == .plex || model.form.returnTo == .mediaServer, way != nil else { return }
            try? await Task.sleep(for: .milliseconds(150))
            guard !Task.isCancelled else { return }
            focus = .method
            model.form.returnTo = nil
        }
    }

    /// Focus to [target] once tvOS's keyboard has closed and handed focus back (`KeyboardHandOff`).
    private func handOn(to target: Field) {
        handOff = target
        Task { await KeyboardHandOff.fallback(target, pending: { handOff }, focused: { focus != nil }) { handOff = nil; focus = target } }
    }
}
