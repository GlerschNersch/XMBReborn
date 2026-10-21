package dev.xmbreborn.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import dev.xmbreborn.xmb.GlyphPainter
import dev.xmbreborn.xmb.IconTint
import dev.xmbreborn.xmb.ItemIcon
import dev.xmbreborn.xmb.MonthPalette
import dev.xmbreborn.xmb.OptionEntry
import dev.xmbreborn.xmb.Placement
import dev.xmbreborn.xmb.XmbLayout
import dev.xmbreborn.xmb.XmbMetrics as M
import dev.xmbreborn.xmb.XmbModel
import dev.xmbreborn.xmb.XmbMotion
import dev.xmbreborn.xmb.XmbOptions
import dev.xmbreborn.xmb.XmbPrefs
import java.util.Calendar
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp

/**
 * Draws the month gradient, the wave and the bar. The model is only touched on the GL thread:
 * other threads post changes with [post], which are drained at the start of each frame.
 */
class XmbRenderer : GLSurfaceView.Renderer {
    private val background = Background()
    private val wave = Wave()
    private val particles = Particles()
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
    /** First frame ever drawn, for the start-up fade (once per launch, not on every return home). */
    private var introNanos = 0L

    /** Snapshot of where things were drawn last frame, for touch hit-testing on the UI thread. */
    @Volatile var lastPlacements: List<Placement> = emptyList()
        private set
    /** Where the bar is this frame, for views laid over a column: its scroll (fractional column)
     *  and the column ids in order. */
    @Volatile var barScroll = 0f
        private set
    @Volatile var columnIds: List<String> = emptyList()
        private set
    @Volatile var lastLayout: XmbLayout = layout
        private set

    /** Height of the system bar over the top of the surface, so the clock stays below it. */
    var topInset = 0f

    /** Background colour: [XmbPrefs.ORIGINAL] follows the month, 0..11 pins one. Eases when changed. */
    var themeColor = XmbPrefs.ORIGINAL
    private val shown = FloatArray(15)
    private var shownValid = false
    /** Glyph icons lean toward the background's hue, like the XMB's environment-lit icons. */
    private val glyphTint = FloatArray(3)

    // Options panel: the list, whether it's open, and its two animations (everything else
    // dimming, then the panel easing in). Kept until both have faded after closing.
    private var options: XmbOptions? = null
    private var optionsOpen = false
    private var optionsAge = 0f
    private var dim = 0f
    private var panel = 0f

    /** For the UI thread: whether input should go to the options panel, and where its rows are. */
    @Volatile var optionsShowing = false
        private set
    @Volatile var optionRows = OptionRows(0f, 0f, 1f, 0)
        private set

    class OptionRows(val panelX: Float, val firstY: Float, val step: Float, val count: Int) {
        fun indexAt(y: Float): Int = Math.round((y - firstY) / step).takeIf { it in 0 until count } ?: -1
    }

    fun openOptions(o: XmbOptions) {
        options?.takeIf { optionsOpen }?.onCancel?.invoke()
        options = o
        optionsOpen = true
        optionsAge = 0f
        optionsShowing = true
        o.focused?.onFocus?.invoke()
    }

    /** Closes the panel; [cancelled] runs its undo (e.g. drops a colour preview). */
    fun closeOptions(cancelled: Boolean) {
        val o = options ?: return
        if (!optionsOpen) return
        optionsOpen = false
        optionsShowing = false
        if (cancelled) o.onCancel?.invoke()
    }

    fun moveOption(delta: Int): Boolean = focusOption { it.move(delta) }
    fun setOption(index: Int): Boolean = focusOption { it.setFocus(index) }

    private inline fun focusOption(change: (XmbOptions) -> Boolean): Boolean {
        val o = options?.takeIf { optionsOpen } ?: return false
        if (!change(o)) return false
        o.focused?.onFocus?.invoke()
        return true
    }

    /** Closes the panel on its focused row and returns it, for the caller to run on the UI thread. */
    fun chooseOption(): OptionEntry? {
        val e = options?.takeIf { optionsOpen }?.focused ?: return null
        closeOptions(cancelled = false)
        return e
    }

    fun post(change: (XmbRenderer) -> Unit) { pending.add(change) }

    /** Installs a freshly built bar (GL thread). Keeps the previous focus where ids still match. */
    fun setModel(newModel: XmbModel, newIcons: Map<String, ItemIcon>, newBackgrounds: Set<String> = emptySet()) {
        backgroundIds = newBackgrounds
        val old = model
        if (old != null) {
            old.focused.id.let { id -> newModel.categories.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(newModel::setCategory) }
            for (c in newModel.categories) {
                val prev = old.category(c.id) ?: continue
                // Same row if it's still there; otherwise (e.g. just hidden) whatever took its place.
                val same = prev.focusedItem?.id?.let { id -> c.items.indexOfFirst { it.id == id } } ?: -1
                c.setFocus(if (same >= 0) same else minOf(prev.focus, c.items.size - 1))
            }
        }
        model = newModel
        columnIds = newModel.categories.map { it.id }
        icons = newIcons
        textures.clear()
        motion = XmbMotion(newModel).also { it.snap() }
    }

    // A focused PS3 game's PIC1 background: requested once focus has rested on the game a moment,
    // then faded in over the wave, and out again when focus moves on, as on the PS3.
    private var backgroundIds: Set<String> = emptySet()
    private var gameBg: Tex? = null
    private var gameBgId: String? = null
    private var gameBgAlpha = 0f
    private var dwellId: String? = null
    private var dwell = 0f
    private var requestedBg: String? = null

    /** Asked (GL thread) for a game's background by item id; answer with [setGameBackground]. */
    var onWantBackground: ((String) -> Unit)? = null

    /** A loaded background (GL thread). Replaces the previous one. */
    fun setGameBackground(id: String, bitmap: android.graphics.Bitmap) {
        gameBg?.let { dev.xmbreborn.gl.Gl.deleteTexture(it.id) }
        gameBg = Tex(dev.xmbreborn.gl.Gl.texture(bitmap, false), bitmap.width, bitmap.height)
        gameBgId = id
        bitmap.recycle()
    }

    private fun drawGameBackground(model: XmbModel, dt: Float) {
        val focused = model.focused.focusedItem?.id
        if (focused != dwellId) { dwellId = focused; dwell = 0f } else dwell += dt
        if (focused != null && focused in backgroundIds && dwell >= M.GAME_BG_DWELL && requestedBg != focused) {
            requestedBg = focused
            onWantBackground?.invoke(focused)
        }
        val target = if (gameBg != null && gameBgId == focused && dim < 0.5f) 1f else 0f
        gameBgAlpha += (target - gameBgAlpha) * (1f - exp(-dt / M.GAME_BG_FADE))
        val tex = gameBg ?: return
        if (gameBgAlpha < 0.004f) return
        // Cover the screen, cropping whichever way the aspect doesn't fit.
        val s = maxOf(layout.width / tex.width, layout.height / tex.height)
        val w = tex.width * s
        val h = tex.height * s
        sprites.draw(tex.id, (layout.width - w) / 2, (layout.height - h) / 2, w, h, gameBgAlpha)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new GL context: the old background texture is gone with the old one.
        gameBg = null
        gameBgId = null
        requestedBg = null
        background.init()
        wave.init()
        particles.init()
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
        if (introNanos == 0L) introNanos = now
        val intro = (now - introNanos) / 1e9f

        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        val month = if (themeColor >= 0) themeColor else cal.get(Calendar.MONTH)
        MonthPalette.colors(month, hour, colors)
        val k = if (shownValid) 1f - exp(-dt / COLOR_TIME) else 1f
        for (i in colors.indices) shown[i] += (colors[i] - shown[i]) * k
        shownValid = true
        IconTint.of(floatArrayOf(shown[12], shown[13], shown[14]), glyphTint)
        val aspect = layout.width / layout.height
        background.draw(shown, aspect)
        val waveLight = 1f - 0.35f * MonthPalette.nightness(hour)
        wave.draw(time, aspect, waveLight)
        particles.step(dt, time, aspect)
        particles.draw(layout.width, layout.height, time, waveLight)

        val model = model ?: return
        val motion = motion ?: return
        motion.step(dt)
        stepOptions(dt)

        sprites.begin(layout.width, layout.height)
        drawGameBackground(model, dt)
        // Start-up: the scene rises out of black first, then the bar fades in over it.
        val introBar = drawIntroCover(intro)
        val items = layout.items(model, motion)
        val cats = layout.categories(model, motion)
        // While options are open everything but the focused row and column dims, as on the PS3.
        // In portrait the panel overlaps the rows' labels, so they go almost out of the way.
        val faded = (1f - (if (layout.portrait) M.OPTIONS_DIM_PORTRAIT else M.OPTIONS_DIM) * dim) * introBar
        val panelX = panelX()
        items.forEach { p ->
            val focusedRow = p.category == model.focus && p.item == model.focused.focus
            if (focusedRow && dim > 0.005f) drawFocusBand(p, panelX)
            drawItem(model, p, if (focusedRow) introBar else faded, if (focusedRow) dim else 0f, shift = !layout.portrait)
        }
        cats.forEach { drawCategory(model, it, if (it.category == model.focus) introBar else faded) }
        drawClock(cal, faded)
        drawOptions(panelX)
        sprites.end()
        lastPlacements = cats + items
        barScroll = motion.category
    }

    /** Date and time, right-aligned on a translucent bar off the top-right edge, as on the PS3. */
    private fun drawClock(cal: Calendar, fade: Float) {
        val u = layout.unit
        val text = textures.slotText("clock", clockFormat.format(cal.time), M.CLOCK_SIZE * u)
        val barH = M.CLOCK_BAR_HEIGHT * u
        val cy = maxOf(layout.height * if (layout.portrait) M.CLOCK_Y_PORTRAIT else M.CLOCK_Y_LANDSCAPE,
            topInset + barH / 2 + M.CLOCK_INSET_GAP * u)
        val barX = layout.width - M.CLOCK_BAR_WIDTH * u
        val solid = solid()
        // Premultiplied white quad: alpha alone darkens nothing, so tint by drawing a faint fill
        // plus brighter hairlines top and bottom, which is how the PS3 bar reads over any colour.
        sprites.draw(solid.id, barX, cy - barH / 2, layout.width - barX, barH, 0.10f * fade)
        sprites.draw(solid.id, barX, cy - barH / 2, layout.width - barX, maxOf(1f, u), 0.45f * fade)
        sprites.draw(solid.id, barX, cy + barH / 2 - maxOf(1f, u), layout.width - barX, maxOf(1f, u), 0.45f * fade)
        val x = layout.width - M.CLOCK_TEXT_RIGHT * u - text.width + TextureCache.padFor(M.CLOCK_SIZE * u)
        sprites.draw(text.id, x, cy - text.height / 2f, text.width.toFloat(), text.height.toFloat(), 0.95f * fade)
    }

    private val clockFormat = java.text.SimpleDateFormat(
        android.text.format.DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMjmm"),
        java.util.Locale.getDefault())

    private fun solid() = textures.icon("solid") { pixel(android.graphics.Color.WHITE) }
    private fun black() = textures.icon("black") { pixel(android.graphics.Color.BLACK) }
    private fun pixel(color: Int) = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /**
     * Our start-up (the PS3's cold boot is Sony's artwork and music, so this is our own): black
     * over the background and wave lifts first; returns how far the bar has faded in (0..1).
     */
    private fun drawIntroCover(t: Float): Float {
        if (t >= INTRO_BAR_END) return 1f
        sprites.draw(black().id, 0f, 0f, layout.width, layout.height, 1f - smooth(t / INTRO_SCENE))
        return smooth((t - INTRO_BAR_START) / (INTRO_BAR_END - INTRO_BAR_START))
    }

    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    private fun stepOptions(dt: Float) {
        if (options == null) return
        optionsAge += dt
        val dimGoal = if (optionsOpen) 1f else 0f
        dim += (dimGoal - dim) * (1f - exp(-dt / M.OPTIONS_DIM_TIME))
        // The panel waits for the dimming, then eases in; closing, it leaves as fast as the dim.
        val panelGoal = if (optionsOpen && optionsAge >= M.OPTIONS_DELAY) 1f else 0f
        val tau = if (optionsOpen) M.OPTIONS_TIME_CONSTANT else M.OPTIONS_DIM_TIME
        panel += (panelGoal - panel) * (1f - exp(-dt / tau))
        if (!optionsOpen && dim < 0.005f && panel < 0.005f) {
            options = null
            dim = 0f
            panel = 0f
        }
    }

    /** Left edge of the panel at rest: measured 880 of 1280, kept at least ~45% of the width. */
    private fun panelX() = maxOf(layout.width - (1280f - M.OPTIONS_PANEL_X) * layout.unit, layout.width * 0.45f)

    /** The darker band joining the focused row to the panel. */
    private fun drawFocusBand(p: Placement, panelX: Float) {
        val h = M.ITEM_ICON_FOCUSED * layout.unit
        val x0 = p.x + p.size / 2
        sprites.draw(black().id, x0, p.y - h, maxOf(0f, panelX - x0), h * 2, M.OPTIONS_BAND * dim)
    }

    private fun drawOptions(restX: Float) {
        val o = options ?: return
        if (panel < 0.005f) return
        val u = layout.unit
        val x = restX + (1f - panel) * M.OPTIONS_SLIDE * u
        val overlay = textures.icon("options-overlay") { overlayBitmap() }
        sprites.draw(overlay.id, x, 0f, M.OPTIONS_FADE_WIDTH * u, layout.height, M.OPTIONS_OVERLAY * panel)
        sprites.draw(solid().id, x, 0f, maxOf(1f, u), layout.height, 0.45f * panel)

        val step = M.OPTIONS_ROW * u
        val n = o.entries.size
        // Under the focused row as measured, pulled up if a long list would run off the bottom.
        val first = (layout.rowY(0f) + M.OPTIONS_BELOW_FOCUS * u)
            .coerceAtMost(layout.height - (n - 0.5f) * step - 24f * u)
            .coerceAtLeast(layout.anchorY + 14f * u)
        val size = M.OPTIONS_TEXT_SIZE * u
        o.entries.forEachIndexed { i, e ->
            val t = textures.text(e.label, size, maxWidth = layout.width - restX - (M.OPTIONS_TEXT_X + M.LABEL_EDGE_MARGIN) * u)
            val y = first + i * step
            val a = panel * if (i == o.focus) 1f else M.OPTIONS_UNFOCUSED
            sprites.draw(t.id, x + M.OPTIONS_TEXT_X * u - TextureCache.padFor(size), y - t.height / 2f,
                t.width.toFloat(), t.height.toFloat(), a)
        }
        optionRows = OptionRows(x, first, step, n)
    }

    /** White, opaque for the first stretch then falling linearly to clear (scaled at draw time). */
    private fun overlayBitmap(): android.graphics.Bitmap {
        val w = 64
        val flat = M.OPTIONS_FADE_FLAT / M.OPTIONS_FADE_WIDTH
        val px = IntArray(w) { i ->
            val f = i / (w - 1f)
            val a = if (f <= flat) 1f else 1f - (f - flat) / (1f - flat)
            android.graphics.Color.argb((a * 255).toInt(), 255, 255, 255)
        }
        return android.graphics.Bitmap.createBitmap(px, w, 1, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun drawCategory(model: XmbModel, p: Placement, fade: Float) {
        val cat = model.categories[p.category]
        val iconPx = (M.CATEGORY_ICON_FOCUSED * layout.unit).toInt().coerceAtLeast(16)
        val tex = textures.icon("glyph:${cat.glyph}:$iconPx") { GlyphPainter.render(cat.glyph, iconPx) }
        sprites.draw(tex.id, p.x - p.size / 2, p.y - p.size / 2, p.size, p.size, p.alpha * fade * IconTint.OPACITY, glyphTint)
        if (p.focus > 0.01f) {
            val label = textures.text(cat.title, M.CATEGORY_LABEL_SIZE * layout.unit)
            sprites.draw(label.id, p.x - label.width / 2f, p.y + p.size / 2 + M.CATEGORY_LABEL_GAP * layout.unit - label.height * 0.2f,
                label.width.toFloat(), label.height.toFloat(), p.focus * fade)
        }
    }

    /** [fade] dims the whole row; [lift] (0..1) is the focused row's options state: its label dims and, if [shift], moves right. */
    private fun drawItem(model: XmbModel, p: Placement, fade: Float, lift: Float, shift: Boolean) {
        val item = model.categories[p.category].items[p.item]
        val iconPx = (M.ITEM_ICON_FOCUSED * layout.unit).toInt().coerceAtLeast(16)
        val icon = icons[item.id]
        val tex = when (icon) {
            is ItemIcon.Bitmap -> textures.icon(item.id) { icon.bitmap.copy(icon.bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false) }
            is ItemIcon.Of -> textures.icon("glyph:${icon.glyph}:$iconPx") { GlyphPainter.render(icon.glyph, iconPx) }
            null -> null
        }
        // PS3 game icons (320x176) keep their shape, at the size the XMB draws them; their title
        // then starts just past the icon's right edge.
        val wide = icon is ItemIcon.Bitmap && icon.wide
        val iconW = if (wide) XmbLayout.lerp(M.GAME_ICON_WIDTH, M.GAME_ICON_WIDTH_FOCUSED, p.focus) * layout.unit else p.size
        val iconH = if (wide) iconW * M.GAME_ICON_ASPECT else p.size
        // Apps keep their own colours; our glyphs are lit, tinted and translucent like the XMB's.
        if (tex != null) {
            if (icon is ItemIcon.Of) sprites.draw(tex.id, p.x - p.size / 2, p.y - p.size / 2, p.size, p.size, p.alpha * fade * IconTint.OPACITY, glyphTint)
            else sprites.draw(tex.id, p.x - iconW / 2, p.y - iconH / 2, iconW, iconH, p.alpha * fade)
        }

        val titleSize = M.ITEM_LABEL_SIZE * layout.unit
        val labelX = if (wide) p.x + iconW / 2 + M.GAME_LABEL_GAP * layout.unit else layout.labelX(p.x)
        val textX = labelX - TextureCache.padFor(titleSize) + if (shift) M.OPTIONS_LABEL_SHIFT * layout.unit * lift else 0f
        // Long names stop short of the screen's edge with an ellipsis. Measured from the focused
        // column's resting place, not this frame's x, so scrolling doesn't re-render labels.
        val maxW = layout.width - layout.labelX(layout.anchorX) - M.LABEL_EDGE_MARGIN * layout.unit
        val title = textures.text(item.title, titleSize, maxWidth = maxW)
        val textAlpha = p.alpha * fade * (1f - M.OPTIONS_LABEL_DIM * lift)
        val sub = item.subtitle?.takeIf { p.focus > 0.01f }?.let { textures.text(it, M.ITEM_SUBTITLE_SIZE * layout.unit, maxWidth = maxW) }
        // Focused rows lift the title to make room for the subtitle under it.
        val titleY = if (sub != null) p.y - title.height * (0.5f + 0.35f * p.focus) else p.y - title.height / 2f
        sprites.draw(title.id, textX, titleY, title.width.toFloat(), title.height.toFloat(), textAlpha)
        if (sub != null) sprites.draw(sub.id, textX, titleY + title.height * 0.8f, sub.width.toFloat(), sub.height.toFloat(), textAlpha * p.focus * 0.85f)
    }

    private companion object {
        /** Time constant for the background easing to a newly chosen colour (ours). */
        const val COLOR_TIME = 0.25f
        // Start-up timings (ours): scene out of black over 1.4 s, bar in from 0.9 to 1.9 s.
        const val INTRO_SCENE = 1.4f
        const val INTRO_BAR_START = 0.9f
        const val INTRO_BAR_END = 1.9f
    }
}
