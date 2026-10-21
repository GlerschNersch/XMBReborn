package dev.xmbreborn

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AmbientSynthTest {
    @Test fun isQuietSafeAndNeverSilent() {
        val synth = AmbientSynth(24000)
        val buf = FloatArray(24000)
        // Two minutes, so every swell and the chord crossfade come round at least once.
        repeat(120) { second ->
            synth.render(buf, 1f)
            val peak = buf.maxOf { abs(it) }
            val rms = sqrt(buf.sumOf { (it * it).toDouble() } / buf.size)
            assertTrue("finite at ${second}s", buf.all { it.isFinite() })
            assertTrue("peak $peak at ${second}s stays well under full scale", peak < 0.5f)
            assertTrue("rms $rms at ${second}s is audible", rms > 0.002)
        }
    }

    @Test fun gainScalesTheOutput() {
        val a = FloatArray(4800).also { AmbientSynth(24000).render(it, 1f) }
        val b = FloatArray(4800).also { AmbientSynth(24000).render(it, 0.25f) }
        for (i in a.indices) assertTrue(abs(b[i] - a[i] * 0.25f) < 1e-6f)
    }

    /** The original per-sample synth, kept as the reference the faster one must still sound like. */
    private class Reference(private val rate: Int) {
        private class V(val freq: Double, val amp: Double, val period: Double, val phase: Double) { var p = 0.0 }
        private fun voices(vararg f: Double) = f.flatMapIndexed { i, hz ->
            val amp = 1.0 / (1 + i * 0.6); val period = 9.0 + i * 3.7
            listOf(V(hz, amp, period, i * 1.3), V(hz + 0.3, amp * 0.6, period * 1.31, i * 2.1))
        }
        private val a = voices(146.83, 220.0, 293.66, 369.99, 440.0, 659.26)
        private val b = voices(123.47, 185.0, 293.66, 329.63, 440.0, 554.37)
        private var t = 0.0; private var noise = 0x2545F491; private var lp1 = 0.0; private var lp2 = 0.0
        private fun sum(vs: List<V>, dt: Double): Double {
            var s = 0.0
            for (v in vs) {
                val sw = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * t / v.period + v.phase)
                s += kotlin.math.sin(v.p) * v.amp * sw * sw
                v.p += 2 * Math.PI * v.freq * dt; if (v.p > 2 * Math.PI) v.p -= 2 * Math.PI
            }
            return s / vs.size * 2.5
        }
        fun render(out: FloatArray) {
            val dt = 1.0 / rate; val lpK = 2 * Math.PI * 700.0 / rate
            for (i in out.indices) {
                val mix = 0.5 + 0.5 * kotlin.math.sin(2 * Math.PI * t / 47.0)
                var s = (1 - mix) * sum(a, dt) + mix * sum(b, dt)
                noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
                lp1 += lpK * (noise / 2147483648.0 - lp1); lp2 += lpK * (lp1 - lp2)
                s += lp2 * 0.9 * (0.6 + 0.4 * kotlin.math.sin(2 * Math.PI * t / 23.0))
                out[i] = (s * 0.11).toFloat(); t += dt
            }
        }
    }

    @Test fun fastSynthSoundsLikeTheReference() {
        val fast = AmbientSynth(24000)
        val ref = Reference(24000)
        val a = FloatArray(1024)
        val b = FloatArray(1024)
        var err = 0.0
        var energy = 0.0
        // 30 s in the launcher's real block size.
        repeat(24000 * 30 / 1024) {
            fast.render(a, 1f); ref.render(b)
            for (i in a.indices) { val d = (a[i] - b[i]).toDouble(); err += d * d; energy += b[i].toDouble() * b[i] }
        }
        val snrDb = 10 * kotlin.math.log10(energy / err)
        assertTrue("signal-to-difference $snrDb dB", snrDb > 40)
    }
}
