import Foundation
import TVServices

/// Hither's Top Shelf (standing in for Android's "Ready to Watch" channel and Watch Next, DECISIONS 199): the titles the
/// app left in the shared folder after its last look, as one section of posters, each opening its title in Hither. The
/// extension reads that file and nothing else: it holds no session and asks no server.
final class ContentProvider: TVTopShelfContentProvider {
    override func loadTopShelfContent(completionHandler: @escaping (TVTopShelfContent?) -> Void) {
        guard let folder = ShelfFile.folder else {
            completionHandler(nil)
            return
        }
        completionHandler(ShelfFile.content(ShelfFile.read(from: folder), folder: folder))
    }
}
