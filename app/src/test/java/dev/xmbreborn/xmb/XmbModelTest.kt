package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmbModelTest {
    private fun items(vararg ids: String) = ids.map { XmbItem(it, it) }

    private fun model() = XmbModel(listOf(
        XmbCategory("a", "A", Glyph.APPS, items("1", "2", "3")),
        XmbCategory("b", "B", Glyph.GAME, items("x", "y")),
        XmbCategory("c", "C", Glyph.MUSIC),
    ))

    @Test fun barDoesNotWrap() {
        val m = model()
        assertFalse(m.moveCategory(-1))
        assertTrue(m.moveCategory(5))
        assertEquals(2, m.focus)
        assertFalse(m.moveCategory(1))
    }

    @Test fun eachColumnRemembersItsRow() {
        val m = model()
        m.focused.moveFocus(2)
        m.moveCategory(1)
        m.focused.moveFocus(1)
        m.moveCategory(-1)
        assertEquals(2, m.focused.focus)
        assertEquals(1, m.categories[1].focus)
    }

    @Test fun emptyColumnIgnoresVerticalMoves() {
        val c = model().categories[2]
        assertFalse(c.moveFocus(1))
        assertEquals(null, c.focusedItem)
    }

    @Test fun replacingItemsKeepsFocusOnSameId() {
        val c = model().categories[0]
        c.moveFocus(1) // "2"
        c.replaceItems(items("0", "1", "2", "3"))
        assertEquals("2", c.focusedItem?.id)
        c.replaceItems(items("9"))
        assertEquals("9", c.focusedItem?.id)
    }

    @Test fun motionGlidesWithoutOvershootAndSettles() {
        val m = model()
        val motion = XmbMotion(m)
        m.moveCategory(2)
        var last = motion.category
        var frames = 0
        while (motion.step(1 / 60f)) {
            assertTrue(motion.category >= last)
            assertTrue(motion.category <= 2f)
            last = motion.category
            frames++
        }
        assertEquals(2f, motion.category)
        assertTrue("settles within half a second, took $frames frames", frames < 30)
    }

    @Test fun layoutPutsFocusedColumnAtAnchorAndRowsAroundBar() {
        val m = model()
        val motion = XmbMotion(m)
        val layout = XmbLayout(1280f, 720f)
        val cats = layout.categories(m, motion)
        val focused = cats.single { it.category == 0 }
        assertEquals(layout.anchorX, focused.x, 0.01f)
        assertEquals(1f, focused.focus, 0f)
        // Focused row hangs just below the bar; earlier rows sit above it.
        assertTrue(layout.rowY(0f) > layout.anchorY)
        assertTrue(layout.rowY(-1f) < layout.anchorY)
        // Continuous through the crossing, so scrolling never jumps.
        assertEquals(layout.rowY(-0.999f), layout.rowY(-1f), 0.5f)
        assertEquals(layout.rowY(-0.001f), layout.rowY(0f), 0.5f)
    }

    @Test fun onlyNearbyColumnsShowRows() {
        val m = model()
        val motion = XmbMotion(m)
        val rows = XmbLayout(1280f, 720f).items(m, motion)
        assertTrue(rows.all { it.category == 0 })
        assertEquals(3, rows.size)
    }

    @Test fun neighbouringColumnsNeverShowRowsTogether() {
        val m = model()
        val motion = XmbMotion(m)
        m.moveCategory(1)
        val layout = XmbLayout(1280f, 720f)
        while (motion.step(1 / 120f)) {
            val visible = layout.items(m, motion).filter { it.alpha > 0.01f }.map { it.category }.toSet()
            assertTrue("columns $visible visible at ${motion.category}", visible.size <= 1)
        }
    }

    @Test fun nightnessRampsAtDawnAndDusk() {
        assertEquals(1f, MonthPalette.nightness(2f), 0f)
        assertEquals(0.5f, MonthPalette.nightness(6f), 0.001f)
        assertEquals(0f, MonthPalette.nightness(12f), 0f)
        assertEquals(1f, MonthPalette.nightness(21f), 0f)
    }
}
