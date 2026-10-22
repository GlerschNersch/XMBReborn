package dev.xmbreborn

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
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
 * D-pad and gamepad buttons work too, for TV boxes and controllers. A quick flick up or down
 * keeps a list going for a while, a row at a time, slowing as it goes.
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
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * FLING_MIN_FACTOR
    private var velocity: VelocityTracker? = null

    private val a11y = XmbAccessibility(this, renderer,
        activate = { c, i, open -> renderer.post { r -> activateNow(r, c, i, open) } },
        showOptions = { c, i -> renderer.post { r -> activateNow(r, c, i, open = false); showOptionsFor(r) } },
        chooseOption = { i -> renderer.post { r -> chooseOptionNow(r, i) } },
    )

    override fun getAccessibilityNodeProvider() = a11y
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        return if (am?.isTouchExplorationEnabled == true) a11y.onHover(event) else super.dispatchHoverEvent(event)
    }

    /** Remaining rows of a fling, and the gap before the next; each step plays the tick. */
    private var flingLeft = 0
    private var caughtFling = false
    private var flingDir = 0
    private var flingGap = 0L
    private val flingStep = object : Runnable {
        override fun run() {
            if (flingLeft <= 0) return
            flingLeft--
            move(0, flingDir)
            flingGap = (flingGap * FLING_SLOWDOWN).toLong().coerceAtMost(FLING_MAX_GAP_MS)
            if (flingLeft > 0) postDelayed(this, flingGap)
        }
    }

    private fun stopFling() { flingLeft = 0; removeCallbacks(flingStep) }

    /** After a quick vertical flick, carries on by a number of rows that grows with its speed. */
    private fun fling(vy: Float) {
        if (kotlin.math.abs(vy) < minFling) return
        val rowPx = M.ITEM_SPACING * renderer.lastLayout.unit
        if (rowPx <= 0f) return
        flingLeft = ((kotlin.math.abs(vy) * FLING_SECONDS) / rowPx).toInt().coerceAtMost(FLING_MAX_ROWS)
        if (flingLeft <= 0) return
        flingDir = if (vy < 0) 1 else -1
        flingGap = FLING_FIRST_GAP_MS
        post(flingStep)
    }

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
        // Keep shaders and textures across a trip to another app, so the first frame back is quick.
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(holder: android.view.SurfaceHolder) {
        super.surfaceCreated(holder)
        // The PS3 drew the XMB at 60 fps; asking for no more lets 90-165 Hz panels drop to 60
        // while home is showing. Motion is time-based, so nothing else changes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { holder.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
        }
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
        a11y.optionsChanged(open = true)
    }

    /** Back to a column's top row, closing any options (Home pressed while already home). */
    fun goHome(categoryId: String) = renderer.post { r ->
        cancelOptionsNow(r)
        val m = r.model ?: return@post
        val target = m.categories.indexOfFirst { it.id == categoryId }.takeIf { it >= 0 } ?: return@post
        val movedColumn = m.setCategory(target)
        val movedRow = m.focused.setFocus(0)
        if (movedColumn || movedRow) sfx.play(Sfx.Kind.CANCEL)
    }

    /** Focuses row [index] of the column [categoryId] (Jump to Letter). */
    fun focusRow(categoryId: String, index: Int) = renderer.post { r ->
        val m = r.model ?: return@post
        val cat = m.category(categoryId) ?: return@post
        if (m.categories.indexOf(cat).let(m::setCategory) or cat.setFocus(index)) { sfx.play(Sfx.Kind.CURSOR); a11y.contentChanged() }
    }

    /** Closes the panel without a choice, if it's showing (e.g. on back). */
    fun cancelOptions() = renderer.post(::cancelOptionsNow)

    private fun showOptionsFor(r: XmbRenderer) {
        val item = r.model?.focused?.focusedItem ?: return
        val options = optionsFor(item) ?: return
        r.openOptions(options)
        sfx.play(Sfx.Kind.OPTION)
        a11y.optionsChanged(open = true)
    }

    // The *Now functions run on the GL thread. Input is decided there, in queue order, so a key
    // pressed right after the one that opens the panel already goes to the panel.

    private fun moveOptionNow(r: XmbRenderer, delta: Int) { if (r.moveOption(delta)) { sfx.play(Sfx.Kind.CURSOR); a11y.contentChanged() } }
    private fun moveOption(delta: Int) = renderer.post { moveOptionNow(it, delta) }

    private fun chooseOptionNow(r: XmbRenderer, index: Int = -1) {
        if (index >= 0) r.setOption(index)
        val entry = r.chooseOption() ?: return
        sfx.play(Sfx.Kind.DECIDE)
        a11y.optionsChanged(open = false)
        post { entry.onSelect() }
    }

    private fun cancelOptionsNow(r: XmbRenderer) {
        if (!r.optionsShowing) return
        r.closeOptions(cancelled = true)
        sfx.play(Sfx.Kind.CANCEL)
        a11y.optionsChanged(open = false)
    }

    /** Moves focus and plays the tick if anything actually moved. */
    private fun moveNow(r: XmbRenderer, dx: Int, dy: Int) {
        val m = r.model ?: return
        val moved = if (dx != 0) m.moveCategory(dx) else m.focused.moveFocus(dy)
        if (moved) { sfx.play(Sfx.Kind.CURSOR); a11y.contentChanged() }
        // A fling that hits the end of the list stops there.
        else if (dy != 0) post(::stopFling)
    }

    /** Accessibility click / long-click: focus that column (and row), opening it if [open]. */
    private fun activateNow(r: XmbRenderer, category: Int, item: Int, open: Boolean) {
        cancelOptionsNow(r)
        val m = r.model ?: return
        var moved = m.setCategory(category)
        if (item >= 0) moved = m.focused.setFocus(item) || moved
        if (moved) { sfx.play(Sfx.Kind.CURSOR); a11y.contentChanged() }
        if (open && item >= 0) openNow(r)
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
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Touching the screen stops a fling where it is, like catching a list.
                val caught = flingLeft > 0
                stopFling()
                velocity?.recycle()
                velocity = VelocityTracker.obtain()
                caughtFling = caught
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
                if (axis == 0 && !longPressed && !caughtFling) {
                    if (downOnOptions) tapOptions(e.x, e.y) else tap(e.x, e.y)
                }
                if (axis == 2 && !downOnOptions && !longPressed) velocity?.let {
                    it.computeCurrentVelocity(1000)
                    fling(it.yVelocity)
                }
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); velocity?.recycle(); velocity = null }
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
        // A page at a time, like L1/R1 in a long PS3 list.
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_BUTTON_L1 -> moveNow(r, 0, -PAGE_ROWS)
        KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_BUTTON_R1 -> moveNow(r, 0, PAGE_ROWS)
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> jumpToLetterNow(r, ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString())
        else -> Unit // back with nothing open: home stays home
    }

    /** Typing a letter on a keyboard jumps to the first row starting with it, if there is one. */
    private fun jumpToLetterNow(r: XmbRenderer, letter: String) {
        val cat = r.model?.focused ?: return
        val index = cat.letters().firstOrNull { it.first == letter }?.second ?: return
        if (cat.setFocus(index)) { sfx.play(Sfx.Kind.CURSOR); a11y.contentChanged() }
    }

    private companion object {
        val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        ) + (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z)

        /** Rows skipped by Page Up/Down and L1/R1. */
        const val PAGE_ROWS = 10

        /** Fraction of the on-screen spacing a finger must travel per step (<1 feels lighter). */
        const val DRAG_STEP_FRACTION = 0.6f

        /** A flick must be this many times the system's minimum fling speed to carry on. */
        const val FLING_MIN_FACTOR = 4
        /** Rows a fling adds: its speed (px/s) times this, in row spacings. */
        const val FLING_SECONDS = 0.35f
        const val FLING_MAX_ROWS = 60
        /** First gap between fling steps; each later one is [FLING_SLOWDOWN] times longer. */
        const val FLING_FIRST_GAP_MS = 28L
        const val FLING_SLOWDOWN = 1.09f
        const val FLING_MAX_GAP_MS = 150L
    }
}
