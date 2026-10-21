package dev.xmbreborn

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import dev.xmbreborn.render.XmbRenderer
import dev.xmbreborn.xmb.XmbItem
import dev.xmbreborn.xmb.XmbMetrics as M
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The launcher surface. Drags scroll the bar one column / row per spacing of travel (axis
 * locked after touch slop); taps focus, and a tap on the focused row opens it. D-pad and
 * gamepad buttons work too, for TV boxes and controllers.
 */
@SuppressLint("ViewConstructor")
class XmbView(
    context: Context,
    private val sfx: Sfx,
    private val onOpen: (XmbItem) -> Unit,
) : GLSurfaceView(context) {
    val renderer = XmbRenderer()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private var downX = 0f
    private var downY = 0f
    private var axis = 0          // 0 undecided, 1 horizontal, 2 vertical
    private var applied = 0       // steps already applied during this drag

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Moves focus on the GL thread and plays the tick if anything actually moved. */
    private fun move(dx: Int, dy: Int) = renderer.post { r ->
        val m = r.model ?: return@post
        val moved = if (dx != 0) m.moveCategory(dx) else m.focused.moveFocus(dy)
        if (moved) sfx.play(Sfx.Kind.CURSOR)
    }

    private fun openFocused() = renderer.post { r ->
        val item = r.model?.focused?.focusedItem ?: return@post
        sfx.play(Sfx.Kind.DECIDE)
        post { onOpen(item) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; axis = 0; applied = 0 }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                if (axis == 0 && hypot(dx, dy) > slop) axis = if (abs(dx) > abs(dy)) 1 else 2
                if (axis != 0) {
                    val unit = renderer.lastLayout.unit
                    val step = (if (axis == 1) M.CATEGORY_SPACING else M.ITEM_SPACING) * unit * DRAG_STEP_FRACTION
                    // Dragging left/up pulls the next column/row into focus.
                    val steps = (-(if (axis == 1) dx else dy) / step).toInt()
                    if (steps != applied) {
                        val delta = steps - applied
                        applied = steps
                        if (axis == 1) move(delta, 0) else move(0, delta)
                    }
                }
            }
            MotionEvent.ACTION_UP -> if (axis == 0) tap(e.x, e.y)
        }
        return true
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
                if (hit.item == cat.focus) {
                    val item = cat.focusedItem ?: return@post
                    sfx.play(Sfx.Kind.DECIDE)
                    post { onOpen(item) }
                } else if (cat.setFocus(hit.item)) sfx.play(Sfx.Kind.CURSOR)
            }
        }
    }

    /** Rows are also tappable on their label, to the right of the icon. */
    private fun hitLabel(x: Float, y: Float) = renderer.lastPlacements
        .filter { it.item >= 0 && x > it.x && abs(it.y - y) < it.size * 0.5f }
        .minByOrNull { abs(it.y - y) }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> move(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(0, 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> openFocused()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private companion object {
        /** Fraction of the on-screen spacing a finger must travel per step (<1 feels lighter). */
        const val DRAG_STEP_FRACTION = 0.6f
    }
}
