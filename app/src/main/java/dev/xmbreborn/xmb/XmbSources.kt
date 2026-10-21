package dev.xmbreborn.xmb

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.UserHandle
import android.provider.Settings
import java.text.Collator

/** What opening an item does. */
sealed interface Launch {
    data class App(val component: ComponentName, val user: UserHandle) : Launch
    data class Action(val intent: Intent) : Launch
    /** One of the launcher's own settings; opening it shows its choices in the options panel. */
    data class Setting(val key: String) : Launch
}

/** An item's picture: an app's own icon, or one of our glyphs. */
sealed interface ItemIcon {
    class Bitmap(val bitmap: android.graphics.Bitmap) : ItemIcon
    class Of(val glyph: Glyph) : ItemIcon
}

/**
 * Builds the bar from the device: system settings shortcuts, apps grouped by their declared
 * Play category, and an everything column. Mirrors the XMB's column order where one exists.
 */
class XmbSources(private val context: Context, private val prefs: XmbPrefs) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    val icons = HashMap<String, ItemIcon>()

    /** Apps left out of the bar because the user hid them, for the Hidden Apps setting. */
    @Volatile var hiddenApps: List<XmbItem> = emptyList()
        private set

    fun build(iconPx: Int): List<XmbCategory> {
        icons.clear()
        val apps = loadApps(iconPx)
        val recent = prefs.recent
        // Category columns put what was opened last on top, like the PS3's Game column; the
        // Apps column stays A-Z so everything also has a fixed place.
        fun appsIn(vararg cats: Int) = byRecency(apps.filter { it.category in cats }.map { it.item }, recent)
        return listOf(
            XmbCategory("settings", "Settings", Glyph.SETTINGS, settingsItems()),
            XmbCategory("photo", "Photo", Glyph.PHOTO, appsIn(ApplicationInfo.CATEGORY_IMAGE)),
            XmbCategory("music", "Music", Glyph.MUSIC, appsIn(ApplicationInfo.CATEGORY_AUDIO)),
            XmbCategory("video", "Video", Glyph.VIDEO, appsIn(ApplicationInfo.CATEGORY_VIDEO)),
            XmbCategory("game", "Game", Glyph.GAME, appsIn(ApplicationInfo.CATEGORY_GAME)),
            XmbCategory("network", "Network", Glyph.NETWORK,
                appsIn(ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_NEWS, ApplicationInfo.CATEGORY_MAPS)),
            XmbCategory("apps", "Apps", Glyph.APPS, apps.map { it.item }),
        )
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

    companion object {
        /** Most recently opened first; never-opened items keep their order after them. */
        fun byRecency(items: List<XmbItem>, recent: Map<String, Long>): List<XmbItem> =
            items.sortedByDescending { recent[it.id] ?: Long.MIN_VALUE }

        const val SETTING_COLOR = "color"
        const val SETTING_AMBIENT = "ambient"
        const val SETTING_SOUNDS = "sounds"
        const val SETTING_HIDDEN = "hidden"
    }

    private fun rasterize(icon: android.graphics.drawable.Drawable, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, px, px)
        icon.draw(Canvas(bmp))
        return bmp
    }
}
