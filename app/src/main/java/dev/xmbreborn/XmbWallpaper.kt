package dev.xmbreborn

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.xmbreborn.render.Scene
import dev.xmbreborn.xmb.XmbPrefs
import java.util.Calendar
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The XMB's background - month gradient, wave and sparkles - as a live wallpaper. With gesture
 * navigation, swiping home from an app plays the system launcher's animation over the wallpaper
 * before XMB Reborn takes over (a third-party home can't draw that animation itself); with this
 * wallpaper set, it plays over the same scene instead of a different picture. It only draws while
 * it's visible, which with XMB Reborn as home is mostly during those transitions.
 */
class XmbWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = XmbEngine()

    inner class XmbEngine : Engine() {
        private var view: WallpaperGlView? = null
        private val renderer = SceneRenderer(XmbPrefs(this@XmbWallpaper))

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            view = WallpaperGlView(this@XmbWallpaper).apply {
                setEGLContextClientVersion(3)
                setEGLConfigChooser(8, 8, 8, 8, 0, 0)
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { holder.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val v = view ?: return
            if (visible) { renderer.refreshPrefs(); v.onResume() } else v.onPause()
        }

        override fun onDestroy() {
            view?.release()
            view = null
            super.onDestroy()
        }

        /**
         * A GLSurfaceView drawing into this engine's surface instead of a window's. Inner, because
         * GLSurfaceView asks for its holder from inside its own constructor.
         */
        inner class WallpaperGlView(context: Context) : GLSurfaceView(context) {
            override fun getHolder(): SurfaceHolder = surfaceHolder
            /** Ends the GL thread (what detaching from a window would do). */
            fun release() = super.onDetachedFromWindow()
        }
    }

    private class SceneRenderer(private val prefs: XmbPrefs) : GLSurfaceView.Renderer {
        private val scene = Scene()
        private var width = 1f
        private var height = 1f
        private var lastNanos = 0L
        @Volatile private var themeColor = prefs.color

        /** Theme Color may have changed in the launcher while the wallpaper was hidden. */
        fun refreshPrefs() { themeColor = prefs.color }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            scene.init()
            lastNanos = SystemClock.elapsedRealtimeNanos()
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES30.glViewport(0, 0, w, h)
            width = w.toFloat()
            height = h.toFloat()
        }

        override fun onDrawFrame(gl: GL10?) {
            val now = SystemClock.elapsedRealtimeNanos()
            val dt = ((now - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
            lastNanos = now
            scene.draw(Calendar.getInstance(), themeColor, dt, width, height)
        }
    }
}
