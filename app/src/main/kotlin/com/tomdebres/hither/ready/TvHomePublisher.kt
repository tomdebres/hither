package com.tomdebres.hither.ready

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.tvprovider.media.tv.BasePreviewProgram
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.tomdebres.hither.MainActivity
import com.tomdebres.hither.R
import com.tomdebres.hither.core.model.MediaType
import com.tomdebres.hither.core.rules.TitleLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hither's cards on the TV's home screen, in Android's TV provider (DECISIONS 76): one preview
 * channel, "Ready to Watch", and Watch Next entries of the "new" type. Launchers read both: Google's
 * shows the channel as a row and Watch Next as "Play Next"; Shield Launcher shows Watch Next in its
 * Continue Watching row (Hither's tile focused or not), and the channel under Hither's tile when Watch
 * Next has nothing of Hither's.
 *
 * Each card is keyed by its title (`movie:550`, the programme's internal provider id), so a publish
 * changes rows in place: a title kept keeps its row, a new one is added, one gone is deleted, and a
 * row that has not changed is not written (a launcher redraws on every write). The provider gives an
 * app only its own rows; on a device without it (not a TV) every call does nothing.
 *
 * Art, as Shield Launcher's session asked (2026-11-11): the 2:3 poster in POSTER_ART_URI and the 16:9
 * backdrop in THUMBNAIL_URI, each with its aspect set, both from the server's image proxy. A card
 * opens Hither's page for the title through an explicit intent ([intentFor]).
 */
class TvHomePublisher(context: Context) : HomePublisher {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver
    private val helper by lazy { PreviewChannelHelper(this.context) }

    /** Rows written (inserted, updated or deleted) so far: a test's way to see that nothing unchanged is written. */
    @VisibleForTesting
    internal var writes = 0
        private set

    override suspend fun publish(channel: List<HomeItem>, watchNext: List<HomeItem>) = withContext(Dispatchers.IO) {
        guarded { syncChannel(channel) }
        guarded { syncWatchNext(watchNext) }
    }

    override suspend fun removeWatchNext(key: String) = withContext(Dispatchers.IO) {
        guarded { watchNextRows().filter { it.key == key }.forEach { deleteWatchNext(it.id) } }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        guarded { helper.allChannels.forEach { helper.deletePreviewChannel(it.id) } }
        guarded { watchNextRows().forEach { deleteWatchNext(it.id) } }
    }

    /** The channel's programmes are [items], weighted so the first is shown first. Creates the channel the first time. */
    private fun syncChannel(items: List<HomeItem>) {
        val channelId = channelId(create = items.isNotEmpty()) ?: return
        val existing = rows(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION) { c ->
            row(c, PreviewProgram.fromCursor(c).toContentValues())
        }
        val wanted = items.mapIndexed { index, item -> item.key to previewProgram(channelId, item, weight = items.size - index) }.toMap()
        for (row in existing) {
            val program = wanted[row.key]
            when {
                program == null -> wrote { helper.deletePreviewProgram(row.id) }
                changed(row.values, program.toContentValues()) -> wrote { helper.updatePreviewProgram(row.id, program) }
            }
        }
        val kept = existing.mapTo(HashSet()) { it.key }
        wanted.filterKeys { it !in kept }.values.forEach { wrote { helper.publishPreviewProgram(it) } }
    }

    /** Watch Next holds [items] and nothing else of Hither's. */
    private fun syncWatchNext(items: List<HomeItem>) {
        val existing = watchNextRows()
        val wanted = items.associate { it.key to watchNextProgram(it) }
        for (row in existing) {
            val program = wanted[row.key]
            when {
                program == null -> deleteWatchNext(row.id)
                changed(row.values, program.toContentValues()) -> wrote { helper.updateWatchNextProgram(program, row.id) }
            }
        }
        val kept = existing.mapTo(HashSet()) { it.key }
        wanted.filterKeys { it !in kept }.values.forEach { wrote { helper.publishWatchNextProgram(it) } }
    }

    /** Hither's channel, made (and Android's launcher asked once to show it) when [create] and there is none. */
    private fun channelId(create: Boolean): Long? {
        val channels = helper.allChannels
        channels.firstOrNull { it.internalProviderId == CHANNEL_ID }?.let { return it.id }
        if (!create) return null
        val channel = PreviewChannel.Builder()
            .setDisplayName(context.getString(R.string.ready_channel))
            .setAppLinkIntentUri(appIntent().toUri(Intent.URI_INTENT_SCHEME).toUri())
            .setInternalProviderId(CHANNEL_ID)
            .setLogo(ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!.toBitmap(LOGO_PX, LOGO_PX))
            .build()
        val id = wrote { helper.publishChannel(channel) }
        // A preview channel shows on Google's launcher only once made browsable; a package's first
        // request is granted. Shield Launcher reads the channel either way.
        runCatching { TvContractCompat.requestChannelBrowsable(context, id) }
        return id
    }

    private fun previewProgram(channelId: Long, item: HomeItem, weight: Int): PreviewProgram =
        PreviewProgram.Builder()
            .setChannelId(channelId)
            .setWeight(weight)
            .also { fill(it, item) }
            .build()

    private fun watchNextProgram(item: HomeItem): WatchNextProgram =
        WatchNextProgram.Builder()
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEW)
            .setLastEngagementTimeUtcMillis(item.readyAt ?: 0L)
            .also { fill(it, item) }
            .build()

    /**
     * What both kinds of card carry. These are the setters Android's guide uses on
     * `PreviewProgram.Builder` and `WatchNextProgram.Builder`; lint calls them restricted because the
     * library declares them in a base class it marks as its own (it says so on the concrete builders
     * too), so the check is set aside here and nowhere else.
     */
    @SuppressLint("RestrictedApi")
    private fun fill(builder: BasePreviewProgram.Builder<*>, item: HomeItem) {
        builder.setType(if (item.type == MediaType.TV) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES else TvContractCompat.PreviewPrograms.TYPE_MOVIE)
        builder.setTitle(item.art.title)
        // Tom, 2026-11-11: the card says why it is there. Shield Launcher reads it as the status line.
        builder.setDescription(context.getString(R.string.ready_to_watch))
        item.art.year?.let { builder.setReleaseDate(it) }
        item.art.poster?.let {
            builder.setPosterArtUri(it.toUri())
            builder.setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3)
        }
        item.art.backdrop?.let {
            builder.setThumbnailUri(it.toUri())
            builder.setThumbnailAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
        }
        // One link for both kinds of card: Shield Launcher pairs a title's Watch Next card with its channel program by
        // this intent URI (DECISIONS 143). Never change one without the other, and tell shield-launcher's sessions
        // before changing the hither://title form.
        builder.setIntentUri(intentFor(TitleLink(item.type, item.tmdbId)).toUri(Intent.URI_INTENT_SCHEME).toUri())
        builder.setInternalProviderId(item.key)
    }

    private fun appIntent(): Intent =
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName(context, MainActivity::class.java)).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)

    /** The explicit intent a card starts: Hither's activity, with the title's link. */
    fun intentFor(link: TitleLink): Intent =
        Intent(Intent.ACTION_VIEW, link.uri.toUri()).setComponent(ComponentName(context, MainActivity::class.java))

    private fun watchNextRows(): List<Row> =
        rows(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION) { c ->
            row(c, WatchNextProgram.fromCursor(c).toContentValues())
        }

    private fun deleteWatchNext(id: Long) {
        wrote { resolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
    }

    private inline fun <T> wrote(write: () -> T): T = write().also { writes++ }

    /** A row's id and key read from the cursor itself (the library's getters for them are its own). */
    private fun row(c: Cursor, values: ContentValues): Row = Row(
        id = c.getLong(c.getColumnIndexOrThrow(BaseColumns._ID)),
        key = c.getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString),
        values = values,
    )

    private fun <T> rows(uri: Uri, projection: Array<String>, read: (Cursor) -> T): List<T> =
        resolver.query(uri, projection, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(read(c)) }
        }.orEmpty()

    /** Whether a row differs from what it should hold, in what Hither writes. */
    private fun changed(row: ContentValues, wanted: ContentValues): Boolean =
        wanted.keySet().any { key -> key != "_id" && row.get(key)?.toString() != wanted.get(key)?.toString() }

    /** A provider that refuses (no TV provider on this device, or it is busy) leaves things as they are. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: IllegalStateException) {
        } catch (_: NullPointerException) {
            // PreviewChannelHelper's own way of saying the provider took nothing.
        } catch (_: java.io.IOException) {
        }
    }

    private class Row(val id: Long, val key: String?, val values: ContentValues)

    companion object {
        /** The channel's internal provider id: there is one. */
        const val CHANNEL_ID = "ready"
        private const val LOGO_PX = 160
    }
}
