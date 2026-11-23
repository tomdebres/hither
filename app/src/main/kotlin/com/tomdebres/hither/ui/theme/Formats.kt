package com.tomdebres.hither.ui.theme

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateUtils
import java.text.DateFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Currency
import java.util.Date
import java.util.Locale

/**
 * How Hither writes a value on the TV (H2's X9; tvOS's `Formats`), one way for each kind, in the TV's language, so a
 * date never reads two ways on one screen. Before it, a film's release read "October 7, 2026" (a long date) where a
 * request read "3 days ago" and Diagnostics "Nov 15, 2026"; a runtime "94 minutes"; money "$400,290,973.00"; and a
 * person's facts and a title's line were split by a bar where every other line uses the middle dot (DECISIONS 298).
 *
 * Values come from `core` as it gives them (ISO dates, whole dollars, core's runtime words) and are written here, so
 * `core` and the models stay as they are.
 */
object Formats {
    /** Between facts on one line ("1 hr 34 min · Horror, Science Fiction"), as the Apple TV app separates them. */
    const val Separator = " · "

    /** The parts that are there, on one line between middle dots; null when none is. */
    fun join(parts: List<String?>): String? = parts.filterNot { it.isNullOrEmpty() }.takeIf { it.isNotEmpty() }?.joinToString(Separator)

    // ---- dates -------------------------------------------------------------------------------------------------------

    /** A day: "Sep 16, 2026" (the medium date), in the TV's language and order. */
    fun date(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    /** A day the server writes `yyyy-MM-dd` (TMDB's release and air dates, a birthday), as [date]; null if it is none. */
    fun day(iso: String): String? = runCatching {
        date(LocalDate.parse(iso).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }.getOrNull()

    /** A moment with its time, for Diagnostics: "Nov 15, 2026, 6:43:27 PM" in the TV's language. */
    fun dateTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(millis))

    /**
     * "3 days ago" within a week, the day ([date]) beyond it, for the server's ISO time; null if it is not one. A time the
     * server has ahead of the TV's clock reads as now (DECISIONS 66, 200).
     */
    fun relative(iso: String?, now: Long = System.currentTimeMillis()): String? {
        val then = instant(iso)?.coerceAtMost(now) ?: return null
        if (now - then >= WEEK_MS) return date(then)
        return DateUtils.getRelativeTimeSpanString(then, now, DateUtils.SECOND_IN_MILLIS).toString()
    }

    /** When something due is expected, from now: "in 42 minutes" (a download's estimate); null if [iso] is not a time. */
    fun due(iso: String?, now: Long = System.currentTimeMillis()): String? {
        val at = instant(iso) ?: return null
        return DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

    private fun instant(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

    // ---- runtime and money ------------------------------------------------------------------------------------------

    /**
     * A running time as the Apple TV app writes it: "1 hr 34 min", "45 min", "2 hr". Each unit in the TV's language
     * (ICU's short width), joined by a space: ICU's own joined form puts a comma between them, as tvOS found. Null for
     * none.
     */
    fun runtime(minutes: Int): String? {
        if (minutes <= 0) return null
        val format = MeasureFormat.getInstance(Locale.getDefault(), MeasureFormat.FormatWidth.SHORT)
        val hours = minutes / 60
        val rest = minutes % 60
        return listOfNotNull(
            hours.takeIf { it > 0 }?.let { format.format(Measure(it, MeasureUnit.HOUR)) },
            rest.takeIf { it > 0 }?.let { format.format(Measure(it, MeasureUnit.MINUTE)) },
        ).joinToString(" ")
    }

    /**
     * `core`'s runtime words ("94 minutes": a film's attribute, a series' Episode Runtime) as [runtime] writes them;
     * anything else as given. `core` is not changed by the redesign, so its words are rewritten here, in that one form.
     */
    fun runtimeIn(words: String): String = CoreMinutes.matchEntire(words)?.groupValues?.get(1)?.toIntOrNull()?.let(::runtime) ?: words

    private val CoreMinutes = Regex("""(\d+) minutes""")

    /** Whole US dollars, as Seerr's web writes a film's takings and budget: "$400,290,973" (no cents). */
    fun money(amount: Long): String = NumberFormat.getCurrencyInstance(Locale.US).apply {
        currency = Currency.getInstance("USD")
        maximumFractionDigits = 0
    }.format(amount)
}
