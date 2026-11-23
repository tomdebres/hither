import HitherCore
import SwiftUI

/// A film's request, asked in the system's alert (the review's F2, F19: a film has nothing to choose, so it is a
/// question, not a sheet): "Request Coco?", Seerr's "approved automatically" where the account's rights say so and the
/// quota where it has one as the message, then Cancel and Request, short titles so tvOS sets them side by side (6b's
/// lesson: a long title stacks them). Past the quota it says so, with OK alone.
struct RequestQuestion: Equatable {
    let draft: RequestDraft
    let title: String
    let message: String
    /// Whether it can be asked for now; else the alert only says why not.
    let enabled: Bool

    /// The question about the film called [name], for [draft]'s copy, as core's modal for it stands.
    init(draft: RequestDraft, name: String, view: RequestModalView) {
        self.draft = draft
        enabled = view.primaryEnabled
        title = view.primaryEnabled ? Words.requestQuestion(name, fourK: draft.is4k) : view.primary
        message = RequestNotes.lines(view).joined(separator: "\n")
    }
}

/// What a request will come to, in sentences (the review's S15, S16): Seerr's "approved automatically" without its
/// coloured mark, and the quota as words, not Seerr's heat-coloured ring.
enum RequestNotes {
    static func lines(_ view: RequestModalView) -> [String] {
        var lines: [String] = []
        if view.approvedAutomatically { lines.append(Words.approvedAutomatically) }
        if let quota = view.quota { lines.append(Words.quota(quota.text, quota.allowance)) }
        return lines
    }
}

/// Seerr's request modal for a series' copy (Android's `RequestModal`, ref 2.9) as the system's form in a sheet (the
/// review's X6, F19; `FormSheet`): the navigation title Seerr's ("Request Series", "Request Series in 4K"), the series'
/// name over the seasons as the filter sheet's ticked rows ("All Seasons" first; seasons already taken dimmed and
/// showing their status; past the quota greyed), "approved automatically" and the quota as sentences under them,
/// and the primary as the last row. Only a season with a status says it: an open season's row without a tick already
/// says it is not requested (S6). Menu
/// closes it (the caller cancels), and focus goes back to the button or the season card that opened it.
///
/// Entry: opened from a season's card (Tom's D2), the season is on, its row is scrolled into view and the primary takes
/// focus, so a second Select asks for it (part 6). Else the first open season, else the primary.
struct RequestSheet: View {
    let model: TitleModel
    let page: MediaPage
    let draft: RequestDraft

    @FocusState private var focus: String?

    var body: some View {
        let view = model.modal(draft, page)
        let open = view.rows.filter { !$0.taken }
        let firstOpen = view.picker ? open.first : nil
        let entry = draft.season != nil && view.primaryEnabled
            ? "primary"
            : firstOpen.map { "season \($0.seasonNumber)" } ?? "primary"
        let notes = RequestNotes.lines(view)
        ScrollViewReader { proxy in
            FormSheet(view.title, id: "request modal") {
                Section {
                    SeasonChoices(view: view, selected: draft.selected, model: model, focus: $focus)
                } header: {
                    FormHeader(words: view.subtitle)
                        .accessibilityIdentifier("request subject")
                } footer: {
                    if !notes.isEmpty {
                        Text(notes.joined(separator: " "))
                            .textCase(nil)
                            .accessibilityIdentifier(view.approvedAutomatically ? "approved automatically" : "request notes")
                    }
                }
                Section {
                    // While the request is out it keeps focus but does nothing (one press, one request).
                    FormAction(words: view.primary, enabled: view.primaryEnabled || model.sending, id: "primary") {
                        model.send()
                    }
                    .focused($focus, equals: "primary")
                }
            }
            .defaultFocus($focus, entry)
            .task {
                // The sheet's own default lands on its first row: the entry is said once the sheet is up, and a season
                // chosen from its card is brought into view.
                try? await Task.sleep(for: .milliseconds(300))
                focus = entry
                if let season = draft.season {
                    try? await Task.sleep(for: .milliseconds(300))
                    withAnimation { proxy.scrollTo(season, anchor: .center) }
                }
            }
        }
    }
}

/// The seasons as the filter sheet's choices (`FormChoice`): "All Seasons" first, then each season with its episode
/// count, and its status where it has one (taken: in the library, requested). Select ticks or unticks a season; seasons
/// already taken are on, dimmed, their status in place of the tick; past the quota a season is greyed and stays
/// unticked. With partial requests off the list is to read: every open season goes, and the rows are ticked and still.
private struct SeasonChoices: View {
    let view: RequestModalView
    let selected: Set<Int32>
    let model: TitleModel
    var focus: FocusState<String?>.Binding

    var body: some View {
        let open = view.rows.filter { !$0.taken }
        let allOn = !open.isEmpty && open.allSatisfy { selected.contains($0.seasonNumber) }
        FormChoice(on: allOn || (!view.picker && !open.isEmpty), id: "season all", action: { model.toggleAll() }) {
            Text(Words.allSeasons)
        }
        .disabled(!view.picker || open.isEmpty)
        .focused(focus, equals: "season all")
        ForEach(view.rows, id: \.seasonNumber) { row in
            let on = row.taken || selected.contains(row.seasonNumber) || (!view.picker && !row.taken)
            FormChoice(on: on, ticks: !row.taken, id: "season \(row.seasonNumber)", action: { model.toggle(row.seasonNumber) }) {
                Text(row.name)
                Text(Words.episodes(row.episodes))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                if row.taken {
                    Spacer(minLength: 0)
                    StatusLabel(badge: row.badge).font(.caption)
                }
            }
            .disabled(!view.picker || row.taken || (row.overQuota && !on))
            .opacity(row.taken || row.overQuota ? 0.5 : 1)
            .focused(focus, equals: "season \(row.seasonNumber)")
            .id(row.seasonNumber)
        }
    }
}
