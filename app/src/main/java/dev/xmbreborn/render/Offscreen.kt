package dev.xmbreborn.render

import android.opengl.GLES30
import dev.xmbreborn.gl.Gl

/**
 * A reduced-resolution render target for the shader-heavy backdrops: they draw into it, then it's
 * stretched over the screen with linear filtering. Their soft, foggy look loses nothing at half
 * size, and the phone does a quarter of the work.
 */
class Offscreen(private val scale: Float = 0.5f) {
    private var fbo = 0
    private var tex = 0
    private var w = 0
    private var h = 0
    private var program = 0
    private var vao = 0
    private val viewport = IntArray(4)

    fun init() {
        // A new GL context: the old target died with the old one.
        fbo = 0; tex = 0; w = 0; h = 0
        program = Gl.program(VS, FS)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
    }

    /** Points drawing at the target (sized for the current viewport); [end] puts it on screen. */
    fun begin() {
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        val tw = (viewport[2] * scale).toInt().coerceAtLeast(1)
        val th = (viewport[3] * scale).toInt().coerceAtLeast(1)
        if (tw != w || th != h) allocate(tw, th)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
    }

    fun end() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun allocate(tw: Int, th: Int) {
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        Gl.deleteTexture(tex)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0); tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        w = tw; h = th
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
            precision mediump float;
            in vec2 vUv;
            uniform sampler2D uTex;
            out vec4 fragColor;
            void main() { fragColor = vec4(texture(uTex, vUv).rgb, 1.0); }"""
    }
}
