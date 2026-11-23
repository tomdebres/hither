import SwiftUI

/// The one way a page meets the screen's edges (the review's X1). Every page goes through it, so the margins, the
/// ground and the title are set once, not page by page: five pages had doubled their margins (F6), three tab pages
/// repeated their tab as a heading, and every pushed page drew its own title, left and white, where tvOS centres the
/// system's in grey (F23).
///
/// - `.tab`: a tab's own page, under the system tab bar. No heading: the selected tab says what the page is, as in
///   tvOS's own apps. Its content sits in the safe area the bar leaves (80 points at the sides) and starts a shelf's
///   room (`Metrics.aboveShelf`, 36 points) under the bar, where Discover's first shelf starts: a notice of one or two
///   lines in the corner beside the bar ends above it (DECISIONS 252).
/// - `.shelves`: a tab's page of shelves (Discover), as `.tab`; the room under the bar is its first shelf header's own.
/// - `.pushed(title)`: a page opened from another, full screen without the tab bar (DECISIONS 193). Its title sits where
///   tvOS's Settings puts its own, in its look: centred, secondary, Title 2 bold, at the top margin, with the content
///   under it at tvOS's 80-point sides. Not the system's `navigationTitle`: with the tab bar hidden, tvOS 26 keeps the
///   bar's 157 points and stacks the navigation bar under them, so the title came 248 points down and the content 302
///   (DECISIONS 243). [trailing] sits at the right of the title's line (the Movies page's Sort and Filters). A page with
///   no title (a title's page, a person's) has the whole screen and its own margins.
/// - `.screen`: a screen before the frame (starting, sign-in, the server not answering), in tvOS's safe area.
///
/// `bleed` names the edges a tab page's or a titled page's content runs to, keeping its own margins inside
/// (`Metrics`): a shelf or a grid runs to the sides, so a lifted lockup is never cut and the row scrolls to the screen's
/// edge. Nothing else pads a page from the screen's edges.
struct PageScaffold<Content: View, Trailing: View>: View {
    enum Kind {
        case tab
        case shelves
        case pushed(String?)
        case screen

        /// The room between the tab bar and a tab page's content.
        var underTheBar: CGFloat {
            switch self {
            case .tab: Metrics.aboveShelf
            case .shelves, .pushed, .screen: 0
            }
        }
    }

    let kind: Kind
    let bleed: Edge.Set
    let id: String
    let content: Content
    let trailing: Trailing

    init(_ kind: Kind, bleed: Edge.Set = [], id: String, @ViewBuilder content: () -> Content, @ViewBuilder trailing: () -> Trailing) {
        self.kind = kind
        self.bleed = bleed
        self.id = id
        self.content = content()
        self.trailing = trailing()
    }

    var body: some View {
        page
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            // The ground, under everything and to every edge, once.
            .background(Tokens.background.ignoresSafeArea())
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(id)
    }

    @ViewBuilder
    private var page: some View {
        switch kind {
        case .tab, .shelves, .screen:
            content
                .padding(.top, kind.underTheBar)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .ignoresSafeArea(edges: bleed)
        case .pushed(let title):
            Group {
                if let title {
                    VStack(alignment: .leading, spacing: 0) {
                        PageTitle(text: title)
                            .overlay(alignment: .trailing) {
                                // The whole line is the section, so Up from any lockup under it reaches the controls (6a's
                                // lesson: a focus section is only as wide as its content).
                                HStack(spacing: 24) { trailing }
                                    .frame(maxWidth: .infinity, alignment: .trailing)
                                    .focusSection()
                            }
                            .padding(.horizontal, Metrics.sideMargin)
                            .padding(.top, Metrics.topMargin)
                            .padding(.bottom, Metrics.belowPageTitle)
                        content
                            .padding(.horizontal, bleed.contains(.horizontal) ? 0 : Metrics.sideMargin)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                    }
                } else {
                    content
                }
            }
            // The safe area here is the hidden tab bar's: tvOS's margins are set above, once.
            .ignoresSafeArea()
            // Full screen, as the Apple TV app opens a title: Menu takes the page away and the bar comes back.
            .toolbar(.hidden, for: .tabBar)
        }
    }
}

extension PageScaffold where Trailing == EmptyView {
    init(_ kind: Kind, bleed: Edge.Set = [], id: String, @ViewBuilder content: () -> Content) {
        self.init(kind, bleed: bleed, id: id, content: content) { EmptyView() }
    }
}

/// A pushed page's title, as tvOS's Settings draws its own: Title 2 bold, secondary, centred, one line.
private struct PageTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.title2.bold())
            .foregroundStyle(.secondary)
            .lineLimit(1)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("page title")
            .frame(maxWidth: .infinity)
    }
}
