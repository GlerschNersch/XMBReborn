package dev.xmbreborn.render

import android.opengl.GLES30
import dev.xmbreborn.gl.Gl

/**
 * The month gradient: a full-screen triangle blending four corner colours bilinearly, with the
 * centre colour pulled in by a soft radial bump (the PS3 gradients are not purely bilinear).
 */
class Background {
    private var program = 0
    private var uColors = 0
    private var uAspect = 0
    private var vao = 0

    fun init() {
        program = Gl.program(VS, FS)
        uColors = GLES30.glGetUniformLocation(program, "uColors")
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
    }

    /** [colors]: tl, tr, bl, br, centre as 15 RGB floats (see MonthPalette). */
    fun draw(colors: FloatArray, aspect: Float) {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        GLES30.glUniform3fv(uColors, 5, colors, 0)
        GLES30.glUniform1f(uAspect, aspect)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private companion object {
        const val VS = """#version 300 es
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }"""

        const val FS = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform vec3 uColors[5];
            uniform float uAspect;
            out vec4 fragColor;
            void main() {
                // vUv.y = 0 at the bottom of the screen.
                vec3 top = mix(uColors[0], uColors[1], vUv.x);
                vec3 bottom = mix(uColors[2], uColors[3], vUv.x);
                vec3 c = mix(bottom, top, vUv.y);
                vec3 bilinearCentre = (uColors[0] + uColors[1] + uColors[2] + uColors[3]) * 0.25;
                vec2 d = (vUv - 0.5) * vec2(uAspect, 1.0);
                float bump = 1.0 - smoothstep(0.0, 0.75, length(d));
                c += (uColors[4] - bilinearCentre) * bump;
                fragColor = vec4(c, 1.0);
            }"""
    }
}
