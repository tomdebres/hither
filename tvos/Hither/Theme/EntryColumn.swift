import SwiftUI

/// Where something is typed (the review's X5: F3, F8, F21, F34): one centred column, as tvOS's own sign-in screens are.
/// Hither's mark, small (it was the biggest thing in the app); the title; a line of help; the fields, each the system's
/// stand-alone `TextField` with its error as plain red words under it (in a `Form` every error was a row and every
/// field a capsule inside a capsule); then the screen's one prominent button, indigo with white words (`EntryButton`),
/// and what went wrong with it under it. The family's sign-in and the approver's use it.
///
/// - The column is pinned at its top and grows down: an error appearing under a field moves what is under it, never
///   what is above it (Android's item 7). It scrolls when it is taller than the screen, so focus can always reach its
///   foot (an error under every field, and the other ways in, below).
/// - [methods]: the other ways in, under the button (M8: Plex, Jellyfin or Emby, as Android's card has them): Seerr's
///   "Other ways to sign in" ([methodsLead]), then a row of the system's own buttons, one per way the server offers. Drawn
///   only while [showsMethods]; with no password form above them (Seerr's own sign-in off) they are the way in, and no
///   "Or" heads them.
struct EntryColumn<Fields: View, Action: View, Methods: View>: View {
    var mark = true
    let title: String
    var titleId = "heading"
    var detail: String?
    var detailId: String?
    var methodsLead: String?
    var showsMethods: Bool
    let fields: Fields
    let action: Action
    let methods: Methods

    init(
        mark: Bool = true, title: String, titleId: String = "heading", detail: String? = nil, detailId: String? = nil,
        methodsLead: String? = Words.signInOr, showsMethods: Bool = true,
        @ViewBuilder fields: () -> Fields, @ViewBuilder action: () -> Action, @ViewBuilder methods: () -> Methods
    ) {
        self.mark = mark
        self.title = title
        self.titleId = titleId
        self.detail = detail
        self.detailId = detailId
        self.methodsLead = methodsLead
        self.showsMethods = showsMethods && Methods.self != EmptyView.self
        self.fields = fields()
        self.action = action()
        self.methods = methods()
    }

    var body: some View {
        ScrollView(.vertical) {
            VStack(spacing: 0) {
                if mark {
                    Image("LogoStacked")
                        .resizable()
                        .scaledToFit()
                        .frame(width: Metrics.entryMark)
                        .accessibilityLabel(Words.appName)
                        .accessibilityIdentifier("wordmark")
                        .padding(.bottom, 40)
                }
                Text(title)
                    .font(.title3.bold())
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier(titleId)
                if let detail {
                    Text(detail)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier(detailId ?? "\(titleId) detail")
                        .padding(.top, 16)
                }
                VStack(spacing: 24) { fields }
                    .padding(.top, 40)
                VStack(spacing: 16) { action }
                    .padding(.top, 40)
                if showsMethods {
                    VStack(spacing: 24) {
                        if let methodsLead {
                            Text(methodsLead)
                                .font(.callout)
                                .foregroundStyle(.secondary)
                                .accessibilityIdentifier("or sign in with")
                        }
                        HStack(spacing: 32) { methods }
                    }
                    .padding(.top, 48)
                }
            }
            .frame(width: Metrics.entryWidth)
            .padding(.bottom, Metrics.topMargin)
            // The column's width is the section's, so Down from a field reaches the button under it.
            .focusSection()
            .frame(maxWidth: .infinity)
        }
        .scrollIndicators(.hidden)
        // The system's fields lift as they take focus: room for it at the column's sides and top.
        .scrollClipDisabled()
    }
}

extension EntryColumn where Methods == EmptyView {
    init(
        mark: Bool = true, title: String, titleId: String = "heading", detail: String? = nil, detailId: String? = nil,
        @ViewBuilder fields: () -> Fields, @ViewBuilder action: () -> Action
    ) {
        self.init(
            mark: mark, title: title, titleId: titleId, detail: detail, detailId: detailId, showsMethods: false, fields: fields, action: action
        ) { EmptyView() }
    }
}

/// A field of an `EntryColumn` with its error under it: a capsule the column's width with the field's words on it, the
/// system's text field over it, and the error (Seerr's sentence) in red Callout words with nothing behind them.
///
/// - Hither draws the capsule and the words; the system's field takes focus, opens the keyboard and is what VoiceOver and
///   the tests read, drawn at 2% (`Metrics.entryFieldTrace`: kept above the 1% under which the focus engine passes a
///   view by). Its own look could not be fitted: at rest a glass capsule, which showed nothing on Hither's black ground
///   (polish part 4, which put a grey capsule under it); focused a white one that grew out of the column, 8 points
///   wider and 9 taller than the field under it, its grey placeholder 1.7:1 on it (Tom, M8 part 2: "First text field
///   doesn't look right"). Its plain style and `focusEffectDisabled()` still drew both.
/// - At rest: a form row's grey (`Tokens.field`), the system's placeholder grey, white words. Focused: the system's
///   focused white (`Tokens.fieldFocused`), black words, and a placeholder that reads on it (`Tokens.fieldFocusedPrompt`,
///   5.6:1), the same size. A secure field's words are dots.
struct EntryField<Field: View>: View {
    let prompt: String
    let text: String
    let secure: Bool
    let focused: Bool
    let error: String?
    let errorId: String
    let field: Field

    init(
        _ prompt: String, text: String, secure: Bool = false, focused: Bool, error: String?, errorId: String,
        @ViewBuilder field: () -> Field
    ) {
        self.prompt = prompt
        self.text = text
        self.secure = secure
        self.focused = focused
        self.error = error
        self.errorId = errorId
        self.field = field()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ZStack(alignment: .leading) {
                words
                    .lineLimit(1)
                    .truncationMode(.head)
                    .padding(.horizontal, Metrics.entryFieldInset)
                    .accessibilityHidden(true)
                // Its words clear as well: at 2% its typed words, larger than these, showed as a ghost beside them.
                // A sibling of the words (in an overlay instead, tvOS's keyboard opened on it but what was typed never
                // reached it).
                field
                    .foregroundStyle(.clear)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .opacity(Metrics.entryFieldTrace)
            }
            .frame(height: Metrics.entryField)
            // The capsule is the frame's, never the field's: the keyboard hands focus back to the field it opened on as
            // it closes, and that field came out of it taller (82 points); in the stack with it the capsule grew too.
            .background { Capsule().fill(focused ? Tokens.fieldFocused : Tokens.field) }
            .animation(.easeOut(duration: 0.2), value: focused)
            if let error { EntryProblem(text: error, id: errorId) }
        }
    }

    private var words: some View {
        Group {
            if text.isEmpty {
                Text(prompt).foregroundStyle(focused ? Tokens.fieldFocusedPrompt : Tokens.fieldPrompt)
            } else {
                Text(secure ? String(repeating: "\u{2022}", count: text.count) : text)
                    .foregroundStyle(focused ? Tokens.fieldFocusedText : Color.primary)
            }
        }
    }
}

/// What went wrong, in words, under what it is about: red Callout, no background (the review's F8: errors drawn as form
/// rows looked like controls).
struct EntryProblem: View {
    let text: String
    let id: String

    var body: some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(Tokens.errorText)
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier(id)
    }
}

/// The screen's one prominent button (the review's X10 rule 1, F3): Seerr's indigo with white words, 6.3:1, the column's
/// width. Out of a `Form`, so the tint is the button's fill, not its words' colour (indigo on dark grey, 2.8:1).
struct EntryButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(Tokens.accent)
    }
}

/// Moving focus off a field once tvOS's full-screen keyboard has gone (Done to the button, or Next to a control that is
/// not a field). While the keyboard is up the field's `FocusState` reads nil; as it closes, the keyboard hands focus back
/// to the field it opened on, after `onSubmit` has run, and a focus set before then is lost. A fixed 400 ms was not
/// always enough under the Studio's load (M8 part 2 measured the hand-back 550 ms after Next). So the view keeps where
/// focus is to go and moves it when the hand-back comes (focus from nil to the field, [target]); [fallback] moves it
/// if no hand-back is seen while focus is somewhere, and gives up after 3 s.
enum KeyboardHandOff {
    /// Where focus goes now: [pending] when focus has just come back from nil (the keyboard's hand-back).
    static func target<Field: Hashable>(_ pending: Field?, from old: Field?, to new: Field?) -> Field? {
        guard let pending, old == nil, let new, new != pending else { return nil }
        return pending
    }

    @MainActor
    static func fallback<Field: Hashable>(
        _ target: Field, pending: () -> Field?, focused: () -> Bool, move: () -> Void
    ) async {
        for _ in 0..<10 {
            try? await Task.sleep(for: .milliseconds(300))
            guard pending() == target else { return }
            if focused() { return move() }
        }
        if pending() == target { move() }
    }
}
