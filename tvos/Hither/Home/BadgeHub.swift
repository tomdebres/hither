import HitherCore

/// A model that draws title cards and takes a title's new status on them (Android's `setBadge`).
@MainActor
protocol TakesBadges: AnyObject {
    func setBadge(_ key: String, _ badge: StatusBadge?)
}

/// Where a title's new status goes (Android's `HomeScreen.onBadge`): Discover's rows and every page on the stack that
/// draws cards, so the card a title page came from, and any other card of the title, shows what the page has just read
/// or a request has just made. Pages join as they are made and are held weakly, so a page that has gone takes nothing.
@MainActor
final class BadgeHub {
    private final class Weak {
        weak var model: TakesBadges?
        init(_ model: TakesBadges) { self.model = model }
    }

    private var models: [Weak] = []

    func join(_ model: TakesBadges) {
        models.removeAll { $0.model == nil }
        models.append(Weak(model))
    }

    func set(_ key: String, _ badge: StatusBadge?) {
        models.removeAll { $0.model == nil }
        for entry in models { entry.model?.setBadge(key, badge) }
    }
}
