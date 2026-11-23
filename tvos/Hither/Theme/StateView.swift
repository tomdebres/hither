import SwiftUI

/// What a page or a row shows in place of what it could not show (the review's X2 and F24), in tvOS's own way: the
/// system's `ContentUnavailableView` (a symbol, the words, the action) for nothing there, a failure and no right, and
/// the system's spinner with its words while something loads. Before it, each was a grey line and a button, drawn five
/// ways.
///
/// One action at most, and it takes focus, so focus never lands on nothing (REVIEW.md): Try Again after a failure, the
/// way out of an empty list. The view is the whole width it is given and a focus section, so Down from a control at
/// the left (a filter) reaches an action in the middle.
struct StateView<Action: View>: View {
    enum Kind {
        case loading, empty, failed, noRight

        /// The symbol each kind shows unless a page names its own.
        var symbol: String {
            switch self {
            case .loading: ""
            case .empty: "tray"
            case .failed: "exclamationmark.triangle"
            case .noRight: "lock"
            }
        }
    }

    let kind: Kind
    let words: String
    var detail: String?
    var symbol: String?
    let id: String
    @ViewBuilder var action: Action

    init(_ kind: Kind, _ words: String, detail: String? = nil, symbol: String? = nil, id: String, @ViewBuilder action: () -> Action) {
        self.kind = kind
        self.words = words
        self.detail = detail
        self.symbol = symbol
        self.id = id
        self.action = action()
    }

    var body: some View {
        Group {
            switch kind {
            case .loading:
                VStack(spacing: 24) {
                    ProgressView()
                    Text(words)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier(id)
            case .empty, .failed, .noRight:
                ContentUnavailableView {
                    Label(words, systemImage: symbol ?? kind.symbol)
                } description: {
                    if let detail { Text(detail) }
                } actions: {
                    // The system spaces its actions from a description (35 points); with none they sat right under the
                    // words, 6 points (Tom, M8 part 2: Quick Connect's failure). The same room either way.
                    action
                        .padding(.top, detail == nil ? Metrics.stateActionsLead : 0)
                }
                // One container holding the symbol, the words and the action: an identifier on the view itself reaches
                // each child, and the first is the symbol, read by its own name ("Warning").
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier(id)
            }
        }
        .frame(maxWidth: .infinity)
        .focusSection()
    }
}

extension StateView where Action == EmptyView {
    init(_ kind: Kind, _ words: String, detail: String? = nil, symbol: String? = nil, id: String) {
        self.init(kind, words, detail: detail, symbol: symbol, id: id) { EmptyView() }
    }
}
