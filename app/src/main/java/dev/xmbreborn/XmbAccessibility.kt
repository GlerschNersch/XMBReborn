package dev.xmbreborn

import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import dev.xmbreborn.render.XmbRenderer
import dev.xmbreborn.xmb.Placement

/**
 * The bar is drawn in GL, so on its own it's one blank view to TalkBack (and to UI tests). This
 * exposes every visible category icon and row as a virtual node with its title, where it's drawn,
 * and click / long-click actions, so the launcher can be explored by touch and driven by switch
 * access or automation.
 *
 * While the options panel is open only its rows are exposed, as it takes all input then.
 *
 * Virtual ids: category icons are `c`, rows are `CATEGORY_STRIDE + c * CATEGORY_STRIDE + i`,
 * option rows are `OPTION_BASE + i`.
 */
class XmbAccessibility(
    private val host: View,
    private val renderer: XmbRenderer,
    /** Focuses [category] (and row [item], or -1 for the icon); opens it if [open]. */
    private val activate: (category: Int, item: Int, open: Boolean) -> Unit,
    /** Shows options for row [item] of [category]. */
    private val showOptions: (category: Int, item: Int) -> Unit,
    /** Chooses row [index] of the open options panel. */
    private val chooseOption: (index: Int) -> Unit,
) : AccessibilityNodeProvider() {
    private var hovered = View.NO_ID

    private fun idOf(p: Placement) = if (p.item < 0) p.category else CATEGORY_STRIDE + p.category * CATEGORY_STRIDE + p.item

    private fun placement(id: Int): Placement? = renderer.lastPlacements.firstOrNull { idOf(it) == id && it.alpha > MIN_ALPHA }

    private fun title(p: Placement): String? {
        val cat = renderer.model?.categories?.getOrNull(p.category) ?: return null
        if (p.item < 0) return cat.title
        val item = cat.items.getOrNull(p.item) ?: return null
        return listOfNotNull(item.title, item.subtitle).joinToString(", ")
    }

    private fun visible() = renderer.lastPlacements.filter {
        it.alpha > MIN_ALPHA && it.y + it.size / 2 > 0 && it.y - it.size / 2 < host.height && title(it) != null
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == View.NO_ID) {
            return AccessibilityNodeInfo.obtain(host).also { info ->
                host.onInitializeAccessibilityNodeInfo(info)
                if (renderer.optionsShowing) optionIds().forEach { info.addChild(host, it) }
                else visible().forEach { info.addChild(host, idOf(it)) }
            }
        }
        if (virtualViewId >= OPTION_BASE) return optionNode(virtualViewId - OPTION_BASE)
        val p = placement(virtualViewId) ?: return null
        val model = renderer.model
        val focusedHere = model != null && p.category == model.focus &&
            (p.item < 0 || p.item == model.categories[p.category].focus)
        return AccessibilityNodeInfo.obtain(host, virtualViewId).apply {
            setParent(host)
            packageName = host.context.packageName
            className = if (p.item < 0) "android.widget.Tab" else "android.widget.Button"
            text = title(p)
            // Read after the name ("Music, Category"), as AndroidX's role description is.
            if (p.item < 0) extras.putCharSequence("AccessibilityNodeInfo.roleDescription", "Category")
            isEnabled = true
            isVisibleToUser = true
            isClickable = true
            isLongClickable = p.item >= 0
            isSelected = focusedHere
            isFocusable = true
            isAccessibilityFocused = hovered == virtualViewId
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            if (p.item >= 0) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
            addAction(
                if (hovered == virtualViewId) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS
            )
            val r = boundsOf(p, host.width)
            setBoundsInParent(r)
            val loc = IntArray(2).also(host::getLocationOnScreen)
            r.offset(loc[0], loc[1])
            setBoundsInScreen(r)
        }
    }

    private fun optionIds(): List<Int> {
        val rows = renderer.optionRows
        return (0 until minOf(rows.count, rows.labels.size)).map { OPTION_BASE + it }
    }

    private fun optionNode(index: Int): AccessibilityNodeInfo? {
        val rows = renderer.optionRows
        if (!renderer.optionsShowing) return null
        val label = rows.labels.getOrNull(index) ?: return null
        val id = OPTION_BASE + index
        return AccessibilityNodeInfo.obtain(host, id).apply {
            setParent(host)
            packageName = host.context.packageName
            className = "android.widget.Button"
            text = label
            isEnabled = true
            isVisibleToUser = true
            isClickable = true
            isSelected = index == rows.focus
            isFocusable = true
            isAccessibilityFocused = hovered == id
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            addAction(
                if (hovered == id) AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                else AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS
            )
            val y = rows.firstY + index * rows.step
            val r = Rect(rows.panelX.toInt(), (y - rows.step / 2).toInt(), host.width, (y + rows.step / 2).toInt())
            setBoundsInParent(r)
            val loc = IntArray(2).also(host::getLocationOnScreen)
            r.offset(loc[0], loc[1])
            setBoundsInScreen(r)
        }
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == View.NO_ID) return host.performAccessibilityAction(action, arguments)
        if (virtualViewId >= OPTION_BASE) return when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> { chooseOption(virtualViewId - OPTION_BASE); true }
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> { setHovered(virtualViewId); true }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (hovered == virtualViewId) { hovered = View.NO_ID; send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) }
                true
            }
            else -> false
        }
        val p = placement(virtualViewId) ?: return false
        return when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> { activate(p.category, p.item, p.item >= 0); send(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED); true }
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> p.item >= 0 && run { showOptions(p.category, p.item); true }
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> { setHovered(virtualViewId); true }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (hovered == virtualViewId) { hovered = View.NO_ID; send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) }
                true
            }
            else -> false
        }
    }

    /** Explore-by-touch: the node under the finger takes accessibility focus and is announced. */
    fun onHover(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_HOVER_EXIT) { setHovered(View.NO_ID); return true }
        if (renderer.optionsShowing) {
            val rows = renderer.optionRows
            val i = rows.indexAt(e.y)
            setHovered(if (e.x >= rows.panelX && i in rows.labels.indices) OPTION_BASE + i else View.NO_ID)
            return true
        }
        val hit = visible().filter { boundsOf(it, host.width).contains(e.x.toInt(), e.y.toInt()) }
            .minByOrNull { (it.x - e.x) * (it.x - e.x) + (it.y - e.y) * (it.y - e.y) }
        setHovered(hit?.let(::idOf) ?: View.NO_ID)
        return true
    }

    /** Tells accessibility services the bar moved (focus changed, rows scrolled). */
    fun contentChanged() = host.post {
        if (!host.isAttachedToWindow) return@post
        host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /**
     * The options panel opened (or closed): the tree switches between the bar and the panel, and
     * with TalkBack on, its focused row takes accessibility focus so it's read straight away.
     * Waits a frame or two for the renderer to publish the panel's rows.
     */
    fun optionsChanged(open: Boolean) = host.postDelayed({
        if (!host.isAttachedToWindow) return@postDelayed
        host.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        val am = host.context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (open && am?.isTouchExplorationEnabled == true) {
            setHovered(OPTION_BASE + renderer.optionRows.focus.coerceAtLeast(0))
        } else if (!open && hovered >= OPTION_BASE) {
            setHovered(View.NO_ID)
        }
    }, OPTIONS_SETTLE_MS)

    private fun setHovered(id: Int) {
        if (id == hovered) return
        val old = hovered
        hovered = id
        if (old != View.NO_ID) send(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        if (id != View.NO_ID) {
            send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            send(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        }
    }

    private fun send(id: Int, type: Int) {
        val parent = host.parent ?: return
        val event = AccessibilityEvent.obtain(type).apply {
            packageName = host.context.packageName
            setSource(host, id)
            val label = if (id >= OPTION_BASE) renderer.optionRows.labels.getOrNull(id - OPTION_BASE) else placement(id)?.let(::title)
            label?.let { text.add(it) }
        }
        parent.requestSendAccessibilityEvent(host, event)
    }

    companion object {
        const val CATEGORY_STRIDE = 10_000
        /** Above any row id (categories stay well under a thousand). */
        const val OPTION_BASE = 100_000_000
        private const val MIN_ALPHA = 0.05f
        private const val OPTIONS_SETTLE_MS = 80L

        /** A row's touch target covers its icon and label, out to the screen's right edge. */
        fun boundsOf(p: Placement, width: Int): Rect {
            val half = p.size * 0.6f
            val right = if (p.item < 0) p.x + half else width.toFloat()
            return Rect((p.x - half).toInt(), (p.y - half).toInt(), right.toInt(), (p.y + half).toInt())
        }
    }
}
