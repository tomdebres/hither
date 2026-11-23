import SwiftUI

/// Settings' pages as tvOS's Settings app draws its own (the review's X4: F13, F22, F26, F37): a picture centred in the
/// left of the page, and on the right a `Form` of plain rows to the safe edge (Apple's Settings on the same simulator:
/// the tile x = 255-795, the list x = 1060-1840). Rows are words, a value on the right where there is one, the system's
/// chevron where a row opens a page; a destructive row says so by its role (red words), never by a fill; a note is a
/// section's footer. Settings, About, Diagnostics and Admin Account use it, inside their `PageScaffold`, which sets the
/// page's edges and title.
struct SettingsScaffold<Content: View>: View {
    /// What the left of the page shows.
    enum Emblem {
        /// The app's stacked logo.
        case logo
        /// An SF Symbol, in the secondary colour (Admin Account: the approver's person and shield, as on the tab).
        case symbol(String)
    }

    var mark: Emblem = .logo
    var markId = "settings mark"
    let content: Content

    init(mark: Emblem = .logo, markId: String = "settings mark", @ViewBuilder content: () -> Content) {
        self.mark = mark
        self.markId = markId
        self.content = content()
    }

    var body: some View {
        HStack(alignment: .top, spacing: Metrics.settingsGap) {
            picture
                .frame(width: Metrics.settingsMarkArea)
                .frame(maxHeight: .infinity)
            Form { content }
                .frame(width: Metrics.settingsList)
                .frame(maxHeight: .infinity, alignment: .top)
                // Its own section, as wide as the list: Up and Down stay in it, and the tab bar is above it.
                .focusSection()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
    }

    @ViewBuilder
    private var picture: some View {
        switch mark {
        case .logo:
            Image("LogoStacked")
                .resizable()
                .scaledToFit()
                .frame(width: Metrics.settingsMark)
                .accessibilityLabel(Words.appName)
                .accessibilityIdentifier(markId)
        case .symbol(let name):
            Image(systemName: name)
                .resizable()
                .scaledToFit()
                .frame(width: Metrics.settingsMark * 0.8)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
                .accessibilityIdentifier(markId)
        }
    }
}
