package dev.xmbreborn.xmb

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.cos
import kotlin.math.sin

enum class Glyph {
    USER, SETTINGS, PHOTO, MUSIC, VIDEO, GAME, NETWORK, APPS, FOLDER,
    // Settings rows, one per kind of setting, as the PS3 gives each its own icon.
    PALETTE, SPEAKER, HOME, DEVICE, TEXT, CLOCK, WIDGETS, HIDDEN, BACKUP, WIFI, BLUETOOTH,
    BRIGHTNESS, BATTERY, INFO,
    // Users > Turn Off System.
    POWER,
}

/**
 * Our own category icons, drawn on a 100x100 grid (none of the XMB's artwork is used). Each
 * silhouette is embossed into a soft pillow and lit with the XMB's icon lighting
 * ([IconLighting]), so it reads like the PS3's glassy icons; the renderer then tints it toward
 * the background and draws it translucent, as measured.
 */
object GlyphPainter {
    /** How much of the square our glyphs' silhouettes span (most reach about 12..88 on the grid). */
    private const val GLYPH_BOX = 0.74f

    fun render(glyph: Glyph, px: Int): Bitmap {
        val s = px / 100f
        val path = path(glyph)

        // Coverage of the silhouette, 0..1 per pixel.
        val mask = Bitmap.createBitmap(px, px, Bitmap.Config.ALPHA_8)
        Canvas(mask).apply { scale(s, s); drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG)) }
        val bytes = java.nio.ByteBuffer.allocate(mask.rowBytes * px)
        mask.copyPixelsToBuffer(bytes)
        val alpha = FloatArray(px * px) { i -> (bytes.get((i / px) * mask.rowBytes + i % px).toInt() and 0xff) / 255f }
        mask.recycle()

        val lit = light(alpha, px)

        // A faint drop shadow underneath (ICONS.mnu's is barely there).
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.save(); c.translate(1.5f * s, 2.5f * s); c.scale(s, s); c.drawPath(path, shadowPaint(s)); c.restore()
        c.drawBitmap(lit, 0f, 0f, null)
        lit.recycle()
        return out
    }

    /**
     * An app's monochrome icon layer (Android 13+ themed icons) lit like our glyphs, so apps can
     * match the XMB's own icons; null when the app has no monochrome layer.
     */
    fun renderMonochrome(icon: Drawable, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val mono = (icon as? AdaptiveIconDrawable)?.monochrome ?: return null
        // Monochrome art sits small in the middle of its 108 dp layer, so find where it is and
        // scale it to fill the box our own glyphs use.
        // (Probed at the usual adaptive-icon size, the layer 1.5x the icon, so thin strokes show.)
        val probe = RectF(-px / 4f, -px / 4f, px * 1.25f, px * 1.25f)
        val box = coverage(mono, px, probe).bounds() ?: return null
        val scale = GLYPH_BOX * px / maxOf(box.width(), box.height())
        val layer = RectF(
            px / 2f + (probe.left - box.centerX()) * scale, px / 2f + (probe.top - box.centerY()) * scale,
            px / 2f + (probe.right - box.centerX()) * scale, px / 2f + (probe.bottom - box.centerY()) * scale,
        )
        val (mask, alpha) = coverage(mono, px, layer)
        val lit = light(alpha, px)

        val s = px / 100f
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(mask, 1.5f * s, 2.5f * s, shadowPaint(s))
        c.drawBitmap(lit, 0f, 0f, null)
        mask.recycle(); lit.recycle()
        return out
    }

    /** [d] drawn over [layer] into a [px] square: the mask and its coverage (0..1 per pixel). */
    private fun coverage(d: Drawable, px: Int, layer: RectF): Pair<Bitmap, FloatArray> {
        val mask = Bitmap.createBitmap(px, px, Bitmap.Config.ALPHA_8)
        val r = android.graphics.Rect().also(layer::round)
        d.setBounds(r)
        d.draw(Canvas(mask))
        val bytes = java.nio.ByteBuffer.allocate(mask.rowBytes * px)
        mask.copyPixelsToBuffer(bytes)
        return mask to FloatArray(px * px) { i -> (bytes.get((i / px) * mask.rowBytes + i % px).toInt() and 0xff) / 255f }
    }

    /** The box around a mask's visible pixels, or null if it's blank. */
    private fun Pair<Bitmap, FloatArray>.bounds(): RectF? {
        val (mask, alpha) = this
        val px = mask.width
        mask.recycle()
        var l = px; var t = px; var r = -1; var b = -1
        for (i in alpha.indices) if (alpha[i] > 0.2f) {
            val x = i % px; val y = i / px
            if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
        }
        return if (r < l) null else RectF(l.toFloat(), t.toFloat(), r + 1f, b + 1f)
    }

    /** Coverage [alpha] (0..1 per pixel) embossed and lit as a grey, glassy pillow. */
    private fun light(alpha: FloatArray, px: Int): Bitmap {
        val shade = IconLighting.shade(alpha, px, px)
        return Bitmap.createBitmap(IntArray(px * px) { i ->
            val v = (shade[i].coerceIn(0f, 1f) * 255).toInt()
            Color.argb((alpha[i] * 255).toInt(), v, v, v)
        }, px, px, Bitmap.Config.ARGB_8888)
    }

    private fun shadowPaint(s: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0)
        maskFilter = BlurMaskFilter(3f * s, BlurMaskFilter.Blur.NORMAL)
    }

    /** Builds a silhouette from solid parts and cut-outs, applied in order (no even-odd surprises). */
    private class Shape {
        val path = Path()
        fun add(build: Path.() -> Unit) { path.op(Path().apply(build), Path.Op.UNION) }
        fun cut(build: Path.() -> Unit) { path.op(Path().apply(build), Path.Op.DIFFERENCE) }
    }

    private fun shape(build: Shape.() -> Unit): Path = Shape().apply(build).path

    /** A straight bar [w] wide from (x1, y1) to (x2, y2), square-ended. */
    private fun Path.bar(x1: Float, y1: Float, x2: Float, y2: Float, w: Float) {
        val len = kotlin.math.hypot(x2 - x1, y2 - y1)
        val nx = -(y2 - y1) / len * w / 2
        val ny = (x2 - x1) / len * w / 2
        moveTo(x1 + nx, y1 + ny); lineTo(x2 + nx, y2 + ny); lineTo(x2 - nx, y2 - ny); lineTo(x1 - nx, y1 - ny); close()
    }

    /** A band of a ring around (cx, cy), between radii [inner] and [outer], over an angle (degrees). */
    private fun Path.ring(cx: Float, cy: Float, outer: Float, inner: Float, start: Float, sweep: Float) {
        arcTo(RectF(cx - outer, cy - outer, cx + outer, cy + outer), start, sweep, true)
        arcTo(RectF(cx - inner, cy - inner, cx + inner, cy + inner), start + sweep, -sweep)
        close()
    }

    private fun path(g: Glyph): Path = when (g) {
        Glyph.USER -> shape {
            add { addCircle(50f, 32f, 16f, Path.Direction.CW) }
            add { addRoundRect(RectF(22f, 54f, 78f, 86f), 26f, 26f, Path.Direction.CW) }
        }
        Glyph.SETTINGS -> shape {
            add {
                val teeth = 8
                for (i in 0 until teeth * 2) {
                    val a = Math.PI * i / teeth
                    val r = if (i % 2 == 0) 40f else 31f
                    val x = 50f + r * cos(a).toFloat()
                    val y = 50f + r * sin(a).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            cut { addCircle(50f, 50f, 13f, Path.Direction.CW) }
        }
        Glyph.PHOTO -> shape {
            add { addRoundRect(RectF(12f, 22f, 88f, 78f), 6f, 6f, Path.Direction.CW) }
            cut { addRoundRect(RectF(19f, 29f, 81f, 71f), 3f, 3f, Path.Direction.CW) }
            add { moveTo(22f, 68f); lineTo(42f, 44f); lineTo(55f, 58f); lineTo(64f, 50f); lineTo(78f, 68f); close() }
            add { addCircle(66f, 38f, 5f, Path.Direction.CW) }
        }
        Glyph.MUSIC -> shape {
            add { addOval(RectF(18f, 64f, 42f, 82f), Path.Direction.CW) }
            add { addOval(RectF(56f, 56f, 80f, 74f), Path.Direction.CW) }
            add { addRect(RectF(36f, 22f, 42f, 72f), Path.Direction.CW) }
            add { addRect(RectF(74f, 14f, 80f, 64f), Path.Direction.CW) }
            add { moveTo(36f, 22f); lineTo(80f, 14f); lineTo(80f, 26f); lineTo(36f, 34f); close() }
        }
        Glyph.VIDEO -> shape {
            add { addRoundRect(RectF(10f, 24f, 90f, 76f), 5f, 5f, Path.Direction.CW) }
            for (i in 0 until 6) {
                val x = 15f + i * 13f
                cut { addRect(RectF(x, 28f, x + 6f, 34f), Path.Direction.CW) }
                cut { addRect(RectF(x, 66f, x + 6f, 72f), Path.Direction.CW) }
            }
            cut { addRect(RectF(16f, 38f, 84f, 62f), Path.Direction.CW) }
        }
        Glyph.GAME -> shape {
            add { addRoundRect(RectF(10f, 32f, 90f, 72f), 20f, 20f, Path.Direction.CW) }
            cut { addRect(RectF(22f, 48f, 40f, 54f), Path.Direction.CW) }
            cut { addRect(RectF(28f, 42f, 34f, 60f), Path.Direction.CW) }
            for ((x, y) in listOf(66f to 45f, 76f to 54f, 66f to 63f, 56f to 54f)) {
                cut { addCircle(x, y, 4.5f, Path.Direction.CW) }
            }
        }
        Glyph.NETWORK -> shape {
            add { addCircle(50f, 50f, 38f, Path.Direction.CW) }
            cut { addCircle(50f, 50f, 33f, Path.Direction.CW) }
            add { addOval(RectF(36f, 12f, 64f, 88f), Path.Direction.CW) }
            cut { addOval(RectF(41f, 17f, 59f, 83f), Path.Direction.CW) }
            add { addRect(RectF(14f, 47f, 86f, 53f), Path.Direction.CW) }
            add { addRect(RectF(47f, 12f, 53f, 88f), Path.Direction.CW) }
        }
        Glyph.APPS -> shape {
            for (r in 0 until 3) for (col in 0 until 3) {
                val x = 18f + col * 24f
                val y = 18f + r * 24f
                add { addRoundRect(RectF(x, y, x + 16f, y + 16f), 4f, 4f, Path.Direction.CW) }
            }
        }
        Glyph.FOLDER -> shape {
            add {
                moveTo(12f, 28f); lineTo(40f, 28f); lineTo(47f, 36f); lineTo(88f, 36f)
                lineTo(88f, 78f); lineTo(12f, 78f); close()
            }
        }
        Glyph.PALETTE -> shape {
            add { addOval(RectF(12f, 18f, 88f, 82f), Path.Direction.CW) }
            cut { addCircle(76f, 66f, 11f, Path.Direction.CW) }   // the thumb notch
            for ((x, y) in listOf(32f to 44f, 46f to 30f, 64f to 32f, 34f to 62f)) {
                cut { addCircle(x, y, 6.5f, Path.Direction.CW) }   // paint wells
            }
        }
        Glyph.SPEAKER -> shape {
            add { addRect(RectF(14f, 40f, 30f, 60f), Path.Direction.CW) }
            add { moveTo(28f, 40f); lineTo(50f, 22f); lineTo(50f, 78f); lineTo(28f, 60f); close() }
            add { ring(50f, 50f, 22f, 16f, -45f, 90f) }
            add { ring(50f, 50f, 36f, 30f, -48f, 96f) }
        }
        Glyph.HOME -> shape {
            add {
                moveTo(50f, 14f); lineTo(90f, 50f); lineTo(79f, 50f); lineTo(79f, 86f)
                lineTo(21f, 86f); lineTo(21f, 50f); lineTo(10f, 50f); close()
            }
            cut { addRect(RectF(42f, 60f, 58f, 86f), Path.Direction.CW) }
        }
        Glyph.DEVICE -> shape {
            add { addRoundRect(RectF(30f, 10f, 70f, 90f), 9f, 9f, Path.Direction.CW) }
            cut { addRoundRect(RectF(35f, 19f, 65f, 76f), 2f, 2f, Path.Direction.CW) }
            cut { addCircle(50f, 83f, 3.5f, Path.Direction.CW) }
        }
        Glyph.TEXT -> shape {
            add {
                moveTo(44f, 14f); lineTo(56f, 14f); lineTo(86f, 86f); lineTo(72f, 86f); lineTo(64f, 66f)
                lineTo(36f, 66f); lineTo(28f, 86f); lineTo(14f, 86f); close()
            }
            cut { moveTo(50f, 34f); lineTo(59f, 55f); lineTo(41f, 55f); close() }
        }
        Glyph.CLOCK -> shape {
            add { addCircle(50f, 50f, 38f, Path.Direction.CW) }
            cut { addCircle(50f, 50f, 32f, Path.Direction.CW) }
            add { bar(50f, 50f, 50f, 26f, 6f) }
            add { bar(50f, 50f, 68f, 58f, 6f) }
            add { addCircle(50f, 50f, 5f, Path.Direction.CW) }
        }
        Glyph.WIDGETS -> shape {
            add { addRoundRect(RectF(14f, 14f, 46f, 46f), 6f, 6f, Path.Direction.CW) }
            add { addRoundRect(RectF(54f, 14f, 86f, 46f), 6f, 6f, Path.Direction.CW) }
            add { addRoundRect(RectF(14f, 54f, 86f, 86f), 6f, 6f, Path.Direction.CW) }
        }
        Glyph.HIDDEN -> shape {
            add { moveTo(8f, 50f); quadTo(50f, 4f, 92f, 50f); quadTo(50f, 96f, 8f, 50f); close() }
            cut { moveTo(18f, 50f); quadTo(50f, 18f, 82f, 50f); quadTo(50f, 82f, 18f, 50f); close() }
            add { addCircle(50f, 50f, 11f, Path.Direction.CW) }
            cut { bar(16f, 88f, 84f, 12f, 14f) }   // a gap either side of the slash
            add { bar(18f, 86f, 82f, 14f, 6f) }
        }
        Glyph.BACKUP -> shape {
            add {
                moveTo(12f, 56f); lineTo(22f, 56f); lineTo(22f, 76f); lineTo(78f, 76f); lineTo(78f, 56f)
                lineTo(88f, 56f); lineTo(88f, 86f); lineTo(12f, 86f); close()
            }
            add {
                moveTo(50f, 10f); lineTo(73f, 36f); lineTo(57f, 36f); lineTo(57f, 66f)
                lineTo(43f, 66f); lineTo(43f, 36f); lineTo(27f, 36f); close()
            }
        }
        Glyph.WIFI -> shape {
            add { ring(50f, 80f, 62f, 52f, -135f, 90f) }
            add { ring(50f, 80f, 44f, 34f, -135f, 90f) }
            add { ring(50f, 80f, 26f, 16f, -135f, 90f) }
            add { addCircle(50f, 78f, 6f, Path.Direction.CW) }
        }
        Glyph.BLUETOOTH -> shape {
            add {
                moveTo(44f, 10f); lineTo(52f, 10f); lineTo(76f, 32f); lineTo(58f, 50f); lineTo(76f, 68f)
                lineTo(52f, 90f); lineTo(44f, 90f); close()
            }
            cut { moveTo(52f, 23f); lineTo(64f, 32f); lineTo(52f, 42f); close() }
            cut { moveTo(52f, 58f); lineTo(64f, 68f); lineTo(52f, 77f); close() }
            add { bar(24f, 31f, 52f, 54f, 7f) }
            add { bar(24f, 69f, 52f, 46f, 7f) }
        }
        Glyph.BRIGHTNESS -> shape {
            add { addCircle(50f, 50f, 15f, Path.Direction.CW) }
            for (i in 0 until 8) {
                val a = Math.PI * i / 4
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                add { bar(50f + 23f * c, 50f + 23f * s, 50f + 37f * c, 50f + 37f * s, 7f) }
            }
        }
        Glyph.BATTERY -> shape {
            add { addRoundRect(RectF(12f, 30f, 80f, 70f), 7f, 7f, Path.Direction.CW) }
            cut { addRoundRect(RectF(18f, 36f, 74f, 64f), 3f, 3f, Path.Direction.CW) }
            add { addRoundRect(RectF(80f, 42f, 89f, 58f), 2f, 2f, Path.Direction.CW) }
            add { addRect(RectF(23f, 41f, 50f, 59f), Path.Direction.CW) }
        }
        Glyph.INFO -> shape {
            add { addCircle(50f, 50f, 38f, Path.Direction.CW) }
            cut { addCircle(50f, 50f, 32f, Path.Direction.CW) }
            add { addCircle(50f, 30f, 5.5f, Path.Direction.CW) }
            add { addRoundRect(RectF(45f, 42f, 55f, 74f), 2f, 2f, Path.Direction.CW) }
        }
        // The power symbol: a ring open at the top, with a bar through the gap.
        Glyph.POWER -> shape {
            add { ring(50f, 54f, 34f, 25f, -60f, 300f) }
            add { addRoundRect(RectF(45f, 12f, 55f, 54f), 5f, 5f, Path.Direction.CW) }
        }
    }
}
