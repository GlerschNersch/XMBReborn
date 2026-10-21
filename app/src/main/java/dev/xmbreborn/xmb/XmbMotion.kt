package dev.xmbreborn.xmb

import kotlin.math.abs
import kotlin.math.exp

/**
 * Continuous positions that chase the model's discrete focus. The bar scrolls by whole
 * columns, so [category] is a fractional column index; [item] holds one fractional row index
 * per category. Exponential approach (no overshoot) matches the XMB's ease-out glide.
 */
class XmbMotion(private val model: XmbModel, private val timeConstant: Float = XmbMetrics.FOCUS_TIME_CONSTANT) {
    var category: Float = model.focus.toFloat()
        private set
    private val item = FloatArray(model.categories.size) { model.categories[it].focus.toFloat() }

    fun item(categoryIndex: Int): Float = item[categoryIndex]

    /** Advances toward the targets; returns true while anything is still moving. */
    fun step(dt: Float): Boolean {
        val k = 1f - exp(-dt / timeConstant)
        var moving = false
        category = approach(category, model.focus.toFloat(), k).also { if (it != model.focus.toFloat()) moving = true }
        for (i in item.indices) {
            val target = model.categories[i].focus.toFloat()
            item[i] = approach(item[i], target, k)
            if (item[i] != target) moving = true
        }
        return moving
    }

    /** Jumps straight to the targets (first frame, or when returning to the launcher). */
    fun snap() {
        category = model.focus.toFloat()
        for (i in item.indices) item[i] = model.categories[i].focus.toFloat()
    }

    private fun approach(current: Float, target: Float, k: Float): Float {
        val next = current + (target - current) * k
        return if (abs(target - next) < SETTLE) target else next
    }

    private companion object {
        const val SETTLE = 0.001f
    }
}
