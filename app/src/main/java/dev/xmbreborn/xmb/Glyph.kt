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
 * Our own category icons: white silhouettes drawn on a 100x100 grid with a soft drop shadow,
 * in the spirit of the XMB's silver icons (none of its artwork is used).
 */
object GlyphPainter {
    fun render(glyph: Glyph, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = px / 100f
        val path = path(glyph)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 0, 0, 0)
            maskFilter = BlurMaskFilter(3f * s, BlurMaskFilter.Blur.NORMAL)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        c.save(); c.translate(1.5f * s, 2.5f * s); c.scale(s, s); c.drawPath(path, shadow); c.restore()
        c.save(); c.scale(s, s); c.drawPath(path, fill); c.restore()
        return bmp
    }

    private fun path(g: Glyph): Path = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        when (g) {
            Glyph.USER -> {
                addCircle(50f, 32f, 16f, Path.Direction.CW)
                addRoundRect(RectF(22f, 54f, 78f, 86f), 26f, 26f, Path.Direction.CW)
            }
            Glyph.SETTINGS -> {
                val teeth = 8
                for (i in 0 until teeth * 2) {
                    val a = Math.PI * i / teeth
                    val r = if (i % 2 == 0) 40f else 31f
                    val x = 50f + r * cos(a).toFloat()
                    val y = 50f + r * sin(a).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
                addCircle(50f, 50f, 13f, Path.Direction.CW)
            }
            Glyph.PHOTO -> {
                addRoundRect(RectF(12f, 22f, 88f, 78f), 6f, 6f, Path.Direction.CW)
                addRoundRect(RectF(19f, 29f, 81f, 71f), 3f, 3f, Path.Direction.CW)
                moveTo(22f, 68f); lineTo(42f, 44f); lineTo(55f, 58f); lineTo(64f, 50f); lineTo(78f, 68f); close()
                addCircle(66f, 38f, 5f, Path.Direction.CW)
            }
            Glyph.MUSIC -> {
                addOval(RectF(18f, 64f, 42f, 82f), Path.Direction.CW)
                addOval(RectF(56f, 56f, 80f, 74f), Path.Direction.CW)
                addRect(RectF(36f, 22f, 42f, 72f), Path.Direction.CW)
                addRect(RectF(74f, 14f, 80f, 64f), Path.Direction.CW)
                moveTo(36f, 22f); lineTo(80f, 14f); lineTo(80f, 26f); lineTo(36f, 34f); close()
            }
            Glyph.VIDEO -> {
                addRoundRect(RectF(10f, 24f, 90f, 76f), 5f, 5f, Path.Direction.CW)
                for (i in 0 until 6) {
                    val x = 15f + i * 13f
                    addRect(RectF(x, 28f, x + 6f, 34f), Path.Direction.CW)
                    addRect(RectF(x, 66f, x + 6f, 72f), Path.Direction.CW)
                }
                addRect(RectF(16f, 38f, 84f, 62f), Path.Direction.CW)
            }
            Glyph.GAME -> {
                addRoundRect(RectF(10f, 32f, 90f, 72f), 20f, 20f, Path.Direction.CW)
                addRect(RectF(22f, 48f, 40f, 54f), Path.Direction.CW)
                addRect(RectF(28f, 42f, 34f, 60f), Path.Direction.CW)
                addCircle(66f, 45f, 4.5f, Path.Direction.CW)
                addCircle(76f, 54f, 4.5f, Path.Direction.CW)
                addCircle(66f, 63f, 4.5f, Path.Direction.CW)
                addCircle(56f, 54f, 4.5f, Path.Direction.CW)
            }
            Glyph.NETWORK -> {
                addCircle(50f, 50f, 38f, Path.Direction.CW)
                addCircle(50f, 50f, 33f, Path.Direction.CW)
                addOval(RectF(36f, 12f, 64f, 88f), Path.Direction.CW)
                addOval(RectF(40f, 17f, 60f, 83f), Path.Direction.CW)
                addRect(RectF(14f, 47f, 86f, 53f), Path.Direction.CW)
                addRect(RectF(47f, 12f, 53f, 88f), Path.Direction.CW)
            }
            Glyph.APPS -> {
                for (r in 0 until 3) for (col in 0 until 3) {
                    val x = 18f + col * 24f
                    val y = 18f + r * 24f
                    addRoundRect(RectF(x, y, x + 16f, y + 16f), 4f, 4f, Path.Direction.CW)
                }
            }
            Glyph.FOLDER -> {
                moveTo(12f, 28f); lineTo(40f, 28f); lineTo(47f, 36f); lineTo(88f, 36f)
                lineTo(88f, 78f); lineTo(12f, 78f); close()
            }
        }
    }
}
