package com.tomdebres.hither.ready

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tomdebres.hither.MainActivity
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.TitleArt
import com.tomdebres.hither.core.rules.parseTitleLink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Hither's cards in the device's own TV provider, as a launcher reads them (DECISIONS 76): the
 * "Ready to Watch" channel and Watch Next. Runs on `tv31`; touches only Hither's own rows.
 */
@RunWith(AndroidJUnit4::class)
class HomeChannelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val publisher = TvHomePublisher(context)
    private val helper = PreviewChannelHelper(context)
    private val base = "http://10.0.2.2:5056/imageproxy/tmdb/t/p"

    private fun item(id: Int, type: MediaType = MediaType.MOVIE, readyAt: Long? = 1_800_000_000_000L + id) = HomeItem(
        key = "${type.api}:$id",
        type = type,
        tmdbId = id,
        art = TitleArt("Title $id", "2020", "$base/w300_and_h450_face/p$id.jpg", "$base/w780/b$id.jpg"),
        readyAt = readyAt,
    )

    @Before
    @After
    fun clean() = runBlocking { publisher.clear() }

    private fun channels(): List<PreviewChannel> = helper.allChannels

    private fun programs(): List<PreviewProgram> {
        val channel = channels().single { it.internalProviderId == TvHomePublisher.CHANNEL_ID }
        return query(TvContractCompat.buildPreviewProgramsUriForChannel(channel.id), PreviewProgram.PROJECTION, PreviewProgram::fromCursor)
            .sortedByDescending { it.weight }
    }

    private fun watchNext(): List<WatchNextProgram> =
        query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, WatchNextProgram::fromCursor)

    private fun <T> query(uri: Uri, projection: Array<String>, read: (android.database.Cursor) -> T): List<T> =
        context.contentResolver.query(uri, projection, null, null, null)!!.use { c -> buildList { while (c.moveToNext()) add(read(c)) } }

    @Test
    fun publishesTheChannelInOrderWithArtAndALinkIntoHither() = runBlocking {
        publisher.publish(listOf(item(3), item(1421, MediaType.TV), item(1)), watchNext = emptyList())
        val channel = channels().single()
        assertEquals("Ready to Watch", channel.displayName)
        assertEquals(TvHomePublisher.CHANNEL_ID, channel.internalProviderId)
        assertEquals(TvContractCompat.Channels.TYPE_PREVIEW, channel.type)

        val shown = programs()
        assertEquals(listOf("movie:3", "tv:1421", "movie:1"), shown.map { it.internalProviderId })
        val series = shown[1]
        assertEquals("Title 1421", series.title)
        // Why it is there (Tom, 2026-11-11): Shield Launcher shows an app's own description as the status.
        assertEquals("Ready to Watch", series.description)
        assertEquals(TvContractCompat.PreviewPrograms.TYPE_TV_SERIES, series.type)
        assertEquals(TvContractCompat.PreviewPrograms.TYPE_MOVIE, shown[0].type)
        assertEquals("$base/w300_and_h450_face/p1421.jpg", series.posterArtUri.toString())
        assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3, series.posterArtAspectRatio)
        assertEquals("$base/w780/b1421.jpg", series.thumbnailUri.toString())
        assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9, series.thumbnailAspectRatio)
        assertTrue(series.isBrowsable)

        // As a launcher starts it: Hither's activity, with the title's link.
        val intent = Intent.parseUri(series.intentUri.toString(), Intent.URI_INTENT_SCHEME)
        val target = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        assertEquals(MainActivity::class.java.name, target?.activityInfo?.name)
        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals("tv:1421", parseTitleLink(intent.dataString)?.key)
        // The channel's own link opens Hither.
        val app = Intent.parseUri(channel.appLinkIntentUri.toString(), Intent.URI_INTENT_SCHEME)
        assertEquals(MainActivity::class.java.name, context.packageManager.resolveActivity(app, 0)?.activityInfo?.name)
    }

    @Test
    fun republishingChangesRowsInPlaceAndWritesNothingUnchanged() = runBlocking {
        publisher.publish(listOf(item(1), item(2), item(3)), listOf(item(1), item(2)))
        val ids = programs().associate { it.internalProviderId to it.id }
        val nextIds = watchNext().associate { it.internalProviderId to it.id }

        // The same again: nothing to write. (A ContentObserver on the provider sees none of its
        // writes on tv31, update or not, so the publisher counts its own.)
        val before = publisher.writes
        publisher.publish(listOf(item(1), item(2), item(3)), listOf(item(1), item(2)))
        assertEquals("an unchanged publish wrote to the provider", before, publisher.writes)

        // 4 is new and first, 2 has gone, 1 and 3 keep their rows.
        publisher.publish(listOf(item(4), item(1), item(3)), listOf(item(4), item(1)))
        val after = programs()
        assertEquals(listOf("movie:4", "movie:1", "movie:3"), after.map { it.internalProviderId })
        assertEquals(ids["movie:1"], after[1].id)
        assertEquals(ids["movie:3"], after[2].id)
        assertEquals(setOf("movie:4", "movie:1"), watchNext().map { it.internalProviderId }.toSet())
        assertEquals(nextIds["movie:1"], watchNext().single { it.internalProviderId == "movie:1" }.id)
        assertEquals(1, channels().size)
    }

    @Test
    fun watchNextEntriesAreNewWithTheirTimeAndLeaveWhenAsked() = runBlocking {
        publisher.publish(listOf(item(1), item(2)), listOf(item(1), item(2, MediaType.MOVIE, readyAt = 1_700_000_000_000L)))
        val entries = watchNext().associateBy { it.internalProviderId }
        assertEquals(setOf("movie:1", "movie:2"), entries.keys)
        val two = entries.getValue("movie:2")
        assertEquals(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW, two.watchNextType)
        assertEquals(1_700_000_000_000L, two.lastEngagementTimeUtcMillis)
        assertEquals("Title 2", two.title)
        // The status line Shield Launcher shows for a whole title in Watch Next: the stored short
        // description, used only as one line of at most 32 characters (its rule, 2026-11-11).
        val stored = context.contentResolver.query(
            TvContractCompat.buildWatchNextProgramUri(two.id),
            arrayOf(TvContractCompat.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION),
            null,
            null,
            null,
        )!!.use { c -> c.moveToFirst(); c.getString(0) }
        assertEquals("Ready to Watch", stored)
        assertTrue("one line of at most 32 characters: \"$stored\"", stored.length <= 32 && '\n' !in stored)
        assertEquals(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9, two.thumbnailAspectRatio)
        assertEquals("movie:2", parseTitleLink(Intent.parseUri(two.intentUri.toString(), Intent.URI_INTENT_SCHEME).dataString)?.key)

        publisher.removeWatchNext("movie:1")
        assertEquals(listOf("movie:2"), watchNext().map { it.internalProviderId })
        // The channel keeps both.
        assertEquals(listOf("movie:1", "movie:2"), programs().map { it.internalProviderId })

        publisher.clear()
        assertEquals(emptyList<PreviewChannel>(), channels())
        assertEquals(emptyList<WatchNextProgram>(), watchNext())
    }

    /**
     * Shield Launcher pairs a title's Watch Next card with its channel program by the intent URI (DECISIONS 143), so
     * the two carry the same link to the character, for a film and a series.
     */
    @Test
    fun aTitlesWatchNextCardAndChannelProgramCarryTheSameLink() = runBlocking {
        publisher.publish(listOf(item(1), item(1421, MediaType.TV)), listOf(item(1), item(1421, MediaType.TV)))
        val programs = programs().associateBy { it.internalProviderId }
        val next = watchNext().associateBy { it.internalProviderId }
        for (key in listOf("movie:1", "tv:1421")) {
            val link = programs.getValue(key).intentUri.toString()
            assertEquals("$key: the Watch Next card's link against the channel program's", link, next.getValue(key).intentUri.toString())
            assertEquals(key, parseTitleLink(Intent.parseUri(link, Intent.URI_INTENT_SCHEME).dataString)?.key)
        }
    }

    @Test
    fun nothingReadyMakesNoChannel() = runBlocking {
        publisher.publish(emptyList(), emptyList())
        assertEquals(emptyList<PreviewChannel>(), channels())
        // A channel made earlier is emptied, not removed, when nothing is ready any more.
        publisher.publish(listOf(item(1)), emptyList())
        publisher.publish(emptyList(), emptyList())
        assertEquals(1, channels().size)
        assertEquals(emptyList<PreviewProgram>(), programs())
    }

    @Test
    fun theBackgroundRefreshIsScheduledEveryThirtyMinutesOnANetwork() {
        ReadyWorker.schedule(context)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(ReadyWorker.NAME).get(10, TimeUnit.SECONDS)
        val info = work.single()
        assertTrue(info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING)
        assertNotNull(info.periodicityInfo)
        assertEquals(TimeUnit.MINUTES.toMillis(30), info.periodicityInfo!!.repeatIntervalMillis)
        assertEquals(androidx.work.NetworkType.CONNECTED, info.constraints.requiredNetworkType)
    }
}
