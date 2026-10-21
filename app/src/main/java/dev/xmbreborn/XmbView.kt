package dev.xmbreborn

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import dev.xmbreborn.render.XmbRenderer
import dev.xmbreborn.xmb.Launch
import dev.xmbreborn.xmb.Placement
import dev.xmbreborn.xmb.XmbItem
import dev.xmbreborn.xmb.XmbMetrics as M
import dev.xmbreborn.xmb.XmbOptions
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The launcher surface. Drags scroll the bar one column / row per spacing of travel (axis
 * locked after touch slop); taps focus, and a tap on the focused row opens it. A long press on
 * a row, or Triangle / Menu, opens its options panel; while that shows, input goes to it.
 * D-pad and gamepad buttons work too, for TV boxes and controllers.
 */
@SuppressLint("ViewConstructor")
class XmbView(
    context: Context,
    private val sfx: Sfx,
    private val onOpen: (XmbItem) -> Unit,
    /** Builds an item's options (called on the GL thread); null if it has none. */
    private val optionsFor: (XmbItem) -> XmbOptions?,
) : GLSurfaceView(context) {
    val renderer = XmbRenderer()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var axis = 0          // 0 undecided, 1 horizontal, 2 vertical
    private var applied = 0       // steps already applied during this drag
    private var longPressed = false
    private var downOnOptions = false

    private val longPress = Runnable {
        longPressed = true
        val hit = hitRow(downX, downY) ?: return@Runnable
        renderer.post { r ->
            val cat = r.model?.takeIf { it.focus == hit.category }?.focused ?: return@post
            if (cat.setFocus(hit.item)) sfx.play(Sfx.Kind.CURSOR)
            showOptionsFor(r)
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()).top.toFloat()
        else @Suppress("DEPRECATION") insets.systemWindowInsetTop.toFloat()
        renderer.post { it.topInset = top }
        return super.onApplyWindowInsets(insets)
    }

    /** Opens [options] directly, e.g. for a setting's choices. */
    fun showOptions(options: XmbOptions) = renderer.post { r ->
        r.openOptions(options)
        sfx.play(Sfx.Kind.OPTION)
    }

    /** Closes the panel without a choice, if it's showing (e.g. on back). */
    fun cancelOptions() = renderer.post(::cancelOptionsNow)

    private fun showOptionsFor(r: XmbRenderer) {
        val item = r.model?.focused?.focusedItem ?: return
        val options = optionsFor(item) ?: return
        r.openOptions(options)
        sfx.play(Sfx.Kind.OPTION)
    }

    // The *Now functions run on the GL thread. Input is decided there, in queue order, so a key
    // pressed right after the one that opens the panel already goes to the panel.

    private fun moveOptionNow(r: XmbRenderer, delta: Int) { if (r.moveOption(delta)) sfx.play(Sfx.Kind.CURSOR) }
    private fun moveOption(delta: Int) = renderer.post { moveOptionNow(it, delta) }

    private fun chooseOptionNow(r: XmbRenderer, index: Int = -1) {
        if (index >= 0) r.setOption(index)
        val entry = r.chooseOption() ?: return
        sfx.play(Sfx.Kind.DECIDE)
        post { entry.onSelect() }
    }

    private fun cancelOptionsNow(r: XmbRenderer) {
        if (!r.optionsShowing) return
        r.closeOptions(cancelled = true)
        sfx.play(Sfx.Kind.CANCEL)
    }

    /** Moves focus and plays the tick if anything actually moved. */
    private fun moveNow(r: XmbRenderer, dx: Int, dy: Int) {
        val m = r.model ?: return
        val moved = if (dx != 0) m.moveCategory(dx) else m.focused.moveFocus(dy)
        if (moved) sfx.play(Sfx.Kind.CURSOR)
    }
    private fun move(dx: Int, dy: Int) = renderer.post { moveNow(it, dx, dy) }

    /** Opens the focused row: our settings show their choices here; anything else launches. */
    private fun openNow(r: XmbRenderer) {
        val item = r.model?.focused?.focusedItem ?: return
        if (item.payload is Launch.Setting) { showOptionsFor(r); return }
        sfx.play(Sfx.Kind.DECIDE)
        post { onOpen(item) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; axis = 0; applied = 0; longPressed = false
                downOnOptions = renderer.optionsShowing
                if (!downOnOptions) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (axis == 0 && hypot(dx, dy) > slop) {
                    axis = if (abs(dx) > abs(dy)) 1 else 2
                    removeCallbacks(longPress)
                }
                if (axis != 0 && !longPressed) drag(dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (axis == 0 && !longPressed) {
                    if (downOnOptions) tapOptions(e.x, e.y) else tap(e.x, e.y)
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    private fun drag(dx: Float, dy: Float) {
        val unit = renderer.lastLayout.unit
        if (downOnOptions) {
            // Vertical drags walk the options list; sideways ones do nothing while it's open.
            if (axis != 2) return
            val steps = (dy / (M.OPTIONS_ROW * unit)).toInt()
            if (steps != applied) { moveOption(steps - applied); applied = steps }
            return
        }
        val step = (if (axis == 1) M.CATEGORY_SPACING else M.ITEM_SPACING) * unit * DRAG_STEP_FRACTION
        // Dragging left/up pulls the next column/row into focus.
        val steps = (-(if (axis == 1) dx else dy) / step).toInt()
        if (steps != applied) {
            val delta = steps - applied
            applied = steps
            if (axis == 1) move(delta, 0) else move(0, delta)
        }
    }

    /** A tap on an option chooses it; anywhere off the panel closes it. */
    private fun tapOptions(x: Float, y: Float) {
        val rows = renderer.optionRows
        if (x < rows.panelX) { cancelOptions(); return }
        val i = rows.indexAt(y)
        if (i >= 0) renderer.post { chooseOptionNow(it, i) }
    }

    private fun tap(x: Float, y: Float) {
        val hit = renderer.lastPlacements
            .filter { hypot(it.x - x, it.y - y) < it.size * 0.75f }
            .minByOrNull { hypot(it.x - x, it.y - y) }
            ?: hitLabel(x, y)
            ?: return
        renderer.post { r ->
            val m = r.model ?: return@post
            if (hit.item < 0) {
                if (m.setCategory(hit.category)) sfx.play(Sfx.Kind.CURSOR)
            } else if (hit.category == m.focus) {
                val cat = m.focused
                if (hit.item == cat.focus) openNow(r)
                else if (cat.setFocus(hit.item)) sfx.play(Sfx.Kind.CURSOR)
            }
        }
    }

    /** A row (not a category icon) under the finger, by icon or label. */
    private fun hitRow(x: Float, y: Float): Placement? = renderer.lastPlacements
        .filter { it.item >= 0 && hypot(it.x - x, it.y - y) < it.size * 0.75f }
        .minByOrNull { hypot(it.x - x, it.y - y) }
        ?: hitLabel(x, y)

    /** Rows are also tappable on their label, to the right of the icon. */
    private fun hitLabel(x: Float, y: Float) = renderer.lastPlacements
        .filter { it.item >= 0 && x > it.x && abs(it.y - y) < it.size * 0.5f }
        .minByOrNull { abs(it.y - y) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in HANDLED_KEYS) return super.onKeyDown(keyCode, event)
        renderer.post { r -> if (r.optionsShowing) optionsKey(r, keyCode) else barKey(r, keyCode) }
        return true
    }

    private fun optionsKey(r: XmbRenderer, keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> moveOptionNow(r, -1)
        KeyEvent.KEYCODE_DPAD_DOWN -> moveOptionNow(r, 1)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> chooseOptionNow(r)
        KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
        else -> cancelOptionsNow(r) // back, B, Triangle, Menu, left
    }

    private fun barKey(r: XmbRenderer, keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> moveNow(r, -1, 0)
        KeyEvent.KEYCODE_DPAD_RIGHT -> moveNow(r, 1, 0)
        KeyEvent.KEYCODE_DPAD_UP -> moveNow(r, 0, -1)
        KeyEvent.KEYCODE_DPAD_DOWN -> moveNow(r, 0, 1)
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> openNow(r)
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU -> showOptionsFor(r)
        else -> Unit // back with nothing open: home stays home
    }

    private companion object {
        val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK,
        )

        /** Fraction of the on-screen spacing a finger must travel per step (<1 feels lighter). */
        const val DRAG_STEP_FRACTION = 0.6f
    }
}
