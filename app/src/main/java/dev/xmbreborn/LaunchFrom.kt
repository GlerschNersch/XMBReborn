package dev.xmbreborn

import android.app.ActivityOptions
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View

/**
 * How the system launcher opens an app: the window clip-reveals out of the tile that was tapped,
 * and the app gets that tile's screen rect as its source bounds. [tile] is in [view]'s coordinates.
 */
class LaunchFrom private constructor(val sourceBounds: Rect, val options: Bundle) {
    companion object {
        fun of(view: View, tile: RectF?): LaunchFrom? {
            if (tile == null || tile.isEmpty) return null
            val r = Rect().also(tile::roundOut)
            val options = ActivityOptions.makeClipRevealAnimation(view, r.left, r.top, r.width(), r.height()).toBundle()
            val loc = IntArray(2).also(view::getLocationOnScreen)
            r.offset(loc[0], loc[1])
            return LaunchFrom(r, options)
        }
    }
}
