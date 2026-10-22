package dev.xmbreborn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import dev.xmbreborn.xmb.Launch
import dev.xmbreborn.xmb.MonthPalette
import dev.xmbreborn.xmb.OptionEntry
import dev.xmbreborn.xmb.Ps3Library
import dev.xmbreborn.xmb.XmbCategory
import dev.xmbreborn.xmb.XmbItem
import dev.xmbreborn.xmb.XmbMetrics
import dev.xmbreborn.xmb.XmbModel
import dev.xmbreborn.xmb.XmbOptions
import dev.xmbreborn.xmb.XmbPrefs
import dev.xmbreborn.xmb.XmbSources
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var view: XmbView
    private lateinit var trays: WidgetTrays
    private lateinit var sfx: Sfx
    private lateinit var prefs: XmbPrefs
    private lateinit var sources: XmbSources
    private val ambient = Ambient()
    private val chime by lazy { BootChime(this) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var resumed = false
    /** PS3 games' backgrounds from the last load, by item id (read on the loader thread). */
    @Volatile private var gameBackgrounds: Map<String, Ps3Library.Pic1> = emptyMap()
    /** An app was opened, so category columns need reordering when home shows again. */
    private var reorderOnResume = false
    /** When the bar was last rebuilt (elapsedRealtime), so returning home can pick up new PS3 games. */
    @Volatile private var lastReload = 0L

    /** Steps the ambient bed aside whenever another app plays media, and back in when it stops. */
    private val playbackWatch = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = updateAmbient(configs)
    }
    private val loader = Executors.newSingleThreadExecutor()
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }

    private val appsChanged = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<android.content.pm.ShortcutInfo>, user: UserHandle) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        prefs = XmbPrefs(this)
        // The user's own PS3 sounds, if they've supplied them (see README).
        sfx = Sfx { SoundFiles.find(this, it) }.apply { enabled = prefs.sounds }
        sources = XmbSources(this, prefs)
        view = XmbView(this, sfx, ::open, ::optionsFor)
        val root = android.widget.FrameLayout(this).apply { addView(view) }
        setContentView(root)
        trays = WidgetTrays(this, root, view) { prefs.widgetButton }
        view.requestFocus()
        view.renderer.post { it.themeColor = prefs.color; it.showClock = prefs.clock }
        view.renderer.onWantBackground = { id -> loader.execute { loadGameBackground(id) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Home ignores back, except to close the options panel.
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { view.cancelOptions() }
        }
        launcherApps.registerCallback(appsChanged)
        audio.registerAudioPlaybackCallback(playbackWatch, Handler(mainLooper))
        reload()
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
        resumed = true
        if (prefs.chime && !otherMediaPlaying()) {
            chime.playOncePerBoot(prefs.chimeBoot, { prefs.chimeBoot = it }) { updateAmbient() }
        }
        updateAmbient()
        // Rebuild after an app was opened (recency order), or now and then while a PS3 games folder
        // is set, since games copied into it don't announce themselves.
        val stale = prefs.ps3Folder != null && android.os.SystemClock.elapsedRealtime() - lastReload > RESCAN_MS
        if (reorderOnResume || stale) { reorderOnResume = false; reload() }
    }

    override fun onPause() {
        resumed = false
        chime.stop()
        ambient.stop()
        view.onPause()
        super.onPause()
    }

    override fun onStart() { super.onStart(); trays.start() }
    override fun onStop() { trays.stop(); super.onStop() }

    override fun onDestroy() {
        trays.release()
        launcherApps.unregisterCallback(appsChanged)
        audio.unregisterAudioPlaybackCallback(playbackWatch)
        loader.shutdownNow()
        ambient.stop()
        sfx.release()
        super.onDestroy()
    }

    /**
     * Ambient plays only while home is showing, it's switched on, and no other app is playing
     * media: never over the user's own music or podcast. Asked of what's actually playing, not
     * isMusicActive, which some phones report as true in VoIP / Wi-Fi calling audio modes. Our
     * bed, clicks and chime are all USAGE_GAME, so none of them counts as someone else's media.
     */
    private fun updateAmbient(configs: List<AudioPlaybackConfiguration> = audio.activePlaybackConfigurations) {
        // Waits for the start-up sound to finish rather than playing under it.
        if (resumed && prefs.ambient && !otherMediaPlaying(configs) && !chime.playing) ambient.start() else ambient.stop()
    }

    private fun otherMediaPlaying(configs: List<AudioPlaybackConfiguration> = audio.activePlaybackConfigurations) =
        configs.any { it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA }

    /**
     * Home pressed while home is already showing: glide back to the start (the Apps column, top
     * row), like returning to the XMB. Coming home from another app keeps where you were.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val alreadyHome = hasWindowFocus() && (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        if (Intent.ACTION_MAIN == intent.action && intent.hasCategory(Intent.CATEGORY_HOME) && alreadyHome) view.goHome("apps")
    }

    /** Home is already showing; back only closes the options panel. */
    @Deprecated("Launcher ignores back")
    override fun onBackPressed() {
        view.cancelOptions()
    }

    private fun reload() {
        val iconPx = (XmbMetrics.ITEM_ICON_FOCUSED * minOf(resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels) / XmbMetrics.REF_SHORT_SIDE).toInt().coerceAtLeast(48)
        lastReload = android.os.SystemClock.elapsedRealtime()
        loader.execute {
            val categories = runCatching { sources.build(iconPx) }
                .onFailure { Log.e(TAG, "Loading the bar failed", it) }
                .getOrNull() ?: return@execute
            val icons = HashMap(sources.icons)
            gameBackgrounds = HashMap(sources.backgrounds)
            val initial = categories.indexOfFirst { it.id == "apps" }.coerceAtLeast(0)
            val backgroundIds = gameBackgrounds.keys.toSet()
            view.renderer.post { it.setModel(XmbModel(categories, initial), icons, backgroundIds) }
        }
    }

    /** Loads a focused game's PIC1, scaled no larger than the screen, and hands it to the renderer. */
    private fun loadGameBackground(id: String) {
        val pic = gameBackgrounds[id] ?: return
        val bytes = Ps3Library.loadPic1(this, pic) ?: return
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longest) sample *= 2
        val bitmap = runCatching {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return
        view.renderer.post { it.setGameBackground(id, bitmap) }
    }

    /** Rebuilds just the Settings column, so the changed setting's subtitle shows its new value. */
    private fun refreshSettings() = loader.execute {
        val items = sources.settingsItems()
        view.renderer.post { it.model?.category("settings")?.replaceItems(items) }
    }

    private fun open(item: XmbItem) {
        try {
            when (val launch = item.payload) {
                is Launch.App -> {
                    launcherApps.startMainActivity(launch.component, launch.user, null, null)
                    prefs.markOpened(item.id)
                    reorderOnResume = true
                }
                is Launch.Action -> startActivity(Intent(launch.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                is Launch.Setting -> view.showOptions(settingOptions(launch.key))
                is Launch.Shortcut -> launcherApps.startShortcut(launch.pkg, launch.id, null, null, launch.user)
                is Launch.Ps3 -> {
                    val emulator = Ps3Library.emulator(packageManager)
                    if (emulator == null) Toast.makeText(this, "Install ARMSX3 to play PS3 games", Toast.LENGTH_LONG).show()
                    else {
                        Ps3Library.launch(this, emulator, launch.titleId, launch.file?.let(Uri::parse))
                        prefs.markOpened(item.id)
                        reorderOnResume = true
                    }
                }
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open ${item.title}", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Can't open ${item.title}", Toast.LENGTH_SHORT).show()
        }
    }

    /** The options panel for a row (GL thread). */
    private fun optionsFor(item: XmbItem): XmbOptions? = when (val launch = item.payload) {
        is Launch.Setting -> settingOptions(launch.key)
        is Launch.Action, is Launch.Ps3 -> XmbOptions(listOf(OptionEntry("Open") { open(item) }, addWidgetEntry()))
        is Launch.Shortcut -> XmbOptions(listOf(
            OptionEntry("Open") { open(item) },
            OptionEntry("Remove") {
                Shortcuts.find(launcherApps, launch.pkg, launch.id, launch.user)?.let { Shortcuts.unpin(launcherApps, it) }
                reload()
            },
            addWidgetEntry(),
        ))
        is Launch.App -> XmbOptions(buildList {
            add(OptionEntry("Open") { open(item) })
            // The app's own shortcuts ("New tab", "Navigate home"...), as other launchers show them.
            for (sc in Shortcuts.forApp(launcherApps, launch.component, launch.user)) {
                add(OptionEntry((sc.shortLabel ?: sc.id).toString()) {
                    runCatching { launcherApps.startShortcut(sc, null, null) }
                        .onFailure { Toast.makeText(this@MainActivity, "Can't open that shortcut", Toast.LENGTH_SHORT).show() }
                })
            }
            add(OptionEntry("App Info") {
                runCatching { launcherApps.startAppDetailsActivity(launch.component, launch.user, null, null) }
            })
            jumpToLetterEntry()?.let(::add)
            add(addWidgetEntry())
            add(OptionEntry("Hide") { prefs.setHidden(item.id, true); reload() })
            if (!isSystemApp(launch.component.packageName)) add(OptionEntry("Uninstall") { uninstall(item, launch) })
        })
        else -> null
    }

    /**
     * In a long column (Apps A-Z), a second panel listing its starting letters; choosing one jumps
     * to the first row under it. Built on the GL thread, like the options it's added to.
     */
    private fun jumpToLetterEntry(): OptionEntry? {
        val column = view.renderer.model?.focused ?: return null
        val letters = column.letters()
        if (column.items.size < JUMP_MIN_ROWS || letters.size < 2) return null
        val here = XmbCategory.letterOf(column.focusedItem?.title.orEmpty())
        return OptionEntry("Jump to Letter") {
            view.showOptions(XmbOptions(
                letters.map { (letter, index) -> OptionEntry(letter) { view.focusRow(column.id, index) } },
                initial = letters.indexOfFirst { it.first == here }.coerceAtLeast(0),
            ))
        }
    }

    /** Adds a widget to the tray of the column these options were opened in. */
    private fun addWidgetEntry(): OptionEntry {
        val column = trays.focusedColumn()
        return OptionEntry("Add Widget Here") { column?.let(trays::add) }
    }

    private fun isSystemApp(pkg: String): Boolean = runCatching {
        packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(true)

    private fun uninstall(item: XmbItem, launch: Launch.App) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", launch.component.packageName, null))
            .putExtra(Intent.EXTRA_USER, launch.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't uninstall ${item.title}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Choices for one of our settings. The colour list previews each colour as it's focused. */
    private fun settingOptions(key: String): XmbOptions = when (key) {
        XmbSources.SETTING_COLOR -> {
            val values = listOf(XmbPrefs.ORIGINAL) + MonthPalette.NAMES.indices
            XmbOptions(
                values.map { v ->
                    OptionEntry(
                        label = if (v == XmbPrefs.ORIGINAL) "Original" else MonthPalette.NAMES[v],
                        onFocus = { view.renderer.themeColor = v },
                        onSelect = { prefs.color = v; refreshSettings() },
                    )
                },
                initial = values.indexOf(prefs.color),
                onCancel = { view.renderer.themeColor = prefs.color },
            )
        }
        XmbSources.SETTING_AMBIENT -> onOff(prefs.ambient) { on ->
            prefs.ambient = on
            updateAmbient()
        }
        XmbSources.SETTING_PS3 -> XmbOptions(buildList {
            add(OptionEntry(if (prefs.ps3Folder == null) "Choose Folder" else "Change Folder") { pickPs3Folder() })
            if (prefs.ps3Folder != null) add(OptionEntry("Forget Folder") { setPs3Folder(null) })
            if (Ps3Library.emulator(packageManager) == null) add(OptionEntry("Get ARMSX3") { openUrl(ARMSX3_URL) })
        })
        XmbSources.SETTING_CHIME -> if (BootChime.file(this) == null) XmbOptions(listOf(OptionEntry("No sound file") {}))
            else onOff(prefs.chime) { on -> prefs.chime = on; if (!on) chime.stop() }
        XmbSources.SETTING_WIDGET_BUTTON -> onOff(prefs.widgetButton) { on -> prefs.widgetButton = on }
        XmbSources.SETTING_CLOCK -> onOff(prefs.clock) { on -> prefs.clock = on; view.renderer.post { it.showClock = on } }
        XmbSources.SETTING_BACKUP -> XmbOptions(listOf(
            OptionEntry("Save Backup") { saveBackup() },
            OptionEntry("Restore Backup") { restoreBackup() },
        ))
        XmbSources.SETTING_SOUNDS -> onOff(prefs.sounds) { on ->
            prefs.sounds = on
            sfx.enabled = on
        }
        XmbSources.SETTING_HIDDEN -> {
            val hidden = sources.hiddenApps
            if (hidden.isEmpty()) XmbOptions(listOf(OptionEntry("No hidden apps") {}))
            else XmbOptions(hidden.map { app -> OptionEntry("Show ${app.title}") { prefs.setHidden(app.id, false); reload() } })
        }
        else -> error("Unknown setting $key")
    }

    /**
     * Settings, hidden apps and recent order to a JSON file of the user's choosing. Widgets and the
     * PS3 folder aren't included: Android ties both to this install.
     */
    private fun saveBackup() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, "xmbreborn-backup.json")
        startForResult(intent, REQUEST_BACKUP_SAVE)
    }

    private fun restoreBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*") // some file managers don't tag .json as application/json
        startForResult(intent, REQUEST_BACKUP_RESTORE)
    }

    private fun startForResult(intent: Intent, request: Int) {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, request)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No file picker on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onBackupResult(request: Int, uri: Uri) {
        val result = runCatching {
            if (request == REQUEST_BACKUP_SAVE) {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(prefs.export().toByteArray()) }
                "Backup saved"
            } else {
                val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                prefs.import(text)
                applySettings()
                "Backup restored"
            }
        }
        val message = result.getOrElse { e -> (e as? IllegalArgumentException)?.message ?: "Couldn't read or write that file" }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** After a restore: every setting takes effect at once, as if chosen one by one. */
    private fun applySettings() {
        sfx.enabled = prefs.sounds
        view.renderer.post { it.themeColor = prefs.color; it.showClock = prefs.clock }
        updateAmbient()
        if (!prefs.chime) chime.stop()
        reload()
    }

    /** ARMSX3's Frontend Export folder, picked with the system's folder picker (read access only). */
    private fun pickPs3Folder() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQUEST_PS3_FOLDER)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No folder picker on this device", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Uses the platform result API; this Activity has no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (trays.onActivityResult(requestCode, resultCode)) return
        if ((requestCode == REQUEST_BACKUP_SAVE || requestCode == REQUEST_BACKUP_RESTORE) && resultCode == RESULT_OK) {
            data?.data?.let { onBackupResult(requestCode, it) }
            return
        }
        if (requestCode != REQUEST_PS3_FOLDER || resultCode != RESULT_OK) return
        val tree = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        setPs3Folder(tree)
    }

    private fun setPs3Folder(tree: Uri?) {
        prefs.ps3Folder?.let { old ->
            if (old != tree?.toString()) runCatching {
                contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs.ps3Folder = tree?.toString()
        reload()
    }

    private fun openUrl(url: String) = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun onOff(current: Boolean, apply: (Boolean) -> Unit) = XmbOptions(
        listOf(true, false).map { on -> OptionEntry(if (on) "On" else "Off") { apply(on); refreshSettings() } },
        initial = if (current) 0 else 1,
    )

    private companion object {
        const val REQUEST_PS3_FOLDER = 31
        const val REQUEST_BACKUP_SAVE = 32
        const val REQUEST_BACKUP_RESTORE = 33
        const val RESCAN_MS = 30_000L
        /** Columns at least this long offer Jump to Letter. */
        const val JUMP_MIN_ROWS = 12
        const val ARMSX3_URL = "https://github.com/ARMSX2/ARMSX3/releases"
        const val TAG = "XmbReborn"
    }
}
