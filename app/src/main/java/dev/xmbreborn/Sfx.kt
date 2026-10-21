package dev.xmbreborn

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Short UI ticks synthesized at startup (our own sounds, no recorded assets): a soft high
 * "tick" for cursor moves, a brighter two-tone for opening something, a low one for back.
 */
class Sfx {
    enum class Kind { CURSOR, DECIDE, CANCEL }

    private val tracks = Kind.entries.associateWith { kind ->
        when (kind) {
            Kind.CURSOR -> track(tone(listOf(2600.0 to 1.0, 5200.0 to 0.25), 0.045, decay = 90.0, gain = 0.22))
            Kind.DECIDE -> track(tone(listOf(1800.0 to 1.0, 2700.0 to 0.6), 0.09, decay = 45.0, gain = 0.25))
            Kind.CANCEL -> track(tone(listOf(1100.0 to 1.0, 1650.0 to 0.4), 0.07, decay = 60.0, gain = 0.22))
        }
    }

    var enabled = true

    fun play(kind: Kind) {
        if (!enabled) return
        val t = tracks[kind] ?: return
        runCatching {
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    fun release() = tracks.values.forEach { it.release() }

    private fun tone(partials: List<Pair<Double, Double>>, seconds: Double, decay: Double, gain: Double): ShortArray {
        val n = (RATE * seconds).toInt()
        val total = partials.sumOf { it.second }
        return ShortArray(n) { i ->
            val t = i / RATE.toDouble()
            val attack = (t / 0.002).coerceAtMost(1.0)
            val s = partials.sumOf { (f, a) -> a * sin(2 * PI * f * t) } / total
            (s * attack * exp(-decay * t) * gain * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun track(pcm: ShortArray): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    private companion object {
        const val RATE = 48000
    }
}
