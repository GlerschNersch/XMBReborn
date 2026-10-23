package dev.xmbreborn

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.xmbreborn.xmb.Glyph
import dev.xmbreborn.xmb.XmbCategory
import dev.xmbreborn.xmb.XmbMetrics
import dev.xmbreborn.xmb.XmbModel
import dev.xmbreborn.xmb.XmbPrefs
import dev.xmbreborn.xmb.XmbSources
import java.util.concurrent.Executors

/**
 * Debug-only mock-ups of three ways widgets could live in XMB Reborn, to choose between:
 *  - DASHBOARD: a separate screen with a grid of real widgets (option 3);
 *  - BOARD: a "What's New" column whose tiles hold real widgets below the bar (option 4);
 *  - NATIVE: info drawn in the XMB's own style instead of Android widgets (option 5).
 * Real widgets need bind permission: `adb shell appwidget grantbind --package dev.xmbreborn`.
 * Not part of release builds.
 */
class WidgetLabActivity : Activity() {
    private enum class Mode { DASHBOARD, BOARD, NATIVE }

    private lateinit var host: AppWidgetHost
    private val manager by lazy { AppWidgetManager.getInstance(this) }
    private val boundIds = ArrayList<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = when (intent.component?.className?.substringAfterLast('.')) {
            "WidgetLabBoard" -> Mode.BOARD
            "WidgetLabNative" -> Mode.NATIVE
            else -> Mode.DASHBOARD
        }
        host = AppWidgetHost(this, HOST_ID)
        val xmb = XmbView(this, Sfx(), { _, _ -> }, { null })
        val root = FrameLayout(this).apply { addView(xmb) }
        setContentView(root)
        loadBar(xmb, mode)
        root.post {
            when (mode) {
                Mode.DASHBOARD -> {
                    val dash = dashboard()
                    val handle = label("⌃  Widgets", 16f).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply { setColor(Color.argb(60, 255, 255, 255)); cornerRadius = dp(20).toFloat() }
                        setPadding(dp(20), dp(8), dp(20), dp(8))
                        setOnClickListener { showDashboard(dash, true) }
                    }
                    root.addView(handle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
                    root.addView(dash) // over the handle, which only shows while the dashboard is away
                    dashboardView = dash
                    dash.translationY = root.height.toFloat()
                    dash.visibility = View.GONE
                    dash.postDelayed({ showDashboard(dash, true) }, 800)
                }
                Mode.BOARD -> root.addView(gated(xmb, board(root.height), "new"))
                Mode.NATIVE -> root.addView(gated(xmb, nativeInfo(root.height), "now"))
            }
        }
    }

    private var dashboardView: View? = null

    // While the dashboard is up, Back closes it (Android 16 apps get Back through this callback).
    private val closeDashboard = android.window.OnBackInvokedCallback { dashboardView?.let { showDashboard(it, false) } }

    /** Slides the dashboard up over the bar, or back down out of the way. */
    private fun showDashboard(dash: View, show: Boolean) {
        val h = (dash.parent as View).height.toFloat()
        if (show) {
            dash.visibility = View.VISIBLE
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, closeDashboard)
        } else {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(closeDashboard)
        }
        dash.animate().translationY(if (show) 0f else h).setDuration(280)
            .withEndAction { if (!show) dash.visibility = View.GONE }.start()
    }

    private fun gated(xmb: XmbView, overlay: View, columnId: String): View =
        TouchGate(this).apply { addView(overlay); followColumn(xmb, overlay, columnId, this) }

    /**
     * Ties a view to one column of the bar, as the XMB does a column's list: it slides with the
     * column and fades over the move (opacity = 1 - distance), gone once another column is focused.
     */
    private fun followColumn(xmb: XmbView, view: View, columnId: String, gate: TouchGate) {
        val spacing = XmbMetrics.CATEGORY_SPACING * minOf(resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels) / XmbMetrics.REF_SHORT_SIDE
        val tick = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val index = xmb.renderer.columnIds.indexOf(columnId)
                val d = if (index < 0) 99f else index - xmb.renderer.barScroll
                val a = (1f - kotlin.math.abs(d) * XmbMetrics.ROW_FADE_RATE).coerceIn(0f, 1f)
                // Slides only while it's fading; fully faded it stays put. (Parking it far off
                // screen broke redrawing: Android skipped its content there, and moving it back
                // didn't record it again.) Touches pass through while it's faded out.
                val show = a > 0.01f
                view.alpha = a
                view.translationX = if (show) d * spacing else 0f
                gate.passThrough = !show
                if (!isDestroyed) android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        android.view.Choreographer.getInstance().postFrameCallback(tick)
    }

    /** Lets touches through to the bar underneath while the overlay inside is faded out. */
    private class TouchGate(context: android.content.Context) : FrameLayout(context) {
        var passThrough = true
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean = !passThrough && super.dispatchTouchEvent(ev)
    }




    override fun onStart() { super.onStart(); host.startListening() }
    override fun onStop() { host.stopListening(); super.onStop() }
    override fun onDestroy() {
        boundIds.forEach(host::deleteAppWidgetId)
        super.onDestroy()
    }

    /** The real bar underneath; the board mock-up adds a What's New column and focuses it. */
    private fun loadBar(xmb: XmbView, mode: Mode) {
        val iconPx = (XmbMetrics.ITEM_ICON_FOCUSED * minOf(resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels) / XmbMetrics.REF_SHORT_SIDE).toInt()
        Executors.newSingleThreadExecutor().execute {
            val sources = XmbSources(this, XmbPrefs(this))
            var cats = sources.build(iconPx)
            var initial = cats.indexOfFirst { it.id == "apps" }
            if (mode == Mode.BOARD) {
                cats = listOf(XmbCategory("new", "What's New", Glyph.FOLDER, emptyList())) + cats
                initial = 0
            }
            if (mode == Mode.NATIVE) {
                cats = listOf(XmbCategory("now", "Now", Glyph.MUSIC, emptyList())) + cats
                initial = 0
            }
            val icons = HashMap(sources.icons)
            xmb.renderer.post { it.setModel(XmbModel(cats, initial.coerceAtLeast(0)), icons) }
        }
    }

    // ---- real widgets --------------------------------------------------------------------

    /** Widgets available here without a configuration step, in a sensible order for a demo. */
    private fun pickProviders(n: Int): List<AppWidgetProviderInfo> {
        val all = manager.installedProviders.filter { it.configure == null }
        val wanted = listOf("DigitalAppWidgetProvider", "SmartspaceWidgetProvider", "MusicWidgetProvider",
            "MonthViewWidgetProvider", "AnalogAppWidgetProvider", "TrafficWidgetProvider", "SearchWidgetProvider")
        val ordered = wanted.mapNotNull { w -> all.firstOrNull { it.provider.className.endsWith(w) } }
        return (ordered + (all - ordered.toSet())).take(n)
    }


    private fun widget(info: AppWidgetProviderInfo, wDp: Int, hDp: Int): View {
        val id = host.allocateAppWidgetId()
        if (!manager.bindAppWidgetIdIfAllowed(id, info.provider)) {
            host.deleteAppWidgetId(id)
            return label("No permission to show ${info.loadLabel(packageManager)}", 14f)
        }
        boundIds += id
        return host.createView(this, id, info).apply {
            val opts = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
            }
            updateAppWidgetOptions(opts)
        }
    }

    // ---- option 3: a dashboard screen -----------------------------------------------------

    private fun dashboard(): View {
        val grid = GridLayout(this).apply { columnCount = 2 }
        val cellW = (resources.displayMetrics.widthPixels - dp(48)) / 2
        for (info in pickProviders(6)) {
            val cell = frame(widget(info, px2dp(cellW), 170), info.loadLabel(packageManager))
            grid.addView(cell, GridLayout.LayoutParams().apply { width = cellW; height = dp(210); setMargins(dp(8), dp(8), dp(8), dp(8)) })
        }
        return FrameLayout(this).apply {
            setBackgroundColor(Color.argb(215, 12, 10, 6))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(72), dp(16), dp(16))
                addView(label("Widgets", 26f))
                addView(label("Option 3 · a separate screen you swipe to (mock-up)", 12f, alpha = 0.7f))
                addView(grid)
            })
        }
    }

    // ---- option 4: a "What's New" board below the bar --------------------------------------

    private fun board(height: Int): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), 0, dp(16), 0) }
        for (info in pickProviders(4)) {
            row.addView(frame(widget(info, 240, 150), info.loadLabel(packageManager)),
                LinearLayout.LayoutParams(dp(250), dp(200)).apply { setMargins(dp(6), 0, dp(6), 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (height * 0.33f).toInt(), 0, 0)
            addView(label("Option 4 · tiles under a What's New column (mock-up)", 12f, alpha = 0.7f).apply { setPadding(dp(22), 0, 0, dp(8)) })
            addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
    }

    // ---- option 5: the XMB's own info panels -----------------------------------------------

    private fun nativeInfo(height: Int): View {
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // In its own column, where a list would be, like the What's New board.
            setPadding(dp(20), (height * 0.30f).toInt(), dp(20), 0)
        }
        col.addView(label("Option 5 · drawn in the XMB's own style, not Android widgets (mock-up, sample data)", 12f, alpha = 0.7f))
        // Now playing, like the PS3's music panel over the XMB.
        col.addView(bar(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(View(context).apply { background = GradientDrawable().apply { setColor(Color.argb(90, 255, 255, 255)); cornerRadius = dp(4).toFloat() } },
                LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
                addView(label("Sample Song", 18f))
                addView(label("Sample Artist · Sample Album", 12f, alpha = 0.8f))
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; progress = 38; progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                }, LinearLayout.LayoutParams(dp(180), dp(10)))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(label("⏮   ⏸   ⏭", 22f))
        }))
        col.addView(bar(label("☀  18°  Partly cloudy   ·   📅  Dentist, 3:30 PM   ·   🔋 $battery%", 15f)))
        return col
    }

    // ---- styling ---------------------------------------------------------------------------

    /** A widget in an XMB-style frame: faint fill, hairline edge, label underneath. */
    private fun frame(content: View, title: CharSequence): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(40, 255, 255, 255)); setStroke(1, Color.argb(110, 255, 255, 255)); cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            clipToOutline = true
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(label(title, 13f).apply { setPadding(dp(4), dp(4), 0, 0) })
    }

    /** A translucent strip with hairlines top and bottom, like the XMB's clock bar. */
    private fun bar(content: View): View = FrameLayout(this).apply {
        background = GradientDrawable().apply { setColor(Color.argb(28, 255, 255, 255)); setStroke(1, Color.argb(100, 255, 255, 255)) }
        setPadding(dp(14), dp(10), dp(14), dp(10))
        addView(content)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun label(text: CharSequence, sp: Float, alpha: Float = 1f) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        this.alpha = alpha
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(4f, 0f, 2f, Color.argb(150, 0, 0, 0))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun px2dp(px: Int) = (px / resources.displayMetrics.density).toInt()

    private companion object {
        const val HOST_ID = 7301
    }
}
