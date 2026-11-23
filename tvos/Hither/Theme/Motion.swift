import SwiftUI

/// Hither's own animations, in one place (the system's focus, sheets and tab bar animate themselves). Few and short, for
/// the tester's Apple TV HD: nothing of Hither's is drawn per frame while focus rests.
///
/// A debug build started with `-hither-slow-motion <n>` plays them n times slower, so a UI test can look at one mid-way
/// (a picture's fade); a release build has no such switch. The caption's step follows the system's focus animation,
/// which no switch of Hither's slows.
enum Motion {
    static let scale: Double = {
        #if DEBUG
        let arguments = CommandLine.arguments
        if let at = arguments.firstIndex(of: "-hither-slow-motion"), at + 1 < arguments.count, let times = Double(arguments[at + 1]), times > 0 {
            return times
        }
        #endif
        return 1
    }()

    /// A lockup's caption stepping down as its picture lifts, and back.
    static var caption: Animation { .easeOut(duration: 0.2 * scale) }
    /// A picture fading in over its grey as it arrives (not one already in memory).
    static var picture: Animation { .easeOut(duration: 0.2 * scale) }
    /// A long text turning a screen with the remote (`ReadingView`).
    static var turn: Animation { .easeInOut(duration: 0.3 * scale) }

}
