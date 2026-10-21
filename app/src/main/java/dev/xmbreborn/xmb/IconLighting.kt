package dev.xmbreborn.xmb

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Lights a flat silhouette as if it were embossed, in the manner of the XMB's icons (which are
 * normal maps lit live; see ICONS.mnu in docs/RE_NOTES.md). The height field is the silhouette
 * blurred, so edges round off into a pillow; its gradient gives normals, lit by ICONS.mnu's two
 * directional lights with ambient and a tight specular. Pure maths so it can be unit-tested.
 *
 * Screen axes: x right, y down, z toward the viewer.
 */
object IconLighting {
    // ICONS.mnu: L1 rotZ 22.8 deg, rotX -32.4 deg (a key light from above-left, 57.6 deg up);
    // L2 rotZ 60 deg, rotX 135 deg (a rim light from behind, catching lower-right edges).
    private val L1 = toward(azimuth = -22.8, elevation = 90.0 - 32.4)
    private val L2 = toward(azimuth = 180.0 - 60.0, elevation = -45.0)
    private val H1 = normalize(doubleArrayOf(L1[0], L1[1], L1[2] + 1.0))

    // Weights chosen so a flat face comes out at white and the measured highlights /
    // shadows (top bevel brighter, lower bevel ~30% darker) appear; ambient's level from ICONS.mnu
    // (0.74/0.80/0.90, used as its grey) and the 100-power specular.
    private const val AMBIENT = 0.81 * 0.55
    private const val KEY = 0.65
    private const val RIM = 0.25
    private const val SPECULAR = 0.35
    private const val SPEC_POWER = 100.0

    /** Bevel radius as a fraction of the icon size. */
    private const val BEVEL = 0.045f

    /** Brightness 0..1+ per pixel for coverage [alpha] (row-major, w x h). */
    fun shade(alpha: FloatArray, w: Int, h: Int): FloatArray {
        val r = max(1, (minOf(w, h) * BEVEL).toInt())
        val height = blur(blur(alpha, w, h, r), w, h, r)
        // Steepness: a bevel rises ~1 over ~2r pixels; scale so its slope tilts normals ~55 deg.
        val k = 3.0 * r
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val gx = (height[y * w + minOf(x + 1, w - 1)] - height[y * w + max(x - 1, 0)]) * 0.5
            val gy = (height[minOf(y + 1, h - 1) * w + x] - height[max(y - 1, 0) * w + x]) * 0.5
            val n = normalize(doubleArrayOf(-gx * k, -gy * k, 1.0))
            val d1 = max(0.0, dot(n, L1))
            val d2 = max(0.0, dot(n, L2))
            val spec = max(0.0, dot(n, H1)).pow(SPEC_POWER)
            out[i] = (AMBIENT + KEY * d1 + RIM * d2 + SPECULAR * spec).toFloat()
        }
        return out
    }

    /** Horizontal-then-vertical box blur of radius [r]. */
    private fun blur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val n = 2 * r + 1f
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (d in -r..r) s += src[y * w + (x + d).coerceIn(0, w - 1)]
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f
            for (d in -r..r) s += tmp[(y + d).coerceIn(0, h - 1) * w + x]
            out[y * w + x] = s / n
        }
        return out
    }

    /**
     * Unit vector toward a light: [azimuth] degrees clockwise from straight up the screen
     * (negative = toward the left), raised [elevation] degrees out of the screen toward the viewer.
     */
    private fun toward(azimuth: Double, elevation: Double): DoubleArray {
        val az = azimuth * PI / 180
        val el = elevation * PI / 180
        return doubleArrayOf(sin(az) * cos(el), -cos(az) * cos(el), sin(el))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun normalize(v: DoubleArray): DoubleArray {
        val l = sqrt(dot(v, v)).takeIf { it > 0 } ?: 1.0
        return doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
    }
}

/**
 * The XMB's icons pick up the background's hue (ICONS.mnu reflects an environment texture at
 * 0.74) and are translucent even when focused. Fitted to a captured frame: over an October-day
 * background (115, 94, 42) the focused toolbox's flat face reads (210, 199, 150).
 */
object IconTint {
    /** Opacity of a focused glyph icon; unfocused ones also get the bar's usual 0.6. */
    const val OPACITY = 0.68f
    /** How far the icon's colour leans toward the background's hue. */
    private const val AMOUNT = 0.3f

    /** Tint (multiplier per channel) for a background colour [bg] (RGB 0..1). */
    fun of(bg: FloatArray, out: FloatArray = FloatArray(3)): FloatArray {
        val m = maxOf(bg[0], bg[1], bg[2], 1e-3f)
        for (c in 0 until 3) out[c] = 1f - AMOUNT * (1f - bg[c] / m)
        return out
    }
}
