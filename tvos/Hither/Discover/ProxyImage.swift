import ImageIO
import SwiftUI
import UIKit

/// A picture from the server's image proxy, cropped to fill, on the placeholder grey until it comes (and where there is
/// none): the grey a loading row's placeholders are, so nothing steps in brightness as lockups replace them (the
/// review's F36). A picture that has to be fetched or decoded fades in over the grey; one already in memory is there at
/// once.
///
/// Decoded at the size it is drawn, never the file's (Android's Coil does the same with `size(...)`): the tester's
/// Apple TV HD has 2 GB, and a cast photo arrives 600 x 900 for a 152-point circle, a backdrop 1920 x 800. ImageIO makes
/// the bitmap straight from the file at the pixels the frame needs (its points times the screen's scale), off the main
/// thread, and keeps the few hundred most recent in memory.
struct ProxyImage: View {
    let url: String?

    var body: some View {
        // The picture fills the space it is given and never sizes it: a wider backdrop must not widen its card.
        Tokens.placeholder
            .overlay {
                if let url, let address = URL(string: url) {
                    GeometryReader { geometry in
                        Downsampled(url: address, size: geometry.size)
                    }
                }
            }
            .clipped()
    }
}

private struct Downsampled: View {
    let url: URL
    let size: CGSize
    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size.width, height: size.height)
                    .transition(.opacity)
            }
        }
        .task(id: Key(url: url, width: Int(size.width * scale), height: Int(size.height * scale))) {
            let pixels = CGSize(width: size.width * scale, height: size.height * scale)
            guard pixels.width > 0, pixels.height > 0 else { return }
            if let kept = ImageLoader.shared.kept(url, filling: pixels) {
                image = kept
                return
            }
            let loaded = await ImageLoader.shared.image(url, filling: pixels)
            withAnimation(Motion.picture) { image = loaded }
        }
    }

    private struct Key: Hashable {
        let url: URL
        let width: Int
        let height: Int
    }
}

/// Fetches pictures from the image proxy and decodes each at the size asked for. Its own URLSession keeps no cookies
/// (the proxy takes no session, REVIEW.md) and caches the files on disk as URLCache does.
final class ImageLoader: @unchecked Sendable {
    static let shared = ImageLoader()

    private let session: URLSession
    private let decoded = NSCache<NSString, UIImage>()

    private init() {
        let configuration = URLSessionConfiguration.default
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCache = URLCache(memoryCapacity: 8 << 20, diskCapacity: 128 << 20)
        session = URLSession(configuration: configuration)
        decoded.countLimit = 300
    }

    /// The picture at [url], decoded just large enough to fill [pixels] (cropped to fill, as it is drawn); nil if it
    /// will not come or is not a picture.
    func image(_ url: URL, filling pixels: CGSize) async -> UIImage? {
        let key = Self.key(url, pixels)
        if let hit = decoded.object(forKey: key) { return hit }
        guard let (data, response) = try? await session.data(from: url),
              (response as? HTTPURLResponse)?.statusCode ?? 200 < 400 else { return nil }
        let image = await Task.detached(priority: .utility) { Self.decode(data, filling: pixels) }.value
        if let image { decoded.setObject(image, forKey: key) }
        return image
    }

    /// The picture at [url] for [pixels] if it is already decoded in memory, at once; nil if it has to be fetched.
    func kept(_ url: URL, filling pixels: CGSize) -> UIImage? {
        decoded.object(forKey: Self.key(url, pixels))
    }

    private static func key(_ url: URL, _ pixels: CGSize) -> NSString {
        "\(url.absoluteString)@\(Int(pixels.width))x\(Int(pixels.height))" as NSString
    }

    /// ImageIO's thumbnail at the longest side that still covers [pixels] once the picture is cropped to fill them.
    private static func decode(_ data: Data, filling pixels: CGSize) -> UIImage? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, options),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? CGFloat,
              let height = properties[kCGImagePropertyPixelHeight] as? CGFloat,
              width > 0, height > 0 else { return nil }
        let fill = min(1, max(pixels.width / width, pixels.height / height))
        let longest = max(width, height) * fill
        let thumbnail = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(longest.rounded(.up)),
        ] as CFDictionary
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, thumbnail) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}
