package com.tomdebres.hither.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomdebres.hither.core.rules.Fact
import com.tomdebres.hither.core.rules.FactKind
import com.tomdebres.hither.ui.media.factValue
import com.tomdebres.hither.ui.theme.Formats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/**
 * How Hither writes a value on the TV (`Formats`, H2's X9, DECISIONS 298), on the device so the TV's own ICU and date
 * formats write it: mirrors tvOS's `FormatsTests`. The words assume the test device's English (tv31 is en-US), as the
 * other tests do. Before A1 the title page wrote "94 minutes", "$400,290,973.00", "October 7, 2026", and split a line
 * with " | ".
 */
@RunWith(AndroidJUnit4::class)
class FormatsTest {
    /** A runtime as the Apple TV app writes it, not core's "94 minutes" (and not ICU's joined "1 hr, 34 min"). */
    @Test
    fun runtime() {
        assertEquals("1 hr 34 min", Formats.runtime(94))
        assertEquals("45 min", Formats.runtime(45))
        assertEquals("2 hr", Formats.runtime(120))
        assertNull("none for no runtime", Formats.runtime(0))
    }

    /** core's runtime words, as the title page and a series' Episode Runtime show them; anything else as given. */
    @Test
    fun coresRuntimeWordsRewritten() {
        assertEquals("1 hr 34 min", Formats.runtimeIn("94 minutes"))
        assertEquals("Horror, Science Fiction", Formats.runtimeIn("Horror, Science Fiction"))
        assertEquals("3 Seasons", Formats.runtimeIn("3 Seasons"))
        assertEquals("45 min", factValue(Fact("Episode Runtime", "45 minutes")))
    }

    /** Facts on one line between middle dots, the missing ones left out. */
    @Test
    fun join() {
        assertEquals("2026 · 1 hr 34 min · Horror", Formats.join(listOf("2026", null, "1 hr 34 min", "", "Horror")))
        assertNull(Formats.join(listOf(null, "")))
        assertEquals(" · ", Formats.Separator)
    }

    /** Money in whole dollars, as a film's facts show it. */
    @Test
    fun money() {
        assertEquals("$400,290,973", Formats.money(400_290_973))
        assertEquals("$400,290,973", factValue(Fact("Revenue", "400290973", FactKind.MONEY)))
        assertEquals("$0", factValue(Fact("Budget", "0", FactKind.MONEY)))
        assertEquals("as given when it is no amount", "not a number", factValue(Fact("Budget", "not a number", FactKind.MONEY)))
    }

    /** A server's day as every date ("Sep 16, 2026"); what is not a day comes back as it was. */
    @Test
    fun days() {
        val millis = LocalDate.of(2026, 9, 16).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("Sep 16, 2026", Formats.date(millis))
        assertEquals("Sep 16, 2026", Formats.day("2026-09-16"))
        assertNull(Formats.day("16/09/2026"))
        assertEquals("Sep 16, 2026", factValue(Fact("Release Date", "2026-09-16", FactKind.DATE)))
        assertEquals("as given when it is no date", "soon", factValue(Fact("Release Date", "soon", FactKind.DATE)))
    }

    /** Diagnostics' moment: the day as every date, then the time to the second. */
    @Test
    fun dateTime() {
        val millis = LocalDate.of(2026, 11, 15).atTime(18, 43, 27).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val written = Formats.dateTime(millis)
        assertTrue(written, written.startsWith("Nov 15, 2026"))
        assertTrue(written, written.contains("43:27"))
    }

    /** "3 days ago" within a week, the day beyond it; a time ahead of the TV's clock is now. */
    @Test
    fun relative() {
        val now = LocalDate.of(2026, 11, 17).atTime(12, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("3 days ago", Formats.relative("2026-11-14T12:00:00.000Z", now))
        val older = Formats.relative("2026-10-01T12:00:00.000Z", now)
        assertTrue("beyond a week, the day: $older", older == "Oct 1, 2026" || older == "Sep 30, 2026" || older == "Oct 2, 2026")
        assertEquals("a time ahead of the TV's clock reads as now", Formats.relative("2026-11-17T12:00:00.000Z", now), Formats.relative("2026-11-18T12:00:00.000Z", now))
        assertNull(Formats.relative("yesterday", now))
    }
}
