import SwiftUI

/// Long text on the TV, read as tvOS's Settings › Legal reads its own (the review's X7, F20): one view that takes focus
/// and moves through the text under the remote, never a column of paragraphs that each take a press. Down and Up, a
/// press or a swipe on the touch surface, turn most of a screen at a time, the last lines seen staying as the first of
/// the next; at either end they do nothing. It draws nothing of its own round the words, and no focus effect: the text
/// is the page.
///
/// SwiftUI's own, not the `UITextView` the review named: on tvOS 26.5 a text view inside SwiftUI never took focus, as a
/// bare view or as a child view controller that preferred it (DECISIONS 271).
///
/// The privacy text, a title's whole overview and a person's whole biography (the review's F9 reading stops, F20, F28).
/// XCUITest finds it by [id]: its label is the whole text; a debug build gives "focused" as its value while it has
/// focus.
struct ReadingView: View {
    let text: ReadingText
    let id: String

    @FocusState private var focused: Bool
    @State private var position = ScrollPosition(edge: .top)
    @State private var place = Place()

    /// Where the text is in its scroll view: how far down, how tall, how much shows.
    private struct Place: Equatable {
        var offset: CGFloat = 0
        var height: CGFloat = 0
        var shown: CGFloat = 0
    }

    /// The lines kept from one screen to the next.
    private static let kept: CGFloat = 80

    var body: some View {
        ScrollView(.vertical) {
            VStack(alignment: .leading, spacing: 16) {
                ForEach(Array(text.blocks.enumerated()), id: \.offset) { _, block in
                    ReadingBlockView(block: block)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollPosition($position)
        .onScrollGeometryChange(for: Place.self) { geometry in
            Place(offset: geometry.contentOffset.y + geometry.contentInsets.top, height: geometry.contentSize.height,
                  shown: geometry.containerSize.height)
        } action: { _, new in
            place = new
        }
        .scrollIndicators(.hidden)
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .defaultFocus($focused, true)
        .onMoveCommand { direction in
            switch direction {
            case .down: turn(by: 1)
            case .up: turn(by: -1)
            default: break
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text.plain)
        .accessibilityIdentifier(id)
        #if DEBUG
        .accessibilityValue(focused ? "focused" : "not focused")
        #endif
    }

    /// Most of a screen down (1) or up (-1), within the text.
    private func turn(by direction: CGFloat) {
        let page = max(place.shown - Self.kept, Self.kept)
        let last = max(0, place.height - place.shown)
        let target = min(max(place.offset + direction * page, 0), last)
        guard abs(target - place.offset) > 0.5 else { return }
        withAnimation(Motion.turn) { position.scrollTo(y: target) }
    }
}

/// A block of a `ReadingView`'s text.
enum ReadingBlock: Equatable {
    case heading(String)
    case paragraph(AttributedString, italic: Bool)
    case bullet(AttributedString)
}

private struct ReadingBlockView: View {
    let block: ReadingBlock

    var body: some View {
        switch block {
        case .heading(let words):
            Text(words)
                .font(.title3.bold())
                .padding(.top, 24)
        case .paragraph(let words, let italic):
            Text(words)
                .font(.body)
                .italic(italic)
        case .bullet(let words):
            // The bullet's lines hang under its words.
            HStack(alignment: .firstTextBaseline, spacing: 16) {
                Text("•").foregroundStyle(.secondary)
                Text(words)
            }
            .font(.body)
        }
    }
}

/// Text for `ReadingView`, built a block at a time: a heading (Title 3 bold), a paragraph (Body, italic where asked), a
/// bullet; a run bold where asked. tvOS's text styles only (DECISIONS 195).
struct ReadingText: Equatable {
    private(set) var blocks: [ReadingBlock] = []

    /// A run of a block, bold or not.
    struct Run {
        let text: String
        var bold = false
    }

    func heading(_ words: String) -> ReadingText {
        adding(.heading(words))
    }

    func paragraph(_ runs: [Run], italic: Bool = false) -> ReadingText {
        adding(.paragraph(Self.attributed(runs), italic: italic))
    }

    func paragraph(_ words: String, italic: Bool = false) -> ReadingText {
        paragraph([Run(text: words)], italic: italic)
    }

    func bullet(_ runs: [Run]) -> ReadingText {
        adding(.bullet(Self.attributed(runs)))
    }

    /// The words alone, a block a line: what VoiceOver reads, and the tests.
    var plain: String {
        blocks.map { block in
            switch block {
            case .heading(let words): words
            case .paragraph(let words, _): String(words.characters)
            case .bullet(let words): "• " + String(words.characters)
            }
        }.joined(separator: "\n")
    }

    /// Whether the words [words] are bold, where they are found first (for the tests).
    func isBold(_ words: String) -> Bool {
        for block in blocks {
            switch block {
            case .heading(let heading): if heading.contains(words) { return true }
            case .paragraph(let text, _), .bullet(let text):
                if let range = text.range(of: words) { return text[range].inlinePresentationIntent == .stronglyEmphasized }
            }
        }
        return false
    }

    private func adding(_ block: ReadingBlock) -> ReadingText {
        var next = self
        next.blocks.append(block)
        return next
    }

    private static func attributed(_ runs: [Run]) -> AttributedString {
        var result = AttributedString()
        for run in runs {
            var part = AttributedString(run.text)
            if run.bold { part.inlinePresentationIntent = .stronglyEmphasized }
            result += part
        }
        return result
    }
}

/// The whole of a text over the page it came from (the Apple TV app's "More"), as a page of its own (`PageScaffold`'s
/// pushed page: the title centred at the top margin as Settings' pages have theirs, the text under it at the 80-point
/// side) in a `ReadingView` that takes focus at once. Opaque black, so nothing behind it costs a blur on the Apple TV
/// HD. Menu closes it.
struct ReadingScreen: View {
    let title: String
    let text: ReadingText
    let id: String
    let textId: String

    /// As wide as reading stays comfortable at ten feet (about 80 characters of Body), from the left margin.
    static let width: CGFloat = 1400

    var body: some View {
        PageScaffold(.pushed(title), id: id) {
            ReadingView(text: text, id: textId)
                .frame(maxWidth: Self.width, alignment: .leading)
                .padding(.bottom, Metrics.topMargin)
        }
    }
}

/// A few lines of a longer text on a page, as the Apple TV app shows a description (the review's F20): one stop, the
/// words cut at [lines] with "More" beside the last where they go on, and Select opening the whole (`ReadingScreen`).
/// The system's plain button, the words in the system's own colours: at rest they are the page's words alone; with focus
/// the system lifts them onto its light platter and draws them dark. (Tried on tvOS 26.5: borderless drew no focus at all
/// on words; the card style drew its platter at rest too, a grey box round the overview; the highlight drew a hairline;
/// plain with words of a fixed white washed them out on the platter.) The words keep the page's line: the platter reaches
/// 24 points past them. [header] goes above the words inside the stop (the title page's tagline).
struct TextStop<Header: View>: View {
    let text: String
    var lines = 4
    let id: String
    let textId: String
    let onSelect: () -> Void
    @ViewBuilder var header: Header

    /// The words' height whole and as cut: "More" shows while the cut is shorter.
    @State private var whole: CGFloat = 0
    @State private var shown: CGFloat = 0

    private var cut: Bool { whole > shown + 1 }

    var body: some View {
        Button(action: onSelect) {
            VStack(alignment: .leading, spacing: 8) {
                header
                HStack(alignment: .lastTextBaseline, spacing: 24) {
                    Text(text)
                        .font(.body)
                        .lineLimit(lines)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { shown = $0 }
                        .background(alignment: .topLeading) {
                            Text(text)
                                .font(.body)
                                .fixedSize(horizontal: false, vertical: true)
                                .hidden()
                                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { whole = $0 }
                        }
                        .accessibilityIdentifier(textId)
                    if cut {
                        Text(Words.more)
                            .font(.caption.bold())
                            .foregroundStyle(.secondary)
                            .accessibilityHidden(true)
                    }
                }
            }
            .padding(.horizontal, Self.inset)
            .padding(.vertical, 16)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, -Self.inset)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(id)
    }

    /// The platter's room round the words.
    static var inset: CGFloat { 24 }
}

extension TextStop where Header == EmptyView {
    init(text: String, lines: Int = 4, id: String, textId: String, onSelect: @escaping () -> Void) {
        self.init(text: text, lines: lines, id: id, textId: textId, onSelect: onSelect) { EmptyView() }
    }
}
