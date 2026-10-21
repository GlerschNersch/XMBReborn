package dev.xmbreborn.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.xmbreborn.xmb.GlyphPainter
import dev.xmbreborn.xmb.ItemIcon
import dev.xmbreborn.xmb.MonthPalette
import dev.xmbreborn.xmb.Placement
import dev.xmbreborn.xmb.XmbLayout
import dev.xmbreborn.xmb.XmbMetrics as M
import dev.xmbreborn.xmb.XmbModel
import dev.xmbreborn.xmb.XmbMotion
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the month gradient, the wave and the bar. The model is only touched on the GL thread:
 * other threads post changes with [post], which are drained at the start of each frame.
 */
class XmbRenderer : GLSurfaceView.Renderer {
    private val background = Background()
    private val wave = Wave()
    private val sprites = Sprites()
    private val textures = TextureCache()
    private val pending = ConcurrentLinkedQueue<(XmbRenderer) -> Unit>()

    var model: XmbModel? = null
        private set
    private var motion: XmbMotion? = null
    private var icons: Map<String, ItemIcon> = emptyMap()
    private var layout = XmbLayout(1f, 1f)
    private val colors = FloatArray(15)
    private var lastNanos = 0L
    private var startNanos = 0L

    /** Snapshot of where things were drawn last frame, for touch hit-testing on the UI thread. */
    @Volatile var lastPlacements: List<Placement> = emptyList()
        private set
    @Volatile var lastLayout: XmbLayout = layout
        private set

    fun post(change: (XmbRenderer) -> Unit) { pending.add(change) }

    /** Installs a freshly built bar (GL thread). Keeps the previous focus where ids still match. */
    fun setModel(newModel: XmbModel, newIcons: Map<String, ItemIcon>) {
        val old = model
        if (old != null) {
            old.focused.id.let { id -> newModel.categories.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(newModel::setCategory) }
            for (c in newModel.categories) {
                val prev = old.category(c.id) ?: continue
                prev.focusedItem?.id?.let { id -> c.items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(c::setFocus) }
            }
        }
        model = newModel
        icons = newIcons
        textures.clear()
        motion = XmbMotion(newModel).also { it.snap() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        background.init()
        wave.init()
        sprites.init()
        textures.clear() // old ids died with the old context
        startNanos = SystemClock.elapsedRealtimeNanos()
        lastNanos = startNanos
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        layout = XmbLayout(width.toFloat(), height.toFloat())
        lastLayout = layout
    }

    override fun onDrawFrame(gl: GL10?) {
        while (true) (pending.poll() ?: break).invoke(this)
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = now
        val time = (now - startNanos) / 1e9f

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        MonthPalette.colors(cal.get(Calendar.MONTH), hour, colors)
        val aspect = layout.width / layout.height
        background.draw(colors, aspect)
        wave.draw(time, aspect, 1f - 0.35f * MonthPalette.nightness(hour))

        val model = model ?: return
        val motion = motion ?: return
        motion.step(dt)

        sprites.begin(layout.width, layout.height)
        val items = layout.items(model, motion)
        val cats = layout.categories(model, motion)
        items.forEach { drawItem(model, it) }
        cats.forEach { drawCategory(model, it) }
        sprites.end()
        lastPlacements = cats + items
    }

    private fun drawCategory(model: XmbModel, p: Placement) {
        val cat = model.categories[p.category]
        val iconPx = (M.CATEGORY_ICON_FOCUSED * layout.unit).toInt().coerceAtLeast(16)
        val tex = textures.icon("glyph:${cat.glyph}:$iconPx") { GlyphPainter.render(cat.glyph, iconPx) }
        sprites.draw(tex.id, p.x - p.size / 2, p.y - p.size / 2, p.size, p.size, p.alpha)
        if (p.focus > 0.01f) {
            val label = textures.text(cat.title, M.CATEGORY_LABEL_SIZE * layout.unit)
            sprites.draw(label.id, p.x - label.width / 2f, p.y + p.size / 2 + M.CATEGORY_LABEL_GAP * layout.unit - label.height * 0.2f,
                label.width.toFloat(), label.height.toFloat(), p.focus)
        }
    }

    private fun drawItem(model: XmbModel, p: Placement) {
        val item = model.categories[p.category].items[p.item]
        val iconPx = (M.ITEM_ICON_FOCUSED * layout.unit).toInt().coerceAtLeast(16)
        val tex = when (val icon = icons[item.id]) {
            is ItemIcon.Bitmap -> textures.icon(item.id) { icon.bitmap.copy(icon.bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false) }
            is ItemIcon.Of -> textures.icon("glyph:${icon.glyph}:$iconPx") { GlyphPainter.render(icon.glyph, iconPx) }
            null -> null
        }
        if (tex != null) sprites.draw(tex.id, p.x - p.size / 2, p.y - p.size / 2, p.size, p.size, p.alpha)

        val textX = p.x + p.size / 2 + M.ITEM_LABEL_GAP * layout.unit
        val title = textures.text(item.title, M.ITEM_LABEL_SIZE * layout.unit)
        val sub = item.subtitle?.takeIf { p.focus > 0.01f }?.let { textures.text(it, M.ITEM_SUBTITLE_SIZE * layout.unit) }
        // Focused rows lift the title to make room for the subtitle under it.
        val titleY = if (sub != null) p.y - title.height * (0.5f + 0.35f * p.focus) else p.y - title.height / 2f
        sprites.draw(title.id, textX, titleY, title.width.toFloat(), title.height.toFloat(), p.alpha)
        if (sub != null) sprites.draw(sub.id, textX, titleY + title.height * 0.8f, sub.width.toFloat(), sub.height.toFloat(), p.alpha * p.focus * 0.85f)
    }
}
