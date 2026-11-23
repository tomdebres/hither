package com.tomdebres.hither.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two apps say one thing (A8, DECISIONS 349; the review's X11): Android's `strings.xml` against the Apple TV app's
 * `tvos/Hither/App/Words.swift`, read from its source as tvOS's `SignInTests.testTheSameWordsAsAndroid` reads
 * `strings.xml` (the same check, the other way round).
 *
 * **What it covers:** both ways round. Each of `Words`' sentences is in `strings.xml` ([everySentenceOfTvosIsInAndroidsStrings],
 * the brief's rule), and every Android string that names the same thing as one of `Words`' (each pair below, most by
 * name: `sign_in_heading` is `Words.signInHeading`) says it word for word, once each side's placeholders are written
 * alike (`%1$s` and `\(server)`). A `Words` function that says more than one thing (a switch, a condition) holds the
 * string if any of its sentences is it.
 *
 * **What it leaves out, and why**, each in a list below with its reason: strings Android alone has ([androidOwn]: the
 * PIN pad Android draws for itself, TalkBack's words for Android's own controls, sentences tvOS builds in code, the
 * empty person page's line); words tvOS alone has ([tvosOwn]: VoiceOver's, the list rows' that A6 took out on Android,
 * helpers); and the few that
 * differ on purpose ([onPurpose]), which must still differ: the day they agree, the test goes red and says to move them
 * into the pairs. Plurals (`<plurals>`) are left
 * out: tvOS words them with a condition.
 *
 * **Every** Android string is in one of the three, so a new one cannot slip by unpaired: add it to [renamed] if
 * `Words` names it otherwise, or to a list with a reason. No string says anything with an exclamation mark (tvOS's
 * rule since polish part 7; A5 took out the last two, its toasts).
 */
class WordsParityTest {
    private val android: Map<String, String> by lazy { androidStrings(File("src/main/res/values/strings.xml")) }
    private val words: SwiftWords by lazy { SwiftWords(File("../tvos/Hither/App/Words.swift").readText()) }

    /** Android's name → `Words`' name, where they differ. */
    private val renamed = mapOf(
        "ready_channel" to "readyToWatch",
        "requests_heading" to "navRequests",
        "nav_movies" to "movies",
        "nav_series" to "series",
        "sign_in_heading_server" to "signInHeading",
        "quick_connect_where_at" to "quickConnectWhere",
        "code_try_again" to "tryAgain",
        "sign_out_body_media_server" to "signOutBody",
        "sign_out_body_plex" to "signOutBody",
        "sign_out_body_quick_connect" to "signOutBody",
        "media_failed" to "titleFailed",
        "person_born" to "born",
        "person_lifespan" to "lifespan",
        "person_also_known_as" to "alsoKnownAs",
        "person_appearances" to "appearances",
        "person_crew" to "crew",
        "person_as" to "playing",
        "seasons_range" to "seasons",
        "season_n" to "season",
        "episode_n" to "episode",
        "episode_one" to "episodes",
        "episode_count" to "episodes",
        "when_by_who" to "whenBy",
        "download_estimate" to "estimated",
        "admin_switch_in_named" to "adminSwitchIn",
        "cancel_request_body_untitled" to "cancelRequestBody",
        "request_question_4k" to "requestQuestion",
        "quota_sentence" to "quota",
        "issue_type" to "issueKindHeader",
        "filters_count" to "filters",
        "sort_most_popular" to "sortName",
        "sort_least_popular" to "sortName",
        "sort_newest" to "sortName",
        "sort_oldest" to "sortName",
        "sort_highest_rated" to "sortName",
        "sort_lowest_rated" to "sortName",
        "sort_title_a_z" to "sortName",
        "sort_title_z_a" to "sortName",
    )

    /** Strings Android alone has, by why. */
    private val androidOwn = mapOf(
        "the PIN pad: Android has no system digit entry, tvOS uses the system's" to listOf("pin_delete", "pin_typed"),
        "TalkBack's words for Android's own admin mark" to listOf("admin_mark_said"),
        "Android's Switch with no name to hand (tvOS always has one)" to listOf("admin_switch_in"),
        "built in code on tvOS: the code spelt out, the wait as minutes and seconds" to listOf("code_described", "switch_wait"),
        "the empty person page's line, a stop for focus (A5; A4's follow-up): tvOS's page shows its header alone" to listOf("person_empty"),
    )

    /** `Words` that Android has no string for, by why (constants and functions by name). */
    private val tvosOwn = mapOf(
        "Android takes a title's kind from core (`kindLabel`)" to listOf("movie"),
        "VoiceOver's words for a lockup's placeholder bar" to listOf("loadingTitle"),
        "on tvOS but used by nothing there either (Android's went with A8's titled sort list and A5's season table)" to
            listOf("sortBy", "status"),
        "the list rows' words; Android's went with A6's lockups (tvOS keeps them in hints and an approver's line)" to
            listOf("requested", "modified", "issueType", "issueOpened", "openedBy", "waitingForApproval"),
        "helpers with no sentence of their own (the sentences are their callers')" to
            listOf("capitalisedFirst", "sorts", "signInFailure", "mediaServerFailure"),
        "built in code on tvOS, or plural forms (Android's are listed in androidOwn or are plurals)" to listOf("codeDescribed", "switchWait", "switchWrong"),
    )

    /** Strings both apps have that differ on purpose, by why: each must still differ. */
    private val onPurpose = mapOf(
        "TalkBack's action label, in Android's imperative (\"Double-tap to open…\"); tvOS's is a VoiceOver hint" to listOf("waiting_open"),
    )

    @Test
    fun theSharedSentencesAreTheSameOnBothApps() {
        assertTrue("Android's strings at ${File("src/main/res/values/strings.xml").absolutePath}", android.size > 150)
        assertTrue("tvOS's words (${words.lets.size} constants, ${words.funcs.size} functions)", words.lets.size > 150 && words.funcs.size > 30)
        val own = androidOwn.values.flatten().toSet()
        val purposely = onPurpose.values.flatten().toSet()
        val differ = mutableListOf<String>()
        val unpaired = mutableListOf<String>()
        var paired = 0
        for ((name, text) in android) {
            if (name in own || name in purposely) continue
            val said = sayings(name, text)
            when {
                said == null -> unpaired += "$name \"$text\""
                normal(text) !in said.map(::normal) -> differ += "$name: Android \"$text\", tvOS ${said.joinToString(" or ") { "\"$it\"" }}"
                else -> paired++
            }
        }
        assertEquals(
            "strings with no counterpart in Words.swift: name it in `renamed`, or list it with a reason",
            emptyList<String>(),
            unpaired,
        )
        assertEquals("the two apps say these differently", emptyList<String>(), differ)
        assertTrue("most strings are shared ($paired)", paired > 200)
    }

    /**
     * The brief's way round (the review's X11): each of `Words`' sentences is in Android's `strings.xml`, word for word. A
     * constant by its words, under any name; a function by the Android string paired with it (its words checked by
     * [theSharedSentencesAreTheSameOnBothApps]).
     */
    @Test
    fun everySentenceOfTvosIsInAndroidsStrings() {
        val own = tvosOwn.values.flatten().toSet() + onPurpose.values.flatten().map(::camel)
        val said = android.values.map(::normal).toSet()
        val missing = words.lets.filter { (name, text) -> name !in own && normal(text) !in said }.map { (name, text) -> "Words.$name \"$text\"" }
        val paired = android.keys.map { renamed[it] ?: camel(it) }.toSet()
        val unsaid = words.funcs.keys.filter { it !in own && it !in paired }.map { "Words.$it(…)" }
        assertEquals("tvOS says these and Android has no string for them: add one, or list it in tvosOwn with a reason", emptyList<String>(), missing + unsaid)
        val stale = tvosOwn.values.flatten().filter { it !in words.lets && it !in words.funcs }
        assertEquals("listed in tvosOwn, but no longer in Words.swift", emptyList<String>(), stale)
    }

    @Test
    fun theListedExceptionsStillHold() {
        val stale = (androidOwn.values.flatten() + onPurpose.values.flatten()).filter { it !in android }
        assertEquals("listed, but no longer Android's: take them off the list", emptyList<String>(), stale)
        val caughtUp = onPurpose.values.flatten().filter { name ->
            val said = sayings(name, android.getValue(name))
            said == null || normal(android.getValue(name)) in said.map(::normal)
        }
        assertEquals("on purpose different, but Android says it as tvOS does now (or tvOS lost it): move it into the pairs", emptyList<String>(), caughtUp)
    }

    @Test
    fun noExclamationMarks() {
        assertEquals(
            "an exclamation mark in Android's words (tvOS has none since polish part 7)",
            emptyList<String>(),
            android.filter { (_, text) -> '!' in text }.keys.toList(),
        )
        assertEquals("an exclamation mark in tvOS's words", emptyList<String>(), (words.lets.values + words.funcs.values.flatten()).filter { '!' in it })
    }

    /** What `Words` says for Android's [name] (one sentence, or a function's several); null when it has no counterpart. */
    private fun sayings(name: String, text: String): List<String>? {
        val swift = renamed[name] ?: camel(name)
        val let = words.lets[swift]?.let(::listOf)
        val func = words.funcs[swift]
        // A string with placeholders is a function's; one without, a constant's, else a function's fixed sentence.
        return if (PLACEHOLDER.containsMatchIn(text)) func ?: let else let ?: func
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+)\$0?\d*[sd]""")

        fun camel(name: String): String = name.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")

        /** Both sides' placeholders written alike: `%1$s`, `%2$02d` and tvOS's own (made `%1$`) all as `%1$`. */
        fun normal(text: String): String = PLACEHOLDER.replace(text) { "%${it.groupValues[1]}\$" }

        /** `<string name="…">…</string>`, as Android reads them: `\'` and `\"` unescaped, XML's entities read. */
        fun androidStrings(file: File): Map<String, String> =
            Regex("""<string name="([a-z_0-9]+)">(.*?)</string>""").findAll(file.readText()).associate { match ->
                match.groupValues[1] to match.groupValues[2]
                    .replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
                    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
            }
    }
}

/**
 * The string literals of `Words.swift`, read from its source: each `static let name = "…"`, and each `static func`'s
 * sentences (every literal in its body, in order), with an interpolation of the function's *n*th parameter written as
 * Android writes a placeholder (`\(server)` is `%1$`; in a one-parameter function, anything interpolated is that
 * parameter's), and any other as `%?$`. Swift's escapes are read (`\u{201C}`,
 * `\"`); comments are skipped.
 */
internal class SwiftWords(source: String) {
    val lets = linkedMapOf<String, String>()
    val funcs = linkedMapOf<String, MutableList<String>>()

    private val text = source
    private var at = 0

    init {
        while (at < text.length) {
            when {
                text.startsWith("//", at) -> at = text.indexOf('\n', at).let { if (it < 0) text.length else it }
                text.startsWith("/*", at) -> at = text.indexOf("*/", at) + 2
                text[at] == '"' -> literal(emptyList())
                text.startsWith("static let ", at) -> constant()
                text.startsWith("static func ", at) -> function()
                else -> at++
            }
        }
    }

    private fun constant() {
        val match = Regex("""\Gstatic let (\w+)\s*=\s*""").find(text, at) ?: run { at++; return }
        at = match.range.last + 1
        if (text[at] != '"') return
        lets[match.groupValues[1]] = literal(emptyList())
    }

    private fun function() {
        val match = Regex("""\Gstatic func (\w+)\(""").find(text, at) ?: run { at++; return }
        at = match.range.last + 1
        val params = balanced('(', ')').split(',').map { it.substringBefore(':').trim().split(Regex("\\s+")).last() }.filter { it.isNotEmpty() }
        val open = text.indexOf('{', at)
        at = open + 1
        var depth = 1
        val said = funcs.getOrPut(match.groupValues[1]) { mutableListOf() }
        while (at < text.length && depth > 0) {
            when {
                text.startsWith("//", at) -> at = text.indexOf('\n', at)
                text[at] == '"' -> said += literal(params)
                text[at] == '{' -> { depth++; at++ }
                text[at] == '}' -> { depth--; at++ }
                else -> at++
            }
        }
    }

    /** From an opening [open] already passed, the text up to its matching [close]; [at] after it. */
    private fun balanced(open: Char, close: Char): String {
        val start = at
        var depth = 1
        while (depth > 0) {
            when (text[at]) {
                '"' -> { literal(emptyList()); continue }
                open -> depth++
                close -> depth--
            }
            at++
        }
        return text.substring(start, at - 1)
    }

    /** The literal starting at [at] (its opening quote), read; [at] after its closing quote. */
    private fun literal(params: List<String>): String {
        check(!text.startsWith("\"\"\"", at)) { "a multi-line string in Words.swift at $at: teach WordsParityTest to read it" }
        at++
        val out = StringBuilder()
        while (text[at] != '"') {
            if (text[at] != '\\') {
                out.append(text[at++])
                continue
            }
            val next = text[at + 1]
            at += 2
            when (next) {
                '(' -> {
                    val expression = balanced('(', ')')
                    // By the parameter it names; a one-parameter function's closure argument (`$0`) is that parameter.
                    val index = params.indexOfFirst { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(expression) }
                        .let { if (it < 0 && params.size == 1) 0 else it }
                    out.append(if (index >= 0) "%${index + 1}\$" else "%?\$")
                }
                'u' -> {
                    val end = text.indexOf('}', at)
                    out.appendCodePoint(text.substring(at + 1, end).toInt(16))
                    at = end + 1
                }
                'n' -> out.append('\n')
                't' -> out.append('\t')
                else -> out.append(next)
            }
        }
        at++
        return out.toString()
    }
}
