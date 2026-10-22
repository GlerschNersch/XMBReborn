package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTest {
    private fun leaf(id: String) = XmbItem(id, id)
    private fun folder(id: String, vararg kids: String) = XmbItem(id, id, children = kids.map(::leaf))

    private fun settings() = listOf(folder("theme", "color", "size", "clock"), folder("sound", "clicks", "pad"), leaf("ps3"))

    private fun column() = XmbCategory("settings", "Settings", Glyph.SETTINGS, settings())

    @Test fun openShowsTheFolderAndCloseReturnsToIt() {
        val c = column()
        c.moveFocus(1)
        assertTrue(c.open())
        assertEquals(1, c.depth)
        assertEquals(listOf("clicks", "pad"), c.items.map { it.id })
        assertEquals(0, c.focus)
        assertEquals(listOf("sound"), c.crumbs.map { it.id })
        c.moveFocus(1)
        assertTrue(c.close())
        assertEquals(0, c.depth)
        assertEquals("sound", c.focusedItem?.id)
        assertFalse(c.close())
    }

    @Test fun leavesAndEmptyFoldersDontOpen() {
        val c = XmbCategory("x", "X", Glyph.APPS, listOf(leaf("a"), XmbItem("empty", "empty", children = emptyList())))
        assertFalse(c.open())
        c.moveFocus(1)
        assertFalse(c.open())
        assertEquals(0, c.depth)
    }

    @Test fun eachFolderRemembersNothingButReopensAtTheTop() {
        val c = column()
        c.open(); c.moveFocus(2); c.close()
        c.open()
        assertEquals("color", c.focusedItem?.id)
    }

    @Test fun refreshKeepsTheOpenFolderAndRow() {
        val c = column()
        c.open(); c.moveFocus(2) // theme > clock
        // A setting's value changed: same ids, new items.
        c.replaceItems(settings())
        assertEquals(1, c.depth)
        assertEquals("clock", c.focusedItem?.id)
        assertEquals("theme", c.crumbs.single().id)
    }

    @Test fun refreshClosesAFolderThatIsGone() {
        val c = column()
        c.moveFocus(1); c.open()
        c.replaceItems(listOf(folder("theme", "color"), leaf("ps3")))
        assertEquals(0, c.depth)
        assertEquals(1, c.focus) // where "sound" was
    }

    @Test fun reloadAdoptsTheOldPath() {
        val old = column()
        old.open(); old.moveFocus(1)
        val fresh = column()
        fresh.keepPathOf(old)
        assertEquals(listOf("theme"), fresh.crumbs.map { it.id })
        assertEquals("size", fresh.focusedItem?.id)
    }

    @Test fun closeToGoesBackSeveralLevels() {
        val c = XmbCategory("x", "X", Glyph.APPS, listOf(XmbItem("a", "a", children = listOf(folder("b", "c")))))
        assertTrue(c.open()); assertTrue(c.open())
        assertEquals(2, c.depth)
        assertTrue(c.closeTo(0))
        assertEquals(0, c.depth)
        assertFalse(c.closeTo(0))
    }

    @Test fun barSlidesLeftAndTheFolderListTakesTheColumnsPlace() {
        val m = XmbModel(listOf(column(), XmbCategory("music", "Music", Glyph.MUSIC, listOf(leaf("song")))))
        val motion = XmbMotion(m)
        val layout = XmbLayout(1280f, 720f)
        val spacing = XmbMetrics.CATEGORY_SPACING * layout.unit
        m.focused.open()
        motion.snap()
        val cats = layout.categories(m, motion)
        val items = layout.items(m, motion)
        // The column's icon has moved one column left; other columns are hidden.
        assertEquals(layout.anchorX - spacing, cats.single { it.category == 0 }.x, 0.01f)
        assertTrue(cats.none { it.category == 1 })
        // The folder's rows sit where the column's own did, its icon as a breadcrumb beside them.
        val crumb = items.single { it.isCrumb }
        assertEquals(layout.anchorX - spacing, crumb.x, 0.01f)
        assertEquals(layout.rowY(0f), crumb.y, 0.01f)
        val rows = items.filter { it.isRow }
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.category == 0 && it.x == layout.anchorX })
    }

    @Test fun portraitKeepsTheBreadcrumbOnScreen() {
        val m = XmbModel(listOf(column()))
        val motion = XmbMotion(m)
        val layout = XmbLayout(1080f, 2400f, scale = 1.25f)
        m.focused.open()
        motion.snap()
        val items = layout.items(m, motion)
        val crumb = items.single { it.isCrumb }
        assertTrue("breadcrumb at ${crumb.x}", crumb.x - crumb.size / 2 >= 0f)
        // The folder's rows start just past the breadcrumb, not a whole column on.
        val row = items.first { it.isRow }
        assertEquals(crumb.x + layout.folderStep, row.x, 0.01f)
        assertTrue(row.x - XmbMetrics.ITEM_ICON_FOCUSED / 2 * layout.unit > crumb.x + crumb.size / 2)
    }

    @Test fun openingGlidesRatherThanJumping() {
        val m = XmbModel(listOf(column()))
        val motion = XmbMotion(m)
        m.focused.open()
        var last = motion.depth(0)
        var frames = 0
        while (motion.step(1 / 60f)) {
            assertTrue(motion.depth(0) >= last)
            last = motion.depth(0)
            frames++
        }
        assertEquals(1f, motion.depth(0))
        assertTrue("took $frames frames", frames in 5..30)
    }
}
