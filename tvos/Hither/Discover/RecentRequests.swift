import HitherCore
import SwiftUI

/// One request on Recent Requests as a backdrop lockup (Seerr's RequestCard, Android's `RequestCardView`, in tvOS's
/// way, DECISIONS 191): the title's backdrop, 16:9, with the system's highlight; under it the title, then who asked
/// (where the account may see it, DECISIONS 118) and the request's status, its glyph and its word.
struct RequestCardView: View {
    let row: RequestRow
    let title: RequestTitle?
    let failed: Bool
    let requester: String?
    var onSelect: (TitleCard) -> Void = { _ in }

    var body: some View {
        Button { onSelect(RequestsKt.requestCard(row: row, title: title)) } label: {
            VStack(alignment: .leading, spacing: 0) {
                LockupImage(url: title?.backdrop, width: Metrics.backdropWidth, height: Metrics.backdropHeight)
                LockupCaption(title: name ?? " ", width: Metrics.backdropWidth, titleId: name == nil ? "" : "recent title") {
                    HStack(spacing: 20) {
                        if let requester { Requester(name: requester) }
                        if let badge = row.badge { StatusLabel(badge: badge) }
                    }
                }
                .redacted(reason: name == nil ? .placeholder : [])
            }
        }
        .buttonStyle(.borderless)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(name ?? "")
        .accessibilityIdentifier("recent \(row.key)")
    }

    private var name: String? {
        if let title { return title.title }
        return failed ? Words.unknownTitle : nil
    }
}

/// Who asked: the person symbol and the display name (Seerr's avatars are Gravatar or Plex pictures, another host:
/// DECISIONS 46).
private struct Requester: View {
    let name: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "person.fill")
            Text(name)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("recent requester")
    }
}

/// See More, the last lockup of every row Seerr links (Seerr's `ShowMoreCard`, Android's `SeeMoreCard`, DECISIONS 112,
/// 120): a poster's size, the next four titles' posters two by two, faint, the arrow over them, and "See More" under it.
struct SeeMoreCard: View {
    let row: DiscoverRow
    let posters: [String?]
    var onSelect: () -> Void = {}

    var body: some View {
        Button(action: onSelect) {
            VStack(alignment: .leading, spacing: 0) {
                SeeMoreFace(posters: posters)
                LockupCaption(title: Words.seeMore, width: Metrics.posterWidth)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(label)
        .accessibilityIdentifier("see more \(row.name)")
    }

    private var label: String {
        switch onEnum(of: row.source) {
        case .popularMovies: Words.seeMoreMovies
        case .popularSeries: Words.seeMoreSeries
        default: Words.seeMoreRow(row.title)
        }
    }
}

/// See More's picture: four posters at 30% on the placeholder grey, and the arrow in a circle over them.
private struct SeeMoreFace: View {
    let posters: [String?]

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: Metrics.imageRadius, style: .continuous)
        let inset: CGFloat = 8
        let width = (Metrics.posterWidth - inset * 3) / 2
        return ZStack {
            Tokens.placeholder
            VStack(spacing: inset) {
                ForEach(Array(stride(from: 0, to: posters.count, by: 2)), id: \.self) { start in
                    HStack(spacing: inset) {
                        ForEach(start..<min(start + 2, posters.count), id: \.self) { index in
                            ProxyImage(url: posters[index])
                                .frame(width: width, height: width * 1.5)
                                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                        }
                    }
                }
            }
            .opacity(0.3)
            Image(systemName: "arrow.right.circle.fill")
                .font(.title)
                .foregroundStyle(.white)
        }
        .frame(width: Metrics.posterWidth, height: Metrics.posterHeight)
        .clipShape(shape)
        .contentShape(.hoverEffect, shape)
        .hoverEffect(.highlight)
    }
}
