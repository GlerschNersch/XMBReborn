package dev.xmbreborn.render

import android.os.SystemClock
import dev.xmbreborn.xmb.MonthPalette
import java.util.Calendar
import kotlin.math.exp

/**
 * Everything behind the bar: the month gradient, the wave and the sparkles. The launcher draws it
 * under its icons, and [XmbWallpaper] draws it alone, so the system's own home animation (which a
 * third-party home can't replace) plays over the same scene.
 *
 * Time comes from the uptime clock, not from when this scene started, so the wave is at the same
 * point in both and carries on unbroken across a hand-off or a lost GL context.
 */
class Scene {
    private val background = Background()
    private val wave = Wave()
    private val particles = Particles()
    private val colors = FloatArray(15)

    /** The gradient as last drawn (eased toward the target colours); centre is [12..14]. */
    val shown = FloatArray(15)
    private var shownValid = false

    fun init() {
        background.init()
        wave.init()
        particles.init()
    }

    /**
     * Draws the scene for [cal]'s time of day; [themeColor] is a month index or negative to follow
     * the calendar. Returns the wave's brightness (dimmer at night) for anything drawn over it.
     */
    fun draw(cal: Calendar, themeColor: Int, dt: Float, width: Float, height: Float): Float {
        val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        val month = if (themeColor >= 0) themeColor else cal.get(Calendar.MONTH)
        MonthPalette.colors(month, hour, colors)
        val k = if (shownValid) 1f - exp(-dt / COLOR_TIME) else 1f
        for (i in colors.indices) shown[i] += (colors[i] - shown[i]) * k
        shownValid = true
        val aspect = width / height
        val time = now()
        background.draw(shown, aspect)
        val waveLight = 1f - 0.35f * MonthPalette.nightness(hour)
        wave.draw(time, aspect, waveLight)
        particles.step(dt, time, aspect)
        particles.draw(width, height, time, waveLight)
        return waveLight
    }

    companion object {
        /** How quickly the gradient follows a colour change (theme previews, day into night). */
        const val COLOR_TIME = 0.25f
        /** The clock wraps hourly so a float keeps millisecond precision however long the phone's been up. */
        private const val WRAP_NANOS = 3_600_000_000_000L

        fun now(): Float = (SystemClock.elapsedRealtimeNanos() % WRAP_NANOS) / 1e9f
    }
}
