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
        // Continuous through every segment boundary, so scrolling never jumps.
        for (edge in listOf(-1f, 0f, 1f)) {
            assertEquals(layout.rowY(edge - 0.001f), layout.rowY(edge + 0.001f), 0.5f)
        }
        // Measured XMB geometry at 1280x720: focused row 144 below the bar, next one 116
        // further, then 53.5 apart; the nearest row above sits 113 over the bar.
        assertEquals(layout.anchorY + 144f, layout.rowY(0f), 0.01f)
        assertEquals(layout.anchorY + 260f, layout.rowY(1f), 0.01f)
        assertEquals(layout.anchorY + 313.5f, layout.rowY(2f), 0.01f)
        assertEquals(layout.anchorY - 113f, layout.rowY(-1f), 0.01f)
        assertEquals(layout.anchorY - 166.5f, layout.rowY(-2f), 0.01f)
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

    @Test fun nightnessFollowsThePs3Schedule() {
        assertEquals(1f, MonthPalette.nightness(0f), 0f)
        assertEquals(0.5f, MonthPalette.nightness(5.16611f / 2), 0.001f)
        assertEquals(0f, MonthPalette.nightness(12f), 0f)
        assertEquals(0.5f, MonthPalette.nightness((18.498f + 20.3312f) / 2), 0.001f)
        assertEquals(1f, MonthPalette.nightness(23.9f), 0f)
    }

    @Test fun octoberNightMatchesTheCapturedFrame() {
        // Real XMB, 20 Oct 23:57: black at the top, dark brown (#371c01) at the bottom.
        val c = MonthPalette.colors(9, 23.95f)
        assertTrue("top-left dark", c[0] < 0.02f && c[1] < 0.02f)
        assertEquals(0x37 / 255f, c[6], 0.04f)  // bottom-left red
        assertEquals(0x1c / 255f, c[7], 0.04f)  // bottom-left green
    }
}
