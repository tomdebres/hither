package com.tomdebres.hither.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Hither's few icons, drawn for it on a 24-unit grid as outlines with round ends: the status
 * badge's six (Seerr's StatusBadgeMini uses the same six ideas: a check in a circle, a minus,
 * a clock, a bell, a bin, a struck-through eye), the search field's magnifier and the play
 * button's triangle. Line icons,
 * no emoji (Tom's design rules); white, coloured by the badge behind them.
 */
object HitherIcons {
    val CheckCircle = icon("check-circle", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20Z", "M8 12.5l2.7 2.7L16 9.8")
    val Minus = icon("minus", "M6 12h12")

    /** An exclamation in a circle: Seerr's toast for something that did not go through. */
    val AlertCircle = icon("alert-circle", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20Z", "M12 7.5v5.5", "M12 16.5h.01")
    val Clock = icon("clock", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20Z", "M12 6.5V12l3.5 2")
    val Bell = icon(
        "bell",
        "M6 8.5a6 6 0 0 1 12 0c0 6.5 2.5 8.5 2.5 8.5h-17S6 15 6 8.5Z",
        "M10.3 20.5a2 2 0 0 0 3.4 0",
    )
    val Trash = icon(
        "trash",
        "M3.5 6h17",
        "M18.5 6v13.5a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2V6",
        "M8.5 6V4.5a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2V6",
    )
    val EyeOff = icon(
        "eye-off",
        "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12Z",
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6Z",
        "M3 3l18 18",
    )
    val Search = icon("search", "M10.5 3.5a7 7 0 1 0 0 14a7 7 0 1 0 0-14Z", "M15.5 15.5L21 21")

    /** An arrow out of a box, for Sign Out (lucide's log-out), as Seerr's menu item carries one. */
    val LogOut = icon("log-out", "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", "M16 17l5-5-5-5", "M21 12H9")

    /** An i in a circle, for About (lucide's info). */
    val Info = icon("info", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20Z", "M12 16v-4.5", "M12 8h.01")

    /** A shield, for Privacy (lucide's shield). */
    val Shield = icon("shield", "M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z")

    /** An arrow into a box, for Sign In: Seerr's button carries heroicons' ArrowLeftOnRectangle (lucide's log-in). */
    val LogIn = icon("log-in", "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4", "M10 17l5-5-5-5", "M15 12H3")

    /** A check, for Approve (Seerr's button carries heroicons' check; lucide's check). */
    val Check = icon("check", "M20 6L9 17l-5-5")

    /** A cross, for Decline (heroicons' x-mark; lucide's x). */
    val Cross = icon("x", "M18 6L6 18", "M6 6l12 12")

    /** A key, for the admin account (lucide's key-round). */
    val Key = icon(
        "key-round",
        "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4Z",
        "M16.5 7a.5.5 0 1 0 0 1a.5.5 0 1 0 0-1Z",
    )

    /** A delete key, for the PIN pad (lucide's delete). */
    val Delete = icon("delete", "M20 5H9l-7 7 7 7h11a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2Z", "M18 9l-6 6", "M12 9l6 6")

    /** Seerr's play button's triangle (heroicons' play), for "Play on Plex". */
    val Play = icon("play", "M7 4.8v14.4a1 1 0 0 0 1.5.87l12-7.2a1 1 0 0 0 0-1.72l-12-7.2A1 1 0 0 0 7 4.8Z")

    /** Seerr's trailer icon (heroicons' film), as lucide's film: "Watch Trailer". */
    val Film = icon(
        "film",
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z",
        "M7 3v18", "M17 3v18", "M3 12h18", "M3 7.5h4", "M3 16.5h4", "M17 7.5h4", "M17 16.5h4",
    )

    /** Seerr's tag icon on a keyword (lucide's tag). */
    val Tag = icon(
        "tag",
        "M12.586 2.586A2 2 0 0 0 11.172 2H4a2 2 0 0 0-2 2v7.172a2 2 0 0 0 .586 1.414l8.704 8.704a2.426 2.426 0 0 0 3.42 0l6.58-6.58a2.426 2.426 0 0 0 0-3.42Z",
        "M7.5 7.5h.01",
    )

    /** The account (M6, Tom's of 2026-11-12): head and shoulders, lucide's user, as Seerr's grey default picture. */
    val User = icon("user", "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2", "M12 3a4 4 0 1 0 0 8a4 4 0 1 0 0-8Z")

    /** Report an Issue (M6): a warning triangle, as Seerr's ExclamationTriangle. */
    val AlertTriangle = icon("alert-triangle", "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z", "M12 9v4", "M12 17h.01")

    /** Diagnostics (M6): lucide's activity line. */
    val Activity = icon("activity", "M22 12h-4l-3 9L9 3l-3 9H2")

    /** Sort (M6): lines narrowing beside a down arrow, as Seerr's BarsArrowDown. */
    val SortDown = icon("sort-down", "M3 16l4 4 4-4", "M7 20V4", "M11 4h10", "M11 8h7", "M11 12h4")

    /** The Movies page's sort (A8): lucide's arrow-up-down, as the Apple TV app's arrow.up.arrow.down. */
    val ArrowUpDown = icon("arrow-up-down", "M21 16l-4 4-4-4", "M17 20V4", "M3 8l4-4 4 4", "M7 4v16")

    /** The Movies page's filters (A8): lucide's list-filter, three lines narrowing, as the Apple TV app's
     * line.3.horizontal.decrease (Seerr's funnel before). */
    val ListFilter = icon("list-filter", "M3 6h18", "M7 12h10", "M10 18h4")

    /** lucide's arrow-left-right: Switch Back to the family's account (polish 2). */
    val ArrowLeftRight = icon("arrow-left-right", "M8 3L4 7l4 4", "M4 7h16", "M16 21l4-4-4-4", "M20 17H4")

    /** "See More" at a row's end (DECISIONS 112): an arrow in a circle, as Seerr's ArrowRightCircle (lucide's circle-arrow-right). */
    val CircleArrowRight = icon("circle-arrow-right", "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20Z", "M12 16l4-4-4-4", "M8 12h8")

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                for (data in paths) {
                    addPath(
                        pathData = addPathNodes(data),
                        stroke = SolidColor(Color.White),
                        strokeLineWidth = 2f,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()
}
