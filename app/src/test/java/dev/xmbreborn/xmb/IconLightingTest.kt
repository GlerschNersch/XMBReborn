package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IconLightingTest {
    /** A 64x64 square silhouette with a 16 px margin. */
    private fun square(): FloatArray = FloatArray(64 * 64) { i ->
        val x = i % 64; val y = i / 64
        if (x in 16 until 48 && y in 16 until 48) 1f else 0f
    }

    @Test fun flatFaceIsWhiteAndBevelsAreLitFromAbove() {
        val s = IconLighting.shade(square(), 64, 64)
        val centre = s[32 * 64 + 32]
        val top = s[17 * 64 + 32]
        val bottom = s[46 * 64 + 32]
        assertEquals(1f, centre, 0.06f)
        assertTrue("top bevel $top brighter than bottom bevel $bottom", top > bottom + 0.15f)
        assertTrue("bottom bevel $bottom visibly darker than the face", bottom < centre - 0.1f)
        assertTrue(s.all { it.isFinite() && it >= 0f })
    }

    @Test fun focusedIconOverOctoberDayMatchesTheCapturedFrame() {
        val bg = floatArrayOf(115f / 255, 94f / 255, 42f / 255)
        val tint = IconTint.of(bg)
        val face = IconLighting.shade(square(), 64, 64)[32 * 64 + 32]
        val a = IconTint.OPACITY
        val got = FloatArray(3) { c -> (bg[c] * (1 - a) + face.coerceAtMost(1f) * tint[c] * a) * 255 }
        val want = floatArrayOf(210f, 199f, 150f)
        for (c in 0 until 3) assertEquals("channel $c", want[c], got[c], 12f)
    }
}
