package dev.xmbreborn.xmb

/**
 * Every size, gap and timing of the bar, in pixels of a 1280x720 reference screen.
 * Landscape values are measured from the real XMB (firmware 4.93 in RPCS3, 2026-10-20);
 * see docs/RE_NOTES.md "Measured layout and motion". Portrait anchors are our own.
 */
object XmbMetrics {
    const val REF_SHORT_SIDE = 720f

    // Where the focused column and the bar sit, as fractions of the screen.
    const val ANCHOR_X_LANDSCAPE = 0.295f   // focused column centre x = 378
    const val ANCHOR_Y_LANDSCAPE = 0.268f   // bar centre y = 193
    const val ANCHOR_X_PORTRAIT = 0.22f
    const val ANCHOR_Y_PORTRAIT = 0.20f

    // Horizontal bar.
    const val CATEGORY_SPACING = 135f
    const val CATEGORY_ICON = 56f
    const val CATEGORY_ICON_FOCUSED = 76f
    const val CATEGORY_ALPHA = 0.6f
    const val CATEGORY_LABEL_GAP = 2f       // label centre sits 41 below the bar centre
    const val CATEGORY_LABEL_SIZE = 15f

    // Vertical list of the focused column. Rows are packed tight except for a wide gap
    // around the focused row: bar -> focused 144, focused -> next 116, others 53-54 apart.
    const val ITEM_FOCUSED_OFFSET = 144f
    const val ITEM_AFTER_FOCUSED = 116f
    const val ITEM_SPACING = 53.5f
    const val ITEM_GAP_ABOVE = 113f         // bar centre -> nearest row scrolled above it
    const val ITEM_ICON = 38f
    const val ITEM_ICON_FOCUSED = 80f
    const val ITEM_ALPHA = 0.6f
    const val ITEM_LABEL_X = 78f            // titles start here from the column centre, every row
    const val ITEM_LABEL_SIZE = 21f
    const val ITEM_SUBTITLE_SIZE = 12f
    /** How fast a column's rows fade as the bar scrolls away from it (1/columns). */
    const val ROW_FADE_RATE = 2.5f

    // Clock, top right: right-aligned text on a translucent bar that runs off the right edge.
    const val CLOCK_SIZE = 18f
    const val CLOCK_BAR_WIDTH = 393f
    const val CLOCK_BAR_HEIGHT = 36f
    const val CLOCK_TEXT_RIGHT = 105f       // text's right edge, measured from the screen's right
    const val CLOCK_Y_LANDSCAPE = 70f / 720f
    const val CLOCK_Y_PORTRAIT = 0.05f
    const val CLOCK_INSET_GAP = 8f          // ours: space kept between the status bar and the clock bar

    // Motion: exponential approach, 24% of the remaining distance per 60 Hz frame
    // (measured: 0.24, 0.42, 0.56, 0.67, 0.75 ...), i.e. a 60 ms time constant.
    const val FOCUS_TIME_CONSTANT = 0.060f
}
