package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionsScrollTest {
    private val step = 34f
    private val top = 300f
    private val bottom = 1050f

    private fun first(count: Int, focus: Int, measured: Float = 500f) =
        XmbLayout.optionsFirstY(measured, count, focus, step, top, bottom)

    @Test fun shortListSitsWhereMeasured() {
        assertEquals(500f, first(5, 0), 0f)
        assertEquals(500f, first(5, 4), 0f)
    }

    @Test fun listNearTheBottomIsPulledUp() {
        val y = first(12, 0, measured = 900f)
        assertEquals(bottom - 11.5f * step, y, 0.01f)
    }

    @Test fun longListKeepsTheFocusedRowOnScreen() {
        for (focus in 0 until 26) {
            val y = first(26, focus) + focus * step
            assertTrue("row $focus at $y", y in top..bottom)
        }
    }

    @Test fun longListLeavesNoGapAtEitherEnd() {
        assertEquals(top, first(26, 0), 0f)
        assertEquals(bottom, first(26, 25) + 25 * step, 0.01f)
    }
}
