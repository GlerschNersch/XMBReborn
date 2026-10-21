package dev.xmbreborn

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal reader for 16-bit PCM RIFF/WAVE files (what the firmware tools in re/tools write). */
object Wav {
    class Pcm(val rate: Int, val channels: Int, val samples: ShortArray)

    /** Null if [bytes] isn't a 16-bit PCM WAVE with 1 or 2 channels. */
    fun parse(bytes: ByteArray): Pcm? {
        if (bytes.size < 12) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WAVE") return null
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var format = 0
        while (pos + 8 <= bytes.size) {
            val id = tag(bytes, pos)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > bytes.size) return null
            when (id) {
                "fmt " -> {
                    format = b.getShort(body).toInt()
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }
                "data" -> {
                    if (format != 1 || bits != 16 || channels !in 1..2 || rate <= 0) return null
                    val samples = ShortArray(size / 2) { i -> b.getShort(body + i * 2) }
                    return Pcm(rate, channels, samples)
                }
            }
            pos = body + size + (size and 1) // chunks are word-aligned
        }
        return null
    }

    private fun tag(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
}
