package dev.xmbreborn.xmb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * App icons as drawn at the bar's size, kept so the bar can be rebuilt (after every trip to an
 * app, for the recency order) without redrawing each icon: in memory for this process, and as
 * PNGs in the cache directory so a cold start skips the drawing too. An entry is only used for
 * the same [version] (the package's last update time), so an updated app's new icon shows.
 */
class IconCache(context: Context) {
    private val dir = File(context.cacheDir, "icons").apply { mkdirs() }
    private val memory = HashMap<String, Pair<Long, Bitmap>>()

    /** How many lookups the last build answered from memory, disk, or by drawing (for the log). */
    var hits = 0; var diskHits = 0; var misses = 0

    fun resetStats() { hits = 0; diskHits = 0; misses = 0 }

    @Synchronized
    fun get(key: String, version: Long, draw: () -> Bitmap): Bitmap {
        memory[key]?.let { (v, bmp) -> if (v == version) { hits++; return bmp } }
        val file = File(dir, fileName(key, version))
        val fromDisk = if (file.isFile) runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() else null
        if (fromDisk != null) {
            diskHits++
            memory[key] = version to fromDisk
            return fromDisk
        }
        misses++
        val bmp = draw()
        memory[key] = version to bmp
        // Older versions of this icon go; the new one is written for next time.
        dir.listFiles { f -> f.name.startsWith(stem(key) + "_") }?.forEach { it.delete() }
        runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return bmp
    }

    /** Drops what the last build didn't ask for (uninstalled apps), memory and disk alike. */
    @Synchronized
    fun keepOnly(keys: Set<String>) {
        memory.keys.retainAll(keys)
        val stems = keys.map(::stem).toSet()
        dir.listFiles()?.forEach { f -> if (f.name.substringBeforeLast('_') !in stems) f.delete() }
    }

    private fun stem(key: String) = Integer.toHexString(key.hashCode()) + Integer.toHexString(key.reversed().hashCode())
    private fun fileName(key: String, version: Long) = "${stem(key)}_$version.png"
}
