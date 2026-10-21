package dev.xmbreborn.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import dev.xmbreborn.gl.Gl
import kotlin.math.ceil

class Tex(val id: Int, val width: Int, val height: Int)

/**
 * GL textures for icons and labels, created lazily on the GL thread and kept until [clear].
 * Labels are white with a soft dark shadow, like XMB text over any month colour.
 */
class TextureCache {
    private val map = HashMap<String, Tex>()

    fun icon(key: String, make: () -> Bitmap): Tex = map.getOrPut("icon:$key") { upload(make(), mipmaps = true) }

    fun text(text: String, sizePx: Float, bold: Boolean = false): Tex =
        map.getOrPut("text:${sizePx.toInt()}:$bold:$text") { upload(renderText(text, sizePx, bold), mipmaps = false) }

    private val slots = HashMap<String, Pair<String, Tex>>()

    /** Text that changes over time (the clock): one texture per slot, replaced when the text changes. */
    fun slotText(slot: String, text: String, sizePx: Float): Tex {
        slots[slot]?.let { (old, tex) -> if (old == text) return tex else Gl.deleteTexture(tex.id) }
        return upload(renderText(text, sizePx, false), mipmaps = false).also { slots[slot] = text to it }
    }

    fun clear() {
        slots.values.forEach { Gl.deleteTexture(it.second.id) }
        slots.clear()
        map.values.forEach { Gl.deleteTexture(it.id) }
        map.clear()
    }

    private fun upload(bmp: Bitmap, mipmaps: Boolean): Tex {
        val tex = Tex(Gl.texture(bmp, mipmaps), bmp.width, bmp.height)
        bmp.recycle()
        return tex
    }

    companion object {
        /** Transparent margin around rendered text (room for its shadow), in pixels. */
        fun padFor(sizePx: Float) = ceil(sizePx * 0.3f)
    }

    private fun renderText(text: String, sizePx: Float, bold: Boolean): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            setShadowLayer(sizePx * 0.12f, 0f, sizePx * 0.06f, Color.argb(150, 0, 0, 0))
        }
        val pad = padFor(sizePx).toInt()
        val fm = paint.fontMetrics
        val w = ceil(paint.measureText(text)).toInt() + pad * 2
        val h = ceil(fm.descent - fm.ascent).toInt() + pad * 2
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, pad.toFloat(), pad - fm.ascent, paint)
        return bmp
    }
}
