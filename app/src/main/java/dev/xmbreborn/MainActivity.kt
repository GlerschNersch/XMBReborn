package dev.xmbreborn

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import dev.xmbreborn.xmb.Launch
import dev.xmbreborn.xmb.XmbItem
import dev.xmbreborn.xmb.XmbMetrics
import dev.xmbreborn.xmb.XmbModel
import dev.xmbreborn.xmb.XmbSources
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var view: XmbView
    private lateinit var sfx: Sfx
    private lateinit var sources: XmbSources
    private val loader = Executors.newSingleThreadExecutor()
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }

    private val appsChanged = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        sfx = Sfx()
        sources = XmbSources(this)
        view = XmbView(this, sfx, ::open)
        setContentView(view)
        view.requestFocus()
        launcherApps.registerCallback(appsChanged)
        reload()
    }

    override fun onResume() {
        super.onResume()
        view.onResume()
    }

    override fun onPause() {
        view.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        launcherApps.unregisterCallback(appsChanged)
        loader.shutdownNow()
        sfx.release()
        super.onDestroy()
    }

    /** Home is already showing; swallow back like any launcher. */
    @Deprecated("Launcher ignores back")
    override fun onBackPressed() = Unit

    private fun reload() {
        val iconPx = (XmbMetrics.ITEM_ICON_FOCUSED * minOf(resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels) / XmbMetrics.REF_SHORT_SIDE).toInt().coerceAtLeast(48)
        loader.execute {
            val categories = runCatching { sources.build(iconPx) }
                .onFailure { Log.e(TAG, "Loading the bar failed", it) }
                .getOrNull() ?: return@execute
            val icons = HashMap(sources.icons)
            val initial = categories.indexOfFirst { it.id == "apps" }.coerceAtLeast(0)
            view.renderer.post { it.setModel(XmbModel(categories, initial), icons) }
        }
    }

    private fun open(item: XmbItem) {
        try {
            when (val launch = item.payload) {
                is Launch.App -> launcherApps.startMainActivity(launch.component, launch.user, null, null)
                is Launch.Action -> startActivity(Intent(launch.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open ${item.title}", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Can't open ${item.title}", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val TAG = "XmbReborn"
    }
}
