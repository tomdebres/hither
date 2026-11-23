import SwiftUI

/// A sheet of choices, as tvOS draws its own (the review's X6, F19): the system's navigation title over the system's
/// rows in sections (choices, a field), the filter sheet's shape and its `List`: in a `Form` on tvOS 26.5 a focused
/// button row kept its white words on the light platter, as a switch's did (DECISIONS 270). Its one action is the last row,
/// the screen's one prominent button; Menu closes the sheet, so there is no Cancel. A question with no choices is an
/// alert instead.
///
/// What the sheet is about (the title it asks for or reports on) is the first section's header (`FormHeader`), since
/// the sheet covers the page that would say it. The system's glass and nothing of Hither's own behind it (F2).
struct FormSheet<Content: View>: View {
    let title: String
    let id: String
    @ViewBuilder var content: Content

    init(_ title: String, id: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.id = id
        self.content = content()
    }

    var body: some View {
        NavigationStack {
            List {
                content
            }
            .navigationTitle(title)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(id)
    }
}

/// A `FormSheet`'s action, in a section of its own as the last row: the screen's one prominent button in the app's
/// indigo (rule 1), standing alone on the sheet's glass rather than inside a row's platter.
struct FormAction: View {
    let words: String
    let enabled: Bool
    let id: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(words)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(Tokens.accent)
        .disabled(!enabled)
        .accessibilityIdentifier(id)
        .listRowBackground(Color.clear)
    }
}

/// A form's header in its own words' case: the system's header style sets a section's title in capitals, which suits
/// a label ("SEASONS") but not a title's name or a sentence.
struct FormHeader: View {
    let words: String

    var body: some View {
        Text(words).textCase(nil)
    }
}

/// A choice in a `FormSheet`, as the filter sheet draws its own: the system's button row, its words left and a tick
/// right where it is on, Select to flip it. Not a `Toggle`: on tvOS 26.5 a switch's label in a sheet kept its white
/// words on the focused row's light platter (DECISIONS 270). The tick is the row's selected trait to VoiceOver.
struct FormChoice<Label: View>: View {
    let on: Bool
    /// Whether the row draws its tick when on: not where its status already says so (a season in the library).
    var ticks = true
    let id: String
    let action: () -> Void
    @ViewBuilder var label: Label

    var body: some View {
        Button(action: action) {
            HStack(spacing: 24) {
                label
                Spacer(minLength: 0)
                if on && ticks {
                    Image(systemName: "checkmark")
                        .accessibilityHidden(true)
                }
            }
        }
        .accessibilityValue(on ? "on" : "off")
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier(id)
    }
}

/// One of a few, in a `FormSheet`: a row as tvOS's Settings draws a choice (its name left, the choice right), which
/// opens the choices as a list of their own with the current one ticked; Select on any goes back with it, Menu goes back
/// without. The filter sheet's Original Language. Not a `.navigationLink` `Picker`: on tvOS 26.5 its list kept white
/// words on the focused row's light platter, and Select on the ticked one did nothing (DECISIONS 270).
///
/// The row carries the choice as its accessibility value; the list is `"<id> list"`, each item in it `"<id>: <words>"`.
struct FormPick<Value: Hashable>: View {
    let title: String
    let choices: [(value: Value, words: String)]
    let selection: Value
    let id: String
    let onPick: (Value) -> Void

    var body: some View {
        let chosen = choices.first { $0.value == selection }?.words ?? ""
        NavigationLink {
            FormPickList(title: title, choices: choices, selection: selection, id: id, onPick: onPick)
        } label: {
            LabeledContent(title) { Text(chosen) }
        }
        .accessibilityValue(chosen)
        .accessibilityIdentifier(id)
    }
}

private struct FormPickList<Value: Hashable>: View {
    let title: String
    let choices: [(value: Value, words: String)]
    let selection: Value
    let id: String
    let onPick: (Value) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List {
            ForEach(choices, id: \.value) { choice in
                FormChoice(on: choice.value == selection, id: "\(id): \(choice.words)", action: {
                    if choice.value != selection { onPick(choice.value) }
                    dismiss()
                }) {
                    Text(choice.words)
                }
            }
        }
        .navigationTitle(title)
        .accessibilityIdentifier("\(id) list")
    }
}
