package com.tomdebres.hither.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The look's rules that a test can hold by reading the app's sources (A1, DECISIONS 292; written in `Tokens.kt`'s
 * header), as tvOS's `PolishTests` reads the Swift. Comments are left out, so a rule may be named in prose.
 *
 * Every assertion lists what breaks it, file and line, so a red says where.
 */
class ThemeRulesTest {
    /** The app's Kotlin sources: Gradle runs a module's unit tests from the module's folder. */
    private val sources = File("src/main/kotlin/com/tomdebres/hither")

    /** Each source file by its path under [sources], comments taken out (lines kept, so line numbers hold). */
    private val files: Map<String, List<String>> by lazy {
        assertTrue("the app's sources at ${sources.absolutePath}", sources.isDirectory)
        sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { file ->
            file.relativeTo(sources).path to uncommented(file.readText()).split("\n")
        }.also { assertTrue("the app's sources were found (${it.size})", it.size > 40) }
    }

    private fun inTheme(path: String) = path.startsWith("ui/theme/")

    /** Every line, outside [allowed] files, that [rule] matches: "path:line: text". */
    private fun findings(allowed: (String) -> Boolean = { false }, rule: (String) -> Boolean): List<String> =
        files.filterKeys { !allowed(it) }.flatMap { (path, lines) ->
            lines.mapIndexedNotNull { index, line -> if (rule(line)) "$path:${index + 1}: ${line.trim()}" else null }
        }.sorted()

    // ---- rule 1: one prominent button per screen, in indigo -----------------------------------------------------

    /** Indigo only on the prominent button: Seerr's accent nowhere else (the current tab's gradient was indigo). */
    @Test
    fun theAccentOnlyOnTheProminentButton() {
        val accent = Regex("""\bTokens\.Accent\b""")
        assertEquals(
            "Tokens.Accent outside ProminentButton (ui/theme/Buttons.kt)",
            emptyList<String>(),
            findings({ it == "ui/theme/Buttons.kt" || it == "ui/theme/Tokens.kt" }) { accent.containsMatchIn(it) },
        )
    }

    // ---- rules 2 and 4: no status colour as a fill, destructive by red words -------------------------------------

    /** Seerr's danger, success and warning fills gone: the status colours reach the screen through `Glyph` only. */
    @Test
    fun statusColoursOnGlyphsOnly() {
        val status = Regex("""\bTokens\.(Danger|Success|Warning|Primary)\b|\b(DangerLook|SuccessLook|WarningLook)\b""")
        assertEquals(
            "a status colour off a glyph (ui/theme/Glyph.kt), or one of Seerr's coloured button fills",
            emptyList<String>(),
            findings({ it == "ui/theme/Glyph.kt" || it == "ui/theme/Tokens.kt" }) { status.containsMatchIn(it) },
        )
    }

    // ---- rule 3: selection is never a fill ------------------------------------------------------------------------

    /**
     * What is chosen is said by a tick or its words, never by filling it: no fill whose colour depends on whether a
     * thing is current, on or selected (the bar's current tab, the filter pills, a chip, a provider, a radio row).
     */
    @Test
    fun selectionIsNeverAFill() {
        val chosen = """\bif \((current|on|selected|picked|isCurrent)\)"""
        val inFill = Regex("""(focusFill|background|focusBorder)\([^\n]*$chosen""")
        val fillUnder = Regex("""$chosen\s*\{?\s*Modifier\.(focusFill|background)""")
        val names = Regex("""\b(ActiveNav|ActiveNavFocused|CurrentFill|CurrentFillFocused)\b""")
        val found = files.flatMap { (path, lines) ->
            lines.indices.mapNotNull { i ->
                val window = lines.subList(i, minOf(i + 3, lines.size)).joinToString(" ") { it.trim() }
                val line = lines[i]
                when {
                    inFill.containsMatchIn(line) || names.containsMatchIn(line) -> "$path:${i + 1}: ${line.trim()}"
                    Regex(chosen).containsMatchIn(line) && fillUnder.containsMatchIn(window) -> "$path:${i + 1}: ${line.trim()}"
                    else -> null
                }
            }
        }.sorted()
        assertEquals("a fill that says what is chosen", emptyList<String>(), found)
    }

    // ---- the type: tv-material's, in one face --------------------------------------------------------------------

    /** A size is set in `Type.kt` alone: a screen names a style, never a size of its own (tvOS's TextStylesTests). */
    @Test
    fun sizesOnlyInTheTypeScale() {
        assertEquals(
            "a fontSize outside ui/theme/Type.kt",
            emptyList<String>(),
            findings({ it == "ui/theme/Type.kt" }) { "fontSize =" in it || "fontSize=" in it },
        )
    }

    /** One typeface, the theme's (Roboto, Tom's Q3): no font family of the app's own, and no font file shipped. */
    @Test
    fun noTypefaceButTheThemes() {
        val family = Regex("""\bFontFamily\b|\bR\.font\.|\bFont\(\s*resId""")
        assertEquals("a font family other than the theme's", emptyList<String>(), findings { family.containsMatchIn(it) })
        val fonts = File("src/main/res").listFiles { file -> file.isDirectory && file.name.startsWith("font") }.orEmpty()
        assertEquals("a font folder in the app's resources", emptyList<String>(), fonts.map { it.name })
    }

    // ---- X1: Page --------------------------------------------------------------------------------------------------

    /** The margins live in `Metrics` once: no file keeps its own SideMargin or TopMargin (eleven files did). */
    @Test
    fun marginsOnlyInTheTheme() {
        val margin = Regex("""\bval \w*(SideMargin|TopMargin)\b\s*(:[^=]*)?=""")
        assertEquals("a file's own margin", emptyList<String>(), findings(::inTheme) { margin.containsMatchIn(it) })
    }

    /** The ground is drawn once, by `Page` (and a dialog's dim by `Overlay`): no screen fills the screen itself. */
    @Test
    fun theGroundOnlyInTheTheme() {
        val found = files.filterKeys { !inTheme(it) }.flatMap { (path, lines) ->
            // a modifier chain may run over lines: from each fillMaxSize() on a line, read on into the next lines,
            // spaces taken out, and see whether a background follows it
            lines.indices.mapNotNull { i ->
                val rest = lines.subList(i + 1, minOf(i + 3, lines.size)).joinToString("") { it.trim() }
                val fills = Regex("""fillMaxSize\(\)""").findAll(lines[i]).any { match ->
                    (lines[i].substring(match.range.first) + rest).replace(" ", "").startsWith("fillMaxSize().background(")
                }
                if (fills) "$path:${i + 1}: ${lines[i].trim()}" else null
            }
        }.sorted()
        assertEquals("a screen-sized fill outside ui/theme", emptyList<String>(), found)
    }

    /** Every screen goes through `Page`: the black ground, the margins and a pushed page's title, set once. */
    @Test
    fun everyScreenThroughPage() {
        val screens = files.filterKeys { it.startsWith("ui/") && it.endsWith("Screen.kt") }
        assertTrue("the screens were found (${screens.size})", screens.size >= 15)
        assertEquals(
            "a screen not drawn through Page",
            emptyList<String>(),
            screens.filterValues { lines -> !Regex("""\bPage\(\s*PageKind\.""").containsMatchIn(lines.joinToString("\n")) }.keys.sorted(),
        )
    }

    // ---- X2, X6 and the buttons: one way each ---------------------------------------------------------------------

    /**
     * One component for each thing: `StateView` for every state, `Page` for every ground, tv-material's buttons, the
     * theme's dialogs, `EntryField` for typing and `HitherListItem` for a row (A7). The names they replaced are gone, and
     * Seerr's brand gradient with them.
     */
    @Test
    fun theReplacedComponentsAreGone() {
        val replaced = Regex(
            """\b(SeerrButton|SeerrPage|FailureNotice|RowNotice|BrandGradient|ConfirmModal|StatusBadgePill|BadgePill|StatusBadgeMini|SeerrField|SettingsRow)\b|fun Empty\(|fun Notice\(""",
        )
        assertEquals("a replaced component", emptyList<String>(), findings { replaced.containsMatchIn(it) })
    }

    // ---- the lockups (A3, DECISIONS 320, 322) --------------------------------------------------------------------

    /**
     * No shadow anywhere: focus is a scale and an edge (tv-material's), and a resting card has none. Seerr drew them on
     * cards (DECISIONS 47, 67), and an animated one cost the Shield thousands of blurred bitmaps (57).
     */
    @Test
    fun noShadowAnywhere() {
        val shadow = Regex("""\b(dropShadow|innerShadow|createDropShadowPainter|shadowContext|DropShadowPainter|cardShadow|fixedShadow|focusLift)\b|\.shadow\(""")
        assertEquals("a shadow", emptyList<String>(), findings { shadow.containsMatchIn(it) })
    }

    /**
     * One lockup for a title, one for a person, one placeholder (`ui/theme/Lockups.kt`): Seerr's cards are gone, and since
     * A6 its request, issue and queue rows too (H2's X3: `RequestRowView`, `IssueRowView`, `ApprovalRow`).
     */
    @Test
    fun oneLockupForATitle() {
        val cards = Regex("""\b(TitleCardView|PersonCardView|RequestCardView|PlaceholderCard|CardOverview|MediaChip|RequestRowView|IssueRowView|ApprovalRow|PlaceholderRow)\b""")
        assertEquals("a card that is not a lockup", emptyList<String>(), findings { cards.containsMatchIn(it) })
    }

    // ---- the lists as the Library (A6, DECISIONS 338) ------------------------------------------------------------

    /**
     * My Requests, Issues and the approval queue are each tvOS's Library split (`ui/theme/LibrarySplit.kt`): a side list of
     * rows, the chosen one ticked, beside a grid of lockups. Seerr's filter pills and the queue's nine filters are gone.
     */
    @Test
    fun theListsAreTheLibrarySplit() {
        val lists = listOf("ui/requests/RequestsScreen.kt", "ui/issues/IssuesScreen.kt", "ui/approvals/ApprovalsScreen.kt")
        assertEquals(
            "a list page not drawn as the split",
            emptyList<String>(),
            lists.filter { path -> files[path]?.joinToString("\n")?.let { Regex("""\bLibrarySplit\(""").containsMatchIn(it) } != true },
        )
        val pills = Regex("""\bFilterPill\b|RequestFilter\.entries""")
        assertEquals("a filter pill, or the nine queue filters", emptyList<String>(), findings { pills.containsMatchIn(it) })
    }

    // ---- X5: Settings' rows ------------------------------------------------------------------------------------------

    /**
     * Settings' rows are words (tvOS's F13: each symbol had its own width, so no two labels lined up): no icon on
     * Settings, About, Diagnostics or Admin Account, and the theme's rows take none before their words.
     */
    @Test
    fun settingsRowsCarryNoIcons() {
        val icon = Regex("""\bHitherIcons\.|\bIcons\.|\bicon\s*=|\bImageVector\b""")
        assertEquals(
            "an icon on a Settings page (ui/settings)",
            emptyList<String>(),
            findings({ !it.startsWith("ui/settings/") }) { icon.containsMatchIn(it) },
        )
        assertEquals(
            "a row with something before its words (ui/theme/ListRows.kt, SettingsScaffold.kt)",
            emptyList<String>(),
            findings({ it != "ui/theme/ListRows.kt" && it != "ui/theme/SettingsScaffold.kt" }) { "leadingContent" in it },
        )
    }

    // ---- the sheets and feedback (A5, DECISIONS 353) ------------------------------------------------------------------

    /**
     * What a press did shows in place, and what went wrong is a `ProblemDialog` (tvOS's D4): no toast says an outcome
     * (a request made, a cancel, a decision, a report, an app that would not open). The notices' banner is all that
     * comes and goes by itself (`ui/home/Alerts.kt`).
     */
    @Test
    fun noOutcomeIsAToast() {
        val toast = Regex("""\b(SaidCard|Said)\b|\.say\(|R\.string\.toast_""")
        assertEquals("an outcome said as a toast", emptyList<String>(), findings { toast.containsMatchIn(it) })
    }

    /**
     * A form of choices is a `SidePanel` (a series' request, Report an Issue) and a question a `ConfirmDialog` (a film's
     * request), as tvOS's sheet and alert: Seerr's modal is gone from the title page, with its season table, switches and
     * quota ring, and the issue form's radio rows and select boxes.
     */
    @Test
    fun theTitlesFormsAreSidePanels() {
        val forms = mapOf(
            "ui/media/RequestModal.kt" to listOf("SidePanel(", "ConfirmDialog("),
            "ui/media/IssueModal.kt" to listOf("SidePanel("),
        )
        assertEquals(
            "a form not drawn as the panel or the question",
            emptyList<String>(),
            forms.flatMap { (path, calls) -> calls.filter { call -> files[path]?.none { call in it } != false }.map { "$path: no $it" } },
        )
        val seerr = Regex("""\b(SeasonTable|SeasonRowView|QuotaRing|QuotaNotice|ApprovedNotice|TableRow|Toggle|TypeRow|PickerModal|SelectBox)\b""")
        assertEquals(
            "Seerr's modal on the title page (ui/media)",
            emptyList<String>(),
            findings({ !it.startsWith("ui/media/") }) { seerr.containsMatchIn(it) },
        )
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** [text] without its comments: block comments blanked line for line, then line comments (not a URL's `//`). */
    private fun uncommented(text: String): String {
        val blocks = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text) { match -> match.value.replace(Regex("""[^\n]"""), " ") }
        return blocks.split("\n").joinToString("\n") { line -> line.replace(Regex("""(?<!:)//.*$"""), "") }
    }
}
