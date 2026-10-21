package dev.xmbreborn.xmb

import android.content.Context

/** The launcher's own settings, as on the PS3's Theme and Sound settings. */
class XmbPrefs(context: Context) {
    private val p = context.getSharedPreferences("xmb", Context.MODE_PRIVATE)

    /** [ORIGINAL] follows the month, as the PS3's "Original" colour; 0..11 pins one month's colour. */
    var color: Int
        get() = p.getInt("color", ORIGINAL).coerceIn(ORIGINAL, 11)
        set(v) = p.edit().putInt("color", v).apply()

    var ambient: Boolean
        get() = p.getBoolean("ambient", true)
        set(v) = p.edit().putBoolean("ambient", v).apply()

    /** Item ids of apps the user has hidden from the bar. */
    val hidden: Set<String> get() = p.getStringSet("hidden", emptySet())!!.toSet()

    fun setHidden(id: String, hide: Boolean) {
        val now = hidden.toMutableSet().apply { if (hide) add(id) else remove(id) }
        p.edit().putStringSet("hidden", now).apply()
    }

    /** When each recently opened item was last opened (epoch ms), newest [RECENT_KEEP] kept. */
    val recent: Map<String, Long>
        get() = p.getString("recent", "")!!.lineSequence().mapNotNull { line ->
            val cut = line.indexOf(' ')
            val at = line.take(cut.coerceAtLeast(0)).toLongOrNull() ?: return@mapNotNull null
            line.substring(cut + 1) to at
        }.toMap()

    fun markOpened(id: String, now: Long = System.currentTimeMillis()) {
        val kept = (recent + (id to now)).entries.sortedByDescending { it.value }.take(RECENT_KEEP)
        p.edit().putString("recent", kept.joinToString("\n") { "${it.value} ${it.key}" }).apply()
    }

    /** Play the start-up sound (if the user supplied one) at the first launch after power-on. */
    var chime: Boolean
        get() = p.getBoolean("chime", true)
        set(v) = p.edit().putBoolean("chime", v).apply()

    /** The device boot the start-up sound last played in. */
    var chimeBoot: Int
        get() = p.getInt("chimeBoot", -1)
        set(v) = p.edit().putInt("chimeBoot", v).apply()

    /** Tree URI of the folder ARMSX3's Frontend Export writes its .ps3 files to, or null. */
    var ps3Folder: String?
        get() = p.getString("ps3Folder", null)
        set(v) = p.edit().putString("ps3Folder", v).apply()

    /** Show the floating "+" for adding widgets (adding from a row's options always works). */
    var widgetButton: Boolean
        get() = p.getBoolean("widgetButton", true)
        set(v) = p.edit().putBoolean("widgetButton", v).apply()

    var sounds: Boolean
        get() = p.getBoolean("sounds", true)
        set(v) = p.edit().putBoolean("sounds", v).apply()

    companion object {
        const val ORIGINAL = -1
        const val RECENT_KEEP = 60
    }
}
