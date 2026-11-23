package com.tomdebres.hither.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// The rules for buttons and glyphs (H2's X10, DECISIONS 292), worded as tvOS's `Tokens.swift` words its own.
// `ThemeRulesTest` holds what a test can hold, reading these sources as tvOS's `PolishTests` reads the Swift.
//
// 1. One prominent button per screen, in indigo (`ProminentButton`, `Tokens.Accent`): the screen's main action. Every
//    other button is tv-material's own (`PlainButton`). Indigo goes on that button only, never on a tab, a switch, a
//    chosen row, words or a page: tv-material reads the theme's `primary` for tabs, switches and checkboxes, so
//    `primary` is white (`HitherColors`).
// 2. Destructive by role, never by fill: `PlainButton(destructive = true)`, red words on tv-material's own button (Sign
//    Out, Remove Admin Account, Decline, Cancel Request). No red fill, and no green or yellow one (Approve is a plain
//    button).
// 3. Selection is never a fill: what is chosen is said by a tick or by its words, not by filling a control or turning
//    it indigo. A fill says focus, and nothing else.
// 4. Status colours on glyphs only (`Glyph`): the disc in Seerr's colour, the mark in the colour that reads on it
//    (black on yellow and green, white on indigo and red), a thin dark ring round the disc. Never a fill behind words.
//
// Seerr's palette went with A1 (gray-900, its gradients, its danger, success and warning fills, Inter): the ground is
// black and the greys are white at a few strengths, as the Apple TV app's (DECISIONS 291).

/**
 * Hither's colours on Android, after tvOS's `Tokens` (DECISIONS 291): black, white and greys for grounds and words, and
 * Seerr's for its accent and its status colours only. This is the one file that names a raw colour, and the scheme
 * tv-material's controls read is made from it (`HitherColors`, `Theme.kt`).
 */
object Tokens {
    /** The page: black, everywhere, once (`Page`). */
    val Background = Color.Black

    /** A picture's place until it comes, and a loading row's placeholders: one grey, a step up from the page (16%). */
    val Placeholder = Color(0xFF292929)

    /** A plain surface on the page (20%): a resting button's container, a row, a notice. */
    val Surface = Color(0xFF333333)

    /**
     * A step further up (28%): the focused state of Hither's own rows and cards, until their parts redraw them with
     * tv-material (A3 to A8).
     */
    val SurfaceRaised = Color(0xFF474747)

    /** A 1 dp line between rows or round a box (36%), where a part still draws one. */
    val Edge = Color(0xFF5C5C5C)

    /** Behind a dialog or a panel, over the page; and the title page's backdrop fading into it. */
    val Scrim = Color.Black

    /** Words. */
    val Text = Color.White

    /** Words that say less: a value's name, a hint, a date (5.0:1 on `Surface`). */
    val TextSecondary = Color(0xFFA3A3A3)

    /** A text field at rest (`EntryField`): the grey tvOS gives a form's row at rest on black. */
    val Field = Color(0xFF212121)

    /** A field's placeholder at rest. */
    val FieldPrompt = TextSecondary

    /** A focused field: tvOS's focused white, black words, and a placeholder that reads on it (5.6:1). */
    val FieldFocused = Color(0xFFEBEBEB)
    val FieldFocusedText = Color.Black
    val FieldFocusedPrompt = Color(0xFF5C5C61)

    /** Seerr's indigo-600: the one prominent button's fill (rule 1), white words on it at 6.3:1. */
    val Accent = Color(0xFF4F46E5)

    // Seerr's status colours (`StatusBadge`), for the status glyphs and nothing else (rule 4). The -500 steps, as tvOS's.
    /** green-500: available. */
    val Success = Color(0xFF22C55E)

    /** indigo-500: requested, processing. */
    val Primary = Color(0xFF6366F1)

    /** yellow-500: pending, waiting. */
    val Warning = Color(0xFFEAB308)

    /** red-500: declined, failed, deleted. */
    val Danger = Color(0xFFEF4444)

    /** A form's error words: red-400, 4.6:1 on `Surface` (red-500 was 3.4:1 there). */
    val ErrorText = Color(0xFFF87171)

    /** A destructive button's words at rest (rule 2): red-400 on the resting container. */
    val Destructive = ErrorText

    /** A destructive button's words with focus: red-600 on the focused white, 4.8:1. */
    val DestructiveFocused = Color(0xFFDC2626)
}

/**
 * The ten-foot sizes in dp, on the 960 x 540 dp panel of a 1080p TV (tvOS's points halved, DECISIONS 291): every size
 * H2's plan names, so the parts after A1 take theirs from here rather than a file's own constant (`ThemeRulesTest`).
 */
object Metrics {
    /** Android TV's overscan-safe margins: 48 dp at the sides, 27 at top and bottom (its layout guide). */
    val SideMargin = 48.dp
    val TopMargin = 27.dp
    val BottomMargin = 27.dp

    /**
     * The bar (A2): its line under the top margin, and from the screen's top to its foot, the room a tab page leaves it
     * at its top (the bar scrolls away with the page, so a list puts it in its own top padding).
     */
    val BarHeight = 60.dp
    val BarFoot = TopMargin + BarHeight

    /** The notices' banner, top right: no wider than the corner the centred bar leaves it (tvOS's 420 points). */
    val BannerWidest = 210.dp

    /** A pushed page's title line (`Page`), as tall as a button on it (Sort, Filters), and the room under it to the
     * page's content (tvOS's 40 points). */
    val PageTitleHeight = 40.dp
    val BelowPageTitle = 20.dp

    /** tv-material's focus scale for buttons and cards (`ButtonDefaults.scale`, `CardDefaults.scale`). */
    const val FocusScale = 1.1f

    /** A poster lockup's picture, 2:3 (A3). */
    val PosterWidth = 120.dp
    val PosterHeight = 180.dp

    /** A backdrop lockup's picture, 16:9 (A3: Recent Requests, a collection). */
    val BackdropWidth = 205.dp
    val BackdropHeight = 115.dp

    /** A portrait lockup's circle (A3, A4: Cast & Crew, a person in search). */
    val Portrait = 100.dp

    /** Between lockups in a shelf, and between a grid's rows (A3). */
    val LockupGap = 24.dp
    val GridRowGap = 32.dp

    /** A picture's corners (tvOS's 12 points). */
    val ImageRadius = 6.dp

    /**
     * The caption's room under a picture (A3): the gap, a title line (Body, 20 dp) and a line under it (Caption, or a
     * status glyph and its word, 18 dp).
     */
    val CaptionHeight = 52.dp

    /**
     * Between a picture and its caption: the 1.1 focus scale grows a poster 9 dp at its foot, and tv-material's edge, 3 dp
     * centred on the picture's outline, 1.65 dp more; 14 leaves 3 clear, so the caption is never under the lifted picture
     * (A3; tvOS steps its caption down instead, an animation Android need not run).
     */
    val CaptionGap = 14.dp

    /** A focused lockup's white edge (tv-material's card border, its own 3 dp). */
    val LockupEdge = 3.dp

    /**
     * A shelf's header (A3): the room above it, its line (titleMedium) and the room under it, so a lifted lockup clears
     * it (tvOS's F31: a poster at 1.1 grows 9 dp up, and 20 leaves 11). 62 dp in all, the rows' 62 before A3, so the
     * focused row's line (DECISIONS 40) does not move.
     */
    val AboveShelf = 18.dp
    val ShelfTitleLine = 24.dp
    val BelowShelfTitle = 20.dp
    val ShelfHeader = AboveShelf + ShelfTitleLine + BelowShelfTitle

    /** Where something is typed (A7's `EntryColumn`): the column, the mark over it, its top, a field and its inset. */
    val EntryWidth = 400.dp
    val EntryMark = 88.dp
    val EntryTop = 50.dp
    val EntryField = 36.dp
    val EntryFieldInset = 15.dp

    /** Settings' pages (A7's `SettingsScaffold`): the list to the right safe edge, the mark centred left of it. */
    val SettingsList = 390.dp
    val SettingsMarkArea = 450.dp
    val SettingsGap = 40.dp
    val SettingsMark = 180.dp

    /** A state's symbol, and the room between its words and its action (`StateView`). */
    val StateIcon = 40.dp
    val StateActionsLead = 24.dp

    /** A dialog's column (`ConfirmDialog`, `ProblemDialog`), and the side panel's width (A5's `SidePanel`). */
    val DialogWidth = 400.dp
    val SidePanel = 420.dp

    /** A status glyph's disc, beside words and on a poster's corner, and the dark ring round it (`Glyph`). */
    val Glyph = 16.dp
    val GlyphRing = 1.dp

    // ---- A4: the title and person pages (tvOS's product page, its points halved) ----------------------------------

    /**
     * A title's page: the backdrop full-bleed across the top (core's 1920 x 800 picture, drawn 960 x 400); the first
     * screen, the backdrop with the words over its foot, a shelf's header showing under it (tvOS's 960 points); the words
     * this wide at most, so the picture keeps its right side (1240).
     */
    val TitleBackdropHeight = 400.dp
    val HeroHeight = 480.dp
    val HeroWords = 620.dp

    /** A title with no backdrop shows its poster at the right instead (tvOS's 400 x 600, 120 down). */
    val HeroPosterWidth = 200.dp
    val HeroPosterHeight = 300.dp
    val HeroPosterTop = 60.dp

    /** A season still to ask for, as a card of words (tvOS's 320 x 190). */
    val SeasonCardWidth = 160.dp
    val SeasonCardHeight = 104.dp

    /** The keywords' chips, closer than lockups (tvOS's 32), and their shelf, a chip and room for its lift. */
    val ChipGap = 16.dp
    val ChipShelfHeight = 52.dp

    /** Information at a title page's foot: four columns across (tvOS's 400, 40 apart), rows 18 apart. */
    val InfoColumn = 200.dp
    val InfoGap = 20.dp
    val InfoRowGap = 18.dp

    /**
     * Long text: as wide as reading stays comfortable at ten feet, about 80 characters of Body (tvOS's 1400); a
     * `TextStop`'s platter reaching past its words on each side (tvOS's 24), and its lift, slight for a block of words.
     */
    val ReadingWidth = 700.dp
    val TextStopInset = 12.dp
    const val TextStopScale = 1.02f

    /** A person's page: the header's top and its portrait (tvOS's 140 and 240). */
    val PersonTop = 70.dp
    val PersonPortrait = 120.dp

    // ---- A6: the lists as the Library (tvOS's split, its points halved) -------------------------------------------

    /**
     * The lists as the Library (A6's `LibrarySplit`, tvOS's): the side list's width (tvOS's 440 points: "Waiting for
     * Approval" and its count on one row), the gap to the grid (tvOS's 80), and over the grid's first line the room a
     * lifted lockup takes (the 1.1 scale grows a poster 9 dp up).
     */
    val SideList = 220.dp
    val SplitGap = 40.dp
    val LiftRoom = 12.dp

    /** A list lockup's caption (A6): the gap, its title line, the status's glyph and word, and the line under them. */
    val StatusCaptionHeight = CaptionGap + 20.dp + 20.dp + 18.dp

    // ---- A8: browse and search (tvOS's Movies page and search, its points halved) ----------------------------------

    /** Search's field: the magnifier before the words, and the room between them (tvOS's search field draws one). */
    val SearchGlyph = 16.dp
    val SearchGlyphGap = 8.dp

    /**
     * The Movies and Series pages' sort list (tvOS's menu under its button): as wide as its longest words ("Highest
     * Rated") and a tick, under the title's line at the right margin.
     */
    val SortListWidth = 240.dp
}
