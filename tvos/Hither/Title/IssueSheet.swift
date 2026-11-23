import HitherCore
import SwiftUI

/// Seerr's "Report an Issue" (`CreateIssueModal`; Android's `IssueModal`, DECISIONS 106) as the system's form in a sheet
/// (the review's X6, F19; `FormSheet`): the title's name over, for a series, the affected season and episode (those in
/// the library, "All" first; the one season chosen when it is alone, core's `issueDraft`), Seerr's four types (F16: no
/// longer a row of pills with the chosen one prominent), each a row as tvOS's Settings draws a choice (`FormPick`: its
/// name left, the choice right, Select opens the list with a tick and a pick goes back), the words (required, typed with
/// tvOS's keyboard; S23: no red asterisk), then Submit Issue as the last row. One press, one report ("Submitting…" while
/// out). Menu closes it, and focus goes back to the button. A report that fails keeps the form, and its alert says why.
struct IssueSheet: View {
    let model: TitleModel
    let page: MediaPage
    let draft: IssueDraft

    @FocusState private var focus: String?

    var body: some View {
        let seasons = IssuesKt.issueSeasons(page: page).map(\.int32Value)
        let episodes = draft.season > 0 ? IssuesKt.issueEpisodes(page: page, season: draft.season) : 0
        let words = !draft.message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        FormSheet(Words.reportIssue, id: "issue modal") {
            Section {
                if !seasons.isEmpty {
                    FormPick(
                        title: Words.affectedSeason,
                        choices: [(value: Int32(0), words: Words.issueAllSeasons)] + seasons.map { (value: $0, words: Words.season($0)) },
                        selection: draft.season, id: "issue season"
                    ) { season in edit(season: season, episode: 0) }
                    .focused($focus, equals: "issue season")
                    FormPick(
                        title: Words.affectedEpisode,
                        choices: [(value: Int32(0), words: Words.allEpisodes)] + (episodes > 0 ? (1...episodes).map { (value: $0, words: Words.episode($0)) } : []),
                        selection: draft.episode, id: "issue episode"
                    ) { episode in edit(episode: episode) }
                    .disabled(episodes == 0)
                    .focused($focus, equals: "issue episode")
                }
                FormPick(
                    title: Words.issueKindHeader,
                    choices: IssuesKt.ISSUE_KINDS.map { (value: $0.type, words: $0.label) },
                    selection: draft.type, id: "issue type"
                ) { type in edit(type: type) }
                .focused($focus, equals: "issue type")
            } header: {
                FormHeader(words: page.title)
                    .accessibilityIdentifier("issue subject")
            }
            Section {
                // The field's own capsule alone, not inside a row's (the review's F8: a capsule in a capsule).
                TextField(Words.issueDetail, text: Binding(get: { draft.message }, set: { edit(message: $0) }))
                    .focused($focus, equals: "issue message")
                    .accessibilityIdentifier("issue message")
                    .listRowBackground(Color.clear)
            } header: {
                FormHeader(words: Words.whatsWrong)
            }
            Section {
                // While the report is out it keeps focus but does nothing (one press, one report).
                FormAction(words: model.reporting ? Words.submittingIssue : Words.submitIssue, enabled: words || model.reporting, id: "issue submit") {
                    model.sendIssue()
                }
                .focused($focus, equals: "issue submit")
            }
        }
        .problemAlert(model.issueProblem) { model.issueProblemSeen() }
        .task {
            // Android's entry: the season where there is one to choose, else the type.
            try? await Task.sleep(for: .milliseconds(300))
            focus = seasons.isEmpty ? "issue type" : "issue season"
        }
    }

    private func edit(type: Int32? = nil, season: Int32? = nil, episode: Int32? = nil, message: String? = nil) {
        model.editIssue(draft.doCopy(type: type ?? draft.type, season: season ?? draft.season, episode: episode ?? draft.episode, message: message ?? draft.message))
    }
}
