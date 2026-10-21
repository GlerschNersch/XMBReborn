package dev.xmbreborn

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast

/**
 * The side trays with widgets the user picks (Widget Lab 6). Each column has its own tray; a
 * column with none looks normal. "+ Widget" opens Android's widget picker (and the widget's own
 * set-up screen if it has one), long-pressing a card's title removes it, and the trays are kept
 * between launches. Its own widget host, so cleaning up the other mock-ups never touches these.
 * Debug/lab builds only.
 */
class WidgetLabTrays(
    private val activity: Activity,
    private val root: FrameLayout,
    private val tray: WidgetLabTray,
    /** Ties a view to a column of the bar (slides and fades with it). */
    private val follow: (View, String) -> View,
) {
    private val host = AppWidgetHost(activity, HOST_ID)
    private val manager = AppWidgetManager.getInstance(activity)
    private val prefs = activity.getSharedPreferences("widget_lab_trays", Context.MODE_PRIVATE)
    private val holders = HashMap<String, FrameLayout>()
    private var pendingColumn: String? = null
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID

    fun start() = host.startListening()
    fun stop() = host.stopListening()

    /** Columns whose tray has widgets, so their list's labels make room. */
    fun nonEmpty(): Set<String> = holders.keys.filter { ids(it).isNotEmpty() }.toSet()

    /** One tray per column of the bar, filled from what was saved. */
    fun build(columns: List<String>) {
        for (c in columns) {
            if (c in holders) continue
            val holder = FrameLayout(activity)
            holders[c] = holder
            root.addView(follow(holder, c), root.childCount - 1) // under the "+ Widget" button
            refill(c)
        }
    }

    /** First launch only: start Music and Settings off with sensible widgets, as before. */
    fun seedIfFirstRun(picks: Map<String, List<AppWidgetProviderInfo>>) {
        if (prefs.getBoolean("seeded", false)) return
        for ((column, infos) in picks) {
            val ids = infos.mapNotNull { info ->
                val id = host.allocateAppWidgetId()
                if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) id else { host.deleteAppWidgetId(id); null }
            }
            save(column, ids)
        }
        prefs.edit().putBoolean("seeded", true).apply()
    }

    /** Opens Android's widget picker for [column]'s tray. */
    fun add(column: String) {
        pendingColumn = column
        pendingId = host.allocateAppWidgetId()
        @Suppress("DEPRECATION")
        activity.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId), REQ_PICK)
    }

    /** The picker's and set-up screen's answers; true if it was one of ours. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_PICK && requestCode != REQ_CONFIGURE) return false
        val id = pendingId
        val column = pendingColumn
        if (resultCode != Activity.RESULT_OK || column == null || id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(id)
            clearPending()
            return true
        }
        val info = manager.getAppWidgetInfo(id)
        if (requestCode == REQ_PICK && info?.configure != null) {
            // Its own set-up screen first (e.g. which city a weather widget shows).
            runCatching {
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQ_CONFIGURE, null)
            }.onFailure {
                Toast.makeText(activity, "That widget's set-up couldn't open", Toast.LENGTH_SHORT).show()
                host.deleteAppWidgetId(id); clearPending()
            }
            return true
        }
        save(column, ids(column) + id)
        refill(column)
        clearPending()
        return true
    }

    private fun clearPending() { pendingColumn = null; pendingId = AppWidgetManager.INVALID_APPWIDGET_ID }

    private fun refill(column: String) {
        val holder = holders[column] ?: return
        holder.removeAllViews()
        val density = activity.resources.displayMetrics.density
        val widthDp = tray.cardWidthDp(root.width)
        val cards = ids(column).mapNotNull { id ->
            val info = manager.getAppWidgetInfo(id) ?: return@mapNotNull null
            // Room for widgets that report less height than they draw.
            val hDp = (maxOf(info.minHeight, info.minResizeHeight) / density).toInt().coerceIn(96, 260) + 12
            val wDp = maxOf(widthDp, (info.minWidth / density).toInt())
            val view = host.createView(activity, id, info).apply {
                updateAppWidgetOptions(Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
                })
            }
            TrayCard(info.loadLabel(activity.packageManager), ScaledWidget(activity, (wDp * density).toInt(), (hDp * density).toInt()).apply { addView(view) }) {
                confirmRemove(column, id, info.loadLabel(activity.packageManager))
            }
        }
        if (cards.isNotEmpty()) holder.addView(tray.realColumn(root.width, root.height, cards))
    }

    private fun confirmRemove(column: String, id: Int, name: CharSequence) {
        AlertDialog.Builder(activity)
            .setTitle("Remove $name?")
            .setPositiveButton("Remove") { _, _ ->
                host.deleteAppWidgetId(id)
                save(column, ids(column) - id)
                refill(column)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ids(column: String): List<Int> =
        prefs.getString("tray:$column", "")!!.split(',').mapNotNull { it.toIntOrNull() }

    private fun save(column: String, ids: List<Int>) = prefs.edit().putString("tray:$column", ids.joinToString(",")).apply()

    private companion object {
        const val HOST_ID = 7302
        const val REQ_PICK = 41
        const val REQ_CONFIGURE = 42
    }
}

/** A widget for a tray card: its name, its view, and what long-pressing the card's title does. */
class TrayCard(val title: CharSequence, val view: View, val onLongPress: () -> Unit)

/**
 * Lays a widget out at a size it's happy with ([naturalW] x [naturalH] px) and scales it down to
 * the width it's given, so a widget with a large minimum size shrinks instead of being cut off.
 * Touches are mapped through the scale by Android.
 */
class ScaledWidget(context: Context, private val naturalW: Int, private val naturalH: Int) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val childW = maxOf(w, naturalW)
        val scale = w / childW.toFloat()
        getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(naturalH, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, (naturalH * scale).toInt())
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        val scale = width / child.measuredWidth.toFloat()
        child.pivotX = 0f; child.pivotY = 0f
        child.scaleX = scale; child.scaleY = scale
    }
}
