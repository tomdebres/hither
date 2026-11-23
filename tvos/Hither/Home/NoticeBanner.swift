import HitherCore
import Observation
import SwiftUI
import UIKit

/// The notices on returning (Android's `HomeAlerts` queue, M4, DECISIONS 79): each change to the user's requests since
/// the last look ("Approved: Coco", "Ready to Watch"), one at a time, each for four seconds. Nothing else: what a press
/// did shows where it was pressed, and what went wrong is an alert (Tom's D4, the review's F18). tvOS has no surface of
/// its own for news that arrives while Hither was away, so these keep the one banner.
@MainActor
@Observable
final class Notices {
    struct Notice: Identifiable, Equatable {
        let id = UUID()
        /// Seerr's word for the change ("Approved"), its glyph and Seerr's colour for it, and the title.
        let word: String
        let symbol: String
        let kind: BadgeKind
        let title: String?

        /// What VoiceOver reads, and the tests: "Approved: Coco".
        var words: String { [word, title].compactMap { $0 }.joined(separator: ": ") }
    }

    /// Notices still to show, the one showing first.
    private(set) var queue: [Notice] = []

    /// How long one stays, fades apart (Android's `NOTICE_MS`).
    static let shown: Duration = .seconds(4)

    /// A change to one of the user's requests, in Seerr's words (Android's `NoticeCard`).
    func notice(_ change: RequestChange, title: String?) {
        let notice: Notice = switch change {
        case .available: Notice(word: Words.readyToWatch, symbol: "checkmark.circle.fill", kind: .success, title: title)
        case .available4K: Notice(word: Words.readyToWatch4k, symbol: "checkmark.circle.fill", kind: .success, title: title)
        case .approved: Notice(word: Words.noticeApproved, symbol: "hand.thumbsup.circle.fill", kind: .primary, title: title)
        case .declined: Notice(word: Words.noticeDeclined, symbol: "xmark.circle.fill", kind: .danger, title: title)
        }
        queue.append(notice)
    }

    func done(_ notice: Notice) {
        queue.removeAll { $0.id == notice.id }
    }
}

/// What the banner is drawn on (Tom's D4): the system's Liquid Glass where tvOS has it, it floats above the page, which
/// is where glass belongs (the `liquid-glass` skill); an opaque surface where glass would not hold the words up: with
/// Reduce Transparency on, before tvOS 26, and on the Apple TV HD (the tester's: an A8, where glass may draw differently
/// or not at all, and nothing may depend on it for legibility: the milestone's decision 5).
enum BannerSurface: String, Equatable {
    case glass, opaque

    /// The Apple TV HD's model identifier (2015, A8): the one tvOS 26 still runs on without the newer GPU.
    static let appleTVHD = "AppleTV5,3"

    static func choose(reduceTransparency: Bool, model: String, glassAvailable: Bool) -> BannerSurface {
        glassAvailable && !reduceTransparency && model != appleTVHD ? .glass : .opaque
    }

    /// The device's model identifier ("AppleTV14,1"; the simulator's is the device it stands for). A debug build takes
    /// `HITHER_DEVICE_MODEL` instead, so a test can draw what the Apple TV HD draws.
    static var model: String {
        let environment = ProcessInfo.processInfo.environment
        #if DEBUG
        if let model = environment["HITHER_DEVICE_MODEL"], !model.isEmpty { return model }
        #endif
        if let simulated = environment["SIMULATOR_MODEL_IDENTIFIER"], !simulated.isEmpty { return simulated }
        var system = utsname()
        uname(&system)
        return withUnsafeBytes(of: &system.machine) { bytes in
            String(decoding: bytes.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    static var glassAvailable: Bool {
        if #available(tvOS 26, *) { return true }
        return false
    }
}

/// The notice showing (Android's `NoticeCard`, the way tvOS would draw one): a small capsule in the top-right corner of
/// the safe area (y = 60, its right edge at 1840), sized to its words and no wider than the room a five-tab bar leaves
/// beside it, so it lies over neither the bar nor the page's content (the review's F10). The change's glyph in Seerr's
/// colour, its word, and the title under it. It takes no focus and nothing waits on it.
struct NoticeBanner: View {
    let notices: Notices

    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    /// The widest the banner grows: the corner right of the tab bar (Discover, Requests, Issues, Search and the account
    /// end at x = 1405 with a short name), less a gap.
    static let widest: CGFloat = 420

    var body: some View {
        ZStack(alignment: .topTrailing) {
            if let notice = notices.queue.first {
                banner(notice)
                    .id(notice.id)
                    .transition(.opacity)
                    .task(id: notice.id) {
                        try? await Task.sleep(for: Notices.shown)
                        withAnimation(.easeOut(duration: 0.3)) { notices.done(notice) }
                    }
            }
        }
        .animation(.easeOut(duration: 0.3), value: notices.queue.first)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
        .allowsHitTesting(false)
    }

    private func banner(_ notice: Notices.Notice) -> some View {
        let surface = BannerSurface.choose(reduceTransparency: reduceTransparency, model: BannerSurface.model, glassAvailable: BannerSurface.glassAvailable)
        return HStack(spacing: 16) {
            Glyph(symbol: notice.symbol, kind: notice.kind, size: .title3)
            VStack(alignment: .leading, spacing: 2) {
                Text(notice.word)
                    .font(.headline)
                if let title = notice.title, !title.isEmpty {
                    Text(title)
                        .font(.callout)
                        .lineLimit(1)
                }
            }
        }
        .foregroundStyle(.white)
        .padding(.leading, 20)
        .padding(.trailing, 32)
        .padding(.vertical, 16)
        // The surface is drawn at the words' size; the room the banner may take is set after it, so a short one stays short.
        .accessibilityHidden(true)
        .modifier(BannerBackground(surface: surface))
        // One element the capsule's size, laid over it: on the words themselves its frame was theirs alone, the glass
        // round them being no part of it.
        .overlay {
            Color.clear
                .accessibilityElement()
                .accessibilityLabel(notice.words)
                .accessibilityIdentifier("notice")
                #if DEBUG
                // What it is drawn on, for a test to read (a release build carries nothing).
                .accessibilityValue(surface.rawValue)
                #endif
        }
        .frame(maxWidth: Self.widest, alignment: .trailing)
    }
}

/// The banner's capsule: glass, or the opaque surface.
private struct BannerBackground: ViewModifier {
    let surface: BannerSurface

    func body(content: Content) -> some View {
        if surface == .glass, #available(tvOS 26, *) {
            content.glassEffect(.regular, in: Capsule())
        } else {
            content.background(Capsule().fill(Tokens.surface))
        }
    }
}
