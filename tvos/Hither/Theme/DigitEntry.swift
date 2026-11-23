import SwiftUI
import TVUIKit

/// tvOS's own digit entry (the review's X8, F14, F17; HIG, "Digit entry views"): `TVDigitEntryViewController`, the
/// system's PIN screen, wrapped once. Its title, the line under it, a place per digit that shows a dot once a digit is
/// typed (never the digit: `isSecureDigitEntry`), and the system's digits keyboard, which also takes digits typed on a
/// paired keyboard. It hands the digits over when the last one is typed: there is no OK to find.
///
/// Hither's rules ride on top. While [takes] is false (a check is out, a wait is on) a full entry is cleared and dropped,
/// never handed over. A new [entry] (the next step of choosing a PIN, a wrong try) clears what is typed. Shown full
/// screen (`.fullScreenCover`), as tvOS shows its own; Menu closes it, through the cover.
struct DigitEntry: UIViewControllerRepresentable {
    let title: String
    let prompt: String
    var digits = 4
    var takes = true
    var entry = 0
    let onEntry: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIViewController(context: Context) -> Host {
        let host = Host()
        let controller = host.entry
        controller.numberOfDigits = digits
        controller.isSecureDigitEntry = true
        controller.titleText = title
        controller.promptText = prompt
        let coordinator = context.coordinator
        coordinator.entry = entry
        controller.entryCompletionHandler = { [weak controller] typed in
            coordinator.complete(typed, controller)
        }
        return host
    }

    func updateUIViewController(_ host: Host, context: Context) {
        context.coordinator.parent = self
        let controller = host.entry
        if controller.titleText != title { controller.titleText = title }
        if controller.promptText != prompt { controller.promptText = prompt }
        if context.coordinator.entry != entry {
            context.coordinator.entry = entry
            controller.clearEntry(animated: true)
        }
    }

    /// The digit entry inside a blur of what is under it, as tvOS presents it itself (`.blurOverFullScreen`): its
    /// places are drawn with the blur's vibrancy, and over a plain view they did not show at all.
    final class Host: UIViewController {
        let entry = TVDigitEntryViewController()

        override func loadView() {
            let blur = UIVisualEffectView(effect: UIBlurEffect(style: .dark))
            view = blur
            addChild(entry)
            entry.view.frame = blur.contentView.bounds
            entry.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            blur.contentView.addSubview(entry.view)
            entry.didMove(toParent: self)
        }

        override var preferredFocusEnvironments: [any UIFocusEnvironment] { [entry] }
    }

    @MainActor
    final class Coordinator {
        var parent: DigitEntry
        var entry = 0

        init(_ parent: DigitEntry) { self.parent = parent }

        func complete(_ typed: String, _ controller: TVDigitEntryViewController?) {
            guard parent.takes else {
                controller?.clearEntry(animated: true)
                return
            }
            parent.onEntry(typed)
        }
    }
}
