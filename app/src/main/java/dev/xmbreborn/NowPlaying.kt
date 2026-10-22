package dev.xmbreborn

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * Exists so the user can grant notification access, which is what Android asks of an app before
 * it may see other apps' media sessions. It does nothing with notifications themselves.
 */
class NowPlayingListener : NotificationListenerService()

/**
 * What's playing in another app (music, podcasts...), for the Now Playing row at the top of the
 * Music column, as the PS3 shows the current track there with its controls. Needs notification
 * access ([NowPlayingListener]); without it [track] is always null.
 */
class NowPlaying(private val context: Context, private val artPx: Int, private val onChange: () -> Unit) {
    class Track(val title: String, val artist: String?, val playing: Boolean, val art: Bitmap?, val pkg: String)

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, NowPlayingListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var lastArt: Pair<Any?, Bitmap?>? = null

    /** The current track, or null if nothing is playing (or access isn't granted). */
    @Volatile var track: Track? = null
        private set

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onSessionDestroyed() = pick()
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pick() }

    val allowed: Boolean
        get() = runCatching { sessions.getActiveSessions(listener); true }.getOrDefault(false)

    fun start() {
        runCatching { sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main) }
        pick()
    }

    fun stop() {
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    /** Follows the session that's playing, or else the most recent one. */
    private fun pick() {
        val all = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList())
        val next = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = next
            next?.registerCallback(callback, main)
        }
        update()
    }

    private fun update() {
        val c = controller
        val meta = c?.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val now = if (c == null || title.isNullOrBlank()) null else Track(
            title = title,
            artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            art = art(meta),
            pkg = c.packageName,
        )
        val old = track
        if (old?.title == now?.title && old?.artist == now?.artist && old?.playing == now?.playing && old?.art === now?.art && old?.pkg == now?.pkg) return
        track = now
        onChange()
    }

    /** The album art at icon size, kept while it's the same picture so the bar needn't re-upload it. */
    private fun art(meta: MediaMetadata?): Bitmap? {
        val src = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        lastArt?.let { (from, scaled) -> if (from === src) return scaled }
        val scaled = src?.let { runCatching {
            val side = minOf(it.width, it.height)
            val square = Bitmap.createBitmap(it, (it.width - side) / 2, (it.height - side) / 2, side, side)
            Bitmap.createScaledBitmap(square, artPx, artPx, true)
        }.getOrNull() }
        lastArt = src to scaled
        return scaled
    }

    fun playPause() {
        val t = controller?.transportControls ?: return
        if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
    }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    /** Opens the playing app at its own player screen, if it offers one. */
    fun open(): Boolean {
        val c = controller ?: return false
        c.sessionActivity?.let { pi ->
            // Since Android 14 a sender must say it lends its right to start activities (home is
            // in front, so it has it); without this the player's screen wouldn't open.
            val options = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic().apply {
                @Suppress("DEPRECATION")
                setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }.toBundle() else null
            return runCatching { pi.send(context, 0, null, null, null, null, options); true }.getOrDefault(false)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(c.packageName) ?: return false
        context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
