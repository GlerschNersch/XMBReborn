package dev.xmbreborn.render

import android.opengl.GLES30
import dev.xmbreborn.gl.Gl

/**
 * The XMB "wave": a wide, slowly folding translucent sheet. Like the PS3's (a Fresnel-shaded
 * ribbon, see LINE1.mnu), it is nearly invisible face-on and glows where it turns edge-on to
 * the viewer, so the folds read as bright lines. The sheet is a static grid; all motion is
 * in the vertex shader. Drawn additively over the background.
 */
class Wave {
    private var program = 0
    private var uTime = 0
    private var uAspect = 0
    private var uBrightness = 0
    private var uLayer = 0
    private var vao = 0
    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0

    fun init() {
        program = Gl.program(VS, FS)
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
        uBrightness = GLES30.glGetUniformLocation(program, "uBrightness")
        uLayer = GLES30.glGetUniformLocation(program, "uLayer")

        // Grid over (u across the screen, v across the sheet's width), both 0..1.
        val verts = FloatArray(COLS * ROWS * 2)
        var k = 0
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            verts[k++] = c / (COLS - 1f)
            verts[k++] = r / (ROWS - 1f)
        }
        val idx = ShortArray((COLS - 1) * (ROWS - 1) * 6)
        k = 0
        for (r in 0 until ROWS - 1) for (c in 0 until COLS - 1) {
            val a = (r * COLS + c).toShort()
            val b = (a + 1).toShort()
            val d = ((r + 1) * COLS + c).toShort()
            val e = (d + 1).toShort()
            idx[k++] = a; idx[k++] = d; idx[k++] = b
            idx[k++] = b; idx[k++] = d; idx[k++] = e
        }
        indexCount = idx.size

        val ids = IntArray(2)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(2, ids, 0); vbo = ids[0]; ibo = ids[1]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, Gl.floats(verts), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        val ib = java.nio.ByteBuffer.allocateDirect(idx.size * 2).order(java.nio.ByteOrder.nativeOrder())
            .asShortBuffer().apply { put(idx).position(0) }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    fun draw(time: Float, aspect: Float, brightness: Float) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uTime, time)
        GLES30.glUniform1f(uAspect, aspect)
        GLES30.glUniform1f(uBrightness, brightness)
        GLES30.glBindVertexArray(vao)
        // Two sheets with different phases, like the PS3's layered ribbon.
        for (layer in 0 until 2) {
            GLES30.glUniform1f(uLayer, layer.toFloat())
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        }
        GLES30.glBindVertexArray(0)
    }

    private companion object {
        const val COLS = 160
        const val ROWS = 24

        // Sheet surface P(u, v, t) in a camera space where x is across the screen, y is up and
        // z is depth. Normals come from analytic-ish finite differences of the same function.
        const val VS = """#version 300 es
            layout(location = 0) in vec2 aGrid;
            uniform float uTime;
            uniform float uAspect;
            uniform float uLayer;
            out float vGlow;
            out float vEdge;
            out float vLine;

            vec3 surface(vec2 g) {
                float x = mix(-1.3, 1.3, g.x);
                float t = uTime * (1.0 + 0.23 * uLayer) + uLayer * 11.0;
                // Sheet width runs into the screen and twists along x.
                float w = (g.y - 0.5) * (0.55 - 0.12 * uLayer);
                float twist = 1.25 * sin(x * 1.05 + t * 0.19) + 0.5 * sin(x * 2.1 - t * 0.15 + uLayer);
                float y = -0.08 + 0.05 * uLayer
                    + 0.20 * sin(x * 1.6 + t * 0.28)
                    + 0.08 * sin(x * 3.1 - t * 0.41 + 1.3)
                    + 0.025 * sin(x * 5.7 + t * 0.8);
                return vec3(x, y + w * sin(twist), w * cos(twist));
            }

            void main() {
                vec3 p = surface(aGrid);
                vec3 du = surface(aGrid + vec2(0.002, 0.0)) - p;
                vec3 dv = surface(aGrid + vec2(0.0, 0.02)) - p;
                vec3 n = normalize(cross(du, dv));
                // Fresnel: faint face-on, bright where the sheet turns edge-on to the eye.
                vGlow = pow(1.0 - abs(n.z), 2.5);
                vLine = abs(aGrid.y * 2.0 - 1.0);
                // Fade toward the screen's sides.
                vEdge = smoothstep(0.0, 0.15, aGrid.x) * smoothstep(1.0, 0.85, aGrid.x);
                // Gentle perspective so nearer parts of the sheet sit a little lower. On a tall
                // portrait screen the same NDC height is many more pixels, so flatten it there.
                float persp = 1.0 / (1.0 + p.z * 0.35);
                gl_Position = vec4(p.x * persp, (p.y - 0.05) * persp * clamp(uAspect, 0.6, 1.0), 0.0, 1.0);
            }"""

        const val FS = """#version 300 es
            precision mediump float;
            in float vGlow;
            in float vEdge;
            in float vLine;
            uniform float uBrightness;
            out vec4 fragColor;
            void main() {
                float body = (0.05 + 0.6 * vGlow) * (1.0 - vLine * vLine * 0.6);
                // A fine bright line along each long edge of the sheet.
                float line = smoothstep(0.93, 0.995, vLine) * (0.35 + 0.65 * vGlow);
                float a = (body + line * 0.55) * vEdge * uBrightness;
                fragColor = vec4(vec3(a), 1.0);
            }"""
    }
}
