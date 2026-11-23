import Foundation
import TVServices

// Shared by the app and its Top Shelf extension (`tvos/TopShelf`): both compile this file. Keep it to Foundation and
// TVServices: the extension links nothing else (not HitherCore), so it stays small and never holds the session.

/// One title on the Top Shelf: its key (`movie:550`), its name, the link it opens in Hither (`hither://title/movie/550`,
/// core's `TitleLink.uri`) and its poster's file in the shared folder (none until it has been fetched).
struct ShelfItem: Codable, Equatable {
    let key: String
    let title: String
    let link: String
    let image: String?
}

/// The Top Shelf's titles as Hither leaves them for its extension (DECISIONS 199): a JSON file and the posters beside
/// it, in the App Group's container, which only Hither and its extension can read. The app writes it after each look;
/// the extension only reads it.
enum ShelfFile {
    /// The App Group Hither shares with its Top Shelf extension, and nothing else.
    static let group = "group.com.tomdebres.heerr"
    static let name = "shelf.json"
    /// The section's heading: Android's channel name, in title case as tvOS titles a shelf (the review's F30).
    static let heading = "Ready to Watch"

    /// `TopShelf/` in the group's container; nil where the group is not granted (an unsigned build).
    static var folder: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?.appendingPathComponent("TopShelf", isDirectory: true)
    }

    static func read(from folder: URL) -> [ShelfItem] {
        guard let data = try? Data(contentsOf: folder.appendingPathComponent(name)) else { return [] }
        return (try? JSONDecoder().decode([ShelfItem].self, from: data)) ?? []
    }

    static func write(_ items: [ShelfItem], to folder: URL) throws {
        try JSONEncoder().encode(items).write(to: folder.appendingPathComponent(name), options: .atomic)
    }

    /// What the Top Shelf shows for [items]: one section, "Ready to Watch", of poster items in the order given (newest
    /// first), each opening its link with Select and with Play; nil with none, so tvOS shows the app's own image. An
    /// item whose link is not Hither's title link is left out.
    static func content(_ items: [ShelfItem], folder: URL) -> TVTopShelfContent? {
        let shelf: [TVTopShelfSectionedItem] = items.compactMap { item in
            guard item.link.hasPrefix("hither://title/"), let link = URL(string: item.link) else { return nil }
            let entry = TVTopShelfSectionedItem(identifier: item.key)
            entry.title = item.title
            entry.imageShape = .poster
            if let image = item.image {
                let file = folder.appendingPathComponent(image)
                // One URL per scale: tvOS keys an image by its exact traits, so a URL set for both at once is found for
                // neither alone.
                entry.setImageURL(file, for: .screenScale1x)
                entry.setImageURL(file, for: .screenScale2x)
            }
            entry.displayAction = TVTopShelfAction(url: link)
            entry.playAction = TVTopShelfAction(url: link)
            return entry
        }
        guard !shelf.isEmpty else { return nil }
        let section = TVTopShelfItemCollection(items: shelf)
        section.title = heading
        return TVTopShelfSectionedContent(sections: [section])
    }
}
