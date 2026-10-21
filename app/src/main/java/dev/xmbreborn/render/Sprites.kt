package dev.xmbreborn.render

import android.opengl.GLES30
import dev.xmbreborn.gl.Gl

/**
 * Textured quads in screen pixels (origin top-left). Textures are premultiplied, so blending
 * is ONE / ONE_MINUS_SRC_ALPHA and opacity scales all four channels.
 */
class Sprites {
    private var program = 0
    private var uRect = 0
    private var uScreen = 0
    private var uAlpha = 0
    private var vao = 0
    private var screenW = 1f
    private var screenH = 1f

    fun init() {
        program = Gl.program(VS, FS)
        uRect = GLES30.glGetUniformLocation(program, "uRect")
        uScreen = GLES30.glGetUniformLocation(program, "uScreen")
        uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
    }

    fun begin(width: Float, height: Float) {
        screenW = width; screenH = height
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(program)
        GLES30.glUniform2f(uScreen, width, height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindVertexArray(vao)
    }

    /** Draws [texture] into the rectangle with top-left (x, y). */
    fun draw(texture: Int, x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        if (alpha <= 0.003f || texture == 0) return
        if (x > screenW || y > screenH || x + w < 0f || y + h < 0f) return
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glUniform4f(uRect, x, y, w, h)
        GLES30.glUniform1f(uAlpha, alpha)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun end() {
        GLES30.glBindVertexArray(0)
    }

    private companion object {
        const val VS = """#version 300 es
            uniform vec4 uRect;
            uniform vec2 uScreen;
            out vec2 vUv;
            void main() {
                vec2 corner = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
                vUv = corner;
                vec2 px = uRect.xy + corner * uRect.zw;
                gl_Position = vec4(px.x / uScreen.x * 2.0 - 1.0, 1.0 - px.y / uScreen.y * 2.0, 0.0, 1.0);
            }"""

        const val FS = """#version 300 es
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTex;
            uniform float uAlpha;
            out vec4 fragColor;
            void main() {
                fragColor = texture(uTex, vUv) * uAlpha;
            }"""
    }
}
