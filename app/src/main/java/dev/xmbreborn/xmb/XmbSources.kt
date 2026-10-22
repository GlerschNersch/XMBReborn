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
    /** A photo or video from the phone's albums, opened in whichever app shows it. */
    data class Media(val uri: String, val video: Boolean) : Launch
    /** One of the user's own folders of apps in [column]; opening it shows them. */
    data class UserFolder(val column: String, val id: String) : Launch
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

    private val iconCache = IconCache(context)
    val userFolders = UserFolders(context)

    /** [items] for [column] with the user's folders gathered in; [byName] re-sorts A-Z (Apps). */
    private fun foldered(column: String, items: List<XmbItem>, byName: Boolean = false): List<XmbItem> {
        val out = UserFolders.apply(items, userFolders.inColumn(column)) { f, members ->
            icons[f.id] = ItemIcon.Of(Glyph.FOLDER)
            XmbItem(f.id, f.name, if (members.size == 1) "1 app" else "${members.size} apps", Launch.UserFolder(column, f.id), members)
        }
        if (!byName) return out
        val collator = Collator.getInstance()
        return out.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }

    /** Apps left out of the bar because the user hid them, for the Hidden Apps setting. */
    @Volatile var hiddenApps: List<XmbItem> = emptyList()
        private set

    /** "N apps, icons: x from memory, y from disk, z drawn" for the last [build], for the log. */
    val lastBuildStats get() = "icons ${iconCache.hits} from memory, ${iconCache.diskHits} from disk, ${iconCache.misses} drawn"

    /** Icon cache keys the current build used; the rest are dropped when it ends. */
    private val usedIconKeys = HashSet<String>()

    /** True if the last [build] left photo thumbnails out (not drawn yet); build again to fill them in. */
    @Volatile var thumbnailsPending = false
        private set

    /** Whether albums can show: the setting is on and Android has granted access to photos/videos. */
    fun mediaAllowed(): Boolean {
        if (!prefs.media) return false
        fun has(p: String) = context.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (android.os.Build.VERSION.SDK_INT >= 33)
            has(android.Manifest.permission.READ_MEDIA_IMAGES) || has(android.Manifest.permission.READ_MEDIA_VIDEO) ||
                (android.os.Build.VERSION.SDK_INT >= 34 && has(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        else has(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /**
     * [drawThumbnails] false leaves thumbnails that aren't cached yet as plain icons (and sets
     * [thumbnailsPending]), so the bar can show at once and fill them in with a second build.
     */
    fun build(iconPx: Int, drawThumbnails: Boolean = true): List<XmbCategory> {
        icons.clear()
        backgrounds.clear()
        usedIconKeys.clear()
        thumbnailsPending = false
        val apps = loadApps(iconPx)
        val photoAlbums = if (mediaAllowed()) albums(video = false, iconPx, drawThumbnails) else emptyList()
        val videoAlbums = if (mediaAllowed()) albums(video = true, iconPx, drawThumbnails) else emptyList()
        iconCache.keepOnly(usedIconKeys)
        val recent = prefs.recent
        // Category columns put what was opened last on top, like the PS3's Game column; the
        // Apps column stays A-Z so everything also has a fixed place.
        fun appsIn(column: String, vararg cats: Int) = foldered(column, byRecency(apps.filter { it.category in cats }.map { it.item }, recent))
        // PS3 games first: the scan also sets the count the PS3 Games setting shows.
        val games = byRecency(ps3Games() + apps.filter { it.category == ApplicationInfo.CATEGORY_GAME }.map { it.item }, recent)
        val settings = settingsItems()
        return listOf(
            XmbCategory("settings", "Settings", Glyph.SETTINGS, settings),
            XmbCategory("photo", "Photo", Glyph.PHOTO, appsIn("photo", ApplicationInfo.CATEGORY_IMAGE) + photoAlbums),
            XmbCategory("music", "Music", Glyph.MUSIC, appsIn("music", ApplicationInfo.CATEGORY_AUDIO)),
            XmbCategory("video", "Video", Glyph.VIDEO, appsIn("video", ApplicationInfo.CATEGORY_VIDEO) + videoAlbums),
            XmbCategory("game", "Game", Glyph.GAME, foldered("game", games)),
            XmbCategory("network", "Network", Glyph.NETWORK,
                appsIn("network", ApplicationInfo.CATEGORY_SOCIAL, ApplicationInfo.CATEGORY_NEWS, ApplicationInfo.CATEGORY_MAPS)),
            XmbCategory("shortcuts", "Shortcuts", Glyph.FOLDER, pinnedShortcuts(iconPx, apps)),
            XmbCategory("apps", "Apps", Glyph.APPS, foldered("apps", apps.map { it.item }, byName = true)),
        ).filter { it.id != "shortcuts" || it.items.isNotEmpty() }
    }

    private class LoadedApp(val item: XmbItem, val category: Int)

    private fun loadApps(iconPx: Int): List<LoadedApp> {
        val self = context.packageName
        val collator = Collator.getInstance()
        val out = ArrayList<LoadedApp>()
        iconCache.resetStats()
        val recent = prefs.recent
        val now = System.currentTimeMillis()
        for (user in launcherApps.profiles) {
            for (info in launcherApps.getActivityList(null, user)) {
                val component = info.componentName
                if (component.packageName == self) continue
                val id = "app:${component.flattenToShortString()}#${user.hashCode()}"
                // Drawing every icon is most of a rebuild's time; reuse it until the app updates.
                val version = runCatching { context.packageManager.getPackageInfo(component.packageName, 0).lastUpdateTime }.getOrDefault(0L)
                val bitmap = iconCache.get("$id@$iconPx", version) { rasterize(info.getBadgedIcon(0), iconPx) }
                usedIconKeys += "$id@$iconPx"
                icons[id] = ItemIcon.Bitmap(bitmap)
                // The focused row's info line, as the PS3 shows under a game: when it was last used.
                val item = XmbItem(id, info.label.toString(), recent[id]?.let { lastOpened(it, now) }, Launch.App(component, user))
                out.add(LoadedApp(item, info.applicationInfo.category))
            }
        }
        out.sortWith { a, b -> collator.compare(a.item.title, b.item.title) }
        val hidden = prefs.hidden
        hiddenApps = out.filter { it.item.id in hidden }.map { it.item }
        return out.filter { it.item.id !in hidden }
    }

    /**
     * The Settings column, in folders as on the PS3 (Theme Settings, Sound Settings...), with
     * Android's own settings gathered in one folder at the end.
     */
    fun settingsItems(): List<XmbItem> {
        val color = prefs.color.let { if (it == XmbPrefs.ORIGINAL) "Original" else MonthPalette.NAMES[it] }
        fun onOff(v: Boolean) = if (v) "On" else "Off"
        return listOf(
            folder("theme", "Theme Settings", "Colour, size, wallpaper and clock", Glyph.PALETTE, listOf(
                setting(SETTING_COLOR, "Theme Color", color, Glyph.PALETTE),
                setting(SETTING_SIZE, "Text and Icon Size", XmbPrefs.SIZE_NAMES[prefs.size], Glyph.TEXT),
                setting(SETTING_WALLPAPER, "Wallpaper", if (wallpaperIsOurs()) "XMB Reborn" else "Not set", Glyph.PHOTO),
                setting(SETTING_CLOCK, "Date and Time", onOff(prefs.clock), Glyph.CLOCK),
            )),
            folder("sound", "Sound Settings", "Menu sounds, ambient pad and startup sound", Glyph.SPEAKER, listOf(
                setting(SETTING_SOUNDS, "Interface Sounds", onOff(prefs.sounds), Glyph.SPEAKER),
                setting(SETTING_AMBIENT, "Ambient Sound", onOff(prefs.ambient), Glyph.MUSIC),
                setting(SETTING_CHIME, "Startup Sound", if (BootChime.file(context) == null) "No sound file" else onOff(prefs.chime), Glyph.SPEAKER),
            )),
            folder("home", "Home Settings", "Widgets, hidden apps and backup", Glyph.HOME, listOf(
                setting(SETTING_WIDGET_BUTTON, "Widget Button", onOff(prefs.widgetButton), Glyph.WIDGETS),
                setting(SETTING_MEDIA, "Photos and Videos", onOff(mediaAllowed()), Glyph.PHOTO),
                setting(SETTING_REMOVE_WIDGETS, "Remove Widgets", "Take widgets off the home screen", Glyph.WIDGETS),
                setting(SETTING_HIDDEN, "Hidden Apps", prefs.hidden.size.let { if (it == 0) "None" else "$it hidden" }, Glyph.HIDDEN),
                setting(SETTING_BACKUP, "Backup", "Save or restore settings", Glyph.BACKUP),
            )),
            setting(SETTING_PS3, "PS3 Games", ps3Status(), Glyph.GAME),
            folder("android", "Android Settings", "Wi-Fi, Bluetooth, display and more", Glyph.DEVICE, systemSettings()),
        )
    }

    private fun setting(key: String, title: String, value: String, glyph: Glyph): XmbItem {
        val id = "setting:$key"
        icons[id] = ItemIcon.Of(glyph)
        return XmbItem(id, title, value, Launch.Setting(key))
    }

    private fun folder(key: String, title: String, subtitle: String, glyph: Glyph, children: List<XmbItem>): XmbItem {
        val id = "folder:$key"
        icons[id] = ItemIcon.Of(glyph)
        return XmbItem(id, title, subtitle, children = children)
    }

    private fun systemSettings(): List<XmbItem> = listOf(
        SystemSetting("All Settings", "Every system setting", Settings.ACTION_SETTINGS, Glyph.SETTINGS),
        SystemSetting("Network Settings", "Wi-Fi and internet", Settings.ACTION_WIRELESS_SETTINGS, Glyph.WIFI),
        SystemSetting("Bluetooth Settings", "Controllers, audio and other accessories", Settings.ACTION_BLUETOOTH_SETTINGS, Glyph.BLUETOOTH),
        SystemSetting("Display Settings", "Brightness, theme and screen", Settings.ACTION_DISPLAY_SETTINGS, Glyph.BRIGHTNESS),
        SystemSetting("Sound Settings", "Volume and tones", Settings.ACTION_SOUND_SETTINGS, Glyph.SPEAKER),
        SystemSetting("Power Save Settings", "Battery use", Intent.ACTION_POWER_USAGE_SUMMARY, Glyph.BATTERY),
        SystemSetting("Date and Time Settings", "Clock and time zone", Settings.ACTION_DATE_SETTINGS, Glyph.CLOCK),
        SystemSetting("Application Settings", "Installed apps", Settings.ACTION_APPLICATION_SETTINGS, Glyph.APPS),
        SystemSetting("Home Screen", "Choose the default launcher", Settings.ACTION_HOME_SETTINGS, Glyph.HOME),
        SystemSetting("System Information", "About this device", Settings.ACTION_DEVICE_INFO_SETTINGS, Glyph.INFO),
    ).map { (title, subtitle, action, glyph) ->
        val id = "settings:$action"
        icons[id] = ItemIcon.Of(glyph)
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

    /**
     * The phone's albums as folders, each holding its newest pictures or clips with thumbnails;
     * an album's own icon is its newest picture, as on the PS3.
     */
    private fun albums(video: Boolean, iconPx: Int, draw: Boolean): List<XmbItem> {
        val fallback = if (video) Glyph.VIDEO else Glyph.PHOTO
        val dateFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        return MediaAlbums.load(context.contentResolver, video).map { album ->
            val children = album.items.map { m ->
                val id = "media:${if (video) "v" else "p"}:${m.id}"
                icons[id] = thumbnail(m, iconPx, draw)?.let { ItemIcon.Bitmap(it) } ?: ItemIcon.Of(fallback)
                val date = dateFormat.format(java.util.Date(m.modified * 1000))
                XmbItem(id, m.name.substringBeforeLast('.'), if (video) "$date · ${MediaAlbums.duration(m.durationMs)}" else date,
                    Launch.Media(m.uri.toString(), video))
            }
            val id = "album:${album.id}"
            icons[id] = children.firstOrNull()?.let { icons[it.id] } ?: ItemIcon.Of(fallback)
            val count = "${album.total} ${if (video) (if (album.total == 1) "video" else "videos") else (if (album.total == 1) "photo" else "photos")}"
            XmbItem(id, album.name, count, children = children)
        }
    }

    /** A square thumbnail at icon size, centre-cropped, from the cache when it's there. */
    private fun thumbnail(m: MediaAlbums.Media, px: Int, draw: Boolean): Bitmap? {
        val key = "media:${m.uri}@$px"
        usedIconKeys += key
        if (!draw && !iconCache.has(key, m.modified)) { thumbnailsPending = true; return null }
        return runCatching {
            iconCache.get(key, m.modified) {
                val thumb = context.contentResolver.loadThumbnail(m.uri, android.util.Size(px * 2, px * 2), null)
                val side = minOf(thumb.width, thumb.height)
                val square = Bitmap.createBitmap(thumb, (thumb.width - side) / 2, (thumb.height - side) / 2, side, side)
                Bitmap.createScaledBitmap(square, px, px, true)
            }
        }.getOrNull()
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

    /** Whether the system wallpaper is ours (see XmbWallpaper). */
    fun wallpaperIsOurs(): Boolean = runCatching {
        android.app.WallpaperManager.getInstance(context).wallpaperInfo?.packageName == context.packageName
    }.getOrDefault(false)

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

        /** "Opened today", "Opened yesterday", "Opened Oct 18", or with the year if it isn't this one. */
        fun lastOpened(at: Long, now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
                       locale: java.util.Locale = java.util.Locale.getDefault()): String {
            val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
            val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            return when {
                day == today -> "Opened today"
                day == today.minusDays(1) -> "Opened yesterday"
                day.year == today.year -> "Opened " + day.format(java.time.format.DateTimeFormatter.ofPattern("MMM d", locale))
                else -> "Opened " + day.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
            }
        }

        const val SETTING_COLOR = "color"
        const val SETTING_AMBIENT = "ambient"
        const val SETTING_SOUNDS = "sounds"
        const val SETTING_HIDDEN = "hidden"
        const val SETTING_CHIME = "chime"
        const val SETTING_PS3 = "ps3"
        const val SETTING_WIDGET_BUTTON = "widgetButton"
        const val SETTING_BACKUP = "backup"
        const val SETTING_REMOVE_WIDGETS = "removeWidgets"
        const val SETTING_MEDIA = "media"
        const val SETTING_CLOCK = "clock"
        const val SETTING_SIZE = "size"
        const val SETTING_WALLPAPER = "wallpaper"
    }

    private fun rasterize(icon: android.graphics.drawable.Drawable, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, px, px)
        icon.draw(Canvas(bmp))
        return bmp
    }
}

/** One shortcut into Android's own settings. */
private data class SystemSetting(val title: String, val subtitle: String, val action: String, val glyph: Glyph)
