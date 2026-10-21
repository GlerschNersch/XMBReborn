package dev.xmbreborn.xmb

import dev.xmbreborn.xmb.XmbMetrics as M
import kotlin.math.abs
import kotlin.math.max

/** Where one icon lands this frame: centre, square size and opacity (all in screen pixels). */
data class Placement(
    val category: Int,
    val item: Int,          // -1 for the category icon itself
    val x: Float,
    val y: Float,
    val size: Float,
    val alpha: Float,
    val focus: Float,       // 1 = the focused icon, falling to 0 one step away
)

/** Pure layout of the bar for a viewport; no GL, so it can be unit-tested. */
class XmbLayout(val width: Float, val height: Float) {
    val portrait = height > width
    /** Reference pixel -> screen pixel. */
    val unit = minOf(width, height) / M.REF_SHORT_SIDE
    val anchorX = width * if (portrait) M.ANCHOR_X_PORTRAIT else M.ANCHOR_X_LANDSCAPE
    val anchorY = height * if (portrait) M.ANCHOR_Y_PORTRAIT else M.ANCHOR_Y_LANDSCAPE

    fun columnX(category: Int, scroll: Float) = anchorX + (category - scroll) * M.CATEGORY_SPACING * unit

    fun categories(model: XmbModel, motion: XmbMotion): List<Placement> =
        model.categories.indices.mapNotNull { i ->
            val f = closeness(i - motion.category)
            val x = columnX(i, motion.category)
            val size = lerp(M.CATEGORY_ICON, M.CATEGORY_ICON_FOCUSED, f) * unit
            if (x < -size || x > width + size) null
            else Placement(i, -1, x, anchorY, size, lerp(M.CATEGORY_ALPHA, 1f, f), f)
        }

    /** Rows of every column near focus; a column's rows fade with its distance from focus. */
    fun items(model: XmbModel, motion: XmbMotion): List<Placement> {
        val out = ArrayList<Placement>()
        for (c in model.categories.indices) {
            val columnAlpha = closeness(c - motion.category)
            if (columnAlpha <= 0.01f) continue
            val x = columnX(c, motion.category)
            val scroll = motion.item(c)
            model.categories[c].items.indices.forEach { j ->
                val r = j - scroll
                val y = rowY(r)
                val f = closeness(r)
                val size = lerp(M.ITEM_ICON, M.ITEM_ICON_FOCUSED, f) * unit
                if (y < -size || y > height + size) return@forEach
                out.add(Placement(c, j, x, y, size, columnAlpha * lerp(M.ITEM_ALPHA, 1f, f), f))
            }
        }
        return out
    }

    /**
     * Row centre for a row [r] places from the focused one. Rows at or after focus hang below
     * the bar; rows before it stack upward above the bar, crossing the bar between -1 and 0.
     */
    fun rowY(r: Float): Float {
        val below = anchorY + M.ITEM_GAP_BELOW * unit
        val above = anchorY - M.ITEM_GAP_ABOVE * unit
        val step = M.ITEM_SPACING * unit
        return when {
            r >= 0f -> below + r * step
            r <= -1f -> above + (r + 1f) * step
            else -> lerp(above, below, r + 1f)
        }
    }

    companion object {
        fun closeness(distance: Float) = max(0f, 1f - abs(distance))
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
