package dev.xmbreborn.render

import android.opengl.GLES30
import dev.xmbreborn.gl.Gl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sparkles around the wave. Measured from the real XMB (docs/RE_NOTES.md): a few hundred
 * mostly 1-2 px dots that gather around and above the ribbon, drifting ~15 px/s with no overall
 * direction, plus a few large faint out-of-focus discs and an occasional iridescent tint.
 * Behaviour follows PARTICLES.mnu's shape (cone emission from the ribbon, some sent downward,
 * friction, Brownian jitter, a slight upward pull, ageing); the numbers are fitted to the
 * capture since the .mnu's units are the PS3's own. Simulated on the CPU, drawn as point sprites.
 */
class Particles {
    private val rnd = Random(7)
    private val x = FloatArray(COUNT)      // screen fraction, 0..1 across
    private val y = FloatArray(COUNT)      // screen fraction, 0..1 down
    private val vx = FloatArray(COUNT)     // in reference pixels per second (720-high screen)
    private val vy = FloatArray(COUNT)
    private val age = FloatArray(COUNT)
    private val life = FloatArray(COUNT)
    private val near = BooleanArray(COUNT) // large, faint and soft, as if out of focus
    private val tint = IntArray(COUNT)
    private val phase = FloatArray(COUNT)
    private val data = FloatArray(COUNT * STRIDE)
    private val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private var program = 0
    private var uScreen = 0
    private var uBrightness = 0
    private var vao = 0
    private var vbo = 0
    private var seeded = false

    fun init() {
        program = Gl.program(VS, FS)
        uScreen = GLES30.glGetUniformLocation(program, "uScreen")
        uBrightness = GLES30.glGetUniformLocation(program, "uBrightness")
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, null, GLES30.GL_DYNAMIC_DRAW)
        // pos.xy (px), size (px), alpha, softness, rgb
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, STRIDE * 4, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE * 4, 8)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, STRIDE * 4, 20)
        GLES30.glBindVertexArray(0)
    }

    /** Advances the simulation; [time] and [aspect] place new particles on the wave. */
    fun step(dt: Float, time: Float, aspect: Float) {
        if (!seeded) {
            // Start mid-life everywhere so the field is already there on the first frame.
            for (i in 0 until COUNT) { spawn(i, time, aspect); age[i] = rnd.nextFloat() * life[i] }
            seeded = true
        }
        val friction = exp(-FRICTION * dt)
        val kick = BROWNIAN * sqrt(dt)
        for (i in 0 until COUNT) {
            age[i] += dt
            if (age[i] >= life[i]) { spawn(i, time, aspect); continue }
            vx[i] = vx[i] * friction + gauss() * kick
            vy[i] = vy[i] * friction + gauss() * kick - LIFT * dt
            x[i] += vx[i] * dt / (REF * aspect)
            y[i] += vy[i] * dt / REF
        }
    }

    private fun spawn(i: Int, time: Float, aspect: Float) {
        // Anywhere along the ribbon (a little past both edges), within its thickness.
        val sx = -0.05f + rnd.nextFloat() * 1.1f
        x[i] = sx
        y[i] = WaveShape.screenY(sx * 2f - 1f, time, rnd.nextInt(2), aspect) + gauss() * 0.035f
        // Cone of +-52 deg about straight up (PARTICLES.mnu), 17% sent downward instead.
        val a = (rnd.nextFloat() * 2f - 1f) * CONE
        val down = rnd.nextFloat() < 0.174f
        val speed = SPEED_MIN + rnd.nextFloat() * SPEED_VAR
        vx[i] = sin(a) * speed
        vy[i] = cos(a) * speed * if (down) 1f else -1f
        age[i] = 0f
        life[i] = LIFE_MIN + rnd.nextFloat() * LIFE_VAR
        near[i] = rnd.nextFloat() < NEAR_FRACTION
        tint[i] = rnd.nextInt(100).let { if (it < 82) 0 else 1 + it % 3 }
        phase[i] = rnd.nextFloat() * 2f * PI.toFloat()
    }

    fun draw(width: Float, height: Float, time: Float, brightness: Float) {
        val unit = min(width, height) / REF
        var k = 0
        for (i in 0 until COUNT) {
            val t = age[i] / life[i]
            // Fade in quickly, out slowly, with a gentle twinkle.
            val env = min(1f, t * 8f) * min(1f, (1f - t) * 3f) * (0.75f + 0.25f * sin(time * 2.7f + phase[i]))
            val (size, alpha, soft) = if (near[i]) Triple(NEAR_SIZE * (0.6f + 0.4f * cos(phase[i])), 0.10f, 1f)
                else Triple(1.4f + 1.6f * (phase[i] / (2f * PI.toFloat())), 0.55f, 0f)
            data[k++] = x[i] * width
            data[k++] = y[i] * height
            data[k++] = max(1.5f, size * unit)
            data[k++] = alpha * env
            data[k++] = soft
            val c = TINTS[tint[i]]
            data[k++] = c[0]; data[k++] = c[1]; data[k++] = c[2]
        }
        buf.position(0); buf.put(data).position(0)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScreen, width, height)
        GLES30.glUniform1f(uBrightness, brightness)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, data.size * 4, buf)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, COUNT)
        GLES30.glBindVertexArray(0)
    }

    private fun gauss() = rnd.nextGaussian().toFloat()

    private companion object {
        const val COUNT = 260
        const val STRIDE = 8
        const val REF = 720f
        const val CONE = (51.87 * PI / 180).toFloat()
        // Fitted to the capture: median drift ~15 px/s once friction and jitter settle.
        const val SPEED_MIN = 6f
        const val SPEED_VAR = 18f
        const val FRICTION = 0.35f
        const val BROWNIAN = 9f
        const val LIFT = 0.8f           // px/s^2 upward ("gravity" -6.8e-5 in the .mnu)
        const val LIFE_MIN = 4f
        const val LIFE_VAR = 6f
        const val NEAR_FRACTION = 0.06f
        const val NEAR_SIZE = 18f
        // Warm white, then the iridescent pink, cyan and gold seen on a few.
        val TINTS = arrayOf(
            floatArrayOf(1f, 0.96f, 0.88f), floatArrayOf(1f, 0.62f, 0.82f),
            floatArrayOf(0.62f, 0.92f, 1f), floatArrayOf(1f, 0.86f, 0.45f),
        )

        const val VS = """#version 300 es
            layout(location = 0) in vec2 aPos;
            layout(location = 1) in vec3 aSizeAlphaSoft;
            layout(location = 2) in vec3 aColor;
            uniform vec2 uScreen;
            out float vAlpha;
            out float vSoft;
            out vec3 vColor;
            void main() {
                gl_Position = vec4(aPos.x / uScreen.x * 2.0 - 1.0, 1.0 - aPos.y / uScreen.y * 2.0, 0.0, 1.0);
                gl_PointSize = aSizeAlphaSoft.x;
                vAlpha = aSizeAlphaSoft.y;
                vSoft = aSizeAlphaSoft.z;
                vColor = aColor;
            }"""

        // Sharp dots are a tight glow; out-of-focus ones a flat disc with a soft rim.
        const val FS = """#version 300 es
            precision mediump float;
            in float vAlpha;
            in float vSoft;
            in vec3 vColor;
            uniform float uBrightness;
            out vec4 fragColor;
            void main() {
                float d = length(gl_PointCoord - 0.5) * 2.0;
                float dot = exp(-d * d * 3.0) * step(d, 1.0);
                float disc = smoothstep(1.0, 0.7, d) * (0.7 + 0.3 * d);
                float a = mix(dot, disc, vSoft) * vAlpha * uBrightness;
                fragColor = vec4(vColor * a, 1.0);
            }"""
    }
}

/**
 * The wave's centre line on the CPU, mirroring `surface()` in [Wave]'s vertex shader at the
 * middle of the sheet (where its width term is zero). Keep the two in step.
 */
object WaveShape {
    /** Screen y (fraction, 0 = top) of [layer]'s centre at NDC x [xNdc] and time [time]. */
    fun screenY(xNdc: Float, time: Float, layer: Int, aspect: Float): Float {
        val l = layer.toFloat()
        val t = time * (1f + 0.23f * l) + l * 11f
        val x = xNdc
        val yy = -0.08f + 0.05f * l +
            0.20f * sin(x * 1.6f + t * 0.28f) +
            0.08f * sin(x * 3.1f - t * 0.41f + 1.3f) +
            0.025f * sin(x * 5.7f + t * 0.8f)
        val ndcY = (yy - 0.05f) * aspect.coerceIn(0.6f, 1f)
        return (1f - ndcY) / 2f
    }
}
