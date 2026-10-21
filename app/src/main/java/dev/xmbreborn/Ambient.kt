package dev.xmbreborn

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * A quiet ambient bed under the bar. The PS3 has no such loop (its only long sound is the boot
 * chime), so this is our own: two soft chords that slowly trade places, each voice swelling on
 * its own slow cycle, over a breath of filtered noise. Generated live, nothing recorded.
 */
class AmbientSynth(private val rate: Int) {
    private class Voice(val freq: Double, val amp: Double, val period: Double, val phase: Double) {
        var p = 0.0
    }

    // D major 9 and B minor 11, voiced open from D3 (phone speakers lose most of what's lower);
    // each note doubled 0.3 Hz sharp for a slow chorus.
    private val chordA = voices(146.83, 220.0, 293.66, 369.99, 440.0, 659.26)
    private val chordB = voices(123.47, 185.0, 293.66, 329.63, 440.0, 554.37)
    private var t = 0.0
    private var noise = 0x2545F491
    private var lp1 = 0.0
    private var lp2 = 0.0

    private fun voices(vararg f: Double) = f.flatMapIndexed { i, hz ->
        val amp = 1.0 / (1 + i * 0.6)
        val period = 9.0 + i * 3.7
        listOf(Voice(hz, amp, period, i * 1.3), Voice(hz + 0.3, amp * 0.6, period * 1.31, i * 2.1))
    }

    /** Fills [out] with the next samples, -1..1 scaled by [gain]. */
    fun render(out: FloatArray, gain: Float) {
        val dt = 1.0 / rate
        val lpK = 2 * PI * 700.0 / rate
        for (i in out.indices) {
            val mix = 0.5 + 0.5 * sin(2 * PI * t / 47.0)
            var s = (1 - mix) * sum(chordA, dt) + mix * sum(chordB, dt)
            noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
            lp1 += lpK * (noise / 2147483648.0 - lp1)
            lp2 += lpK * (lp1 - lp2)
            s += lp2 * 0.9 * (0.6 + 0.4 * sin(2 * PI * t / 23.0))
            out[i] = (s * 0.11 * gain).toFloat()
            t += dt
        }
    }

    private fun sum(vs: List<Voice>, dt: Double): Double {
        var s = 0.0
        for (v in vs) {
            val swell = 0.5 + 0.5 * sin(2 * PI * t / v.period + v.phase)
            s += sin(v.p) * v.amp * swell * swell
            v.p += 2 * PI * v.freq * dt
            if (v.p > 2 * PI) v.p -= 2 * PI
        }
        return s / vs.size * 2.5
    }
}

/** Streams [AmbientSynth] on its own thread, fading in over a few seconds and out quickly. */
class Ambient {
    @Volatile private var target = 0f
    private var thread: Thread? = null

    @Synchronized fun start() {
        target = 1f
        if (thread?.isAlive == true) return
        thread = Thread(::run, "xmb-ambient").apply { isDaemon = true; start() }
    }

    fun stop() { target = 0f }

    private fun run() {
        val synth = AmbientSynth(RATE)
        val buf = FloatArray(BLOCK)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(BLOCK * 4 * 4)
            .build()
        try {
            track.play()
            var level = 0f
            while (true) {
                val goal = target
                // ~3 s fade in, ~0.4 s fade out, per block of ~43 ms.
                level = if (goal > level) minOf(goal, level + BLOCK / (RATE * 3f)) else maxOf(goal, level - BLOCK / (RATE * 0.4f))
                if (goal == 0f && level == 0f) break
                synth.render(buf, level)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            track.stop()
            track.release()
            synchronized(this) { if (thread === Thread.currentThread()) thread = null }
        }
        // A start() that raced the fade-out sees the thread still alive; pick it up here.
        if (target > 0f) start()
    }

    private companion object {
        const val RATE = 24000
        const val BLOCK = 1024
    }
}
