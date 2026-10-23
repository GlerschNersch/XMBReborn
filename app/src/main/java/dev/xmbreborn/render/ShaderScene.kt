package dev.xmbreborn.render

import android.opengl.GLES30
import dev.xmbreborn.gl.Gl

/**
 * A backdrop drawn entirely by one full-screen fragment shader, given the time, the screen's
 * aspect, the month palette (as [Background] gets it) and the night dimming.
 */
open class ShaderScene(private val fragment: String) {
    private var program = 0
    private var uTime = 0
    private var uAspect = 0
    private var uLight = 0
    private var uColors = 0
    private var vao = 0

    fun init() {
        program = Gl.program(VS, fragment)
        uTime = GLES30.glGetUniformLocation(program, "uTime")
        uAspect = GLES30.glGetUniformLocation(program, "uAspect")
        uLight = GLES30.glGetUniformLocation(program, "uLight")
        uColors = GLES30.glGetUniformLocation(program, "uColors")
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
    }

    fun draw(time: Float, aspect: Float, colors: FloatArray, light: Float) {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(program)
        GLES30.glUniform1f(uTime, time)
        GLES30.glUniform1f(uAspect, aspect)
        GLES30.glUniform1f(uLight, light)
        GLES30.glUniform3fv(uColors, 5, colors, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        const val VS = """#version 300 es
            out vec2 vUv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }"""

        /** Uniforms, value noise and the month gradient, shared by the scenes' fragment shaders. */
        const val COMMON = """#version 300 es
            precision highp float;
            in vec2 vUv;
            uniform float uTime;
            uniform float uAspect;
            uniform float uLight;
            uniform vec3 uColors[5];
            out vec4 fragColor;

            float hash2(vec2 p) {
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }
            float noise2(vec2 p) {
                vec2 i = floor(p), f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash2(i), hash2(i + vec2(1.0, 0.0)), u.x),
                           mix(hash2(i + vec2(0.0, 1.0)), hash2(i + vec2(1.0, 1.0)), u.x), u.y);
            }
            float hash3(vec3 p) {
                p = fract(p * 0.3183099 + 0.1);
                p *= 17.0;
                return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
            }
            float noise3(vec3 x) {
                vec3 i = floor(x), f = fract(x);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x),
                               mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y),
                           mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x),
                               mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y), f.z);
            }
            /** The month gradient as Background draws it, without its centre bump. */
            vec3 palette(vec2 uv) {
                vec3 top = mix(uColors[0], uColors[1], uv.x);
                vec3 bottom = mix(uColors[2], uColors[3], uv.x);
                return mix(bottom, top, uv.y);
            }
        """
    }
}
