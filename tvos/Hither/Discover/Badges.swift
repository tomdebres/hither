import HitherCore
import SwiftUI

extension BadgeKind {
    /// Seerr's status colour for the kind: green, indigo, yellow, red (DECISIONS 189: on the glyphs only).
    var colour: Color {
        switch self {
        case .success: Tokens.success
        case .primary: Tokens.primary
        case .warning: Tokens.warning
        case .danger: Tokens.danger
        }
    }

    /// The glyph's mark on the kind's disc: white where white reads (indigo 4.5:1, red 3.8:1), black on the light discs
    /// where it cannot (yellow 1.9:1 white, 10.9:1 black; green 2.3:1 white, 9.2:1 black). The review's F27: a white
    /// bell on yellow and a white tick on green could not be read.
    var mark: Color {
        switch self {
        case .warning, .success: .black
        case .primary, .danger: .white
        }
    }
}

extension StatusBadge {
    /// The SF Symbol that says the status at a glance, in place of Seerr's pill (Tom's native look, DECISIONS 192):
    /// Seerr's own icons where its mini badge has one (the check, the minus, the clock, the bell), the system's
    /// nearest for the rest. A download under way is the arrow down.
    var symbol: String {
        if inProgress { return "arrow.down.circle.fill" }
        switch word {
        case .available: return "checkmark.circle.fill"
        case .partiallyAvailable: return "minus.circle.fill"
        case .requested: return "clock.circle.fill"
        case .processing: return "arrow.down.circle.fill"
        case .pending: return "bell.circle.fill"
        case .declined: return "xmark.circle.fill"
        case .failed: return "exclamationmark.circle.fill"
        case .deleted: return "trash.circle.fill"
        case .blocklisted: return "eye.slash.circle.fill"
        case .notRequested: return "plus.circle.fill"
        default: return "questionmark.circle.fill"
        }
    }
}

/// A status as a glyph: its symbol on Seerr's colour, sized by a text style; VoiceOver reads its word ("4K
/// Requested"). On a poster's corner it is all there is; elsewhere `StatusLabel` sets the word beside it.
struct StatusGlyph: View {
    let badge: StatusBadge
    var size: Font = .body

    var body: some View {
        Glyph(symbol: badge.symbol, kind: badge.kind, size: size)
            .accessibilityLabel(badge.text)
            .accessibilityIdentifier("badge \(badge.text)")
    }
}

/// Every status colour Hither draws, drawn one way (the review's X10 and F27): a `.circle.fill` symbol, its disc in the
/// kind's colour and its mark in the colour that reads on it (`BadgeKind.mark`), with a thin dark ring round the disc so
/// it keeps its edge on a picture of its own colour (F35: a green disc on a green poster had none). Statuses, the
/// waiting count and an issue's state all use it: yellow was drawn three ways (a white bell on a disc, a black mark on
/// a triangle, a clock with its hands cut out).
struct Glyph: View {
    let symbol: String
    let kind: BadgeKind
    var size: Font = .body

    var body: some View {
        Image(systemName: symbol)
            .symbolRenderingMode(.palette)
            .foregroundStyle(kind.mark, kind.colour)
            .font(size)
            // The ring: a disc of black at 40% a point and a half wider than the symbol's own, under it. Still, so it
            // costs nothing per frame; on the page's black it is not seen.
            .background { Circle().fill(.black.opacity(0.4)).padding(-1.5) }
    }
}

/// A status with its word: the glyph, then the word in the line's own style (the title page's status line, a
/// season, a request). One element for VoiceOver, read as the word.
struct StatusLabel: View {
    let badge: StatusBadge

    var body: some View {
        HStack(spacing: 10) {
            StatusGlyph(badge: badge)
            Text(badge.text)
                .lineLimit(1)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(badge.text)
        .accessibilityIdentifier("status \(badge.text)")
    }
}
