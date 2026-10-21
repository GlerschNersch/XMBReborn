package dev.xmbreborn

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * UI clicks. If the user has supplied their own PS3 sound files (extracted from their firmware
 * with re/tools/rco.py; [ownSound] finds one by name), those play; nothing of Sony's ships in the app. Otherwise we
 * synthesize our own, shaped after the PS3's (docs/RE_NOTES.md): one 40 ms click for cursor,
 * decide and options, centred near 6.8 kHz with nearly all its energy in the first 4 ms;
 * cancel is the same click a little lower and quieter.
 */
class Sfx(ownSound: (String) -> File? = { null }) {
    enum class Kind(val file: String) {
        CURSOR("snd_cursor.wav"), DECIDE("snd_decide.wav"), CANCEL("snd_cancel.wav"), OPTION("snd_option.wav")
    }

    /** Kinds playing the user's own files rather than our synthesized clicks. */
    val fromFiles = mutableSetOf<Kind>()

    private val tracks = Kind.entries.associateWith { kind ->
        ownSound(kind.file)?.let(::load)?.also { fromFiles += kind } ?: when (kind) {
            Kind.CURSOR, Kind.DECIDE, Kind.OPTION -> track(click(6840.0, upper = 1.30, lower = 0.712, peak = 0.16))
            Kind.CANCEL -> track(click(6450.0, upper = 1.30, lower = 0.0, peak = 0.08))
        }
    }

    private fun load(f: File): AudioTrack? {
        if (!f.isFile || f.length() > MAX_FILE) return null
        val pcm = runCatching { Wav.parse(f.readBytes()) }.getOrNull() ?: return null
        if (pcm.samples.isEmpty()) return null
        return runCatching { track(pcm.samples, pcm.rate, pcm.channels) }.getOrNull()
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

    /**
     * A struck, quickly damped partial plus two faint neighbours (-17 and -19 dB, as measured):
     * a fast 2.7 ms decay carries the click, a quieter 12 ms one leaves the short tail.
     */
    private fun click(freq: Double, upper: Double, lower: Double, peak: Double): ShortArray {
        val n = (RATE * 0.040).toInt()
        return ShortArray(n) { i ->
            val t = i / RATE.toDouble()
            val attack = (t / 0.0004).coerceAtMost(1.0)
            val env = attack * (0.8 * exp(-t / 0.0027) + 0.2 * exp(-t / 0.012))
            val s = sin(2 * PI * freq * t) +
                0.14 * sin(2 * PI * freq * upper * t) +
                (if (lower > 0) 0.11 * sin(2 * PI * freq * lower * t) else 0.0)
            (s / 1.25 * env * peak * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private fun track(pcm: ShortArray, rate: Int = RATE, channels: Int = 1): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        return t
    }

    private companion object {
        const val RATE = 48000
        /** UI sounds are tiny; refuse anything big enough to be a mistake. */
        const val MAX_FILE = 2L shl 20
    }
}
