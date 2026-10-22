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
            // Old and new lists cross-fade linearly as the bar moves, as on the PS3.
            val columnAlpha = max(0f, 1f - abs(c - motion.category) * M.ROW_FADE_RATE)
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
     * Row centre for a row [r] places from the focused one. The focused row sits well below
     * the bar with a wide gap after it; every other row is packed tight, those before focus
     * stacked upward above the bar. Piecewise linear, so scrolling is continuous.
     */
    fun rowY(r: Float): Float {
        val focused = anchorY + M.ITEM_FOCUSED_OFFSET * unit
        val next = focused + M.ITEM_AFTER_FOCUSED * unit
        val above = anchorY - M.ITEM_GAP_ABOVE * unit
        val step = M.ITEM_SPACING * unit
        return when {
            r >= 1f -> next + (r - 1f) * step
            r >= 0f -> lerp(focused, next, r)
            r > -1f -> lerp(above, focused, r + 1f)
            else -> above + (r + 1f) * step
        }
    }

    /** Where every row's title starts: a fixed offset from the column centre. */
    fun labelX(columnX: Float) = columnX + M.ITEM_LABEL_X * unit

    companion object {
        fun closeness(distance: Float) = max(0f, 1f - abs(distance))
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        /**
         * Where the options panel's first row goes: [measured] (under the focused bar row), pulled
         * up if the list would run off the bottom. A list too long for [top]..[bottom] at all
         * scrolls instead, keeping row [focus] central but never leaving a gap at either end.
         */
        fun optionsFirstY(measured: Float, count: Int, focus: Int, step: Float, top: Float, bottom: Float): Float {
            val rest = measured.coerceAtMost(bottom - (count - 0.5f) * step).coerceAtLeast(top)
            if (rest + (count - 1) * step <= bottom) return rest
            return ((top + bottom) / 2f - focus * step).coerceIn(bottom - (count - 1) * step, top)
        }
    }
}
