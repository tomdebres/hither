import SwiftUI
import UIKit

/// Every press on the remote, and every touch on its surface, anywhere in Hither, for the admin's idle clock (Android's
/// `MainActivity.onUserInteraction`, DECISIONS 93): a gesture recognizer on the window that notes each press it is
/// offered and declines it, so it never takes part in what the press does.
struct PressWatcher: UIViewRepresentable {
    let onPress: () -> Void

    func makeUIView(context: Context) -> WatchView { WatchView(onPress: onPress) }

    func updateUIView(_ view: WatchView, context: Context) { view.watcher.onPress = onPress }

    final class WatchView: UIView {
        let watcher: Watcher
        private weak var watched: UIWindow?

        init(onPress: @escaping () -> Void) {
            watcher = Watcher(onPress: onPress)
            super.init(frame: .zero)
            isUserInteractionEnabled = false
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("not from a storyboard") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window, window !== watched else { return }
            watched?.removeGestureRecognizer(watcher.recognizer)
            window.addGestureRecognizer(watcher.recognizer)
            watched = window
        }
    }

    /// The recognizer's delegate: it is asked about every press and touch the window gets, notes it, and says no.
    final class Watcher: NSObject, UIGestureRecognizerDelegate {
        var onPress: () -> Void
        let recognizer = UITapGestureRecognizer()

        init(onPress: @escaping () -> Void) {
            self.onPress = onPress
            super.init()
            recognizer.delegate = self
            recognizer.cancelsTouchesInView = false
            recognizer.delaysTouchesBegan = false
            recognizer.delaysTouchesEnded = false
            recognizer.allowedPressTypes = [
                UIPress.PressType.upArrow, .downArrow, .leftArrow, .rightArrow, .select, .menu, .playPause,
            ].map { NSNumber(value: $0.rawValue) }
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive press: UIPress) -> Bool {
            onPress()
            return false
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            onPress()
            return false
        }
    }
}
