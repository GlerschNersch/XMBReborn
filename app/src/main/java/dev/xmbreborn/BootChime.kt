package dev.xmbreborn

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * The start-up sound, played once per power-on like the PS3's boot chime. Only the user's own
 * file is used (files/sounds/coldboot.m4a, e.g. converted from their firmware's
 * coldboot_stereo.ac3); the app ships none. A launcher restarts whenever Android reclaims its
 * memory, so this keys on the device's boot count rather than on process start.
 */
class BootChime(private val context: Context) {
    private var player: MediaPlayer? = null
    private var startedAt = 0L
    private var markPlayed: (() -> Unit)? = null

    val playing: Boolean get() = player != null

    /**
     * Plays if there's a file and it hasn't played since this boot; [onDone] runs on completion.
     * It only counts as played once it has run a couple of seconds ([markPlayed] gets the boot
     * count then), so a start cut short (the activity pausing right after launch, or at power-on
     * behind the lock screen) plays again on the next resume instead of being lost.
     */
    fun playOncePerBoot(lastBoot: Int, markPlayed: (Int) -> Unit, onDone: () -> Unit): Boolean {
        val f = file(context)
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (!f.isFile || boot < 0 || boot == lastBoot || player != null) return false
        val p = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(f.path)
                setOnCompletionListener { this@BootChime.stop(); onDone() }
                prepare()
                start()
            }
        }.onFailure { Log.w("XmbReborn", "Start-up sound failed", it) }.getOrNull() ?: return false
        player = p
        startedAt = SystemClock.elapsedRealtime()
        this.markPlayed = { markPlayed(boot) }
        return true
    }

    /** Stops it (e.g. home was left mid-chime); counts as played if it got far enough. */
    fun stop() {
        val p = player ?: return
        if (SystemClock.elapsedRealtime() - startedAt >= COUNTS_AFTER_MS) markPlayed?.invoke()
        markPlayed = null
        p.release()
        player = null
    }

    companion object {
        fun file(context: Context) = File(File(context.filesDir, "sounds"), "coldboot.m4a")
        private const val COUNTS_AFTER_MS = 2000L
    }
}
