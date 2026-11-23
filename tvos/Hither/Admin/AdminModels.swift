import Foundation
import HitherCore
import Observation

/// Why the guard removed the kept account: the last wrong PIN, or its password refused on signing in again.
enum AdminGone: Equatable {
    case tries, password
}

/// Where setting up the admin account is (Android's `AdminStep`, DECISIONS 92).
enum AdminStep: Equatable {
    /// Reading what is kept.
    case loading
    /// None kept: the sign-in form (email and password; the server is the family's).
    case form(AdminForm)
    /// The account is an administrator: said once, then on to the PIN.
    case administrator(name: String)
    /// Choosing the PIN, or entering it again ([confirming]).
    case pin(PinStep)
    /// An admin account is kept.
    case kept(name: String, removing: Bool)
}

struct AdminForm: Equatable {
    var problems: Set<SignInProblem> = []
    var failure: SignInFailure?
    var submitting = false
    /// The last account signed in could not be the admin, and why; nothing was kept.
    var refused: AdminVerdict?
    /// The kept account was just removed by the guard, and why (DECISIONS 93).
    var gone: AdminGone?
}

struct PinStep: Equatable {
    /// Core's `PinSetting`: choosing, or entering again against the first.
    var setting: PinSetting
    var name: String
    var saving = false
    var failed = false

    var confirming: Bool { setting is PinSettingConfirm }
    var mismatched: Bool { (setting as? PinSettingChoose)?.mismatched ?? false }

    static func == (a: PinStep, b: PinStep) -> Bool {
        (a.setting as AnyObject) === (b.setting as AnyObject) && a.name == b.name && a.saving == b.saving && a.failed == b.failed
    }
}

/// Setting up the TV's admin account (Android's `AdminSetup`), a page's worth of state: sign in once with the account
/// Tom made for approving (its rights read live), say so once if it is an administrator, choose the PIN and enter it
/// again; or see the account kept, switch to it, and remove it. The password is dropped from memory once sent.
@MainActor
@Observable
final class AdminSetup {
    private(set) var step: AdminStep = .loading
    var email = "" { didSet { forget(.email) } }
    var password = "" { didSet { forget(.password) } }
    /// A new entry clears the pad: bumped with each step of setting the PIN.
    private(set) var entry = 0

    @ObservationIgnored let admin: AdminGateway
    @ObservationIgnored private let family: User
    @ObservationIgnored private let baseUrl: String

    init(admin: AdminGateway, family: User, baseUrl: String) {
        self.admin = admin
        self.family = family
        self.baseUrl = baseUrl
    }

    func open() {
        guard step == .loading else { return }
        step = admin.kept().map { .kept(name: $0, removing: false) } ?? .form(AdminForm())
    }

    private func forget(_ problem: SignInProblem) {
        guard case .form(var form) = step, form.problems.contains(problem) else { return }
        form.problems.remove(problem)
        step = .form(form)
    }

    /// Sign In: once per press, nothing while one is out. The server is the family's.
    func submit() async {
        guard case .form(var form) = step, !form.submitting else { return }
        let checked = SignInFormKt.checkSignIn(server: baseUrl, email: email, password: password)
        let problems = ((checked.second as? Set<SignInProblem>) ?? []).subtracting([.server])
        guard let request = checked.first, problems.isEmpty else {
            step = .form(AdminForm(problems: problems))
            return
        }
        form = AdminForm(submitting: true)
        step = .form(form)
        let answer = await admin.signIn(request, family: family)
        password = ""
        switch answer {
        case .accepted(let verdict, let name):
            step = verdict == .administrator ? .administrator(name: name) : .pin(PinStep(setting: PinSettingChoose(mismatched: false), name: name))
        case .refused(let verdict):
            email = email.trimmingCharacters(in: .whitespaces)
            step = .form(AdminForm(refused: verdict))
        case .failed(let failure):
            email = email.trimmingCharacters(in: .whitespaces)
            step = .form(AdminForm(failure: failure))
        }
    }

    /// Past the administrator's note, to the PIN.
    func carryOn() {
        guard case .administrator(let name) = step else { return }
        step = .pin(PinStep(setting: PinSettingChoose(mismatched: false), name: name))
    }

    /// A full PIN entered: the next step of setting it, and keeping the account when both entries match.
    func pin(_ digits: String) async {
        guard case .pin(var at) = step, !at.saving, AdminKt.isPin(digits: digits) else { return }
        let next = AdminKt.pinSetting(state: at.setting, entry: digits)
        entry += 1
        guard let set = next as? PinSettingSet else {
            at.setting = next
            at.failed = false
            step = .pin(at)
            return
        }
        at.saving = true
        at.failed = false
        step = .pin(at)
        if await admin.setPin(set.pin) {
            step = .kept(name: at.name, removing: false)
        } else {
            at.setting = PinSettingChoose(mismatched: false)
            at.saving = false
            at.failed = true
            step = .pin(at)
        }
    }

    /// Back out of setting up: nothing is kept, and the session made is ended.
    func cancel() {
        switch step {
        case .kept, .loading: return
        default: break
        }
        password = ""
        step = .form(AdminForm())
        Task { await admin.cancel() }
    }

    /// The guard removed the kept account: the form again, saying why.
    func gone(_ why: AdminGone) {
        email = ""
        password = ""
        step = .form(AdminForm(gone: why))
    }

    /// Remove the admin account (asked first on the page).
    func remove() async {
        guard case .kept(let name, let removing) = step, !removing else { return }
        step = .kept(name: name, removing: true)
        await admin.remove()
        email = ""
        step = .form(AdminForm())
    }
}

/// What the switch's PIN sheet says under its title (Android's `SwitchNote`).
enum SwitchNote: Equatable {
    case ask
    /// Checking the PIN and opening the session.
    case checking
    case wrong(left: Int32)
    /// A wait is on until [until] (the TV's clock); the pad takes nothing meanwhile. [left]: tries left, -1 unknown.
    case wait(until: Date, left: Int32)
    case noRight
    case failed

    var trouble: Bool {
        switch self {
        case .ask, .checking: false
        default: true
        }
    }
}

/// Switching to the TV's admin (Android's `AdminSwitchModel`, DECISIONS 93), a sheet's worth of state: the PIN taken
/// once per entry, nothing while a check is out or a wait is on; [onIn] once the admin is active, [onGone] when the
/// account was removed (the last wrong PIN, or its password refused) with the reason.
@MainActor
@Observable
final class AdminSwitchModel {
    private(set) var note: SwitchNote = .ask
    /// A new entry clears the pad (wrong tries and waits).
    private(set) var entry = 0

    @ObservationIgnored private let admin: AdminGateway
    @ObservationIgnored private let onIn: (ActiveAdmin) -> Void
    @ObservationIgnored private let onGone: (AdminGone) -> Void

    init(admin: AdminGateway, onIn: @escaping (ActiveAdmin) -> Void, onGone: @escaping (AdminGone) -> Void) {
        self.admin = admin
        self.onIn = onIn
        self.onGone = onGone
    }

    var takesPin: Bool {
        switch note {
        case .checking, .wait: false
        default: true
        }
    }

    func pin(_ digits: String) async {
        guard takesPin else { return }
        note = .checking
        switch await admin.switchIn(digits) {
        case .switchedIn(let active):
            onIn(active)
        case .pin(let check):
            switch onEnum(of: check) {
            case .removed:
                onGone(.tries)
            case .wrong(let wrong):
                note = wrong.waitMs > 0 ? .wait(until: Date().addingTimeInterval(Double(wrong.waitMs) / 1000), left: wrong.left) : .wrong(left: wrong.left)
            case .wait(let wait):
                note = .wait(until: Date().addingTimeInterval(Double(wait.waitMs) / 1000), left: -1)
            case .right:
                break
            }
        case .noRight:
            note = .noRight
        case .passwordRefused:
            onGone(.password)
        case .failed:
            note = .failed
        case .noneKept:
            onGone(.tries)
        case .closed:
            // Hither left the front meanwhile: nothing switched; the pad is there again.
            note = .ask
        }
        entry += 1
    }

    /// The wait is over: the pad takes a PIN again.
    func waited() {
        guard case .wait(let until, let left) = note, Date() >= until else { return }
        note = left >= 0 ? .wrong(left: left) : .ask
    }
}
