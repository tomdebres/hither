package com.tomdebres.hither.ui.settings

/**
 * `docs/privacy.md`, the policy a store listing points at, read for the screen. The app shows
 * that file itself (the build copies it in as an asset), so the text inside the app and the
 * text in the repository cannot drift apart. Only what the file uses is understood: `#` and
 * `##` headings, paragraphs, `- ` bullets with indented continuation lines, `**bold**`,
 * `` `code` `` and `<links>`.
 */
sealed interface PrivacyBlock {
    data class Title(val text: String) : PrivacyBlock

    data class Heading(val text: String) : PrivacyBlock

    data class Paragraph(val runs: List<Run>) : PrivacyBlock

    data class Bullet(val runs: List<Run>) : PrivacyBlock
}

/** A stretch of text, bold or not. */
data class Run(val text: String, val bold: Boolean = false)

/** The text of [runs] without the emphasis, for tests and accessibility. */
fun List<Run>.plain(): String = joinToString("") { it.text }

fun parsePrivacy(markdown: String): List<PrivacyBlock> {
    val blocks = mutableListOf<PrivacyBlock>()
    val pending = StringBuilder()
    var pendingIsBullet = false

    fun flush() {
        if (pending.isNotEmpty()) {
            val runs = inline(pending.toString())
            blocks += if (pendingIsBullet) PrivacyBlock.Bullet(runs) else PrivacyBlock.Paragraph(runs)
            pending.clear()
        }
        pendingIsBullet = false
    }

    for (raw in markdown.lines()) {
        val line = raw.trimEnd()
        when {
            line.isBlank() -> flush()
            line.startsWith("## ") -> {
                flush()
                blocks += PrivacyBlock.Heading(inline(line.removePrefix("## ")).plain())
            }
            line.startsWith("# ") -> {
                flush()
                blocks += PrivacyBlock.Title(inline(line.removePrefix("# ")).plain())
            }
            line.startsWith("- ") -> {
                flush()
                pendingIsBullet = true
                pending.append(line.removePrefix("- ").trim())
            }
            else -> {
                if (pending.isNotEmpty()) pending.append(' ')
                pending.append(line.trim())
            }
        }
    }
    flush()
    return blocks
}

private val INLINE = Regex("""\*\*(.+?)\*\*|`([^`]+)`|<(https?://[^>]+)>""")

/** Bold runs from `**…**`; code and links become plain text. */
private fun inline(text: String): List<Run> {
    val runs = mutableListOf<Run>()
    var at = 0
    for (match in INLINE.findAll(text)) {
        if (match.range.first > at) runs += Run(text.substring(at, match.range.first))
        val (bold, code, link) = match.destructured
        runs += when {
            bold.isNotEmpty() -> Run(bold.replace("`", ""), bold = true)
            code.isNotEmpty() -> Run(code)
            else -> Run(link)
        }
        at = match.range.last + 1
    }
    if (at < text.length) runs += Run(text.substring(at))
    // Neighbours of the same weight are one run.
    return runs.fold(mutableListOf()) { merged, run ->
        val last = merged.lastOrNull()
        if (last != null && last.bold == run.bold) merged[merged.lastIndex] = Run(last.text + run.text, run.bold) else merged += run
        merged
    }
}
