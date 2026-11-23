import Foundation
import HitherCore
import UIKit

/// The links Hither hands to other apps on the Apple TV, tvOS's counterpart of Android's intents (Tom's M7 decision 1,
/// DECISIONS 153): a trailer to the YouTube app, a title to the Plex app. Hither fetches nothing from either; it builds
/// the link from what core has already checked and asks tvOS to open it.
enum AppLinks {
    /// The YouTube app's own scheme for one video: `youtube://watch/<id>` (the form the tvOS app is reported to open;
    /// tvOS has no browser for a web link). The id is core's, checked as YouTube's eleven characters.
    static func trailer(_ key: String) -> URL? {
        // core's youtubeWatchLink refuses anything but a YouTube id; only then is it put in a link.
        guard MediaFactsKt.youtubeWatchLink(key: key) != nil else { return nil }
        return URL(string: "youtube://watch/\(key)")
    }

    /// The Plex app's link for a copy, in the form Seerr gives for iOS (`mediaInfo.iOSPlexUrl`, Seerr's
    /// `Media.setPlexUrls`): `plex://preplay/?metadataKey=%2Flibrary%2Fmetadata%2F<ratingKey>&server=<machineId>`.
    /// Rebuilt from core's checked [PlayAction] (a machine id of letters and digits, a numeric rating key), so nothing
    /// else the server sent reaches a link the TV opens.
    static func plex(_ play: PlayAction) -> URL? {
        // core's link: plex://libraries/<machineId>/unmatched/[tv/]%2Flibrary%2Fmetadata%2F<ratingKey>
        let prefix = "plex://libraries/"
        let marker = "%2Flibrary%2Fmetadata%2F"
        guard play.link.hasPrefix(prefix), let keyRange = play.link.range(of: marker, options: .backwards) else { return nil }
        let rest = play.link.dropFirst(prefix.count)
        guard let slash = rest.firstIndex(of: "/") else { return nil }
        let server = String(rest[..<slash])
        let key = String(play.link[keyRange.upperBound...])
        guard !server.isEmpty, server.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }),
              !key.isEmpty, key.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return URL(string: "plex://preplay/?metadataKey=\(marker)\(key)&server=\(server)")
    }
}

/// Opening another app: whether one on this Apple TV takes a link, and handing it over. `LSApplicationQueriesSchemes`
/// lists `youtube` and `plex`, so tvOS answers [canOpen] for them and nothing else.
@MainActor
enum AppOpener {
    /// Whether an app on this Apple TV opens [url]: as Android asks the package manager, so there is never a button
    /// that opens nothing (DECISIONS 68, 98).
    static func canOpen(_ url: URL) -> Bool {
        #if DEBUG
        // The simulator has neither app: a debug build can be told they are there (HITHER_APPS="youtube,plex"), so the
        // buttons show and pressing one reaches the open below, which the simulator refuses.
        if let apps = ProcessInfo.processInfo.environment["HITHER_APPS"], let scheme = url.scheme,
           apps.split(separator: ",").contains(Substring(scheme)) {
            return true
        }
        #endif
        return UIApplication.shared.canOpenURL(url)
    }

    /// Hands [url] to its app; false when tvOS would not open it (the app went since the page was read).
    static func open(_ url: URL) async -> Bool {
        await UIApplication.shared.open(url)
    }
}
