package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmbOptionsTest {
    private fun options(n: Int, initial: Int = 0) = XmbOptions(List(n) { OptionEntry("o$it") {} }, initial)

    @Test fun focusDoesNotWrap() {
        val o = options(3)
        assertFalse(o.move(-1))
        assertTrue(o.move(2))
        assertEquals(2, o.focus)
        assertFalse(o.move(1))
    }

    @Test fun initialFocusIsClamped() {
        assertEquals(2, options(3, initial = 9).focus)
        assertEquals(0, options(3, initial = -4).focus)
        assertEquals(0, options(0).focus)
    }

    @Test fun everyThemeColourHasAName() {
        assertEquals(12, MonthPalette.NAMES.size)
        assertEquals(12, MonthPalette.NAMES.toSet().size)
    }
}
