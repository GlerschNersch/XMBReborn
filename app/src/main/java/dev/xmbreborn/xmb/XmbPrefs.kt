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

    var sounds: Boolean
        get() = p.getBoolean("sounds", true)
        set(v) = p.edit().putBoolean("sounds", v).apply()

    companion object {
        const val ORIGINAL = -1
    }
}
