package dev.xmbreborn.xmb

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

enum class Glyph { USER, SETTINGS, PHOTO, MUSIC, VIDEO, GAME, NETWORK, APPS, FOLDER }

/**
 * Our own category icons, drawn on a 100x100 grid (none of the XMB's artwork is used). Each
 * silhouette is embossed into a soft pillow and lit with the XMB's icon lighting
 * ([IconLighting]), so it reads like the PS3's glassy icons; the renderer then tints it toward
 * the background and draws it translucent, as measured.
 */
object GlyphPainter {
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

        val shade = IconLighting.shade(alpha, px, px)
        val lit = Bitmap.createBitmap(IntArray(px * px) { i ->
            val v = (shade[i].coerceIn(0f, 1f) * 255).toInt()
            Color.argb((alpha[i] * 255).toInt(), v, v, v)
        }, px, px, Bitmap.Config.ARGB_8888)

        // A faint drop shadow underneath (ICONS.mnu's is barely there).
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(50, 0, 0, 0)
            maskFilter = BlurMaskFilter(3f * s, BlurMaskFilter.Blur.NORMAL)
        }
        c.save(); c.translate(1.5f * s, 2.5f * s); c.scale(s, s); c.drawPath(path, shadow); c.restore()
        c.drawBitmap(lit, 0f, 0f, null)
        lit.recycle()
        return out
    }

    /** Builds a silhouette from solid parts and cut-outs, applied in order (no even-odd surprises). */
    private class Shape {
        val path = Path()
        fun add(build: Path.() -> Unit) { path.op(Path().apply(build), Path.Op.UNION) }
        fun cut(build: Path.() -> Unit) { path.op(Path().apply(build), Path.Op.DIFFERENCE) }
    }

    private fun shape(build: Shape.() -> Unit): Path = Shape().apply(build).path

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
    }
}
