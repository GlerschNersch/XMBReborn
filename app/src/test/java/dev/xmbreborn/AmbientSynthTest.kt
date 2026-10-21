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
}
