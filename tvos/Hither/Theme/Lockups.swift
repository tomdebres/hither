import HitherCore
import SwiftUI

// tvOS's lockups (Tom's native look, DECISIONS 189, 191): a picture with its caption under it in a borderless button.
// The picture takes the system's highlight (it lifts, tilts with the remote and catches the light) and the caption
// stays under it, as the Apple TV app's lockups do. Nothing of Hither's is drawn per frame on focus: the caption's
// colour and its step down change once as focus comes and goes.

/// A lockup's picture: the server's picture (its proxy, or TMDB's image server), cropped to [size], its corners rounded as tvOS rounds a
/// lockup's, with the system's highlight. [overlay] sits on it (a status glyph) and lifts with it.
struct LockupImage<Overlay: View>: View {
    let url: String?
    let width: CGFloat
    let height: CGFloat
    @ViewBuilder var overlay: Overlay

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Metrics.imageRadius, style: .continuous)
        ZStack(alignment: .topTrailing) {
            ProxyImage(url: url)
            overlay
        }
        .frame(width: width, height: height)
        .clipShape(shape)
        .contentShape(.hoverEffect, shape)
        .hoverEffect(.highlight)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("card image")
    }
}

extension LockupImage where Overlay == EmptyView {
    init(url: String?, width: CGFloat, height: CGFloat) {
        self.init(url: url, width: width, height: height) { EmptyView() }
    }
}

/// A lockup's caption, under its picture: the title in Caption, a line under it in Caption 2; secondary at rest and
/// primary with focus, and a step further down while the picture is lifted, so the two never touch. Only the step
/// animates; the words change colour at once (the review's F32: animating both cross-faded the recoloured words as they
/// slid, and for 200 ms the caption showed twice, 12 points apart).
struct LockupCaption<Subtitle: View>: View {
    let title: String
    var width: CGFloat
    var centred = false
    var titleLines = 1
    var titleId = "card title"
    @ViewBuilder var subtitle: Subtitle
    @Environment(\.isFocused) private var focused

    var body: some View {
        VStack(alignment: centred ? .center : .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(focused ? .primary : .secondary)
                // The colour changes at once, whatever animates round it.
                .transaction { $0.animation = nil }
                .lineLimit(titleLines)
                .multilineTextAlignment(centred ? .center : .leading)
                .accessibilityIdentifier(titleId)
            subtitle
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(width: width, alignment: centred ? .center : .leading)
        .padding(.top, 16)
        .offset(y: focused ? 12 : 0)
        .animation(Motion.caption, value: focused)
    }
}

extension LockupCaption where Subtitle == EmptyView {
    init(title: String, width: CGFloat, centred: Bool = false, titleLines: Int = 1) {
        self.init(title: title, width: width, centred: centred, titleLines: titleLines) { EmptyView() }
    }
}

/// What a poster lockup says under its title: the year, and the kind of title where its row mixes films and series
/// (Seerr's MOVIE and SERIES chips, as words: DECISIONS 191).
func lockupSubtitle(_ card: TitleCard, mixed: Bool) -> String? {
    let kind = mixed ? (card.type == .movie ? Words.movie : Words.seriesOne) : nil
    return Formats.join([card.year, kind])
}

/// Whether [cards] mix films and series: their lockups then say which each is.
func mixesKinds(_ cards: some Sequence<TitleCard>) -> Bool {
    var seen = Set<String>()
    for card in cards { seen.insert(card.type.name) }
    return seen.count > 1
}

/// A TitleCard as a poster lockup: the poster with the title's status glyph in its corner, the title and the year
/// under it. Select opens the title; VoiceOver reads the title, then its overview as the hint (what Seerr's card showed
/// once focus rested, DECISIONS 191).
///
/// A list's lockup (My Requests, Issues, the queue: the review's X3, DECISIONS 260) has a status caption under the title
/// in place of the year ([status]: the status's glyph and word, and when), and no glyph on the poster, which would say
/// the title's library status beside the request's. Until a list has the title's name, the title is a placeholder bar.
struct TitleCardView: View {
    let card: TitleCard
    var mixed = false
    /// Words under the title in place of the year (a person's part in a credit).
    var subtitle: String?
    /// A list's lockup: its status caption, in place of the year and the poster's glyph.
    var status: LockupStatus?
    /// The lockup's identifier where the title's key is not unique (a list: two requests or issues for one title).
    var id: String?
    /// What VoiceOver reads after the title: a list's details (the seasons, who, what the reporter wrote).
    var details: String?
    var onSelect: () -> Void = {}

    var body: some View {
        Button(action: onSelect) {
            VStack(alignment: .leading, spacing: 0) {
                LockupImage(url: card.poster, width: Metrics.posterWidth, height: Metrics.posterHeight) {
                    if status == nil, let badge = card.badge {
                        StatusGlyph(badge: badge).padding(12)
                    }
                }
                LockupCaption(title: named ? card.title : Words.loadingTitle, width: Metrics.posterWidth, titleId: named ? "card title" : "") {
                    if let status {
                        LockupStatusLines(status: status)
                    } else if let subtitle = subtitle ?? lockupSubtitle(card, mixed: mixed) {
                        Text(subtitle).accessibilityIdentifier("card subtitle")
                    }
                }
                .redacted(reason: named ? [] : .placeholder)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(card.title)
        .accessibilityValue(Formats.join([status?.words, status?.line, details]) ?? "")
        .accessibilityHint(card.overview ?? "")
        .accessibilityIdentifier(id ?? "card \(card.key)")
    }

    /// The title's name has come (a list reads it after the list itself).
    private var named: Bool { !card.title.isEmpty }
}

/// A person as a portrait lockup (Cast & Crew, a person in search): the photo in a circle, or their initials where
/// there is none, the name and the part under it. Select opens the person's page.
struct PersonCardView: View {
    let id: String
    let name: String
    let part: String?
    let photo: String?
    var onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(spacing: 0) {
                Portrait(name: name, photo: photo)
                LockupCaption(title: name, width: Metrics.portrait + 40, centred: true, titleLines: 2) {
                    if let part { Text(part) }
                }
            }
        }
        // The system's lockup with a circular border shape (the review's F25): the portrait takes the system's highlight
        // in its circle. Before `buttonBorderShape(.circle)`, `.borderless` and the highlight each drew a rounded square
        // round the circle (DECISIONS 191).
        .buttonStyle(.borderless)
        .buttonBorderShape(.circle)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(name)
        .accessibilityValue(part ?? "")
        .accessibilityIdentifier(id)
    }
}

/// A person's picture in a circle, or their initials where there is none (as tvOS draws a person with no picture), with
/// the system's highlight in the circle: it lifts, tilts with the remote and catches the light, as a poster does.
private struct Portrait: View {
    let name: String
    let photo: String?

    var body: some View {
        ZStack {
            Tokens.placeholder
            if photo == nil {
                Text(initials)
                    .font(.title2)
                    .foregroundStyle(.secondary)
            } else {
                ProxyImage(url: photo)
            }
        }
        .frame(width: Metrics.portrait, height: Metrics.portrait)
        .clipShape(Circle())
        .contentShape(.hoverEffect, Circle())
        .hoverEffect(.highlight)
        .accessibilityIdentifier("card image")
    }

    /// The first letters of the first and last names.
    private var initials: String {
        let words = name.split(separator: " ")
        let letters = [words.first, words.count > 1 ? words.last : nil].compactMap { $0?.first }
        return String(letters).uppercased()
    }
}

/// A lockup's place while its row loads: the picture's shape in the placeholder grey, and a caption's line. Still, so
/// a loading page costs nothing per frame.
struct PlaceholderCard: View {
    var width: CGFloat = Metrics.posterWidth
    var height: CGFloat = Metrics.posterHeight

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            RoundedRectangle(cornerRadius: Metrics.imageRadius, style: .continuous)
                .fill(Tokens.placeholder)
                .frame(width: width, height: height)
            Capsule().fill(Tokens.placeholder).frame(width: width * 0.6, height: 20)
        }
        .accessibilityHidden(true)
    }
}

/// A shelf's header: Headline, in the words Seerr gives the row, over its lockups.
struct ShelfTitle: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.headline)
            .foregroundStyle(.primary)
            .lineLimit(1)
            .padding(.top, Metrics.aboveShelf)
            .padding(.bottom, Metrics.belowShelfTitle)
            .padding(.leading, Metrics.sideMargin)
            .accessibilityAddTraits(.isHeader)
            .accessibilityIdentifier("row title")
    }
}

/// A shelf's lockups side by side, scrolling sideways as focus moves along them; the lifted picture is never clipped.
/// Lazy where a row can run long (Discover's, a grid's, the related titles); a short one (seasons, Cast & Crew) is built
/// whole, so every lockup is there for VoiceOver and the tests from the start.
struct Shelf<Content: View>: View {
    var height: CGFloat = Metrics.posterHeight + Metrics.captionHeight
    var lazy = true
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView(.horizontal) {
            Group {
                if lazy {
                    LazyHStack(alignment: .top, spacing: Metrics.lockupGap) { content }
                } else {
                    HStack(alignment: .top, spacing: Metrics.lockupGap) { content }
                }
            }
            .padding(.horizontal, Metrics.sideMargin)
        }
        .scrollClipDisabled()
        .scrollIndicators(.hidden)
        .frame(height: height)
    }
}
