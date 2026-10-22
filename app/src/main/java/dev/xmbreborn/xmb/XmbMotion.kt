package dev.xmbreborn.xmb

import kotlin.math.abs
import kotlin.math.exp

/**
 * Continuous positions that chase the model's discrete focus. The bar scrolls by whole
 * columns, so [category] is a fractional column index; [item] holds one fractional row index
 * per category. Exponential approach (no overshoot) matches the XMB's ease-out glide.
 *
 * Each category also has a fractional folder [depth]. Opening or closing a folder changes which
 * list the rows are, so the row scroll jumps to the new list's focus while the depth glides:
 * the bar slides a column left per open folder and the new list slides in beside it.
 */
class XmbMotion(private val model: XmbModel, private val timeConstant: Float = XmbMetrics.FOCUS_TIME_CONSTANT) {
    var category: Float = model.focus.toFloat()
        private set
    private val item = FloatArray(model.categories.size) { model.categories[it].focus.toFloat() }
    private val depth = FloatArray(model.categories.size) { model.categories[it].depth.toFloat() }
    private val shownDepth = IntArray(model.categories.size) { model.categories[it].depth }

    fun item(categoryIndex: Int): Float = item[categoryIndex]
    fun depth(categoryIndex: Int): Float = depth.getOrElse(categoryIndex) { 0f }

    /** Advances toward the targets; returns true while anything is still moving. */
    fun step(dt: Float): Boolean {
        val k = 1f - exp(-dt / timeConstant)
        var moving = false
        category = approach(category, model.focus.toFloat(), k).also { if (it != model.focus.toFloat()) moving = true }
        for (i in item.indices) {
            val c = model.categories[i]
            val target = c.focus.toFloat()
            // A different list now: start it at its own focus rather than scrolling to it.
            if (c.depth != shownDepth[i]) { shownDepth[i] = c.depth; item[i] = target }
            item[i] = approach(item[i], target, k)
            if (item[i] != target) moving = true
            depth[i] = approach(depth[i], c.depth.toFloat(), k)
            if (depth[i] != c.depth.toFloat()) moving = true
        }
        return moving
    }

    /** Jumps straight to the targets (first frame, or when returning to the launcher). */
    fun snap() {
        category = model.focus.toFloat()
        for (i in item.indices) {
            item[i] = model.categories[i].focus.toFloat()
            depth[i] = model.categories[i].depth.toFloat()
            shownDepth[i] = model.categories[i].depth
        }
    }

    private fun approach(current: Float, target: Float, k: Float): Float {
        val next = current + (target - current) * k
        return if (abs(target - next) < SETTLE) target else next
    }

    private companion object {
        const val SETTLE = 0.001f
    }
}
