package dev.xmbreborn.xmb

/**
 * Every size, gap and timing of the bar, in pixels of a 1280x720 reference screen.
 * Provisional values: they get replaced by measurements of the real XMB (see docs/RE_NOTES.md).
 */
object XmbMetrics {
    const val REF_SHORT_SIDE = 720f

    // Where the focused column and the bar sit, as fractions of the screen.
    const val ANCHOR_X_LANDSCAPE = 0.27f
    const val ANCHOR_Y_LANDSCAPE = 0.29f
    const val ANCHOR_X_PORTRAIT = 0.24f
    const val ANCHOR_Y_PORTRAIT = 0.20f

    // Horizontal bar.
    const val CATEGORY_SPACING = 150f
    const val CATEGORY_ICON = 84f
    const val CATEGORY_ICON_FOCUSED = 100f
    const val CATEGORY_ALPHA = 0.55f
    const val CATEGORY_LABEL_GAP = 12f
    const val CATEGORY_LABEL_SIZE = 20f

    // Vertical list of the focused column.
    const val ITEM_SPACING = 88f
    const val ITEM_GAP_BELOW = 132f   // bar centre -> first row below it
    const val ITEM_GAP_ABOVE = 96f    // bar centre -> last row scrolled above it
    const val ITEM_ICON = 64f
    const val ITEM_ICON_FOCUSED = 96f
    const val ITEM_ALPHA = 0.7f
    const val ITEM_LABEL_GAP = 22f
    const val ITEM_LABEL_SIZE = 22f
    const val ITEM_SUBTITLE_SIZE = 16f

    // Motion: exponential approach time constant (s). ~4 constants to settle.
    const val FOCUS_TIME_CONSTANT = 0.055f
}
