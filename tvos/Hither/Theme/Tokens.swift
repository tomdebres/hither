import SwiftUI
import UIKit

// The rules for buttons and glyphs (the review's X10, DECISIONS 246). `PolishTests` holds what a test can hold.
//
// 1. One `.borderedProminent` button per screen, tinted indigo (`Tokens.accent`): the screen's main action. Every
//    other button is the system's own. A tint goes on that button only, never on a form, a page or the app (an
//    app-wide tint blanks tvOS's buttons; a form's tint becomes its buttons' words).
// 2. Destructive by role, never by fill: `Button(role: .destructive)` in a form row or an alert, which tvOS draws as
//    red words on its own surface. No red fill.
// 3. Selection is never a button's fill: what is chosen is said by the system's own controls (a menu's tick, a
//    picker, a list row), not by turning a button prominent.
// 4. Status colours on glyphs only (`Glyph`): the disc in Seerr's colour, the mark in the colour that reads on it
//    (white on indigo and red, black on yellow and green), a thin dark ring round the disc. The quota's ring (Seerr's
//    gauge) is a glyph too. Never a fill behind words.
//
// The fills the plan left for later parts are gone: part 3 took Approve, Decline and Cancel Request into dialogs and
// the Issues and queue filters into the Library split's side list, and part 4 made Remove Admin Account a destructive
// row.

/// Hither's colours on tvOS (Tom's native look, DECISIONS 189): the system's own for grounds and words, and Seerr's for
/// its accent and its status colours only. Seerr's are Tailwind 3.4's default palette, as Android Hither has them
/// (`app/.../ui/theme/Tokens.kt`), with the Tailwind name beside each; this is the one file that names a raw colour.
enum Tokens {
    /// The page: black, the system's own ground in the dark appearance Hither keeps (DECISIONS 190; UIKit's
    /// `systemBackground` is not on tvOS).
    static let background = Color.black
    /// A picture's place until it comes, a lockup with none, and a loading row's placeholders: one grey, a step up from
    /// the page (the review's F36: pictures sat on a lighter grey than the placeholders they replaced).
    static let placeholder = Color(white: 0.16)
    /// A plain surface on the page (the notices' banner where glass would not hold, DECISIONS 272): a step further up.
    static let surface = Color(white: 0.2)
    /// A text field at rest (`EntryField`): the grey tvOS gives a form's row at rest on black (`#212121`, read off the
    /// screen). The system draws a stand-alone field's rest capsule as glass, which shows nothing on a black ground.
    static let field = Color(hex: 0x212121)
    /// A field at rest: the system's placeholder grey (UIKit's `placeholderText`).
    static let fieldPrompt = Color(uiColor: .placeholderText)
    /// A focused field (`EntryField`): the system's focused white (`#EBEBEB`, read off the screen), black words, and a
    /// placeholder that reads on it, 5.6:1 (the system's own was `#B7B7BD`, 1.7:1: Tom, M8 part 2).
    static let fieldFocused = Color(hex: 0xEBEBEB)
    static let fieldFocusedText = Color.black
    static let fieldFocusedPrompt = Color(hex: 0x5C5C61)

    /// The app's tint: Seerr's indigo-600, its primary buttons' colour.
    static let accent = Color(hex: 0x4F46E5)
    /// Seerr's indigo-400: the request sheet's info mark, legible on the sheet's dark ground.
    static let accentText = Color(hex: 0x818CF8)

    // Seerr's status colours (`StatusBadge`), for the status glyphs and nothing else. The -500 steps: the -600 fills
    // Seerr draws at 80% under white words read too dark as a glyph on black.
    static let success = Color(hex: 0x22C55E)         // green-500: available
    static let primary = Color(hex: 0x6366F1)         // indigo-500: requested, processing
    static let warning = Color(hex: 0xEAB308)         // yellow-500: pending
    static let danger = Color(hex: 0xEF4444)          // red-500: declined, failed, deleted
    /// A form's error words: Seerr's red-500.
    static let errorText = danger

    /// The title page's backdrop fades into the page through this, and the words over it keep their contrast.
    static let scrim = Color.black
}

/// The ten-foot sizes, in points, after Apple's tvOS grid (HIG, "Layout": 80-point sides, 60 at top and bottom; six
/// posters across at 250 points, four 16:9 pictures at 410) rather than Android's dp doubled (DECISIONS 191).
enum Metrics {
    static let sideMargin: CGFloat = 80
    static let topMargin: CGFloat = 60
    /// Under a pushed page's title, to its content (`PageScaffold`).
    static let belowPageTitle: CGFloat = 40

    /// A poster lockup's picture: 2:3, six across.
    static let posterWidth: CGFloat = 250
    static let posterHeight: CGFloat = 375
    /// A backdrop lockup's picture (Recent Requests, a collection): 16:9, four across.
    static let backdropWidth: CGFloat = 410
    static let backdropHeight: CGFloat = 231
    /// A portrait lockup's circle (Cast & Crew, a person in search).
    static let portrait: CGFloat = 200
    /// Between lockups in a row, and between rows of a grid.
    static let lockupGap: CGFloat = 48
    static let gridRowGap: CGFloat = 64
    /// A picture's corners, as tvOS rounds its lockups.
    static let imageRadius: CGFloat = 12
    /// The caption's room under a picture: the title and a subtitle line, with the gap the lifted picture needs.
    static let captionHeight: CGFloat = 96

    /// A shelf's header: the room above it, its line, the room under it. Under it, 40: a lifted lockup grows about 5%
    /// each way, and at 24 a focused poster came within 11 points of the header (the review's F31).
    static let aboveShelf: CGFloat = 36
    static let belowShelfTitle: CGFloat = 40

    /// Where something is typed (`EntryColumn`): the column's width, a field's and the button's, and the mark over it,
    /// small (the sign-in's mark was 520 points wide, the biggest thing in the app: the review's F34).
    static let entryWidth: CGFloat = 800
    static let entryMark: CGFloat = 176
    /// The sign-in column's top, under the safe area's 60.
    static let entryTop: CGFloat = 100
    /// A field (`EntryField`): the system's own stand-alone field at rest, 70 high, its words 30 in from the capsule's
    /// ends.
    static let entryField: CGFloat = 70
    static let entryFieldInset: CGFloat = 30
    /// The system's text field under Hither's drawing of it: seen by the focus engine (which passes over a view under 1%),
    /// not by a person.
    static let entryFieldTrace: Double = 0.02

    /// Over a `StateView`'s actions when it has no line under its words: the system puts its actions 35 points under a
    /// description and 6 under the words alone (measured, tvOS 26.5), so this makes up the difference.
    static let stateActionsLead: CGFloat = 29

    /// Settings' pages (`SettingsScaffold`), on Apple's Settings' lines: the list 780 points wide to the right safe
    /// edge, the picture centred in the 900 points left of it, 80 between them.
    static let settingsList: CGFloat = 780
    static let settingsMarkArea: CGFloat = 900
    static let settingsGap: CGFloat = 80
    static let settingsMark: CGFloat = 360
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}
