package dev.xmbreborn.xmb

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The launcher's own settings, as on the PS3's Theme and Sound settings. Those worth keeping on a
 * new phone live in "xmb" (Android's backup and [export] carry them); ones tied to this device -
 * a folder permission, a boot count - in "xmb_device", which neither does.
 */
class XmbPrefs(context: Context) {
    private val p = context.getSharedPreferences("xmb", Context.MODE_PRIVATE)
    private val device = context.getSharedPreferences("xmb_device", Context.MODE_PRIVATE)

    init {
        // 0.4.1 and earlier kept these in "xmb".
        if (p.contains("ps3Folder") || p.contains("chimeBoot")) {
            device.edit().apply {
                p.getString("ps3Folder", null)?.let { putString("ps3Folder", it) }
                if (p.contains("chimeBoot")) putInt("chimeBoot", p.getInt("chimeBoot", -1))
            }.apply()
            p.edit().remove("ps3Folder").remove("chimeBoot").apply()
        }
    }

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
        get() = device.getInt("chimeBoot", -1)
        set(v) = device.edit().putInt("chimeBoot", v).apply()

    /** Tree URI of the folder ARMSX3's Frontend Export writes its .ps3 files to, or null. */
    var ps3Folder: String?
        get() = device.getString("ps3Folder", null)
        set(v) = device.edit().putString("ps3Folder", v).apply()

    /** Show the floating "+" for adding widgets (adding from a row's options always works). */
    var widgetButton: Boolean
        get() = p.getBoolean("widgetButton", true)
        set(v) = p.edit().putBoolean("widgetButton", v).apply()

    /** Draw the PS3's date-and-time bar; off by default, as the status bar already shows the time. */
    var clock: Boolean
        get() = p.getBoolean("clock", false)
        set(v) = p.edit().putBoolean("clock", v).apply()

    /** Index of the Backdrop drawn behind the bar (0 = the PS3's wave). */
    var backdrop: Int
        get() = p.getInt("backdrop", 0).coerceAtLeast(0)
        set(v) = p.edit().putInt("backdrop", v).apply()

    /** Draw apps' monochrome icons lit like the XMB's own (apps without one keep their colours). */
    var xmbIcons: Boolean
        get() = p.getBoolean("xmbIcons", false)
        set(v) = p.edit().putBoolean("xmbIcons", v).apply()

    /** Index into [SIZES]: Normal is the PS3's own proportions; Large (the default) suits a phone. */
    var size: Int
        get() = p.getInt("size", SIZE_LARGE).coerceIn(0, SIZES.size - 1)
        set(v) = p.edit().putInt("size", v).apply()

    val sizeScale: Float get() = SIZES[size]

    /** Show the phone's photo and video albums in the Photo and Video columns (needs permission). */
    var media: Boolean
        get() = p.getBoolean("media", false)
        set(v) = p.edit().putBoolean("media", v).apply()

    var sounds: Boolean
        get() = p.getBoolean("sounds", true)
        set(v) = p.edit().putBoolean("sounds", v).apply()

    /** Everything in "xmb" as a small JSON file (Settings > Backup > Save Backup). */
    fun export(): String = JSONObject().apply {
        put("app", "XMB Reborn")
        put("version", BACKUP_VERSION)
        put("color", color)
        put("ambient", ambient)
        put("sounds", sounds)
        put("chime", chime)
        put("widgetButton", widgetButton)
        put("clock", clock)
        put("size", size)
        put("xmbIcons", xmbIcons)
        put("backdrop", backdrop)
        put("folders", JSONObject(p.getString(UserFolders.KEY, "{}")!!))
        put("hidden", JSONArray(hidden.sorted()))
        put("recent", JSONObject().apply { recent.forEach { (id, at) -> put(id, at) } })
    }.toString(2)

    /** Replaces these settings with a backup's; throws [IllegalArgumentException] if it isn't one. */
    fun import(text: String) {
        val o = runCatching { JSONObject(text) }.getOrNull()
        require(o != null && o.optString("app") == "XMB Reborn") { "Not an XMB Reborn backup" }
        require(o.optInt("version") in 1..BACKUP_VERSION) { "Backup is from a newer version" }
        val hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).map(a::getString).toSet() } ?: emptySet()
        val recent = o.optJSONObject("recent")?.let { r -> r.keys().asSequence().associateWith { r.getLong(it) } } ?: emptyMap()
        p.edit()
            .putInt("color", o.optInt("color", ORIGINAL).coerceIn(ORIGINAL, 11))
            .putBoolean("ambient", o.optBoolean("ambient", true))
            .putBoolean("sounds", o.optBoolean("sounds", true))
            .putBoolean("chime", o.optBoolean("chime", true))
            .putBoolean("widgetButton", o.optBoolean("widgetButton", true))
            .putBoolean("clock", o.optBoolean("clock", false))
            .putInt("size", o.optInt("size", SIZE_LARGE).coerceIn(0, SIZES.size - 1))
            .putBoolean("xmbIcons", o.optBoolean("xmbIcons", false))
            .putInt("backdrop", o.optInt("backdrop", 0).coerceAtLeast(0))
            .putString(UserFolders.KEY, o.optJSONObject("folders")?.toString() ?: "{}")
            .putStringSet("hidden", hidden)
            .putString("recent", recent.entries.sortedByDescending { it.value }.take(RECENT_KEEP)
                .joinToString("\n") { "${it.value} ${it.key}" })
            .apply()
    }

    companion object {
        const val ORIGINAL = -1
        const val RECENT_KEEP = 60
        const val BACKUP_VERSION = 1
        val SIZE_NAMES = listOf("Normal", "Large", "Larger")
        val SIZES = floatArrayOf(1f, 1.25f, 1.5f)
        const val SIZE_LARGE = 1
    }
}
