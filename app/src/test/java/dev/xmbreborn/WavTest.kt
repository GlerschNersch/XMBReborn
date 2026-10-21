package dev.xmbreborn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun wav(channels: Int, rate: Int, samples: ShortArray, bits: Int = 16, extraChunk: Boolean = false): ByteArray {
        val extra = if (extraChunk) 8 + 3 + 1 else 0 // odd-sized chunk plus its pad byte
        val b = ByteBuffer.allocate(44 + extra + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + extra + samples.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * 2).putShort((channels * 2).toShort()).putShort(bits.toShort())
        if (extraChunk) { b.put("LIST".toByteArray()).putInt(3).put(byteArrayOf(1, 2, 3, 0)) }
        b.put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test fun readsStereoPcm() {
        val pcm = Wav.parse(wav(2, 48000, shortArrayOf(1, -2, 300, -400)))!!
        assertEquals(48000, pcm.rate)
        assertEquals(2, pcm.channels)
        assertEquals(listOf<Short>(1, -2, 300, -400), pcm.samples.toList())
    }

    @Test fun skipsUnknownOddSizedChunks() {
        assertEquals(3, Wav.parse(wav(1, 22050, shortArrayOf(5, 6, 7), extraChunk = true))!!.samples.size)
    }

    @Test fun rejectsWhatItCantPlay() {
        assertNull(Wav.parse(wav(1, 48000, shortArrayOf(1), bits = 8)))
        assertNull(Wav.parse(ByteArray(10)))
        assertNull(Wav.parse("RIFF....WAVEjunk".toByteArray()))
    }
}
