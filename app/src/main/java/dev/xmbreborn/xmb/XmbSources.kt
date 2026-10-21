package dev.xmbreborn.xmb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import dev.xmbreborn.BootChime
import dev.xmbreborn.Shortcuts
import java.text.Collator

/** What opening an item does. */
sealed interface Launch {
    data class App(val component: ComponentName, val user: UserHandle) : Launch
    data class Action(val intent: Intent) : Launch
    /** One of the launcher's own settings; opening it shows its choices in the options panel. */
    data class Setting(val key: String) : Launch
    /** A shortcut another app pinned to home, or one of an app's own shortcuts. */
    data class Shortcut(val pkg: String, val id: String, val user: UserHandle) : Launch
    /** A PS3 game, opened in ARMSX3 by its title id. */
    data class Ps3(val titleId: String?, val file: String?) : Launch
}

/** An item's picture: an app's own icon, or one of our glyphs. */
sealed interface ItemIcon {
    /** [wide]: a PS3 game icon (320x176), drawn at its own shape rather than square. */
    class Bitmap(val bitmap: android.graphics.Bitmap, val wide: Boolean = false) : ItemIcon
    class Of(val glyph: Glyph) : ItemIcon
}

/**
 * Builds the bar from the device: system settings shortcuts, apps grouped by their declared
 * Play category, and an everything column. Mirrors the XMB's column order where one exists.
 */
class XmbSources(private val context: Context, private val prefs: XmbPrefs) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    val icons = HashMap<String, ItemIcon>()
    /** PS3 games' PIC1 backgrounds, by item id, faded in behind the bar while one is focused. */
    val backgrounds = HashMap<String, Ps3Library.Pic1>()

    /** Apps left out of the bar because the user hid them, for the Hidden Apps setting. */
    @Volatile var hiddenApps: List<XmbItem> = emptyList()
        private set

    fun build(iconPx: Int): List<XmbCategory> {
        icons.clear()
        backgrounds.clear()
        val apps = loadApps(iconPx)
        val recent = prefs.recent
        // Category columns put what was opened last on top, like the PS3's Game column; the
        // Apps column stays A-Z so everything also has a fixed place.
        fun appsIn(vararg cats: Int) = byRecency(apps.filter { it.category in cats }.map { it.item }, recent)
        // PS3 games first: the scan also sets the count the PS3 Games setting shows.
        val games = byRecency(ps3Games() + apps.filter { it.category == ApplicationInfo.CATEGORY_GAME }.map { it.item }, recent)
        val settings = settingsItems()
        return listOf(
            XmbCategory("settings", "Settings", Glyph.SETTINGS, settings),
            XmbCategory("photo", "Photo", Glyph.PHOTO, appsIn(ApplicationInfo.CATEGORY_IMAGE)),
            XmbCategory("music", "Music", Glyph.MUSIC, appsIn(ApplicationInfo.CATEGORY_AUDIO)),
            XmbCategory("video", "Video", Glyph.VIDEO, appsIn(ApplicationInfo.CATEGORY_VIDEO)),
            XmbCategory("game", "Game", Glyph.GAME, games),
            XmbCategory("network", "Network", Glyph.NETWORK,
                appsIn(ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_NEWS, ApplicationInfo.CATEGORY_MAPS)),
            XmbCategory("shortcuts", "Shortcuts", Glyph.FOLDER, pinnedShortcuts(iconPx, apps)),
            XmbCategory("apps", "Apps", Glyph.APPS, apps.map { it.item }),
        ).filter { it.id != "shortcuts" || it.items.isNotEmpty() }
    }

    private class LoadedApp(val item: XmbItem, val category: Int)

    private fun loadApps(iconPx: Int): List<LoadedApp> {
        val self = context.packageName
        val collator = Collator.getInstance()
        val out = ArrayList<LoadedApp>()
        for (user in launcherApps.profiles) {
            for (info in launcherApps.getActivityList(null, user)) {
                val component = info.componentName
                if (component.packageName == self) continue
                val id = "app:${component.flattenToShortString()}#${user.hashCode()}"
                icons[id] = ItemIcon.Bitmap(rasterize(info.getBadgedIcon(0), iconPx))
                val item = XmbItem(id, info.label.toString(), null, Launch.App(component, user))
                out.add(LoadedApp(item, info.applicationInfo.category))
            }
        }
        out.sortWith { a, b -> collator.compare(a.item.title, b.item.title) }
        val hidden = prefs.hidden
        hiddenApps = out.filter { it.item.id in hidden }.map { it.item }
        return out.filter { it.item.id !in hidden }
    }

    /** The Settings column: our Theme and Sound settings first, then shortcuts into Android's. */
    fun settingsItems(): List<XmbItem> = launcherSettings() + systemSettings()

    private fun launcherSettings(): List<XmbItem> {
        val color = prefs.color.let { if (it == XmbPrefs.ORIGINAL) "Original" else MonthPalette.NAMES[it] }
        fun onOff(v: Boolean) = if (v) "On" else "Off"
        return listOf(
            Triple(SETTING_COLOR, "Theme Color", color),
            Triple(SETTING_AMBIENT, "Ambient Sound", onOff(prefs.ambient)),
            Triple(SETTING_SOUNDS, "Interface Sounds", onOff(prefs.sounds)),
            Triple(SETTING_PS3, "PS3 Games", ps3Status()),
            Triple(SETTING_CHIME, "Startup Sound", if (BootChime.file(context) == null) "No sound file" else onOff(prefs.chime)),
            Triple(SETTING_HIDDEN, "Hidden Apps", prefs.hidden.size.let { if (it == 0) "None" else "$it hidden" }),
        ).map { (key, title, value) ->
            val id = "setting:$key"
            icons[id] = ItemIcon.Of(Glyph.SETTINGS)
            XmbItem(id, title, value, Launch.Setting(key))
        }
    }

    private fun systemSettings(): List<XmbItem> = listOf(
        Triple("All Settings", "Every system setting", Settings.ACTION_SETTINGS),
        Triple("Network Settings", "Wi-Fi and internet", Settings.ACTION_WIRELESS_SETTINGS),
        Triple("Bluetooth Settings", "Controllers, audio and other accessories", Settings.ACTION_BLUETOOTH_SETTINGS),
        Triple("Display Settings", "Brightness, theme and screen", Settings.ACTION_DISPLAY_SETTINGS),
        Triple("Sound Settings", "Volume and tones", Settings.ACTION_SOUND_SETTINGS),
        Triple("Power Save Settings", "Battery use", Intent.ACTION_POWER_USAGE_SUMMARY),
        Triple("Date and Time Settings", "Clock and time zone", Settings.ACTION_DATE_SETTINGS),
        Triple("Application Settings", "Installed apps", Settings.ACTION_APPLICATION_SETTINGS),
        Triple("Home Screen", "Choose the default launcher", Settings.ACTION_HOME_SETTINGS),
        Triple("System Information", "About this device", Settings.ACTION_DEVICE_INFO_SETTINGS),
    ).map { (title, subtitle, action) ->
        val id = "settings:$action"
        icons[id] = ItemIcon.Of(Glyph.SETTINGS)
        XmbItem(id, title, subtitle, Launch.Action(Intent(action)))
    }

    /** Shortcuts other apps pinned to home ("Add to Home screen"), titled with their app's name. */
    private fun pinnedShortcuts(iconPx: Int, apps: List<LoadedApp>): List<XmbItem> {
        val appNames = apps.associate { (it.item.payload as? Launch.App)?.component?.packageName to it.item.title }
        val density = context.resources.displayMetrics.densityDpi
        return Shortcuts.pinned(launcherApps).map { info ->
            val id = "shortcut:${info.`package`}/${info.id}#${info.userHandle.hashCode()}"
            val icon = runCatching { launcherApps.getShortcutBadgedIconDrawable(info, density) }.getOrNull()
            icons[id] = icon?.let { ItemIcon.Bitmap(rasterize(it, iconPx)) } ?: ItemIcon.Of(Glyph.FOLDER)
            XmbItem(id, (info.shortLabel ?: info.id).toString(), appNames[info.`package`], Launch.Shortcut(info.`package`, info.id, info.userHandle))
        }
    }

    /** The user's PS3 games from ARMSX3's exported folder; none if it isn't set up. */
    private fun ps3Games(): List<XmbItem> {
        val folder = prefs.ps3Folder ?: return emptyList()
        if (Ps3Library.emulator(context.packageManager) == null) return emptyList()
        val games = runCatching { Ps3Library.scan(context, Uri.parse(folder)) }.getOrNull()
        lastPs3Count = games?.size
        return games.orEmpty().map { g ->
            val id = "ps3:${g.titleId ?: g.file}"
            // The game's own ICON0 (320x176, drawn wide like the XMB's) or our game glyph.
            val art = g.icon?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            icons[id] = art?.let { ItemIcon.Bitmap(it, wide = true) } ?: ItemIcon.Of(Glyph.GAME)
            g.pic1?.let { backgrounds[id] = it }
            XmbItem(id, g.title, listOfNotNull("PlayStation 3", g.titleId).joinToString(" · "), Launch.Ps3(g.titleId, g.file?.toString()))
        }
    }

    private fun ps3Status(): String = when {
        Ps3Library.emulator(context.packageManager) == null -> "Needs ARMSX3"
        prefs.ps3Folder == null -> "Choose your games folder"
        else -> when (val n = lastPs3Count) {
            null -> "Folder not readable"
            1 -> "1 game"
            else -> "$n games"
        }
    }

    /** Games found by the last scan (null if it failed), so the setting's subtitle needn't rescan. */
    @Volatile private var lastPs3Count: Int? = null

    companion object {
        /** Most recently opened first; never-opened items keep their order after them. */
        fun byRecency(items: List<XmbItem>, recent: Map<String, Long>): List<XmbItem> =
            items.sortedByDescending { recent[it.id] ?: Long.MIN_VALUE }

        const val SETTING_COLOR = "color"
        const val SETTING_AMBIENT = "ambient"
        const val SETTING_SOUNDS = "sounds"
        const val SETTING_HIDDEN = "hidden"
        const val SETTING_CHIME = "chime"
        const val SETTING_PS3 = "ps3"
    }

    private fun rasterize(icon: android.graphics.drawable.Drawable, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, px, px)
        icon.draw(Canvas(bmp))
        return bmp
    }
}
