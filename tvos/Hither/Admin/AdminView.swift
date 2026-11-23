import HitherCore
import SwiftUI

/// Settings › Admin Account (Android's `AdminScreen`, DECISIONS 92, 93), in tvOS's own shapes (the review's part 4):
/// with none kept, a word on what it is for and the approver's sign-in as the family's is (`EntryColumn`: the server is
/// the family's, the same keyboard handling); for an administrator, a word said once; then the PIN, chosen and entered
/// again on tvOS's own digit entry, full screen (`DigitEntry`); or the account kept, as Settings' rows
/// (`SettingsScaffold`): who it is and its right, Switch to {name} (Switch Back while active), and Remove Admin Account,
/// a destructive row that asks first in the system's alert. Menu leaves at any step, keeping nothing unless the PIN was
/// set.
struct AdminView: View {
    /// Kept by the page itself, so Settings drawing it again does not start setting up again.
    @State var setup: AdminSetup
    var onBack: () -> Void

    @State private var asking = false
    @State private var switching = false
    @FocusState private var focus: Field?

    private enum Field: Hashable { case email, password, submit, carryOn, switchButton, remove }

    var body: some View {
        PageScaffold(.pushed(Words.adminAccount), id: "page Admin") {
            steps
                // The PIN, chosen and entered again, on the system's own screen. Menu there leaves the page, keeping
                // nothing (as on the pad before it).
                .fullScreenCover(isPresented: choosingPin) {
                    PinChoice(setup: setup)
                }
        }
        .task { setup.open() }
        // Menu from the page (or anything that takes it away) keeps nothing that was not yet kept.
        .onDisappear { setup.cancel() }
        .fullScreenCover(isPresented: $switching, onDismiss: {
            // Back from the PIN: focus on the switch again.
            Task {
                try? await Task.sleep(for: .milliseconds(150))
                if case .kept = setup.step { focus = .switchButton }
            }
        }) {
            SwitchPin(admin: setup.admin, name: keptName, onIn: { _ in switching = false }, onGone: { why in
                switching = false
                setup.gone(why)
            })
        }
        .alert(Words.adminRemoveTitle, isPresented: $asking) {
            Button(Words.cancel, role: .cancel) {
                Task {
                    try? await Task.sleep(for: .milliseconds(150))
                    focus = .remove
                }
            }
            .accessibilityIdentifier("cancel remove admin")
            Button(Words.adminRemove, role: .destructive) { Task { await setup.remove() } }
                .accessibilityIdentifier("confirm remove admin")
        } message: {
            Text(Words.adminRemoveBody)
        }
    }

    /// The step the setting up is at, under the page's title.
    @ViewBuilder
    private var steps: some View {
        switch setup.step {
        case .loading, .pin:
            // The PIN is the system's screen over the page.
            Color.clear
        case .form(let form):
            signInForm(form)
        case .administrator(let name):
            administrator(name)
        case .kept(let name, let removing):
            kept(name, removing: removing)
        }
    }

    /// While a PIN is being chosen; Menu there closes the digit entry and leaves the page.
    private var choosingPin: Binding<Bool> {
        Binding(
            get: { if case .pin = setup.step { true } else { false } },
            set: { shown in
                if !shown, case .pin = setup.step { onBack() }
            }
        )
    }

    private var keptName: String {
        if case .kept(let name, _) = setup.step { return name }
        return ""
    }

    // MARK: - None kept: sign in as the approver

    private func signInForm(_ form: AdminForm) -> some View {
        @Bindable var setup = setup
        return EntryColumn(mark: false, title: Words.adminHeadingSignIn, titleId: "admin heading", detail: Words.adminIntro, detailId: "admin intro") {
            EntryField(
                Words.fieldEmail, text: setup.email, focused: focus == .email, error: form.problems.contains(.email) ? Words.errorEmail : nil,
                errorId: "admin email error"
            ) {
                TextField(Words.fieldEmail, text: $setup.email)
                    .textContentType(.username)
                    .keyboardType(.emailAddress)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.next)
                    .disabled(form.submitting)
                    .focused($focus, equals: .email)
                    .onSubmit { focus = .password }
                    .accessibilityIdentifier("admin email")
            }
            EntryField(
                Words.fieldPassword, text: setup.password, secure: true, focused: focus == .password, error: form.problems.contains(.password) ? Words.errorPassword : nil,
                errorId: "admin password error"
            ) {
                SecureField(Words.fieldPassword, text: $setup.password)
                    .textContentType(.password)
                    .submitLabel(.done)
                    .disabled(form.submitting)
                    .focused($focus, equals: .password)
                    .onSubmit {
                        // As the family's form: Sign In takes focus once tvOS's keyboard has gone.
                        Task {
                            try? await Task.sleep(for: .milliseconds(400))
                            focus = .submit
                        }
                    }
                    .accessibilityIdentifier("admin password")
            }
        } action: {
            EntryButton(title: form.submitting ? Words.signingIn : Words.signIn) {
                Task { await setup.submit() }
            }
            .focused($focus, equals: .submit)
            .accessibilityIdentifier("admin sign in")
            if let failure = form.failure { EntryProblem(text: Words.signInFailure(failure), id: "admin sign-in error") }
            if let gone = form.gone {
                EntryProblem(text: gone == .tries ? Words.adminGoneTries : Words.adminGonePassword, id: "admin gone")
            }
            if let refused = form.refused {
                EntryProblem(text: refused == .family ? Words.adminRefusedFamily : Words.adminRefusedNoRight, id: "admin refused")
            }
        }
        .defaultFocus($focus, .email)
    }

    // MARK: - An administrator: said once

    private func administrator(_ name: String) -> some View {
        EntryColumn(mark: false, title: Words.adminAdministratorTitle, titleId: "admin administrator title", detail: Words.adminAdministratorBody(name), detailId: "admin administrator body") {
            EmptyView()
        } action: {
            EntryButton(title: Words.adminCarryOn) { setup.carryOn() }
                .focused($focus, equals: .carryOn)
                .accessibilityIdentifier("admin carry on")
            Button(Words.cancel, action: onBack)
                .accessibilityIdentifier("admin cancel")
        }
        .defaultFocus($focus, .carryOn)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("admin administrator")
    }

    // MARK: - Kept

    /// The account kept, as Settings' rows (the review's F22, F26, S30): who it is with its right in plain secondary
    /// words (Seerr's role pill is gone), Switch to {name}, then Remove Admin Account in red words by its role (the red
    /// fill beside an indigo one is gone; its red bled through the PIN's glass); the note as the section's footer.
    private func kept(_ name: String, removing: Bool) -> some View {
        let active = setup.admin.active != nil
        return SettingsScaffold(mark: .symbol("person.badge.shield.checkmark"), markId: "admin mark") {
            Section {
                LabeledContent {
                    Text(Words.adminKeptRole)
                        .accessibilityIdentifier("admin role")
                } label: {
                    Text(name)
                        .accessibilityIdentifier("admin name")
                }
                Button(active ? Words.adminSwitchBack : Words.adminSwitchIn(name)) {
                    if active { setup.admin.back() } else { switching = true }
                }
                .disabled(removing)
                .focused($focus, equals: .switchButton)
                .accessibilityIdentifier(active ? "admin switch back" : "admin switch in")
            } footer: {
                Text(active ? Words.adminActiveNote : Words.adminKeptNote)
                    .accessibilityIdentifier("admin kept note")
            }
            Section {
                Button(role: .destructive) {
                    asking = true
                } label: {
                    Text(removing ? Words.adminRemoving : Words.adminRemove)
                }
                .disabled(removing)
                .focused($focus, equals: .remove)
                .accessibilityIdentifier("admin remove")
            }
        }
        .defaultFocus($focus, .switchButton)
        .onAppear { focus = .switchButton }
    }
}

/// Choosing the PIN (Android's `AdminScreen` PIN steps, DECISIONS 92) on tvOS's digit entry: "Choose a 4-digit PIN",
/// then "Enter the PIN again", the line under it saying what it is for, that the two were not the same, or that keeping
/// the account failed. Each step starts empty.
private struct PinChoice: View {
    let setup: AdminSetup

    var body: some View {
        if case .pin(let step) = setup.step {
            DigitEntry(
                title: step.confirming ? Words.pinAgain : Words.pinChoose,
                prompt: note(step),
                digits: Int(AdminKt.PIN_LENGTH),
                takes: !step.saving,
                entry: setup.entry
            ) { pin in
                Task { await setup.pin(pin) }
            }
            .ignoresSafeArea()
            .accessibilityIdentifier("pin entry")
        }
    }

    private func note(_ step: PinStep) -> String {
        if step.saving { return Words.pinSaving }
        if step.failed { return Words.pinSaveFailed }
        if step.mismatched { return Words.pinMismatch }
        return Words.pinChooseNote(step.name)
    }
}

/// Switching to the TV's admin (Android's `AdminPinDialog`, DECISIONS 93) on tvOS's digit entry, full screen: "Switch to
/// {name}", and under it what happened last. A wrong PIN says how many tries are left (and, with one left, that the next
/// wrong one makes the TV forget the account); a wait counts down each second and an entry during it is dropped
/// unchecked. Menu closes it (the cover's dismissal).
struct SwitchPin: View {
    @State private var model: AdminSwitchModel
    let name: String
    /// The clock the wait's countdown reads, moved on each second while a wait is on.
    @State private var now = Date()

    init(admin: AdminGateway, name: String, onIn: @escaping (ActiveAdmin) -> Void, onGone: @escaping (AdminGone) -> Void) {
        _model = State(initialValue: AdminSwitchModel(admin: admin, onIn: onIn, onGone: onGone))
        self.name = name
    }

    var body: some View {
        DigitEntry(
            title: Words.switchTitle(name),
            prompt: words(at: now),
            digits: Int(AdminKt.PIN_LENGTH),
            takes: model.takesPin,
            entry: model.entry
        ) { pin in
            Task { await model.pin(pin) }
        }
        .ignoresSafeArea()
        .task(id: model.note) {
            // A wait counts down, and gives the PIN back when it is over.
            guard case .wait(let until, _) = model.note else { return }
            while !Task.isCancelled {
                now = Date()
                let left = until.timeIntervalSince(now)
                if left <= 0 { break }
                // To the next whole second left, where the words change.
                let step = left - left.rounded(.down)
                try? await Task.sleep(for: .seconds(step > 0.01 ? step : 1))
            }
            if !Task.isCancelled {
                now = Date()
                model.waited()
            }
        }
        .accessibilityIdentifier("pin entry")
    }

    private func words(at now: Date) -> String {
        switch model.note {
        case .ask: Words.switchAsk
        case .checking: Words.switchChecking
        case .wrong(let left): Words.switchWrong(Int(left))
        case .wait(let until, _): Words.switchWait(Int(max(0, until.timeIntervalSince(now)).rounded(.up)))
        case .noRight: Words.switchNoRight(name)
        case .failed: Words.switchFailed
        }
    }
}
