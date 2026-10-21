package dev.xmbreborn

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import dev.xmbreborn.xmb.XmbMetrics
import kotlin.math.abs

/**
 * Widget trays: each column of the bar can hold real Android widgets, shown in gold glass cards
 * beside its list. A tray belongs to its column like the column's list does: it slides with it
 * and fades over the move, gone while another column is focused. "+ Widget" (or "Add Widget
 * Here" in a row's options) opens Android's widget picker for the focused column, plus the
 * widget's own set-up screen if it has one. Long-pressing a card's title arranges it: move it
 * up or down, make it bigger or smaller, widen the tray (the list then shows icons only), or
 * remove it.
 */
class WidgetTrays(
    private val activity: Activity,
    private val root: FrameLayout,
    private val xmb: XmbView,
) {
    private val host = AppWidgetHost(activity, HOST_ID)
    private val manager = AppWidgetManager.getInstance(activity)
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val holders = HashMap<String, FrameLayout>()
    private val density = activity.resources.displayMetrics.density
    private var pendingColumn: String? = null
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var running = true

    private val button: TextView = text("+", 26f).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(170, 26, 18, 6)); setStroke(dp(1), AMBER)
        }
        includeFontPadding = false
        contentDescription = "Add a widget to this column"
        setOnClickListener { focusedColumn()?.let(::add) }
    }

    init {
        root.addView(button, FrameLayout.LayoutParams(dp(52), dp(52),
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(56)) })
        // Rotating changes where trays go and how many columns they use.
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) root.post { holders.keys.forEach(::refill); listState = "" }
        }
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running) return
                frame()
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    fun start() = host.startListening()
    fun stop() = host.stopListening()
    fun release() { running = false }

    /** The column the bar is on now, or null before it's loaded. */
    fun focusedColumn(): String? = xmb.renderer.columnIds.getOrNull(Math.round(xmb.renderer.barScroll))

    // ---- following the bar ---------------------------------------------------------------------

    private var listState = ""
    private var optionsAlpha = 1f

    /** Each frame: trays slide and fade with their columns; lists beside a tray make room for it. */
    private fun frame() {
        val ids = xmb.renderer.columnIds
        for (c in ids) if (c !in holders) addHolder(c)
        val spacing = XmbMetrics.CATEGORY_SPACING * minOf(root.width, root.height) / XmbMetrics.REF_SHORT_SIDE
        // The options panel is drawn by the bar, underneath these views: step aside while it's open.
        val optionsGoal = if (xmb.renderer.optionsShowing) 0f else 1f
        optionsAlpha += (optionsGoal - optionsAlpha) * 0.35f
        button.alpha = optionsAlpha
        button.isEnabled = optionsAlpha > 0.5f
        for ((column, holder) in holders) {
            val index = ids.indexOf(column)
            val d = if (index < 0) 99f else index - xmb.renderer.barScroll
            val a = (1f - abs(d) * XmbMetrics.ROW_FADE_RATE).coerceIn(0f, 1f) * optionsAlpha
            val show = a > 0.01f && holder.childCount > 0
            val gate = holder.parent as Gate
            // Slides only while fading; fully faded it stays put (moving a view far off screen
            // stopped Android redrawing it). Touches pass through while it's hidden.
            holder.alpha = a
            holder.translationX = if (show) d * spacing else 0f
            gate.passThrough = !show
        }
        // Beside a tray, the list makes room: labels end before it (smaller), or with a wide
        // tray, rows show icons only.
        val focused = focusedColumn()
        val hasTray = focused != null && ids(focused).isNotEmpty()
        val state = if (!hasTray) "none" else "${left(focused!!)}:${wide(focused)}"
        if (state != listState) {
            listState = state
            val limit = if (hasTray) left(focused!!) else Float.NaN
            val iconsOnly = hasTray && wide(focused!!)
            xmb.renderer.post {
                it.labelLimitX = limit
                it.labelScale = if (hasTray) LIST_SCALE else 1f
                it.labelsHidden = iconsOnly
            }
        }
    }

    private fun addHolder(column: String) {
        val holder = FrameLayout(activity)
        holders[column] = holder
        root.addView(Gate(activity).apply { addView(holder) }, root.indexOfChild(button)) // under the button
        refill(column)
    }

    /**
     * Sits over a column's tray. While the tray is hidden it lets touches through to the bar.
     * While it shows, a gesture that turns clearly sideways is handed to the bar, so swiping over
     * a widget still changes category; taps, vertical scrolls and press-and-hold stay with the
     * widgets (a picked-up widget blocks this while it's dragged).
     */
    private inner class Gate(context: Context) : FrameLayout(context) {
        var passThrough = true
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var toBar = false

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = !passThrough && super.dispatchTouchEvent(ev)

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downTime = ev.downTime; toBar = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop && abs(dx) > abs(dy) * SIDEWAYS) {
                        // Start the bar's gesture where the finger went down, then feed it the rest.
                        toBar = true
                        MotionEvent.obtain(downTime, ev.eventTime, MotionEvent.ACTION_DOWN, downX, downY, 0).also {
                            xmb.dispatchTouchEvent(it); it.recycle()
                        }
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!toBar) return false
            xmb.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) toBar = false
            return true
        }
    }

    // ---- adding and removing -------------------------------------------------------------------

    /** Opens Android's widget picker for [column]'s tray. */
    fun add(column: String) {
        pendingColumn = column
        pendingId = host.allocateAppWidgetId()
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId), REQ_PICK)
        } catch (e: Exception) {
            host.deleteAppWidgetId(pendingId); clearPending()
            Toast.makeText(activity, "No widget picker on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    /** The picker's and set-up screen's answers; true if the request was ours. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_PICK && requestCode != REQ_CONFIGURE) return false
        val id = pendingId
        val column = pendingColumn
        if (resultCode != Activity.RESULT_OK || column == null || id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(id)
            clearPending()
            return true
        }
        if (requestCode == REQ_PICK && manager.getAppWidgetInfo(id)?.configure != null) {
            // The widget's own set-up first (which city a weather widget shows, and so on).
            runCatching { host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQ_CONFIGURE, null) }
                .onFailure {
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

    /** Long-press on a card's title: arrange the tray, size the widget, or remove it. */
    private fun showMenu(column: String, id: Int, name: CharSequence) {
        val ids = ids(column)
        val at = ids.indexOf(id)
        val actions = buildList<Pair<String, () -> Unit>> {
            if (at > 0) add("Move Up" to { save(column, ids.toMutableList().apply { removeAt(at); add(at - 1, id) }) })
            if (at in 0 until ids.lastIndex) add("Move Down" to { save(column, ids.toMutableList().apply { removeAt(at); add(at + 1, id) }) })
            if (size(id) < SIZES.lastIndex) add("Bigger" to { prefs.edit().putInt("size:$id", size(id) + 1).apply() })
            if (size(id) > 0) add("Smaller" to { prefs.edit().putInt("size:$id", size(id) - 1).apply() })
            add((if (wide(column)) "Narrower Tray" else "Wider Tray (icons only list)") to {
                prefs.edit().putBoolean("wide:$column", !wide(column)).apply()
                listState = ""
            })
            add("Remove" to { host.deleteAppWidgetId(id); save(column, ids - id) })
        }
        AlertDialog.Builder(activity)
            .setTitle(name)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which ->
                actions[which].second()
                refill(column)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun ids(column: String): List<Int> =
        prefs.getString("tray:$column", "")!!.split(',').mapNotNull { it.toIntOrNull() }

    private fun save(column: String, ids: List<Int>) = prefs.edit().putString("tray:$column", ids.joinToString(",")).apply()

    // ---- layout ---------------------------------------------------------------------------------

    private val landscape get() = root.width > root.height

    /** Where [column]'s tray starts (screen x); the list beside it ends before this. A wide tray
     *  starts just right of the row icons. */
    private fun left(column: String) = root.width * when {
        wide(column) -> if (landscape) WIDE_LEFT_LANDSCAPE else WIDE_LEFT
        landscape -> LEFT_LANDSCAPE
        else -> LEFT
    }

    private fun wide(column: String) = prefs.getBoolean("wide:$column", false)
    private fun size(id: Int) = prefs.getInt("size:$id", 1).coerceIn(0, SIZES.lastIndex)

    /** Rebuilds [column]'s cards from what's saved. Portrait: one column under the bar; landscape:
     *  below the bar (clear of the categories) in two columns, since height is what's short. */
    private fun refill(column: String) {
        val holder = holders[column] ?: return
        holder.removeAllViews()
        val ids = ids(column)
        if (ids.isEmpty() || root.width == 0) return
        val left = left(column)
        val columns = if (landscape) 2 else 1
        val innerPx = (root.width - left) - dp(14) - (columns - 1) * dp(12)
        val cardWidthDp = ((innerPx / columns) / density).toInt()
        val cards = ids.mapNotNull { id ->
            val info = manager.getAppWidgetInfo(id) ?: return@mapNotNull null
            val name = info.loadLabel(activity.packageManager)
            // A little extra height for widgets that draw more than they report.
            val baseDp = (maxOf(info.minHeight, info.minResizeHeight) / density).toInt().coerceIn(96, 260) + 12
            val hDp = (baseDp * SIZES[size(id)]).toInt()
            val wDp = maxOf(cardWidthDp, (info.minWidth / density).toInt())
            val view = host.createView(activity, id, info).apply {
                updateAppWidgetOptions(Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
                })
            }
            card(name, Scaled(activity, dp(wDp), dp(hDp)).apply { addView(view) }, column, id) { showMenu(column, id, name) }
        }
        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            for (c in 0 until columns) {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    cards.filterIndexed { i, _ -> i % columns == c }.forEach {
                        addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
                    }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (c > 0) leftMargin = dp(12) })
            }
        }
        holder.addView(grid, FrameLayout.LayoutParams((root.width - left).toInt() - dp(14), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = left.toInt()
            topMargin = (root.height * if (landscape) TOP_LANDSCAPE else TOP).toInt()
        })
    }

    /** A widget in a dark glass card with a gold hairline; long-press the title row to remove it. */
    /**
     * A widget straight on the XMB background (no card: widgets bring their own), under a small,
     * faint name. Press and hold anywhere on it, then drag, to move it; hold without moving to
     * arrange it. Taps and swipes inside the widget still go to the widget.
     */
    private fun card(title: CharSequence, content: View, column: String, id: Int, onArrange: () -> Unit) =
        DragCard(activity, column, id, onArrange).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title.toString(), 11f, alpha = 0.65f).apply {
                setPadding(dp(4), dp(6), dp(4), dp(6))
                contentDescription = "$title. Press and hold to move or arrange"
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    /**
     * Watches every touch on a widget without getting in its way: a press held still for the
     * long-press time picks the widget up (the widget gets a cancel), and from then on the finger
     * drags it. Let go without having moved and the arrange menu opens instead.
     */
    private inner class DragCard(context: Context, val column: String, val widgetId: Int, private val onArrange: () -> Unit) :
        LinearLayout(context) {
        private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var moved = false
        private val pickUp = Runnable {
            dragging = true
            moved = false
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            // The widget had the touch until now; tell it the gesture is over.
            val now = android.os.SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { super.dispatchTouchEvent(it); it.recycle() }
            parent?.requestDisallowInterceptTouchEvent(true)
            startDrag(this)
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (dragging) {
                val dy = ev.rawY - downY
                if (kotlin.math.hypot(ev.rawX - downX, dy) > slop) moved = true
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> moveDrag(this, dy)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        dragging = false
                        endDrag(this)
                        if (!moved && ev.actionMasked == MotionEvent.ACTION_UP) onArrange()
                    }
                }
                return true
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    postDelayed(pickUp, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.hypot(ev.rawX - downX, ev.rawY - downY) > slop) removeCallbacks(pickUp)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(pickUp)
            }
            super.dispatchTouchEvent(ev)
            return true // keep the gesture even over a widget's empty space, so a hold there still picks it up
        }
    }

    // ---- dragging ---------------------------------------------------------------------------------

    private var dragTarget = 0
    private var dragFrom = 0

    /** The widgets that move together with [card]: its stack in the tray. */
    private fun stack(card: View): List<View> {
        val col = card.parent as? ViewGroup ?: return emptyList()
        return (0 until col.childCount).map { col.getChildAt(it) }
    }

    private fun startDrag(card: DragCard) {
        dragFrom = stack(card).indexOf(card)
        dragTarget = dragFrom
        card.animate().scaleX(1.04f).scaleY(1.04f).alpha(0.9f).translationZ(dp(12).toFloat()).setDuration(120).start()
    }

    /** The lifted widget follows the finger; the others slide to open the gap where it would land. */
    private fun moveDrag(card: DragCard, dy: Float) {
        val views = stack(card)
        if (views.isEmpty()) return
        card.translationY = dy
        val step = card.height + dp(12)
        val centre = card.top + dy + card.height / 2f
        var target = views.indexOfFirst { it !== card && centre < it.top + it.height / 2f }.let { if (it < 0) views.lastIndex else it }
        if (target > dragFrom) target -= 1 // indexes of the others shift once the card leaves its slot
        target = target.coerceIn(0, views.lastIndex)
        if (centre > views.last().top + views.last().height / 2f) target = views.lastIndex
        if (target == dragTarget) return
        dragTarget = target
        for ((j, v) in views.withIndex()) {
            if (v === card) continue
            val shift = when {
                dragFrom < target && j in dragFrom + 1..target -> -step
                target < dragFrom && j in target until dragFrom -> step
                else -> 0
            }
            v.animate().translationY(shift.toFloat()).setDuration(140).start()
        }
    }

    /** Drops it in the slot opened for it, saves the new order and rebuilds the tray. */
    private fun endDrag(card: DragCard) {
        val views = stack(card)
        val from = dragFrom
        val to = dragTarget
        val slotY = views.getOrNull(to)?.let { (it.top - card.top).toFloat() } ?: 0f
        card.animate().translationY(if (to == from) 0f else slotY).scaleX(1f).scaleY(1f).alpha(1f).translationZ(0f).setDuration(140)
            .withEndAction {
                if (to != from) {
                    // Same stack in the tray's saved order (landscape trays interleave two stacks).
                    val stackIds = views.map { (it as DragCard).widgetId }.toMutableList()
                    stackIds.add(to, stackIds.removeAt(from))
                    val all = ids(card.column)
                    val slots = all.indices.filter { all[it] in stackIds }
                    val reordered = all.toMutableList().also { list -> slots.forEachIndexed { k, i -> list[i] = stackIds[k] } }
                    save(card.column, reordered)
                }
                refill(card.column)
            }.start()
    }

    /**
     * Lays a widget out at a size it's happy with ([naturalW] x [naturalH] px) and scales it down
     * to the width given, so a widget with a large minimum size shrinks rather than being cut off.
     * Android maps touches through the scale.
     */
    private class Scaled(context: Context, private val naturalW: Int, private val naturalH: Int) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val childW = maxOf(w, naturalW)
            getChildAt(0)?.measure(MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(naturalH, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, (naturalH * w / childW.toFloat()).toInt())
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val child = getChildAt(0) ?: return
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
            val scale = width / child.measuredWidth.toFloat()
            child.pivotX = 0f; child.pivotY = 0f
            child.scaleX = scale; child.scaleY = scale
        }
    }

    private fun text(s: String, sp: Float, bold: Boolean = false, alpha: Float = 1f, color: Int = Color.WHITE) = TextView(activity).apply {
        text = s
        setTextColor(color)
        this.alpha = alpha
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
    }

    private fun dp(v: Int) = (v * density).toInt()

    private companion object {
        // Shared with the Widget Lab's tray build, so widgets added there carry over.
        const val HOST_ID = 7302
        const val PREFS = "widget_lab_trays"
        const val REQ_PICK = 41
        const val REQ_CONFIGURE = 42
        const val LEFT = 0.47f
        const val TOP = 0.27f
        const val LEFT_LANDSCAPE = 0.40f
        const val TOP_LANDSCAPE = 0.36f
        const val LIST_SCALE = 0.8f
        /** How much more sideways than vertical a swipe over a widget must be to go to the bar. */
        const val SIDEWAYS = 1.3f
        const val WIDE_LEFT = 0.30f
        const val WIDE_LEFT_LANDSCAPE = 0.28f
        /** Card heights for Small / Medium / Large, relative to the widget's own size. */
        val SIZES = floatArrayOf(0.75f, 1f, 1.45f)
        val AMBER = Color.rgb(242, 178, 60)
    }
}
